#!/usr/bin/env python3
import re
CH = re.compile(r"[\u4e00-\u9fff]")
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

def remove_block_comments(text):
    return re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)

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

text = '''                .setPositiveButton("保存", (d, w) -> {'''
no_block = remove_block_comments(text)
for i, raw_line in enumerate(no_block.splitlines(), 1):
    if not CH.search(raw_line):
        continue
    line = raw_line.strip()
    if any(re.search(p, line) for p in SKIP):
        continue
    for pat, arg_idx in USER_PATTERNS:
        for m in re.finditer(pat, raw_line):
            print("match", pat, "pos", m.start())
            args = extract_top_level_args(raw_line[m.start():])
            print("args", args)
