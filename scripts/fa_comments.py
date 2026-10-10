#!/usr/bin/env python3
import re, glob, os
fa = re.compile(r'[\u0600-\u06FF]')
base = '/home/kali/android-agent/app/src/main/java'
seen = {}
for f in glob.glob(base + '/**/*.kt', recursive=True):
    src = open(f, encoding='utf-8').read()
    # block comments /* */
    for m in re.finditer(r'/\*[\s\S]*?\*/', src):
        if fa.search(m.group(0)):
            seen.setdefault(('B', m.group(0)), os.path.basename(f))
    # line comments: capture only lines where // content has persian
    lines = src.splitlines()
    for i, l in enumerate(lines):
        idx = l.find('//')
        if idx >= 0 and fa.search(l):
            # avoid // inside string literal — crude: count quotes before idx
            if l[:idx].count('"') % 2 == 0:
                seen.setdefault(('L', l[:idx].rstrip(), l[idx:].strip()), os.path.basename(f))
print(len(seen))
# json dump
data = []
for k, fn in seen.items():
    if k[0] == 'B':
        data.append({'kind':'B','text':k[1],'file':fn})
    else:
        data.append({'kind':'L','prefix':k[1],'comment':k[2],'file':fn})
import json
json.dump(data, open('/tmp/fa_comments.json','w'), ensure_ascii=False, indent=0)
for d in data:
    t = d.get('text') or d.get('comment')
    print(d['kind'], '|', t.replace('\n',' ⏎ ')[:180])
