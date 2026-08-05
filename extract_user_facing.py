import re, json

with open('chinese_candidates.json', encoding='utf-8') as f:
    data = json.load(f)

user_facing_keywords = [
    'Toast', 'setPositiveButton', 'setNegativeButton', 'setNeutralButton',
    'setTitle', 'setMessage', 'setView', 'setText', 'setHint', 'contentDescription',
    'notify', 'createChooser', 'appendToChatHistory', 'buildPersistNotification',
    'buildPersist', 'notifyChatReply', 'notifyOperation', 'notifyScheduledTask',
    'startForeground', 'AlertDialog', 'new Dialog',
    'getString',
]

# filter entries whose code contains any keyword
filtered = []
for e in data:
    code = e['code']
    if any(k in code for k in user_facing_keywords):
        filtered.append(e)

print('user-facing lines', len(filtered))

# collect unique texts (excluding those that are just format fragments like "链: ")
texts = {}
for e in filtered:
    for t in e['texts']:
        # ignore pure punctuation/space+Chinese? include all for now
        texts.setdefault(t, []).append((e['file'], e['line']))

# output
items = [{'text': k, 'occurrences': v} for k, v in texts.items()]
items.sort(key=lambda x: x['text'])
with open('user_facing_texts.json', 'w', encoding='utf-8') as f:
    json.dump(items, f, ensure_ascii=False, indent=2)
print('unique texts', len(items))
