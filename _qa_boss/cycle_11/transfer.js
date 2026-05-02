const puppeteer = require('puppeteer');
const fs = require('fs');

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1920, height: 1080 });
  const resources = [];
  page.on('response', async resp => {
    try {
      const buf = await resp.buffer();
      resources.push({ url: resp.url(), type: resp.headers()['content-type']?.split(';')[0], status: resp.status(), bytes: buf.length });
    } catch (e) {}
  });
  await page.goto('http://localhost:8082/?_qa=1', { waitUntil: 'networkidle2', timeout: 15000 });
  await browser.close();
  resources.sort((a,b) => b.bytes - a.bytes);
  console.log('Top 15 by transfer size:');
  for (const r of resources.slice(0, 15)) {
    const url = r.url.replace('http://localhost:8082', '').slice(0, 80);
    console.log(`  ${(r.bytes/1024).toFixed(0).padStart(6)}KB  ${(r.type||'?').padEnd(20)}  ${url}`);
  }
  console.log();
  const byType = {};
  for (const r of resources) {
    const t = (r.type || 'unknown').split('/')[0];
    byType[t] = (byType[t] || 0) + r.bytes;
  }
  console.log('By type:');
  for (const [t, b] of Object.entries(byType).sort((a,b) => b[1] - a[1])) {
    console.log(`  ${(b/1024).toFixed(0).padStart(6)}KB  ${t}`);
  }
})();
