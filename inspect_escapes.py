#!/usr/bin/env python3
import re, pathlib
for lang in ['values-en','values-de']:
    p = pathlib.Path(f'app/src/main/res/{lang}/strings.xml')
    content = p.read_text(encoding='utf-8')
    print(f'=== {lang} ===')
    for name in ['btn_i_ve_saved','str_1_select_the_supplier','str_4_clock','str_save_img','str_this_software_is_an','str_world_first','text_coller_adresse']:
        m = re.search(rf'<string name="{re.escape(name)}">(.*?)</string>', content, re.S)
        if m:
            val = m.group(1)
            print(f'--- {name} ---')
            print(repr(val))
            for i,ch in enumerate(val):
                if ch == '\\':
                    print(' backslash at', i, 'followed by', repr(val[i+1:i+6]))
            print()
