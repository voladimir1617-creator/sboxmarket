// Entry point. Mounts <App/> wrapped in the error boundary.
// Loaded as a module from index.html: <script type="module" src="/js/main.js"></script>
import { h, createRoot } from './utils.js';
import { ErrorBoundary, App } from './app.js';

// Global safety nets so silent failures still surface in the console.
window.addEventListener('error',              e => console.error('[window error]',       e.error || e.message));
window.addEventListener('unhandledrejection', e => console.error('[unhandled rejection]', e.reason));

createRoot(document.getElementById('root')).render(h(ErrorBoundary, null, h(App)));
