// Admin + CSR panels. Kept in their own file so the ordinary-user modal
// module doesn't pull in staff code paths. Both panels are tabbed, use
// InfoModal as the shell, and call into api.js for I/O.
import { h, useState, useEffect, useCallback, fmt, timeAgo } from './utils.js';
import { InfoModal } from './info-modal.js';
import {
  adminStats, adminWithdrawals, adminApproveWithdrawal, adminRejectWithdrawal,
  adminUsers, adminBanUser, adminUnbanUser, adminGrant, adminRevoke,
  adminCreditWallet, adminRemoveListing, adminTickets, adminTicket,
  adminTicketReply, adminCloseTicket, adminRefundDeposit, adminAudit,
  adminFraudSignals,
  adminTrades, adminReleaseTrade, adminCancelTrade,
  adminSimulateListings, adminClearSimulated, adminCountSimulated, adminSyncScmm,
  csrStats, csrLookup, csrTickets, csrTicket, csrTicketReply, csrCloseTicket,
  csrGoodwill, csrFlagListing
} from './api.js';

// ── ADMIN PANEL ─────────────────────────────────────────────────
export function AdminModal({ onClose, me }) {
  const [tab, setTab] = useState('dashboard');
  const TABS = [
    { id: 'dashboard',   label: '📊 Dashboard' },
    { id: 'withdrawals', label: '💸 Withdrawals' },
    { id: 'trades',      label: '⇄ Trades' },
    { id: 'users',       label: '👥 Users' },
    { id: 'tickets',     label: '🎧 Tickets' },
    { id: 'refunds',     label: '↩ Refunds' },
    { id: 'simulator',   label: '🧪 Simulator' },
    { id: 'fraud',       label: '🚨 Fraud' },
    { id: 'audit',       label: '📜 Audit Log' },
  ];
  return h(InfoModal, { title: '⚙ Admin Panel', onClose },
    h('div', { className: 'staff-banner admin' },
      h('strong', null, 'ADMIN MODE'),
      ' — every action here is logged with your Steam ID and is reversible only by another admin. Use with care.'
    ),
    h('div', { className: 'profile-tabs' },
      TABS.map(t => h('button', {
        key: t.id,
        className: `profile-tab ${tab === t.id ? 'active' : ''}`,
        onClick: () => setTab(t.id)
      }, t.label))
    ),
    tab === 'dashboard'   && h(AdminDashboardTab, null),
    tab === 'withdrawals' && h(AdminWithdrawalsTab, null),
    tab === 'trades'      && h(AdminTradesTab, null),
    tab === 'users'       && h(AdminUsersTab, { me }),
    tab === 'tickets'     && h(AdminTicketsTab, null),
    tab === 'refunds'     && h(AdminRefundsTab, null),
    tab === 'simulator'   && h(AdminSimulatorTab, null),
    tab === 'fraud'       && h(AdminFraudTab, null),
    tab === 'audit'       && h(AdminAuditTab, null),
  );
}

// Fraud-signals triage — read-only rollup of the last 24h of audit rows.
// Groups suspicious patterns by severity so an admin can scan HIGH rows
// first and dismiss LOW noise. No write actions; the admin still makes
// ban/unban decisions manually from the Users tab.
function AdminFraudTab() {
  const [rows, setRows]   = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const r = await adminFraudSignals();
      setRows(Array.isArray(r) ? r : []);
    } catch (e) { setError(e?.message || 'Failed to load'); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { load(); }, [load]);

  const sevClass = (s) => s === 'HIGH' ? 'sev-high' : s === 'MED' ? 'sev-med' : 'sev-low';
  const sevIcon  = (s) => s === 'HIGH' ? '🔴' : s === 'MED' ? '🟡' : '⚪';

  return h('div', { className: 'admin-tab-content' },
    h('div', { className: 'admin-card' },
      h('div', { className: 'admin-card-title' }, '🚨 Fraud Signals (last 24h)'),
      h('div', { className: 'admin-card-note' },
        "Rolled up from the audit log. These are patterns worth investigating — " +
        "not guaranteed fraud. Click a row to open the flagged user in the Users tab."),
      h('button', {
        className: 'btn btn-ghost',
        style: { marginTop: 10, padding: '6px 14px' },
        onClick: load, disabled: loading
      }, loading ? 'Loading…' : 'Refresh'),
      error && h('div', { className: 'admin-error' }, error),
      !loading && rows.length === 0 && h('div', { className: 'empty-inline' },
        '✨ No suspicious patterns detected in the last 24 hours.'),
      rows.length > 0 && h('div', { className: 'fraud-list' },
        rows.map((r, i) => h('div', {
          key: i,
          className: `fraud-row ${sevClass(r.severity)}`
        },
          h('div', { className: 'fraud-sev' }, sevIcon(r.severity), ' ', r.severity),
          h('div', { className: 'fraud-body' },
            h('div', { className: 'fraud-type' }, r.type),
            h('div', { className: 'fraud-summary' }, r.summary),
            r.ip && h('div', { className: 'fraud-meta' }, 'IP: ', r.ip),
            r.userId && h('div', { className: 'fraud-meta' }, 'User: ', r.userName || `#${r.userId}`),
          ),
          h('div', { className: 'fraud-time' }, r.createdAt ? timeAgo(r.createdAt) : '')
        ))
      )
    )
  );
}

