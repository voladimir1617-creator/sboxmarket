// Agentation dev-toolbar entry point. Loaded as: <script type="module" src=".../mount.mjs?app=Name">
//
// Both host apps send Content-Security-Policy: script-src 'self' with NO 'unsafe-inline'
// and no nonce, so there is no inline script anywhere in this feature -- not even an
// import map, which is itself an inline script and would be refused. Every specifier in
// agentation.mjs has been rewritten to a relative sibling instead.
//
// REACT IS BORROWED WHEN THE PAGE ALREADY HAS IT. sboxmarket's index.html loads React and
// ReactDOM 18.2.0 UMD from unpkg (with SRI) before this module runs -- a deferred module
// always runs after the classic scripts in the document. Overwriting window.React there
// would swap the library the application itself is mid-render against, for no gain. So the
// vendored 18.3.1 copies are a FALLBACK, used only when the page has no React: which is
// rusty-royale always, and sboxmarket whenever unpkg is unreachable (i.e. offline).
const BASE = new URL('.', import.meta.url);
const appName = new URL(import.meta.url).searchParams.get('app') || 'app';
const MOUNT_ID = 'agentation-dev-root';

function loadScript(file) {
  return new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = new URL(file, BASE).href;   // same-origin, so script-src 'self' allows it
    s.async = false;
    s.onload = resolve;
    s.onerror = () => reject(new Error('[agentation] failed to load ' + file));
    document.head.appendChild(s);
  });
}

function majorOf(lib) {
  const v = lib && typeof lib.version === 'string' ? parseInt(lib.version, 10) : NaN;
  return Number.isFinite(v) ? v : 0;
}

async function ensureReact() {
  const haveReact = !!(globalThis.React && globalThis.React.createElement);
  const haveDom = !!(globalThis.ReactDOM && globalThis.ReactDOM.createRoot);
  if (haveReact && haveDom) {
    const major = majorOf(globalThis.ReactDOM) || majorOf(globalThis.React);
    if (major >= 18) {
      console.info('[agentation] reusing the React already on the page: ' + (globalThis.React.version || '?'));
      return;
    }
    // Refuse rather than clobber: replacing a React the app is already using is a worse
    // outcome than no toolbar, and a silent half-load would be worse than both.
    throw new Error('[agentation] page has React ' + (globalThis.React.version || '?') +
                    '; the toolbar needs >= 18 and will not replace the copy the app is using');
  }
  if (haveReact !== haveDom) {
    throw new Error('[agentation] page has only one half of React loaded; refusing to guess');
  }
  await loadScript('react.production.min.js');
  await loadScript('react-dom.production.min.js');
  console.info('[agentation] loaded vendored React ' + (globalThis.React && globalThis.React.version));
}

// agentation.mjs carries ONE unsubstituted bundler define:
//     const isDevMode = process.env.NODE_ENV === "development";
// The published ESM build expects a bundler to replace it. Loaded straight into a
// browser it throws `ReferenceError: process is not defined` from inside the first
// render, which surfaces as a react-dom stack trace and an empty toolbar -- measured.
// "production" is the correct value for these two hosts: the only thing isDevMode
// gates is a settings toggle for including REACT COMPONENT NAMES in an annotation,
// and neither app has a React component tree to name.
function defineProcessEnv() {
  if (typeof globalThis.process === 'undefined') {
    globalThis.process = { env: { NODE_ENV: 'production' } };
  } else if (!globalThis.process.env) {
    globalThis.process.env = { NODE_ENV: 'production' };
  }
}

async function start() {
  if (document.getElementById(MOUNT_ID)) return;
  defineProcessEnv();
  await ensureReact();
  // Dynamic import so agentation.mjs -- and its react-shim imports -- are only evaluated
  // once window.React exists. A static import would run before ensureReact().
  const { Agentation } = await import('./agentation.mjs');
  const host = document.createElement('div');
  host.id = MOUNT_ID;
  host.setAttribute('data-agentation-dev', '');
  document.body.appendChild(host);
  globalThis.ReactDOM.createRoot(host).render(
    globalThis.React.createElement(Agentation, {
      appName,
      copyToClipboard: true,
      // No `endpoint` and no `webhookUrl`: annotations live in localStorage and the
      // toolbar makes no network request of any kind.
      onCopy: (output) => { globalThis.__agentationLastCopy = output; },
      onSubmit: (output) => { globalThis.__agentationLastCopy = output; }
    })
  );
  globalThis.__agentationMounted = true;
  console.info('[agentation] dev toolbar mounted for ' + appName);
}

start().catch((e) => console.error(e));
