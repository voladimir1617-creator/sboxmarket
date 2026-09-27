// @ts-check
const { test, expect } = require('@playwright/test');

/**
 * THE BUYER'S JOURNEY, END TO END, SIGNED OUT.
 *
 * Browse -> find -> open -> understand what you are buying -> be told,
 * before you pay, what actually arrives and when. Every step here is a step a
 * real stranger takes before he has an account, which is why the whole file
 * runs in the `anon` project and needs no dev-login opt-in: if this journey
 * does not work signed out, nobody ever becomes a signed-in buyer.
 *
 * Each test asserts something that can be WRONG, not merely present:
 *   * search is checked against the rows it returned, not against its own
 *     echo of the query;
 *   * the price sort is checked by reading the prices and comparing them;
 *   * "no results" and "could not load" are checked to be DIFFERENT screens;
 *   * the delivery line is checked to quote the server's deadline when the
 *     server gives one and to quote NO deadline when it does not.
 *
 * The last pair is the point of the file. A buyer is told his wallet is
 * charged instantly. On a listing owned by another user, what he receives is
 * a wait for a person to send a Steam trade offer by hand. Until this spec
 * existed, the only surface that said so was the multi-item cart confirm —
 * the item page said nothing and the single-item Buy Now dialog said "an
 * escrow trade opens with the seller", which names a mechanism and answers
 * neither question.
 */

const POLICY = '**/api/listings/delivery-policy';

/**
 * HOW TO PROVE THESE TESTS HAVE TEETH, ON A TREE SIX AGENTS SHARE.
 *
 * The obvious way to check a test detects a defect is to re-introduce the
 * defect and re-run. On this machine that does not work by editing files: the
 * Spring app serves its static JS out of build/resources/main/static, which
 * any agent's Gradle build restores from src by running processResources.
 * Measured 2026-09-23 — a mutation written into that directory at T+0 was
 * gone by T+23s, and the suite reported GREEN with nothing in the run saying
 * the mutation had been erased. A sweep run that way cannot tell a surviving
 * mutation from a mutation that was never there, which is this codebase's own
 * recurring defect pointed at its test harness.
 *
 * So the mutation is applied to the BYTES IN FLIGHT instead. Nothing on disk
 * changes, no other agent is affected, and a concurrent rebuild is harmless
 * because the replacement runs on whatever the server just sent:
 *
 *   SBOX_MUTATE='{"file":"modals.js","find":"...","replace":"..."}' \
 *     npx playwright test --project=anon tests/buy-journey.anon.spec.js
 *
 * Unset (the normal case) this is completely inert. When the anchor text is
 * missing the page is replaced with a module that throws by name, so a sweep
 * entry whose anchor has drifted reads as a LOUD failure to mutate rather
 * than as a mutation the tests failed to catch.
 */
const MUTATION = process.env.SBOX_MUTATE ? JSON.parse(process.env.SBOX_MUTATE) : null;

test.beforeEach(async ({ page }) => {
  if (!MUTATION) return;
  await page.route(`**/js/${MUTATION.file}*`, async (route) => {
    const res = await route.fetch();
    const body = await res.text();
    if (!body.includes(MUTATION.find)) {
      return route.fulfill({
        status: 200,
        contentType: 'application/javascript',
        body: `throw new Error("SBOX_MUTATE: anchor not found in ${MUTATION.file}");`,
      });
    }
    return route.fulfill({
      status: 200,
      contentType: 'application/javascript',
      body: body.replace(MUTATION.find, MUTATION.replace),
    });
  });
});

/** Wait for the market grid to reach a terminal state (rows, empty, or fault). */
async function gridSettled(page) {
  await expect(
    page
      .locator('.grid-card').first()
      .or(page.locator('[data-testid="market-empty"]'))
      .or(page.locator('[data-testid="market-load-error"]'))
  ).toBeVisible({ timeout: 20_000 });
}

/** Dollar amounts visible on the grid, in DOM order, as numbers. */
async function gridPrices(page) {
  const raw = await page.locator('.grid-card .grid-price').allInnerTexts();
  return raw
    .map((t) => {
      const m = t.match(/([\d,]+\.\d{2})/);
      return m ? parseFloat(m[1].replace(/,/g, '')) : null;
    })
    .filter((n) => n !== null);
}

