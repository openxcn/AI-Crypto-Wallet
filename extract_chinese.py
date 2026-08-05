import re, os, json
root = r"c:\Users\Administrator\Documents\trae work\ai_crypto_wallet_android\app\src\main\java\com\aicryptowallet\app"
results = []
for dp, dn, fn in os.walk(root):
    for f in fn:
        if not f.endswith('.java'): continue
        path = os.path.join(dp, f)
        with open(path, 'r', encoding='utf-8') as file:
            lines = file.readlines()
        for i, line in enumerate(lines, 1):
            raw = line
            # skip comments (simple heuristic)
            stripped = line.strip()
            if stripped.startswith('*') or stripped.startswith('//') or stripped.startswith('/*'):
                continue
            if 'Logger.' in line or 'Log.' in line:
                continue
            # find string literals with Chinese
            literals = re.findall(r'"([^"]*[\u4e00-\u9fff][^"]*)"', line)
            if literals:
                results.append({'file': os.path.basename(path), 'line': i, 'texts': literals, 'code': raw.strip()})
with open('chinese_candidates.json', 'w', encoding='utf-8') as out:
    json.dump(results, out, ensure_ascii=False, indent=2)
print('found', len(results), 'lines')
