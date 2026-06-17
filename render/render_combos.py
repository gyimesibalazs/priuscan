import bpy, sys, math, itertools
from mathutils import Matrix, Vector

argv = sys.argv[sys.argv.index("--")+1:]
GLB, OUTDIR = argv[0], argv[1]

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=GLB)
scene = bpy.context.scene
meshes = [o for o in scene.objects if o.type == 'MESH']

def find(name):
    for o in meshes:
        if name.lower() in o.name.lower():
            return o
    return None

# glTF (Y-up) -> Blender (Z-up): (x,y,z)->(x,-z,y) == Rotation(90deg, X)
C = Matrix.Rotation(math.radians(90), 4, 'X'); Cinv = C.inverted()
orig = {o.name: o.matrix_world.copy() for o in meshes}

def reset_all():
    for o in meshes:
        o.matrix_world = orig[o.name].copy()

def open_part(obj, pivot, axis, deg):
    P = Vector(pivot)
    R = Matrix.Translation(P) @ Matrix.Rotation(math.radians(deg), 4, Vector(axis)) @ Matrix.Translation(-P)
    obj.matrix_world = (C @ R @ Cinv) @ obj.matrix_world

# door-maszk bit -> (mesh, pivot, axis, deg)  [Prius3DView szerint]
#   0x80 vezeto -> Door_fr,  0x40 utas -> Door_fl,
#   0x08 bal hatso -> Door_rl, 0x04 jobb hatso -> Door_rr, 0x01 csomagtarto -> Trunk
BITS = [
 (0x80, "Door_fr", (-3.320, 4.6, -5.326), (0,1,0), -62),
 (0x40, "Door_fl", ( 3.849, 4.6, -5.326), (0,1,0),  62),
 (0x08, "Door_rl", ( 3.754, 4.6, -0.654), (0,1,0),  62),
 (0x04, "Door_rr", (-3.225, 4.6, -0.654), (0,1,0), -62),
 (0x01, "Trunk",   ( 0.0, 6.974, 3.463),  (1,0,0), -72),
]

def world_bbox():
    mn = Vector(( 1e9, 1e9, 1e9)); mx = Vector((-1e9,-1e9,-1e9))
    for o in meshes:
        for c in o.bound_box:
            w = o.matrix_world @ Vector(c)
            for i in range(3):
                mn[i] = min(mn[i], w[i]); mx[i] = max(mx[i], w[i])
    return mn, mx

# keretezes az OSSZES ajto nyitott allapotanak befoglalojara -> semmi sem vagodik le
reset_all()
for bit, mesh, pivot, axis, deg in BITS:
    open_part(find(mesh), pivot, axis, deg)
mn, mx = world_bbox(); center = (mn+mx)*0.5; size = mx-mn
reset_all()

cam_data = bpy.data.cameras.new("Cam"); cam_data.type = 'ORTHO'
cam_data.ortho_scale = max(size.x, size.y) * 1.12   # kis margo
cam = bpy.data.objects.new("Cam", cam_data); scene.collection.objects.link(cam)
cam.location = (center.x, center.y, center.z + max(size)*4 + 10)
cam.rotation_euler = (0, 0, 0)
scene.camera = cam

world = bpy.data.worlds.new("W"); scene.world = world; world.use_nodes = True
world.node_tree.nodes["Background"].inputs[0].default_value = (0.30, 0.32, 0.35, 1)
world.node_tree.nodes["Background"].inputs[1].default_value = 0.6
def add_sun(n, rot, e):
    d = bpy.data.lights.new(n,'SUN'); d.energy=e; d.angle=math.radians(3)
    o = bpy.data.objects.new(n,d); o.rotation_euler=rot; scene.collection.objects.link(o)
add_sun("Key", (math.radians(35), math.radians(15), math.radians(20)), 4.0)
add_sun("Fill",(math.radians(-25),math.radians(-20),math.radians(200)),1.6)

scene.render.engine = 'CYCLES'; scene.cycles.device = 'CPU'
scene.cycles.samples = 64; scene.cycles.use_denoising = True
scene.render.film_transparent = True
scene.render.image_settings.file_format = 'PNG'
scene.render.image_settings.color_mode = 'RGBA'
# fele felbontas: a nagyobbik oldal ~480 px
if size.x >= size.y:
    scene.render.resolution_x = 480
    scene.render.resolution_y = int(round(480 * (size.y/size.x)))
else:
    scene.render.resolution_y = 480
    scene.render.resolution_x = int(round(480 * (size.x/size.y)))

# minden 32 kombinacio: a maszk-erteke a fajlnev (hex)
for r in range(len(BITS)+1):
    for combo in itertools.combinations(BITS, r):
        reset_all()
        mask = 0
        for bit, mesh, pivot, axis, deg in combo:
            mask |= bit
            open_part(find(mesh), pivot, axis, deg)
        scene.render.filepath = f"{OUTDIR}/car_{mask:02x}.png"
        bpy.ops.render.render(write_still=True)

print("RENDER DONE")
