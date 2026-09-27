// Bridges ESM `import ... from "react"` onto the React 18.3.1 UMD global.
const R = globalThis.React;
if (!R) throw new Error('[agentation] window.React missing — load react.production.min.js first.');
export default R;
export const createElement = R.createElement;
export const forwardRef = R.forwardRef;
export const memo = R.memo;
export const useCallback = R.useCallback;
export const useEffect = R.useEffect;
export const useId = R.useId;
export const useImperativeHandle = R.useImperativeHandle;
export const useLayoutEffect = R.useLayoutEffect;
export const useMemo = R.useMemo;
export const useRef = R.useRef;
export const useState = R.useState;
export const useSyncExternalStore = R.useSyncExternalStore;
export const Fragment = R.Fragment;
