// CSFloat-style feature modals: Database, Buy Orders, Loadout Lab,
// Trade-Up Calculator, Notifications feed, Auction bid panel.
//
// Every modal follows the same pattern as ./modals.js — narrow prop surface,
// uses InfoModal as the shell, calls into ./api.js for I/O.
import { h, useState, useEffect, useCallback, useMemo, fmt, timeAgo } from './utils.js';
import { ItemImage, RarityBadge, MaterialIcon } from './primitives.js';
import { InfoModal } from './info-modal.js';
import {
  fetchDatabase, fetchBuyOrders, createBuyOrder, deleteBuyOrder,
  fetchPublicLoadouts, fetchMyLoadouts, fetchLoadout, createLoadout,
  setLoadoutSlot, generateLoadout, deleteLoadout, favoriteLoadout,
  fetchListings,
  fetchNotifications, markAllNotificationsRead, markNotificationRead,
  fetchBidHistory, placeBid
} from './api.js';

// ── Database ────────────────────────────────────────────────────
export function DatabaseModal({ onClose, onPickItem }) {
  const [data, setData]       = useState({ items: [], total: 0, indexed: 0 });
  const [loading, setLoading] = useState(true);
  const [search, setSearch]   = useState('');
  const [category, setCat]    = useState('All');
  const [rarity, setRar]      = useState('All');
  const [sort, setSort]       = useState('rarest');
  const [page, setPage]       = useState(0);
  const PAGE_SIZE = 30;

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetchDatabase({
        q: search || null, category, rarity, sort,
        limit: PAGE_SIZE, offset: page * PAGE_SIZE
      });
      setData(res);
    } finally { setLoading(false); }
  }, [search, category, rarity, sort, page]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => { setPage(0); }, [search, category, rarity, sort]);

  const CATS = ['All','Hats','Jackets','Shirts','Pants','Gloves','Boots','Accessories'];
  const RARS = ['All','Limited','Off-Market','Standard'];
  const SORTS = [
    { v: 'rarest',      l: 'Rarest (lowest supply)' },
    { v: 'most_traded', l: 'Most Traded' },
    { v: 'price_desc',  l: 'Price: High → Low' },
    { v: 'price_asc',   l: 'Price: Low → High' },
    { v: 'newest',      l: 'Newest Indexed' },
  ];

  const totalPages = Math.max(1, Math.ceil(data.total / PAGE_SIZE));

  return h(InfoModal, { title: `Database · ${Number(data.indexed || 0).toLocaleString()} indexed`, onClose },
    h('div', { className: 'db-controls' },
      h('input', {
        className: 'price-input', style: { flex: 1, minWidth: 180 },
        placeholder: 'Search items…', value: search,
        onChange: e => setSearch(e.target.value)
      }),
      h('select', { className: 'sort-select', value: category, onChange: e => setCat(e.target.value) },
        CATS.map(c => h('option', { key: c, value: c }, c))),
      h('select', { className: 'sort-select', value: rarity, onChange: e => setRar(e.target.value) },
        RARS.map(r => h('option', { key: r, value: r }, r))),
      h('select', { className: 'sort-select', value: sort, onChange: e => setSort(e.target.value) },
        SORTS.map(s => h('option', { key: s.v, value: s.v }, s.l))),
    ),
    h('div', { className: 'db-meta' },
      h('strong', null, Number(data.total).toLocaleString()), ' results · page ',
      h('strong', null, page + 1), ' / ', totalPages
    ),
    loading
      ? h('div', { className: 'spinner' })
      : h('table', { className: 'db-table' },
          h('thead', null, h('tr', null,
            h('th', { style: { width: 50 } }, '#'),
            h('th', null, 'Item'),
            h('th', null, 'Category'),
            h('th', null, 'Rarity'),
            h('th', { className: 'right' }, 'Supply'),
            h('th', { className: 'right' }, 'Sold'),
            h('th', { className: 'right' }, 'Floor')
          )),
          h('tbody', null,
            data.items.map((item, i) => h('tr', {
              key: item.id,
              className: 'db-row',
              onClick: () => onPickItem && onPickItem(item)
            },
              h('td', { className: 'db-rank' }, '#' + (page * PAGE_SIZE + i + 1)),
              h('td', null,
                h('div', { className: 'db-item-cell' },
                  h('div', { className: 'db-thumb' }, h(ItemImage, { item })),
                  h('div', null,
                    h('div', { className: 'db-name' }, item.name),
                    h('div', { className: 'db-sub' }, '#' + item.id)
                  )
                )
              ),
              h('td', { className: 'db-cat' }, item.category),
              h('td', null, h(RarityBadge, { rarity: item.rarity })),
              h('td', { className: 'right db-mono' }, Number(item.supply).toLocaleString()),
              h('td', { className: 'right db-mono' }, Number(item.totalSold).toLocaleString()),
              h('td', { className: 'right db-mono accent' }, fmt(item.lowestPrice))
            ))
          )
        ),
    h('div', { className: 'db-pager' },
      h('button', { className: 'btn btn-ghost', disabled: page === 0, onClick: () => setPage(p => Math.max(0, p - 1)) }, '← Prev'),
      h('span', { style: { fontSize: 12, color: 'var(--text-muted)' } }, `${page + 1} of ${totalPages}`),
      h('button', { className: 'btn btn-ghost', disabled: page + 1 >= totalPages, onClick: () => setPage(p => p + 1) }, 'Next →')
    )
  );
}

