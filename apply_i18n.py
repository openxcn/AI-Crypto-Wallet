#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Extract user-facing Chinese strings and apply i18n replacements."""
import json
import os
import re
from pathlib import Path
from collections import defaultdict

PROJECT = Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android")
JAVA_DIR = PROJECT / "app/src/main/java/com/aicryptowallet/app"
LAYOUT_DIR = PROJECT / "app/src/main/res/layout"
VALUES_DIR = PROJECT / "app/src/main/res/values"
VALUES_EN = PROJECT / "app/src/main/res/values-en"
VALUES_JA = PROJECT / "app/src/main/res/values-ja"
VALUES_DE = PROJECT / "app/src/main/res/values-de"

CHINESE_RE = re.compile(r'[\u4e00-\u9fff]')
STRING_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')

# (pattern, arg_index) arg_index is 0-based within method parentheses
USER_FACING_PATTERNS = [
    (r'\.setText\s*\(', 0),
    (r'\.setTitle\s*\(', 0),
    (r'\.setMessage\s*\(', 0),
    (r'\.setHint\s*\(', 0),
    (r'\.setError\s*\(', 0),
    (r'\.setPositiveButton\s*\(', 0),
    (r'\.setNegativeButton\s*\(', 0),
    (r'\.setNeutralButton\s*\(', 0),
    (r'Toast\.makeText\s*\(', 1),
    (r'\.getMenu\s*\(\s*\)\.add\s*\(', 3),
    (r'popup\.getMenu\s*\(\s*\)\.add\s*\(', 3),
    (r'\.setContentTitle\s*\(', 0),
    (r'\.setContentText\s*\(', 0),
    (r'\.setSubText\s*\(', 0),
    (r'\.setTicker\s*\(', 0),
    (r'\.setDialogTitle\s*\(', 0),
    (r'\.setSummary\s*\(', 0),
    (r'\.setAction\s*\(', 0),
    (r'\.createChooser\s*\(', 1),
    (r'\.setLabel\s*\(', 0),
    (r'\.setPlaceholderText\s*\(', 0),
    (r'\.setPrompt\s*\(', 0),
    (r'Snackbar\.make\s*\(', 1),
]

SKIP_PATTERNS = [
    r'^\s*//',
    r'^\s*\*',
    r'^\s*/\*',
    r'\bLog\.[vdiew]\s*\(',
    r'\bLogger\.[a-zA-Z]+\s*\(',
    r'new\s+SimpleDateFormat\s*\(',
    r'\.format\s*\(',
    r'new\s+File\s*\(',
    r'\.putString\s*\(',
    r'\.getString\s*\(',
    r'\.putInt\s*\(',
    r'\.getInt\s*\(',
    r'\.putBoolean\s*\(',
    r'\.getBoolean\s*\(',
    r'optString\s*\(',
    r'getString\s*\(',
    r'\.getJSONArray\s*\(',
    r'\.put\s*\(',
    r'\.remove\s*\(',
    r'toSystemPrompt',
    r'systemPrompt',
    r'\.print\s*\(',
    r'\.println\s*\(',
    r'Log\.getStackTraceString',
    r'\.tag\s*\(',
    r'\.d\s*\(',
    r'\.e\s*\(',
    r'\.i\s*\(',
    r'\.v\s*\(',
    r'\.w\s*\(',
]

XML_USER_ATTRS = [
    ('android:text', 'text'),
    ('android:hint', 'hint'),
    ('android:title', 'title'),
    ('app:title', 'title'),
    ('android:contentDescription', 'desc'),
    ('android:dialogTitle', 'title'),
    ('android:summary', 'summary'),
    ('android:subtitle', 'subtitle'),
    ('android:textOn', 'text'),
    ('android:textOff', 'text'),
    ('android:tooltipText', 'tooltip'),
]

def is_user_facing_context(line):
    return any(re.search(p, line) for p, _ in USER_FACING_PATTERNS)