// Simulator: spawns fake listings + fake auctions for QA so the marketplace
// doesn't look empty while you're click-testing features. Every row is
// tagged "SIM · <handle>" so `Clear all` strips only simulated rows.
function AdminSimulatorTab() {
  const [count, setCount]     = useState(0);
  const [busy, setBusy]       = useState(false);
  const [spawnN, setSpawnN]   = useState(20);
  const [toast, setToast]     = useState('');

  const reload = useCallback(async () => {
    try {
      const res = await adminCountSimulated();
      if (res && typeof res.count === 'number') setCount(res.count);
    } catch (e) {}
  }, []);
  useEffect(() => { reload(); }, [reload]);

  const runSpawn = async () => {
    setBusy(true); setToast('');
    try {
      const res = await adminSimulateListings(spawnN);
      if (res.code || res.error) { setToast(`Error: ${res.message || res.error}`); return; }
      setToast(`Spawned ${res.created} simulated listings`);
      await reload();
    } finally { setBusy(false); }
  };
  const runClear = async () => {
    if (!confirm(`Delete all ${count} simulated listings? Real listings are untouched.`)) return;
    setBusy(true); setToast('');
    try {
      const res = await adminClearSimulated();
      if (res.code || res.error) { setToast(`Error: ${res.message || res.error}`); return; }
      setToast(`Removed ${res.removed} simulated listings`);
      await reload();
    } finally { setBusy(false); }
  };
  const runSync = async () => {
    setBusy(true); setToast('');
    try {
      const res = await adminSyncScmm();
      if (res.error) { setToast(`Error: ${res.error}`); return; }
      setToast(`SCMM sync complete — ${res.imported || 0} items refreshed`);
    } finally { setBusy(false); }
  };

  return h('div', { className: 'admin-panel' },
    h('div', { className: 'admin-card' },
      h('div', { className: 'admin-card-title' }, '🧪 Marketplace Simulator'),
      h('div', { className: 'admin-card-sub' },
        `${count} simulated listing${count === 1 ? '' : 's'} currently live. Real listings are never touched.`
      ),
      h('div', { className: 'admin-card-body' },
        h('div', { style: { display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' } },
          h('label', { style: { fontSize: 12, color: 'var(--text-muted)', fontWeight: 600 } }, 'Count:'),
          h('input', {
            type: 'number', min: 1, max: 100, step: 1,
            value: spawnN,
            onChange: e => setSpawnN(Math.max(1, Math.min(100, parseInt(e.target.value, 10) || 20))),
            style: {
              width: 80, padding: '8px 12px', fontSize: 13, fontWeight: 700,
              background: 'var(--bg-input)', border: '1px solid var(--border)',
              color: 'var(--text-primary)', borderRadius: 6, fontFamily: 'JetBrains Mono, monospace'
            }
          }),
          h('button', {
            className: 'btn btn-accent',
            disabled: busy,
            onClick: runSpawn
          }, busy ? 'Working…' : `Spawn ${spawnN} listings`),
          h('button', {
            className: 'btn btn-ghost',
            disabled: busy || count === 0,
            style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)' },
            onClick: runClear
          }, `Clear ${count} simulated`),
          h('button', {
            className: 'btn btn-ghost',
            disabled: busy,
            style: { border: '1px solid var(--border)' },
            onClick: runSync
          }, '↻ Re-sync SCMM catalogue')
        ),
        toast && h('div', {
          style: {
            marginTop: 14, padding: '10px 14px',
            background: toast.startsWith('Error') ? 'var(--red-dim)' : 'var(--accent-dim)',
            border: `1px solid ${toast.startsWith('Error') ? 'rgba(248,113,113,0.3)' : 'var(--accent-border)'}`,
            color: toast.startsWith('Error') ? 'var(--red)' : 'var(--accent)',
            borderRadius: 6, fontSize: 12, fontWeight: 600
          }
        }, toast),
        h('div', {
          style: { marginTop: 16, fontSize: 11, color: 'var(--text-muted)', lineHeight: 1.6 }
        },
          '• Each simulated listing picks a real catalogue item and applies a random price jitter so the grid looks lived-in.',
          h('br'), '• ~1 in 5 picks also spawns a 24h auction so you can smoke-test the bid flow.',
          h('br'), '• Rows are marked "SIM · <handle>" in the seller column and tagged "[SIMULATED]" in the description — safe to leave in place during QA, nuke with Clear before launch.'
        )
      )
    )
  );
}

function AdminTradesTab() {
  const [rows, setRows]     = useState(null);
  const [filter, setFilter] = useState('DISPUTED');
  const [busy, setBusy]     = useState(false);

  const load = useCallback(async () => { setRows(null); setRows(await adminTrades(filter)); }, [filter]);
  useEffect(() => { load(); }, [load]);

  const release = async (r) => {
    const reason = prompt('Reason for force-release (shows in audit log + user notification):');
    if (reason == null) return;
    setBusy(true);
    try {
      const res = await adminReleaseTrade(r.id, reason);
      if (res.code || res.error) { alert(res.message || res.error); return; }
      await load();
    } finally { setBusy(false); }
  };
  const cancel = async (r) => {
    const reason = prompt('Reason for force-cancel (buyer will be refunded):');
    if (reason == null) return;
    setBusy(true);
    try {
      const res = await adminCancelTrade(r.id, reason);
      if (res.code || res.error) { alert(res.message || res.error); return; }
      await load();
    } finally { setBusy(false); }
  };

  const STATES = ['ALL','PENDING_SELLER_ACCEPT','PENDING_SELLER_SEND','PENDING_BUYER_CONFIRM','VERIFIED','DISPUTED','CANCELLED'];

  return h('div', { className: 'profile-panel' },
    h('div', { style: { display: 'flex', gap: 6, marginBottom: 14, flexWrap: 'wrap' } },
      STATES.map(s => h('button', {
        key: s,
        className: `offer-tab ${filter === s ? 'active' : ''}`,
        onClick: () => setFilter(s)
      }, s.replace(/_/g, ' ').toLowerCase()))
    ),
    rows === null
      ? h('div', { className: 'spinner' })
      : rows.length === 0
        ? h('div', { className: 'empty-inline' },
            h('div', { className: 'empty-icon' }, '⇄'),
            h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, `No ${filter.toLowerCase()} trades.`))
        : h('table', { className: 'db-table' },
            h('thead', null, h('tr', null,
              h('th', null, 'ID'),
              h('th', null, 'Item'),
              h('th', null, 'Buyer'),
              h('th', null, 'Seller'),
              h('th', null, 'State'),
              h('th', { className: 'right' }, 'Price'),
              h('th', { className: 'right' }, 'Updated'),
              h('th', { className: 'right' }, 'Actions'))),
            h('tbody', null, rows.map(r => h('tr', { key: r.id, className: 'db-row' },
              h('td', { className: 'db-rank' }, '#' + r.id),
              h('td', null, r.itemName || '—'),
              h('td', { className: 'db-mono', style: { fontSize: 11 } }, '#' + (r.buyerUserId || '?')),
              h('td', { className: 'db-mono', style: { fontSize: 11 } }, '#' + (r.sellerUserId || 'system')),
              h('td', { style: { fontSize: 10, fontWeight: 700 } }, (r.state || '').replace(/_/g, ' ')),
              h('td', { className: 'right db-mono accent' }, fmt(r.price)),
              h('td', { className: 'right', style: { fontSize: 11, color: 'var(--text-muted)' } }, timeAgo(r.updatedAt)),
              h('td', { className: 'right' },
                !['VERIFIED','CANCELLED'].includes(r.state) && h('div', { style: { display: 'flex', gap: 4, justifyContent: 'flex-end' } },
                  h('button', { className: 'buy-btn', disabled: busy, onClick: () => release(r), title: 'Force-release funds to seller' }, 'Release'),
                  h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '5px 10px', fontSize: 11 },
                    disabled: busy, onClick: () => cancel(r), title: 'Force-cancel and refund the buyer'
                  }, 'Cancel')
                )
              )
            )))
          )
  );
}

