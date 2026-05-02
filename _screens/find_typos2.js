// Find identifiers used inside JSX/expression children of h(...) calls that
// have only ONE total occurrence in the file — likely typos / undefined refs.
const fs = require('fs');
const path = require('path');
const targets = [
  'src/main/resources/static/js/modals.js',
  'src/main/resources/static/js/app.js',
  'src/main/resources/static/js/csfloat-modals.js',
  'src/main/resources/static/js/staff-modals.js',
  'src/main/resources/static/js/cards.js',
  'src/main/resources/static/js/help-modal.js',
  'src/main/resources/static/js/nav-widgets.js',
  'src/main/resources/static/js/primitives.js'
];
const SAFE = new Set('fmt toast useState useEffect useRef useCallback useMemo navigate console window document location localStorage sessionStorage JSON Math Date Number Promise Array Object String Boolean true false null undefined this arguments fetch URLSearchParams URL setTimeout setInterval clearTimeout clearInterval h React Map Set'.split(' '));
for (const f of targets) {
  const code = fs.readFileSync(path.join(process.cwd(), f), 'utf8');
  // Match identifiers preceded by ` ${ ` or `(... ` inside h( call args; rough heuristic.
  // Just look for `?\s+\w+\s*:` ternary RHS that single-references a var.
  const re = /\b([a-zA-Z_$][a-zA-Z0-9_$]{4,})\b/g;
  const counts = new Map();
  let m;
  while ((m = re.exec(code))) {
    counts.set(m[1], (counts.get(m[1]) || 0) + 1);
  }
  const single = [...counts.entries()].filter(([k, c]) => c === 1 && !SAFE.has(k) && !/^[A-Z]/.test(k) && k.length > 5);
  // Filter to ones that look like identifiers, not strings / class names
  const suspicious = single.filter(([k]) => {
    // Skip if it appears inside a quoted string
    const re2 = new RegExp('["\']' + k + '["\']');
    return !re2.test(code);
  });
  if (suspicious.length) console.log(f + ':', suspicious.slice(0,15).map(([k]) => k));
}
