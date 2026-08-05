import re, pathlib
files = {
    'en': r'app\src\main\res\values-en\strings.xml',
    'de': r'app\src\main\res\values-de\strings.xml',
    'ja': r'app\src\main\res\values-ja\strings.xml',
    'zh': r'app\src\main\res\values\strings.xml',
}
names = ['str_1_select_the_supplier','str_4_clock','str_save_img','str_this_software_is_an','str_world_first','text_coller_adresse']
for lang, p in files.items():
    print(f'=== {lang} ===')
    text = pathlib.Path(p).read_text(encoding='utf-8')
    for name in names:
        m = re.search(rf'<string name="{re.escape(name)}">(.*?)</string>', text, re.S)
        if m:
            val = m.group(1)
            unusual = [(i, c, f'U+{ord(c):04X}') for i,c in enumerate(val) if ord(c)>0x7E or ord(c)<0x20 and c not in '\n\t\r']
            print(f'{name}: len={len(val)} unusual={unusual[:20]}')
        else:
            print(f'{name}: NOT FOUND')