test.describe('browse and find', () => {
  test('the shop window opens with real rows and an honest count', async ({ page }) => {
    await page.goto('/market');
    await gridSettled(page);

    const cards = page.locator('.grid-card');
    const n = await cards.count();
    expect(n, 'the seeded catalogue should put rows in the shop window').toBeGreaterThan(0);

    // The count above the grid must match the grid. A "41 listings found"
    // over 3 cards is the same class of false statement as "Marketplace is
    // empty" over a 500 — a confident number that nothing produced.
    const meta = await page.locator('.results-meta').innerText();
    const claimed = parseInt((meta.match(/(\d+)\s+listings? found/) || [])[1], 10);
    expect(Number.isFinite(claimed), `results-meta should state a count, got "${meta}"`).toBeTruthy();
    expect(claimed).toBe(n);
  });

  test('search narrows the grid to rows that actually match', async ({ page }) => {
    await page.goto('/market');
    await gridSettled(page);

    // Take a distinctive word from a row that is really there, so the query
    // is guaranteed to have at least one honest answer.
    const firstName = (await page.locator('.grid-card .grid-name').first().innerText()).trim();
    const token = firstName
      .replace(/^★\s*/, '')
      .split(/\s+/)
      .filter((w) => w.length >= 5)[0];
    expect(token, `needed a >=5 letter word from "${firstName}" to search for`).toBeTruthy();

    await page.locator('input.search-input').fill(token);
    // The query is echoed in the results meta once it has been applied.
    await expect(page.locator('.results-meta')).toContainText(new RegExp(`matching\\s+"?${token}`, 'i'), { timeout: 15_000 });
    await gridSettled(page);

    // THE ASSERTION THAT BITES: every row on screen contains the token.
    // Asserting only that the count changed would pass on a search that
    // returns an arbitrary subset, and asserting on the echoed query would
    // pass on a search that does nothing at all.
    const names = await page.locator('.grid-card .grid-name').allInnerTexts();
    expect(names.length, 'a token taken from a real row must return that row').toBeGreaterThan(0);
    for (const nm of names) {
      expect(nm.toLowerCase(), `"${nm}" came back for the search "${token}"`).toContain(token.toLowerCase());
    }
  });

  test('a search with no matches reads as empty, not as a fault', async ({ page }) => {
    await page.goto('/market');
    await gridSettled(page);

    await page.locator('input.search-input').fill('zzzqqqxnotarealskinname');
    await expect(page.locator('[data-testid="market-empty"]')).toBeVisible({ timeout: 20_000 });
    // A market with three items must not look like a market that failed to
    // load — and a market that found nothing must not look broken either.
    await expect(page.locator('[data-testid="market-load-error"]')).toHaveCount(0);
    // A dead end is not an empty state: there has to be a way back. Scoped to
    // the empty state itself — the filter sidebar has its own Clear Filters,
    // and matching that one would pass even if the empty state offered no
    // escape at all.
    await expect(page.locator('.empty-state-actions').getByRole('button', { name: /Clear Filters/i }))
      .toBeVisible();
  });

  test('sorting by price actually orders the grid by price', async ({ page }) => {
    await page.goto('/market');
    await gridSettled(page);

    // The sort chip's label flips the instant the option is clicked, but the
    // rows it describes arrive with the next /api/listings response. Reading
    // prices off the label alone measured the OLD order and called it the new
    // one — so the check polls the rows themselves and only fails once the
    // grid has had a fair chance to answer.
    const pick = async (label) => {
      await page.locator('.sort-picker-chip').click();
      await page.getByRole('option', { name: label }).click();
      await expect(page.locator('.sort-picker-label')).toHaveText(label);
      await gridSettled(page);
    };
    const sortedBy = (dir) => async () => {
      const p = await gridPrices(page);
      if (p.length < 2) return 'too few prices on screen';
      for (let i = 1; i < p.length; i++) {
        const bad = dir === 'asc' ? p[i] < p[i - 1] : p[i] > p[i - 1];
        if (bad) return `row ${i} ($${p[i]}) after row ${i - 1} ($${p[i - 1]})`;
      }
      return 'ok';
    };

    await pick(/Price: Low to High/i);
    await expect.poll(sortedBy('asc'), {
      message: 'the grid never settled into ascending price order',
      timeout: 15_000,
    }).toBe('ok');
    const asc = await gridPrices(page);

    await pick(/Price: High to Low/i);
    await expect.poll(sortedBy('desc'), {
      message: 'the grid never settled into descending price order',
      timeout: 15_000,
    }).toBe('ok');

    // Both orders passing on an unsorted grid is possible only if every price
    // is identical; reject that so the test cannot pass vacuously.
    expect(new Set(asc).size, 'all prices identical — the sort assertions prove nothing').toBeGreaterThan(1);
  });
});

