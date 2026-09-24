// Bridges ESM `import ... from "react-dom"` onto the ReactDOM 18.3.1 UMD global.
const D = globalThis.ReactDOM;
if (!D) throw new Error('[agentation] window.ReactDOM missing — load react-dom.production.min.js first.');
export default D;
export const createPortal = D.createPortal;
export const createRoot = D.createRoot;
export const flushSync = D.flushSync;
