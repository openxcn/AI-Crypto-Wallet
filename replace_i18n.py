#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Apply i18n replacements using extracted mapping and translations."""
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from collections import OrderedDict

PROJECT = Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android")
STRINGS_ZH = PROJECT / "app/src/main/res/values/strings.xml"
STRINGS_EN = PROJECT / "app/src/main/res/values-en/strings.xml"
STRINGS_JA = PROJECT / "app/src/main/res/values-ja/strings.xml"
STRINGS_DE = PROJECT / "app/src/main/res/values-de/strings.xml"

CHINESE_RE = re.compile(r'[\u4e00-\u9fff]')

def escape_xml(text):
    return text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;').replace("'", '&apos;')

def unescape_xml(text):
    return text.replace('&apos;', "'").replace('&quot;', '"').replace('&gt;', '>').replace('&lt;', '<').replace('&amp;', '&')

def xml_string_line(name, text):
    # preserve \n as literal newline in XML resource? Android strings support \n
    # In XML, we store \n as backslash-n, not actual newline
    xml_text = text.replace('\n', '\\n').replace('\t', '\\t')
    return f'    <string name="{name}">{escape_xml(xml_text)}</string>'

def load_existing_strings(path):
    result = OrderedDict()
    if not path.exists():
        return result
    text = path.read_text(encoding="utf-8")
    for m in re.finditer(r'<string name="([^"]+)">(.*?)</string>', text, re.S):
        name, value = m.group(1), unescape_xml(m.group(2))
        result[name] = value
    return result

def is_activity_file(filepath):
    name = Path(filepath).name
    return name.endswith("Activity.java")

def get_string_call(filepath, text):
    """Determine whether to use getString or ctx.getString based on file."""
    path = Path(filepath)
    name = path.name
    if is_activity_file(filepath):
        return "getString"
    # Read file to check for Context field
    content = path.read_text(encoding="utf-8")
    if "private Context ctx;" in content or "private final Context ctx;" in content or "protected Context ctx;" in content or "public Context ctx;" in content:
        return "ctx.getString"
    if "private Context context;" in content or "private final Context context;" in content or "protected Context context;" in content or "public Context context;" in content:
        return "context.getString"
    if "private final android.content.Context ctx;" in content:
        return "ctx.getString"
    # Dialog classes: use getContext().getString
    if "extends Dialog" in content or "extends android.app.Dialog" in content or "extends AppCompatDialog" in content:
        return "getContext().getString"
    # Service / Application
    if "extends Service" in content or "extends Application" in content or "extends android.app.Application" in content:
        return "getString"
    return "getString"

def replace_java_file(filepath, occurrences, fmt_to_name, translations):
    path = Path(filepath)
    content = path.read_text(encoding="utf-8")
    lines = content.splitlines()
    # Group occurrences by line
    by_line = {}
    for occ in occurrences:
        by_line.setdefault(occ['line'], []).append(occ)
    # Process from bottom to top so line numbers remain valid
    for line_no in sorted(by_line.keys(), reverse=True):
        if line_no > len(lines):
            continue
        raw_line = lines[line_no - 1]
        for occ in by_line[line_no]:
            fmt = occ['fmt']
            name = fmt_to_name.get(fmt)
            if not name:
                continue
            args = occ['args']
            method = occ['method']
            expr = occ['expr']
            getter = get_string_call(filepath, raw_line)
            if args:
                replacement = f"{getter}(R.string.{name}, {', '.join(args)})"
            else:
                replacement = f"{getter}(R.string.{name})"
            # Replace only the text argument in the method call
            # Find the method call and its arguments in the line
            method_pos = raw_line.find(method)
            if method_pos == -1:
                continue
            # Extract the full method call from method_pos to matching )
            start = raw_line.find('(', method_pos)
            if start == -1:
                continue
            depth = 0
            in_string = False
            escape = False
            end = start
            while end < len(raw_line):
                c = raw_line[end]
                if escape:
                    escape = False
                elif c == '\\':
                    escape = True
                elif c == '"':
                    in_string = not in_string
                elif not in_string:
                    if c == '(':
                        depth += 1
                    elif c == ')':
                        depth -= 1
                        if depth == 0:
                            break
                end += 1
            full_call = raw_line[method_pos:end+1]
            # Build new call: method(replacement, remaining_args...)
            # For methods where text is first arg, replace first arg
            # For Toast.makeText, text is second arg
            method_key = method.strip()
            if method_key in ['Toast.makeText(', 'Snackbar.make(']:
                # args are: context, text, duration or parent, text, duration
                # We need to replace the second argument
                # Parse top-level args of the full call
                inner = full_call[len(method):-1]
                tl_args = split_top_level_args(inner)
                if len(tl_args) >= 2:
                    tl_args[1] = replacement
                    new_call = method + ', '.join(tl_args) + ')'
                else:
                    new_call = full_call
            elif method_key in ['.createChooser(']:
                inner = full_call[len(method):-1]
                tl_args = split_top_level_args(inner)
                if len(tl_args) >= 2:
                    tl_args[1] = replacement
                    new_call = method + ', '.join(tl_args) + ')'
                else:
                    new_call = full_call
            elif method_key in ['.getMenu().add(', 'popup.getMenu().add(']:
                inner = full_call[len(method):-1]
                tl_args = split_top_level_args(inner)
                if len(tl_args) >= 4:
                    tl_args[3] = replacement
                    new_call = method + ', '.join(tl_args) + ')'
                else:
                    new_call = full_call
            else:
                # text is first arg
                inner = full_call[len(method):-1]
                tl_args = split_top_level_args(inner)
                if tl_args:
                    tl_args[0] = replacement
                    new_call = method + ', '.join(tl_args) + ')'
                else:
                    new_call = full_call
            raw_line = raw_line[:method_pos] + new_call + raw_line[end+1:]
        lines[line_no - 1] = raw_line
    path.write_text('\n'.join(lines), encoding="utf-8")