test.describe('browse and find — the case the live catalogue cannot show', () => {
  test('an item whose dearest listing sorts first still lands at its own floor', async ({ page }) => {
    // WHY A FIXTURE. The grid shows one card per ITEM, priced at that item's
    // cheapest listing, but the backend sorts per LISTING. So an item whose
    // dearest listing sorts near the top, and whose floor is near the bottom,
    // gets its row frozen at the dear listing's slot and renders ABOVE cards
    // that are genuinely more expensive. The client re-sort at
    // `sort === 'price_desc' || 'price_asc'` exists solely to fix that.
    //
    // A mutation deleting that re-sort survived the whole suite on 2026-09-23
    // — not because the assertion was weak, but because the live catalogue
    // has no item with two listings at materially different prices, so the
    // defect has nothing to appear in. A test that can only pass is not a
    // test. This builds the missing shape out of two real rows.
    await page.route((u) => { try { return new URL(u).pathname === '/api/listings'; } catch { return false; } },
      async (route) => {
        const real = await route.fetch();
        const rows = await real.json().catch(() => null);
        if (!Array.isArray(rows) || rows.length < 2) {
          return route.fulfill({ status: 599, contentType: 'application/json', body: '{"error":"fixture: need 2 rows"}' });
        }
        const a = rows.find((r) => r?.item?.id != null);
        const b = rows.find((r) => r?.item?.id != null && r.item.id !== a.item.id);
        const c = rows.find((r) => r?.item?.id != null && r.item.id !== a.item.id && r.item.id !== b?.item?.id);
        if (!a || !b || !c) {
          return route.fulfill({ status: 599, contentType: 'application/json', body: '{"error":"fixture: need 3 distinct items"}' });
        }
        const mk = (src, id, price, extra) => ({
          ...src, id, price, currentBid: null, listingType: 'BUY_NOW', status: 'ACTIVE', ...(extra || {}),
        });
        // Descending per-LISTING RAW price, exactly as the backend returns it
        // for sort=price_desc. Two shapes the naive order gets wrong live in
        // here: item A's dear listing sorts above its own floor, and item C
        // is an auction whose raw `price` is the opening bid ($0.05) while
        // the card prints — and a buyer compares — the current bid ($2.00).
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify([
            mk(a, 900001, 1.00),
            mk(b, 900002, 0.50),
            mk(a, 900003, 0.10),
            // `expiresAt` is load-bearing: GridCard treats a listing as an
            // auction only when it has one, and prices it at `price` without
            // it — while app.js's effPrice keys off currentBid regardless.
            // Omit it and the card prints $0.05 while the column sorts it at
            // $2.00, which is a different bug wearing this test's clothes.
            mk(c, 900004, 0.05, { listingType: 'AUCTION', currentBid: 2.00, expiresAt: Date.now() + 86400000 }),
          ]),
        });
      });

    await page.goto('/market');
    await gridSettled(page);
    await page.locator('.sort-picker-chip').click();
    await page.getByRole('option', { name: /Price: High to Low/i }).click();
    await expect(page.locator('.sort-picker-label')).toHaveText(/Price: High to Low/i);
    await gridSettled(page);

    // Three cards, priced at what each card actually PRINTS: the auction at
    // its $2.00 current bid, item B at $0.50, item A at its $0.10 floor.
    // Ordering by the API's per-listing position instead gives 0.50, 0.10,
    // 2.00 — a "highest price first" column with its dearest row at the
    // bottom, because the backend sorted that auction on its $0.05 opening
    // bid and nothing on screen says $0.05.
    await expect.poll(async () => (await gridPrices(page)).join(','), {
      message: 'the grid never reached the effective-price order',
      timeout: 15_000,
    }).toBe('2,0.5,0.1');
  });
});

