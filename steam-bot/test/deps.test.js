// Checks the dependency swaps in package.json `overrides` (see README "npm advisories").
// Needs `npm install`; skipped when node_modules is absent so `npm test` still runs bare.
const test = require('node:test');
const assert = require('node:assert');
const http = require('node:http');

let installed = true;
try { require.resolve('steamcommunity'); } catch { installed = false; }
const opts = { skip: installed ? false : 'run npm install first' };

test('cheerio selectors and .css() run on the nth-check / lodash.pick shims', opts, () => {
  const $ = require('cheerio').load('<ul><li>a</li><li>b</li><li>c</li></ul><p style="color:red;width:1px">x</p>');
  assert.strictEqual($('li:nth-child(2n+1)').map((i, e) => $(e).text()).get().join(''), 'ac');
  assert.deepStrictEqual($('p').css(['color']), { color: 'red' });
});

test('image-size shim keeps the function call shape', opts, () => {
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAYAAACddGYaAAAAEklEQVR4nGP4z8DwHwYZkDkAo14K9mB2dqwAAAAASUVORK5CYII=', 'base64');
  const dim = require('image-size')(png);
  assert.strictEqual(dim.width, 3);
  assert.strictEqual(dim.height, 2);
});

test('steamcommunity sends its cookie jar through @cypress/request', opts, async () => {
  require('steam-tradeoffer-manager');
  require('steam-user');
  const SteamCommunity = require('steamcommunity');
  const community = new SteamCommunity();
  community.setCookies(['sessionid=abc123']);
  assert.strictEqual(community.getSessionID(), 'abc123');

  const srv = http.createServer((req, res) => {
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify({ cookie: req.headers.cookie || '' }));
  });
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${srv.address().port}/x`;
  community._jar.setCookie('sessionid=abc123', url);
  try {
    const body = await new Promise((resolve, reject) => {
      community.httpRequestGet({ uri: url, json: true }, (err, resp, b) => (err ? reject(err) : resolve(b)), 'test');
    });
    assert.match(body.cookie, /sessionid=abc123/);
  } finally {
    srv.close();
  }
});
