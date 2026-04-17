// Admin + CSR panels. Kept in their own file so the ordinary-user modal
// module doesn't pull in staff code paths. Both panels are tabbed, use
// InfoModal as the shell, and call into api.js for I/O.
import { h, useState, useEffect, useCallback, fmt, timeAgo } from './utils.js';
import { InfoModal } from './info-modal.js';
import {
  adminStats, adminWithdrawals, adminApproveWithdrawal, adminRejectWithdrawal,
  adminUsers, adminBanUser, adminUnbanUser, adminGrant, adminRevoke,
  adminGrantCsr, adminRevokeCsr, adminReset2fa, adminReadNotes, adminWriteNotes,
  adminCreditWallet, adminRemoveListing, adminReportedListings, adminDismissReports, adminTickets, adminTicket,
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
    { id: 'catalogue',   label: '🗂 Catalogue' },
    { id: 'simulator',   label: '🧪 Simulator' },
    { id: 'fraud',       label: '🚨 Fraud' },
    { id: 'reported',    label: '🚩 Reports' },
    { id: 'announce',    label: '📢 Announce' },
    { id: 'health',      label: '❤ Health' },
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
    tab === 'dashboard'   && h(AdminDashboardTab, { onNavTab: setTab }),
    tab === 'withdrawals' && h(AdminWithdrawalsTab, null),
    tab === 'trades'      && h(AdminTradesTab, null),
    tab === 'users'       && h(AdminUsersTab, { me }),
    tab === 'tickets'     && h(AdminTicketsTab, null),
    tab === 'refunds'     && h(AdminRefundsTab, null),
    tab === 'catalogue'   && h(AdminCatalogueTab, null),
    tab === 'simulator'   && h(AdminSimulatorTab, null),
    tab === 'fraud'       && h(AdminFraudTab, null),
    tab === 'reported'    && h(AdminReportedTab, null),
    tab === 'announce'    && h(AdminAnnouncementsTab, null),
    tab === 'health'      && h(AdminHealthTab, null),
    tab === 'audit'       && h(AdminAuditTab, null),
  );
}

// System-health panel — JVM memory, DB pool, threads, uptime. Polls every
// 5s while the tab is active so the numbers feel live. Admin-gated
// server-side; this tab is only mounted for ADMIN users anyway.
function AdminHealthTab() {
  const [data, setData] = useState(null);
  const [err, setErr]   = useState('');
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const r = await fetch('/api/admin/health', { credentials: 'same-origin' });
        if (!r.ok) { if (alive) setErr(`HTTP ${r.status}`); return; }
        const j = await r.json();
        if (alive) { setData(j); setErr(''); }
      } catch (e) { if (alive) setErr(String(e)); }
    };
    load();
    const id = setInterval(load, 5_000);
    return () => { alive = false; clearInterval(id); };
  }, []);
  if (err) return h('div', { className: 'admin-tab-content' }, h('div', { className: 'wallet-error' }, err));
  if (!data) return h('div', { className: 'admin-tab-content' }, h('div', { className: 'spinner' }));

  const uptimeH = Math.floor((data.uptimeMs || 0) / 3_600_000);
  const uptimeM = Math.floor((((data.uptimeMs || 0) % 3_600_000) / 60_000));
  const heapPct = data.memory?.heapMaxMb
    ? Math.round((data.memory.heapUsedMb / data.memory.heapMaxMb) * 100)
    : 0;
  const poolPct = data.pool?.max
    ? Math.round((data.pool.active / data.pool.max) * 100)
    : 0;

  const card = (label, value, hint) => h('div', { className: 'health-card' },
    h('div', { className: 'health-card-label' }, label),
    h('div', { className: 'health-card-value' }, value),
    hint != null && h('div', { className: 'health-card-hint' }, hint)
  );

  return h('div', { className: 'admin-tab-content' },
    h('div', { className: 'health-grid' },
      card('Uptime', `${uptimeH}h ${uptimeM}m`,
           `Started ${new Date(data.startedAt).toLocaleString()}`),
      card('Heap', `${data.memory?.heapUsedMb || 0} / ${data.memory?.heapMaxMb || 0} MB`,
           `${heapPct}% of max`),
      card('Threads', `${data.threads?.live || 0} live`,
           `peak ${data.threads?.peak || 0} · daemon ${data.threads?.daemon || 0}`),
      data.pool ? card('DB pool',
           `${data.pool.active} active / ${data.pool.total} total`,
           `${data.pool.idle} idle · ${data.pool.waiting} waiting · ${poolPct}% of ${data.pool.max} max`)
        : card('DB pool', '—', 'Not reporting'),
      card('System load', (data.systemLoad >= 0 ? data.systemLoad.toFixed(2) : 'n/a'),
           `${data.memory?.processorCount || 0} cores`),
      card('Schema version', `V${data.db?.schemaVersion || '—'}`,
           `${data.jvm?.name || 'JVM'} ${data.jvm?.version || ''}`.trim())
    )
  );
}

// Announcement management — read the current banner state, post a new
// INFO/WARN/CRITICAL banner, optionally schedule an auto-expiry. Admins
// can deactivate any row from the history list. Minimum 3 chars (matched
// by the server-side validator in AnnouncementService.create).
// User-reported listings queue. Sorted highest-report-count-first. Each row
// shows the top reasons + recent notes inline so the admin decides without
// a drill-down for most calls.
function AdminReportedTab() {
  const [rows, setRows] = useState(null);
  const [busy, setBusy] = useState(false);
  const [expanded, setExpanded] = useState({});  // listingId → bool
  const load = useCallback(async () => { setRows(null); setRows(await adminReportedListings()); }, []);
  useEffect(() => { load(); }, [load]);
  const remove = async (r) => {
    const reason = prompt(`Force-cancel listing #${r.id} (${r.itemName})?\n\nSeller will be notified. Enter admin-visible reason:`,
      r.topReasons?.[0] || 'Policy violation');
    if (reason == null) return;
    setBusy(true);
    try {
      const res = await adminRemoveListing(r.id, reason);
      if (res.code || res.error) { alert(res.message || res.error); return; }
      await load();
    } finally { setBusy(false); }
  };
  const dismiss = async (r) => {
    const note = prompt(`Dismiss ${r.reportCount} report${r.reportCount === 1 ? '' : 's'} on listing #${r.id} (${r.itemName})?\n\nReporters will be notified that admin reviewed and found no issue. Optional admin-only note:`,
      'No policy violation');
    if (note == null) return;
    setBusy(true);
    try {
      const res = await adminDismissReports(r.id, note);
      if (res.code || res.error) { alert(res.message || res.error); return; }
      await load();
    } finally { setBusy(false); }
  };
  if (rows === null) return h('div', { className: 'spinner' });
  if (rows.length === 0) {
    return h('div', { className: 'profile-panel' },
      h('div', { className: 'empty-inline' },
        h('div', { className: 'empty-icon' }, '🚩'),
        h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'No user-reported listings. Good signal — the marketplace is clean right now.'))
    );
  }
  return h('div', { className: 'profile-panel' },
    h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 12, lineHeight: 1.5 } },
      `${rows.length} listing${rows.length === 1 ? '' : 's'} with active user reports. Sorted highest-report-count-first. Clicking a row expands the recent report notes.`),
    h('table', { className: 'db-table' },
      h('thead', null, h('tr', null,
        h('th', null, 'Listing'),
        h('th', null, 'Seller'),
        h('th', { className: 'right' }, 'Price'),
        h('th', { className: 'right' }, 'Reports'),
        h('th', null, 'Top reasons'),
        h('th', { className: 'right' }, 'Last'),
        h('th', { className: 'right' }, 'Actions')
      )),
      h('tbody', null, rows.map(r => [
        h('tr', { key: r.id, className: 'db-row',
          onClick: () => setExpanded(s => ({ ...s, [r.id]: !s[r.id] })),
          style: { cursor: 'pointer' }
        },
          h('td', null,
            h('div', { className: 'db-name' }, r.itemName || '—'),
            h('div', { className: 'db-sub' }, '#' + r.id)
          ),
          h('td', { className: 'db-mono', style: { fontSize: 11 } }, r.sellerName || '#' + (r.sellerUserId || '?')),
          h('td', { className: 'right db-mono accent' }, fmt(r.price)),
          h('td', { className: 'right' },
            h('span', {
              style: {
                padding: '3px 10px', borderRadius: 12, fontSize: 12, fontWeight: 800,
                background: r.reportCount >= 3 ? 'rgba(248,113,113,0.15)' : 'rgba(251,191,36,0.15)',
                color:      r.reportCount >= 3 ? 'var(--red)'               : '#fbbf24'
              }
            }, '🚩 ' + r.reportCount + (r.distinctReporters > 1 ? ` · ${r.distinctReporters}👤` : ''))
          ),
          h('td', { style: { fontSize: 11, color: 'var(--text-secondary)' } },
            (r.topReasons || []).slice(0, 2).join(' · ')
          ),
          h('td', { className: 'right', style: { fontSize: 11, color: 'var(--text-muted)' } },
            r.lastReportedAt ? timeAgo(r.lastReportedAt) : '—'
          ),
          h('td', { className: 'right' },
            h('div', { style: { display: 'flex', gap: 4, justifyContent: 'flex-end' }, onClick: e => e.stopPropagation() },
              h('a', {
                className: 'btn btn-ghost',
                style: { padding: '5px 10px', fontSize: 11, border: '1px solid var(--border)' },
                href: `/item/${r.itemId}`, target: '_blank', rel: 'noopener noreferrer'
              }, 'View ↗'),
              h('button', {
                className: 'btn btn-ghost',
                style: { padding: '5px 10px', fontSize: 11, border: '1px solid var(--border)' },
                disabled: busy, onClick: () => dismiss(r), title: 'Mark reviewed — keep the listing, clear the reports'
              }, 'Dismiss'),
              h('button', {
                className: 'btn btn-ghost',
                style: { padding: '5px 10px', fontSize: 11, border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)' },
                disabled: busy, onClick: () => remove(r)
              }, 'Remove')
            )
          )
        ),
        expanded[r.id] && (r.recentNotes || []).length > 0 && h('tr', { key: r.id + '-notes' },
          h('td', { colSpan: 7, style: { padding: 10, background: 'var(--bg-elevated)' } },
            h('div', { style: { fontSize: 11, fontWeight: 700, color: 'var(--text-muted)', marginBottom: 6, textTransform: 'uppercase', letterSpacing: 0.5 } }, 'Recent reports'),
            (r.recentNotes || []).map((n, i) => h('div', { key: i, style: { fontSize: 12, padding: '6px 0', borderBottom: '1px solid var(--border)' } },
              h('strong', { style: { color: 'var(--text-primary)' } }, n.reason),
              n.note && h('span', { style: { color: 'var(--text-secondary)', marginLeft: 8 } }, '— ' + n.note),
              h('span', { style: { color: 'var(--text-muted)', marginLeft: 8, fontSize: 11 } }, '· ' + timeAgo(n.at))
            ))
          )
        )
      ]).flat())
    )
  );
}