function AdminAuditTab() {
  const [rows, setRows] = useState(null);
  const [filter, setFilter] = useState({ event: '', actor: '', subject: '' });
  const load = useCallback(async () => {
    setRows(null);
    setRows(await adminAudit({
      event:   filter.event   || null,
      actor:   filter.actor   || null,
      subject: filter.subject || null
    }));
  }, [filter]);
  useEffect(() => { load(); }, [load]);

  const EVENTS = ['','DEPOSIT_COMPLETE','WITHDRAW_REQUESTED','WITHDRAW_APPROVED','WITHDRAW_REJECTED','REFUND_ISSUED',
                  'LISTING_PURCHASED','LISTING_FORCE_CANCELLED','USER_BANNED','USER_UNBANNED',
                  'ADMIN_GRANTED','ADMIN_REVOKED','CSR_CREDIT','ADMIN_CREDIT','API_KEY_MINTED','API_KEY_REVOKED'];

  return h('div', { className: 'profile-panel' },
    h('div', { style: { display: 'flex', gap: 10, marginBottom: 14, flexWrap: 'wrap' } },
      h('select', { className: 'sort-select', value: filter.event, onChange: e => setFilter(f => ({ ...f, event: e.target.value })) },
        EVENTS.map(ev => h('option', { key: ev || 'all', value: ev }, ev || 'All events'))
      ),
      h('input', { className: 'price-input', style: { width: 130 }, placeholder: 'Actor user #id', value: filter.actor, onChange: e => setFilter(f => ({ ...f, actor: e.target.value })) }),
      h('input', { className: 'price-input', style: { width: 130 }, placeholder: 'Subject user #id', value: filter.subject, onChange: e => setFilter(f => ({ ...f, subject: e.target.value })) }),
      h('button', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11 }, onClick: load }, 'Refresh')
    ),
    rows === null
      ? h('div', { className: 'spinner' })
      : rows.length === 0
        ? h('div', { className: 'empty-inline' }, h('div', { className: 'empty-icon' }, '📜'),
            h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'No audit entries match those filters.'))
        : h('table', { className: 'db-table' },
            h('thead', null, h('tr', null,
              h('th', null, 'When'),
              h('th', null, 'Event'),
              h('th', null, 'Actor'),
              h('th', null, 'Subject'),
              h('th', null, 'Summary'),
              h('th', null, 'IP')
            )),
            h('tbody', null, rows.slice(0, 200).map(r => h('tr', { key: r.id },
              h('td', { className: 'db-rank' }, timeAgo(r.createdAt)),
              h('td', { style: { fontSize: 10, fontWeight: 700, color: 'var(--accent)' } }, r.eventType),
              h('td', { className: 'db-mono', style: { fontSize: 11 } }, r.actorName || (r.actorUserId ? '#' + r.actorUserId : 'system')),
              h('td', { className: 'db-mono', style: { fontSize: 11 } }, r.subjectName || (r.subjectUserId ? '#' + r.subjectUserId : '—')),
              h('td', { style: { fontSize: 11, color: 'var(--text-secondary)', maxWidth: 380, overflow: 'hidden', textOverflow: 'ellipsis' } }, r.summary || '—'),
              h('td', { className: 'db-mono', style: { fontSize: 10, color: 'var(--text-muted)' } }, r.ipAddress || '—')
            )))
          )
  );
}