def should_skip_line(line):
    return any(re.search(p, line) for p in SKIP_PATTERNS)

def extract_method_args(line, method_pos):
    """Extract all top-level arguments inside parentheses starting at method_pos."""
    start = line.find('(', method_pos)
    if start == -1:
        return []
    depth = 0
    in_string = False
    escape = False
    args = []
    current = []
    i = start + 1
    while i < len(line):
        c = line[i]
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
                if depth == 0:
                    arg = ''.join(current).strip()
                    if arg:
                        args.append(arg)
                    return args
                depth -= 1
                current.append(c)
            elif c == ',' and depth == 0:
                arg = ''.join(current).strip()
                if arg:
                    args.append(arg)
                current = []
            else:
                current.append(c)
        else:
            current.append(c)
        i += 1
    return []

def tokenize_arg(expr):
    """Tokenize a concatenated expression into string literals and non-string tokens."""
    tokens = []
    i = 0
    while i < len(expr):
        if expr[i] in ' \t\n+':
            i += 1
            continue
        if expr[i] == '"':
            j = i + 1
            while j < len(expr):
                if expr[j] == '\\' and j+1 < len(expr):
                    j += 2
                elif expr[j] == '"':
                    break
                else:
                    j += 1
            literal = expr[i+1:j]
            tokens.append(('str', literal))
            i = j + 1
        else:
            j = i
            depth = 0
            in_str = False
            escape = False
            while j < len(expr):
                c = expr[j]
                if escape:
                    escape = False
                elif c == '\\':
                    escape = True
                elif c == '"':
                    in_str = not in_str
                elif not in_str:
                    if c == '(':
                        depth += 1
                    elif c == ')':
                        depth -= 1
                    elif c == '+' and depth == 0:
                        break
                j += 1
            token = expr[i:j].strip()
            if token:
                tokens.append(('var', token))
            i = j
    return tokens

def build_format_and_args(tokens):
    fmt_parts = []
    args = []
    var_index = 1
    for kind, value in tokens:
        if kind == 'str':
            fmt_parts.append(value)
        else:
            fmt_parts.append('%' + str(var_index) + '$s')
            args.append(value)
            var_index += 1
    fmt = ''.join(fmt_parts)
    return fmt, args

def extract_java_occurrences(text):
    occurrences = []
    lines = text.splitlines()
    for i, raw_line in enumerate(lines, 1):
        if not CHINESE_RE.search(raw_line):
            continue
        line = raw_line.strip()
        if should_skip_line(line):
            continue
        if not is_user_facing_context(raw_line):
            continue
        for pattern, arg_idx in USER_FACING_PATTERNS:
            for m in re.finditer(pattern, raw_line):
                method_start = m.start()
                args = extract_method_args(raw_line, method_start)
                if arg_idx >= len(args):
                    continue
                expr = args[arg_idx]
                tokens = tokenize_arg(expr)
                chinese_literals = [v for k, v in tokens if k == 'str' and CHINESE_RE.search(v)]
                if not chinese_literals:
                    continue
                occurrences.append({
                    'line': i,
                    'raw_line': raw_line,
                    'method': m.group(0),
                    'expr': expr,
                    'tokens': tokens,
                })
    return occurrences

def extract_xml_occurrences(text):
    occurrences = []
    lines = text.splitlines()
    for i, raw_line in enumerate(lines, 1):
        if not CHINESE_RE.search(raw_line):
            continue
        for attr, prefix in XML_USER_ATTRS:
            pattern = rf'({attr}\s*=\s*")([^"]*)"'
            for m in re.finditer(pattern, raw_line):
                value = m.group(2)
                if value.startswith('@') or value.startswith('?'):
                    continue
                if CHINESE_RE.search(value):
                    occurrences.append({
                        'line': i,
                        'raw_line': raw_line,
                        'attr': attr,
                        'prefix': prefix,
                        'value': value,
                    })
    return occurrences

