import re, json, codecs

h = open('market_search.html', encoding='utf-8', errors='replace').read()
# The page embeds JSON-in-JSON, so quotes arrive as \" and unicode as \\uXXXX
pairs = re.findall(r'\\"appid\\":(\d+),\\"name\\":\\"((?:[^\\]|\\\\.|\\u[0-9a-fA-F]{4}|\\[^u])*?)\\"', h)
d = {}
for a, n in pairs:
    try:
        n2 = codecs.decode(n.replace('\\\\', '\\'), 'unicode_escape')
    except Exception:
        n2 = n
    d.setdefault(int(a), n2)
print('distinct appids with a Steam market:', len(d))
json.dump({str(k): v for k, v in d.items()}, open('market_apps.json', 'w', encoding='utf-8'),
          indent=0, ensure_ascii=False)
for a in sorted(d):
    print(a, d[a])