function AdminDashboardTab() {
  const [stats, setStats] = useState(null);
  useEffect(() => { adminStats().then(setStats); }, []);
  if (!stats) return h('div', { className: 'spinner' });
  const Stat = (label, val, cls) =>
    h('div', { className: 'admin-stat' },
      h('div', { className: 'admin-stat-label' }, label),
      h('div', { className: `admin-stat-val ${cls || ''}` }, val)
    );
  return h('div', { className: 'profile-panel' },
    h('div', { className: 'admin-stats-grid' },
      Stat('Registered Users',     Number(stats.users || 0).toLocaleString()),
      Stat('Catalogue Items',      Number(stats.items || 0).toLocaleString()),
      Stat('Active Listings',      Number(stats.activeListings || 0).toLocaleString()),
      Stat('Total Escrow',         fmt(stats.totalEscrow || 0), 'accent'),
      Stat('Deposits 24h',         fmt(stats.deposits24h || 0), 'green'),
      Stat('Sales 24h',            fmt(stats.sales24h || 0), 'green'),
      Stat('Pending Withdrawals',  `${stats.pendingWithdrawals || 0} · ${fmt(stats.pendingWithdrawalsAmount || 0)}`, 'yellow'),
      Stat('Open Tickets',         Number(stats.openTickets || 0)),
      Stat('Banned Users',         Number(stats.bannedUsers || 0), stats.bannedUsers > 0 ? 'red' : '')
    )
  );
}

function AdminWithdrawalsTab() {
  const [rows, setRows]     = useState(null);
  const [filter, setFilter] = useState('PENDING');
  const [busy, setBusy]     = useState(false);
  const load = useCallback(async () => {
    setRows(null);
    setRows(await adminWithdrawals(filter));
  }, [filter]);
  useEffect(() => { load(); }, [load]);

  const approve = async (row) => {
    if (busy) return;
    const ref = prompt('Stripe/Connect payout reference (optional):', row.destination || '') || '';
    setBusy(true);
    try {
      const res = await adminApproveWithdrawal(row.id, ref);
      if (res.code || res.error) { alert(res.message || res.error); return; }
      await load();
    } finally { setBusy(false); }
  };
  const reject = async (row) => {
    if (busy) return;
    const reason = prompt('Reason for rejection (user will see this):', '');
    if (reason == null) return;
    setBusy(true);
    try {
      const res = await adminRejectWithdrawal(row.id, reason);
      if (res.code || res.error) { alert(res.message || res.error); return; }
      await load();
    } finally { setBusy(false); }
  };

  return h('div', { className: 'profile-panel' },
    h('div', { style: { display: 'flex', gap: 10, marginBottom: 14 } },
      ['PENDING','COMPLETED','FAILED'].map(f =>
        h('button', { key: f, className: `offer-tab ${filter === f ? 'active' : ''}`, onClick: () => setFilter(f) }, f)
      ),
      h('div', { style: { flex: 1 } }),
      h('button', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11 }, onClick: load }, 'Refresh')
    ),
    rows === null
      ? h('div', { className: 'spinner' })
      : rows.length === 0
        ? h('div', { className: 'empty-inline' },
            h('div', { className: 'empty-icon' }, '💸'),
            h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, `No ${filter.toLowerCase()} withdrawals.`))
        : h('table', { className: 'db-table' },
            h('thead', null, h('tr', null,
              h('th', null, 'ID'),
              h('th', null, 'Wallet'),
              h('th', null, 'Destination'),
              h('th', { className: 'right' }, 'Amount'),
              h('th', null, 'Created'),
              h('th', { className: 'right' }, 'Action'))),
            h('tbody', null, rows.map(r => h('tr', { key: r.id, className: 'db-row' },
              h('td', { className: 'db-rank' }, '#' + r.id),
              h('td', { className: 'db-mono' }, r.walletUsername || ('wallet ' + r.walletId)),
              h('td', { style: { fontSize: 11, color: 'var(--text-muted)', maxWidth: 220, overflow: 'hidden', textOverflow: 'ellipsis' } }, r.destination || '—'),
              h('td', { className: 'right db-mono accent' }, fmt(r.amount)),
              h('td', { style: { fontSize: 11, color: 'var(--text-muted)' } }, timeAgo(r.createdAt)),
              h('td', { className: 'right' },
                filter === 'PENDING' && h('div', { style: { display: 'flex', gap: 6, justifyContent: 'flex-end' } },
                  h('button', { className: 'buy-btn', disabled: busy, onClick: () => approve(r) }, 'Approve'),
                  h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '5px 10px', fontSize: 11 },
                    disabled: busy,
                    onClick: () => reject(r)
                  }, 'Reject')
                )
              )
            )))
          )
  );
}

