// @ts-check
const { test, expect } = require('@playwright/test');

// True bargain (offer) round-trip at the API contract level — the existing
// offer.spec only drives the dialog's client-side guards. This proves the
// server side a real customer depends on: a below-asking offer is created
// PENDING and shows up in the buyer's outgoing list with the right asking
// price, while a sub-floor (<= $0) offer is rejected. Cleans up after itself
// by cancelling all outgoing offers (so it's re-runnable).
test('a below-asking offer is created PENDING and surfaces in outgoing; sub-floor is rejected', async ({ page }) => {
  const api = page.request;

  // CSRF (double-submit cookie echoed as a header).
  const cookies = await page.context().cookies();
  const csrf = cookies.find((c) => c.name === 'sbox_csrf')?.value;
  expect(csrf, 'sbox_csrf cookie present after dev-login').toBeTruthy();
  const hdrs = { 'X-CSRF-Token': csrf, 'Content-Type': 'application/json' };

  // Start from a clean slate so assertions on "outgoing" are deterministic.
  await api.post('/api/offers/outgoing/cancel-all', { headers: hdrs, data: {} });

  // Pick a real-seller (not us, not a system listing), non-auction, ACTIVE
  // listing with no maxDiscount so a below-asking offer stays PENDING (no
  // auto-accept) and is comfortably above the $0.01 floor.
  const listings = await (await api.get('/api/listings?sort=price_asc')).json();
  const target = listings.find(
    (l) => l.sellerUserId != null && l.sellerUserId !== 1
      && l.listingType !== 'AUCTION' && l.status === 'ACTIVE'
      && l.maxDiscount == null && Number(l.price) >= 0.1
  );
  expect(target, 'a real-seller listing to bargain on should exist').toBeTruthy();
  const asking = Number(target.price);
  const offerAmt = Math.max(0.01, Math.round(asking * 70) / 100); // ~70% of asking, 2dp
  expect(offerAmt).toBeLessThan(asking);

  // Below-asking offer → created PENDING.
  const offerRes = await api.post('/api/offers', {
    headers: hdrs,
    data: { listingId: target.id, amount: offerAmt.toFixed(2), message: 'e2e bargain' },
  });
  expect(offerRes.ok(), `offer should be accepted (got ${offerRes.status()})`).toBeTruthy();
  const offer = await offerRes.json();
  expect(offer.status).toBe('PENDING');
  expect(Math.round(Number(offer.amount) * 100)).toBe(Math.round(offerAmt * 100));

  // It shows up in the buyer's outgoing offers with the right asking price.
  const outgoing = await (await api.get('/api/offers/outgoing')).json();
  const rows = Array.isArray(outgoing) ? outgoing : (outgoing.content || []);
  const mine = rows.find((o) => o.listingId === target.id && o.status === 'PENDING');
  expect(mine, 'the new offer is listed under outgoing').toBeTruthy();
  expect(Math.round(Number(mine.askingPrice) * 100)).toBe(Math.round(asking * 100));

  // A sub-floor (zero / negative) offer is rejected by the server.
  const badRes = await api.post('/api/offers', {
    headers: hdrs,
    data: { listingId: target.id, amount: '0', message: 'floor probe' },
  });
  expect(badRes.ok(), 'a $0 offer must be rejected by the server floor').toBeFalsy();

  // Cleanup — leave no PENDING offers behind.
  const cleanup = await api.post('/api/offers/outgoing/cancel-all', { headers: hdrs, data: {} });
  expect(cleanup.ok()).toBeTruthy();
});
