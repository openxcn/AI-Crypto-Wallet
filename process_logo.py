"""Process logo: strict non-white crop + transparent bg"""
from PIL import Image
import os

SRC = r"c:\Users\Administrator\Documents\trae work\ai_crypto_wallet_android\logo_source.png"
OUT_DIR = r"c:\Users\Administrator\Documents\trae work\ai_crypto_wallet_android\app\src\main\res"

def process(img):
    """Remove white bg, strict crop, center in square canvas"""
    img = img.convert("RGBA")
    w, h = img.size
    data = list(img.getdata())
    
    # Step 1: Make near-white pixels transparent AND set their RGB to black
    new_data = []
    for r, g, b, a in data:
        if r > 220 and g > 220 and b > 220:
            new_data.append((0, 0, 0, 0))
        elif a == 0:
            new_data.append((0, 0, 0, 0))
        else:
            new_data.append((r, g, b, a))
    img.putdata(new_data)
    
    # Step 2: Find tight bounding box of non-transparent pixels
    min_x, min_y = w, h
    max_x, max_y = 0, 0
    for y in range(h):
        for x in range(w):
            r, g, b, a = data[y * w + x]
            if a > 10 and not (r > 220 and g > 220 and b > 220):
                if x < min_x: min_x = x
                if y < min_y: min_y = y
                if x > max_x: max_x = x
                if y > max_y: max_y = y
    
    content = img.crop((min_x, min_y, max_x + 1, max_y + 1))
    cw, ch = content.size
    print(f"  Content bbox: ({min_x},{min_y})-({max_x},{max_y}) = {cw}x{ch}")
    
    # Step 3: Place in square canvas with padding
    pad = int(max(cw, ch) * 0.15)
    canvas_size = max(cw, ch) + pad * 2
    canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    offset_x = (canvas_size - cw) // 2
    offset_y = (canvas_size - ch) // 2
    canvas.paste(content, (offset_x, offset_y))
    print(f"  Canvas: {canvas_size}x{canvas_size}")
    
    return canvas

def generate_mipmap(img, size, density):
    mipmap_dir = os.path.join(OUT_DIR, f"mipmap-{density}")
    os.makedirs(mipmap_dir, exist_ok=True)
    resized = img.resize((size, size), Image.LANCZOS)
    resized.save(os.path.join(mipmap_dir, "ic_launcher.png"))
    resized.save(os.path.join(mipmap_dir, "ic_launcher_round.png"))
    print(f"  {density}: {size}x{size}")

def main():
    print("1. Loading logo...")
    img = Image.open(SRC)
    print(f"  Original: {img.size}, mode={img.mode}")

    print("2. Processing...")
    result = process(img)

    processed_path = SRC.replace("logo_source.png", "logo_processed.png")
    result.save(processed_path)
    print(f"  Saved: {processed_path}")

    fg_path = os.path.join(OUT_DIR, "drawable", "ic_launcher_foreground.png")
    os.makedirs(os.path.dirname(fg_path), exist_ok=True)
    result.save(fg_path)
    print(f"  Saved foreground drawable")

    print("3. Generating mipmap icons...")
    generate_mipmap(result, 48, "mdpi")
    generate_mipmap(result, 72, "hdpi")
    generate_mipmap(result, 96, "xhdpi")
    generate_mipmap(result, 144, "xxhdpi")
    generate_mipmap(result, 192, "xxxhdpi")

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

    colors_path = os.path.join(OUT_DIR, "values", "ic_launcher_background.xml")
    os.makedirs(os.path.dirname(colors_path), exist_ok=True)
    with open(colors_path, "w") as f:
        f.write('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#0a0a1a</color>
</resources>''')

    print("\nDone!")

if __name__ == "__main__":
    main()