function AdminUsersTab({ me }) {
  const [rows, setRows] = useState(null);
  const [search, setSearch] = useState('');
  const [busy, setBusy] = useState(false);
  const load = useCallback(async () => { setRows(await adminUsers(search)); }, [search]);
  useEffect(() => { load(); }, [load]);

  const doBan = async (u) => {
    const reason = prompt('Ban reason (shown to user):', '');
    if (reason == null) return;
    setBusy(true);
    try {
      const res = await adminBanUser(u.id, reason);
      if (res.code || res.error) { alert(res.message || res.error); return; }
      await load();
    } finally { setBusy(false); }
  };
  const doUnban = async (u) => {
    if (!confirm(`Unban ${u.displayName || u.steamId64}?`)) return;
    setBusy(true);
    try {
      const res = await adminUnbanUser(u.id);
      if (res && res.error) { alert(res.error); return; }
      await load();
    } finally { setBusy(false); }
  };
  const doGrant = async (u) => {
    if (!confirm(`Grant ADMIN role to ${u.displayName || u.steamId64}?`)) return;
    setBusy(true);
    try {
      const res = await adminGrant(u.id);
      if (res && res.error) { alert(res.error); return; }
      await load();
    } finally { setBusy(false); }
  };
  const doRevoke = async (u) => {
    if (!confirm(`Revoke ADMIN role from ${u.displayName || u.steamId64}?`)) return;
    setBusy(true);
    try {
      const res = await adminRevoke(u.id);
      if (res.code || res.error) { alert(res.message || res.error); return; }
      await load();
    } finally { setBusy(false); }
  };
  const doCredit = async (u) => {
    const amtStr = prompt(`Adjust wallet for ${u.displayName || u.steamId64} — positive credits, negative debits ($):`, '');
    if (!amtStr) return;
    const amt = parseFloat(amtStr);
    if (isNaN(amt)) { alert('Enter a number'); return; }
    const note = prompt('Note (audit trail):', '');
    if (note == null) return;
    setBusy(true);
    try {
      const res = await adminCreditWallet(u.id, amt, note);
      if (res.code || res.error) { alert(res.message || res.error); return; }
      alert('New balance: $' + res.newBalance);
    } finally { setBusy(false); }
  };

  return h('div', { className: 'profile-panel' },
    h('div', { style: { display: 'flex', gap: 10, marginBottom: 14 } },
      h('input', {
        className: 'price-input', style: { flex: 1 },
        placeholder: 'Search by name, Steam ID…',
        value: search,
        onChange: e => setSearch(e.target.value),
        onKeyDown: e => { if (e.key === 'Enter') load(); }
      }),
      h('button', { className: 'btn btn-accent', onClick: load }, 'Search')
    ),
    rows === null
      ? h('div', { className: 'spinner' })
      : rows.length === 0
        ? h('div', { className: 'empty-inline' },
            h('div', { className: 'empty-icon' }, '👥'),
            h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'No matching users.'))
        : h('table', { className: 'db-table' },
            h('thead', null, h('tr', null,
              h('th', null, 'User'),
              h('th', null, 'Steam ID'),
              h('th', null, 'Role'),
              h('th', null, 'Status'),
              h('th', { className: 'right' }, 'Actions'))),
            h('tbody', null, rows.map(u => h('tr', { key: u.id, className: 'db-row' },
              h('td', null,
                h('div', { className: 'db-item-cell' },
                  u.avatarUrl
                    ? h('img', { src: u.avatarUrl, alt: u.displayName, style: { width: 32, height: 32, borderRadius: 6 } })
                    : h('div', { className: 'db-thumb', style: { width: 32, height: 32, fontSize: 14 } }, (u.displayName || 'U').substring(0,2).toUpperCase()),
                  h('div', null,
                    h('div', { className: 'db-name' }, u.displayName || 'Player'),
                    h('div', { className: 'db-sub' }, '#' + u.id)
                  )
                )
              ),
              h('td', { className: 'db-mono', style: { fontSize: 11 } }, u.steamId64),
              h('td', null,
                h('span', { style: { fontSize: 10, fontWeight: 700, padding: '3px 8px', borderRadius: 4,
                  background: u.role === 'ADMIN' ? 'rgba(236,72,153,0.15)' : u.role === 'CSR' ? 'rgba(96,165,250,0.15)' : 'var(--bg-elevated)',
                  color:      u.role === 'ADMIN' ? '#ec4899' : u.role === 'CSR' ? '#60a5fa' : 'var(--text-muted)'
                } }, u.role || 'USER')
              ),
              h('td', null,
                u.banned
                  ? h('span', { style: { fontSize: 10, fontWeight: 700, color: 'var(--red)' } }, '🚫 BANNED')
                  : h('span', { style: { fontSize: 10, color: 'var(--green)' } }, '✓ active')
              ),
              h('td', { className: 'right' },
                h('div', { style: { display: 'flex', gap: 6, justifyContent: 'flex-end', flexWrap: 'wrap' } },
                  h('button', { className: 'btn btn-ghost', style: { padding: '5px 10px', fontSize: 11, border: '1px solid var(--border)' }, disabled: busy, onClick: () => doCredit(u) }, '$'),
                  u.role !== 'ADMIN'
                    ? h('button', { className: 'btn btn-ghost', style: { padding: '5px 10px', fontSize: 11, border: '1px solid var(--border)' }, disabled: busy, onClick: () => doGrant(u) }, '+Admin')
                    : (me?.id !== u.id && h('button', { className: 'btn btn-ghost', style: { padding: '5px 10px', fontSize: 11, border: '1px solid var(--border)' }, disabled: busy, onClick: () => doRevoke(u) }, '−Admin')),
                  u.banned
                    ? h('button', { className: 'btn btn-ghost', style: { padding: '5px 10px', fontSize: 11, border: '1px solid rgba(74,222,128,0.3)', color: 'var(--green)' }, disabled: busy, onClick: () => doUnban(u) }, 'Unban')
                    : h('button', { className: 'btn btn-ghost', style: { padding: '5px 10px', fontSize: 11, border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)' }, disabled: busy, onClick: () => doBan(u) }, 'Ban')
                )
              )
            )))
          )
  );
}

function AdminTicketsTab() {
  const [list, setList]     = useState(null);
  const [viewing, setView]  = useState(null);
  const [reply, setReply]   = useState('');
  const [filter, setFilter] = useState('');
  const [busy, setBusy]     = useState(false);

  const load = useCallback(async () => {
    setList(await adminTickets(filter));
  }, [filter]);
  useEffect(() => { load(); }, [load]);

  const open = async (id) => setView(await adminTicket(id));
  const sendReply = async () => {
    if (!reply.trim() || !viewing?.ticket) return;
    setBusy(true);
    try {
      const res = await adminTicketReply(viewing.ticket.id, reply);
      if (res && res.error) { alert(res.error); return; }
      setReply('');
      setView(await adminTicket(viewing.ticket.id));
      load();
    } finally { setBusy(false); }
  };
  const closeTicket = async () => {
    if (!viewing?.ticket) return;
    const res = await adminCloseTicket(viewing.ticket.id);
    if (res && res.error) { alert(res.error); return; }
    setView(await adminTicket(viewing.ticket.id));
    load();
  };

  if (viewing) {
    return h('div', { className: 'profile-panel' },
      h('div', { style: { display: 'flex', gap: 8, alignItems: 'center', marginBottom: 14 } },
        h('button', { className: 'btn btn-ghost', onClick: () => setView(null) }, '← Tickets'),
        h('div', { style: { flex: 1 } },
          h('div', { style: { fontSize: 14, fontWeight: 700 } }, '#' + viewing.ticket.id + ' · ' + viewing.ticket.subject),
          h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } },
            viewing.ticket.category + ' · ' + viewing.ticket.status + ' · ' + (viewing.ticket.username || '#' + viewing.ticket.userId))
        ),
        viewing.ticket.status !== 'RESOLVED' && h('button', { className: 'btn btn-ghost', onClick: closeTicket, style: { border: '1px solid var(--border)' } }, 'Close Ticket')
      ),
      h('div', { className: 'support-thread' },
        viewing.messages.map(m => h('div', { key: m.id, className: `support-msg ${m.author === 'STAFF' ? 'staff' : 'user'}` },
          h('div', { className: 'support-msg-head' }, m.authorName, ' · ', timeAgo(m.createdAt)),
          h('div', { className: 'support-msg-body' }, m.body)
        ))
      ),
      viewing.ticket.status !== 'RESOLVED' && h('div', { style: { display: 'flex', gap: 8, marginTop: 14 } },
        h('input', { className: 'chat-input', style: { flex: 1 }, placeholder: 'Staff reply…', value: reply, onChange: e => setReply(e.target.value), onKeyDown: e => { if (e.key === 'Enter') sendReply(); } }),
        h('button', { className: 'btn btn-accent', disabled: busy || !reply.trim(), onClick: sendReply }, 'Send')
      )
    );
  }

  return h('div', { className: 'profile-panel' },
    h('div', { style: { display: 'flex', gap: 10, marginBottom: 14 } },
      [['',  'All'],['WAITING_STAFF','Waiting on us'],['WAITING_USER','Waiting on user'],['RESOLVED','Resolved']].map(([v, l]) =>
        h('button', { key: v || 'all', className: `offer-tab ${filter === v ? 'active' : ''}`, onClick: () => setFilter(v) }, l)
      )
    ),
    list === null
      ? h('div', { className: 'spinner' })
      : list.length === 0
        ? h('div', { className: 'empty-inline' }, h('div', { className: 'empty-icon' }, '🎧'),
            h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'No tickets.'))
        : h('table', { className: 'db-table' },
            h('thead', null, h('tr', null,
              h('th', null, 'ID'), h('th', null, 'Subject'),
              h('th', null, 'User'), h('th', null, 'Status'),
              h('th', { className: 'right' }, 'Updated'))),
            h('tbody', null, list.map(t => h('tr', { key: t.id, className: 'db-row', onClick: () => open(t.id) },
              h('td', { className: 'db-rank' }, '#' + t.id),
              h('td', null, t.subject),
              h('td', { className: 'db-mono', style: { fontSize: 11 } }, t.username || '#' + t.userId),
              h('td', { style: { fontSize: 10, fontWeight: 700 } }, t.status),
              h('td', { className: 'right', style: { fontSize: 11, color: 'var(--text-muted)' } }, timeAgo(t.updatedAt))
            )))
          )
  );
}

