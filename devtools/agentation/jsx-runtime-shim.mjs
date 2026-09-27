// Minimal react/jsx-runtime over React.createElement. The UMD build predates the
// automatic runtime, so the three entry points it needs are reimplemented here.
// createElement(type, config) already lifts config.key/config.ref and keeps
// config.children as props.children, which is exactly the jsx() contract.
const R = globalThis.React;
if (!R) throw new Error('[agentation] window.React missing — load react.production.min.js first.');
export const Fragment = R.Fragment;
export function jsx(type, config, maybeKey) {
  return maybeKey === undefined
    ? R.createElement(type, config)
    : R.createElement(type, Object.assign({}, config, { key: maybeKey }));
}
export const jsxs = jsx;
export const jsxDEV = jsx;
