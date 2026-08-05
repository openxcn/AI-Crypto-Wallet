import json
import sys
from pathlib import Path
p = Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android/extracted_strings.json")
data = json.loads(p.read_text(encoding="utf-8"))
with open("strings_list.txt", "w", encoding="utf-8") as out:
    for s in data["strings"]:
        out.write(f"{s['name']}|{s['zh']}\n")
print(f"wrote {len(data['strings'])} strings", flush=True)