function AdminRefundsTab() {
  const [txId, setTxId]   = useState('');
  const [amount, setAmt]  = useState('');
  const [busy, setBusy]   = useState(false);
  const [result, setRes]  = useState(null);

  const run = async () => {
    setBusy(true); setRes(null);
    try {
      const res = await adminRefundDeposit(parseInt(txId, 10), amount ? parseFloat(amount) : null);
      setRes(res);
    } finally { setBusy(false); }
  };

  return h('div', { className: 'profile-panel' },
    h('div', { className: 'staff-banner warning' },
      'Refunds hit Stripe immediately — there is no undo. Enter the deposit transaction id from the user\'s Transactions tab. Leave amount blank for a full refund.'
    ),
    h('div', { className: 'wallet-input-label', style: { marginTop: 14 } }, 'Deposit transaction ID'),
    h('input', { className: 'wallet-amount-input', value: txId, onChange: e => setTxId(e.target.value), placeholder: 'e.g. 42' }),
    h('div', { className: 'wallet-input-label' }, 'Refund amount (blank = full)'),
    h('input', { className: 'wallet-amount-input', value: amount, onChange: e => setAmt(e.target.value), placeholder: 'Leave blank for full refund' }),
    h('button', { className: 'btn btn-accent wallet-submit', disabled: busy || !txId, onClick: run }, busy ? 'Processing…' : 'Process Refund'),
    result && h('div', {
      style: { marginTop: 14, padding: 14, borderRadius: 8,
        background: result.error || result.code ? 'var(--red-dim)' : 'var(--green-dim)',
        border: '1px solid ' + (result.error || result.code ? 'var(--red)' : 'var(--green)'),
        fontSize: 12 } },
      (result.error || result.code)
        ? h('span', { style: { color: 'var(--red)' } }, result.message || result.error)
        : h('span', { style: { color: 'var(--green)' } }, `Refund complete. Stripe refund: ${result.stripeRefund || 'dev'} · New balance: $${result.newBalance}`)
    )
  );
}

