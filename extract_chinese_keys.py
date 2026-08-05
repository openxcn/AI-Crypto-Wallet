import xml.etree.ElementTree as ET
import re

path = r"c:\Users\Administrator\Documents\trae work\ai_crypto_wallet_android\app\src\main\res\values\strings.xml"
out = r"c:\Users\Administrator\Documents\trae work\ai_crypto_wallet_android\chinese_keys.txt"
tree = ET.parse(path)
root = tree.getroot()
ch = re.compile(r"[\u4e00-\u9fff]")
lines = []
for s in root.findall("string"):
    name = s.get("name")
    val = "".join(s.itertext())
    if ch.search(val):
        esc = val.replace("\n", "\\n")
        lines.append(f"{name}||{esc}")
with open(out, "w", encoding="utf-8") as f:
    f.write("\n".join(lines))
print("wrote", len(lines), "keys")
