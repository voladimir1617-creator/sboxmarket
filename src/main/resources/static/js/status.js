// Static status page — pings public endpoints and renders a green/amber/red
// summary the same way any status dashboard does. Zero server-side plumbing:
// every probe is a vanilla fetch from the client.
//
// Extracted from status.html <script> block so the page complies with the
// site-wide CSP (script-src 'self' https://unpkg.com …). Inline scripts
// were blocked; loading this file via <script src=> keeps the strict CSP.
const CHECKS = [
  { id: 'api',       name: 'API',             endpoint: '/api/health',
    sub: 'Core REST API', ok: (r) => r.ok },
  // Batch 689 — add /api/ready so the status page surfaces DB
  // connectivity separately from JVM liveness. /api/health stays
  // green as long as Spring is up; /api/ready goes red when the
  // DB pool is unreachable. Two green rows = fully operational;
  // /api/health green + /api/ready red = user-visible degradation.
  { id: 'database-conn', name: 'Database',    endpoint: '/api/ready',
    sub: 'Connection pool · isValid() probe', ok: (r) => r.ok },
  { id: 'market',    name: 'Marketplace',     endpoint: '/api/listings/stats',
    sub: 'Active listings + floor prices', ok: (r) => r.ok },
  { id: 'database',  name: 'Item catalogue',  endpoint: '/api/items?category=Hats',
    sub: 'Item lookups', ok: (r) => r.ok },
  { id: 'auctions',  name: 'Auction engine',  endpoint: '/api/listings/ending-soon',
    sub: 'Bid placement + anti-snipe', ok: (r) => r.ok }
];

async function runChecks() {
  const results = await Promise.all(CHECKS.map(async c => {
    const t0 = performance.now();
    try {
      const r = await fetch(c.endpoint, { credentials: 'same-origin' });
      const ms = Math.round(performance.now() - t0);
      return { ...c, status: c.ok(r) ? 'up' : (r.status >= 500 ? 'down' : 'warn'), httpStatus: r.status, ms };
    } catch (e) {
      return { ...c, status: 'down', httpStatus: 0, ms: Math.round(performance.now() - t0) };
    }
  }));
  render(results);
}

function render(results) {
  const anyDown  = results.some(r => r.status === 'down');
  const anyWarn  = results.some(r => r.status === 'warn');
  const overall  = anyDown ? 'down' : anyWarn ? 'warn' : 'up';
  const headline = document.getElementById('headline');
  const sub      = document.getElementById('sub');
  headline.innerHTML =
    '<span class="status-dot ' + overall + '"></span>' +
    (overall === 'up'   ? 'All systems operational' :
     overall === 'warn' ? 'Degraded performance'   :
                          'Partial outage');
  sub.textContent =
    overall === 'up'   ? 'Every probed service is responding normally.' :
    overall === 'warn' ? 'Some services are slower than expected — bids and buys may be queued.' :
                         'One or more services are unreachable. Trades in flight are safe — see below.';

  const grid = document.getElementById('grid');
  grid.innerHTML = '';
  results.forEach(r => {
    const row = document.createElement('div');
    row.className = 'status-row';
    row.innerHTML =
      '<div>' +
        '<div class="status-row-name">' + r.name + '</div>' +
        '<div class="status-row-sub">' + r.sub + ' · ' + r.endpoint + ' · ' + r.ms + 'ms</div>' +
      '</div>' +
      '<div class="status-row-badge ' + r.status + '">' +
        (r.status === 'up' ? 'OPERATIONAL' : r.status === 'warn' ? 'DEGRADED' : 'DOWN') +
      '</div>';
    grid.appendChild(row);
  });
}

runChecks();
setInterval(runChecks, 30_000);

// Batch 866 — surface the current pod's version + uptime so
// operators reading this page can tell at a glance "is this the
// pod that got redeployed 20min ago, or a lingering old replica?"
// Cached for 10min by the server so this is cheap.
async function renderVersion() {
  try {
    const r = await fetch('/api/version', { credentials: 'same-origin' });
    if (!r.ok) return;
    const data = await r.json();
    const el = document.getElementById('version-line');
    if (!el) return;
    const bits = [];
    if (data.version) bits.push('v' + data.version);
    if (data.startupAt) {
      const ms = Date.now() - data.startupAt;
      const h  = Math.floor(ms / 3_600_000);
      const m  = Math.floor((ms % 3_600_000) / 60_000);
      const label = h > 0 ? (h + 'h ' + m + 'm') : (m + 'm');
      bits.push('uptime ' + label);
      bits.push('started ' + new Date(data.startupAt).toLocaleString());
    }
    el.textContent = bits.join(' · ');
  } catch (_) { /* silent — version line stays blank */ }
}
renderVersion();
setInterval(renderVersion, 60_000);