// ── CSR PANEL ───────────────────────────────────────────────────
export function CsrModal({ onClose, me }) {
  const [tab, setTab] = useState('dashboard');
  const TABS = [
    { id: 'dashboard', label: '📊 Queue' },
    { id: 'lookup',    label: '🔎 User Lookup' },
    { id: 'tickets',   label: '🎧 Tickets' },
  ];
  return h(InfoModal, { title: '🎧 Customer Service', onClose },
    h('div', { className: 'staff-banner csr' },
      h('strong', null, 'CSR MODE'),
      ' — limited-power panel. You can answer tickets, look up users, and issue small goodwill credits. Anything bigger escalates to an admin.'
    ),
    h('div', { className: 'profile-tabs' },
      TABS.map(t => h('button', {
        key: t.id,
        className: `profile-tab ${tab === t.id ? 'active' : ''}`,
        onClick: () => setTab(t.id)
      }, t.label))
    ),
    tab === 'dashboard' && h(CsrDashboardTab, null),
    tab === 'lookup'    && h(CsrLookupTab, null),
    tab === 'tickets'   && h(CsrTicketsTab, null),
  );
}

function CsrDashboardTab() {
  const [stats, setStats] = useState(null);
  useEffect(() => { csrStats().then(setStats); }, []);
  if (!stats) return h('div', { className: 'spinner' });
  const waitingHours = stats.oldestWaitingAgeMs ? Math.floor(stats.oldestWaitingAgeMs / 3_600_000) : 0;
  const Stat = (label, val, cls) =>
    h('div', { className: 'admin-stat' },
      h('div', { className: 'admin-stat-label' }, label),
      h('div', { className: `admin-stat-val ${cls || ''}` }, val)
    );
  return h('div', { className: 'profile-panel' },
    h('div', { className: 'admin-stats-grid' },
      Stat('Open Tickets',         stats.openTickets || 0),
      Stat('Waiting on Staff',     stats.waitingStaff || 0, stats.waitingStaff > 0 ? 'yellow' : ''),
      Stat('Waiting on User',      stats.waitingUser || 0),
      Stat('Oldest wait',          waitingHours > 0 ? waitingHours + 'h' : '—',
                                   waitingHours > 24 ? 'red' : waitingHours > 6 ? 'yellow' : 'green'),
      Stat('Goodwill credit cap',  fmt(stats.creditCap || 0), 'accent')
    )
  );
}

function CsrLookupTab() {
  const [q, setQ] = useState('');
  const [data, setData] = useState(null);
  const [busy, setBusy] = useState(false);
  const search = async () => {
    if (!q.trim()) return;
    setBusy(true);
    try { setData(await csrLookup(q)); } finally { setBusy(false); }
  };
  const giveCredit = async (u) => {
    const amt = prompt(`Goodwill credit for ${u.displayName || u.steamId64} (max per CSR adjustment applies):`, '5.00');
    if (!amt) return;
    const note = prompt('Reason / note (required for audit):', '');
    if (!note || !note.trim()) return;
    const res = await csrGoodwill(u.id, parseFloat(amt), note);
    if (res.code || res.error) { alert(res.message || res.error); return; }
    alert(`Credited. New balance: $${res.newBalance}`);
    search();
  };
  return h('div', { className: 'profile-panel' },
    h('div', { style: { display: 'flex', gap: 10, marginBottom: 14 } },
      h('input', { className: 'price-input', style: { flex: 1 }, placeholder: 'Steam ID / display name / user #id', value: q, onChange: e => setQ(e.target.value), onKeyDown: e => { if (e.key === 'Enter') search(); } }),
      h('button', { className: 'btn btn-accent', disabled: busy, onClick: search }, busy ? '…' : 'Search')
    ),
    data && (data.matches.length === 0
      ? h('div', { className: 'empty-inline' }, h('div', { className: 'empty-icon' }, '🔎'),
          h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'No matches.'))
      : data.matches.map(u => h('div', { key: u.id, className: 'csr-user-card' },
          h('div', { style: { display: 'flex', alignItems: 'center', gap: 12, marginBottom: 10 } },
            u.avatarUrl
              ? h('img', { src: u.avatarUrl, alt: u.displayName, style: { width: 44, height: 44, borderRadius: 8 } })
              : h('div', { className: 'db-thumb', style: { width: 44, height: 44 } }, (u.displayName || 'U').substring(0,2).toUpperCase()),
            h('div', { style: { flex: 1 } },
              h('div', { style: { fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' } }, u.displayName || 'Player'),
              h('div', { style: { fontSize: 11, color: 'var(--text-muted)', fontFamily: 'JetBrains Mono, monospace' } }, u.steamId64 + ' · ' + u.role + (u.banned ? ' · 🚫 BANNED' : ''))
            ),
            h('div', { style: { textAlign: 'right' } },
              h('div', { style: { fontSize: 10, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: 0.5 } }, 'Balance'),
              h('div', { style: { fontSize: 16, fontWeight: 800, color: 'var(--accent)', fontFamily: 'JetBrains Mono, monospace' } }, fmt(u.balance || 0))
            ),
            h('button', { className: 'btn btn-accent', style: { marginLeft: 10, padding: '8px 14px' }, onClick: () => giveCredit(u) }, '+ Goodwill')
          ),
          u.banned && h('div', { style: { fontSize: 11, padding: '8px 10px', background: 'var(--red-dim)', border: '1px solid var(--red)', borderRadius: 6, color: 'var(--red)', marginBottom: 8 } },
            'Ban reason: ', u.banReason || '(none)'),
          h('div', { style: { fontSize: 10, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: 0.5, fontWeight: 700, marginBottom: 4 } }, 'Recent activity'),
          u.recentTx.length === 0
            ? h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } }, 'No transactions.')
            : h('div', null, u.recentTx.slice(0, 6).map(t => h('div', { key: t.id, className: 'csr-tx-row' },
                h('span', { style: { fontSize: 10, fontWeight: 700, color: 'var(--text-muted)', width: 110 } }, t.type),
                h('span', { style: { flex: 1, fontSize: 11, color: 'var(--text-secondary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' } }, t.description || '—'),
                h('span', { className: 'db-mono', style: { fontSize: 11, fontWeight: 700 } }, fmt(t.amount))
              )))
        )))
  );
}

