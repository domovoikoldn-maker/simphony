"""
tools/make_icons.py — иконка «Симфонии»: четыре цветные дорожки-волны на тёмном фоне.
  mipmap-*/ic_launcher.png (Android 7), ic_launcher_fg.png (адаптивная, 8+), ic_launcher_mono.png (13+).
Разработчик и владелец: domovoi4ik.
"""
import os, struct, zlib
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android", "res")
DENS = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
COLORS = [(0.91, 0.69, 0.29), (0.37, 0.70, 0.94), (0.48, 0.83, 0.54), (0.79, 0.55, 0.94)]


def save_png(path, arr):
    h, w = arr.shape[:2]
    a = np.clip(arr * 255 + 0.5, 0, 255).astype(np.uint8)
    raw = b"".join(b"\x00" + a[y].tobytes() for y in range(h))
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "wb").write(png)


def art(size, mono=False, legacy=False, ss=4):
    n = size * ss
    y, x = np.mgrid[0:n, 0:n] / n
    img = np.zeros((n, n, 4))
    def put(mask, rgb, alpha=1.0):
        m = np.clip(mask, 0, 1) * alpha
        for i in range(3):
            img[..., i] = img[..., i] * (1 - m) + rgb[i] * m
        img[..., 3] = img[..., 3] * (1 - m) + m
    if legacy:   # круглая иконка со своим фоном
        r = np.hypot(x - 0.5, y - 0.5)
        put((r < 0.48).astype(float), (0.086, 0.075, 0.114))
        s, o = 1.25, -0.125
    else:        # 108dp холст, безопасная зона ~0.2..0.8
        s, o = 1.0, 0.0
    X, Y = (x - o) / s, (y - o) / s
    for k in range(4):
        cy = 0.33 + k * 0.115
        # волна: синус с огибающей, своя частота у каждой дорожки
        env = np.clip(1 - np.abs(X - 0.5) / 0.3, 0, 1) ** 0.7
        amp = 0.045 * env * (0.55 + 0.45 * np.abs(np.sin(X * (19 + 7 * k) + k)))
        mask = ((np.abs(Y - cy) < amp + 0.006) & (X > 0.2) & (X < 0.8)).astype(float)
        put(mask, (1, 1, 1) if mono else COLORS[k])
    if mono:
        img[..., :3] = 1
    img = img.reshape(size, ss, size, ss, 4).mean(axis=(1, 3))
    return img


for d, f in DENS.items():
    save_png(os.path.join(RES, f"mipmap-{d}", "ic_launcher.png"), art(int(48 * f), legacy=True))
    save_png(os.path.join(RES, f"mipmap-{d}", "ic_launcher_fg.png"), art(int(108 * f)))
    save_png(os.path.join(RES, f"mipmap-{d}", "ic_launcher_mono.png"), art(int(108 * f), mono=True))
os.makedirs(os.path.join(RES, "mipmap-anydpi-v26"), exist_ok=True)
open(os.path.join(RES, "mipmap-anydpi-v26", "ic_launcher.xml"), "w").write('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/icon_bg" />
    <foreground android:drawable="@mipmap/ic_launcher_fg" />
    <monochrome android:drawable="@mipmap/ic_launcher_mono" />
</adaptive-icon>
''')
save_png(os.path.join(ROOT, "icon_512.png"), art(512, legacy=True))
print("ok")
