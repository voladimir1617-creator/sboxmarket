// Cycle 12 — naked-number scan. Boss prompt: "every price uses $X,XXX.XX,
// every count uses 1,234, every percent uses +X.X% with sign". Walk the
// rendered DOM and flag any leaf <span>/<div>/<td> whose text is:
//   - a $-prefixed number ≥1000 without thousands separator
//   - a count of 4+ digits without thousands separator
//   - a percent without explicit sign
const puppeteer = require('puppeteer');

const ROUTES = ['/', '/market', '/db', '/item/1', '/stall/1', '/wallet', '/profile/personal'];

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  const findings = {};

  for (const r of ROUTES) {
    const page = await browser.newPage();
    await page.setViewport({ width: 1440, height: 900 });
    try {
      await page.goto('http://localhost:8082' + r + '?_qa=1', { waitUntil: 'networkidle2', timeout: 20000 });

      const out = await page.evaluate(() => {
        const naked = { prices: [], counts: [], percents: [] };
        const all = document.querySelectorAll('span, div, td, p, h1, h2, h3, h4, li, b, strong');
        for (const el of all) {
          if (el.children.length > 0) continue;
          const t = (el.textContent || '').trim();
          if (!t || t.length > 40) continue;

          // Naked $-price ≥1000 without comma. e.g. "$1234" or "$1234.56"
          const priceM = t.match(/^\$\s?(\d{4,}(?:\.\d+)?)(?!\d)/);
          if (priceM && !/,/.test(t)) {
            naked.prices.push({ text: t, cls: (el.className || '').toString().slice(0, 50) });
          }

          // Plain count ≥1000 (no $, no %), bare digits in tag
          if (/^\d{4,}$/.test(t)) {
            naked.counts.push({ text: t, cls: (el.className || '').toString().slice(0, 50) });
          }

          // Percent without sign, e.g. "5%" or "5.2%" without leading +/−
          const pctM = t.match(/^(\d+(?:\.\d+)?)%$/);
          if (pctM) naked.percents.push({ text: t, cls: (el.className || '').toString().slice(0, 50) });
        }
        return {
          prices: naked.prices.slice(0, 12),
          pricesCount: naked.prices.length,
          counts: naked.counts.slice(0, 12),
          countsCount: naked.counts.length,
          percents: naked.percents.slice(0, 12),
          percentsCount: naked.percents.length
        };
      });

      findings[r] = out;
    } catch (e) {
      findings[r] = { error: e.message };
    }
    await page.close();
    await new Promise(r => setTimeout(r, 250));
  }

  await browser.close();
  console.log(JSON.stringify(findings, null, 2));
})().catch(e => { console.error(e); process.exit(1); });