// ── Buy Orders — per-specific-item, CSFloat-style ──────────────
//
// CSFloat buy orders are ALWAYS attached to a specific item ("I'll pay up to
// $X for a Wizard Hat"). The buyer does NOT set category/rarity filters.
// The creation flow supports two entry points:
//
//   1) The user clicks "+ Create Buy Order" inside this modal and searches
//      the item catalogue via an autocomplete picker.
//   2) The user clicks "+ Create Buy Order" INSIDE an ItemModal, in which
//      case we open this modal pre-seeded with that item and skip the search.
//
// Both paths hit POST /api/buy-orders with an explicit itemId. The buyer
// then sees every active buy order grouped by item, with live status.
export function BuyOrdersModal({ onClose, me, preselectedItem }) {
  const [orders, setOrders]   = useState(null);
  const [creating, setCreating] = useState(!!preselectedItem);
  const [picked, setPicked]   = useState(preselectedItem || null);
  const [search, setSearch]   = useState('');
  const [pool, setPool]       = useState([]);
  const [maxPrice, setMaxPrice] = useState('');
  const [qty, setQty]         = useState('1');
  const [busy, setBusy]       = useState(false);
  const [err, setErr]         = useState('');

  const load = useCallback(async () => { setOrders(await fetchBuyOrders()); }, []);
  useEffect(() => { if (me) load(); }, [me, load]);

  // Catalogue pool for the autocomplete search
  useEffect(() => {
    fetchListings({}).then(listings => {
      const seen = new Set();
      const items = [];
      listings.forEach(l => {
        if (!l?.item || seen.has(l.item.id)) return;
        seen.add(l.item.id);
        items.push(l.item);
      });
      setPool(items);
    });
  }, []);

  const filteredPool = useMemo(() => {
    if (!search) return pool.slice(0, 30);
    const s = search.toLowerCase();
    return pool.filter(p => p.name.toLowerCase().includes(s)).slice(0, 30);
  }, [pool, search]);

  if (!me) return h(InfoModal, { title: 'Buy Orders', onClose },
    h('div', { className: 'empty-inline' },
      h('div', { className: 'empty-icon' }, '🔒'),
      h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'Sign in to manage your buy orders.')));

  const submit = async () => {
    setErr('');
    if (!picked) { setErr('Pick an item first'); return; }
    const max = parseFloat(maxPrice);
    if (!max || max <= 0) { setErr('Enter a max price above zero'); return; }
    if (picked.lowestPrice && max >= parseFloat(picked.lowestPrice)) {
      setErr(`Your max (${fmt(max)}) is at or above the current floor (${fmt(picked.lowestPrice)}) — just buy now instead.`);
      return;
    }
    setBusy(true);
    try {
      const res = await createBuyOrder({
        itemId:   picked.id,
        category: picked.category,   // snapshot the category for display only
        rarity:   picked.rarity,     // snapshot the rarity for display only
        maxPrice: max,
        quantity: parseInt(qty || '1', 10) || 1
      });
      if (res.code || res.error) { setErr(res.message || res.error); return; }
      setPicked(null); setMaxPrice(''); setQty('1'); setSearch('');
      setCreating(false);
      load();
    } finally { setBusy(false); }
  };

  const cancel = async (id) => {
    if (!confirm('Cancel this buy order?')) return;
    const res = await deleteBuyOrder(id);
    if (res && res.error) { alert(res.error); return; }
    load();
  };

  return h(InfoModal, { title: `Buy Orders · ${(orders?.length) || 0}`, onClose },
    h('div', { style: { display: 'flex', gap: 10, marginBottom: 16 } },
      h('button', { className: 'btn btn-accent', onClick: () => { setCreating(c => !c); setErr(''); } },
        creating ? 'Cancel' : '+ Create Buy Order')
    ),
    creating && h('div', { className: 'buyorder-form' },
      // STEP 1 — item picker (skipped if a preselected item is already set)
      !picked && h('div', null,
        h('div', { className: 'wallet-input-label' }, 'Item'),
        h('input', {
          className: 'price-input', style: { width: '100%' }, autoFocus: true,
          placeholder: 'Search catalogue…',
          value: search,
          onChange: e => setSearch(e.target.value)
        }),
        h('div', { className: 'buyorder-picker' },
          filteredPool.length === 0
            ? h('div', { style: { padding: 12, fontSize: 12, color: 'var(--text-muted)' } }, 'No matches.')
            : filteredPool.map(it => h('div', {
                key: it.id, className: 'buyorder-picker-row',
                onClick: () => { setPicked(it); setMaxPrice((parseFloat(it.lowestPrice || 0) * 0.9).toFixed(2)); }
              },
                h('div', { className: 'db-thumb', style: { width: 36, height: 36 } }, h(ItemImage, { item: it, variant: 'thumb' })),
                h('div', { style: { flex: 1, minWidth: 0 } },
                  h('div', { style: { fontSize: 13, fontWeight: 600, color: 'var(--text-primary)' } }, it.name),
                  h('div', { style: { fontSize: 10, color: 'var(--text-muted)' } }, it.category + ' · ' + it.rarity)
                ),
                h('div', { style: { fontSize: 12, fontWeight: 700, color: 'var(--accent)', fontFamily: 'JetBrains Mono, monospace' } }, fmt(it.lowestPrice))
              ))
        )
      ),

      // STEP 2 — price + quantity with the selected item locked in
      picked && h('div', null,
        h('div', { className: 'buyorder-picked' },
          h('div', { className: 'db-thumb', style: { width: 48, height: 48 } }, h(ItemImage, { item: picked, variant: 'card' })),
          h('div', { style: { flex: 1, minWidth: 0 } },
            h('div', { style: { fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' } }, picked.name),
            h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } }, 'Floor · ', h('strong', { style: { color: 'var(--accent)' } }, fmt(picked.lowestPrice)))
          ),
          !preselectedItem && h('button', {
            className: 'btn btn-ghost',
            style: { padding: '6px 10px', fontSize: 11, border: '1px solid var(--border)' },
            onClick: () => { setPicked(null); setMaxPrice(''); }
          }, 'Change')
        ),
        h('div', { className: 'wallet-input-label' }, 'Maximum Price per Item (USD)'),
        h('input', { className: 'wallet-amount-input', type: 'number', step: '0.01', min: '0.01',
          value: maxPrice, onChange: e => setMaxPrice(e.target.value),
          placeholder: picked.lowestPrice ? (parseFloat(picked.lowestPrice) * 0.9).toFixed(2) : '0.00' }),
        h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginTop: 4 } },
          'When a listing for this item drops to or below your max, it is auto-purchased from your wallet.'),
        h('div', { className: 'wallet-input-label' }, 'Quantity'),
        h('input', { className: 'wallet-amount-input', type: 'number', min: '1', step: '1',
          value: qty, onChange: e => setQty(e.target.value) }),
        err && h('div', { className: 'wallet-error' }, err),
        h('button', { className: 'btn btn-accent wallet-submit', disabled: busy, onClick: submit },
          busy ? 'Creating…' : 'Place Buy Order')
      )
    ),

    orders === null
      ? h('div', { className: 'spinner' })
      : orders.length === 0
        ? h('div', { className: 'empty-inline' },
            h('div', { className: 'empty-icon' }, '🛒'),
            h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } },
              'No active buy orders yet. Click an item and use "+ Create Buy Order" to place one.'))
        : h('div', { className: 'buyorder-list' },
            orders.map(o => h('div', { key: o.id, className: `buyorder-row ${(o.status || '').toLowerCase()}` },
              h('div', { style: { flex: 1, minWidth: 0 } },
                h('div', { className: 'buyorder-title' },
                  o.itemName || ('Any ' + (o.category || 'item'))
                ),
                h('div', { className: 'buyorder-sub' },
                  'Created ', timeAgo(o.createdAt),
                  ' · Qty ', h('strong', null, o.quantity), ' / ', o.originalQuantity
                )
              ),
              h('div', { style: { textAlign: 'right', marginRight: 14 } },
                h('div', { className: 'buyorder-cap' }, '≤ ' + fmt(o.maxPrice)),
                h('div', { className: `buyorder-status ${o.status}` }, o.status)
              ),
              o.status === 'ACTIVE' && h('button', {
                className: 'btn btn-ghost',
                style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '7px 14px', fontSize: 12 },
                onClick: () => cancel(o.id)
              }, 'Cancel')
            ))
          )
  );
}

