"""Dead CSS heuristic scanner with dynamic-pattern awareness."""
import re
import os

# Load all class selectors
with open('/tmp/all_css_classes.txt') as f:
    css_classes = [l.strip() for l in f if l.strip()]

# Read all source files (HTML/JS) under static dir, excluding /css
src_text = ''
src_dir = 'src/main/resources/static'
for root, dirs, files in os.walk(src_dir):
    norm = root.replace(os.sep, '/')
    if '/css' in norm or norm.endswith('/css'):
        continue
    for fn in files:
        if fn.endswith(('.html', '.js')):
            with open(os.path.join(root, fn), 'r', encoding='utf-8', errors='ignore') as f:
                src_text += f.read() + '\n'

print(f'Source text size: {len(src_text)} chars')

# Detect template-literal class assembly: `foo-${var}`, `foo-${var}-bar`
template_patterns = re.findall(r'`([a-zA-Z_][a-zA-Z0-9_-]*-)\$\{', src_text)
template_prefixes = set(template_patterns)

# Concatenation: 'foo-' + ... or ... + 'foo-'
concat1 = re.findall(r"""['"`]([a-zA-Z_][a-zA-Z0-9_-]*-)['"`]\s*\+""", src_text)
concat2 = re.findall(r"""\+\s*['"`]([a-zA-Z_][a-zA-Z0-9_-]*-)['"`]""", src_text)
concat_prefixes = set(concat1) | set(concat2)

# Suffix patterns inside template literals: `${kind}-active`, `prefix-${var}-light`
suffix_patterns = re.findall(r'\$\{[^}]*\}([-_][a-zA-Z][a-zA-Z0-9_-]*)', src_text)
suffix_set = set(suffix_patterns)
# Also `-${var}` -> capture prefix
mid_patterns = re.findall(r'([a-zA-Z_][a-zA-Z0-9_-]*-)\$\{', src_text)
template_prefixes |= set(mid_patterns)

dyn_prefixes = template_prefixes | concat_prefixes
print(f'Dynamic prefixes: {len(dyn_prefixes)}')
print(f'Dynamic suffixes: {len(suffix_set)}')

# Build set of all word-boundary tokens in source. This is the fast lookup.
src_tokens = set(re.findall(r'[a-zA-Z_][a-zA-Z0-9_-]*', src_text))
print(f'Source tokens: {len(src_tokens)}')

matched = 0
unmatched = []
maybe_dynamic = []

for cls in css_classes:
    if cls in src_tokens:
        matched += 1
        continue
    is_maybe_dynamic = False
    for prefix in dyn_prefixes:
        if cls.startswith(prefix) and len(cls) > len(prefix):
            is_maybe_dynamic = True
            break
    if not is_maybe_dynamic:
        for sfx in suffix_set:
            if cls.endswith(sfx) and len(cls) > len(sfx):
                is_maybe_dynamic = True
                break
    if is_maybe_dynamic:
        maybe_dynamic.append(cls)
    else:
        unmatched.append(cls)

total = len(css_classes)
print()
print(f'Matched (whole-word):                   {matched:>6} ({100*matched/total:.1f}%)')
print(f'Maybe-dynamic (excluded from delete):   {len(maybe_dynamic):>6} ({100*len(maybe_dynamic)/total:.1f}%)')
print(f'Unmatched & not-dynamic (likely dead):  {len(unmatched):>6} ({100*len(unmatched)/total:.1f}%)')

with open('/tmp/dead_css.txt', 'w') as f:
    for c in sorted(unmatched):
        f.write(c + '\n')
with open('/tmp/maybe_dynamic_css.txt', 'w') as f:
    for c in sorted(maybe_dynamic):
        f.write(c + '\n')
with open('/tmp/dyn_prefixes.txt', 'w') as f:
    for p in sorted(dyn_prefixes):
        f.write(p + '\n')