function CsrTicketsTab() {
  // Reuse the exact same ticket thread UX as AdminTicketsTab but point at
  // the CSR endpoints. Keeping two small copies instead of DRY-ing is
  // deliberate — the surfaces are intentionally separable for rate-limiting
  // and can diverge later (e.g. CSRs might get a canned-response dropdown
  // admins don't have).
  const [list, setList]     = useState(null);
  const [viewing, setView]  = useState(null);
  const [reply, setReply]   = useState('');
  const [filter, setFilter] = useState('WAITING_STAFF');
  const [busy, setBusy]     = useState(false);

  const load = useCallback(async () => { setList(await csrTickets(filter)); }, [filter]);
  useEffect(() => { load(); }, [load]);
  const open = async (id) => setView(await csrTicket(id));
  const sendReply = async () => {
    if (!reply.trim() || !viewing?.ticket) return;
    setBusy(true);
    try {
      const res = await csrTicketReply(viewing.ticket.id, reply);
      if (res && res.error) { alert(res.error); return; }
      setReply('');
      setView(await csrTicket(viewing.ticket.id));
      load();
    } finally { setBusy(false); }
  };
  const closeTicket = async () => {
    if (!viewing?.ticket) return;
    const res = await csrCloseTicket(viewing.ticket.id);
    if (res && res.error) { alert(res.error); return; }
    setView(await csrTicket(viewing.ticket.id));
    load();
  };

  if (viewing) {
    return h('div', { className: 'profile-panel' },
      h('div', { style: { display: 'flex', gap: 8, alignItems: 'center', marginBottom: 14 } },
        h('button', { className: 'btn btn-ghost', onClick: () => setView(null) }, '← Tickets'),
        h('div', { style: { flex: 1 } },
          h('div', { style: { fontSize: 14, fontWeight: 700 } }, '#' + viewing.ticket.id + ' · ' + viewing.ticket.subject),
          h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } }, viewing.ticket.category + ' · ' + viewing.ticket.status + ' · ' + (viewing.ticket.username || '#' + viewing.ticket.userId))
        ),
        viewing.ticket.status !== 'RESOLVED' && h('button', { className: 'btn btn-ghost', onClick: closeTicket, style: { border: '1px solid var(--border)' } }, 'Close')
      ),
      h('div', { className: 'support-thread' },
        viewing.messages.map(m => h('div', { key: m.id, className: `support-msg ${m.author === 'STAFF' ? 'staff' : 'user'}` },
          h('div', { className: 'support-msg-head' }, m.authorName, ' · ', timeAgo(m.createdAt)),
          h('div', { className: 'support-msg-body' }, m.body)
        ))
      ),
      viewing.ticket.status !== 'RESOLVED' && h('div', { style: { display: 'flex', gap: 8, marginTop: 14 } },
        h('input', { className: 'chat-input', style: { flex: 1 }, placeholder: 'Reply as CSR…', value: reply, onChange: e => setReply(e.target.value), onKeyDown: e => { if (e.key === 'Enter') sendReply(); } }),
        h('button', { className: 'btn btn-accent', disabled: busy || !reply.trim(), onClick: sendReply }, 'Send')
      )
    );
  }

  return h('div', { className: 'profile-panel' },
    h('div', { style: { display: 'flex', gap: 10, marginBottom: 14 } },
      [['WAITING_STAFF','Waiting on us'],['WAITING_USER','Waiting on user'],['','All']].map(([v, l]) =>
        h('button', { key: v || 'all', className: `offer-tab ${filter === v ? 'active' : ''}`, onClick: () => setFilter(v) }, l)
      )
    ),
    list === null
      ? h('div', { className: 'spinner' })
      : list.length === 0
        ? h('div', { className: 'empty-inline' }, h('div', { className: 'empty-icon' }, '🎧'),
            h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'Queue is clear.'))
        : h('table', { className: 'db-table' },
            h('thead', null, h('tr', null,
              h('th', null, 'ID'), h('th', null, 'Subject'), h('th', null, 'User'),
              h('th', null, 'Status'), h('th', { className: 'right' }, 'Updated'))),
            h('tbody', null, list.map(t => h('tr', { key: t.id, className: 'db-row', onClick: () => open(t.id) },
              h('td', { className: 'db-rank' }, '#' + t.id),
              h('td', null, t.subject),
              h('td', { className: 'db-mono', style: { fontSize: 11 } }, t.username || '#' + t.userId),
              h('td', { style: { fontSize: 10, fontWeight: 700 } }, t.status),
              h('td', { className: 'right', style: { fontSize: 11, color: 'var(--text-muted)' } }, timeAgo(t.updatedAt))
            )))
          )
  );
}
