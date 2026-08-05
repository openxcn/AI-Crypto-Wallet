#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Fix i18n: rename invalid resource names, add missing strings, replace hardcoded Chinese."""
import json, os, re, hashlib
import xml.etree.ElementTree as ET
from pathlib import Path
from collections import OrderedDict, defaultdict

PROJECT = Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android")
STRINGS_PATHS = {
    "zh": PROJECT / "app/src/main/res/values/strings.xml",
    "en": PROJECT / "app/src/main/res/values-en/strings.xml",
    "ja": PROJECT / "app/src/main/res/values-ja/strings.xml",
    "de": PROJECT / "app/src/main/res/values-de/strings.xml",
}
JAVA_DIR = PROJECT / "app/src/main/java/com/aicryptowallet/app"
LAYOUT_DIR = PROJECT / "app/src/main/res/layout"
RES_DIR = PROJECT / "app/src/main/res"

CH = re.compile(r"[\u4e00-\u9fff]")
VALID_NAME_RE = re.compile(r"^[a-z][a-z0-9_]*$")
KNOWN_PREFIXES = ("label_", "btn_", "toast_", "title_", "msg_", "text_", "hint_", "str_")

# Load translations
TRANSLATIONS = {}
for lang in ("en", "ja", "de"):
    p = PROJECT / f"translations_{lang}.json"
    TRANSLATIONS[lang] = json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}

def load_strings(path):
    if not path.exists():
        return []
    tree = ET.parse(path)
    root = tree.getroot()
    return [(s.get("name"), "".join(s.itertext())) for s in root.findall("string")]

