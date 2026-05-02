const fs = require('fs');
const path = require('path');
const targets = [
  'src/main/resources/static/js/modals.js',
  'src/main/resources/static/js/app.js',
  'src/main/resources/static/js/csfloat-modals.js',
  'src/main/resources/static/js/staff-modals.js',
  'src/main/resources/static/js/cards.js',
  'src/main/resources/static/js/info-modal.js',
  'src/main/resources/static/js/help-modal.js',
  'src/main/resources/static/js/nav-widgets.js',
  'src/main/resources/static/js/primitives.js',
  'src/main/resources/static/js/api.js'
];
const root = process.cwd();
const SAFE = new Set([
  'fmt','toast','useState','useEffect','useRef','useCallback','useMemo',
  'navigate','console','window','document','location','localStorage','sessionStorage',
  'JSON','Math','Date','Number','Promise','Array','Object','String','Boolean',
  'true','false','null','undefined','this','arguments',
  'fetch','URLSearchParams','URL','setTimeout','setInterval','clearTimeout','clearInterval',
  'event','err','res','val','msg','idx','len','key','value','data','item','items','listing',
  'me','user','i','j','k','n','t','e','r','s','a','b','c','x','y'
]);
const interp = /\$\{\s*([a-zA-Z_$][a-zA-Z0-9_$]*)\s*[\}\.\[\?]/g;
for (const f of targets) {
  const code = fs.readFileSync(path.join(root, f), 'utf8');
  const ids = new Set();
  let m;
  while ((m = interp.exec(code))) {
    if (m[1].length > 3 && !SAFE.has(m[1])) ids.add(m[1]);
  }
  const suspicious = [];
  for (const id of ids) {
    const re = new RegExp('\\b' + id + '\\b', 'g');
    const matches = code.match(re) || [];
    if (matches.length === 1) suspicious.push(id);
  }
  if (suspicious.length) console.log(f + ':', suspicious);
}
