const puppeteer = require('puppeteer');
(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1920, height: 1080 });
  await page.goto('http://localhost:8082/', { waitUntil: 'networkidle2', timeout: 25000 });
  await new Promise(r => setTimeout(r, 3500));
  const result = await page.evaluate(() => {
    const stack = document.querySelector('.csfloat-home-hero-stack');
    const allCards = document.querySelectorAll('[class*="hero-stack"]');
    if (!stack) return { found: false, allCardsCount: allCards.length, allCardsClasses: [...allCards].map(e => e.className).slice(0, 10) };
    stack.scrollIntoView({ block: 'center' });
    const cards = ['.pos-0', '.pos-1', '.pos-2'].map(sel => {
      const el = stack.querySelector(sel);
      if (!el) return null;
      const cs = getComputedStyle(el);
      const r = el.getBoundingClientRect();
      return {
        sel,
        opacity: cs.opacity,
        filter: cs.filter,
        transform: cs.transform.slice(0, 60),
        rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }
      };
    });
    return { found: true, cards };
  });
  console.log(JSON.stringify(result, null, 2));
  const stack = await page.$('.csfloat-home-hero-stack');
  if (stack) await stack.screenshot({ path: '_qa_boss/cycle_4/P1.2_stack.png' });
  await browser.close();
})();