def escape_xml(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;").replace("'", "&apos;")

def write_strings_xml(path, entries):
    lines = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>']
    for name, value in entries:
        text = value.replace("\n", "\\n").replace("\t", "\\t")
        lines.append(f'    <string name="{name}">{escape_xml(text)}</string>')
    lines.append('</resources>')
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")

def is_valid_name(n):
    return bool(n and VALID_NAME_RE.match(n))

def slugify(text):
    s = re.sub(r"%\d+\$[sdxf]", "", text)
    s = re.sub(r"[^A-Za-z0-9]+", "_", s)
    s = s.strip("_").lower()
    s = re.sub(r"_+", "_", s)
    return s or "text"

def short_hash(text):
    return hashlib.md5(text.encode("utf-8")).hexdigest()[:6]

def decide_prefix(old_name, method, attr):
    if old_name:
        for prefix in KNOWN_PREFIXES:
            if old_name.startswith(prefix):
                return prefix
    if method:
        if "setPositiveButton" in method or "setNegativeButton" in method or "setNeutralButton" in method:
            return "btn_"
        if "Toast" in method or "Snackbar" in method:
            return "toast_"
        if "setTitle" in method:
            return "title_"
        if "setMessage" in method:
            return "msg_"
        if "setHint" in method or "setPlaceholderText" in method or "setPrompt" in method:
            return "hint_"
        if "setText" in method or "setError" in method or "setSummary" in method or "setContentTitle" in method or "setContentText" in method or "setSubText" in method or "setTicker" in method or "setAction" in method or "setLabel" in method:
            return "text_"
    if attr:
        if "hint" in attr:
            return "hint_"
        if "title" in attr or "subtitle" in attr:
            return "title_"
        return "label_"
    return "str_"

def make_unique(name, used):
    if name not in used:
        return name
    i = 2
    while f"{name}_{i}" in used:
        i += 1
    return f"{name}_{i}"

def generate_name(value, used, old_name=None, method=None, attr=None):
    prefix = decide_prefix(old_name, method, attr)
    en = TRANSLATIONS["en"].get(value, "")
    if en and en != value:
        suffix = slugify(en)
    else:
        # Fallback: try to extract ASCII from old_name after prefix
        suffix = ""
        if old_name:
            rest = old_name[len(prefix):] if old_name.startswith(prefix) else old_name
            rest = re.sub(r"[^A-Za-z0-9]+", "_", rest).strip("_").lower()
            rest = re.sub(r"_+", "_", rest)
            suffix = rest
        if not suffix:
            suffix = "text_" + short_hash(value)
    suffix = suffix[:80]
    base = (prefix + suffix).strip("_").lower()
    base = re.sub(r"_+", "_", base)
    return make_unique(base, used)

def get_getter(file_path, file_content):
    name = Path(file_path).name
    if name.endswith("Activity.java"):
        return "getString"
    if "extends Application" in file_content or "extends Service" in file_content:
        return "getString"
    if "extends Dialog" in file_content or "extends AppCompatDialog" in file_content:
        return "getContext().getString"
    # prefer ctx, then context
    if re.search(r"\bContext\s+ctx\b|\bprivate\s+Context\s+ctx\b|\bprotected\s+Context\s+ctx\b|\bfinal\s+Context\s+ctx\b", file_content):
        return "ctx.getString"
    if re.search(r"\bContext\s+context\b|\bprivate\s+Context\s+context\b|\bprotected\s+Context\s+context\b|\bfinal\s+Context\s+context\b", file_content):
        return "context.getString"
    return "getString"

# --- extraction helpers ---
USER_PATTERNS = [
    (r"\.setText\s*\(", 0), (r"\.setTitle\s*\(", 0), (r"\.setMessage\s*\(", 0),
    (r"\.setHint\s*\(", 0), (r"\.setError\s*\(", 0),
    (r"\.setPositiveButton\s*\(", 0), (r"\.setNegativeButton\s*\(", 0), (r"\.setNeutralButton\s*\(", 0),
    (r"Toast\.makeText\s*\(", 1), (r"\.setContentTitle\s*\(", 0), (r"\.setContentText\s*\(", 0),
    (r"\.setSubText\s*\(", 0), (r"\.setTicker\s*\(", 0), (r"\.setDialogTitle\s*\(", 0),
    (r"\.setSummary\s*\(", 0), (r"\.setAction\s*\(", 0), (r"\.setLabel\s*\(", 0),
    (r"\.setPlaceholderText\s*\(", 0), (r"\.setPrompt\s*\(", 0), (r"Snackbar\.make\s*\(", 1),
]
SKIP = [r"^\s*//", r"^\s*\*", r"\bLog\.", r"\bLogger\.", r"System\.out\."]

def mask_block_comments(text):
    def repl(m):
        s = m.group(0)
        return "".join("\n" if c == "\n" else " " for c in s)
    return re.sub(r"/\*.*?\*/", repl, text, flags=re.DOTALL)

def extract_top_level_args(inner):
    args = []
    cur = []
    depth = 0
    in_str = False
    esc = False
    for c in inner:
        if esc:
            esc = False
            cur.append(c)
        elif c == "\\":
            esc = True
            cur.append(c)
        elif c == '"':
            in_str = not in_str
            cur.append(c)
        elif not in_str:
            if c == '(':
                depth += 1
                cur.append(c)
            elif c == ')':
                depth -= 1
                cur.append(c)
            elif c == ',' and depth == 0:
                if cur:
                    args.append("".join(cur).strip())
                    cur = []
            else:
                cur.append(c)
        else:
            cur.append(c)
    if cur:
        args.append("".join(cur).strip())
    return args

def tokenize_expr(expr):
    tokens = []
    i = 0
    while i < len(expr):
        if expr[i] in " \t\n+":
            i += 1
            continue
        if expr[i] == '"':
            j = i + 1
            while j < len(expr):
                if expr[j] == "\\" and j + 1 < len(expr):
                    j += 2
                elif expr[j] == '"':
                    break
                else:
                    j += 1
            tokens.append(("str", expr[i+1:j]))
            i = j + 1
        else:
            j = i
            depth = 0
            ins = False
            esc = False
            while j < len(expr):
                c = expr[j]
                if esc:
                    esc = False
                elif c == "\\":
                    esc = True
                elif c == '"':
                    ins = not ins
                elif not ins:
                    if c == '(':
                        depth += 1
                    elif c == ')':
                        depth -= 1
                    elif c == '+' and depth == 0:
                        break
                j += 1
            tok = expr[i:j].strip()
            if tok:
                tokens.append(("var", tok))
            i = j
    return tokens

def extract_java_occurrences(text):
    occs = []
    masked = mask_block_comments(text)
    for i, raw_line in enumerate(masked.splitlines(), 1):
        if not CH.search(raw_line):
            continue
        line = raw_line.strip()
        if any(re.search(p, line) for p in SKIP):
            continue
        for pat, arg_idx in USER_PATTERNS:
            for m in re.finditer(pat, raw_line):
                paren_start = raw_line.find("(", m.start())
                if paren_start == -1:
                    continue
                args = extract_top_level_args(raw_line[paren_start+1:])
                if arg_idx >= len(args):
                    continue
                expr = args[arg_idx]
                toks = tokenize_expr(expr)
                if not any(k == "str" and CH.search(v) for k, v in toks):
                    continue
                parts = []
                a = []
                vi = 1
                for k, v in toks:
                    if k == "str":
                        parts.append(v)
                    else:
                        parts.append(f"%{vi}$s")
                        a.append(v)
                        vi += 1
                fmt = "".join(parts)
                occs.append({"line": i, "method": m.group(0), "expr": expr, "fmt": fmt, "args": a})
    return occs

def extract_xml_occurrences(text):
    occs = []
    XML_ATTRS = [("android:text", "text"), ("android:hint", "hint"), ("android:title", "title"),
                 ("app:title", "title"), ("android:contentDescription", "desc"),
                 ("android:summary", "summary"), ("android:subtitle", "subtitle"),
                 ("android:textOn", "text"), ("android:textOff", "text")]
    for i, raw_line in enumerate(text.splitlines(), 1):
        if not CH.search(raw_line):
            continue
        for attr, _ in XML_ATTRS:
            for m in re.finditer(rf'({re.escape(attr)}\s*=\s*")([^"]*)"', raw_line):
                v = m.group(2)
                if v.startswith("@") or v.startswith("?"):
                    continue
                if CH.search(v):
                    occs.append({"line": i, "attr": attr, "value": v})
    return occs

# --- main processing ---
def main():
    # 1. Load existing default strings and build value->valid name mapping
    zh_entries = load_strings(STRINGS_PATHS["zh"])
    value_to_name = OrderedDict()
    old_invalid_to_new = {}
    used_names = set()
    final_entries = OrderedDict()  # name -> value (zh)

    # First pass: valid names
    for name, value in zh_entries:
        if is_valid_name(name):
            used_names.add(name)
            if CH.search(value) and value not in value_to_name:
                value_to_name[value] = name
            final_entries[name] = value

    # Second pass: invalid names -> merge or generate
    for name, value in zh_entries:
        if is_valid_name(name):
            continue
        if value in value_to_name:
            old_invalid_to_new[name] = value_to_name[value]
        else:
            new_name = generate_name(value, used_names, old_name=name)
            used_names.add(new_name)
            value_to_name[value] = new_name
            old_invalid_to_new[name] = new_name
            final_entries[new_name] = value

    # 3. Extract Java hardcoded occurrences
    java_all_occurrences = []
    java_values = set()
    for f in sorted(JAVA_DIR.rglob("*.java")):
        content = f.read_text(encoding="utf-8")
        occs = extract_java_occurrences(content)
        for occ in occs:
            occ["file"] = str(f)
            occ["file_content"] = content
            java_all_occurrences.append(occ)
            if CH.search(occ["fmt"]):
                java_values.add(occ["fmt"])

    # 4. Extract XML hardcoded occurrences
    xml_all_occurrences = []
    xml_values = set()
    for f in sorted(LAYOUT_DIR.rglob("*.xml")):
        content = f.read_text(encoding="utf-8")
        occs = extract_xml_occurrences(content)
        for occ in occs:
            occ["file"] = str(f)
            xml_all_occurrences.append(occ)
            if CH.search(occ["value"]):
                xml_values.add(occ["value"])

    # 5. Assign names to new hardcoded values
    for value in sorted(java_values | xml_values):
        if value in value_to_name:
            continue
        new_name = generate_name(value, used_names)
        used_names.add(new_name)
        value_to_name[value] = new_name
        final_entries[new_name] = value

    # 6. Build locale entries
    locale_entries = {lang: OrderedDict() for lang in STRINGS_PATHS}
    existing_locale = {lang: dict(load_strings(STRINGS_PATHS[lang])) for lang in STRINGS_PATHS}

    def locale_value(lang, name, zh_value):
        if lang == "zh":
            return zh_value
        if not CH.search(zh_value):
            # non-Chinese: prefer existing translation if present
            return existing_locale[lang].get(name, zh_value)
        return TRANSLATIONS[lang].get(zh_value, zh_value)

    for name, zh_value in final_entries.items():
        for lang in STRINGS_PATHS:
            locale_entries[lang][name] = locale_value(lang, name, zh_value)

    # 7. Write strings.xml files
    for lang, path in STRINGS_PATHS.items():
        write_strings_xml(path, list(locale_entries[lang].items()))
    print(f"Wrote strings.xml files. Total strings: {len(final_entries)}")

    # 8. Rename invalid references globally
    invalid_names = set(old_invalid_to_new.keys())
    if invalid_names:
        print(f"Renaming {len(invalid_names)} invalid resource references...")
        # Java files
        for f in sorted(JAVA_DIR.rglob("*.java")):
            content = f.read_text(encoding="utf-8")
            new_content = content
            for old, new in old_invalid_to_new.items():
                new_content = new_content.replace(f"R.string.{old}", f"R.string.{new}")
            if new_content != content:
                f.write_text(new_content, encoding="utf-8")
        # XML files
        for f in sorted(RES_DIR.rglob("*.xml")):
            try:
                content = f.read_text(encoding="utf-8")
            except Exception:
                continue
            new_content = content
            for old, new in old_invalid_to_new.items():
                new_content = new_content.replace(f"@string/{old}", f"@string/{new}")
            if new_content != content:
                f.write_text(new_content, encoding="utf-8")

    # 9. Replace Java hardcoded occurrences
    print(f"Replacing {len(java_all_occurrences)} Java hardcoded occurrences...")
    by_file = defaultdict(list)
    for occ in java_all_occurrences:
        by_file[occ["file"]].append(occ)

    def replace_method_arg(line, method_pos, arg_idx, replacement):
        start = line.find("(", method_pos)
        if start == -1:
            return line
        depth = 0
        in_str = False
        esc = False
        end = start
        while end < len(line):
            c = line[end]
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = not in_str
            elif not in_str:
                if c == '(':
                    depth += 1
                elif c == ')':
                    depth -= 1
                    if depth == 0:
                        break
            end += 1
        method = line[method_pos:line.find("(", method_pos)+1]
        full_call = line[method_pos:end+1]
        inner = full_call[len(method):-1]
        args = extract_top_level_args(inner)
        if arg_idx < len(args):
            args[arg_idx] = replacement
            new_call = method + ", ".join(args) + ")"
            return line[:method_pos] + new_call + line[end+1:]
        return line

    for filepath, occs in by_file.items():
        f = Path(filepath)
        content = f.read_text(encoding="utf-8")
        lines = content.splitlines()
        # group by line, process reverse
        by_line = defaultdict(list)
        for occ in occs:
            by_line[occ["line"]].append(occ)
        getter = None
        for line_no in sorted(by_line.keys(), reverse=True):
            raw_line = lines[line_no - 1]
            for occ in sorted(by_line[line_no], key=lambda x: x["method"], reverse=True):
                if getter is None:
                    getter = get_getter(filepath, occ["file_content"])
                fmt = occ["fmt"]
                name = value_to_name.get(fmt)
                if not name:
                    continue
                if occ["args"]:
                    repl = f"{getter}(R.string.{name}, {', '.join(occ['args'])})"
                else:
                    repl = f"{getter}(R.string.{name})"
                arg_idx = 1 if "Toast" in occ["method"] or "Snackbar" in occ["method"] else 0
                raw_line = replace_method_arg(raw_line, raw_line.find(occ["method"]), arg_idx, repl)
            lines[line_no - 1] = raw_line
        f.write_text("\n".join(lines), encoding="utf-8")

    # 10. Replace XML hardcoded occurrences
    print(f"Replacing {len(xml_all_occurrences)} XML hardcoded occurrences...")
    for occ in xml_all_occurrences:
        f = Path(occ["file"])
        content = f.read_text(encoding="utf-8")
        lines = content.splitlines()
        line_no = occ["line"]
        raw_line = lines[line_no - 1]
        name = value_to_name.get(occ["value"])
        if name:
            pattern = rf'({re.escape(occ["attr"])}\s*=\s*")[^"]*"'
            raw_line = re.sub(pattern, rf'\1@string/{name}"', raw_line)
            lines[line_no - 1] = raw_line
            f.write_text("\n".join(lines), encoding="utf-8")

    # Save mapping
    mapping = {"old_to_new": old_invalid_to_new, "value_to_name": dict(value_to_name)}
    (PROJECT / "i18n_final_map.json").write_text(json.dumps(mapping, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Done.")

if __name__ == "__main__":
    main()
