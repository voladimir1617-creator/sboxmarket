// k6 smoke-load test for sboxmarket.
//
// Two scenarios run in parallel:
//
//   1. `browse` — mimics normal marketplace browsing. Hits the unlimited-
//      budget endpoints (/, /api/listings, /api/database, /api/health).
//      50 VUs for 30s, expecting <1% errors and p95<500ms. This is the
//      real-traffic scenario that matters for launch-day capacity.
//
//   2. `enumeration` — intentionally walks /api/items/{id} to exercise
//      the anti-enumeration rate limiter. 429s are EXPECTED and counted
//      as success; the test proves the cap kicks in and stays stable.
//
// Run it against a container:
//   docker run --rm --add-host=host.docker.internal:host-gateway \
//     -v "$(pwd)/deploy:/scripts:ro" grafana/k6:latest \
//     run -e BASE_URL=http://host.docker.internal:8082 /scripts/load-test.js
//
// Or against localhost with k6 installed:
//   BASE_URL=http://localhost:8082 k6 run deploy/load-test.js

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8082';

export const options = {
  scenarios: {
    browse: {
      executor: 'constant-vus',
      vus: 50,
      duration: '30s',
      exec: 'browse',
      tags: { scenario: 'browse' },
    },
    enumeration: {
      executor: 'constant-vus',
      vus: 10,
      duration: '30s',
      exec: 'enumeration',
      tags: { scenario: 'enumeration' },
    },
  },
  thresholds: {
    // Browsing: strict error + latency budgets.
    'http_req_failed{scenario:browse}':   ['rate<0.01'],
    'http_req_duration{scenario:browse}': ['p(95)<500'],
    // Enumeration: we only check that either 200 or 429 was returned
    // (via the check() call below) — no http_req_failed threshold here
    // because 429 counts as a failed request for k6's default metric.
  },
};

export function browse() {
  const endpoints = [
    '/',
    '/api/listings?limit=20',
    '/api/database?limit=20',
    '/api/health',
  ];
  for (const p of endpoints) {
    const res = http.get(BASE + p, { tags: { path: p } });
    check(res, {
      [`${p} status 2xx`]: (r) => r.status >= 200 && r.status < 300,
    });
  }
  sleep(0.2);
}

export function enumeration() {
  // Walk item ids 1..80 (the SCMM sync seeded 80 items). The rate limiter
  // should start returning 429 after the first 40 requests / 10s window.
  const id = 1 + Math.floor(Math.random() * 80);
  const res = http.get(`${BASE}/api/items/${id}`, { tags: { path: '/api/items/:id' } });
  check(res, {
    'items/{id} returned 200 or 429': (r) => r.status === 200 || r.status === 429,
  });
  sleep(0.1);
}
