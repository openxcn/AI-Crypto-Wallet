"""Generate AI Crypto Wallet logo: red triangle + green ring, transparent bg"""
from PIL import Image, ImageDraw, ImageFont
import math, os

OUT_DIR = r"c:\Users\Administrator\Documents\trae work\ai_crypto_wallet_android\app\src\main\res"
SIZE = 1024

def create_logo(size):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    
    cx, cy = size // 2, size // 2
    
    # === Red Triangle (top) ===
    tri_top = (cx, int(size * 0.12))
    tri_left = (int(size * 0.22), int(size * 0.42))
    tri_right = (int(size * 0.78), int(size * 0.42))
    
    # Gradient effect: draw multiple triangles with slightly different colors
    for i in range(20):
        offset = i * 0.5
        r = int(220 - i * 2)
        g = int(20 + i)
        b = int(20 + i)
        pts = [
            (tri_top[0], tri_top[1] + offset),
            (tri_left[0] - offset * 0.3, tri_left[1] + offset),
            (tri_right[0] + offset * 0.3, tri_right[1] + offset),
        ]
        draw.polygon(pts, fill=(r, g, b, 255))
    
    # Highlight on triangle
    highlight_pts = [
        (cx, int(size * 0.14)),
        (int(size * 0.35), int(size * 0.38)),
        (cx, int(size * 0.38)),
    ]
    draw.polygon(highlight_pts, fill=(255, 100, 100, 80))
    
    # === Green Ring (bottom) ===
    ring_cx = cx
    ring_cy = int(size * 0.62)
    outer_r = int(size * 0.32)
    inner_r = int(size * 0.18)
    
    # Draw outer circle
    for i in range(outer_r):
        ratio = i / outer_r
        # Gradient from dark green edge to bright green center
        r = int(30 + 60 * ratio)
        g = int(100 + 120 * ratio)
        b = int(30 + 40 * ratio)
        draw.ellipse(
            [ring_cx - outer_r + i, ring_cy - outer_r + i,
             ring_cx + outer_r - i, ring_cy + outer_r - i],
            fill=(r, g, b, 255)
        )
    
    # Cut out inner circle (transparent)
    for i in range(inner_r):
        draw.ellipse(
            [ring_cx - inner_r + i, ring_cy - inner_r + i,
             ring_cx + inner_r - i, ring_cy + inner_r - i],
            fill=(0, 0, 0, 0)
        )
    
    # Inner ring highlight (3D effect)
    for i in range(int(inner_r * 0.15)):
        r = int(100 + 80 * (i / (inner_r * 0.15)))
        g = int(200 + 55 * (i / (inner_r * 0.15)))
        b = int(80 + 40 * (i / (inner_r * 0.15)))
        draw.ellipse(
            [ring_cx - inner_r + i, ring_cy - inner_r + i,
             ring_cx + inner_r - i, ring_cy + inner_r - i],
            fill=(r, g, b, 180)
        )
    
    # Shine on green ring (top-left highlight)
    shine_cx = ring_cx - int(outer_r * 0.3)
    shine_cy = ring_cy - int(outer_r * 0.3)
    shine_r = int(outer_r * 0.25)
    for i in range(shine_r):
        alpha = int(120 * (1 - i / shine_r))
        draw.ellipse(
            [shine_cx - shine_r + i, shine_cy - shine_r + i,
             shine_cx + shine_r - i, shine_cy + shine_r - i],
            fill=(200, 255, 200, alpha)
        )
    
    return img

def generate_mipmap(img, size, density):
    mipmap_dir = os.path.join(OUT_DIR, f"mipmap-{density}")
    os.makedirs(mipmap_dir, exist_ok=True)
    resized = img.resize((size, size), Image.LANCZOS)
    resized.save(os.path.join(mipmap_dir, "ic_launcher.png"))
    resized.save(os.path.join(mipmap_dir, "ic_launcher_round.png"))
    print(f"  {density}: {size}x{size}")

def main():
    print("1. Generating logo (1024x1024)...")
    logo = create_logo(SIZE)
    
    # Save full-size logo
    logo_path = r"c:\Users\Administrator\Documents\trae work\ai_crypto_wallet_android\logo_generated.png"
    logo.save(logo_path)
    print(f"  Saved: {logo_path}")
    
    # Save as foreground drawable
    fg_path = os.path.join(OUT_DIR, "drawable", "ic_launcher_foreground.png")
    os.makedirs(os.path.dirname(fg_path), exist_ok=True)
    logo.save(fg_path)
    print(f"  Saved foreground drawable")
    
    # Generate mipmap icons
    print("2. Generating mipmap icons...")
    generate_mipmap(logo, 48, "mdpi")
    generate_mipmap(logo, 72, "hdpi")
    generate_mipmap(logo, 96, "xhdpi")
    generate_mipmap(logo, 144, "xxhdpi")
    generate_mipmap(logo, 192, "xxxhdpi")
    
    # Create adaptive icon XML
    xml_dir = os.path.join(OUT_DIR, "mipmap-anydpi-v26")
    os.makedirs(xml_dir, exist_ok=True)
    adaptive_xml = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>'''
    with open(os.path.join(xml_dir, "ic_launcher.xml"), "w") as f:
        f.write(adaptive_xml)
    with open(os.path.join(xml_dir, "ic_launcher_round.xml"), "w") as f:
        f.write(adaptive_xml)
    print(f"  Created adaptive icon XML")
    
    # Create background color
    colors_path = os.path.join(OUT_DIR, "values", "ic_launcher_background.xml")
    os.makedirs(os.path.dirname(colors_path), exist_ok=True)
    with open(colors_path, "w") as f:
        f.write('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#0a0a1a</color>
</resources>''')
    print(f"  Created background color (#0a0a1a)")
    
    print("\nDone!")

if __name__ == "__main__":
    main()
