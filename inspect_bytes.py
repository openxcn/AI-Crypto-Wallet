import pathlib, re
p = pathlib.Path('app/src/main/res/values-en/strings.xml')
content = p.read_bytes()
text = content.decode('utf-8')
for name in ['btn_i_ve_saved','str_4_clock','str_world_first','str_1_select_the_supplier','str_this_software_is_an','text_coller_adresse']:
    m = re.search(rf'<string name="{re.escape(name)}">(.*?)</string>', text, re.S)
    if m:
        start = m.start(1)
        end = m.end(1)
        val_bytes = content[start:end]
        print(f'--- {name} ---')
        print(val_bytes.hex(' '))
        print(repr(val_bytes.decode('utf-8')))
    else:
        print(f'--- {name} not found ---')
