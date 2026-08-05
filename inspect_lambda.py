import pathlib, re
root = pathlib.Path('app/src/main/java/com/aicryptowallet/app')
for p in root.rglob('*.java'):
    text = p.read_text(encoding='utf-8')
    for m in re.finditer(r'\) ->\)', text):
        start = max(0, m.start()-60)
        snippet = text[start:m.end()+80]
        print(f'{p}:{text.count(chr(10),0,m.start())+1}: {snippet.replace(chr(10)," ")}')
