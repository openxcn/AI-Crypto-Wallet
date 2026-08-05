import re, pathlib
PROJECT = pathlib.Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android")
VALUES_DIRS = [
    PROJECT / "app/src/main/res/values",
    PROJECT / "app/src/main/res/values-en",
    PROJECT / "app/src/main/res/values-ja",
    PROJECT / "app/src/main/res/values-de",
]
# Match a backslash followed by anything; check if it's a valid escape
valid_escape = re.compile(r'\\(n|t|r|\\|\'|\"|u[0-9a-fA-F]{4})')
invalid_escape = re.compile(r'\\(?!n|t|r|\\|\'|\"|u[0-9a-fA-F]{4})')
for d in VALUES_DIRS:
    f = d / "strings.xml"
    if not f.exists():
        continue
    content = f.read_text(encoding='utf-8')
    for m in re.finditer(r'<string name="([^"]+)">(.*?)</string>', content, re.S):
        name, val = m.group(1), m.group(2)
        # Find invalid escapes
        bad = []
        for bm in invalid_escape.finditer(val):
            start = max(0, bm.start()-3)
            end = min(len(val), bm.end()+5)
            bad.append((bm.start(), repr(val[start:end])))
        if bad:
            print(f"{d.name} {name}: {bad}")
