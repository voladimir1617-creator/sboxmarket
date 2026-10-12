// @ts-check
const { test, expect } = require('@playwright/test');

// Every auction's bid book must be one a real auction could have produced.
// A live auction refuses a first bid under its starting price, but the demo
// seed opened lots ABOVE their bids: the WW1 Helmet read "start $69.12" over
// bids of $43-$54. Now each seeded lot opens at its first bid.
test('no auction carries a bid under its own starting price', async ({ page }) => {
  const listings = await (await page.request.get('/api/listings')).json();
  const auctions = listings.filter((l) => l.listingType === 'AUCTION' && (l.bidCount || 0) > 0);
  expect(auctions.length, 'the seeded market should have auctions with bids').toBeGreaterThan(0);
  for (const a of auctions) {
    const bids = await (await page.request.get(`/api/bids/listing/${a.id}`)).json();
    const lowest = Math.min(...bids.map((b) => Number(b.amount)));
    expect(Number(a.price), `listing ${a.id} starts above its lowest bid`).toBeLessThanOrEqual(lowest);
  }
});
