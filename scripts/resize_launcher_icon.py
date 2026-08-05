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

img = Image.open(src).convert("RGBA")

for folder, size in sizes.items():
    resized = img.resize((size, size), Image.LANCZOS)
    out_dir = os.path.join(base, folder)
    os.makedirs(out_dir, exist_ok=True)
    resized.save(os.path.join(out_dir, "ic_launcher.png"), "PNG")
    resized.save(os.path.join(out_dir, "ic_launcher_round.png"), "PNG")
    print(f"Saved {size}x{size} -> {folder}")

print("Done")