// Trade-Up Calculator intentionally removed — s&box has no trade-up contract
// mechanic. See memory/sbox_vs_csfloat.md for the full list of CS-only
// features we don't port over.

// ── Loadout Lab ─────────────────────────────────────────────────
export function LoadoutLabModal({ onClose, me }) {
  const [tab, setTab]         = useState('discover'); // discover | mine | view
  const [list, setList]       = useState(null);
  const [search, setSearch]   = useState('');
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState('');
  const [viewing, setViewing] = useState(null);
  const [allItems, setAllItems] = useState([]);
  const [budget, setBudget]   = useState('');

  const load = useCallback(async () => {
    setList(null);
    if (tab === 'discover') setList(await fetchPublicLoadouts(search));
    else if (tab === 'mine' && me) setList(await fetchMyLoadouts());
  }, [tab, search, me]);
  useEffect(() => { load(); }, [load]);

  // Cache item pool for slot picker
  useEffect(() => {
    fetchListings({}).then(listings => {
      const seen = new Set();
      const items = [];
      listings.forEach(l => {
        if (!l?.item || seen.has(l.item.id)) return;
        seen.add(l.item.id);
        items.push(l.item);
      });
      setAllItems(items);
    });
  }, []);

  const openLoadout = async (id) => {
    const data = await fetchLoadout(id);
    setViewing(data);
    setTab('view');
  };

  const handleCreate = async () => {
    if (!newName.trim()) return;
    const created = await createLoadout({ name: newName, visibility: 'PUBLIC' });
    setNewName('');
    setCreating(false);
    if (created && created.id) openLoadout(created.id);
  };

  const handleSlot = async (slot, itemId) => {
    if (!viewing) return;
    await setLoadoutSlot(viewing.loadout.id, slot, itemId);
    const fresh = await fetchLoadout(viewing.loadout.id);
    setViewing(fresh);
  };

  const handleGenerate = async () => {
    if (!viewing) return;
    const b = budget ? parseFloat(budget) : 10000;
    await generateLoadout(viewing.loadout.id, b);
    const fresh = await fetchLoadout(viewing.loadout.id);
    setViewing(fresh);
  };

  const handleDelete = async () => {
    if (!viewing) return;
    if (!confirm('Delete this loadout?')) return;
    await deleteLoadout(viewing.loadout.id);
    setViewing(null);
    setTab('mine');
    load();
  };

  if (tab === 'view' && viewing) {
    const isOwner = me && viewing.loadout.ownerUserId === me.id;
    const SLOT_NAMES = ['Hats','Jackets','Shirts','Pants','Gloves','Boots','Accessories','Wild'];
    const slotsBySlot = Object.fromEntries(viewing.slots.map(s => [s.slot, s]));

    return h(InfoModal, { title: viewing.loadout.name, onClose },
      h('div', { style: { display: 'flex', gap: 10, marginBottom: 14 } },
        h('button', { className: 'btn btn-ghost', onClick: () => { setViewing(null); setTab(isOwner ? 'mine' : 'discover'); } }, '← Back'),
        h('div', { style: { flex: 1 } }),
        h('div', { style: { color: 'var(--text-muted)', fontSize: 12, alignSelf: 'center' } },
          'By ', h('strong', { style: { color: 'var(--text-secondary)' } }, viewing.loadout.ownerName || 'anon'),
          ' · ❤ ', viewing.loadout.favorites
        ),
        !isOwner && h('button', { className: 'btn btn-ghost', onClick: () => favoriteLoadout(viewing.loadout.id).then(load) }, '♡ Favorite'),
        isOwner && h('button', { className: 'btn btn-ghost', style: { color: 'var(--red)', border: '1px solid rgba(248,113,113,0.3)' }, onClick: handleDelete }, 'Delete')
      ),
      isOwner && h('div', { className: 'loadout-tools' },
        h('input', { className: 'price-input', placeholder: 'Max budget', value: budget, onChange: e => setBudget(e.target.value), style: { width: 130 } }),
        h('button', { className: 'btn btn-accent', onClick: handleGenerate }, '✨ Generate'),
        h('div', { style: { color: 'var(--text-muted)', fontSize: 12, marginLeft: 'auto' } },
          'Total Value · ',
          h('strong', { style: { color: 'var(--accent)' } }, fmt(viewing.loadout.totalValue))
        )
      ),
      h('div', { className: 'loadout-grid' },
        SLOT_NAMES.map(slotName => {
          const s = slotsBySlot[slotName];
          return h('div', { key: slotName, className: 'loadout-slot' },
            h('div', { className: 'loadout-slot-label' }, slotName),
            s && s.itemId
              ? h('div', { className: 'loadout-slot-filled' },
                  h('div', { className: 'loadout-slot-emoji' }, s.itemEmoji || '👕'),
                  h('div', { className: 'loadout-slot-name' }, s.itemName),
                  h('div', { className: 'loadout-slot-price' }, fmt(s.snapshotPrice)),
                  isOwner && h('button', { className: 'loadout-slot-clear', onClick: () => handleSlot(slotName, null), title: 'Clear', 'aria-label': `Clear ${slotName} slot` }, '✕')
                )
              : h('div', { className: 'loadout-slot-empty' },
                  isOwner
                    ? h(SlotPicker, { slot: slotName, allItems, onPick: (id) => handleSlot(slotName, id) })
                    : h('span', { style: { color: 'var(--text-muted)', fontSize: 11 } }, 'empty')
                )
          );
        })
      )
    );
  }

  return h(InfoModal, { title: 'Loadout Lab', onClose },
    h('div', { className: 'loadout-tabs' },
      h('button', { className: `offer-tab ${tab === 'discover' ? 'active' : ''}`, onClick: () => setTab('discover') }, 'Discover'),
      me && h('button', { className: `offer-tab ${tab === 'mine' ? 'active' : ''}`, onClick: () => setTab('mine') }, 'My Loadouts'),
      h('div', { style: { flex: 1 } }),
      me && h('button', { className: 'btn btn-accent', onClick: () => setCreating(c => !c) }, creating ? 'Cancel' : '+ Create')
    ),
    creating && h('div', { style: { marginBottom: 14, display: 'flex', gap: 10 } },
      h('input', { className: 'price-input', placeholder: 'Loadout name…', value: newName, onChange: e => setNewName(e.target.value), style: { flex: 1 } }),
      h('button', { className: 'btn btn-accent', onClick: handleCreate, disabled: !newName.trim() }, 'Create')
    ),
    tab === 'discover' && h('input', {
      className: 'price-input',
      placeholder: 'Search loadouts…',
      style: { width: '100%', marginBottom: 14 },
      value: search,
      onChange: e => setSearch(e.target.value)
    }),
    list === null
      ? h('div', { className: 'spinner' })
      : list.length === 0
        ? h('div', { className: 'empty-inline' },
            h('div', { className: 'empty-icon' }, '👗'),
            h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } },
              tab === 'mine' ? 'No loadouts yet. Create one to get started.' : 'No public loadouts found.'))
        : h('div', { className: 'loadout-list' },
            list.map(l => h('div', { key: l.id, className: 'loadout-card', onClick: () => openLoadout(l.id) },
              h('div', { className: 'loadout-card-name' }, l.name),
              h('div', { className: 'loadout-card-meta' },
                h('span', null, '❤ ', l.favorites),
                h('span', null, fmt(l.totalValue))
              ),
              l.ownerName && h('div', { className: 'loadout-card-owner' }, 'by ' + l.ownerName)
            ))
          )
  );
}

