#!/usr/bin/env python3
"""Inventory of Persian strings per source file."""
import re, glob, os
fa = re.compile(r'[\u0600-\u06FF]')
base = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main')
for f in sorted(glob.glob(base + '/**/*', recursive=True)):
    if not (f.endswith('.kt') or f.endswith('.xml')):
        continue
    t = open(f, encoding='utf-8').read()
    n = sum(1 for l in t.splitlines() if fa.search(l))
    if n:
        print(n, f.replace(base + '/', ''))