test.describe('the item page answers the buying questions', () => {
  /** Open the first grid row and return its /item/:id URL. */
  async function openFirstItem(page) {
    await page.goto('/market');
    await gridSettled(page);
    await page.locator('.grid-card').first().click();
    await expect(page).toHaveURL(/\/item\/\d+/, { timeout: 15_000 });
    await expect(page.locator('.modal-name')).toBeVisible({ timeout: 15_000 });
  }

  test('name, price and a buy path are all on the page', async ({ page }) => {
    await openFirstItem(page);

    await expect(page.locator('.modal-name')).not.toBeEmpty();
    // "Listing price" carries a real amount, not the $0.00 a delisted row
    // used to render.
    const priceBox = page.locator('.modal-stat-box', { hasText: 'Listing price' });
    await expect(priceBox).toContainText(/\$\d+\.\d{2}/);

    // Signed out, the buy control is a sign-in CTA carrying the price — not a
    // dead button and not a silent no-op.
    const buy = page.locator('.item-rail-actions-buy');
    await expect(buy).toBeVisible();
    await expect(buy).toHaveText(/Sign in to buy · \$\d+\.\d{2}/);
  });

  test('the page says how the item is delivered, and names the deadline the server gave', async ({ page }) => {
    // Pin the policy so the assertion is about the RENDERING, not about which
    // number a particular deployment has configured. The server side of this
    // — that the value served is the one the auto-cancel sweep enforces — is
    // pinned separately by DeliveryPolicyIsServedNotGuessedSpec.
    await page.route(POLICY, (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '{"sellerResponseDays":3}' })
    );

    await openFirstItem(page);

    const line = page.locator('[data-testid="delivery-expectation-seller"]')
      .or(page.locator('[data-testid="delivery-expectation-platform"]'));
    await expect(line).toBeVisible({ timeout: 15_000 });

    const kind = await page.locator('[data-testid="delivery-expectation-seller"]').count();
    if (kind > 0) {
      const seller = page.locator('[data-testid="delivery-expectation-seller"]');
      // The buyer is told the wait exists BEFORE he decides, not after he pays.
      await expect(seller).toContainText(/Delivery is not instant/i);
      await expect(seller).toContainText(/Steam trade offer by hand/i);
      // ...and told the bound on it, taken from the server.
      await expect(seller).toHaveAttribute('data-delivery-deadline', '3');
      await expect(seller).toContainText(/within\s+3\s+days/i);
      await expect(seller).toContainText(/refunded in full/i);
    } else {
      await expect(page.locator('[data-testid="delivery-expectation-platform"]'))
        .toContainText(/Delivered in-platform/i);
    }
  });

  test('when the deadline cannot be fetched the page states the mechanism and invents no number', async ({ page }) => {
    // The other direction, and the one this codebase keeps paying for: a
    // failed lookup must not be rendered as a confident answer. A deadline
    // guessed at the moment money moves is a promise the server never made.
    await page.route(POLICY, (route) =>
      route.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"boom"}' })
    );

    await openFirstItem(page);

    const seller = page.locator('[data-testid="delivery-expectation-seller"]');
    const platform = page.locator('[data-testid="delivery-expectation-platform"]');
    await expect(seller.or(platform)).toBeVisible({ timeout: 15_000 });

    if ((await seller.count()) > 0) {
      // Still says what always holds...
      await expect(seller).toContainText(/Delivery is not instant/i);
      await expect(seller).toContainText(/cancels itself and you are refunded in full/i);
      // ...and marks the deadline as unknown rather than printing one.
      await expect(seller).toHaveAttribute('data-delivery-deadline', 'unknown');
      const text = await seller.innerText();
      expect(text, `a deadline was printed from a failed policy lookup: "${text}"`)
        .not.toMatch(/within\s+\d+\s+day/i);
    }
  });

  test('a listing whose owner we cannot read is never described as "nothing to wait on"', async ({ page }) => {
    // THE THIRD STATE, and the one worth a test. `sellerUserId: null` is the
    // platform's own inventory — a real answer, and "nothing to wait on" is
    // true of it. A row where the field is ABSENT is not that answer, and
    // JavaScript makes the two trivially easy to confuse: `undefined != null`
    // is false, so one loose comparison turns "we do not know who owns this"
    // into "SkinBox owns this", printed at the top of the buy rail.
    //
    // Driven by taking the REAL response and deleting the field, which is
    // also a realistic shape change — a serializer view that drops it, an
    // older cached payload — rather than a fixture invented for the test.
    await page.route('**/api/listings/item/**', async (route) => {
      const real = await route.fetch();
      let rows;
      try {
        rows = await real.json();
      } catch {
        return route.fulfill({ status: 599, contentType: 'application/json', body: '{"error":"fixture: unreadable"}' });
      }
      if (!Array.isArray(rows) || rows.length === 0) {
        // Fail the FIXTURE loudly rather than silently testing nothing.
        return route.fulfill({ status: 599, contentType: 'application/json', body: '{"error":"fixture: no listings for this item"}' });
      }
      const stripped = rows.map((r) => {
        const { sellerUserId, ...rest } = r;
        return rest;
      });
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(stripped) });
    });

    await page.goto('/market');
    await gridSettled(page);
    await page.locator('.grid-card').first().click();
    await expect(page).toHaveURL(/\/item\/\d+/, { timeout: 15_000 });
    await expect(page.locator('.modal-name')).toBeVisible({ timeout: 15_000 });

    const unknown = page.locator('[data-testid="delivery-expectation-unknown"]');
    await expect(unknown).toBeVisible({ timeout: 15_000 });
    await expect(unknown).toContainText(/could not confirm how this one is delivered/i);
    // And the confident claim must be absent — this is the half that bites.
    await expect(page.locator('[data-testid="delivery-expectation-platform"]')).toHaveCount(0);
  });
});