function SlotPicker({ slot, allItems, onPick }) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const filtered = useMemo(() => {
    let pool = allItems;
    if (slot !== 'Wild') pool = pool.filter(i => i.category === slot);
    if (q) pool = pool.filter(i => i.name.toLowerCase().includes(q.toLowerCase()));
    return pool.slice(0, 20);
  }, [allItems, slot, q]);

  if (!open) {
    return h('button', { className: 'loadout-add-btn', onClick: () => setOpen(true) }, '+ Add');
  }
  return h('div', { className: 'loadout-picker' },
    h('input', { className: 'price-input', autoFocus: true, placeholder: 'Filter…', value: q, onChange: e => setQ(e.target.value), style: { width: '100%', marginBottom: 6 } }),
    h('div', { className: 'loadout-picker-list' },
      filtered.map(it => h('div', {
        key: it.id, className: 'loadout-picker-item',
        onClick: () => { onPick(it.id); setOpen(false); }
      },
        h('span', { style: { fontSize: 16 } }, it.iconEmoji || '👕'),
        h('span', { style: { fontSize: 12, flex: 1, minWidth: 0 } }, it.name),
        h('span', { style: { fontSize: 11, color: 'var(--accent)', fontFamily: 'JetBrains Mono, monospace' } }, fmt(it.lowestPrice))
      ))
    ),
    h('button', { className: 'loadout-add-btn', style: { marginTop: 6 }, onClick: () => setOpen(false) }, 'Cancel')
  );
}