def split_top_level_args(inner):
    args = []
    current = []
    depth = 0
    in_string = False
    escape = False
    for c in inner:
        if escape:
            escape = False
            current.append(c)
        elif c == '\\':
            escape = True
            current.append(c)
        elif c == '"':
            in_string = not in_string
            current.append(c)
        elif not in_string:
            if c == '(':
                depth += 1
                current.append(c)
            elif c == ')':
                depth -= 1
                current.append(c)
            elif c == ',' and depth == 0:
                args.append(''.join(current).strip())
                current = []
            else:
                current.append(c)
        else:
            current.append(c)
    if current:
        args.append(''.join(current).strip())
    return args

def replace_xml_file(filepath, occurrences, fmt_to_name):
    path = Path(filepath)
    content = path.read_text(encoding="utf-8")
    lines = content.splitlines()
    by_line = {}
    for occ in occurrences:
        by_line.setdefault(occ['line'], []).append(occ)
    for line_no in sorted(by_line.keys(), reverse=True):
        if line_no > len(lines):
            continue
        raw_line = lines[line_no - 1]
        for occ in by_line[line_no]:
            fmt = occ['fmt']
            name = fmt_to_name.get(fmt)
            if not name:
                continue
            attr = occ['attr']
            # Replace attr="value" with attr="@string/name"
            pattern = rf'({re.escape(attr)}\s*=\s*")([^"]*)"'
            def repl(m):
                return f'{m.group(1)}@string/{name}"'
            raw_line = re.sub(pattern, repl, raw_line)
        lines[line_no - 1] = raw_line
    path.write_text('\n'.join(lines), encoding="utf-8")

def merge_strings(existing, new_entries, translations, lang):
    """Merge existing strings with new entries, preserving existing and using translations."""
    result = OrderedDict(existing)
    for name, zh in new_entries:
        if name in result:
            # Keep existing translation unless we explicitly want to update
            continue
        if lang == 'zh':
            result[name] = zh
        else:
            result[name] = translations.get(zh, {}).get(lang, zh)
    return result

def write_strings_xml(path, strings_dict):
    lines = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>']
    for name, text in strings_dict.items():
        lines.append(xml_string_line(name, text))
    lines.append('</resources>')
    path.write_text('\n'.join(lines) + '\n', encoding="utf-8")

def main():
    mapping = json.loads((PROJECT / "i18n_mapping.json").read_text(encoding="utf-8"))
    extracted = json.loads((PROJECT / "extracted_strings.json").read_text(encoding="utf-8"))
    fmt_to_name = mapping['fmt_to_name']
    occurrences = mapping['occurrences']

    # Load translations
    translations_path = PROJECT / "translations.json"
    if translations_path.exists():
        translations = json.loads(translations_path.read_text(encoding="utf-8"))
    else:
        translations = {}

    # Group occurrences by file
    by_file = {}
    for occ in occurrences:
        by_file.setdefault(occ['file'], []).append(occ)

    # Replace in source files
    for filepath, occs in by_file.items():
        if filepath.endswith('.java'):
            replace_java_file(filepath, occs, fmt_to_name, translations)
        elif filepath.endswith('.xml'):
            replace_xml_file(filepath, occs, fmt_to_name)

    # Generate strings.xml files
    new_entries = [(s['name'], s['zh']) for s in extracted['strings']]

    existing_zh = load_existing_strings(STRINGS_ZH)
    existing_en = load_existing_strings(STRINGS_EN)
    existing_ja = load_existing_strings(STRINGS_JA)
    existing_de = load_existing_strings(STRINGS_DE)

    merged_zh = merge_strings(existing_zh, new_entries, translations, 'zh')
    merged_en = merge_strings(existing_en, new_entries, translations, 'en')
    merged_ja = merge_strings(existing_ja, new_entries, translations, 'ja')
    merged_de = merge_strings(existing_de, new_entries, translations, 'de')

    write_strings_xml(STRINGS_ZH, merged_zh)
    write_strings_xml(STRINGS_EN, merged_en)
    write_strings_xml(STRINGS_JA, merged_ja)
    write_strings_xml(STRINGS_DE, merged_de)

    print("Replaced strings in source files and generated strings.xml files.")

if __name__ == "__main__":
    main()
