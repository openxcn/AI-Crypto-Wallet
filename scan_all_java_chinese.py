import os, re

JAVA_DIR = r"c:\Users\Administrator\Documents\trae work\ai_crypto_wallet_android\app\src\main\java\com\aicryptowallet\app"
OUT = r"c:\Users\Administrator\Documents\trae work\ai_crypto_wallet_android\all_java_chinese.txt"
CH = re.compile(r"[\u4e00-\u9fff]")
STRING_RE = re.compile(r'"([^"\\]*(?:\\.[^"\\]*)*)"')
LOG_RE = re.compile(r"\b(Log|Logger|System\.out\.print)\b")

def remove_block_comments(text):
    return re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)

lines = []
for root, _, files in os.walk(JAVA_DIR):
    for f in files:
        if not f.endswith(".java"):
            continue
        path = os.path.join(root, f)
        with open(path, "r", encoding="utf-8") as fp:
            raw = fp.read()
        no_block = remove_block_comments(raw)
        for i, line in enumerate(no_block.splitlines(), 1):
            if line.strip().startswith("//") or line.strip().startswith("*"):
                continue
            if LOG_RE.search(line):
                continue
            for m in STRING_RE.finditer(line):
                s = m.group(1)
                if CH.search(s):
                    esc = s.replace("\n", "\\n")
                    lines.append(f"{path}:{i}  \"{esc}\"")
with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines))
print("wrote", len(lines))
