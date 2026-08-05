import re, pathlib, collections
PROJECT = pathlib.Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android")
VALUES_DIRS = [
    PROJECT / "app/src/main/res/values",
    PROJECT / "app/src/main/res/values-en",
    PROJECT / "app/src/main/res/values-ja",
    PROJECT / "app/src/main/res/values-de",
]
for d in VALUES_DIRS:
    f = d / "strings.xml"
    if not f.exists():
        continue
    content = f.read_text(encoding='utf-8')
    names = re.findall(r'<string name="([^"]+)">', content)
    dups = [n for n, c in collections.Counter(names).items() if c > 1]
    if dups:
        print(f"{d}: duplicates = {dups}")
