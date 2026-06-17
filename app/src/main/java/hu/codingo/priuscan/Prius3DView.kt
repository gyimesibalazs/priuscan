package hu.codingo.priuscan

import android.content.Context
import android.view.Choreographer
import android.view.SurfaceView
import android.widget.FrameLayout
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.utils.Float3
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import com.google.android.filament.utils.rotation
import com.google.android.filament.utils.translation
import com.google.android.filament.utils.transpose
import java.nio.ByteBuffer

/**
 * A feltoltott Prius GLB modell renderelese Filamenttel, az ajtok es a
 * csomagterajto zsanerkoruli animalt nyitasaval.
 *
 * A pivotok az eredeti OBJ-bol szamolt zsanerpontok (modell-terben):
 * a forgatas T(p) * R(tengely, szog) * T(-p) transzformmal tortenik, igy
 * nem kellett a mesh-eket atpivotalni.
 *
 * Modell: "MODEL BY TAI0504" (a csomag CREDIT.txt-je szerint).
 * Tengelyek: Y=fel, -Z=orr, +X=bal oldal.
 */
class Prius3DView(ctx: Context) : FrameLayout(ctx) {

    companion object {
        init { Utils.init() }
        private const val ANIM_DEG_PER_FRAME = 3.5f
    }

    /** Csomagter bit a door maszkban - dump sessionbol verifikalando! */
    var hatchBit: Int = 0x01

    private class Part(
        val name: String,
        val pivot: Float3,
        val axis: Float3,
        val openDeg: Float,
    ) {
        var entity = 0
        var current = 0f
        var target = 0f
    }

    private val parts = listOf(
        Part("Door_fl", Float3(3.849f, 4.6f, -5.326f), Float3(0f, 1f, 0f), 62f),
        Part("Door_fr", Float3(-3.320f, 4.6f, -5.326f), Float3(0f, 1f, 0f), -62f),
        Part("Door_rl", Float3(3.754f, 4.6f, -0.654f), Float3(0f, 1f, 0f), 62f),
        Part("Door_rr", Float3(-3.225f, 4.6f, -0.654f), Float3(0f, 1f, 0f), -62f),
        Part("Trunk", Float3(0f, 6.974f, 3.463f), Float3(1f, 0f, 0f), -72f),
        // motorhazteto: CAN bit egyelore nincs hozza, de a resz animalhato
        Part("Hood", Float3(0f, 5.399f, -6.848f), Float3(1f, 0f, 0f), 45f),
    )

    private val surfaceView = SurfaceView(ctx)
    private val viewer: ModelViewer
    private val choreographer = Choreographer.getInstance()

    init {
        addView(surfaceView)
        viewer = ModelViewer(
            surfaceView,
            manipulator = Manipulator.Builder()
                // teljesen felulrol, az orr (-Z) a kepernyon felfele:
                // a kamera "up" vektora -Z; a pici z-offset a fuggoleges
                // tengely-degeneracio elkerulesere kell
                .orbitHomePosition(0f, 2.6f, -0.02f)
                .targetPosition(0f, 0f, 0f)
                .upVector(0f, 0f, -1f)
                .build(Manipulator.Mode.ORBIT)
        )
        context.assets.open("prius.glb").use { ins ->
            viewer.loadModelGlb(ByteBuffer.wrap(ins.readBytes()))
        }
        viewer.transformToUnitCube()
        viewer.asset?.let { asset ->
            for (p in parts) p.entity = asset.getFirstEntityByName(p.name)
        }
        setupLights()
    }

    private fun setupLights() {
        val engine = viewer.engine
        val sun = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.98f, 0.95f)
            .intensity(70_000f)
            .direction(-0.5f, -1.0f, 0.6f)
            .castShadows(false)
            .build(engine, sun)
        viewer.scene.addEntity(sun)
        val fill = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(0.9f, 0.93f, 1.0f)
            .intensity(25_000f)
            .direction(0.6f, -0.4f, -0.7f)
            .castShadows(false)
            .build(engine, fill)
        viewer.scene.addEntity(fill)
        // egyenletes ambiens 1 savos SH-val, IBL textura nelkul
        viewer.scene.indirectLight = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(0.55f, 0.57f, 0.60f))
            .intensity(22_000f)
            .build(engine)
    }

    /** A CAN door maszkbol allitja a celszogeket. */
    fun setMask(mask: Int) {
        val open = mapOf(
            // a modell fl/fr elnevezese forditott a valos oldalakhoz kepest:
            // 0x80 (vezeto/bal) = Door_fr mesh, 0x40 (utas/jobb) = Door_fl mesh
            "Door_fr" to (mask and 0x80 != 0),
            "Door_fl" to (mask and 0x40 != 0),
            "Door_rl" to (mask and 0x04 != 0),
            "Door_rr" to (mask and 0x04 != 0),
            "Trunk" to (mask and hatchBit != 0),
            "Hood" to false,
        )
        for (p in parts) p.target = if (open[p.name] == true) p.openDeg else 0f
    }

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            choreographer.postFrameCallback(this)
            val tm = viewer.engine.transformManager
            for (p in parts) {
                if (p.entity == 0) continue
                if (p.current != p.target) {
                    val d = (p.target - p.current)
                        .coerceIn(-ANIM_DEG_PER_FRAME, ANIM_DEG_PER_FRAME)
                    p.current += d
                }
                // T(pivot) * R * T(-pivot); ha az ajtok rossz pont korul
                // keringenenek, a transpose() elhagyasa az elso gyanusitott
                val m = translation(p.pivot) *
                        rotation(p.axis, p.current) *
                        translation(Float3(-p.pivot.x, -p.pivot.y, -p.pivot.z))
                tm.setTransform(tm.getInstance(p.entity), transpose(m).toFloatArray())
            }
            viewer.render(frameTimeNanos)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        choreographer.postFrameCallback(frameCb)
    }

    override fun onDetachedFromWindow() {
        choreographer.removeFrameCallback(frameCb)
        super.onDetachedFromWindow()
    }
}