// ── Notifications (full feed) ───────────────────────────────────
export function NotificationsModal({ onClose, me }) {
  // Grouped-by-day rendering with a Today / Yesterday / date-label header per
  // section and an unread-only filter. Mirrors the way CSFloat (and Slack)
  // organise high-volume feeds so scanning a week of notifications is fast.
  const [data, setData] = useState({ items: [], unread: 0 });
  const [filter, setFilter] = useState('ALL');
  const load = useCallback(async () => { setData(await fetchNotifications()); }, []);
  useEffect(() => { if (me) load(); }, [me, load]);

  if (!me) return h(InfoModal, { title: 'Notifications', onClose },
    h('div', { className: 'empty-inline' },
      h('div', { className: 'empty-icon' }, '🔒'),
      h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'Sign in to see your notifications.')));

  const clear = async () => { await markAllNotificationsRead(); load(); };
  const open = async (n) => { if (!n.read) { await markNotificationRead(n.id); load(); } };

  // Group items by local-calendar day and label each bucket.
  const groups = useMemo(() => {
    const source = filter === 'UNREAD' ? data.items.filter(n => !n.read) : data.items;
    const buckets = {};
    source.forEach(n => {
      const d = new Date(n.createdAt);
      const key = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
      if (!buckets[key]) buckets[key] = { label: dayLabel(d), items: [] };
      buckets[key].items.push(n);
    });
    return Object.entries(buckets)
      .sort(([a], [b]) => (a < b ? 1 : -1))
      .map(([k, v]) => ({ key: k, label: v.label, items: v.items }));
  }, [data.items, filter]);

  const count = filter === 'UNREAD' ? data.unread : data.items.length;

  return h(InfoModal, { title: `Notifications · ${data.unread} unread`, onClose },
    h('div', { style: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 } },
      h('button', { className: `offer-tab ${filter === 'ALL' ? 'active' : ''}`, onClick: () => setFilter('ALL') },
        'All ', h('span', { className: 'filter-count', style: { marginLeft: 6 } }, data.items.length)),
      h('button', { className: `offer-tab ${filter === 'UNREAD' ? 'active' : ''}`, onClick: () => setFilter('UNREAD') },
        'Unread ', h('span', { className: 'filter-count', style: { marginLeft: 6 } }, data.unread)),
      h('div', { style: { flex: 1 } }),
      data.items.length > 0 && h('button', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11 }, onClick: clear }, 'Mark all read')
    ),
    count === 0
      ? h('div', { className: 'empty-inline' },
          h('div', { className: 'empty-icon' }, '🔔'),
          h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } },
            filter === 'UNREAD' ? 'No unread notifications.' : 'No notifications yet.'))
      : h('div', { className: 'notif-feed' },
          groups.map(g => h('div', { key: g.key },
            h('div', { className: 'notif-feed-day' }, g.label),
            g.items.map(n => h('div', {
              key: n.id,
              className: `notif-feed-row ${n.read ? '' : 'unread'}`,
              onClick: () => open(n)
            },
              h('div', { className: 'notif-feed-icon' }, kindIcon(n.kind)),
              h('div', { style: { flex: 1, minWidth: 0 } },
                h('div', { className: 'notif-feed-title' }, n.title),
                n.body && h('div', { className: 'notif-feed-body' }, n.body),
                h('div', { className: 'notif-feed-time' }, timeAgo(n.createdAt))
              ),
              !n.read && h('div', { className: 'notif-feed-dot' })
            ))
          ))
        )
  );
}

