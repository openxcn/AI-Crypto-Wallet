from PIL import Image
import os

src = r"C:\Users\Administrator\Desktop\logo.png"
base = r"C:\Users\Administrator\Documents\trae work\ai_crypto_wallet_android\app\src\main\res"

sizes = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# 白色背景阈值，越接近 255 越透明
BG_THRESHOLD = 245

img = Image.open(src).convert("RGBA")
w, h = img.size
pixels = img.load()

for y in range(h):
    for x in range(w):
        r, g, b, a = pixels[x, y]
        # 计算“白度”：RGB 最大值，并考虑最小值（灰色也按白处理）
        max_val = max(r, g, b)
        min_val = min(r, g, b)
        if max_val >= BG_THRESHOLD and (max_val - min_val) <= 40:
            # 接近白色的像素：按离白色的距离设置 alpha
            whiteness = (r + g + b) / 3.0
            alpha_factor = max(0.0, (whiteness - 200) / 55.0)
            new_a = int(a * (1.0 - alpha_factor))
            pixels[x, y] = (r, g, b, new_a)

for folder, size in sizes.items():
    resized = img.resize((size, size), Image.LANCZOS)
    out_dir = os.path.join(base, folder)
    os.makedirs(out_dir, exist_ok=True)
    resized.save(os.path.join(out_dir, "ic_launcher.png"), "PNG")
    resized.save(os.path.join(out_dir, "ic_launcher_round.png"), "PNG")
    print(f"Saved {size}x{size} -> {folder}")

print("Done")
