import re, pathlib, json
PROJECT = pathlib.Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android")
VALUES_DIRS = [
    PROJECT / "app/src/main/res/values",
    PROJECT / "app/src/main/res/values-en",
    PROJECT / "app/src/main/res/values-ja",
    PROJECT / "app/src/main/res/values-de",
]
nonascii = {}
for d in VALUES_DIRS:
    f = d / "strings.xml"
    if not f.exists():
        continue
    content = f.read_text(encoding='utf-8')
    for m in re.finditer(r'<string name="([^"]+)">', content):
        name = m.group(1)
        if any(ord(c) > 127 for c in name):
            nonascii.setdefault(name, []).append(str(d))
print(f"Found {len(nonascii)} non-ASCII resource names")
for name in sorted(nonascii):
    print(name)