function dayLabel(d) {
  const today = new Date();
  const yest = new Date();
  yest.setDate(today.getDate() - 1);
  const same = (a, b) => a.getFullYear() === b.getFullYear() &&
                         a.getMonth() === b.getMonth() &&
                         a.getDate() === b.getDate();
  if (same(d, today)) return 'Today';
  if (same(d, yest))  return 'Yesterday';
  return d.toLocaleDateString(undefined, { weekday: 'long', month: 'short', day: 'numeric' });
}

function kindIcon(kind) {
  const map = {
    ITEM_PURCHASED: '🛒', TRADE_VERIFIED: '✓', TRADE_OPENED: '⇄',
    TRADE_ACCEPTED: '🤝', TRADE_SENT: '📨', TRADE_CANCELLED: '✕',
    TRADE_REQUESTED: '🛎',
    AUCTION_WON: '🏆', AUCTION_LOST: '✕', AUCTION_OUTBID: '↑', AUCTION_ENDING: '⏰',
    OFFER_RECEIVED: '💬', OFFER_ACCEPTED: '✓', OFFER_REJECTED: '✕',
    BUY_ORDER_FILLED: '⚡',
    DEPOSIT_COMPLETE: '$', WITHDRAWAL_COMPLETE: '$', WITHDRAWAL_REJECTED: '✕',
    ACCOUNT_BANNED: '🚫', ACCOUNT_UNBANNED: '↩',
    ADMIN_CREDIT: '+', CSR_CREDIT: '+',
    STEAM_INVENTORY: '🎮',
    SUPPORT_REPLY: '💬',
    LISTING_REMOVED: '⚠'
  };
  return map[kind] || '•';
}

