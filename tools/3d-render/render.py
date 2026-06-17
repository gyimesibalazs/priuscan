import os
os.environ['PYOPENGL_PLATFORM'] = 'egl'
import numpy as np
import trimesh
import pyrender
from PIL import Image

BASE = '/home/claude/prius3d/Prius'

# ---- MTL: szin anyagonkent (Kd vagy a textura atlagszine) ----
mat_color = {}
cur = None
for line in open(f'{BASE}/Prius.mtl'):
    t = line.split()
    if not t:
        continue
    if t[0] == 'newmtl':
        cur = t[1]
        mat_color[cur] = (0.7, 0.7, 0.7)
    elif t[0] == 'Kd' and cur:
        mat_color[cur] = tuple(float(x) for x in t[1:4])
    elif t[0] == 'map_Kd' and cur:
        try:
            img = Image.open(f'{BASE}/{t[1]}').convert('RGB')
            img.thumbnail((64, 64))
            a = np.asarray(img).reshape(-1, 3).mean(0) / 255.0
            mat_color[cur] = tuple(a)
        except Exception:
            pass

# ---- OBJ: vertexek + (objektum, anyag) szerinti haromszogek ----
verts = []
objs = {}          # name -> list of (tri_indices, material)
cur_o, cur_m = None, None
for line in open(f'{BASE}/Prius.obj'):
    if line.startswith('v '):
        verts.append([float(x) for x in line.split()[1:4]])
    elif line.startswith('o '):
        cur_o = line.split(None, 1)[1].strip()
        objs[cur_o] = []
    elif line.startswith('usemtl'):
        cur_m = line.split()[1]
    elif line.startswith('f ') and cur_o:
        idx = [int(tok.split('/')[0]) - 1 for tok in line.split()[1:]]
        for k in range(1, len(idx) - 1):   # fan-triangulalas
            objs[cur_o].append(((idx[0], idx[k], idx[k + 1]), cur_m))

V = np.array(verts, dtype=np.float64)

# ---- trimesh objektumonkent, face-szinekkel ----
meshes = {}
for name, faces in objs.items():
    F = np.array([f for f, _ in faces])
    C = np.array([[*(np.clip(mat_color.get(m, (0.7,) * 3), 0, 1) * np.array([255, 255, 255])), 255]
                  for _, m in faces], dtype=np.uint8)
    m = trimesh.Trimesh(vertices=V, faces=F, process=False)
    m.remove_unreferenced_vertices()
    m.visual.face_colors = C
    meshes[name] = m

# ---- mozgo reszek: pivot, tengely, nyitasi szog (= a Kotlin konstansok) ----
PARTS = {
    'Door_fl': ((3.849, 4.6, -5.326), (0, 1, 0), 62),
    'Door_fr': ((-3.320, 4.6, -5.326), (0, 1, 0), -62),
    'Door_rl': ((3.754, 4.6, -0.654), (0, 1, 0), 62),
    'Door_rr': ((-3.225, 4.6, -0.654), (0, 1, 0), -62),
    'Trunk':   ((0, 6.974, 3.463), (1, 0, 0), -72),
}

def hinge_matrix(pivot, axis, deg):
    p = np.array(pivot)
    R = trimesh.transformations.rotation_matrix(np.radians(deg), axis, point=p)
    return R

# ---- pyrender szinter ----
scene = pyrender.Scene(bg_color=[0.06, 0.07, 0.09, 1.0], ambient_light=[0.35, 0.36, 0.38])
nodes = {}
for name, m in meshes.items():
    pm = pyrender.Mesh.from_trimesh(m, smooth=False)
    nodes[name] = scene.add(pm)

def look_at(eye, target, up=(0, 1, 0)):
    eye, target, up = map(np.asarray, (eye, target, up))
    f = (target - eye); f = f / np.linalg.norm(f)
    s = np.cross(f, up); s = s / np.linalg.norm(s)
    u = np.cross(s, f)
    M = np.eye(4)
    M[:3, 0], M[:3, 1], M[:3, 2], M[:3, 3] = s, u, -f, eye
    return M

cam = pyrender.PerspectiveCamera(yfov=np.radians(32))
cam_pose = look_at((14.5, 11.0, -16.5), (0.3, 3.6, -0.8))
scene.add(cam, pose=cam_pose)

sun = pyrender.DirectionalLight(color=[1.0, 0.98, 0.94], intensity=4.2)
scene.add(sun, pose=look_at((10, 18, -12), (0, 0, 0)))
fill = pyrender.DirectionalLight(color=[0.85, 0.9, 1.0], intensity=1.6)
scene.add(fill, pose=look_at((-12, 8, 10), (0, 0, 0)))

r = pyrender.OffscreenRenderer(640, 400)

def render_frame(t):
    """t: 0..1 nyitottsag"""
    e = t * t * (3 - 2 * t)   # smoothstep
    for name, (pivot, axis, deg) in PARTS.items():
        scene.set_pose(nodes[name], hinge_matrix(pivot, axis, deg * e))
    color, _ = r.render(scene)
    return color

# proba-allokep felig nyitva
Image.fromarray(render_frame(0.7)).save('/home/claude/prius3d/test_frame.png')
print('test frame OK')
