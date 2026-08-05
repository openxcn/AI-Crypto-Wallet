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

# 白色容差：RGB 都 >= 250 视为背景
TOLERANCE = 250

def is_bg(pixel):
    return pixel[0] >= TOLERANCE and pixel[1] >= TOLERANCE and pixel[2] >= TOLERANCE

def flood_fill_transparent(img):
    """从四边向内 flood fill，只去掉与边缘相连的白色背景，保留图标内部的白色元素。"""
    w, h = img.size
    pixels = img.load()
    visited = [[False] * h for _ in range(w)]
    stack = []

    # 把四条边的白色像素加入种子
    for x in range(w):
        if is_bg(pixels[x, 0]):
            stack.append((x, 0))
        if is_bg(pixels[x, h - 1]):
            stack.append((x, h - 1))
    for y in range(h):
        if is_bg(pixels[0, y]):
            stack.append((0, y))
        if is_bg(pixels[w - 1, y]):
            stack.append((w - 1, y))

    while stack:
        x, y = stack.pop()
        if x < 0 or x >= w or y < 0 or y >= h:
            continue
        if visited[x][y]:
            continue
        if not is_bg(pixels[x, y]):
            continue
        visited[x][y] = True
        r, g, b = pixels[x, y][:3]
        pixels[x, y] = (r, g, b, 0)
        stack.append((x - 1, y))
        stack.append((x + 1, y))
        stack.append((x, y - 1))
        stack.append((x, y + 1))

img = Image.open(src).convert("RGBA")
flood_fill_transparent(img)

for folder, size in sizes.items():
    resized = img.resize((size, size), Image.LANCZOS)
    out_dir = os.path.join(base, folder)
    os.makedirs(out_dir, exist_ok=True)
    resized.save(os.path.join(out_dir, "ic_launcher.png"), "PNG")
    resized.save(os.path.join(out_dir, "ic_launcher_round.png"), "PNG")
    print(f"Saved {size}x{size} -> {folder}")

print("Done")
