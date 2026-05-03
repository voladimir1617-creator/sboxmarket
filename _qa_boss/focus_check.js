const puppeteer = require('puppeteer');
const URL = process.argv[2] || 'https://skinbox.market/';
(async () => {
  const b = await puppeteer.launch({ headless: 'new', executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', args: ['--no-sandbox'] });
  const p = await b.newPage();
  await p.setViewport({ width: 1920, height: 1080 });
  await p.goto(URL + '?_qa=1', { waitUntil: 'networkidle2', timeout: 30000 });
  await new Promise(r => setTimeout(r, 1500));
  for (let i = 0; i < 8; i++) {
    await p.keyboard.press('Tab');
    await new Promise(r => setTimeout(r, 250));
    const info = await p.evaluate(() => {
      const el = document.activeElement;
      const cs = getComputedStyle(el);
      return { tag: el.tagName, cls: el.className.toString().slice(0,40), text: (el.innerText||'').replace(/\n/g, ' ').slice(0,30), outline: cs.outline, outlineColor: cs.outlineColor, outlineOffset: cs.outlineOffset };
    });
    console.log(`Tab #${i+1}: ${info.tag}.${info.cls} "${info.text}" outline=${info.outline} offset=${info.outlineOffset}`);
  }
  await b.close();
})().catch(e => { console.error(e); process.exit(1); });