def suggest_name(fmt, context):
    s = re.sub(r'%\d+\$[sdxf]', '', fmt)
    s = re.sub(r'[^\u4e00-\u9fff\w]', '_', s)
    s = re.sub(r'_+', '_', s).strip('_')
    if 'setText' in context:
        prefix = 'text'
    elif 'setTitle' in context or 'getSupportActionBar().setTitle' in context:
        prefix = 'title'
    elif 'setMessage' in context:
        prefix = 'msg'
    elif 'Toast' in context:
        prefix = 'toast'
    elif 'setPositiveButton' in context or 'setNegativeButton' in context or 'setNeutralButton' in context:
        prefix = 'btn'
    elif 'setHint' in context:
        prefix = 'hint'
    elif 'xml_attr' in context:
        prefix = 'label'
    else:
        prefix = 'str'
    chars = re.findall(r'[\u4e00-\u9fff]', s)
    suffix = ''.join(chars[:5]) if chars else s[:10]
    if not suffix:
        suffix = 'item'
    return f"{prefix}_{suffix}".lower()

def make_name_unique(name, used):
    if name not in used:
        return name
    i = 2
    while f"{name}_{i}" in used:
        i += 1
    return f"{name}_{i}"

def main():
    all_occurrences = []
    for f in sorted(JAVA_DIR.rglob("*.java")):
        text = f.read_text(encoding="utf-8")
        occs = extract_java_occurrences(text)
        for occ in occs:
            occ['file'] = f
            occ['file_rel'] = str(f.relative_to(PROJECT))
            all_occurrences.append(occ)
    for f in sorted(LAYOUT_DIR.rglob("*.xml")):
        text = f.read_text(encoding="utf-8")
        occs = extract_xml_occurrences(text)
        for occ in occs:
            occ['file'] = f
            occ['file_rel'] = str(f.relative_to(PROJECT))
            all_occurrences.append(occ)

    by_fmt = defaultdict(list)
    for occ in all_occurrences:
        if 'tokens' in occ:
            fmt, args = build_format_and_args(occ['tokens'])
            occ['fmt'] = fmt
            occ['args'] = args
            occ['has_vars'] = len(args) > 0
        else:
            occ['fmt'] = occ['value']
            occ['args'] = []
            occ['has_vars'] = False
        by_fmt[occ['fmt']].append(occ)

    used_names = set()
    entries = []
    fmt_to_name = {}
    for fmt, occs in sorted(by_fmt.items(), key=lambda x: x[0]):
        context = occs[0]['raw_line']
        name = suggest_name(fmt, context)
        name = make_name_unique(name, used_names)
        used_names.add(name)
        fmt_to_name[fmt] = name
        entries.append({
            'name': name,
            'zh': fmt,
            'has_vars': occs[0]['has_vars'],
            'count': len(occs),
            'sample_file': occs[0]['file_rel'],
            'sample_line': occs[0]['line'],
            'sample_context': context.strip(),
        })

    data = {
        'total_occurrences': len(all_occurrences),
        'unique_strings': len(entries),
        'strings': entries,
    }
    (PROJECT / "extracted_strings.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Extracted {len(all_occurrences)} occurrences, {len(entries)} unique strings")

    mapping = {
        'fmt_to_name': fmt_to_name,
        'occurrences': [
            {
                'file': str(occ['file']),
                'line': occ['line'],
                'method': occ.get('method', ''),
                'expr': occ.get('expr', ''),
                'attr': occ.get('attr', ''),
                'value': occ.get('value', ''),
                'fmt': occ['fmt'],
                'args': occ['args'],
            }
            for occ in all_occurrences
        ]
    }
    (PROJECT / "i18n_mapping.json").write_text(json.dumps(mapping, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Saved mapping to i18n_mapping.json")

if __name__ == "__main__":
    main()
