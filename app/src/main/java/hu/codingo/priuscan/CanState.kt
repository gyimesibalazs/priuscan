package hu.codingo.priuscan

import org.json.JSONObject

/** Egy szenzormezo leiroja a megjeleniteshez. */
data class Field(
    val key: String,
    val label: String,
    val unit: String = "",
    val decimals: Int = 0,
)

/** A megjelenitesi sorrend es csoportositas - a fo kepernyo ebbol epul.
 *  A kulcsok a prius_parse.h emit_json() kimenetevel egyeznek (v2 config). */
object Fields {
    val groups: List<Pair<String, List<Field>>> = listOf(
        "Motor" to listOf(
            Field("ct", "Hűtővíz", "°C"),
            Field("rpm", "Fordulat", "rpm"),
            Field("spd", "Sebesség", "km/h"),
            Field("load", "Terhelés", "%"),
            Field("maf", "MAF", "g/s", 1),
            Field("map", "Szívócső nyomás", "kPa"),
            Field("iat", "Szívólevegő", "°C"),
            Field("thr", "Fojtószelep", "%"),
            Field("pedal", "Gázpedál", "%"),
            Field("fuel", "Fogyasztás", "l/h", 1),
            Field("engNm", "Motornyomaték", "Nm"),
            Field("injml", "Befecskendezés", "ml", 3),
            Field("run", "Futási idő", "s"),
        ),
        "Hibrid hajtás" to listOf(
            Field("mg1t", "MG1 hőfok", "°C"),
            Field("mg1r", "MG1 fordulat", "rpm"),
            Field("mg1q", "MG1 nyomaték", "Nm"),
            Field("mg2t", "MG2 hőfok", "°C"),
            Field("mg2r", "MG2 fordulat", "rpm"),
            Field("mg2q", "MG2 nyomaték", "Nm"),
            Field("inv1", "Inverter MG1", "°C"),
            Field("inv2", "Inverter MG2", "°C"),
            Field("btu", "Boost konv. felső", "°C"),
            Field("btl", "Boost konv. alsó", "°C"),
            Field("vl", "VL feszültség", "V"),
            Field("vh", "VH feszültség", "V"),
            Field("invct", "Inverter hűtővíz", "°C"),
            Field("invwp", "Inverter vízpumpa", "rpm"),
        ),
        "Hibrid akku" to listOf(
            Field("soc", "Töltöttség", "%"),
            Field("hvA", "Pack áram", "A", 1),
            Field("hvdis", "Kisütési limit", "kW", 1),
            Field("hvchg", "Töltési limit", "kW", 1),
            Field("bmin", "Blokk min", "V", 2),
            Field("bmax", "Blokk max", "V", 2),
            Field("blkD", "Blokk delta", "V", 2),
            Field("weakB", "Leggyengébb blokk"),
            Field("maxR", "Max belső ellenállás", "mΩ"),
            Field("hvAir", "Akku hűtőlevegő", "°C"),
            Field("tb1", "TB1", "°C"),
            Field("tb2", "TB2", "°C"),
            Field("tb3", "TB3", "°C"),
            Field("tHot", "Forró akku idő"),
        ),
        "Klíma" to listOf(
            Field("cabin", "Kabinhőfok", "°C"),
            Field("setT", "Beállított hőfok", "°C", 1),
            Field("comp", "Kompresszor", "rpm"),
            Field("evap", "Párologtató", "°C"),
            Field("acw", "A/C fogyasztás", "W"),
            Field("solar", "Napszenzor"),
            Field("acAmb", "Külső (klíma)", "°C"),
            Field("blower", "Ventilátor szint"),
            Field("acPress", "Hűtőközeg nyomás", "MPa", 2),
        ),
        "Menet / fék" to listOf(
            Field("wFL", "Kerék BE", "km/h", 1),
            Field("wFR", "Kerék JE", "km/h", 1),
            Field("wRL", "Kerék BH", "km/h", 1),
            Field("wRR", "Kerék JH", "km/h", 1),
            Field("wDif", "Első kerék eltérés", "%", 2),
            Field("gLat", "Oldalgyorsulás", "m/s²", 2),
            Field("gFwd", "Hosszgyorsulás", "m/s²", 2),
            Field("steer", "Kormányszög", "°"),
            Field("brkP", "Féknyomás", "V", 2),
        ),
        "Karbantartás / állapot" to listOf(
            Field("bodyV", "12V (body)", "V", 1),
            Field("fuelIn", "Üzemanyagszint", "L", 1),
            Field("oilDist", "Olajcsere óta", "km"),
            Field("battFan", "Akku hűtőventilátor", "%"),
            Field("dtcCur", "Aktív hibakódok"),
            Field("dtcHist", "Korábbi hibakódok"),
        ),
        "Egyéb" to listOf(
            Field("vbat", "12V akku", "V", 2),
            Field("amb", "Külső hőfok", "°C"),
        ),
    )
}


/** Egy beerkezett allapot-pillanatkep. */
class CanState(json: JSONObject?) {

    val values = HashMap<String, Double>()
    val ts = System.currentTimeMillis()

    init {
        if (json != null) {
            for (key in json.keys()) {
                // a firmware a HV blokkokat b01..b14 kulcskent kuldi (nem tombkent)
                val v = json.optDouble(key)
                if (!v.isNaN()) values[key] = v
            }
        }
    }

    fun d(key: String): Double? = values[key]
    fun i(key: String): Int = values[key]?.toInt() ?: 0

    val coolant: Double? get() = d("ct")
    val wpWarn: Int get() = i("wpW")
    val cellWarn: Int get() = i("cellW")
    val doorMask: Int get() = i("door")
    val gear: Int get() = values["gear"]?.toInt() ?: -1

    companion object {
        val EMPTY = CanState(null)
        val GEARS = arrayOf("P", "R", "N", "D", "B")
    }
}
