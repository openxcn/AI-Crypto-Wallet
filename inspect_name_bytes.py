import re, pathlib
PROJECT = pathlib.Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android")
names = ['str_1_select_the_supplier','str_4_clock','str_save_img','str_this_software_is_an','str_world_first','text_coller_adresse']
for lang in ['values','values-en','values-ja','values-de']:
    f = PROJECT / f"app/src/main/res/{lang}/strings.xml"
    if not f.exists():
        continue
    content = f.read_text(encoding='utf-8')
    print(f'=== {lang} ===')
    for name in names:
        m = re.search(rf'<string name="{re.escape(name)}">(.*?)</string>', content, re.S)
        if m:
            val = m.group(1)
            # check for unusual chars in value (control chars, etc)
            bad = [(i, c, hex(ord(c))) for i,c in enumerate(val) if ord(c) < 0x20 and c not in '\n\t\r']
            print(f"{name}: name_bytes={name.encode('utf-8')} bad_chars={bad[:10]}")