// ── Auction bid panel — embeds inside ItemModal for AUCTION listings ──
export function AuctionBidPanel({ listing, me, onPlaced }) {
  const [history, setHistory] = useState([]);
  const [amount, setAmount]   = useState('');
  const [maxAmount, setMax]   = useState('');
  const [busy, setBusy]       = useState(false);
  const [err, setErr]         = useState('');
  const [now, setNow]         = useState(Date.now());

  const load = useCallback(async () => {
    if (!listing?.id) return;
    setHistory(await fetchBidHistory(listing.id));
  }, [listing?.id]);
  useEffect(() => { load(); }, [load]);

  // Tick every second for the countdown timer
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  if (!listing || listing.listingType !== 'AUCTION') return null;

  const remaining = listing.expiresAt - now;
  const ended = remaining <= 0;
  const fmtTime = (ms) => {
    if (ms <= 0) return 'Ended';
    const s = Math.floor(ms / 1000);
    const d = Math.floor(s / 86400);
    const h_ = Math.floor((s % 86400) / 3600);
    const m = Math.floor((s % 3600) / 60);
    const sec = s % 60;
    if (d > 0) return `${d}d ${h_}h ${m}m`;
    return `${String(h_).padStart(2,'0')}:${String(m).padStart(2,'0')}:${String(sec).padStart(2,'0')}`;
  };
  const floor = parseFloat(listing.currentBid || listing.price);
  const minNext = (floor + 0.05).toFixed(2);

  const submit = async () => {
    setErr('');
    if (!me) { setErr('Sign in to bid'); return; }
    const a = parseFloat(amount);
    if (!a || a < parseFloat(minNext)) { setErr(`Minimum bid is $${minNext}`); return; }
    setBusy(true);
    try {
      const res = await placeBid(listing.id, a, maxAmount ? parseFloat(maxAmount) : null);
      if (res.code || res.error) { setErr(res.message || res.error); return; }
      setAmount(''); setMax('');
      load();
      onPlaced && onPlaced();
    } finally { setBusy(false); }
  };

  return h('div', { className: 'auction-panel' },
    h('div', { className: 'auction-header' },
      h('div', null,
        h('div', { className: 'auction-label' }, 'CURRENT BID'),
        h('div', { className: 'auction-bid' }, fmt(listing.currentBid || listing.price)),
        listing.currentBidderName && h('div', { className: 'auction-bidder' }, 'by ' + listing.currentBidderName)
      ),
      h('div', { style: { textAlign: 'right' } },
        h('div', { className: 'auction-label' }, ended ? 'STATUS' : 'TIME LEFT'),
        h('div', { className: `auction-timer ${remaining < 60_000 ? 'urgent' : ''}` }, fmtTime(remaining))
      )
    ),
    !ended && h('div', { className: 'auction-bid-form' },
      h('input', { className: 'wallet-amount-input', type: 'number', step: '0.05', min: minNext,
        placeholder: `Min $${minNext}`, value: amount, onChange: e => setAmount(e.target.value) }),
      h('input', { className: 'wallet-amount-input', type: 'number', step: '0.05',
        placeholder: 'Auto-bid cap (optional)', value: maxAmount, onChange: e => setMax(e.target.value) }),
      err && h('div', { className: 'wallet-error' }, err),
      h('button', { className: 'btn btn-accent', disabled: busy, onClick: submit }, busy ? 'Placing…' : 'Place Bid')
    ),
    history.length > 0 && h('div', { className: 'auction-history' },
      h('div', { className: 'modal-section-title', style: { marginTop: 16 } },
        h('div', { className: 'section-title-dot' }), `Bid History (${history.length})`),
      history.slice(0, 8).map(b => h('div', { key: b.id, className: 'auction-history-row' },
        h('div', { className: 'auction-history-bidder' }, b.bidderName || 'anon'),
        h('div', { className: 'auction-history-kind' }, b.kind),
        h('div', { className: 'auction-history-amt' }, fmt(b.amount)),
        h('div', { className: 'auction-history-time' }, timeAgo(b.createdAt))
      ))
    )
  );
}
