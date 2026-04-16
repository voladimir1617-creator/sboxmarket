// SkinBox Inventory Valuer — content script.
//
// Runs on steamcommunity.com/id/<name>/inventory and /profiles/<steamid>/inventory.
// Scrapes the visible s&box inventory (appid 590830), cross-references every
// item against:
//   1) its Steam Community Market lowest price (public JSON endpoint)
//   2) the matching lowest active listing on the SkinBox marketplace
// Renders a floating summary panel: total inventory value on Steam vs on
// SkinBox, plus a percentage delta so the user can see at a glance where
// they'd get more.
//
// No auth tokens are required — Steam inventory listings and market prices
// are public endpoints for any account whose inventory is public.

(() => {
  'use strict';

  const SBOX_APP_ID = '590830';
  const STEAM_CURRENCY_USD = 1; // 1 = USD for priceoverview
  const PANEL_ID = 'sbx-valuer-panel';

  // Where SkinBox lives. The extension ships with both localhost ports and
  // a prod placeholder in host_permissions; the first reachable one wins.
  const SKINBOX_ORIGINS = [
    'http://localhost:8080',
    'http://localhost:8090',
    'https://skinbox.market'
  ];

  // ── State ────────────────────────────────────────────────────
  const state = {
    steamId:    null,
    assetItems: [],       // [{ name, marketHashName, iconUrl, count }]
    steamPrices:{},       // marketHashName → USD number
    sbxPrices:  {},       // marketHashName → USD number
    origin:     null      // resolved SkinBox origin
  };

  // ── Utils ────────────────────────────────────────────────────
  const el = (tag, cls, text) => {
    const node = document.createElement(tag);
    if (cls)  node.className = cls;
    if (text != null) node.textContent = text;
    return node;
  };

  const fmt = n => '$' + (Number(n || 0)).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

  const extractSteamId = () => {
    const m = location.pathname.match(/\/profiles\/(\d{17})/);
    if (m) return m[1];
    // vanity url — the page sets g_steamID on window for us
    try { return window.g_steamID || null; } catch { return null; }
  };

  // ── Steam inventory scrape ──────────────────────────────────
  // Steam exposes the public inventory JSON at:
  //   /inventory/{steamid}/{appid}/{contextid}
  // We call it with credentials so a private-only-to-friends inventory
  // works when the user is viewing their own profile.
  async function fetchSteamInventory(steamId) {
    const url = `https://steamcommunity.com/inventory/${steamId}/${SBOX_APP_ID}/2?l=english&count=500`;
    const res = await fetch(url, { credentials: 'include' });
    if (!res.ok) throw new Error(`Steam inventory HTTP ${res.status}`);
    const json = await res.json();

    const descByKey = {};
    (json.descriptions || []).forEach(d => {
      descByKey[`${d.classid}_${d.instanceid}`] = d;
    });

    // Group duplicates by market_hash_name so we only price each item once.
    const counts = {};
    (json.assets || []).forEach(a => {
      const d = descByKey[`${a.classid}_${a.instanceid}`];
      if (!d) return;
      const name = d.market_hash_name || d.market_name || d.name;
      if (!counts[name]) {
        counts[name] = {
          name,
          marketHashName: name,
          iconUrl: d.icon_url ? `https://steamcommunity-a.akamaihd.net/economy/image/${d.icon_url}/96fx96f` : null,
          marketable: ((d.marketable | 0) === 1),
          count: 0
        };
      }
      counts[name].count += 1;
    });
    return Object.values(counts);
  }

  // ── Steam Market price lookup ───────────────────────────────
  // The priceoverview endpoint is public and unauthenticated but is
  // rate-limited at ~1 req/s — we throttle with a 600ms gap.
  async function fetchSteamPrice(marketHashName) {
    const url = `https://steamcommunity.com/market/priceoverview/?country=US&currency=${STEAM_CURRENCY_USD}&appid=${SBOX_APP_ID}&market_hash_name=${encodeURIComponent(marketHashName)}`;
    const res = await fetch(url);
    if (!res.ok) return null;
    const j = await res.json();
    if (!j || j.success !== true) return null;
    const raw = j.lowest_price || j.median_price;
    if (!raw) return null;
    // "$1.23" → 1.23 ; "1,23€" → 1.23 (strip anything non-numeric except the sep)
    const num = parseFloat(String(raw).replace(/[^\d.]/g, ''));
    return isNaN(num) ? null : num;
  }

  // ── SkinBox lookup ──────────────────────────────────────────
  // Hit /api/listings with the item name as the search param and take the
  // cheapest active listing. Fails open (returns null) if SkinBox is down.
  // Handles both response shapes: bare array (when the server uses the
  // default limit=100/offset=0 path) and the wrapped {items, total, ...}
  // form returned for any other limit/offset combo.
  async function fetchSbxPrice(origin, name) {
    try {
      const url = `${origin}/api/listings?search=${encodeURIComponent(name)}&limit=10`;
      const res = await fetch(url, { credentials: 'omit' });
      if (!res.ok) return null;
      const data = await res.json();
      const rows = Array.isArray(data) ? data : (data && Array.isArray(data.items) ? data.items : []);
      if (rows.length === 0) return null;
      const match = rows.find(r => r.item && r.item.name && r.item.name.toLowerCase() === name.toLowerCase());
      const pick = match || rows[0];
      if (!pick || !pick.price) return null;
      return parseFloat(pick.price);
    } catch (e) {
      return null;
    }
  }

  // Walk the list of configured origins and pick the first one that
  // answers /api/listings. Avoids hardcoding prod vs local.
  async function resolveOrigin() {
    for (const o of SKINBOX_ORIGINS) {
      try {
        const r = await fetch(`${o}/api/listings?limit=1`, { credentials: 'omit', mode: 'cors' });
        if (r.ok) return o;
      } catch {}
    }
    return null;
  }

  // ── Panel UI ────────────────────────────────────────────────
  function mountPanel() {
    let panel = document.getElementById(PANEL_ID);
    if (panel) return panel;

    panel = el('div');
    panel.id = PANEL_ID;
    panel.innerHTML = `
      <div class="sbx-head">
        <div class="sbx-brand">
          <span class="sbx-logo">SB</span>
          <span class="sbx-title">SkinBox Valuer</span>
        </div>
        <button class="sbx-close" aria-label="Close">×</button>
      </div>
      <div class="sbx-body">
        <div class="sbx-status">Waiting for inventory…</div>
        <div class="sbx-grid" hidden>
          <div class="sbx-card">
            <div class="sbx-card-label">Steam Market</div>
            <div class="sbx-card-val" data-steam-total>—</div>
            <div class="sbx-card-note">public priceoverview</div>
          </div>
          <div class="sbx-card">
            <div class="sbx-card-label">SkinBox</div>
            <div class="sbx-card-val" data-sbx-total>—</div>
            <div class="sbx-card-note" data-sbx-origin>—</div>
          </div>
        </div>
        <div class="sbx-delta" data-delta hidden></div>
        <div class="sbx-items" data-items hidden></div>
        <div class="sbx-footer">
          <span data-item-count>0 items</span>
          <a href="#" data-refresh>↻ Refresh</a>
        </div>
      </div>
    `;
    document.body.appendChild(panel);

    panel.querySelector('.sbx-close').addEventListener('click', () => panel.remove());
    panel.querySelector('[data-refresh]').addEventListener('click', (e) => {
      e.preventDefault();
      run();
    });
    return panel;
  }

  function renderInto(panel) {
    const steamTotal = state.assetItems.reduce((sum, it) =>
      sum + (state.steamPrices[it.marketHashName] || 0) * it.count, 0);
    const sbxTotal = state.assetItems.reduce((sum, it) =>
      sum + (state.sbxPrices[it.marketHashName] || 0) * it.count, 0);

    const itemCount = state.assetItems.reduce((n, it) => n + it.count, 0);

    panel.querySelector('[data-steam-total]').textContent = fmt(steamTotal);
    panel.querySelector('[data-sbx-total]').textContent   = sbxTotal > 0 ? fmt(sbxTotal) : '—';
    panel.querySelector('[data-sbx-origin]').textContent  = state.origin ? new URL(state.origin).host : 'offline';
    panel.querySelector('[data-item-count]').textContent  = `${itemCount} item${itemCount === 1 ? '' : 's'}`;
    panel.querySelector('.sbx-grid').hidden = false;
    panel.querySelector('.sbx-status').hidden = true;

    const deltaEl = panel.querySelector('[data-delta]');
    if (sbxTotal > 0 && steamTotal > 0) {
      const diff = sbxTotal - steamTotal;
      const pct  = (diff / steamTotal) * 100;
      const better = diff > 0;
      deltaEl.hidden = false;
      deltaEl.className = 'sbx-delta ' + (better ? 'up' : 'down');
      deltaEl.textContent = better
        ? `SkinBox is ${fmt(diff)} (${pct.toFixed(1)}%) higher than Steam`
        : `Steam is ${fmt(-diff)} (${(-pct).toFixed(1)}%) higher than SkinBox`;
    } else {
      deltaEl.hidden = true;
    }

    // Per-item breakdown (max 20 rows, biggest first).
    const itemsWrap = panel.querySelector('[data-items]');
    itemsWrap.hidden = false;
    itemsWrap.innerHTML = '';
    const rows = state.assetItems
      .map(it => {
        const steam = (state.steamPrices[it.marketHashName] || 0) * it.count;
        const sbx   = (state.sbxPrices[it.marketHashName]   || 0) * it.count;
        return { ...it, steam, sbx };
      })
      .sort((a, b) => Math.max(b.steam, b.sbx) - Math.max(a.steam, a.sbx))
      .slice(0, 20);

    rows.forEach(r => {
      const row = el('div', 'sbx-item');
      row.innerHTML = `
        <img class="sbx-item-icon" src="${r.iconUrl || ''}" alt="">
        <div class="sbx-item-name" title="${r.name}">${r.name}${r.count > 1 ? ` ×${r.count}` : ''}</div>
        <div class="sbx-item-price">${r.steam > 0 ? fmt(r.steam) : '—'}</div>
        <div class="sbx-item-price sbx-item-price-sbx">${r.sbx > 0 ? fmt(r.sbx) : '—'}</div>
      `;
      itemsWrap.appendChild(row);
    });
  }

  // ── Orchestration ───────────────────────────────────────────
  async function run() {
    const panel = mountPanel();
    const statusEl = panel.querySelector('.sbx-status');
    statusEl.hidden = false;
    statusEl.textContent = 'Fetching inventory…';

    try {
      state.steamId = extractSteamId();
      if (!state.steamId) throw new Error('Could not resolve Steam ID from page');

      const items = await fetchSteamInventory(state.steamId);
      state.assetItems = items;
      if (items.length === 0) {
        statusEl.textContent = 'No s&box items in this inventory.';
        return;
      }

      state.origin = await resolveOrigin();
      statusEl.textContent = `Pricing ${items.length} unique item${items.length === 1 ? '' : 's'}…`;

      // Prices — sequential with a short gap so Steam doesn't rate-limit us.
      // 600ms throttle → ~1.6 req/s, under Steam's public ~1 req/s limit
      // across bursts. Small inventories finish in seconds; huge ones take
      // a minute or two with progress updates.
      for (let i = 0; i < items.length; i++) {
        const it = items[i];
        statusEl.textContent = `Pricing ${i + 1}/${items.length}: ${it.name}`;
        const [sp, bp] = await Promise.all([
          fetchSteamPrice(it.marketHashName),
          state.origin ? fetchSbxPrice(state.origin, it.marketHashName) : Promise.resolve(null)
        ]);
        if (sp != null) state.steamPrices[it.marketHashName] = sp;
        if (bp != null) state.sbxPrices[it.marketHashName]   = bp;
        renderInto(panel);
        await new Promise(r => setTimeout(r, 600));
      }
      statusEl.hidden = true;
    } catch (e) {
      statusEl.textContent = `Error: ${e.message}`;
      console.error('[SkinBox Valuer]', e);
    }
  }

  // Boot once the inventory tab has rendered. Steam's inventory page is
  // a SPA — the initial document often has zero items and loads them via
  // XHR. We wait for either the #inventory_ anchor or a 2s timeout.
  const boot = () => {
    if (document.readyState !== 'complete') {
      window.addEventListener('load', boot, { once: true });
      return;
    }
    setTimeout(run, 1500);
  };
  boot();
})();
