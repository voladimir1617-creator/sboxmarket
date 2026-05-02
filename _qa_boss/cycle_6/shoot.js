// Cycle 6 — desktop (1920x1080) + mobile (390x852) viewport screenshots
// for the full QA route surface, written via puppeteer (the powershell
// chrome.exe script failed: chrome not at the hardcoded path on this box).
const puppeteer = require('puppeteer');
const path = require('path');
const fs = require('fs');

const OUT = 'C:\\Users\\WW\\Desktop\\sboxmarket\\_qa_boss\\cycle_6';

const desktopRoutes = [
  { name: '01-home',         path: '/' },
  { name: '02-market',       path: '/market' },
  { name: '04-database',     path: '/db' },
  { name: '05-help',         path: '/help' },
  { name: '06-faq',          path: '/faq' },
  { name: '10-cart',         path: '/cart' },
  { name: '11-watchlist',    path: '/watchlist' },
  { name: '12-wallet',       path: '/wallet' },
  { name: '13-sell',         path: '/sell' },
  { name: '14-settings',     path: '/settings' },
  { name: '15-profile',      path: '/profile/personal' },
  { name: '22-item-missing', path: '/item/missing' },
  { name: '23-stall-missing',path: '/stall/missing' },
  { name: '24-loadout-miss', path: '/loadout/missing' },
  { name: '25-item-real',    path: '/item/1' },
  { name: '26-stall-real',   path: '/stall/1' },
  { name: '27-loadout-real', path: '/loadout/1' }
];

const mobileRoutes = [
  { name: 'm01-home',        path: '/' },
  { name: 'm02-market',      path: '/market' },
  { name: 'm03-database',    path: '/db' },
  { name: 'm04-item',        path: '/item/1' },
  { name: 'm05-stall',       path: '/stall/1' },
  { name: 'm06-help',        path: '/help' },
  { name: 'm07-faq',         path: '/faq' },
  { name: 'm08-cart',        path: '/cart' },
  { name: 'm09-wallet',      path: '/wallet' },
  { name: 'm10-watchlist',   path: '/watchlist' },
  { name: 'm11-settings',    path: '/settings' },
  { name: 'm12-profile',     path: '/profile/personal' },
  { name: 'm13-sell',        path: '/sell' },
  { name: 'm14-item-miss',   path: '/item/missing' }
];

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });

  // Desktop pass
  const dPage = await browser.newPage();
  await dPage.setViewport({ width: 1920, height: 1080 });
  for (const r of desktopRoutes) {
    try {
      await dPage.goto('http://localhost:8082' + r.path, { waitUntil: 'domcontentloaded', timeout: 20000 });
      await new Promise(res => setTimeout(res, 1500));
      await dPage.screenshot({ path: path.join(OUT, r.name + '.png') });
      console.log('OK', r.name);
    } catch (e) {
      console.log('FAIL', r.name, e.message.slice(0, 80));
    }
  }
  await dPage.close();

  // Mobile pass
  const mPage = await browser.newPage();
  await mPage.setViewport({ width: 390, height: 852, isMobile: true, deviceScaleFactor: 1 });
  await mPage.setUserAgent('Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1');
  for (const r of mobileRoutes) {
    try {
      await mPage.goto('http://localhost:8082' + r.path, { waitUntil: 'domcontentloaded', timeout: 20000 });
      await new Promise(res => setTimeout(res, 1500));
      await mPage.screenshot({ path: path.join(OUT, r.name + '.png') });
      console.log('OK', r.name);
    } catch (e) {
      console.log('FAIL', r.name, e.message.slice(0, 80));
    }
  }
  await mPage.close();
  await browser.close();
})();