function AdminAnnouncementsTab() {
  const [rows, setRows]       = useState([]);
  const [message, setMessage] = useState('');
  const [severity, setSeverity] = useState('INFO');
  const [hours, setHours]     = useState('');
  const [busy, setBusy]       = useState(false);
  const [err, setErr]         = useState('');
  const load = useCallback(async () => {
    try {
      const r = await fetch('/api/admin/announcements', { credentials: 'same-origin' });
      if (r.ok) setRows(await r.json());
    } catch (_) {}
  }, []);
  useEffect(() => { load(); }, [load]);
  const create = async () => {
    setErr(''); setBusy(true);
    try {
      const csrf = (document.cookie.match(/sbox_csrf=([^;]+)/) || [])[1];
      const body = { message, severity };
      if (hours) body.expiresAt = Date.now() + (parseFloat(hours) || 0) * 3600_000;
      const r = await fetch('/api/admin/announcements', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json',
                   ...(csrf ? { 'X-CSRF-Token': decodeURIComponent(csrf) } : {}) },
        body: JSON.stringify(body)
      });
      if (!r.ok) {
        const j = await r.json().catch(() => ({}));
        setErr(j.message || `HTTP ${r.status}`); return;
      }
      setMessage(''); setHours('');
      await load();
    } finally { setBusy(false); }
  };
  const deactivate = async (id) => {
    const csrf = (document.cookie.match(/sbox_csrf=([^;]+)/) || [])[1];
    await fetch(`/api/admin/announcements/${id}`, {
      method: 'DELETE',
      credentials: 'same-origin',
      headers: csrf ? { 'X-CSRF-Token': decodeURIComponent(csrf) } : {}
    });
    await load();
  };
  const live = rows.filter(r => r.active && (!r.expiresAt || r.expiresAt > Date.now()));
  return h('div', { className: 'admin-tab-content' },
    h('div', { style: { padding: 16, background: 'var(--bg-elevated)', border: '1px solid var(--border)', borderRadius: 10, marginBottom: 18 } },
      h('div', { style: { fontSize: 12, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--text-muted)', marginBottom: 10 } }, 'Post sitewide banner'),
      h('textarea', {
        value: message,
        onChange: e => setMessage(e.target.value),
        maxLength: 500,
        placeholder: 'Message (3–500 chars). HTML tags are stripped server-side.',
        style: { width: '100%', minHeight: 72, padding: 10, background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 6, color: 'var(--text-primary)', fontFamily: 'inherit', fontSize: 13, resize: 'vertical' }
      }),
      h('div', { style: { display: 'flex', gap: 8, marginTop: 10, flexWrap: 'wrap' } },
        h('select', {
          value: severity,
          onChange: e => setSeverity(e.target.value),
          style: { padding: '6px 10px', background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 6, color: 'var(--text-primary)' }
        },
          h('option', { value: 'INFO' }, 'INFO — blue'),
          h('option', { value: 'WARN' }, 'WARN — amber'),
          h('option', { value: 'CRITICAL' }, 'CRITICAL — red')
        ),
        h('input', {
          type: 'number',
          step: '0.5',
          min: '0',
          placeholder: 'Auto-expire in Nh (blank = manual)',
          value: hours,
          onChange: e => setHours(e.target.value),
          style: { padding: '6px 10px', background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 6, color: 'var(--text-primary)', width: 180 }
        }),
        h('button', {
          className: 'btn btn-accent',
          disabled: busy || !message.trim(),
          onClick: create
        }, busy ? 'Posting…' : 'Post banner')
      ),
      err && h('div', { className: 'wallet-error' }, err)
    ),
    live.length > 0 && h('div', { style: { padding: 12, background: 'var(--bg-card)', border: '1px solid var(--accent-border)', borderRadius: 8, marginBottom: 16 } },
      h('div', { style: { fontSize: 11, color: 'var(--accent)', fontWeight: 800, textTransform: 'uppercase', letterSpacing: '0.04em', marginBottom: 6 } }, 'LIVE NOW'),
      live.map(r => h('div', { key: r.id, style: { display: 'flex', gap: 12, padding: 6, alignItems: 'center' } },
        h(SeverityChip, { severity: r.severity }),
        h('span', { style: { flex: 1 } }, r.message),
        r.expiresAt && h('span', { style: { fontSize: 10, color: 'var(--text-muted)' } }, 'ends ' + timeAgo(r.expiresAt)),
        h('button', { className: 'btn btn-ghost', style: { padding: '4px 10px', fontSize: 11, border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)' }, onClick: () => deactivate(r.id) }, 'Stop')
      ))
    ),
    h('div', { style: { fontSize: 11, color: 'var(--text-muted)', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.04em', margin: '10px 0 6px' } }, 'History'),
    rows.length === 0
      ? h('div', { style: { fontSize: 13, color: 'var(--text-muted)', padding: 20, textAlign: 'center' } }, 'No announcements yet.')
      : rows.map(r => h('div', {
          key: r.id,
          style: {
            display: 'flex', gap: 10, padding: 10, alignItems: 'center',
            borderBottom: '1px solid var(--border)',
            opacity: r.active && (!r.expiresAt || r.expiresAt > Date.now()) ? 1 : 0.55
          }
        },
          h(SeverityChip, { severity: r.severity }),
          h('span', { style: { flex: 1, fontSize: 13 } }, r.message),
          h('span', { style: { fontSize: 11, color: 'var(--text-muted)' } }, timeAgo(r.createdAt))
        ))
  );
}

function SeverityChip({ severity }) {
  const s = (severity || 'INFO').toUpperCase();
  const cfg = {
    INFO:    { bg: 'rgba(96,165,250,0.15)', fg: '#60a5fa', label: 'INFO' },
    WARNING: { bg: 'rgba(251,191,36,0.15)', fg: '#fbbf24', label: 'WARN' },
    CRITICAL:{ bg: 'rgba(248,113,113,0.15)', fg: '#f87171', label: 'CRIT' }
  }[s] || { bg: 'var(--bg-elevated)', fg: 'var(--text-muted)', label: s };
  return h('span', {
    style: {
      fontSize: 10, fontWeight: 800, padding: '2px 8px', borderRadius: 4,
      background: cfg.bg, color: cfg.fg, letterSpacing: 0.5,
      fontFamily: 'JetBrains Mono, monospace', minWidth: 40, textAlign: 'center'
    }
  }, cfg.label);
}

// Fraud-signals triage — read-only rollup of the last 24h of audit rows.
// Groups suspicious patterns by severity so an admin can scan HIGH rows
// first and dismiss LOW noise. No write actions; the admin still makes
// ban/unban decisions manually from the Users tab.
function AdminFraudTab() {
  const [rows, setRows]   = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  // "Reviewed" signal ids live in localStorage on the admin's browser —
  // the FraudAnalysis surface is read-only aggregate over the audit log,
  // so there's no server-side row to mark. Gives individual admins a
  // way to dismiss signals they've already triaged without polluting
  // the shared view for everyone else.
  const [reviewed, setReviewed] = useState(() => {
    try { return new Set(JSON.parse(localStorage.getItem('sb_fraud_reviewed') || '[]')); }
    catch { return new Set(); }
  });
  const [showReviewed, setShowReviewed] = useState(false);
  // Auto-refresh toggle — off by default so admins who open the tab to
  // triage a specific signal don't have the list re-shuffle under them.
  // When on, the rollup re-fetches every 60 seconds so long-running ops
  // sessions see new HIGH-severity signals without manual refresh.
  const [autoRefresh, setAutoRefresh] = useState(false);
  const rowKey = (r) => `${r.type}|${r.userId || ''}|${r.ip || ''}|${r.createdAt || ''}`;
  const markReviewed = (r) => {
    const key = rowKey(r);
    const next = new Set(reviewed);
    if (next.has(key)) next.delete(key); else next.add(key);
    setReviewed(next);
    try { localStorage.setItem('sb_fraud_reviewed', JSON.stringify([...next])); } catch (_) {}
  };
  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const r = await adminFraudSignals();
      setRows(Array.isArray(r) ? r : []);
    } catch (e) { setError(e?.message || 'Failed to load'); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { load(); }, [load]);
  // Live-refresh loop — only runs when autoRefresh is on. 60 seconds
  // balances "see new signals quickly" against "don't hammer the
  // audit-log rollup query" (it scans the last 24h window).
  useEffect(() => {
    if (!autoRefresh) return;
    const id = setInterval(() => {
      if (document.visibilityState === 'visible') load();
    }, 60_000);
    return () => clearInterval(id);
  }, [autoRefresh, load]);

  const sevClass = (s) => s === 'HIGH' ? 'sev-high' : s === 'MED' ? 'sev-med' : 'sev-low';
  const sevIcon  = (s) => s === 'HIGH' ? '🔴' : s === 'MED' ? '🟡' : '⚪';
  const visible = showReviewed ? rows : rows.filter(r => !reviewed.has(rowKey(r)));
  const hiddenCount = rows.length - visible.length;

  return h('div', { className: 'admin-tab-content' },
    h('div', { className: 'admin-card' },
      h('div', { className: 'admin-card-title' }, '🚨 Fraud Signals (last 24h)'),
      h('div', { className: 'admin-card-note' },
        "Rolled up from the audit log. These are patterns worth investigating — " +
        "not guaranteed fraud. Mark a row reviewed to hide it from your queue; " +
        "other admins keep seeing it until they review too."),
      h('div', { style: { display: 'flex', gap: 8, marginTop: 10, flexWrap: 'wrap', alignItems: 'center' } },
        h('button', {
          className: 'btn btn-ghost',
          style: { padding: '6px 14px' },
          onClick: load, disabled: loading
        }, loading ? 'Loading…' : 'Refresh'),
        h('label', {
          style: { display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 11, color: 'var(--text-muted)', cursor: 'pointer' },
          title: 'Auto-refresh the rollup every 60 seconds while this tab is visible'
        },
          h('input', {
            type: 'checkbox',
            checked: autoRefresh,
            onChange: e => setAutoRefresh(e.target.checked),
            style: { accentColor: 'var(--accent)' }
          }),
          autoRefresh ? '● Auto-refreshing' : 'Auto-refresh'
        ),
        hiddenCount > 0 && h('button', {
          className: `wallet-tx-filter-chip ${showReviewed ? 'active' : ''}`,
          onClick: () => setShowReviewed(v => !v)
        }, showReviewed ? 'Hide reviewed' : `Show ${hiddenCount} reviewed`)
      ),
      error && h('div', { className: 'admin-error' }, error),
      !loading && rows.length === 0 && h('div', { className: 'empty-inline' },
        '✨ No suspicious patterns detected in the last 24 hours.'),
      !loading && rows.length > 0 && visible.length === 0 && h('div', { className: 'empty-inline' },
        '✓ You\'ve reviewed every signal in the current window.'),
      visible.length > 0 && h('div', { className: 'fraud-list' },
        visible.map((r, i) => {
          const key = rowKey(r);
          const isReviewed = reviewed.has(key);
          return h('div', {
            key: key + ':' + i,
            className: `fraud-row ${sevClass(r.severity)}${isReviewed ? ' reviewed' : ''}`
          },
            h('div', { className: 'fraud-sev' }, sevIcon(r.severity), ' ', r.severity),
            h('div', { className: 'fraud-body' },
              h('div', { className: 'fraud-type' }, r.type),
              h('div', { className: 'fraud-summary' }, r.summary),
              r.ip && h('div', { className: 'fraud-meta' }, 'IP: ', r.ip),
              r.userId && h('div', { className: 'fraud-meta' }, 'User: ', r.userName || `#${r.userId}`),
            ),
            h('div', { className: 'fraud-time' },
              r.createdAt ? timeAgo(r.createdAt) : '',
              h('button', {
                className: 'btn btn-ghost',
                style: { marginTop: 6, padding: '4px 10px', fontSize: 10, border: '1px solid var(--border)' },
                onClick: () => markReviewed(r),
                title: isReviewed ? 'Move back to queue' : 'Mark reviewed (hides from your queue)'
              }, isReviewed ? '↺ Reopen' : '✓ Reviewed')
            )
          );
        })
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
  // Age sort toggle — defaults off (uses backend's newest-first), flips
  // to oldest-first so ops can attack the tail of the queue without
  // scrolling. Re-sorts client-side so we don't need a new endpoint.
  const [oldestFirst, setOldestFirst] = useState(false);

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

  // Effective rows — optionally reverse newest-first ordering. The
  // backend returns rows sorted by updatedAt DESC; flipping here gives
  // ops a "fix the oldest trade first" view.
  const display = rows ? (oldestFirst ? [...rows].sort((a, b) => (a.updatedAt || 0) - (b.updatedAt || 0)) : rows) : null;

  return h('div', { className: 'profile-panel' },
    h('div', { style: { display: 'flex', gap: 6, marginBottom: 14, flexWrap: 'wrap', alignItems: 'center' } },
      STATES.map(s => h('button', {
        key: s,
        className: `offer-tab ${filter === s ? 'active' : ''}`,
        onClick: () => setFilter(s)
      }, s.replace(/_/g, ' ').toLowerCase())),
      h('div', { style: { flex: 1 } }),
      h('button', {
        className: `wallet-tx-filter-chip ${oldestFirst ? 'active' : ''}`,
        onClick: () => setOldestFirst(v => !v),
        title: oldestFirst ? 'Currently oldest-first — click to reset' : 'Sort oldest-first (tail of queue)'
      }, oldestFirst ? '↑ Oldest first' : '↓ Newest first')
    ),
    display === null
      ? h('div', { className: 'spinner' })
      : display.length === 0
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
            h('tbody', null, display.map(r => h('tr', { key: r.id, className: 'db-row' },
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
  // Free-text filter applied to the already-fetched rows so the admin can
  // narrow down by summary contents without a server round-trip. Matches
  // against summary + actor/subject names. Backend-side filters (event,
  // actor id, subject id) still drive the fetch so we don't scan rows we
  // don't need.
  const [textSearch, setTextSearch] = useState('');
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
                  'ADMIN_GRANTED','ADMIN_REVOKED','CSR_GRANTED','CSR_REVOKED',
                  'CSR_CREDIT','ADMIN_CREDIT','API_KEY_MINTED','API_KEY_REVOKED'];
  // Apply the client-side free-text filter against whatever the backend
  // returned. Case-insensitive on summary + the pre-resolved display names.
  const displayRows = (() => {
    if (!rows) return null;
    const q = textSearch.trim().toLowerCase();
    if (q.length === 0) return rows;
    return rows.filter(r => {
      const summary = (r.summary || '').toLowerCase();
      const actor   = (r.actorName || '').toLowerCase();
      const subject = (r.subjectName || '').toLowerCase();
      return summary.includes(q) || actor.includes(q) || subject.includes(q);
    });
  })();

  return h('div', { className: 'profile-panel' },
    h('div', { style: { display: 'flex', gap: 10, marginBottom: 14, flexWrap: 'wrap' } },
      h('select', { className: 'sort-select', value: filter.event, onChange: e => setFilter(f => ({ ...f, event: e.target.value })) },
        EVENTS.map(ev => h('option', { key: ev || 'all', value: ev }, ev || 'All events'))
      ),
      h('input', { className: 'price-input', style: { width: 130 }, placeholder: 'Actor user #id', value: filter.actor, onChange: e => setFilter(f => ({ ...f, actor: e.target.value })) }),
      h('input', { className: 'price-input', style: { width: 130 }, placeholder: 'Subject user #id', value: filter.subject, onChange: e => setFilter(f => ({ ...f, subject: e.target.value })) }),
      h('input', { className: 'price-input', style: { flex: 1, minWidth: 140 }, placeholder: '🔎 Search summary / names…', value: textSearch, onChange: e => setTextSearch(e.target.value) }),
      h('button', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11 }, onClick: load }, 'Refresh'),
      // CSV export — honors the current filter selection so the download
      // matches what the admin is looking at.
      (() => {
        const qs = new URLSearchParams();
        if (filter.event)   qs.set('event', filter.event);
        if (filter.actor)   qs.set('actor', filter.actor);
        if (filter.subject) qs.set('subject', filter.subject);
        return h('a', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11 },
          href: '/api/admin/audit.csv' + (qs.toString() ? '?' + qs.toString() : ''),
          title: 'Export the current audit view as CSV'
        }, '⇣ CSV');
      })()
    ),
    displayRows === null
      ? h('div', { className: 'spinner' })
      : displayRows.length === 0
        ? h('div', { className: 'empty-inline' }, h('div', { className: 'empty-icon' }, '📜'),
            h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } },
              textSearch.trim() ? 'No audit entries match "' + textSearch.trim() + '".' : 'No audit entries match those filters.'))
        : h('table', { className: 'db-table' },
            h('thead', null, h('tr', null,
              h('th', null, 'When'),
              h('th', null, 'Event'),
              h('th', null, 'Actor'),
              h('th', null, 'Subject'),
              h('th', null, 'Summary'),
              h('th', null, 'IP')
            )),
            h('tbody', null, displayRows.slice(0, 200).map(r => h('tr', { key: r.id },
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

function AdminDashboardTab({ onNavTab }) {
  const [stats, setStats] = useState(null);
  // Recent activity — last 10 audit rows surfaced as a mini feed so the
  // admin landing page shows ops pulse at a glance. Refetched every 30s
  // while the tab is open; no new endpoint — reuses /api/admin/audit.
  const [recent, setRecent] = useState([]);
  useEffect(() => { adminStats().then(setStats); }, []);
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const rows = await adminAudit({});
        if (alive) setRecent((Array.isArray(rows) ? rows : []).slice(0, 10));
      } catch (_) {}
    };
    load();
    const id = setInterval(load, 30_000);
    return () => { alive = false; clearInterval(id); };
  }, []);
  if (!stats) return h('div', { className: 'spinner' });
  // Compute a % delta vs the prior 24h window. When the prior bucket
  // is 0 we render either "+new" (current has volume) or no delta
  // (both zero — nothing interesting to compare).
  const delta = (current, prior) => {
    const c = parseFloat(current) || 0;
    const p = parseFloat(prior) || 0;
    if (p === 0 && c === 0) return null;
    if (p === 0) return { pct: null, direction: 'up', label: 'new' };
    const pct = ((c - p) / p) * 100;
    return { pct, direction: pct >= 0 ? 'up' : 'down' };
  };
  const Stat = (label, val, cls, dlt, navTarget) =>
    h('div', {
      className: `admin-stat${navTarget ? ' clickable' : ''}`,
      onClick: navTarget && onNavTab ? () => onNavTab(navTarget) : null,
      role: navTarget ? 'button' : null,
      tabIndex: navTarget ? 0 : null,
      onKeyDown: navTarget && onNavTab ? (e) => { if (e.key === 'Enter') onNavTab(navTarget); } : null,
      title: navTarget ? `Click to open the ${navTarget} tab` : null
    },
      h('div', { className: 'admin-stat-label' }, label),
      h('div', { className: `admin-stat-val ${cls || ''}` }, val),
      dlt && h('div', {
        className: `admin-stat-delta ${dlt.direction === 'up' ? 'up' : 'down'}`,
        title: 'vs. the prior 24 h window'
      },
        dlt.direction === 'up' ? '▲ ' : '▼ ',
        dlt.pct == null ? dlt.label : `${dlt.pct >= 0 ? '+' : ''}${dlt.pct.toFixed(0)}%`,
        ' vs yesterday'
      )
    );
  const depositDelta = delta(stats.deposits24h, stats.depositsPrior24h);
  const salesDelta   = delta(stats.sales24h,    stats.salesPrior24h);
  return h('div', { className: 'profile-panel' },
    h('div', { className: 'admin-stats-grid' },
      Stat('Registered Users',     Number(stats.users || 0).toLocaleString(),
           null, stats.newUsers24h > 0 ? { pct: null, direction: 'up', label: `+${stats.newUsers24h} in 24h` } : null,
           'users'),
      Stat('Catalogue Items',      Number(stats.items || 0).toLocaleString(), null, null, 'catalogue'),
      Stat('Active Listings',      Number(stats.activeListings || 0).toLocaleString()),
      Stat('Total Escrow',         fmt(stats.totalEscrow || 0), 'accent'),
      Stat('Deposits 24h',         fmt(stats.deposits24h || 0), 'green', depositDelta),
      Stat('Sales 24h',            fmt(stats.sales24h || 0), 'green', salesDelta, 'trades'),
      Stat('Platform fees 24h',    fmt(stats.fees24h || 0), 'accent'),
      Stat('Pending Withdrawals',  `${stats.pendingWithdrawals || 0} · ${fmt(stats.pendingWithdrawalsAmount || 0)}`, 'yellow',
           null, 'withdrawals'),
      Stat('Open Tickets',         Number(stats.openTickets || 0), null, null, 'tickets'),
      Stat('Banned Users',         Number(stats.bannedUsers || 0), stats.bannedUsers > 0 ? 'red' : '', null, 'users')
    ),
    // Recent activity feed — compact list of the last 10 audit rows
    // with event type, actor, subject, and relative time. Clicking a
    // row is a no-op by design — this is a glance surface, not a
    // navigation target. Links to the Audit Log tab for drill-down.
    recent.length > 0 && h('div', { className: 'admin-activity', style: { marginTop: 20 } },
      h('div', { className: 'admin-activity-head' },
        h('span', { className: 'section-title-dot' }),
        'Recent activity',
        h('span', { className: 'admin-activity-hint' }, 'live · last 10 events')
      ),
      h('div', { className: 'admin-activity-list' },
        recent.map(r => h('div', { key: r.id, className: 'admin-activity-row' },
          h('span', { className: 'admin-activity-event' }, r.eventType),
          h('span', { className: 'admin-activity-summary' },
            (r.summary || '—'),
            r.actorUserId && h('span', { style: { color: 'var(--text-muted)', marginLeft: 6 } },
              `· actor #${r.actorUserId}`)
          ),
          h('span', { className: 'admin-activity-time' }, timeAgo(r.createdAt))
        ))
      )
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
              h('td', { style: { fontSize: 11, color: 'var(--text-muted)' } },
                timeAgo(r.createdAt),
                // Urgency badge — surface any PENDING withdrawal that's
                // been waiting more than 24 hours. Keeps finance team
                // focused on the old tail of the queue before SLA blows.
                filter === 'PENDING' && (Date.now() - (r.createdAt || 0)) > 24 * 3600_000 &&
                  h('span', { className: 'withdraw-urgent', title: 'Waiting more than 24 hours' }, '● SLA')
              ),
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

// Admin catalogue editor — lets staff override stale SCMM fields on an
// item without waiting for the next sync. Backed by `PUT /api/admin/items/{id}`.
// Free-text search uses the public /api/items?q= endpoint so we don't need
// a new list endpoint. Edit form applies partial updates (only the changed
// fields land in the PUT body), audits on every save.
function AdminCatalogueTab() {
  const [q, setQ] = useState('');
  const [results, setResults] = useState([]);
  const [editing, setEditing] = useState(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [draft, setDraft] = useState({});
  useEffect(() => {
    const qs = (q || '').trim();
    if (qs.length < 2) { setResults([]); return; }
    let alive = true;
    const t = setTimeout(async () => {
      try {
        const r = await fetch(`/api/items?q=${encodeURIComponent(qs)}`, { credentials: 'same-origin' });
        if (!r.ok) return;
        const j = await r.json();
        if (alive) setResults(Array.isArray(j) ? j.slice(0, 30) : []);
      } catch (_) {}
    }, 220);
    return () => { alive = false; clearTimeout(t); };
  }, [q]);
  const edit = (it) => {
    setEditing(it);
    setDraft({
      steamPrice:  it.steamPrice  != null ? String(it.steamPrice) : '',
      rarity:      it.rarity      || '',
      imageUrl:    it.imageUrl    || '',
      accentColor: it.accentColor || ''
    });
    setErr('');
  };
  const save = async () => {
    if (!editing) return;
    setBusy(true); setErr('');
    try {
      const csrf = (document.cookie.match(/sbox_csrf=([^;]+)/) || [])[1];
      const r = await fetch(`/api/admin/items/${editing.id}`, {
        method: 'PUT',
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          ...(csrf ? { 'X-CSRF-Token': decodeURIComponent(csrf) } : {})
        },
        body: JSON.stringify(draft)
      });
      if (!r.ok) {
        const j = await r.json().catch(() => ({}));
        setErr(j.message || `HTTP ${r.status}`);
        return;
      }
      const fresh = await r.json();
      setResults(rs => rs.map(x => x.id === fresh.id ? { ...x, ...fresh } : x));
      setEditing(null);
    } finally { setBusy(false); }
  };
  return h('div', { className: 'admin-tab-content' },
    h('div', { style: { display: 'flex', gap: 8, marginBottom: 12 } },
      h('input', {
        className: 'price-input',
        placeholder: 'Search items by name…',
        value: q,
        onChange: e => setQ(e.target.value),
        style: { flex: 1 }
      })
    ),
    q.trim().length < 2 && h('div', { style: { fontSize: 12, color: 'var(--text-muted)', padding: 16 } },
      'Type at least 2 letters to search the catalogue. Edit steamPrice / rarity / imageUrl / accentColor inline; updates apply without waiting for the next SCMM sync.'),
    q.trim().length >= 2 && results.length === 0 && h('div', { className: 'empty-inline' },
      h('div', { style: { fontSize: 13, color: 'var(--text-muted)' } }, 'No items match.')),
    results.length > 0 && h('table', { className: 'db-table', style: { marginTop: 8 } },
      h('thead', null, h('tr', null,
        h('th', null, 'ID'),
        h('th', null, 'Name'),
        h('th', null, 'Rarity'),
        h('th', { className: 'right' }, 'Floor'),
        h('th', { className: 'right' }, 'Steam'),
        h('th', { className: 'right' }, ''))),
      h('tbody', null, results.map(it => h('tr', { key: it.id, className: 'db-row' },
        h('td', { className: 'db-rank' }, '#' + it.id),
        h('td', null,
          h('div', { style: { display: 'flex', alignItems: 'center', gap: 10 } },
            it.imageUrl
              ? h('img', { src: it.imageUrl, alt: '', style: { width: 28, height: 28, borderRadius: 4, background: 'var(--bg-elevated)' } })
              : h('span', { style: { fontSize: 16 } }, it.iconEmoji || '📦'),
            h('span', null, it.name)
          )
        ),
        h('td', { style: { fontSize: 11, color: 'var(--text-muted)' } }, it.rarity || 'Standard'),
        h('td', { className: 'right db-mono accent' }, it.lowestPrice != null ? fmt(it.lowestPrice) : '—'),
        h('td', { className: 'right db-mono' }, it.steamPrice != null ? fmt(it.steamPrice) : '—'),
        h('td', { className: 'right' },
          h('button', {
            className: 'btn btn-ghost',
            style: { border: '1px solid var(--border)', padding: '5px 10px', fontSize: 11 },
            onClick: () => edit(it)
          }, '✎ Edit')
        )
      )))
    ),
    // Edit drawer — partial PUT, validates on the server.
    editing && h('div', { className: 'cart-confirm-backdrop', onClick: () => !busy && setEditing(null) },
      h('div', { className: 'cart-confirm-panel', style: { maxWidth: 520 }, onClick: e => e.stopPropagation() },
        h('div', { className: 'cart-confirm-title' }, '✎ Edit ' + editing.name),
        h('div', { className: 'cart-confirm-sub' },
          `ID #${editing.id} — partial edits apply immediately. Clear a field to restore the SCMM default on the next sync.`),
        h('div', { style: { marginTop: 14, display: 'grid', gap: 10 } },
          h('div', null,
            h('div', { className: 'settings-label' }, 'Steam price'),
            h('input', {
              className: 'wallet-amount-input',
              type: 'number', step: '0.01', min: '0',
              placeholder: '0.00 (blank = clear)',
              value: draft.steamPrice,
              onChange: e => setDraft(d => ({ ...d, steamPrice: e.target.value }))
            })
          ),
          h('div', null,
            h('div', { className: 'settings-label' }, 'Rarity'),
            h('select', {
              className: 'sort-select',
              value: draft.rarity,
              onChange: e => setDraft(d => ({ ...d, rarity: e.target.value }))
            },
              ['Standard','Off-Market','Limited','Unique','Rare','Exceedingly Rare'].map(r =>
                h('option', { key: r, value: r }, r))
            )
          ),
          h('div', null,
            h('div', { className: 'settings-label' }, 'Image URL (https)'),
            h('input', {
              className: 'wallet-amount-input',
              type: 'url',
              placeholder: 'https://…',
              value: draft.imageUrl,
              onChange: e => setDraft(d => ({ ...d, imageUrl: e.target.value }))
            })
          ),
          h('div', null,
            h('div', { className: 'settings-label' }, 'Accent colour (#hex)'),
            h('input', {
              className: 'wallet-amount-input',
              placeholder: '#1ea5ff',
              value: draft.accentColor,
              onChange: e => setDraft(d => ({ ...d, accentColor: e.target.value }))
            })
          )
        ),
        err && h('div', { className: 'wallet-error' }, err),
        h('div', { className: 'cart-confirm-actions' },
          h('button', {
            className: 'btn btn-ghost',
            style: { border: '1px solid var(--border)' },
            onClick: () => setEditing(null),
            disabled: busy
          }, 'Cancel'),
          h('button', {
            className: 'btn btn-accent',
            onClick: save,
            disabled: busy
          }, busy ? 'Saving…' : 'Save changes')
        )
      )
    )
  );
}

// Ban-reason drawer — picker + textarea. Selecting a template fills
// the textarea; admin can still edit freely before submitting. Submit
// short-circuits on empty reasons (prevents accidental no-context bans).
function BanReasonDrawer({ user, templates, busy, onCancel, onSubmit }) {
  const [reason, setReason] = useState('');
  const [picked, setPicked] = useState('');
  const choose = (t) => { setPicked(t.id); setReason(t.reason); };
  return h('div', {
    className: 'cart-confirm-backdrop',
    onClick: () => !busy && onCancel()
  },
    h('div', {
      className: 'cart-confirm-panel',
      style: { maxWidth: 560 },
      onClick: e => e.stopPropagation()
    },
      h('div', { className: 'cart-confirm-title' },
        'Ban ', user.displayName || user.steamId64),
      h('div', { className: 'cart-confirm-sub' },
        'The reason is recorded in the audit log and shown to the user in their banned state. Pick a template or type your own.'),
      h('div', { className: 'wallet-tx-filter-row', style: { marginTop: 14, marginBottom: 8 } },
        templates.map(t => h('button', {
          key: t.id,
          className: `wallet-tx-filter-chip ${picked === t.id ? 'active' : ''}`,
          onClick: () => choose(t)
        }, t.label))
      ),
      h('textarea', {
        value: reason,
        onChange: e => { setReason(e.target.value); setPicked(''); },
        maxLength: 500,
        placeholder: 'Ban reason (3–500 chars)',
        style: { width: '100%', minHeight: 90, padding: 10, background: 'var(--bg-elevated)', border: '1px solid var(--border)', borderRadius: 6, color: 'var(--text-primary)', fontFamily: 'inherit', fontSize: 13, resize: 'vertical' }
      }),
      h('div', { className: 'cart-confirm-actions' },
        h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)' },
          disabled: busy,
          onClick: onCancel
        }, 'Cancel'),
        h('button', {
          className: 'btn btn-accent',
          style: { background: 'var(--red)', color: '#fff' },
          disabled: busy || !reason.trim() || reason.trim().length < 3,
          onClick: () => onSubmit(reason)
        }, busy ? 'Banning…' : 'Ban user')
      )
    )
  );
}

function AdminUsersTab({ me }) {
  const [rows, setRows] = useState(null);
  const [search, setSearch] = useState('');
  // Role/status chip filter — applied client-side on the loaded rows so
  // admins can narrow to "show me every CSR" or "show me the banned
  // accounts" without a server round-trip. Backend still filters by
  // search text (display name / Steam ID).
  const [roleFilter, setRoleFilter] = useState('ALL');
  const [busy, setBusy] = useState(false);
  // User detail sub-modal: loaded lazily when an admin clicks a row.
  // Shows trades + reviews received + public profile fields. Read-only
  // panel — write actions stay on the row actions so they're audited.
  const [detailUser, setDetailUser]   = useState(null);
  const [detailData, setDetailData]   = useState(null);
  // Staff-only notes draft editor — separate from detailData because
  // it's user-editable + its own PUT. Persisted by the Save button.
  const [notesDraft, setNotesDraft]   = useState('');
  const [notesSaving, setNotesSaving] = useState(false);
  const load = useCallback(async () => { setRows(await adminUsers(search)); }, [search]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    if (!detailUser) { setDetailData(null); setNotesDraft(''); return; }
    let alive = true;
    (async () => {
      try {
        // Public stall + reviews + staff notes for a quick at-a-glance card.
        const [stall, reviews, notes] = await Promise.all([
          fetch(`/api/listings/stall/${detailUser.id}`, { credentials: 'same-origin' }).then(r => r.ok ? r.json() : null),
          fetch(`/api/reviews/user/${detailUser.id}`,   { credentials: 'same-origin' }).then(r => r.ok ? r.json() : []),
          adminReadNotes(detailUser.id).catch(() => null)
        ]);
        if (alive) {
          setDetailData({ stall, reviews: Array.isArray(reviews) ? reviews : [] });
          setNotesDraft(notes?.adminNotes || '');
        }
      } catch (_) {}
    })();
    return () => { alive = false; };
  }, [detailUser?.id]);

  // Ban-reason templates surface from the prompt dialog — admins can
  // pick a standard reason (fraud, chargeback, abuse, scam, ToS) to
  // keep ban reasons consistent across the team. Typing anything custom
  // still works; the picker just saves keystrokes.
  const [banTarget, setBanTarget] = useState(null);
  const BAN_TEMPLATES = [
    { id: 'fraud',      label: 'Fraud / stolen account', reason: 'Account flagged for fraudulent activity — linked to stolen wallet or Steam account.' },
    { id: 'chargeback', label: 'Chargeback abuse',       reason: 'Deposit reversed via credit card chargeback after receiving items.' },
    { id: 'scam',       label: 'Scam / misrepresented item', reason: 'Multiple buyers reported receiving items that did not match the listing description.' },
    { id: 'abuse',      label: 'Harassment / abuse',     reason: 'Abusive language toward other users or staff in support tickets.' },
    { id: 'tos',        label: 'ToS violation',          reason: 'Violation of sboxmarket Terms of Service.' }
  ];
  const doBan = async (u) => setBanTarget(u);
  const submitBan = async (reason) => {
    if (!banTarget || !reason || !reason.trim()) { setBanTarget(null); return; }
    setBusy(true);
    try {
      const res = await adminBanUser(banTarget.id, reason.trim());
      if (res.code || res.error) { alert(res.message || res.error); return; }
      setBanTarget(null);
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
  const doGrantCsr = async (u) => {
    if (!confirm(`Grant CSR role to ${u.displayName || u.steamId64}?\n\nThey'll see the 🎧 Customer Service panel and can handle tickets + issue small goodwill credits.`)) return;
    setBusy(true);
    try {
      const res = await adminGrantCsr(u.id);
      if (res.code || res.error) { alert(res.message || res.error); return; }
      await load();
    } finally { setBusy(false); }
  };
  const doRevokeCsr = async (u) => {
    if (!confirm(`Revoke CSR role from ${u.displayName || u.steamId64}?`)) return;
    setBusy(true);
    try {
      const res = await adminRevokeCsr(u.id);
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
      h('button', { className: 'btn btn-accent', onClick: load }, 'Search'),
      // CSV export — hands the admin whatever's currently in the view
      // (honors the `search` filter) via the server-rendered endpoint.
      h('a', {
        className: 'btn btn-ghost',
        style: { border: '1px solid var(--border)', padding: '8px 14px', fontSize: 11 },
        href: search ? `/api/admin/users.csv?search=${encodeURIComponent(search)}` : '/api/admin/users.csv',
        title: 'Export the current user list as a CSV'
      }, '⇣ CSV')
    ),
    // Role / status chip filter — applied below the search. Counts
    // derived from the already-fetched rows so the chip labels show
    // exactly how many match each filter.
    rows && rows.length > 0 && (() => {
      const count = {
        ALL:    rows.length,
        USER:   rows.filter(r => (r.role || 'USER') === 'USER' && !r.banned).length,
        CSR:    rows.filter(r => r.role === 'CSR').length,
        ADMIN:  rows.filter(r => r.role === 'ADMIN').length,
        BANNED: rows.filter(r => r.banned).length
      };
      return h('div', { style: { display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 12 } },
        [
          { id: 'ALL',    label: 'All' },
          { id: 'USER',   label: 'Users' },
          { id: 'CSR',    label: 'CSRs' },
          { id: 'ADMIN',  label: 'Admins' },
          { id: 'BANNED', label: 'Banned' }
        ].map(opt => h('button', {
          key: opt.id,
          className: `wallet-tx-filter-chip ${roleFilter === opt.id ? 'active' : ''}`,
          onClick: () => setRoleFilter(opt.id),
          disabled: count[opt.id] === 0 && opt.id !== 'ALL'
        }, `${opt.label} · ${count[opt.id]}`))
      );
    })(),
    (() => {
      const visibleRows = rows === null ? null : (roleFilter === 'ALL'
        ? rows
        : roleFilter === 'BANNED' ? rows.filter(r => r.banned)
        : rows.filter(r => (r.role || 'USER') === roleFilter && !r.banned));
      return visibleRows === null
      ? h('div', { className: 'spinner' })
      : visibleRows.length === 0
        ? h('div', { className: 'empty-inline' },
            h('div', { className: 'empty-icon' }, '👥'),
            h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } },
              rows.length === 0 ? 'No matching users.' : `No users match filter "${roleFilter.toLowerCase()}".`))
        : h('table', { className: 'db-table' },
            h('thead', null, h('tr', null,
              h('th', null, 'User'),
              h('th', null, 'Steam ID'),
              h('th', null, 'Role'),
              h('th', null, 'Status'),
              h('th', { className: 'right' }, 'Actions'))),
            h('tbody', null, visibleRows.map(u => h('tr', { key: u.id, className: 'db-row' },
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
                  h('button', {
                    className: 'btn btn-ghost',
                    style: { padding: '5px 10px', fontSize: 11, border: '1px solid var(--border)' },
                    onClick: () => setDetailUser(u),
                    title: 'View user detail'
                  }, '🔎'),
                  h('button', { className: 'btn btn-ghost', style: { padding: '5px 10px', fontSize: 11, border: '1px solid var(--border)' }, disabled: busy, onClick: () => doCredit(u) }, '$'),
                  u.role === 'USER'
                    ? h('button', { className: 'btn btn-ghost', style: { padding: '5px 10px', fontSize: 11, border: '1px solid rgba(96,165,250,0.4)', color: '#60a5fa' }, disabled: busy, onClick: () => doGrantCsr(u), title: 'Grant CSR role' }, '+CSR')
                    : u.role === 'CSR'
                      ? h('button', { className: 'btn btn-ghost', style: { padding: '5px 10px', fontSize: 11, border: '1px solid rgba(96,165,250,0.4)', color: '#60a5fa' }, disabled: busy, onClick: () => doRevokeCsr(u), title: 'Revoke CSR role' }, '−CSR')
                      : null,
                  u.role !== 'ADMIN'
                    ? h('button', { className: 'btn btn-ghost', style: { padding: '5px 10px', fontSize: 11, border: '1px solid var(--border)' }, disabled: busy, onClick: () => doGrant(u) }, '+Admin')
                    : (me?.id !== u.id && h('button', { className: 'btn btn-ghost', style: { padding: '5px 10px', fontSize: 11, border: '1px solid var(--border)' }, disabled: busy, onClick: () => doRevoke(u) }, '−Admin')),
                  u.banned
                    ? h('button', { className: 'btn btn-ghost', style: { padding: '5px 10px', fontSize: 11, border: '1px solid rgba(74,222,128,0.3)', color: 'var(--green)' }, disabled: busy, onClick: () => doUnban(u) }, 'Unban')
                    : h('button', { className: 'btn btn-ghost', style: { padding: '5px 10px', fontSize: 11, border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)' }, disabled: busy, onClick: () => doBan(u) }, 'Ban')
                )
              )
            )))
          );
    })(),
    // Ban-reason picker drawer — opens on "Ban" click with templates +
    // a free-text textarea. Admins pick a template to populate the box
    // and can still edit before submitting. Keeps ban-reason text
    // consistent across the team without blocking custom entries.
    banTarget && h(BanReasonDrawer, {
      user: banTarget,
      templates: BAN_TEMPLATES,
      busy,
      onCancel: () => setBanTarget(null),
      onSubmit: submitBan
    }),
    // User detail drawer — read-only snapshot (public stall + reviews)
    // so admins can eyeball a user's activity without scavenging the UI.
    // Backed by the existing public endpoints; no new surface area.
    detailUser && h('div', {
      className: 'cart-confirm-backdrop',
      onClick: () => setDetailUser(null)
    },
      h('div', {
        className: 'cart-confirm-panel',
        style: { maxWidth: 640 },
        onClick: e => e.stopPropagation()
      },
        h('div', { style: { display: 'flex', alignItems: 'center', gap: 14, marginBottom: 12 } },
          detailUser.avatarUrl
            ? h('img', { src: detailUser.avatarUrl, alt: '', style: { width: 56, height: 56, borderRadius: '50%' } })
            : h('div', { className: 'db-thumb', style: { width: 56, height: 56, fontSize: 18 } }, (detailUser.displayName || 'U').substring(0, 2).toUpperCase()),
          h('div', { style: { flex: 1, minWidth: 0 } },
            h('div', { className: 'cart-confirm-title' }, detailUser.displayName || 'Player'),
            h('div', { className: 'cart-confirm-sub' },
              `Steam ID ${detailUser.steamId64} · #${detailUser.id} · role ${detailUser.role || 'USER'}`)
          ),
          h('a', {
            className: 'btn btn-ghost',
            style: { padding: '6px 12px', fontSize: 11, border: '1px solid var(--border)' },
            href: `/stall/${detailUser.id}`, target: '_blank', rel: 'noopener noreferrer'
          }, 'Open stall ↗'),
          // Reset 2FA — support flow for users who have lost access to
          // their TOTP authenticator. Admin-gated action (not CSR). The
          // target gets a notification + must re-enrol next session.
          h('button', {
            className: 'btn btn-ghost',
            style: { padding: '6px 12px', fontSize: 11, border: '1px solid rgba(251,191,36,0.3)', color: '#fbbf24' },
            title: 'Reset the user\'s two-factor authentication',
            onClick: async () => {
              const note = window.prompt(
                `Reset 2FA for ${detailUser.displayName || ('#' + detailUser.id)}?\n\n` +
                `They\'ll need to re-enrol from Profile → 2FA next time they sign in. Enter an audit note (required):`);
              if (!note || !note.trim()) return;
              const res = await adminReset2fa(detailUser.id, note.trim());
              if (res.code || res.error) { alert(res.message || res.error); return; }
              alert('✓ 2FA has been reset for this user.');
            }
          }, '⚿ Reset 2FA')
        ),
        detailData === null
          ? h('div', { className: 'spinner' })
          : h('div', null,
              h('div', { className: 'health-grid', style: { marginBottom: 16 } },
                h('div', { className: 'health-card' },
                  h('div', { className: 'health-card-label' }, 'Active listings'),
                  h('div', { className: 'health-card-value' }, (detailData.stall?.count ?? 0).toString()),
                  h('div', { className: 'health-card-hint' }, detailData.stall?.away ? 'Seller is away' : 'Visible to buyers')
                ),
                h('div', { className: 'health-card' },
                  h('div', { className: 'health-card-label' }, 'Lifetime sales'),
                  h('div', { className: 'health-card-value' }, (detailData.stall?.seller?.soldCount ?? 0).toString()),
                  h('div', { className: 'health-card-hint' }, detailData.stall?.seller?.verified ? 'Verified' : 'Not verified')
                ),
                h('div', { className: 'health-card' },
                  h('div', { className: 'health-card-label' }, 'Reviews'),
                  h('div', { className: 'health-card-value' },
                    detailData.stall?.rating?.count
                      ? `${Number(detailData.stall.rating.average || 0).toFixed(1)} ★`
                      : '—'
                  ),
                  h('div', { className: 'health-card-hint' }, `${detailData.stall?.rating?.count ?? 0} review${detailData.stall?.rating?.count === 1 ? '' : 's'}`)
                )
              ),
              // Staff notes editor — textarea + Save button. Notes are
              // @JsonIgnore'd on the entity so regular user-facing
              // endpoints never surface them. Cap mirrors the 4000-char
              // server cap.
              h('div', { style: { marginTop: 14, marginBottom: 12 } },
                h('div', { style: { fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: 0.5, color: 'var(--text-muted)', marginBottom: 6 } }, '🔒 Staff notes (not visible to user)'),
                h('textarea', {
                  className: 'price-input',
                  style: { width: '100%', minHeight: 70, resize: 'vertical', fontFamily: 'inherit', fontSize: 12 },
                  placeholder: 'Internal context — ops observations, prior incidents, handoff notes between CSRs.',
                  value: notesDraft,
                  maxLength: 4000,
                  onChange: e => setNotesDraft(e.target.value)
                }),
                h('div', { style: { display: 'flex', gap: 8, marginTop: 6, alignItems: 'center' } },
                  h('button', {
                    className: 'btn btn-accent',
                    style: { padding: '5px 14px', fontSize: 11 },
                    disabled: notesSaving,
                    onClick: async () => {
                      setNotesSaving(true);
                      try {
                        const res = await adminWriteNotes(detailUser.id, notesDraft);
                        if (res && (res.error || res.code)) { alert(res.message || res.error); return; }
                      } finally { setNotesSaving(false); }
                    }
                  }, notesSaving ? 'Saving…' : 'Save notes'),
                  h('span', { style: { fontSize: 10, color: 'var(--text-muted)' } },
                    `${notesDraft.length}/4000`)
                )
              ),
              detailData.reviews.length > 0 && h('div', null,
                h('div', { style: { fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--text-muted)', margin: '10px 0 8px' } }, 'Most recent reviews'),
                detailData.reviews.slice(0, 6).map(r => h('div', { key: r.id, style: { padding: '8px 12px', background: 'var(--bg-elevated)', border: '1px solid var(--border)', borderRadius: 6, marginBottom: 6 } },
                  h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } },
                    '★'.repeat(r.rating) + '☆'.repeat(5 - r.rating),
                    ' · ', r.fromDisplayName || 'Anonymous',
                    ' · ', new Date(r.createdAt).toLocaleDateString()
                  ),
                  r.comment && h('div', { style: { fontSize: 12.5, color: 'var(--text-secondary)', marginTop: 2 } }, r.comment)
                ))
              )
            ),
        h('div', { className: 'cart-confirm-actions' },
          h('button', {
            className: 'btn btn-accent',
            onClick: () => setDetailUser(null)
          }, 'Close')
        )
      )
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
      // Canned-response templates. Click a chip to populate the reply
      // input; admin can still edit before sending. Saves typing the
      // same "investigating, will follow up within 24h" boilerplate on
      // every deposit/trade/refund ticket. Selected chip state is local
      // to the open ticket view.
      viewing.ticket.status !== 'RESOLVED' && h('div', { className: 'ticket-templates' },
        [
          { id: 'greet',     label: '👋 Greet',     body: 'Hi — thanks for reaching out. I\'m looking into this now and will follow up within 24 hours.' },
          { id: 'deposit',   label: '💳 Deposit',   body: 'Can you share the Stripe session id from your Wallet → History tab? Most deposits clear within 2 minutes; if yours hasn\'t, I\'ll check the Stripe side for a hold or decline.' },
          { id: 'trade',     label: '⇄ Trade',     body: 'Trades sit in escrow until the buyer confirms — typically within 8 days. If the seller hasn\'t sent the Steam offer yet, their trade URL is on their stall page. Let me know if you need me to nudge them.' },
          { id: 'refund',    label: '↩ Refund',    body: 'I can issue a refund to your sboxmarket wallet balance for this trade. Confirm you\'d like that and I\'ll process it — Stripe-side chargebacks would go through your card issuer instead.' },
          { id: 'resolved',  label: '✓ Resolved',  body: 'Glad that\'s sorted. I\'m marking this resolved — reply here any time if anything else comes up.' }
        ].map(tpl => h('button', {
          key: tpl.id,
          className: 'wallet-tx-filter-chip',
          onClick: () => setReply(tpl.body),
          title: tpl.body
        }, tpl.label))
      ),
      viewing.ticket.status !== 'RESOLVED' && h('div', { style: { display: 'flex', gap: 8, marginTop: 10 } },
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
    { id: 'flag',      label: '🚩 Flag Listing' },
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
    tab === 'flag'      && h(CsrFlagTab, null),
  );
}

function CsrFlagTab() {
  const [id, setId]        = useState('');
  const [reason, setReason]= useState('');
  const [busy, setBusy]    = useState(false);
  const [result, setResult]= useState(null);
  const REASONS = [
    'Suspicious pricing',
    'Suspected duplicate / scam',
    'Wrong category or description',
    'Prohibited item',
    'Other (see note)'
  ];
  const submit = async () => {
    const listingId = parseInt(id, 10);
    if (!listingId) { alert('Enter a numeric listing ID.'); return; }
    if (!reason.trim()) { alert('Pick or type a reason.'); return; }
    setBusy(true);
    try {
      const res = await csrFlagListing(listingId, reason.trim());
      if (res.error || res.code) {
        setResult({ ok: false, msg: res.message || res.error });
      } else {
        setResult({ ok: true, msg: 'Flag note appended to listing #' + res.id });
        setId(''); setReason('');
      }
    } finally { setBusy(false); }
  };
  return h('div', { className: 'profile-panel' },
    h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 10, lineHeight: 1.5 } },
      'Flagging a listing appends an admin-visible note to its description. Use when you spot something during a support chat that an admin should review. Does not remove the listing — admins take the final action.'),
    h('div', { style: { display: 'flex', gap: 10, marginBottom: 10 } },
      h('input', {
        className: 'price-input',
        style: { width: 140 },
        placeholder: 'Listing ID',
        value: id,
        inputMode: 'numeric',
        onChange: e => setId(e.target.value.replace(/[^0-9]/g, ''))
      }),
      h('select', {
        className: 'price-input',
        style: { flex: 1 },
        value: REASONS.includes(reason) ? reason : '',
        onChange: e => setReason(e.target.value)
      },
        h('option', { value: '' }, 'Pick a reason…'),
        REASONS.map(r => h('option', { key: r, value: r }, r))
      )
    ),
    h('textarea', {
      className: 'price-input',
      style: { width: '100%', minHeight: 70, marginBottom: 10, resize: 'vertical' },
      placeholder: 'Additional note (optional, 500 char max)',
      maxLength: 500,
      value: REASONS.includes(reason) ? '' : reason,
      onChange: e => setReason(e.target.value)
    }),
    h('button', {
      className: 'btn btn-accent',
      disabled: busy || !id || !reason.trim(),
      onClick: submit
    }, busy ? 'Flagging…' : '🚩 Flag listing'),
    result && h('div', {
      style: {
        marginTop: 12, padding: 10, borderRadius: 6,
        background: result.ok ? 'rgba(34,197,94,0.1)' : 'var(--red-dim)',
        border: '1px solid ' + (result.ok ? 'rgba(34,197,94,0.4)' : 'var(--red)'),
        color: result.ok ? '#22c55e' : 'var(--red)',
        fontSize: 12
      }
    }, result.msg)
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
      // Same canned-response templates as the admin view — lets CSRs
      // drop a standard reply in one click.
      viewing.ticket.status !== 'RESOLVED' && h('div', { className: 'ticket-templates' },
        [
          { id: 'greet',    label: '👋 Greet',    body: 'Hi — thanks for reaching out. I\'m looking into this now and will follow up within 24 hours.' },
          { id: 'deposit',  label: '💳 Deposit',  body: 'Can you share the Stripe session id from your Wallet → History tab? Most deposits clear within 2 minutes; if yours hasn\'t, I\'ll check the Stripe side for a hold or decline.' },
          { id: 'trade',    label: '⇄ Trade',     body: 'Trades sit in escrow until the buyer confirms — typically within 8 days. If the seller hasn\'t sent the Steam offer yet, their trade URL is on their stall page.' },
          { id: 'refund',   label: '↩ Refund',    body: 'I can issue a refund to your sboxmarket wallet balance for this trade. Confirm you\'d like that and I\'ll process it.' },
          { id: 'resolved', label: '✓ Resolved',  body: 'Glad that\'s sorted. I\'m marking this resolved — reply here any time if anything else comes up.' }
        ].map(tpl => h('button', {
          key: tpl.id,
          className: 'wallet-tx-filter-chip',
          onClick: () => setReply(tpl.body),
          title: tpl.body
        }, tpl.label))
      ),
      viewing.ticket.status !== 'RESOLVED' && h('div', { style: { display: 'flex', gap: 8, marginTop: 10 } },
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
            h('tbody', null, list.map(t => {
              // Urgency: waiting-staff ticket with no CSR reply in >2h.
              // Lets the lead CSR spot the tail of the queue at a glance
              // and prioritise old tickets before they blow SLA.
              const urgent = t.status === 'WAITING_STAFF' &&
                (Date.now() - (t.updatedAt || 0)) > 2 * 3600_000;
              return h('tr', {
                key: t.id,
                className: `db-row${urgent ? ' urgent-row' : ''}`,
                onClick: () => open(t.id)
              },
                h('td', { className: 'db-rank' }, '#' + t.id),
                h('td', null, t.subject),
                h('td', { className: 'db-mono', style: { fontSize: 11 } }, t.username || '#' + t.userId),
                h('td', { style: { fontSize: 10, fontWeight: 700 } },
                  t.status,
                  urgent && h('span', { className: 'withdraw-urgent', title: 'No staff reply in over 2 hours' }, '● SLA')
                ),
                h('td', { className: 'right', style: { fontSize: 11, color: 'var(--text-muted)' } }, timeAgo(t.updatedAt))
              );
            }))
          )
  );
}
