import xml.etree.ElementTree as ET
import re, os

root_dir = r"c:\Users\Administrator\Documents\trae work\ai_crypto_wallet_android\app\src\main\res"
ch = re.compile(r"[\u4e00-\u9fff]")

zh = ET.parse(os.path.join(root_dir, "values", "strings.xml")).getroot()
zh_map = {s.get("name"): "".join(s.itertext()) for s in zh.findall("string")}

for loc in ["en", "ja", "de"]:
    path = os.path.join(root_dir, f"values-{loc}", "strings.xml")
    tree = ET.parse(path)
    out_lines = []
    for s in tree.getroot().findall("string"):
        name = s.get("name")
        val = "".join(s.itertext())
        if ch.search(val):
            zh_val = zh_map.get(name, "")
            esc_val = val.replace("\n", "\\n")
            esc_zh = zh_val.replace("\n", "\\n")
            out_lines.append(f"{name}||{esc_val}||{esc_zh}")
    out_path = f"untranslated_{loc}.txt"
    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(out_lines))
    print(loc, len(out_lines))
