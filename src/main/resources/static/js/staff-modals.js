// Admin + CSR panels. Kept in their own file so the ordinary-user modal
// module doesn't pull in staff code paths. Both panels are tabbed, use
// InfoModal as the shell, and call into api.js for I/O.
import { h, useState, useEffect, useCallback, fmt, timeAgo, toast, linkifyText } from './utils.js';
import { InfoModal } from './info-modal.js';
import { ReasonDrawer, MaterialIcon } from './primitives.js';
import {
  adminStats, adminWithdrawals, adminApproveWithdrawal, adminRejectWithdrawal,
  adminUsers, adminUserSummary, adminUserTransactions, adminMessageUser, adminBanUser, adminUnbanUser, adminForceLogout, adminGrant, adminRevoke,
  adminGrantCsr, adminRevokeCsr, adminReset2fa, adminReadNotes, adminWriteNotes,
  adminDeletionRequests, adminFinalizeDeletion,
  adminCreditWallet, adminFreezeWallet, adminUnfreezeWallet, adminRemoveListing, adminReportedListings, adminDismissReports, adminTickets, adminTicket,
  adminTicketReply, adminCloseTicket, adminRefundDeposit, adminAudit,
  adminFraudSignals, adminApiKeyLookup,
  adminTrades, adminReleaseTrade, adminCancelTrade, adminDeleteTradeMessage, fetchTradeMessages,
  adminSimulateListings, adminClearSimulated, adminCountSimulated, adminSyncScmm,
  adminSyncSteamPrices,
  csrStats, csrLookup, csrTickets, csrTicket, csrTicketReply, csrCloseTicket,
  csrGoodwill, csrFlagListing
} from './api.js';

// ── ADMIN PANEL ─────────────────────────────────────────────────
export function AdminModal({ onClose, me }) {
  // Deep-link via `?tab=<id>` (batch 463) — lets a notification like
  // CHARGEBACK_OPENED route the admin straight to /admin?tab=disputes
  // instead of dropping them on the dashboard. Falls back to dashboard
  // when the tab id is missing or unknown.
  const initialTab = (() => {
    try {
      const t = new URLSearchParams(window.location.search).get('tab');
      return t || 'dashboard';
    } catch { return 'dashboard'; }
  })();
  const [tab, setTab] = useState(initialTab);
  // Priority-tab badge counts (batch 487). Polls dashboardStats every
  // 60s so the tab bar shows "⚠ Disputes (3)" / "💸 Withdrawals (5)"
  // without clicking in. Helps admins notice work piling up in tabs
  // they're not currently looking at.
  const [badgeCounts, setBadgeCounts] = useState({ disputes: 0, withdrawals: 0, tickets: 0, trades: 0 });
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const s = await adminStats();
        if (!alive || !s) return;
        setBadgeCounts({
          disputes:    Number(s.activeChargebacks || 0),
          withdrawals: Number(s.pendingWithdrawals || 0),
          tickets:     Number(s.openTickets || 0),
          trades:      Number(s.disputedTrades || 0)
        });
      } catch (_) {}
    };
    load();
    // Batch 806 — visibility-aware poll.
    const id = setInterval(() => { if (!document.hidden) load(); }, 60_000);
    const onVis = () => { if (!document.hidden) load(); };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      alive = false; clearInterval(id);
      document.removeEventListener('visibilitychange', onVis);
    };
  }, []);
  const TABS = [
    { id: 'dashboard',   label: 'Dashboard' },
    { id: 'withdrawals', label: 'Withdrawals', badge: badgeCounts.withdrawals },
    { id: 'trades',      label: 'Trades',      badge: badgeCounts.trades },
    { id: 'users',       label: 'Users' },
    { id: 'tickets',     label: 'Tickets',     badge: badgeCounts.tickets },
    { id: 'refunds',     label: 'Refunds' },
    { id: 'catalogue',   label: 'Catalogue' },
    { id: 'simulator',   label: 'Simulator' },
    { id: 'fraud',       label: 'Fraud' },
    { id: 'disputes',    label: 'Disputes',    badge: badgeCounts.disputes },
    { id: 'reported',    label: 'Reports' },
    { id: 'deletions',   label: 'Deletions' },
    { id: 'announce',    label: 'Announce' },
    { id: 'health',      label: 'Health' },
    { id: 'audit',       label: 'Audit Log' },
  ];
  return h(InfoModal, { title: 'Admin Panel', onClose },
    h('div', { className: 'staff-banner admin' },
      h('strong', null, 'ADMIN MODE'),
      ' — every action here is logged with your Steam ID and is reversible only by another admin. Use with care.'
    ),
    // Batch 938 — tablist semantics on the admin tab strip (matches the
    // profile + wallet + offers tablist work in batches 936/937).
    h('div', { className: 'profile-tabs', role: 'tablist', 'aria-label': 'Admin sections' },
      TABS.map(t => h('button', {
        key: t.id,
        id: `admin-tab-${t.id}`,
        className: `profile-tab ${tab === t.id ? 'active' : ''}`,
        role: 'tab',
        'aria-selected': tab === t.id,
        'aria-controls': 'admin-tabpanel',
        tabIndex: tab === t.id ? 0 : -1,
        onKeyDown: (e) => {
          if (!['ArrowRight','ArrowLeft','Home','End'].includes(e.key)) return;
          e.preventDefault();
          const idx = TABS.findIndex(x => x.id === tab);
          let n = idx;
          if (e.key === 'ArrowRight') n = (idx + 1) % TABS.length;
          else if (e.key === 'ArrowLeft') n = (idx - 1 + TABS.length) % TABS.length;
          else if (e.key === 'Home') n = 0;
          else if (e.key === 'End') n = TABS.length - 1;
          setTab(TABS[n].id);
        },
        onClick: () => {
          setTab(t.id);
          // Mirror the active tab into the URL so a copy-link / browser-
          // back retains the admin's current view. replaceState (not push)
          // because each tab click shouldn't add a history entry.
          try {
            const next = t.id === 'dashboard' ? '/admin' : '/admin?tab=' + t.id;
            if (window.location.pathname + window.location.search !== next) {
              window.history.replaceState({}, '', next);
            }
          } catch (_) {}
        }
      },
        t.label,
        // Priority-tab badge (batch 487). Red pill with the pending
        // count next to tabs that carry outstanding work. Capped at
        // 99+ so a giant queue doesn't blow out the tab bar.
        typeof t.badge === 'number' && t.badge > 0 && h('span', {
          className: 'filter-count',
          style: {
            marginLeft: 6, background: 'var(--red)', color: '#0b0f1a',
            fontWeight: 800, padding: '1px 6px', borderRadius: 10,
            fontSize: 10, minWidth: 16, textAlign: 'center'
          }
        }, t.badge > 99 ? '99+' : t.badge)
      ))
    ),
    // Single tabpanel host for the active tab's content — `aria-labelledby`
    // tracks the active tab button so screen readers announce the right
    // section name. One stable id is fine since only one tab renders at a time.
    h('div', { role: 'tabpanel', id: 'admin-tabpanel', 'aria-labelledby': `admin-tab-${tab}` },
      tab === 'dashboard'   && h(AdminDashboardTab, { onNavTab: setTab }),
      tab === 'withdrawals' && h(AdminWithdrawalsTab, null),
      tab === 'trades'      && h(AdminTradesTab, null),
      tab === 'users'       && h(AdminUsersTab, { me }),
      tab === 'tickets'     && h(AdminTicketsTab, null),
      tab === 'refunds'     && h(AdminRefundsTab, null),
      tab === 'catalogue'   && h(AdminCatalogueTab, null),
      tab === 'simulator'   && h(AdminSimulatorTab, null),
      tab === 'fraud'       && h(AdminFraudTab, null),
      tab === 'disputes'    && h(AdminDisputesTab, null),
      tab === 'reported'    && h(AdminReportedTab, null),
      tab === 'deletions'   && h(AdminDeletionsTab, null),
      tab === 'announce'    && h(AdminAnnouncementsTab, null),
      tab === 'health'      && h(AdminHealthTab, null),
      tab === 'audit'       && h(AdminAuditTab, null),
    ),
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
    // Batch 806 — a staff panel left open in a background tab was
    // polling /api/admin/health every 5s forever. Gate on visibility
    // + add a tab-focus refresh so returning to the tab still gets a
    // fresh reading without waiting five seconds.
    const id = setInterval(() => { if (!document.hidden) load(); }, 5_000);
    const onVis = () => { if (!document.hidden) load(); };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      alive = false; clearInterval(id);
      document.removeEventListener('visibilitychange', onVis);
    };
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
           `${data.jvm?.name || 'JVM'} ${data.jvm?.version || ''}`.trim()),
      // Stripe webhook telemetry (batch 471). Lets ops spot silent
      // outages — webhook secret rotated, Stripe dashboard pointed
      // at the wrong URL, etc. Hint goes amber when no events have
      // been received in over 24h since boot (suggests config issue).
      (() => {
        const wh = data.stripeWebhook;
        const at = wh?.lastReceivedAt || 0;
        if (!at) return card('Stripe webhook', 'No events yet',
          'Awaiting first event since boot');
        const ageMin = Math.round((Date.now() - at) / 60000);
        const ageLabel = ageMin < 1 ? 'just now'
          : ageMin < 60 ? ageMin + 'm ago'
          : ageMin < 1440 ? Math.round(ageMin / 60) + 'h ago'
          : Math.round(ageMin / 1440) + 'd ago';
        return card('Stripe webhook', wh.lastEventType || '(unknown)', 'Last event: ' + ageLabel);
      })()
    ),
    // SMTP validation — ops changes SMTP config + wants a live test
    // without waiting for a trade event. Sends through EmailService.send
    // so log-sink mode is distinguishable (admin sees "sent" in the
    // response but the mail lands in /var/log).
    h(SmtpTestRow, null),
    h(ForcePriceSyncRow, { priceSync: data.priceSync })
  );
}

function ForcePriceSyncRow({ priceSync }) {
  const [busy, setBusy]  = useState(false);
  const [result, setRes] = useState(null);
  const submit = async () => {
    setBusy(true); setRes(null);
    try {
      const res = await adminSyncSteamPrices();
      if (res && (res.error || res.code)) {
        setRes({ ok: false, msg: res.error || res.message || 'Failed to start sync' });
      } else {
        setRes({ ok: true, msg: 'Sync running in the background — prices + trends will update over the next few minutes. Check server logs for per-item progress.' });
      }
    } catch (e) {
      setRes({ ok: false, msg: String(e) });
    } finally { setBusy(false); }
  };
  return h('div', {
    style: {
      marginTop: 12, padding: 14,
      background: 'var(--bg-elevated)',
      border: '1px solid var(--border)',
      borderRadius: 8
    }
  },
    h('div', { style: { fontSize: 12, fontWeight: 700, textTransform: 'uppercase', letterSpacing: 0.5, color: 'var(--text-muted)', marginBottom: 8 } },
      '💹 Force Steam price sync'),
    h('div', { style: { fontSize: 12, color: 'var(--text-secondary)', marginBottom: 10, lineHeight: 1.5 } },
      "Kicks off an on-demand sync against Steam Community Market's priceoverview endpoint. The scheduled job already runs every 30 min; use this after a catalogue import or when prices look stale. Full run takes ~11 min (80 items × 8s throttle)."),
    // Last-run telemetry (batch 396). Shows freshness + outcome + next
    // scheduled run so ops don't have to tail logs. Silent until the
    // first sync completes (finishedAt stays 0 until then).
    priceSync && priceSync.finishedAt > 0 && (() => {
      const finishedAgo = Math.max(0, Date.now() - priceSync.finishedAt);
      const fmtAgo = finishedAgo < 60_000 ? 'just now' :
                     finishedAgo < 3_600_000 ? Math.floor(finishedAgo / 60_000) + 'm ago' :
                     finishedAgo < 86_400_000 ? Math.floor(finishedAgo / 3_600_000) + 'h ago' :
                     Math.floor(finishedAgo / 86_400_000) + 'd ago';
      const nextInMs = priceSync.nextRunAt - Date.now();
      const nextLabel = nextInMs <= 0 ? 'scheduled any moment' :
                        nextInMs < 60_000 ? 'in <1m' :
                        nextInMs < 3_600_000 ? 'in ~' + Math.ceil(nextInMs / 60_000) + 'm' :
                        'in ~' + Math.ceil(nextInMs / 3_600_000) + 'h';
      const durationMin = priceSync.durationMs > 0
        ? (priceSync.durationMs / 60_000).toFixed(1)
        : null;
      return h('div', {
        style: {
          marginBottom: 10, padding: 8, borderRadius: 6,
          background: 'var(--bg-page-2, #0d1320)',
          border: '1px solid var(--border)',
          fontSize: 11, color: 'var(--text-secondary)',
          display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'center'
        }
      },
        h('span', null,
          h('strong', { style: { color: 'var(--text-primary)' } }, 'Last run'),
          ' · ', fmtAgo),
        h('span', null,
          'updated ',
          h('strong', { style: { color: 'var(--green)' } }, priceSync.updated),
          '/', priceSync.total,
          priceSync.failed > 0 && h('span', { style: { marginLeft: 6, color: 'var(--red)' } }, ' · ' + priceSync.failed + ' failed'),
          priceSync.skipped > 0 && h('span', { style: { marginLeft: 6, color: 'var(--text-muted)' } }, ' · ' + priceSync.skipped + ' skipped'),
          // Aborted-by-circuit-breaker badge (batch 460). When Steam
          // 429s us 5 times in a row, the sync aborts before
          // processing every item — surface that here so ops don't
          // misread "updated 7/80" as a degraded item catalog.
          priceSync.aborted && h('span', {
            style: {
              marginLeft: 6, padding: '1px 6px', borderRadius: 3,
              background: 'rgba(251,191,36,0.15)', color: '#fbbf24',
              fontSize: 10, fontWeight: 800, letterSpacing: 0.4
            },
            title: 'The last sync hit the 5-consecutive-429 circuit breaker and aborted early. Steam was rate-limiting us. The next scheduled run will pick up where this one left off.'
          }, '⚠ ABORTED')
        ),
        durationMin && h('span', null, 'took ', durationMin, 'min'),
        h('span', { style: { marginLeft: 'auto', color: 'var(--text-muted)' } },
          'Next ', nextLabel)
      );
    })(),
    h('button', {
      className: 'btn btn-accent',
      disabled: busy,
      onClick: submit
    }, busy ? 'Starting…' : 'Run sync now'),
    result && h('div', {
      style: {
        marginTop: 10, padding: 8, borderRadius: 6,
        background: result.ok ? 'rgba(34,197,94,0.1)' : 'var(--red-dim)',
        border: '1px solid ' + (result.ok ? 'rgba(34,197,94,0.4)' : 'var(--red)'),
        color: result.ok ? '#22c55e' : 'var(--red)',
        fontSize: 12, lineHeight: 1.5
      }
    }, result.msg)
  );
}

function SmtpTestRow() {
  const [to, setTo]       = useState('');
  const [busy, setBusy]   = useState(false);
  const [result, setRes]  = useState(null);
  const submit = async () => {
    if (!to.trim()) { toast('Enter a destination address', 'err'); return; }
    setBusy(true); setRes(null);
    try {
      const csrf = (document.cookie.match(/sbox_csrf=([^;]+)/) || [])[1];
      const r = await fetch('/api/admin/test-email', {
        method: 'POST', credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          ...(csrf ? { 'X-CSRF-Token': decodeURIComponent(csrf) } : {})
        },
        body: JSON.stringify({ to: to.trim(), subject: 'SkinBox SMTP test', body: 'This is a diagnostic email from your SkinBox admin panel. If you received it, the send pipeline is healthy.' })
      });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) { setRes({ ok: false, msg: j.message || j.error || `HTTP ${r.status}` }); return; }
      setRes({ ok: true, msg: `Sent to ${j.to || to.trim()}. Check the inbox (or the server log in dev).` });
    } catch (e) {
      setRes({ ok: false, msg: String(e) });
    } finally { setBusy(false); }
  };
  return h('div', {
    style: {
      marginTop: 20, padding: 14,
      background: 'var(--bg-elevated)',
      border: '1px solid var(--border)',
      borderRadius: 8
    }
  },
    h('div', { style: { fontSize: 12, fontWeight: 700, textTransform: 'uppercase', letterSpacing: 0.5, color: 'var(--text-muted)', marginBottom: 8 } },
      '📧 SMTP test'),
    h('div', { style: { fontSize: 12, color: 'var(--text-secondary)', marginBottom: 10, lineHeight: 1.5 } },
      'Fires one email through the live pipeline — useful after changing SMTP_HOST / credentials. In log-sink mode (no SMTP configured) the message lands in the server log, not the inbox.'),
    h('div', { style: { display: 'flex', gap: 8, flexWrap: 'wrap' } },
      h('input', {
        className: 'price-input',
        style: { flex: 1, minWidth: 240 },
        type: 'email',
        placeholder: 'ops@example.com',
        value: to,
        onChange: e => setTo(e.target.value)
      }),
      h('button', {
        className: 'btn btn-accent',
        disabled: busy || !to.trim(),
        onClick: submit
      }, busy ? 'Sending…' : 'Send test')
    ),
    result && h('div', {
      style: {
        marginTop: 10, padding: 8, borderRadius: 6,
        background: result.ok ? 'rgba(34,197,94,0.1)' : 'var(--red-dim)',
        border: '1px solid ' + (result.ok ? 'rgba(34,197,94,0.4)' : 'var(--red)'),
        color: result.ok ? '#22c55e' : 'var(--red)',
        fontSize: 12
      }
    }, result.msg)
  );
}

// Announcement management — read the current banner state, post a new
// INFO/WARN/CRITICAL banner, optionally schedule an auto-expiry. Admins
// can deactivate any row from the history list. Minimum 3 chars (matched
// by the server-side validator in AnnouncementService.create).
// User-reported listings queue. Sorted highest-report-count-first. Each row
// shows the top reasons + recent notes inline so the admin decides without
// a drill-down for most calls.
// Self-service account-deletion queue. Each row shows outstanding
// obligations (pending withdrawals, open trades) that must be
// resolved before the admin can click Finalise. Finalise scrubs
// PII + bans the account. No rows are physically deleted.
function AdminDeletionsTab() {
  const [rows, setRows] = useState(null);
  const [busy, setBusy] = useState(false);
  const load = useCallback(async () => { setRows(null); setRows(await adminDeletionRequests()); }, []);
  useEffect(() => { load(); }, [load]);
  const finalise = async (r) => {
    const ok = confirm(
      `Finalise deletion for ${r.displayName || ('#' + r.id)}?\n\n` +
      `• Display name → "Deleted user #${r.id}"\n` +
      `• Email, avatar, trade URL, 2FA secret → cleared\n` +
      `• Account banned (user can't sign back in)\n` +
      `• Listings, trades, transactions stay for audit\n\n` +
      `This is irreversible.`);
    if (!ok) return;
    // Batch 943 — capture the display name BEFORE finalise (after the
    // row drops off the queue it's harder to cite which user just went).
    const label = r.displayName || ('#' + r.id);
    setBusy(true);
    try {
      const res = await adminFinalizeDeletion(r.id);
      if (res && (res.error || res.code)) { toast(res.message || res.error, 'err'); return; }
      await load();
      // Final-state toast names the user + what happened so the admin
      // has a concrete audit trail in their UI session before the
      // next tab switch.
      toast(`${label} deletion finalised — PII cleared, account banned, ledger rows retained.`, 'ok');
    } finally { setBusy(false); }
  };
  if (rows === null) return h('div', { className: 'spinner' });
  if (rows.length === 0) return h('div', { className: 'profile-panel' },
    h('div', { className: 'empty-inline' },
      h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'delete', size: 26 })),
      h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } },
        'No pending deletion requests.')));
  return h('div', { className: 'profile-panel' },
    h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 12, lineHeight: 1.5 } },
      `${rows.length} user${rows.length === 1 ? '' : 's'} have requested account deletion (GDPR/DSAR). Check the "open" columns before finalising — pending withdrawals and open trades must be resolved first.`),
    h('table', { className: 'db-table' },
      h('thead', null, h('tr', null,
        h('th', null, 'User'),
        h('th', null, 'Requested'),
        h('th', { className: 'right' }, 'Balance'),
        h('th', { className: 'right' }, 'Open withdrawals'),
        h('th', { className: 'right' }, 'Open trades'),
        h('th', { className: 'right' }, 'Actions')
      )),
      h('tbody', null, rows.map(r => {
        const blocked = r.pendingWithdrawals > 0 || r.openTrades > 0;
        return h('tr', { key: r.id, className: 'db-row' },
          h('td', null,
            h('div', { className: 'db-name' }, r.displayName || 'Player'),
            h('div', { className: 'db-sub' }, '#' + r.id + ' · ' + (r.email || 'no email'))
          ),
          h('td', { style: { fontSize: 11, color: 'var(--text-muted)' } }, timeAgo(r.deletionRequestedAt)),
          h('td', { className: 'right db-mono' }, fmt(r.walletBalance || 0)),
          h('td', { className: 'right', style: { color: r.pendingWithdrawals > 0 ? 'var(--red)' : 'var(--text-muted)' } }, r.pendingWithdrawals),
          h('td', { className: 'right', style: { color: r.openTrades > 0 ? 'var(--red)' : 'var(--text-muted)' } }, r.openTrades),
          h('td', { className: 'right' },
            h('button', {
              className: 'btn btn-ghost',
              style: {
                padding: '5px 10px', fontSize: 11,
                border: '1px solid rgba(248,113,113,0.3)',
                color: blocked ? 'var(--text-muted)' : 'var(--red)',
                opacity: blocked ? 0.5 : 1
              },
              disabled: busy || blocked,
              title: blocked ? 'Resolve pending withdrawals + open trades first' : 'Scrub PII + ban account',
              onClick: () => finalise(r)
            }, blocked ? 'Blocked' : 'Finalise')
          )
        );
      }))
    )
  );
}

function AdminReportedTab() {
  const [rows, setRows] = useState(null);
  const [busy, setBusy] = useState(false);
  const [expanded, setExpanded] = useState({});  // listingId → bool
  const [actionDraft, setActionDraft] = useState(null);  // { kind: 'remove'|'dismiss', row }
  const load = useCallback(async () => { setRows(null); setRows(await adminReportedListings()); }, []);
  useEffect(() => { load(); }, [load]);

  const submitReportAction = async (text) => {
    if (!actionDraft || busy) return;
    const { kind, row } = actionDraft;
    setBusy(true);
    try {
      if (kind === 'remove') {
        const res = await adminRemoveListing(row.id, text);
        if (res.code || res.error) { toast(res.message || res.error, 'err'); return; }
        toast(`Listing #${row.id} (${row.itemName}) removed — seller notified.`);
      } else {
        const res = await adminDismissReports(row.id, text);
        if (res.code || res.error) { toast(res.message || res.error, 'err'); return; }
        toast(`Dismissed ${row.reportCount} report${row.reportCount === 1 ? '' : 's'} on #${row.id}.`);
      }
      setActionDraft(null);
      await load();
    } finally { setBusy(false); }
  };
  if (rows === null) return h('div', { className: 'spinner' });
  if (rows.length === 0) {
    return h('div', { className: 'profile-panel' },
      h('div', { className: 'empty-inline' },
        h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'flag', size: 26 })),
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
          style: { cursor: 'pointer' },
          role: 'button',
          tabIndex: 0,
          'aria-expanded': !!expanded[r.id],
          'aria-label': `${expanded[r.id] ? 'Collapse' : 'Expand'} reported listing #${r.id} — ${r.itemName || 'unknown item'} (${r.reportCount} reports)`,
          onKeyDown: (e) => {
            const tag = (e.target?.tagName || '').toLowerCase();
            if (tag === 'button' || tag === 'a' || tag === 'input') return;
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault();
              setExpanded(s => ({ ...s, [r.id]: !s[r.id] }));
            }
          }
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
                disabled: busy,
                'aria-haspopup': 'dialog',
                'aria-expanded': !!(actionDraft && actionDraft.kind === 'dismiss' && actionDraft.row.id === r.id),
                onClick: () => setActionDraft(
                  actionDraft && actionDraft.kind === 'dismiss' && actionDraft.row.id === r.id
                    ? null
                    : { kind: 'dismiss', row: r }),
                title: 'Mark reviewed — keep the listing, clear the reports'
              }, 'Dismiss'),
              h('button', {
                className: 'btn btn-ghost',
                style: { padding: '5px 10px', fontSize: 11, border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)' },
                disabled: busy,
                'aria-haspopup': 'dialog',
                'aria-expanded': !!(actionDraft && actionDraft.kind === 'remove' && actionDraft.row.id === r.id),
                onClick: () => setActionDraft(
                  actionDraft && actionDraft.kind === 'remove' && actionDraft.row.id === r.id
                    ? null
                    : { kind: 'remove', row: r })
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
    ),
    actionDraft && h('div', { style: { marginTop: 14 } },
      h(ReasonDrawer, {
        // Key on kind + row so switching the drawer to a different
        // listing (clicking another row's Remove/Dismiss while the
        // drawer is open) remounts it — otherwise ReasonDrawer keeps
        // the first row's textarea text (its useState only seeds on
        // mount) and the reason composed for #A submits against #B.
        key: actionDraft.kind + ':' + actionDraft.row.id,
        title: actionDraft.kind === 'remove'
          ? `Force-cancel listing #${actionDraft.row.id} — ${actionDraft.row.itemName}`
          : `Dismiss ${actionDraft.row.reportCount} report${actionDraft.row.reportCount === 1 ? '' : 's'} on #${actionDraft.row.id}`,
        hint: actionDraft.kind === 'remove'
          ? 'Seller sees this reason in the listing-removed notification. Logged in the audit trail.'
          : 'Logged for audit. Keeps the listing live, clears the report queue.',
        initial: actionDraft.kind === 'remove'
          ? (actionDraft.row.topReasons?.[0] || 'Policy violation')
          : 'No policy violation',
        cta: actionDraft.kind === 'remove' ? 'Force-cancel listing' : 'Dismiss reports',
        busy,
        onCancel: () => setActionDraft(null),
        onSubmit: submitReportAction
      })
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
  // Batch 566 — broadcast notification form state.
  const [bcTitle, setBcTitle] = useState('');
  const [bcBody, setBcBody]   = useState('');
  const [bcPath, setBcPath]   = useState('');
  const [bcBusy, setBcBusy]   = useState(false);
  const [bcMsg, setBcMsg]     = useState('');
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
      // Pre-fix: silent on success — form cleared, list refreshed, but
      // no confirmation that the banner went live sitewide. Staff with
      // multiple similar entries had to scroll the list to confirm the
      // post landed. Surface a toast so the action reads as real.
      try {
        window.dispatchEvent(new CustomEvent('sb:toast', { detail: {
          text: hours
            ? `Banner posted — auto-expires in ${hours}h.`
            : 'Banner posted — live sitewide until manually deactivated.',
          kind: 'ok'
        }}));
      } catch (_) {}
    } finally { setBusy(false); }
  };
  const deactivate = async (id) => {
    const csrf = (document.cookie.match(/sbox_csrf=([^;]+)/) || [])[1];
    try {
      const r = await fetch(`/api/admin/announcements/${id}`, {
        method: 'DELETE',
        credentials: 'same-origin',
        headers: csrf ? { 'X-CSRF-Token': decodeURIComponent(csrf) } : {}
      });
      if (!r.ok) {
        // Pre-fix: bare fetch with no status check. A 401/403/500 left
        // the row visible AND `await load()` repainted it identically
        // — staff thought the banner was gone when it was still live
        // sitewide. Surface a real toast so the failure is obvious.
        const j = await r.json().catch(() => ({}));
        try {
          window.dispatchEvent(new CustomEvent('sb:toast', { detail: {
            text: `Could not deactivate banner — ${j.message || j.error || `HTTP ${r.status}`}`,
            kind: 'err'
          }}));
        } catch (_) {}
      } else {
        try {
          window.dispatchEvent(new CustomEvent('sb:toast', { detail: {
            text: 'Banner deactivated — no longer shown sitewide.',
            kind: 'ok'
          }}));
        } catch (_) {}
      }
    } catch (e) {
      try {
        window.dispatchEvent(new CustomEvent('sb:toast', { detail: {
          text: 'Could not reach the server — banner was not deactivated.',
          kind: 'err'
        }}));
      } catch (_) {}
    }
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
          'aria-label': 'Banner severity',
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
    // Batch 566 — broadcast notification form. Fan-out: one bell-row per
    // active user. Distinct from banner (which is a site-wide static
    // strip) — use this for things users should SEE when they next
    // open the site (new feature launch, policy update) instead of
    // passively scrolling past.
    h('div', { style: { padding: 16, background: 'var(--bg-elevated)', border: '1px solid var(--border)', borderRadius: 10, marginBottom: 18 } },
      h('div', { style: { fontSize: 12, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--text-muted)', marginBottom: 4 } }, '📢 Broadcast notification'),
      h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 10, lineHeight: 1.5 } },
        'Pushes one bell-icon notification to every non-banned user. Use for feature launches or TOS updates — for transient ops notices use the banner above instead.'),
      h('input', {
        className: 'price-input',
        style: { width: '100%', marginBottom: 8 },
        placeholder: 'Title (required, max 120 chars)',
        value: bcTitle,
        maxLength: 120,
        onChange: e => setBcTitle(e.target.value)
      }),
      h('textarea', {
        value: bcBody,
        onChange: e => setBcBody(e.target.value),
        maxLength: 500,
        placeholder: 'Body (optional, max 500 chars) — shown under the title in the bell dropdown.',
        style: { width: '100%', minHeight: 60, padding: 10, background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 6, color: 'var(--text-primary)', fontFamily: 'inherit', fontSize: 12, resize: 'vertical', marginBottom: 8 }
      }),
      h('input', {
        className: 'price-input',
        style: { width: '100%', marginBottom: 10 },
        placeholder: 'Deep-link path (optional, must start with /, e.g. /help)',
        value: bcPath,
        maxLength: 200,
        onChange: e => setBcPath(e.target.value)
      }),
      h('div', { style: { display: 'flex', gap: 8, alignItems: 'center' } },
        h('button', {
          className: 'btn btn-accent',
          disabled: bcBusy || !bcTitle.trim(),
          onClick: async () => {
            if (!confirm(`Send "${bcTitle.trim()}" to every active user? This fans out one notification per user and can't be undone.`)) return;
            setBcBusy(true); setBcMsg('');
            try {
              const { adminBroadcast } = await import('./api.js');
              const res = await adminBroadcast(bcTitle.trim(), bcBody.trim(), bcPath.trim());
              if (res && (res.error || res.code)) {
                setBcMsg(res.message || res.error || 'Broadcast failed');
                return;
              }
              setBcMsg(`✓ Sent to ${res.sent} user${res.sent === 1 ? '' : 's'} in ${res.batches} batch${res.batches === 1 ? '' : 'es'}`);
              setBcTitle(''); setBcBody(''); setBcPath('');
            } finally { setBcBusy(false); }
          }
        }, bcBusy ? 'Broadcasting…' : '📢 Send broadcast'),
        bcMsg && h('span', {
          style: { fontSize: 11, color: bcMsg.startsWith('✓') ? 'var(--green)' : 'var(--red)' }
        }, bcMsg)
      )
    ),
    live.length > 0 && h('div', { style: { padding: 12, background: 'var(--bg-card)', border: '1px solid var(--accent-border)', borderRadius: 8, marginBottom: 16 } },
      h('div', { style: { fontSize: 11, color: 'var(--accent)', fontWeight: 800, textTransform: 'uppercase', letterSpacing: '0.04em', marginBottom: 6 } }, 'LIVE NOW'),
      live.map(r => h('div', { key: r.id, style: { display: 'flex', gap: 12, padding: 6, alignItems: 'center' } },
        h(SeverityChip, { severity: r.severity }),
        h('span', { style: { flex: 1 } }, r.message),
        r.expiresAt && h('span', { style: { fontSize: 10, color: 'var(--text-muted)' } }, (() => {
          // timeAgo() is PAST-only — feeding it a future expiry rendered every
          // live banner as "ends Just now". Forward-relative instead. (audit P3)
          const d = Number(r.expiresAt) - Date.now();
          if (!(d > 0)) return 'ending now';
          const hh = Math.floor(d / 3600000), mm = Math.floor((d % 3600000) / 60000);
          return hh > 0 ? `ends in ${hh}h ${mm}m` : `ends in ${mm}m`;
        })()),
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
  // The announcement form posts `WARN` and AnnouncementService stores it
  // verbatim (ALLOWED_SEVERITY = INFO/WARN/CRITICAL). Key on `WARN` so a
  // WARN-severity banner gets the amber chip — previously the only key
  // was `WARNING`, so every WARN row fell through to the grey default.
  // `WARNING` kept as an alias in case any legacy row stored the long form.
  const amber = { bg: 'rgba(251,191,36,0.15)', fg: '#fbbf24', label: 'WARN' };
  const cfg = {
    INFO:    { bg: 'rgba(96,165,250,0.15)', fg: '#60a5fa', label: 'INFO' },
    WARN:    amber,
    WARNING: amber,
    CRITICAL:{ bg: 'rgba(248,113,113,0.15)', fg: '#f87171', label: 'CRIT' }
  }[s] || { bg: 'var(--bg-elevated)', fg: 'var(--text-muted)', label: s };
  return h('span', {
    style: {
      fontSize: 10, fontWeight: 800, padding: '2px 8px', borderRadius: 4,
      background: cfg.bg, color: cfg.fg, letterSpacing: 0.5,
      fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace", minWidth: 40, textAlign: 'center'
    }
  }, cfg.label);
}

// Fraud-signals triage — read-only rollup of the last 24h of audit rows.
// Groups suspicious patterns by severity so an admin can scan HIGH rows
// first and dismiss LOW noise. No write actions; the admin still makes
// ban/unban decisions manually from the Users tab.
// Batch 700 — admin fraud-triage: look up an API key by prefix and
// see the owner + label + scope + revoked state + last-used. Ops
// person pastes from a log line, gets the user id in one click.
function ApiKeyLookupPanel() {
  const [q, setQ] = useState('');
  const [rows, setRows] = useState([]);
  const [busy, setBusy] = useState(false);
  const [searched, setSearched] = useState(false);
  const doLookup = async () => {
    const prefix = (q || '').trim();
    if (prefix.length < 3) return;
    setBusy(true);
    try {
      const data = await adminApiKeyLookup(prefix);
      setRows(data || []);
      setSearched(true);
    } finally { setBusy(false); }
  };
  return h('div', { className: 'admin-card', style: { marginBottom: 16 } },
    h('div', { className: 'admin-card-title' }, 'API key lookup'),
    h('div', { className: 'admin-card-note' },
      'Paste a prefix fragment from a log line (e.g. ',
      h('code', null, 'sbx_live_abc12'),
      ') to identify the owner. Supports prefix matching — a truncated log line still resolves.'),
    h('div', { style: { display: 'flex', gap: 8, marginTop: 10, flexWrap: 'wrap' } },
      h('input', {
        className: 'price-input',
        style: { flex: 1, minWidth: 240, fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace", fontSize: 12 },
        placeholder: 'sbx_live_…',
        value: q,
        onChange: e => setQ(e.target.value),
        onKeyDown: e => { if (e.key === 'Enter') doLookup(); }
      }),
      h('button', {
        className: 'btn btn-accent',
        style: { padding: '6px 14px' },
        onClick: doLookup,
        disabled: busy || (q || '').trim().length < 3
      }, busy ? 'Searching…' : 'Lookup')
    ),
    searched && rows.length === 0 && h('div', {
      style: { fontSize: 12, color: 'var(--text-muted)', marginTop: 10 }
    }, 'No matching API keys. Check the prefix — search requires ≥ 3 chars.'),
    rows.length > 0 && h('table', { className: 'db-table', style: { marginTop: 10 } },
      h('thead', null, h('tr', null,
        h('th', null, 'ID'),
        h('th', null, 'Owner'),
        h('th', null, 'Label'),
        h('th', null, 'Scope'),
        h('th', null, 'Prefix'),
        h('th', null, 'Status'),
        h('th', null, 'Last Used'))),
      h('tbody', null, rows.map(k => h('tr', { key: k.id, className: 'db-row' },
        h('td', { className: 'db-rank' }, '#' + k.id),
        h('td', null, h('button', {
          type: 'button',
          // The link previously dead-ended (just preventDefault) and the
          // accent-blue underline made it look navigable. Copy the user id
          // to clipboard so an admin can paste it straight into the Users
          // tab search without re-typing from the row.
          onClick: async (e) => {
            e.preventDefault();
            // Pre-fix: clipboard.writeText was called blindly on a
            // possibly-undefined navigator.clipboard, so on insecure
            // contexts / blocked permissions it threw and the catch
            // surfaced a half-useful "User #42" toast without putting
            // anything on the clipboard. Now: prompt fallback when
            // the API is missing so admins can always copy by hand.
            try {
              if (navigator.clipboard?.writeText) {
                await navigator.clipboard.writeText(String(k.userId));
                toast(`User #${k.userId} copied to clipboard`, 'ok');
              } else {
                window.prompt('Copy this user id:', String(k.userId));
              }
            } catch (_) {
              window.prompt('Copy this user id:', String(k.userId));
            }
          },
          style: {
            background: 'transparent', border: 0, padding: 0,
            color: 'var(--accent)', cursor: 'pointer', fontFamily: 'inherit'
          },
          title: 'Copy user id ' + k.userId + ' to clipboard'
        }, 'user #' + k.userId)),
        h('td', null, k.label || '—'),
        h('td', null, h('span', {
          style: {
            fontSize: 10, fontWeight: 700, padding: '2px 6px', borderRadius: 4,
            background: k.scope === 'RO' ? 'rgba(34,197,94,0.15)' : 'rgba(250,204,21,0.15)',
            color:      k.scope === 'RO' ? 'var(--green, #22c55e)' : 'var(--yellow, #facc15)'
          }
        }, k.scope || 'RW')),
        h('td', { className: 'db-mono' }, k.publicPrefix + '…'),
        h('td', null, k.revoked
          ? h('span', { style: { fontSize: 10, color: 'var(--red)', fontWeight: 700 } }, 'REVOKED')
          : h('span', { style: { fontSize: 10, color: 'var(--green)', fontWeight: 700 } }, 'ACTIVE')),
        h('td', { style: { fontSize: 11, color: 'var(--text-muted)' } },
          k.lastUsedAt ? timeAgo(k.lastUsedAt) : 'Never')
      )))
    )
  );
}

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
  // Guard the initial load + autoRefresh tick with an `alive` flag so
  // closing the modal mid-fetch doesn't fire setRows/setLoading on an
  // unmounted component (React warns + we drop the in-flight result on
  // the floor anyway).
  useEffect(() => {
    let alive = true;
    (async () => {
      setLoading(true); setError('');
      try {
        const r = await adminFraudSignals();
        if (alive) setRows(Array.isArray(r) ? r : []);
      } catch (e) { if (alive) setError(e?.message || 'Failed to load'); }
      finally { if (alive) setLoading(false); }
    })();
    return () => { alive = false; };
  }, []);
  // Live-refresh loop — only runs when autoRefresh is on. 60 seconds
  // balances "see new signals quickly" against "don't hammer the
  // audit-log rollup query" (it scans the last 24h window).
  useEffect(() => {
    if (!autoRefresh) return;
    let alive = true;
    const tick = async () => {
      if (document.visibilityState !== 'visible') return;
      try {
        const r = await adminFraudSignals();
        if (alive) setRows(Array.isArray(r) ? r : []);
      } catch (_) {}
    };
    const id = setInterval(tick, 60_000);
    return () => { alive = false; clearInterval(id); };
  }, [autoRefresh]);

  const sevClass = (s) => s === 'HIGH' ? 'sev-high' : s === 'MED' ? 'sev-med' : 'sev-low';
  const visible = showReviewed ? rows : rows.filter(r => !reviewed.has(rowKey(r)));
  const hiddenCount = rows.length - visible.length;

  return h('div', { className: 'admin-tab-content' },
    // Batch 700 — API-key lookup helper. Paste a prefix from a log
    // line, get back every matching key with the owner id, label,
    // scope, revoked-flag, and last-used timestamp. Sits on the
    // Fraud tab because that's where an ops person is already when
    // they spot a suspicious log line.
    h(ApiKeyLookupPanel, null),
    h('div', { className: 'admin-card' },
      h('div', { className: 'admin-card-title' }, 'Fraud Signals (last 24h)'),
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
        // Batch 588 — CSV export for quarterly reporting / ops archive.
        // Same as the withdrawals/trades/tickets CSVs pattern. Signals
        // are computed on-demand so the CSV is a point-in-time snapshot.
        h('a', {
          className: 'btn btn-ghost',
          style: { padding: '6px 14px', border: '1px solid var(--border)' },
          href: '/api/admin/fraud.csv',
          title: 'Export the current fraud-signal rollup as CSV'
        }, '⇣ CSV'),
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
          'aria-pressed': showReviewed,
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
            h('div', { className: 'fraud-sev' },
              h('span', { className: `sev-dot ${sevClass(r.severity)}` }),
              ' ', r.severity),
            h('div', { className: 'fraud-body' },
              h('div', { className: 'fraud-type' }, r.type),
              h('div', { className: 'fraud-summary' }, r.summary),
              r.ip && h('div', { className: 'fraud-meta' }, 'IP: ', r.ip),
              r.userId && h('div', { className: 'fraud-meta' },
                'User: ',
                // Clickable — deep-links to /admin?tab=audit&subject=<id>
                // so the investigator can see the user's full audit tail
                // without hunting through the Users tab. The Audit tab
                // reads `subject` from the URL on mount (added in the
                // same batch) and pre-fills the filter.
                h('a', {
                  href: `/admin?tab=audit&subject=${r.userId}`,
                  style: { color: 'var(--accent)', textDecoration: 'underline', cursor: 'pointer' },
                  title: 'Open the audit-log tail for this user'
                }, r.userName || `#${r.userId}`)
              ),
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

// Chargeback queue (batch 462). Every DEPOSIT transaction Stripe has
// flagged as DISPUTED via `charge.dispute.created`. Admin actions live
// in the existing user/wallet panels — this tab is the inbox that says
// "go look at user #42 because their bank just clawed back $200".
function AdminDisputesTab() {
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState('');
  const [actionDraft, setActionDraft] = useState(null);
  const [actionBusy, setActionBusy]   = useState(false);
  const load = useCallback(async () => {
    setLoading(true); setErr('');
    try {
      const { adminDisputes } = await import('./api.js');
      const r = await adminDisputes();
      setRows(Array.isArray(r) ? r : []);
    } catch (e) { setErr(e?.message || 'Failed to load'); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { load(); }, [load]);

  const submitDisputeAction = async (reason) => {
    if (!actionDraft || actionBusy) return;
    const { kind, row } = actionDraft;
    setActionBusy(true);
    try {
      if (kind === 'clear') {
        const { adminClearDispute } = await import('./api.js');
        const res = await adminClearDispute(row.id, reason);
        if (res && (res.error || res.code)) {
          toast(res.message || res.error || 'Could not clear dispute', 'err');
          return;
        }
        toast(`Dispute #${row.id} cleared — withdrawal hold lifted.`);
      } else if (kind === 'freeze') {
        const res = await adminFreezeWallet(row.userId, reason);
        if (res.code || res.error) { toast(res.message || res.error, 'err'); return; }
        toast(res.noChange
          ? 'Already frozen.'
          : `🔒 Wallet frozen for ${row.userDisplayName || ('#' + row.userId)}`);
      }
      setActionDraft(null);
      load();
    } finally { setActionBusy(false); }
  };
  return h('div', { className: 'admin-tab-content' },
    h('div', { className: 'admin-card' },
      h('div', { className: 'admin-card-title' }, 'Stripe Chargebacks'),
      h('div', { className: 'admin-card-note' },
        "Deposit transactions Stripe has flagged as DISPUTED. The webhook " +
        "marks the row + notifies you in real time; this tab is your queue. " +
        "Investigate the user, decide if you need to ban + clawback the " +
        "credit (Users tab → Credit), and let the Stripe dispute resolve in " +
        "the dashboard."),
      h('div', { style: { display: 'flex', gap: 8, marginTop: 10 } },
        h('button', {
          className: 'btn btn-ghost', style: { padding: '6px 14px' },
          onClick: load, disabled: loading
        }, loading ? 'Loading…' : 'Refresh'),
        // Batch 578 — CSV export of the dispute queue for quarterly
        // reconciliation against Stripe dashboard exports.
        h('a', {
          className: 'btn btn-ghost',
          style: { padding: '6px 14px', border: '1px solid var(--border)' },
          href: '/api/admin/disputes.csv',
          title: 'Export the active-disputes queue as CSV'
        }, '⇣ CSV')
      ),
      err && h('div', { className: 'admin-error' }, err),
      !loading && rows.length === 0 && h('div', { className: 'empty-inline' },
        '✓ No active chargebacks. The webhook will populate this list when one fires.'),
      rows.length > 0 && h('table', { className: 'db-table', style: { marginTop: 14 } },
        h('thead', null, h('tr', null,
          h('th', null, 'Tx'),
          h('th', null, 'User'),
          h('th', { className: 'right' }, 'Amount'),
          h('th', null, 'Signals'),
          h('th', null, 'When'),
          h('th', null, 'Note'),
          h('th', null, 'Action')
        )),
        h('tbody', null,
          rows.map(r => h('tr', { key: r.id, className: 'db-row' },
            h('td', { className: 'db-mono' }, '#' + r.id),
            h('td', null,
              r.userDisplayName || ('user_' + r.userId),
              r.userBanned && h('span', {
                style: { marginLeft: 6, fontSize: 9, padding: '1px 5px', borderRadius: 3, background: 'var(--red-dim)', color: 'var(--red)', fontWeight: 800 }
              }, 'BANNED')
            ),
            h('td', { className: 'right db-mono accent' }, fmt(r.amount)),
            // Fraud-signal chips on disputes (batch 515). Same visual
            // pattern as the withdrawal queue — helps staff spot
            // repeat chargeback-offenders + fresh-account abuse.
            h('td', { style: { fontSize: 11, whiteSpace: 'nowrap' } }, (() => {
              const chips = [];
              if (r.userCreatedAt) {
                const ageDays = Math.floor((Date.now() - r.userCreatedAt) / 86400000);
                const young = ageDays < 7;
                chips.push(h('span', {
                  key: 'age',
                  title: 'Account age',
                  style: {
                    padding: '2px 6px', borderRadius: 4, marginRight: 4,
                    background: young ? 'rgba(251,191,36,0.15)' : 'var(--bg-elevated)',
                    color: young ? '#fbbf24' : 'var(--text-muted)',
                    border: '1px solid ' + (young ? 'rgba(251,191,36,0.3)' : 'var(--border)'),
                    fontWeight: young ? 700 : 500
                  }
                }, (ageDays < 1 ? '<1d' : ageDays + 'd')));
              }
              if ((r.lifetimeDisputes || 0) > 1) {
                chips.push(h('span', {
                  key: 'cb',
                  title: `${r.lifetimeDisputes} lifetime chargebacks — repeat offender`,
                  style: {
                    padding: '2px 6px', borderRadius: 4, marginRight: 4,
                    background: 'rgba(248,113,113,0.18)', color: '#fca5a5',
                    border: '1px solid rgba(248,113,113,0.4)', fontWeight: 800
                  }
                }, '⚠ ' + r.lifetimeDisputes + 'x'));
              }
              if (r.walletFrozen) {
                chips.push(h('span', {
                  key: 'frozen', title: 'Wallet frozen',
                  style: {
                    padding: '2px 6px', borderRadius: 4, marginRight: 4,
                    background: 'rgba(96,165,250,0.15)', color: '#60a5fa',
                    border: '1px solid rgba(96,165,250,0.3)', fontWeight: 700
                  }
                }, '🔒'));
              }
              if (r.userEmailVerified === false) {
                chips.push(h('span', {
                  key: 'noemail', title: 'Email NOT verified',
                  style: {
                    padding: '2px 6px', borderRadius: 4, marginRight: 4,
                    background: 'rgba(248,113,113,0.1)', color: '#fca5a5',
                    border: '1px solid rgba(248,113,113,0.3)', fontWeight: 700
                  }
                }, '✉✕'));
              }
              return chips.length === 0
                ? h('span', { style: { color: 'var(--text-muted)' } }, '—')
                : chips;
            })()),
            h('td', null, r.createdAt ? timeAgo(r.createdAt) : ''),
            h('td', { style: { fontSize: 11, color: 'var(--text-muted)' } }, r.description || '—'),
            h('td', null,
              // Clear-dispute (batch 467). Flips the row back to
              // COMPLETED so the user's withdrawal hold lifts. Used
              // when the cardholder dropped the dispute or we won it
              // via Stripe. Confirms before firing — destructive in
              // the sense that it un-blocks money movement.
              h('button', {
                className: 'btn btn-ghost',
                style: { padding: '4px 10px', fontSize: 11, border: '1px solid var(--border)' },
                title: 'Mark this dispute resolved — lifts the withdrawal hold for the user',
                'aria-haspopup': 'dialog',
                'aria-expanded': !!(actionDraft && actionDraft.kind === 'clear' && actionDraft.row.id === r.id),
                onClick: () => setActionDraft(
                  actionDraft && actionDraft.kind === 'clear' && actionDraft.row.id === r.id
                    ? null
                    : { kind: 'clear', row: r })
              }, '✓ Clear'),
              // Inline Freeze (batch 515). Lets staff freeze the wallet
              // directly from the dispute queue — one click instead of
              // bouncing to the Users tab + opening the detail drawer.
              // Disabled when already frozen or when we don't have the
              // owner user id (shouldn't happen but belt-and-braces).
              r.userId && !r.walletFrozen && h('button', {
                className: 'btn btn-ghost',
                style: { padding: '4px 10px', fontSize: 11, marginLeft: 6, border: '1px solid rgba(96,165,250,0.35)', color: '#60a5fa' },
                title: 'Freeze this wallet — refuses deposit/withdraw/purchase. Softer than a ban.',
                'aria-haspopup': 'dialog',
                'aria-expanded': !!(actionDraft && actionDraft.kind === 'freeze' && actionDraft.row.id === r.id),
                onClick: () => setActionDraft(
                  actionDraft && actionDraft.kind === 'freeze' && actionDraft.row.id === r.id
                    ? null
                    : { kind: 'freeze', row: r })
              }, '🔒 Freeze')
            )
          ))
        )
      ),
      actionDraft && h('div', { style: { marginTop: 14 } },
        h(ReasonDrawer, {
          // Remount when the drawer's target row/kind changes — see the
          // AdminReportedTab note. Without a key, the clear/freeze reason
          // composed for dispute #A leaks into the drawer for #B.
          key: actionDraft.kind + ':' + actionDraft.row.id,
          title: actionDraft.kind === 'clear'
            ? `Clear dispute #${actionDraft.row.id} — ${fmt(actionDraft.row.amount)}`
            : `Freeze wallet for ${actionDraft.row.userDisplayName || ('#' + actionDraft.row.userId)}`,
          hint: actionDraft.kind === 'clear'
            ? 'Logged in the audit trail. Lifts the withdrawal hold for the user.'
            : 'Reason is shown to the user and logged in the audit trail. Freezes deposit/withdraw/purchase.',
          initial: actionDraft.kind === 'clear'
            ? 'Stripe dispute resolved in our favour'
            : `Active chargeback #${actionDraft.row.id} under investigation`,
          cta: actionDraft.kind === 'clear' ? 'Clear dispute' : 'Freeze wallet',
          busy: actionBusy,
          onCancel: () => setActionDraft(null),
          onSubmit: submitDisputeAction
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
      h('div', { className: 'admin-card-title' }, 'Marketplace Simulator'),
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
              color: 'var(--text-primary)', borderRadius: 6, fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace"
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
  // Per-trade chat expansion — admins often need to read the
  // counterparty thread when investigating a dispute. State: trade id
  // of the currently-open chat + loaded messages keyed by trade id.
  const [openChat, setOpenChat] = useState(null);
  const [chatThreads, setChatThreads] = useState({});
  // Force-action ReasonDrawer state (batch 908). Replaces two
  // window.prompt() calls for force-release + force-cancel. The reason
  // is logged to audit AND surfaced to the user via notification, so
  // the drawer is the right UX for composing it.
  const [actionDraft, setActionDraft] = useState(null);  // { kind: 'release'|'cancel', row }
  const toggleChat = async (tradeId) => {
    if (openChat === tradeId) { setOpenChat(null); return; }
    setOpenChat(tradeId);
    const msgs = await fetchTradeMessages(tradeId);
    setChatThreads(prev => ({ ...prev, [tradeId]: msgs }));
  };
  const redactMessage = async (tradeId, msgId) => {
    if (!confirm(`Redact message ${msgId}? The row is hard-deleted and can't be recovered.`)) return;
    const res = await adminDeleteTradeMessage(msgId);
    if (res && (res.error || res.code)) { toast(res.message || res.error, 'err'); return; }
    const msgs = await fetchTradeMessages(tradeId);
    setChatThreads(prev => ({ ...prev, [tradeId]: msgs }));
    // Pre-fix: silent on success — staff saw the row vanish from chat but
    // had no confirmation that the audit-log write landed. Surface a toast
    // so admin actions consistently report outcome.
    toast(`Message #${msgId} redacted from trade #${tradeId}.`, 'ok');
  };

  const load = useCallback(async () => { setRows(null); setRows(await adminTrades(filter)); }, [filter]);
  useEffect(() => { load(); }, [load]);

  const submitTradeAction = async (reason) => {
    if (!actionDraft || busy) return;
    const { kind, row } = actionDraft;
    setBusy(true);
    try {
      if (kind === 'release') {
        const res = await adminReleaseTrade(row.id, reason);
        if (res.code || res.error) { toast(res.message || res.error, 'err'); return; }
        toast(`Trade #${row.id} force-released — seller paid out, user notified.`);
      } else {
        const res = await adminCancelTrade(row.id, reason);
        if (res.code || res.error) { toast(res.message || res.error, 'err'); return; }
        toast(`Trade #${row.id} force-cancelled — buyer refunded, user notified.`);
      }
      setActionDraft(null);
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
        'aria-pressed': oldestFirst,
        onClick: () => setOldestFirst(v => !v),
        title: oldestFirst ? 'Currently oldest-first — click to reset' : 'Sort oldest-first (tail of queue)'
      }, oldestFirst ? '↑ Oldest first' : '↓ Newest first'),
      // Batch 559 — CSV export honors the current state filter so the
      // download matches the visible queue. Server hard-caps at 5000
      // rows; a fuller history needs a tighter filter + re-download.
      h('a', {
        className: 'btn btn-ghost',
        style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11 },
        href: `/api/admin/trades.csv?state=${encodeURIComponent(filter)}`,
        title: `Export the ${filter.toLowerCase()} trades queue as a CSV`
      }, '⇣ CSV')
    ),
    display === null
      ? h('div', { className: 'spinner' })
      : display.length === 0
        ? h('div', { className: 'empty-inline' },
            h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'swap_horiz', size: 26 })),
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
            h('tbody', null, display.map(r => [
              h('tr', { key: r.id, className: 'db-row' },
                h('td', { className: 'db-rank' }, '#' + r.id),
                h('td', null, r.itemName || '—'),
                // Buyer + seller cells (batch 526). Now show name and
                // inline risk chips — chargeback count (red), fresh
                // account (amber), banned (red). Clicking a name row
                // still drops into the chat thread via the existing
                // row-click handler below.
                h('td', null,
                  h('div', { className: 'db-mono', style: { fontSize: 11 } }, r.buyerName || ('#' + (r.buyerUserId || '?'))),
                  h('div', { style: { fontSize: 10, marginTop: 2, whiteSpace: 'nowrap' } },
                    (() => {
                      const chips = [];
                      if (r.buyerCreatedAt) {
                        const ageDays = Math.floor((Date.now() - r.buyerCreatedAt) / 86400000);
                        if (ageDays < 7) chips.push(h('span', { key: 'a', style: { padding: '1px 5px', borderRadius: 3, marginRight: 3, background: 'rgba(251,191,36,0.15)', color: '#fbbf24', border: '1px solid rgba(251,191,36,0.3)', fontWeight: 700 } }, (ageDays < 1 ? '<1d' : ageDays + 'd')));
                      }
                      if ((r.buyerLifetimeDisputes || 0) > 0) chips.push(h('span', { key: 'cb', title: `${r.buyerLifetimeDisputes} lifetime chargebacks`, style: { padding: '1px 5px', borderRadius: 3, marginRight: 3, background: 'rgba(248,113,113,0.18)', color: '#fca5a5', border: '1px solid rgba(248,113,113,0.4)', fontWeight: 800 } }, '⚠ ' + r.buyerLifetimeDisputes + 'x'));
                      if (r.buyerBanned) chips.push(h('span', { key: 'b', style: { padding: '1px 5px', borderRadius: 3, marginRight: 3, background: 'rgba(248,113,113,0.2)', color: '#fca5a5', border: '1px solid rgba(248,113,113,0.45)', fontWeight: 800 } }, 'BAN'));
                      return chips;
                    })()
                  )
                ),
                h('td', null,
                  h('div', { className: 'db-mono', style: { fontSize: 11 } }, r.sellerName || (r.sellerUserId ? '#' + r.sellerUserId : 'system')),
                  h('div', { style: { fontSize: 10, marginTop: 2, whiteSpace: 'nowrap' } },
                    (() => {
                      const chips = [];
                      if (r.sellerCreatedAt) {
                        const ageDays = Math.floor((Date.now() - r.sellerCreatedAt) / 86400000);
                        if (ageDays < 7) chips.push(h('span', { key: 'a', style: { padding: '1px 5px', borderRadius: 3, marginRight: 3, background: 'rgba(251,191,36,0.15)', color: '#fbbf24', border: '1px solid rgba(251,191,36,0.3)', fontWeight: 700 } }, (ageDays < 1 ? '<1d' : ageDays + 'd')));
                      }
                      if ((r.sellerLifetimeDisputes || 0) > 0) chips.push(h('span', { key: 'cb', title: `${r.sellerLifetimeDisputes} lifetime chargebacks`, style: { padding: '1px 5px', borderRadius: 3, marginRight: 3, background: 'rgba(248,113,113,0.18)', color: '#fca5a5', border: '1px solid rgba(248,113,113,0.4)', fontWeight: 800 } }, '⚠ ' + r.sellerLifetimeDisputes + 'x'));
                      if (r.sellerBanned) chips.push(h('span', { key: 'b', style: { padding: '1px 5px', borderRadius: 3, marginRight: 3, background: 'rgba(248,113,113,0.2)', color: '#fca5a5', border: '1px solid rgba(248,113,113,0.45)', fontWeight: 800 } }, 'BAN'));
                      return chips;
                    })()
                  )
                ),
                h('td', { style: { fontSize: 10, fontWeight: 700 } }, (r.state || '').replace(/_/g, ' ')),
                h('td', { className: 'right db-mono accent' }, fmt(r.price)),
                h('td', { className: 'right', style: { fontSize: 11, color: 'var(--text-muted)' } }, timeAgo(r.updatedAt)),
                h('td', { className: 'right' },
                  h('div', { style: { display: 'flex', gap: 4, justifyContent: 'flex-end' } },
                    // Batch 781 — staff-side deep link to the Steam
                    // trade-offer URL when the seller captured one at
                    // Mark-Sent (batch 773). Dispute triage + refund
                    // decisions want a one-click path to the actual
                    // offer, not "search the seller's Steam offers
                    // inbox by date".
                    r.tradeOfferUrl && /^https:\/\/steamcommunity\.com\/tradeoffer\//.test(r.tradeOfferUrl) && h('a', {
                      href: r.tradeOfferUrl,
                      target: '_blank',
                      rel: 'noopener noreferrer',
                      className: 'btn btn-ghost',
                      style: { border: '1px solid var(--accent-border)', color: 'var(--accent)', padding: '5px 10px', fontSize: 11, textDecoration: 'none' },
                      title: 'Open the seller-captured Steam offer in a new tab — useful for dispute triage'
                    }, '↗ Steam offer'),
                    h('button', {
                      className: 'btn btn-ghost',
                      style: { border: '1px solid var(--border)', padding: '5px 10px', fontSize: 11 },
                      onClick: () => toggleChat(r.id),
                      title: 'Read (and moderate) the counterparty chat'
                    }, openChat === r.id ? '✕ Chat' : '💬 Chat'),
                    !['VERIFIED','CANCELLED'].includes(r.state) && h('button', {
                      className: 'buy-btn',
                      disabled: busy,
                      'aria-haspopup': 'dialog',
                      'aria-expanded': !!(actionDraft && actionDraft.kind === 'release' && actionDraft.row.id === r.id),
                      onClick: () => setActionDraft(
                        actionDraft && actionDraft.kind === 'release' && actionDraft.row.id === r.id
                          ? null
                          : { kind: 'release', row: r }),
                      title: 'Force-release funds to seller'
                    }, 'Release'),
                    !['VERIFIED','CANCELLED'].includes(r.state) && h('button', {
                      className: 'btn btn-ghost',
                      style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '5px 10px', fontSize: 11 },
                      disabled: busy,
                      'aria-haspopup': 'dialog',
                      'aria-expanded': !!(actionDraft && actionDraft.kind === 'cancel' && actionDraft.row.id === r.id),
                      onClick: () => setActionDraft(
                        actionDraft && actionDraft.kind === 'cancel' && actionDraft.row.id === r.id
                          ? null
                          : { kind: 'cancel', row: r }),
                      title: 'Force-cancel and refund the buyer'
                    }, 'Cancel')
                  )
                )
              ),
              openChat === r.id && h('tr', { key: r.id + '-chat' },
                h('td', { colSpan: 8, style: { padding: 12, background: 'var(--bg-elevated)', borderBottom: '1px solid var(--border)' } },
                  // `undefined` = fetch still in flight; `[]` = loaded-empty.
                  // Distinguishing the two stops the panel flashing "No
                  // messages in this trade." before the request resolves.
                  chatThreads[r.id] === undefined
                    ? h('div', { className: 'spinner' })
                    : (chatThreads[r.id] || []).length === 0
                    ? h('div', { style: { fontSize: 12, color: 'var(--text-muted)', textAlign: 'center', padding: 8 } },
                        'No messages in this trade.')
                    : h('div', { style: { display: 'flex', flexDirection: 'column', gap: 6, maxHeight: 260, overflowY: 'auto' } },
                        chatThreads[r.id].map(m => h('div', {
                          key: m.id,
                          style: {
                            display: 'flex', gap: 8, alignItems: 'flex-start',
                            padding: '6px 10px',
                            background: 'var(--bg-card)',
                            border: '1px solid var(--border)',
                            borderRadius: 6,
                            fontSize: 12
                          }
                        },
                          h('div', { style: { minWidth: 70, fontSize: 10, color: 'var(--text-muted)' } },
                            '#' + m.senderUserId),
                          // Redacted rows show the placeholder to staff too —
                          // the original text is gone post-redaction (body column
                          // cleared on soft-redact, batch 349). If staff wants
                          // audit context they check the audit log entry.
                          m.redactedAt
                            ? h('div', {
                                style: {
                                  flex: 1,
                                  color: 'var(--text-muted)',
                                  fontStyle: 'italic',
                                  fontSize: 11
                                },
                                title: 'Redacted ' + new Date(m.redactedAt).toLocaleString()
                              }, '🛡 Message removed by moderators')
                            : h('div', { style: { flex: 1, whiteSpace: 'pre-wrap', wordBreak: 'break-word', color: 'var(--text-primary)' } }, m.body),
                          h('div', { style: { fontSize: 10, color: 'var(--text-muted)' } }, timeAgo(m.createdAt)),
                          // Redact button hidden once the message is already
                          // redacted — no point offering the action twice.
                          !m.redactedAt && h('button', {
                            className: 'btn btn-ghost',
                            style: { padding: '2px 8px', fontSize: 10, border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)' },
                            onClick: () => redactMessage(r.id, m.id),
                            title: 'Soft-redact this message (body cleared, row stays, audit logged)'
                          }, 'Redact')
                        ))
                      )
                )
              )
            ]).flat())
          ),
    actionDraft && h('div', { style: { marginTop: 14 } },
      h(ReasonDrawer, {
        // Remount on target/kind change — clicking Release/Cancel on a
        // different trade row while the drawer is open would otherwise
        // keep the reason typed for the previous trade. Force-release /
        // force-cancel move real money, so a stale reason is a genuine
        // safety hazard, not just cosmetic.
        key: actionDraft.kind + ':' + actionDraft.row.id,
        title: actionDraft.kind === 'release'
          ? `Force-release trade #${actionDraft.row.id}${actionDraft.row.itemName ? ' — ' + actionDraft.row.itemName : ''}`
          : `Force-cancel trade #${actionDraft.row.id}${actionDraft.row.itemName ? ' — ' + actionDraft.row.itemName : ''}`,
        hint: actionDraft.kind === 'release'
          ? 'Seller gets paid; buyer receives a notification with this reason. Logged in the audit trail.'
          : 'Buyer is refunded; both parties receive a notification with this reason. Logged in the audit trail.',
        initial: '',
        cta: actionDraft.kind === 'release' ? 'Force-release trade' : 'Force-cancel trade',
        busy,
        onCancel: () => setActionDraft(null),
        onSubmit: submitTradeAction
      })
    )
  );
}

function AdminAuditTab() {
  const [rows, setRows] = useState(null);
  // Pre-fill subject + event from the URL so fraud-signal rows (and
  // any future deep-link) can jump straight to a filtered audit view.
  // Falls back to an empty filter if no ?subject=/?event= is present.
  const initialFilter = (() => {
    try {
      const p = new URLSearchParams(window.location.search);
      return {
        event:   p.get('event')   || '',
        actor:   p.get('actor')   || '',
        subject: p.get('subject') || '',
        since:   p.get('since')   || ''
      };
    } catch (_) { return { event: '', actor: '', subject: '', since: '' }; }
  })();
  const [filter, setFilter] = useState(initialFilter);
  // Free-text filter applied to the already-fetched rows so the admin can
  // narrow down by summary contents without a server round-trip. Matches
  // against summary + actor/subject names. Backend-side filters (event,
  // actor id, subject id) still drive the fetch so we don't scan rows we
  // don't need.
  const [textSearch, setTextSearch] = useState('');
  const load = useCallback(async () => {
    setRows(null);
    // Batch 556 — translate the chip's hours-ago value into a wall-clock
    // millis `since` value at fetch time. Done here (not in the chip's
    // onClick) so a long-lived filter doesn't freeze a stale horizon.
    const sinceMs = filter.since
      ? (Date.now() - (parseInt(filter.since, 10) * 3_600_000))
      : null;
    setRows(await adminAudit({
      event:   filter.event   || null,
      actor:   filter.actor   || null,
      subject: filter.subject || null,
      since:   sinceMs || null
    }));
  }, [filter]);
  useEffect(() => { load(); }, [load]);

  const EVENTS = ['','DEPOSIT_COMPLETE','WITHDRAW_REQUESTED','WITHDRAW_APPROVED','WITHDRAW_REJECTED',
                  'WITHDRAW_SELF_CANCELLED','REFUND_ISSUED',
                  'CHARGEBACK_OPENED','DISPUTE_CLEARED',
                  'LISTING_PURCHASED','LISTING_FORCE_CANCELLED','ITEM_EDITED',
                  'USER_BANNED','USER_UNBANNED','USER_SIGN_IN','SESSION_LOGOUT_ALL','USER_FORCE_LOGOUT',
                  'ADMIN_GRANTED','ADMIN_REVOKED','CSR_GRANTED','CSR_REVOKED','TWOFA_RESET',
                  'CSR_CREDIT','ADMIN_CREDIT','API_KEY_MINTED','API_KEY_REVOKED',
                  'ADMIN_NOTES_UPDATED',
                  'ANNOUNCEMENT_CREATED','ANNOUNCEMENT_DEACTIVATED',
                  'TRADE_DISPUTED','TRADE_CANCELLED','TRADE_AUTO_CANCELLED','TRADE_AUTO_RELEASED',
                  'TRADE_FORCE_RELEASED','TRADE_FORCE_CANCELLED','TRADE_MESSAGE_DELETED',
                  'REVIEW_DELETED_STAFF'];
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
      h('select', { className: 'sort-select', 'aria-label': 'Filter by audit event', value: filter.event, onChange: e => setFilter(f => ({ ...f, event: e.target.value })) },
        EVENTS.map(ev => h('option', { key: ev || 'all', value: ev }, ev || 'All events'))
      ),
      h('input', { className: 'price-input', style: { width: 130 }, placeholder: 'Actor user #id', value: filter.actor, onChange: e => setFilter(f => ({ ...f, actor: e.target.value })) }),
      h('input', { className: 'price-input', style: { width: 130 }, placeholder: 'Subject user #id', value: filter.subject, onChange: e => setFilter(f => ({ ...f, subject: e.target.value })) }),
      // Batch 556 — date-horizon chips. Each chip stores its hours-ago
      // value; the load() callback translates to a wall-clock floor at
      // fetch time. "All time" clears the bound so the server returns
      // the full 500-row recent window.
      h('div', { style: { display: 'flex', gap: 4 } },
        [{ id: '',     label: 'All time' },
         { id: '24',   label: '24h' },
         { id: '168',  label: '7d' },
         { id: '720',  label: '30d' }].map(opt => h('button', {
          key: opt.id || 'all',
          className: `wallet-tx-filter-chip ${filter.since === opt.id ? 'active' : ''}`,
          'aria-pressed': filter.since === opt.id,
          onClick: () => setFilter(f => ({ ...f, since: opt.id })),
          title: opt.id ? `Only events in the last ${opt.label}` : 'Full 500-row recent window'
        }, opt.label))
      ),
      h('input', { className: 'price-input', style: { flex: 1, minWidth: 140 }, placeholder: '🔎 Search summary / names…', value: textSearch, onChange: e => setTextSearch(e.target.value) }),
      h('button', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11 }, onClick: load }, 'Refresh'),
      // CSV export — honors the current filter selection so the download
      // matches what the admin is looking at.
      (() => {
        const qs = new URLSearchParams();
        if (filter.event)   qs.set('event', filter.event);
        if (filter.actor)   qs.set('actor', filter.actor);
        if (filter.subject) qs.set('subject', filter.subject);
        // Batch 556 — forward the date horizon to the CSV endpoint so
        // the download matches what's on screen. Compute the millis
        // floor at click time so a stale tab doesn't download a
        // frozen window.
        if (filter.since) {
          const sinceMs = Date.now() - (parseInt(filter.since, 10) * 3_600_000);
          qs.set('since', sinceMs);
        }
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
        ? h('div', { className: 'empty-inline' }, h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'receipt_long', size: 26 })),
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
  // Stats auto-refresh (batch 408). Previously fetched once on mount so
  // an admin staring at the dashboard would see stale numbers forever.
  // 30s matches the recent-activity cadence below. Cheap: the endpoint
  // is a handful of indexed aggregates.
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const s = await adminStats();
        if (alive) setStats(s);
      } catch (_) {}
    };
    load();
    // Batch 806 — visibility-aware poll.
    const id = setInterval(() => { if (!document.hidden) load(); }, 30_000);
    const onVis = () => { if (!document.hidden) load(); };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      alive = false; clearInterval(id);
      document.removeEventListener('visibilitychange', onVis);
    };
  }, []);
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const rows = await adminAudit({});
        if (alive) setRecent((Array.isArray(rows) ? rows : []).slice(0, 10));
      } catch (_) {}
    };
    load();
    // Batch 806 — visibility-aware poll.
    const id = setInterval(() => { if (!document.hidden) load(); }, 30_000);
    const onVis = () => { if (!document.hidden) load(); };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      alive = false; clearInterval(id);
      document.removeEventListener('visibilitychange', onVis);
    };
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
      onKeyDown: navTarget && onNavTab
        ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onNavTab(navTarget); } }
        : null,
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
      Stat('Platform fees 7d',     fmt(stats.fees7d || 0), 'accent'),
      Stat('Platform fees 30d',    fmt(stats.fees30d || 0), 'accent'),
      Stat('Pending Withdrawals',  `${stats.pendingWithdrawals || 0} · ${fmt(stats.pendingWithdrawalsAmount || 0)}`, 'yellow',
           null, 'withdrawals'),
      Stat('Open Tickets',         Number(stats.openTickets || 0), null, null, 'tickets'),
      Stat('Banned Users',         Number(stats.bannedUsers || 0), stats.bannedUsers > 0 ? 'red' : '', null, 'users'),
      // Trade-state probes — urgency-ranked by colour. Disputes get red
      // (urgent CSR intervention), pending-buyer-confirm amber (auto-
      // sweeper handles it but staff may want to force-release early),
      // seller-side pendings stay neutral.
      Stat('Open Disputes',        Number(stats.disputedTrades || 0),
           stats.disputedTrades > 0 ? 'red' : '', null, 'trades'),
      Stat('Escrow · awaiting seller accept', Number(stats.pendingSellerAccept || 0),
           null, null, 'trades'),
      Stat('Escrow · awaiting seller send',   Number(stats.pendingSellerSend || 0),
           null, null, 'trades'),
      Stat('Escrow · awaiting buyer confirm', Number(stats.pendingBuyerConfirm || 0),
           stats.pendingBuyerConfirm > 0 ? 'yellow' : '', null, 'trades'),
      // Active chargebacks (batch 486) — money-at-risk; each row drives
      // a user's withdrawal hold. Click jumps to the Disputes tab.
      Stat('Active Chargebacks',   Number(stats.activeChargebacks || 0),
           stats.activeChargebacks > 0 ? 'red' : '', null, 'disputes'),
      // 30-day chargeback trend (batch 685) — count of DISPUTED deposits
      // opened in the last 30 days, regardless of whether staff cleared
      // them. A spike here is a card-fraud surge even when the active
      // count stays low. Click jumps to Disputes for drill-down.
      Stat('Chargebacks · 30d',    Number(stats.chargebacks30d || 0),
           stats.chargebacks30d > 5 ? 'yellow' : '', null, 'disputes')
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
  const [rejectRow, setRejectRow] = useState(null);
  const load = useCallback(async () => {
    setRows(null);
    setRows(await adminWithdrawals(filter));
  }, [filter]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => { setRejectRow(null); }, [filter]);

  const approve = async (row) => {
    if (busy) return;
    // `who` resolution shared by the confirm + the success toast — the
    // shape from `adminWithdrawals` carries `ownerDisplayName` /
    // `ownerUserId` / `walletUsername` (verified against
    // AdminController#withdrawals.csv columns).
    const who = row.ownerDisplayName ? `@${row.ownerDisplayName}`
              : row.walletUsername ? `@${row.walletUsername}`
              : row.ownerUserId ? `user #${row.ownerUserId}`
              : 'wallet';
    // Confirm before releasing real money — reject returns funds to the
    // wallet and is reversible, but approve pays out and is not. Spell
    // out amount + recipient so the admin can catch a wrong-row click.
    if (!confirm(`Approve withdrawal #${row.id}?\n\nThis pays out ${fmt(row.amount)} to ${who}. Withdrawals cannot be reversed once approved.`)) return;
    const ref = prompt('Stripe/Connect payout reference (optional):', row.destination || '') || '';
    setBusy(true);
    try {
      const res = await adminApproveWithdrawal(row.id, ref);
      if (res.code || res.error) { toast(res.message || res.error, 'err'); return; }
      // Pre-fix: approve was silent on success while reject toasted. The
      // sibling reject path (line 1952) confirms with amount + user
      // context, so an admin processing a queue could tell which click
      // landed. Approve was the inconsistent outlier — same toast pattern
      // applied for symmetry.
      toast(`✓ Approved withdrawal #${row.id} — ${fmt(row.amount)} to ${who}.`, 'ok');
      await load();
    } finally { setBusy(false); }
  };
  const submitReject = async (reason) => {
    if (!rejectRow || busy) return;
    setBusy(true);
    try {
      const res = await adminRejectWithdrawal(rejectRow.id, reason);
      if (res.code || res.error) { toast(res.message || res.error, 'err'); return; }
      toast(`Rejected withdrawal #${rejectRow.id} — ${fmt(rejectRow.amount)} returned to wallet`);
      setRejectRow(null);
      await load();
    } finally { setBusy(false); }
  };

  return h('div', { className: 'profile-panel' },
    h('div', { style: { display: 'flex', gap: 10, marginBottom: 14 } },
      ['PENDING','COMPLETED','FAILED'].map(f =>
        h('button', { key: f, className: `offer-tab ${filter === f ? 'active' : ''}`, onClick: () => setFilter(f) }, f)
      ),
      h('div', { style: { flex: 1 } }),
      // Batch 577 — CSV export with the current status filter. Matches
      // the pattern used by trades.csv / tickets.csv / audit.csv so
      // ops can download + diff in Excel.
      h('a', {
        className: 'btn btn-ghost',
        style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11 },
        href: `/api/admin/withdrawals.csv?status=${encodeURIComponent(filter)}`,
        title: `Export the ${filter.toLowerCase()} withdrawals as a CSV`
      }, '⇣ CSV'),
      h('button', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11 }, onClick: load }, 'Refresh')
    ),
    rows === null
      ? h('div', { className: 'spinner' })
      : rows.length === 0
        ? h('div', { className: 'empty-inline' },
            h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'payments', size: 26 })),
            h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, `No ${filter.toLowerCase()} withdrawals.`))
        : h('table', { className: 'db-table' },
            h('thead', null, h('tr', null,
              h('th', null, 'ID'),
              h('th', null, 'Wallet'),
              h('th', null, 'Destination'),
              h('th', { className: 'right' }, 'Amount'),
              h('th', null, 'Signals'),
              h('th', null, 'Created'),
              h('th', { className: 'right' }, 'Action'))),
            h('tbody', null, rows.map(r => h('tr', { key: r.id, className: 'db-row' },
              h('td', { className: 'db-rank' }, '#' + r.id),
              h('td', { className: 'db-mono' }, r.walletUsername || ('wallet ' + r.walletId)),
              h('td', { style: { fontSize: 11, color: 'var(--text-muted)', maxWidth: 220, overflow: 'hidden', textOverflow: 'ellipsis' } }, r.destination || '—'),
              h('td', { className: 'right db-mono accent' }, fmt(r.amount)),
              // Fraud-signal cell (batch 514). Chips compress the three
              // key review signals — account age, active + lifetime
              // chargebacks, wallet-frozen state, email-verified state —
              // into one glance per row so finance can triage without
              // opening the user detail drawer.
              h('td', { style: { fontSize: 11, whiteSpace: 'nowrap' } }, (() => {
                const chips = [];
                if (r.ownerCreatedAt) {
                  const ageDays = Math.floor((Date.now() - r.ownerCreatedAt) / 86400000);
                  const young = ageDays < 7;
                  chips.push(h('span', {
                    key: 'age',
                    title: 'Account age',
                    style: {
                      padding: '2px 6px', borderRadius: 4, marginRight: 4,
                      background: young ? 'rgba(251,191,36,0.15)' : 'var(--bg-elevated)',
                      color: young ? '#fbbf24' : 'var(--text-muted)',
                      border: '1px solid ' + (young ? 'rgba(251,191,36,0.3)' : 'var(--border)'),
                      fontWeight: young ? 700 : 500
                    }
                  }, (ageDays < 1 ? '<1d' : ageDays + 'd')));
                }
                if ((r.activeDisputes || 0) > 0 || (r.lifetimeDisputes || 0) > 0) {
                  const active = r.activeDisputes || 0;
                  chips.push(h('span', {
                    key: 'cb',
                    title: `${active} active + ${r.lifetimeDisputes || 0} lifetime chargebacks`,
                    style: {
                      padding: '2px 6px', borderRadius: 4, marginRight: 4,
                      background: active > 0 ? 'rgba(248,113,113,0.15)' : 'rgba(251,191,36,0.10)',
                      color: active > 0 ? '#fca5a5' : '#fbbf24',
                      border: '1px solid ' + (active > 0 ? 'rgba(248,113,113,0.35)' : 'rgba(251,191,36,0.3)'),
                      fontWeight: 700
                    }
                  }, '⚠ ' + (active > 0 ? active + ' active' : (r.lifetimeDisputes + ' prior'))));
                }
                if (r.walletFrozen) {
                  chips.push(h('span', {
                    key: 'frozen', title: 'Wallet frozen',
                    style: {
                      padding: '2px 6px', borderRadius: 4, marginRight: 4,
                      background: 'rgba(96,165,250,0.15)', color: '#60a5fa',
                      border: '1px solid rgba(96,165,250,0.3)', fontWeight: 700
                    }
                  }, '🔒'));
                }
                if (r.ownerEmailVerified === false) {
                  chips.push(h('span', {
                    key: 'noemail', title: 'Email NOT verified',
                    style: {
                      padding: '2px 6px', borderRadius: 4, marginRight: 4,
                      background: 'rgba(248,113,113,0.1)', color: '#fca5a5',
                      border: '1px solid rgba(248,113,113,0.3)', fontWeight: 700
                    }
                  }, '✉✕'));
                }
                return chips.length === 0
                  ? h('span', { style: { color: 'var(--text-muted)' } }, '—')
                  : chips;
              })()),
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
                    'aria-haspopup': 'dialog',
                    'aria-expanded': rejectRow && rejectRow.id === r.id,
                    onClick: () => setRejectRow(rejectRow && rejectRow.id === r.id ? null : r)
                  }, 'Reject')
                )
              )
            )))
          ),
    rejectRow && h('div', { style: { marginTop: 14 } },
      h(ReasonDrawer, {
        // Remount when the targeted withdrawal changes — clicking Reject
        // on a different row while the drawer is open would otherwise
        // submit the reason typed for the previous withdrawal.
        key: 'reject:' + rejectRow.id,
        title: `Reject withdrawal #${rejectRow.id} — ${fmt(rejectRow.amount)}`,
        hint: 'Reason is shown to the user and logged in the audit trail. Funds return to their wallet.',
        initial: '',
        cta: 'Reject withdrawal',
        busy,
        onCancel: () => setRejectRow(null),
        onSubmit: submitReject
      })
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
      // Confirm the save — the drawer just closes otherwise, leaving the
      // admin to eyeball the row to tell whether the PUT actually landed.
      toast(`Catalogue updated — ${fresh.name || editing.name} saved.`, 'ok');
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
              : h('span', { style: { fontSize: 16 } }, ({Hats:'◈',Jackets:'▲',Shirts:'■',Pants:'▮',Gloves:'◉',Boots:'▼',Accessories:'◆',Workshop:'❖'})[it.category] || '—'),
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
              'aria-label': 'Item rarity',
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
          'aria-pressed': picked === t.id,
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
  // Batch 765 — role/banned filter now goes server-side so a user
  // searching for "all admins" gets every admin, not just the ones
  // that happened to be in the first 200-row page. When the filter
  // chip is ALL, we skip the role param entirely (the fast findAll
  // path on the service keeps the cold open snappy). BANNED is a
  // special case — it's a status filter, not a role — so we map it
  // to `banned=true` without constraining role.
  const load = useCallback(async () => {
    const opts = { search };
    if (roleFilter === 'BANNED') {
      opts.banned = true;
    } else if (roleFilter !== 'ALL') {
      opts.role = roleFilter;
      opts.banned = false;  // role chips mean "active of this role" — hide banned
    }
    setRows(await adminUsers(opts));
  }, [search, roleFilter]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    if (!detailUser) { setDetailData(null); setNotesDraft(''); return; }
    let alive = true;
    (async () => {
      try {
        // Public stall + reviews + staff notes for a quick at-a-glance card.
        // Batch 549 — pull the consolidated summary alongside the
        // existing stall + reviews calls so the drawer shows wallet
        // balance, dispute counts, 2FA state without bouncing tabs.
        // Batch 568 — also pull the recent transactions so fraud
        // triage can scan the money trail inline.
        // Batch 579 — pull the user's audit-log timeline (subject
        // filter) so staff sees a chronological activity feed inline
        // instead of bouncing to the Audit tab.
        // Every leg has a .catch fallback — without one on the two raw
        // fetches, a network failure rejected the whole Promise.all, the
        // outer catch swallowed it, and detailData stayed null → the
        // drawer spinner hung forever with no way out but closing it.
        const [stall, reviews, notes, summary, transactions, activity] = await Promise.all([
          fetch(`/api/listings/stall/${detailUser.id}`, { credentials: 'same-origin' }).then(r => r.ok ? r.json() : null).catch(() => null),
          fetch(`/api/reviews/user/${detailUser.id}`,   { credentials: 'same-origin' }).then(r => r.ok ? r.json() : []).catch(() => []),
          adminReadNotes(detailUser.id).catch(() => null),
          adminUserSummary(detailUser.id),
          adminUserTransactions(detailUser.id),
          adminAudit({ subject: detailUser.id }).catch(() => [])
        ]);
        if (alive) {
          setDetailData({
            stall, reviews: Array.isArray(reviews) ? reviews : [],
            summary, transactions,
            activity: Array.isArray(activity) ? activity.slice(0, 20) : []
          });
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
    const label = banTarget.displayName || banTarget.steamId64;
    setBusy(true);
    try {
      const res = await adminBanUser(banTarget.id, reason.trim());
      if (res.code || res.error) { toast(res.message || res.error, 'err'); return; }
      setBanTarget(null);
      await load();
      // Batch 944 — confirm toast on destructive role changes. Ban
      // cancels all listings/offers/trades + kicks all sessions, so
      // "did it go through?" is the first thing an admin checks.
      toast(`${label} banned — listings cancelled, sessions wiped, audit logged.`, 'ok');
    } finally { setBusy(false); }
  };
  const doUnban = async (u) => {
    if (!confirm(`Unban ${u.displayName || u.steamId64}?`)) return;
    const label = u.displayName || u.steamId64;
    setBusy(true);
    try {
      const res = await adminUnbanUser(u.id);
      if (res && res.error) { toast(res.error, 'err'); return; }
      await load();
      toast(`${label} unbanned — they can sign in again.`, 'ok');
    } finally { setBusy(false); }
  };
  const doForceLogout = async (u) => {
    if (!confirm(`Revoke every live session for ${u.displayName || u.steamId64}?\n\nThey'll have to sign in again on every device. The account is NOT banned — this is just a session wipe.`)) return;
    setBusy(true);
    try {
      const res = await adminForceLogout(u.id);
      if (res && (res.code || res.error)) { toast(res.message || res.error, 'err'); return; }
      toast(`Sessions revoked for ${u.displayName || 'user'}`, 'ok');
    } finally { setBusy(false); }
  };
  const doGrant = async (u) => {
    if (!confirm(`Grant ADMIN role to ${u.displayName || u.steamId64}?`)) return;
    const label = u.displayName || u.steamId64;
    setBusy(true);
    try {
      const res = await adminGrant(u.id);
      if (res && res.error) { toast(res.error, 'err'); return; }
      await load();
      // Batch 943 — confirm role change. Silent success meant an admin
      // granting role at the end of a long shift had to reload the row
      // to verify the promotion landed.
      toast(`${label} granted ADMIN — action logged in audit trail.`, 'ok');
    } finally { setBusy(false); }
  };
  const doRevoke = async (u) => {
    if (!confirm(`Revoke ADMIN role from ${u.displayName || u.steamId64}?`)) return;
    const label = u.displayName || u.steamId64;
    setBusy(true);
    try {
      const res = await adminRevoke(u.id);
      if (res.code || res.error) { toast(res.message || res.error, 'err'); return; }
      await load();
      toast(`ADMIN role revoked from ${label} — they now have USER-level access.`, 'ok');
    } finally { setBusy(false); }
  };
  const doGrantCsr = async (u) => {
    if (!confirm(`Grant CSR role to ${u.displayName || u.steamId64}?\n\nThey'll see the 🎧 Customer Service panel and can handle tickets + issue small goodwill credits.`)) return;
    const label = u.displayName || u.steamId64;
    setBusy(true);
    try {
      const res = await adminGrantCsr(u.id);
      if (res.code || res.error) { toast(res.message || res.error, 'err'); return; }
      await load();
      toast(`${label} granted CSR — they can now handle tickets and issue goodwill credits.`, 'ok');
    } finally { setBusy(false); }
  };
  const doRevokeCsr = async (u) => {
    if (!confirm(`Revoke CSR role from ${u.displayName || u.steamId64}?`)) return;
    const label = u.displayName || u.steamId64;
    setBusy(true);
    try {
      const res = await adminRevokeCsr(u.id);
      if (res.code || res.error) { toast(res.message || res.error, 'err'); return; }
      await load();
      toast(`CSR role revoked from ${label} — they now have USER-level access.`, 'ok');
    } finally { setBusy(false); }
  };
  const doCredit = async (u) => {
    const label = u.displayName || u.steamId64;
    const amtStr = prompt(`Adjust wallet for ${label} — positive credits, negative debits ($):`, '');
    if (!amtStr) return;
    const amt = parseFloat(amtStr);
    if (isNaN(amt)) { toast('Enter a number', 'err'); return; }
    if (amt === 0) { toast('Amount must be non-zero', 'err'); return; }
    const note = prompt('Note (audit trail):', '');
    if (note == null) return;
    // Confirm before moving money — a fat-fingered amount debits a user
    // instantly otherwise. Spell out direction + amount + target so the
    // admin can catch a mistyped value before it lands in the ledger.
    const verb = amt >= 0 ? 'CREDIT' : 'DEBIT';
    if (!confirm(`${verb} ${fmt(Math.abs(amt))} ${amt >= 0 ? 'to' : 'from'} ${label}'s wallet?\n\nThis adjusts the balance immediately and is logged in the audit trail.`)) return;
    setBusy(true);
    try {
      const res = await adminCreditWallet(u.id, amt, note);
      if (res.code || res.error) { toast(res.message || res.error, 'err'); return; }
      // Pre-fix: the user-list table row binds to `r.walletBalance`, but
      // doCredit didn't re-fetch the list — so the column kept showing the
      // pre-adjustment number. The toast confirmed the new balance, then
      // the row contradicted it. Refresh keeps both surfaces in sync.
      await load();
      const direction = amt >= 0 ? 'credited' : 'debited';
      toast(`${label} ${direction} ${fmt(Math.abs(amt))} — new balance ${fmt(res.newBalance)}.`, 'ok');
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
          'aria-pressed': roleFilter === opt.id,
          // Batch 765 — chips are always clickable. Previously the
          // disabled flag looked at count===0 (a client-side computation
          // over the loaded rows), which became misleading after we
          // moved the filter server-side: if the user picked ADMIN, all
          // counts for other kinds dropped to 0 in the loaded set, so
          // those chips disabled themselves. Clicking re-fetches with
          // the new filter, which is the actual source of truth.
          onClick: () => setRoleFilter(opt.id)
        }, roleFilter === opt.id ? opt.label : `${opt.label} · ${count[opt.id]}`))
      );
    })(),
    (() => {
      // Batch 765 — the filter now runs server-side; rows already
      // represents the filter result. Keep the variable for readability
      // below + defensive re-filter in case role goes stale between a
      // refetch and a chip click.
      const visibleRows = rows === null ? null : (roleFilter === 'ALL'
        ? rows
        : roleFilter === 'BANNED' ? rows.filter(r => r.banned)
        : rows.filter(r => (r.role || 'USER') === roleFilter && !r.banned));
      return visibleRows === null
      ? h('div', { className: 'spinner' })
      : visibleRows.length === 0
        ? h('div', { className: 'empty-inline' },
            h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'group', size: 26 })),
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
                  background: u.role === 'ADMIN' ? 'rgba(248,113,113,0.15)' : u.role === 'CSR' ? 'rgba(96,165,250,0.15)' : 'var(--bg-elevated)',
                  color:      u.role === 'ADMIN' ? 'var(--red)' : u.role === 'CSR' ? '#60a5fa' : 'var(--text-muted)'
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
                    ? h('button', { className: 'btn btn-ghost', style: { padding: '5px 10px', fontSize: 11, border: '1px solid var(--border)' }, disabled: busy, onClick: () => doGrantCsr(u), title: 'Grant CSR role' }, '+CSR')
                    : u.role === 'CSR'
                      ? h('button', { className: 'btn btn-ghost', style: { padding: '5px 10px', fontSize: 11, border: '1px solid var(--border)' }, disabled: busy, onClick: () => doRevokeCsr(u), title: 'Revoke CSR role' }, '−CSR')
                      : null,
                  u.role !== 'ADMIN'
                    ? h('button', { className: 'btn btn-ghost', style: { padding: '5px 10px', fontSize: 11, border: '1px solid var(--border)' }, disabled: busy, onClick: () => doGrant(u) }, '+Admin')
                    : (me?.id !== u.id && h('button', { className: 'btn btn-ghost', style: { padding: '5px 10px', fontSize: 11, border: '1px solid var(--border)' }, disabled: busy, onClick: () => doRevoke(u) }, '−Admin')),
                  u.banned
                    ? h('button', { className: 'btn btn-ghost', style: { padding: '5px 10px', fontSize: 11, border: '1px solid rgba(74,222,128,0.3)', color: 'var(--green)' }, disabled: busy, onClick: () => doUnban(u) }, 'Unban')
                    // Hide Ban on self — banning your own admin account
                    // locks you out of the panel and bumps your session
                    // epoch. The Force-logout button below already guards
                    // self; Ban (a strict superset of force-logout) was
                    // missing the same check.
                    : (me?.id !== u.id && h('button', { className: 'btn btn-ghost', style: { padding: '5px 10px', fontSize: 11, border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)' }, disabled: busy, onClick: () => doBan(u) }, 'Ban')),
                  // Force-logout — revoke every live session without banning.
                  // Hidden on self (the admin has logout-all on their own
                  // profile) and on already-banned users (ban already
                  // bumps sessionEpoch).
                  me?.id !== u.id && !u.banned && h('button', {
                    className: 'btn btn-ghost',
                    style: { padding: '5px 10px', fontSize: 11, border: '1px solid var(--border)' },
                    disabled: busy, onClick: () => doForceLogout(u),
                    title: 'Revoke every live session (does not ban)'
                  }, 'Force logout')
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
          // Batch 589 — listings CSV drill-down. Shown unconditionally
          // (a brand-new user with zero listings gets an empty CSV,
          // which is more useful than hiding the button).
          h('a', {
            className: 'btn btn-ghost',
            style: { padding: '6px 12px', fontSize: 11, border: '1px solid var(--border)' },
            href: `/api/admin/users/${detailUser.id}/listings.csv`,
            title: 'Full listing history (ACTIVE / SOLD / CANCELLED) as CSV'
          }, '⇣ Listings CSV'),
          // Quick-jump to the audit log filtered on this user as the
          // subject. Same page the admin would land on if they typed
          // the id into the audit filter — just one click instead.
          h('a', {
            className: 'btn btn-ghost',
            style: { padding: '6px 12px', fontSize: 11, border: '1px solid var(--border)' },
            href: `/admin#audit?subject=${detailUser.id}`,
            onClick: (e) => {
              // Hash-based routing isn't wired — intercept, flip the
              // detail modal, and swap the admin tab to audit with the
              // user's id prefilled. For now, close drawer + open a
              // new tab with the filter in the URL.
              e.preventDefault();
              setDetailUser(null);
              // Surface the user's id for the admin to paste into the
              // subject filter. The audit tab doesn't currently read
              // the URL fragment, so a clipboard copy is the safer UX.
              try {
                if (navigator.clipboard?.writeText) {
                  navigator.clipboard.writeText(detailUser.id.toString());
                  toast(`User id ${detailUser.id} copied. Paste into the Audit Log "Subject user #id" box.`, 'ok');
                } else {
                  window.prompt('Paste into Audit Log subject filter:', detailUser.id.toString());
                }
              } catch (_) { window.prompt('Paste into Audit Log subject filter:', detailUser.id.toString()); }
            }
          }, '📜 Audit'),
          // Batch 580 — direct message. One-off heads-up ("your email
          // is still unverified and you're about to miss out on
          // withdrawal") that pushes a bell notification to just this
          // user. Goes through ADMIN_MESSAGE kind; server rejects
          // banned targets so buttons stay safe.
          h('button', {
            className: 'btn btn-ghost',
            style: { padding: '6px 12px', fontSize: 11, border: '1px solid rgba(96,165,250,0.35)', color: '#60a5fa' },
            title: 'Send this user a direct bell notification',
            onClick: async () => {
              const title = window.prompt(
                `Message ${detailUser.displayName || ('#' + detailUser.id)}\n\nTitle (required, max 120 chars):`);
              if (!title || !title.trim()) return;
              const body = window.prompt(
                'Body (optional, max 500 chars):', '') || '';
              const res = await adminMessageUser(detailUser.id, title.trim(), body.trim(), '');
              if (res && (res.error || res.code)) {
                toast(res.message || res.error || 'Could not send message', 'err');
                return;
              }
              toast('✓ Message sent to user\'s bell.', 'ok');
            }
          }, '✉ Message'),
          // Reset 2FA — support flow for users who have lost access to
          // their TOTP authenticator. Admin-gated action (not CSR). The
          // target gets a notification + must re-enrol next session.
          h('button', {
            className: 'btn btn-ghost',
            style: { padding: '6px 12px', fontSize: 11, border: '1px solid rgba(251,191,36,0.3)', color: '#fbbf24' },
            title: 'Reset the user\'s two-factor authentication',
            onClick: async () => {
              const label = detailUser.displayName || ('#' + detailUser.id);
              // Capture the target id at click time — if the admin closes
              // the drawer or opens a different user mid-flight, the patch
              // below would otherwise flip the wrong user's 2FA chip.
              const targetId = detailUser.id;
              const note = window.prompt(
                `Reset 2FA for ${label}?\n\n` +
                `They\'ll need to re-enrol from Profile → 2FA next time they sign in. Enter an audit note (required):`);
              if (!note || !note.trim()) return;
              const res = await adminReset2fa(targetId, note.trim());
              if (res.code || res.error) { toast(res.message || res.error, 'err'); return; }
              // Pre-fix: the Security health-card shows `summary.twoFactorEnabled`
              // ("🔐 2FA" → "○ 2FA"). Reset flipped the server-side flag but
              // the local summary kept the old value until the drawer was
              // reopened, contradicting the toast. In-place patch keeps the
              // card honest. Gate the patch on detailUser.id === targetId so
              // a drawer switch during the await doesn't leak state.
              setDetailData(d => d && d.summary && detailUser?.id === targetId
                ? { ...d, summary: { ...d.summary, twoFactorEnabled: false } }
                : d);
              // Batch 944 — name the user in the toast so staff see which
              // reset just landed when bulk-supporting multiple accounts.
              toast(`✓ 2FA reset for ${label} — they'll re-enrol on next sign-in.`, 'ok');
            }
          }, '⚿ Reset 2FA'),
          // Wallet freeze (batch 509). Softer than ban — halts money-in/out
          // on this wallet without nuking listings / offers / trades. Two
          // buttons because we don't have the wallet's current frozen
          // state in the detail drawer; both paths are idempotent so a
          // wrong click is a no-op.
          h('button', {
            className: 'btn btn-ghost',
            style: { padding: '6px 12px', fontSize: 11, border: '1px solid rgba(96,165,250,0.35)', color: '#60a5fa' },
            title: 'Freeze this wallet — refuses deposit / withdraw / purchase. Softer than a ban.',
            onClick: async () => {
              const label = detailUser.displayName || ('#' + detailUser.id);
              // Capture target — same drawer-switch race as Reset 2FA above.
              const targetId = detailUser.id;
              const reason = window.prompt(
                `Freeze wallet for ${label}?\n\n` +
                `Reason (required — shown to the user):`);
              if (!reason || !reason.trim()) return;
              const res = await adminFreezeWallet(targetId, reason.trim());
              if (res.code || res.error) { toast(res.message || res.error, 'err'); return; }
              // Pre-fix: the Wallet health-card at the top of the drawer
              // reads `summary.walletFrozen` and stayed "Active" until the
              // drawer was reopened. Patch the local summary so the card
              // flips to "❄ FROZEN" the moment the toast fires. Skip the
              // patch when the drawer has moved to a different user.
              setDetailData(d => d && d.summary && detailUser?.id === targetId
                ? { ...d, summary: { ...d.summary, walletFrozen: true, walletFrozenReason: reason.trim() } }
                : d);
              // Batch 944 — name the user in the toast. Previously generic
              // "Wallet frozen." reading made bulk triaging harder.
              toast(res.noChange
                ? `${label}'s wallet already frozen — no-op.`
                : `🔒 ${label}'s wallet frozen — deposit/withdraw/purchase blocked, user sees the reason banner.`,
                'ok');
            }
          }, '🔒 Freeze'),
          h('button', {
            className: 'btn btn-ghost',
            style: { padding: '6px 12px', fontSize: 11, border: '1px solid rgba(74,222,128,0.35)', color: 'var(--green)' },
            title: 'Unfreeze this wallet — restores deposit / withdraw / purchase.',
            onClick: async () => {
              const label = detailUser.displayName || ('#' + detailUser.id);
              if (!confirm(`Unfreeze wallet for ${label}?`)) return;
              // Capture target — drawer-switch race same as Freeze above.
              const targetId = detailUser.id;
              const res = await adminUnfreezeWallet(targetId);
              if (res.code || res.error) { toast(res.message || res.error, 'err'); return; }
              // Same in-place patch as Freeze above so the Wallet health-card
              // flips to "Active" without a drawer reopen. Skip the patch
              // when the drawer has moved on to a different user.
              setDetailData(d => d && d.summary && detailUser?.id === targetId
                ? { ...d, summary: { ...d.summary, walletFrozen: false, walletFrozenReason: null } }
                : d);
              toast(res.noChange
                ? `${label}'s wallet already unfrozen — no-op.`
                : `✓ ${label}'s wallet unfrozen — money-in/out restored.`,
                'ok');
            }
          }, '✓ Unfreeze')
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
              // Batch 549 — consolidated staff summary strip. Wallet +
              // dispute + account-security signals at a glance so staff
              // don't have to cross-reference the Withdrawals / Disputes
              // tabs to answer "is this user safe to act on right now?"
              detailData.summary && h('div', { className: 'health-grid', style: { marginBottom: 16 } },
                h('div', { className: 'health-card' },
                  h('div', { className: 'health-card-label' }, 'Wallet balance'),
                  h('div', { className: 'health-card-value' },
                    detailData.summary.walletBalance != null
                      ? fmt(detailData.summary.walletBalance)
                      : '—'),
                  h('div', { className: 'health-card-hint',
                    // 2026-05-20 — was `var(--err)`, an undefined CSS var, so
                    // the FROZEN hint never got its red emphasis. `--red` is
                    // the app-wide danger token (used everywhere else here).
                    style: detailData.summary.walletFrozen ? { color: 'var(--red)', fontWeight: 600 } : null },
                    detailData.summary.walletFrozen
                      ? '❄ FROZEN' + (detailData.summary.walletFrozenReason ? ' · ' + detailData.summary.walletFrozenReason : '')
                      : 'Active')
                ),
                h('div', { className: 'health-card' },
                  h('div', { className: 'health-card-label' }, 'Disputes'),
                  h('div', { className: 'health-card-value',
                    // 2026-05-20 — was `var(--warn)`, an undefined CSS var, so
                    // a non-zero active-dispute count never got its amber
                    // emphasis. Use the #fbbf24 literal the rest of this
                    // file already uses for every other amber accent.
                    style: (detailData.summary.activeDisputes || 0) > 0 ? { color: '#fbbf24' } : null },
                    (detailData.summary.activeDisputes ?? 0).toString()),
                  h('div', { className: 'health-card-hint' },
                    `active · ${detailData.summary.lifetimeDisputes ?? 0} lifetime`)
                ),
                h('div', { className: 'health-card' },
                  h('div', { className: 'health-card-label' }, 'Pending withdraw'),
                  h('div', { className: 'health-card-value' },
                    detailData.summary.pendingWithdrawAmt != null
                      ? fmt(detailData.summary.pendingWithdrawAmt)
                      : fmt(0)),
                  h('div', { className: 'health-card-hint' },
                    `${detailData.summary.openTrades ?? 0} open trade${(detailData.summary.openTrades ?? 0) === 1 ? '' : 's'}`)
                ),
                h('div', { className: 'health-card' },
                  h('div', { className: 'health-card-label' }, 'Security'),
                  h('div', { className: 'health-card-value' },
                    (detailData.summary.twoFactorEnabled ? '🔐 2FA' : '○ 2FA') + ' · ' +
                    (detailData.summary.emailVerified ? '✓ email' : '○ email')),
                  h('div', { className: 'health-card-hint' },
                    detailData.summary.email || 'No email on file')
                ),
                // Batch 552 — ship-time card. Same 90-day median metric
                // the public stall surfaces, but with the sample count
                // shown explicitly so fraud staff can distinguish
                // "ships fast + 50 trades of evidence" from "one lucky
                // fast trade, otherwise ghosts." Hidden when the
                // account has never shipped (typicalShipMs=null = below
                // the 3-sample floor).
                (() => {
                  const ms = detailData.summary.typicalShipMs;
                  const samples = detailData.summary.typicalShipSamples || 0;
                  let value, hint, color = null;
                  if (ms != null) {
                    if (ms < 3_600_000)           value = Math.max(1, Math.round(ms / 60_000)) + 'm';
                    else if (ms < 24 * 3_600_000) value = Math.max(1, Math.round(ms / 3_600_000)) + 'h';
                    else                          value = Math.max(1, Math.round(ms / (24 * 3_600_000))) + 'd';
                    hint = `based on ${samples} trade${samples === 1 ? '' : 's'} · 90d window`;
                    color = ms < 4 * 3_600_000 ? 'var(--green)' : ms < 24 * 3_600_000 ? '#fbbf24' : 'var(--red)';
                  } else {
                    value = '—';
                    hint = samples > 0
                      ? `${samples}/3 trades · below noise floor`
                      : 'no completed trades';
                  }
                  return h('div', { className: 'health-card' },
                    h('div', { className: 'health-card-label' }, 'Typical ship'),
                    h('div', { className: 'health-card-value', style: color ? { color } : null }, value),
                    h('div', { className: 'health-card-hint' }, hint)
                  );
                })(),
                // Distinct-sign-in-IPs card (batch 603). 1-3 is normal;
                // 10+ in 30 days is a shared-credential / proxied flag
                // worth investigating. Color ramps from neutral → amber
                // → red so fraud triage can scan the drawer and spot
                // the outliers instantly. Hidden when we've observed
                // zero sign-in audit rows (pre-batch-408 account or
                // audit logs recently pruned).
                (() => {
                  const n = detailData.summary.distinctSignInIps30d || 0;
                  if (n === 0) return null;
                  const color = n >= 10 ? 'var(--red)'
                    : n >= 5 ? '#fbbf24'
                    : 'var(--text-primary)';
                  return h('div', { className: 'health-card' },
                    h('div', { className: 'health-card-label' }, 'Distinct IPs · 30d'),
                    h('div', { className: 'health-card-value', style: { color } }, n.toString()),
                    h('div', { className: 'health-card-hint' },
                      n >= 10 ? '⚠ many · shared or proxied?'
                        : n >= 5 ? 'heightened · worth a look'
                        : 'normal range')
                  );
                })(),
                // Active API keys card (batch 703). 0-5 is normal; 10+ is
                // a persistence-token red flag worth investigating in
                // conjunction with the sign-in-IP signal above. Hides
                // at zero so users with no keys don't get a noisy card.
                (() => {
                  const n = detailData.summary.activeApiKeys || 0;
                  if (n === 0) return null;
                  const color = n >= 10 ? 'var(--red)'
                    : n >= 5 ? '#fbbf24'
                    : 'var(--text-primary)';
                  return h('div', { className: 'health-card' },
                    h('div', { className: 'health-card-label' }, 'Active API keys'),
                    h('div', { className: 'health-card-value', style: { color } }, n.toString()),
                    h('div', { className: 'health-card-hint' },
                      n >= 10 ? '⚠ many · compromise risk'
                        : n >= 5 ? 'heightened · bot fleet?'
                        : 'normal range')
                  );
                })()
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
                        if (res && (res.error || res.code)) { toast(res.message || res.error, 'err'); return; }
                        // Every other mutating action in this file confirms
                        // with a toast — notes was the silent outlier, so an
                        // admin couldn't tell a save from a no-op.
                        toast(notesDraft.trim()
                          ? 'Staff notes saved.'
                          : 'Staff notes cleared.', 'ok');
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
              ),
              // Batch 568 — inline wallet-transaction history for fraud
              // triage. Scrollable table capped at 100 rows (server cap
              // matches). Colors inbound rows green, outbound red.
              // Shows raw stripeReference so ops can cross-reference a
              // specific Stripe session id when a chargeback lands.
              detailData.transactions && detailData.transactions.length > 0 && h('div', null,
                h('div', { style: { display: 'flex', alignItems: 'center', gap: 8, margin: '14px 0 6px' } },
                  h('div', { style: { fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--text-muted)', flex: 1 } },
                    `Wallet history · ${detailData.transactions.length} txn${detailData.transactions.length === 1 ? '' : 's'}`),
                  // Batch 586 — CSV download mirroring the inline table
                  // so ops can open the full per-user tx list in Excel
                  // when cross-referencing a Stripe chargeback.
                  h('a', {
                    className: 'btn btn-ghost',
                    style: { padding: '3px 8px', fontSize: 10, border: '1px solid var(--border)' },
                    href: `/api/admin/users/${detailUser.id}/transactions.csv`,
                    title: 'Download this user\'s recent transactions as CSV'
                  }, '⇣ CSV')
                ),
                h('div', { style: { maxHeight: 220, overflowY: 'auto', border: '1px solid var(--border)', borderRadius: 6 } },
                  h('table', { className: 'db-table', style: { fontSize: 11 } },
                    h('thead', null, h('tr', null,
                      h('th', null, 'When'),
                      h('th', null, 'Type'),
                      h('th', { className: 'right' }, 'Amount'),
                      h('th', null, 'Status'),
                      h('th', null, 'Ref / Desc')
                    )),
                    h('tbody', null, detailData.transactions.map(tx => {
                      const inbound = tx.type === 'DEPOSIT' || tx.type === 'SALE' || tx.type === 'REFUND' || tx.type === 'ADJUSTMENT_CREDIT';
                      return h('tr', { key: tx.id },
                        h('td', { className: 'db-mono', style: { fontSize: 10, color: 'var(--text-muted)' } },
                          new Date(tx.createdAt).toLocaleString()),
                        h('td', { style: { fontSize: 11 } }, tx.type),
                        h('td', { className: 'right db-mono', style: { color: inbound ? 'var(--green)' : 'var(--red)' } },
                          (inbound ? '+' : '−') + fmt(parseFloat(tx.amount) || 0)),
                        h('td', { className: `wallet-tx-status ${tx.status}`, style: { fontSize: 10 } }, tx.status),
                        h('td', { style: { fontSize: 10, color: 'var(--text-secondary)', maxWidth: 220, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' } },
                          tx.stripeReference && tx.stripeReference !== 'admin'
                            ? h('span', { title: tx.stripeReference }, tx.stripeReference)
                            : (tx.description || '—'))
                      );
                    }))
                  )
                )
              ),
              // Batch 579 — chronological activity feed from the audit
              // log for this subject user. Scrollable (20-row cap) so
              // it doesn't push the Close button off-screen on a busy
              // account. Each row shows the event type + timestamp +
              // short summary; clicking the row deep-links into the
              // Audit tab scoped to the event.
              detailData.activity && detailData.activity.length > 0 && h('div', null,
                h('div', { style: { fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--text-muted)', margin: '14px 0 6px' } },
                  `Activity timeline · ${detailData.activity.length} audit entr${detailData.activity.length === 1 ? 'y' : 'ies'}`),
                h('div', { style: { maxHeight: 200, overflowY: 'auto', border: '1px solid var(--border)', borderRadius: 6 } },
                  detailData.activity.map(a => h('div', {
                    key: a.id,
                    style: {
                      display: 'flex', alignItems: 'center', gap: 8,
                      padding: '6px 10px', fontSize: 11,
                      borderBottom: '1px solid var(--border)',
                      background: 'var(--bg-elevated)'
                    }
                  },
                    h('span', { className: 'mono', style: { color: 'var(--text-muted)', minWidth: 130 } },
                      a.createdAt ? new Date(a.createdAt).toLocaleString() : '—'),
                    h('span', { style: { fontWeight: 700, color: 'var(--accent)', minWidth: 140 } },
                      a.eventType || 'UNKNOWN'),
                    h('span', {
                      style: { flex: 1, color: 'var(--text-secondary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' },
                      title: a.summary || ''
                    }, a.summary || ''),
                    a.actorName && a.actorUserId !== detailUser.id && h('span', {
                      style: { fontSize: 10, color: 'var(--text-muted)' },
                      title: `Action by ${a.actorName}`
                    }, 'by ', a.actorName)
                  ))
                )
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
  // Batch 576 — free-text search across subject / username / category.
  // Debounced at 300ms so typing doesn't thrash the server and the
  // request count stays sane on a long typed query.
  const [search, setSearch] = useState('');
  const [busy, setBusy]     = useState(false);

  const load = useCallback(async () => {
    setList(await adminTickets(filter, search));
  }, [filter, search]);
  useEffect(() => {
    const h = setTimeout(() => { load(); }, search ? 300 : 0);
    return () => clearTimeout(h);
  }, [load, search]);

  // Guard against a null return (safeJson yields null on 404/5xx) — an
  // unguarded setView(null) made a row click a silent no-op. Also guard
  // a missing `ticket` field so the detail view never renders half-data.
  const open = async (id) => {
    const t = await adminTicket(id);
    if (!t || !t.ticket) { toast('Could not open that ticket — it may have been removed.', 'err'); return; }
    setView(t);
  };
  const sendReply = async () => {
    if (!reply.trim() || !viewing?.ticket) return;
    const t = viewing.ticket;
    setBusy(true);
    try {
      const res = await adminTicketReply(t.id, reply);
      if (res && res.error) { toast(res.error, 'err'); return; }
      setReply('');
      // The reply already landed — guard the refetch so a transient 5xx
      // doesn't `setView(null)` and eject the admin from the ticket they
      // just replied to. Keep the current thread on screen if it fails.
      const fresh = await adminTicket(t.id);
      if (fresh && fresh.ticket) setView(fresh);
      load();
      // Batch 917 — confirm toast on admin-side reply. Matches the
      // CSR-side toast so both staff flows feel consistent. Previously
      // the flow succeeded silently.
      toast(`Reply posted to ticket #${t.id}. User will see it + get an email.`, 'ok');
    } finally { setBusy(false); }
  };
  const closeTicket = async () => {
    if (!viewing?.ticket) return;
    const t = viewing.ticket;
    const res = await adminCloseTicket(t.id);
    if (res && res.error) { toast(res.error, 'err'); return; }
    // Guard the refetch — same reasoning as sendReply: the close already
    // succeeded server-side, so a failed refresh shouldn't blank the view.
    const fresh = await adminTicket(t.id);
    if (fresh && fresh.ticket) setView(fresh);
    load();
    // Batch 917 — cite the id + truncated subject so admin closing
    // many tickets back-to-back knows which one just closed.
    const subjStr = t.subject ? ` — "${t.subject.length > 40 ? t.subject.slice(0, 40) + '…' : t.subject}"` : '';
    toast(`Ticket #${t.id}${subjStr} closed. User can reopen from the thread.`, 'ok');
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
          h('div', { className: 'support-msg-body' }, linkifyText(m.body, 'st-' + m.id))
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
    h('div', { style: { display: 'flex', gap: 10, marginBottom: 14, alignItems: 'center', flexWrap: 'wrap' } },
      [['',  'All'],['WAITING_STAFF','Waiting on us'],['WAITING_USER','Waiting on user'],['RESOLVED','Resolved']].map(([v, l]) =>
        h('button', { key: v || 'all', className: `offer-tab ${filter === v ? 'active' : ''}`, onClick: () => setFilter(v) }, l)
      ),
      // Batch 576 — free-text search across subject / username /
      // category. Debounced client-side (see load()) so typing doesn't
      // hammer the server. Server-side capped at 100 chars + 500
      // rows.
      h('input', {
        className: 'price-input',
        style: { flex: 1, minWidth: 160 },
        placeholder: '🔎 Search subject / username / category…',
        value: search,
        onChange: e => setSearch(e.target.value),
        maxLength: 100
      }),
      // Batch 559 — CSV export mirroring both the status filter AND
      // the search term. Quarterly reporting / ticket-triage audits
      // without clicking every row.
      (() => {
        const qp = new URLSearchParams();
        if (filter) qp.set('status', filter);
        if (search) qp.set('search', search);
        return h('a', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11 },
          href: '/api/admin/tickets.csv' + (qp.toString() ? '?' + qp.toString() : ''),
          title: 'Export the current tickets view as CSV'
        }, '⇣ CSV');
      })()
    ),
    list === null
      ? h('div', { className: 'spinner' })
      : list.length === 0
        ? h('div', { className: 'empty-inline' }, h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'support_agent', size: 26 })),
            h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'No tickets.'))
        : h('table', { className: 'db-table' },
            h('thead', null, h('tr', null,
              h('th', null, 'ID'), h('th', null, 'Subject'),
              h('th', null, 'User'), h('th', null, 'Signals'),
              h('th', null, 'Status'),
              h('th', { className: 'right' }, 'Updated'))),
            h('tbody', null, list.map(t => h('tr', {
              key: t.id, className: 'db-row',
              onClick: () => open(t.id),
              role: 'button',
              tabIndex: 0,
              'aria-label': `Open ticket #${t.id} — ${t.subject} from ${t.username || 'user #' + t.userId} (${t.status})`,
              onKeyDown: (e) => {
                if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(t.id); }
              }
            },
              h('td', { className: 'db-rank' }, '#' + t.id),
              h('td', null, t.subject),
              h('td', { className: 'db-mono', style: { fontSize: 11 } }, t.username || '#' + t.userId),
              // Fraud-signal chips on tickets (batch 525). Lets support
              // spot the "3 chargebacks + fresh account + unverified email"
              // pattern at a glance instead of opening every ticket.
              h('td', { style: { fontSize: 11, whiteSpace: 'nowrap' } }, (() => {
                const chips = [];
                if (t.userCreatedAt) {
                  const ageDays = Math.floor((Date.now() - t.userCreatedAt) / 86400000);
                  const young = ageDays < 7;
                  chips.push(h('span', {
                    key: 'age', title: 'Account age',
                    style: {
                      padding: '2px 6px', borderRadius: 4, marginRight: 4,
                      background: young ? 'rgba(251,191,36,0.15)' : 'var(--bg-elevated)',
                      color: young ? '#fbbf24' : 'var(--text-muted)',
                      border: '1px solid ' + (young ? 'rgba(251,191,36,0.3)' : 'var(--border)'),
                      fontWeight: young ? 700 : 500
                    }
                  }, (ageDays < 1 ? '<1d' : ageDays + 'd')));
                }
                if ((t.lifetimeDisputes || 0) > 0) {
                  chips.push(h('span', {
                    key: 'cb', title: `${t.lifetimeDisputes} lifetime chargeback${t.lifetimeDisputes === 1 ? '' : 's'}`,
                    style: {
                      padding: '2px 6px', borderRadius: 4, marginRight: 4,
                      background: 'rgba(248,113,113,0.15)', color: '#fca5a5',
                      border: '1px solid rgba(248,113,113,0.35)', fontWeight: 800
                    }
                  }, '⚠ ' + t.lifetimeDisputes + 'x'));
                }
                if (t.walletFrozen) {
                  chips.push(h('span', {
                    key: 'frozen', title: 'Wallet frozen',
                    style: {
                      padding: '2px 6px', borderRadius: 4, marginRight: 4,
                      background: 'rgba(96,165,250,0.15)', color: '#60a5fa',
                      border: '1px solid rgba(96,165,250,0.3)', fontWeight: 700
                    }
                  }, '🔒'));
                }
                if (t.userBanned) {
                  chips.push(h('span', {
                    key: 'ban', title: 'Account banned',
                    style: {
                      padding: '2px 6px', borderRadius: 4, marginRight: 4,
                      background: 'rgba(248,113,113,0.2)', color: '#fca5a5',
                      border: '1px solid rgba(248,113,113,0.45)', fontWeight: 800
                    }
                  }, 'BANNED'));
                }
                if (t.userEmailVerified === false) {
                  chips.push(h('span', {
                    key: 'noemail', title: 'Email NOT verified',
                    style: {
                      padding: '2px 6px', borderRadius: 4, marginRight: 4,
                      background: 'rgba(248,113,113,0.1)', color: '#fca5a5',
                      border: '1px solid rgba(248,113,113,0.3)', fontWeight: 700
                    }
                  }, '✉✕'));
                }
                return chips.length === 0
                  ? h('span', { style: { color: 'var(--text-muted)' } }, '—')
                  : chips;
              })()),
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
    if (busy) return;
    // Validate before firing — a NaN tx id or amount would otherwise be
    // posted straight to an irreversible Stripe refund call.
    const tx = parseInt(txId, 10);
    if (!Number.isFinite(tx) || tx <= 0) { toast('Enter a valid deposit transaction id', 'err'); return; }
    let amt = null;
    if (amount.trim()) {
      amt = parseFloat(amount);
      if (!Number.isFinite(amt) || amt <= 0) { toast('Refund amount must be a positive number, or blank for a full refund', 'err'); return; }
    }
    // Confirm — the banner warns this hits Stripe with no undo, but the
    // button fired immediately. Every other destructive action in this
    // file gates behind a confirm; the irreversible one must too.
    if (!confirm(
      `Refund deposit transaction #${tx}` +
      (amt != null ? ` for $${amt.toFixed(2)}` : ' in full') +
      `?\n\nThis hits Stripe immediately and cannot be undone.`)) return;
    setBusy(true); setRes(null);
    try {
      const res = await adminRefundDeposit(tx, amt);
      setRes(res);
    } finally { setBusy(false); }
  };

  return h('div', { className: 'profile-panel' },
    h('div', { className: 'staff-banner warning' },
      'Refunds hit Stripe immediately — there is no undo. Enter the deposit transaction id from the user\'s Transactions tab. Leave amount blank for a full refund.'
    ),
    h('div', { className: 'wallet-input-label', style: { marginTop: 14 } }, 'Deposit transaction ID'),
    // Editing either field clears the previous result — otherwise the
    // green "Refund complete" banner for tx #42 lingers while the admin
    // types #99, which reads as if #99 already processed.
    h('input', { className: 'wallet-amount-input', value: txId, onChange: e => { setTxId(e.target.value); setRes(null); }, placeholder: 'e.g. 42' }),
    h('div', { className: 'wallet-input-label' }, 'Refund amount (blank = full)'),
    h('input', { className: 'wallet-amount-input', value: amount, onChange: e => { setAmt(e.target.value); setRes(null); }, placeholder: 'Leave blank for full refund' }),
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
  // Deep-link via `?tab=<id>` (batch 464). Same pattern as AdminModal.
  const initialTab = (() => {
    try {
      const t = new URLSearchParams(window.location.search).get('tab');
      return t || 'dashboard';
    } catch { return 'dashboard'; }
  })();
  const [tab, setTab] = useState(initialTab);
  const TABS = [
    { id: 'dashboard', label: 'Queue' },
    { id: 'lookup',    label: 'User Lookup' },
    { id: 'tickets',   label: 'Tickets' },
    { id: 'flag',      label: 'Flag Listing' },
  ];
  return h(InfoModal, { title: 'Customer Service', onClose },
    h('div', { className: 'staff-banner csr' },
      h('strong', null, 'CSR MODE'),
      ' — limited-power panel. You can answer tickets, look up users, and issue small goodwill credits. Anything bigger escalates to an admin.'
    ),
    // Batch 938 — CSR panel tablist semantics, matches the Admin panel
    // pattern just above.
    h('div', { className: 'profile-tabs', role: 'tablist', 'aria-label': 'CSR sections' },
      TABS.map(t => h('button', {
        key: t.id,
        id: `csr-tab-${t.id}`,
        className: `profile-tab ${tab === t.id ? 'active' : ''}`,
        role: 'tab',
        'aria-selected': tab === t.id,
        'aria-controls': 'csr-tabpanel',
        tabIndex: tab === t.id ? 0 : -1,
        onKeyDown: (e) => {
          if (!['ArrowRight','ArrowLeft','Home','End'].includes(e.key)) return;
          e.preventDefault();
          const idx = TABS.findIndex(x => x.id === tab);
          let n = idx;
          if (e.key === 'ArrowRight') n = (idx + 1) % TABS.length;
          else if (e.key === 'ArrowLeft') n = (idx - 1 + TABS.length) % TABS.length;
          else if (e.key === 'Home') n = 0;
          else if (e.key === 'End') n = TABS.length - 1;
          setTab(TABS[n].id);
        },
        onClick: () => {
          setTab(t.id);
          try {
            const next = t.id === 'dashboard' ? '/csr' : '/csr?tab=' + t.id;
            if (window.location.pathname + window.location.search !== next) {
              window.history.replaceState({}, '', next);
            }
          } catch (_) {}
        }
      }, t.label))
    ),
    h('div', { role: 'tabpanel', id: 'csr-tabpanel', 'aria-labelledby': `csr-tab-${tab}` },
      tab === 'dashboard' && h(CsrDashboardTab, null),
      tab === 'lookup'    && h(CsrLookupTab, null),
      tab === 'tickets'   && h(CsrTicketsTab, null),
      tab === 'flag'      && h(CsrFlagTab, null),
    ),
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
    if (!listingId) { toast('Enter a numeric listing ID.', 'err'); return; }
    if (!reason.trim()) { toast('Pick or type a reason.', 'err'); return; }
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
        // Clear the prior result so a stale "Flag note appended to #5"
        // banner doesn't sit next to a fresh listing id being typed.
        onChange: e => { setId(e.target.value.replace(/[^0-9]/g, '')); setResult(null); }
      }),
      h('select', {
        className: 'price-input',
        'aria-label': 'Penalty reason',
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
  const [err, setErr]     = useState('');
  // Pre-fix: bare `.then(setStats)` — no cleanup (setState fired after
  // the modal closed) and no `.catch` (a rejected csrStats() left the
  // spinner up forever). Guard with an `alive` flag + surface the error.
  useEffect(() => {
    let alive = true;
    csrStats()
      .then(s => { if (alive) setStats(s); })
      .catch(e => { if (alive) setErr(e?.message || 'Failed to load queue stats'); });
    return () => { alive = false; };
  }, []);
  if (err) return h('div', { className: 'profile-panel' }, h('div', { className: 'wallet-error' }, err));
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
    const label = u.displayName || u.steamId64;
    const amt = prompt(`Goodwill credit for ${label} (max per CSR adjustment applies):`, '5.00');
    if (!amt) return;
    // Guard the parse — a non-numeric entry would otherwise post NaN to
    // the goodwill endpoint. Mirrors AdminUsersTab.doCredit.
    const value = parseFloat(amt);
    if (!Number.isFinite(value) || value <= 0) { toast('Enter a positive number', 'err'); return; }
    const note = prompt('Reason / note (required for audit):', '');
    if (!note || !note.trim()) return;
    // Confirm before moving money — mirrors AdminUsersTab.doCredit's
    // confirm gate. CSR goodwill is real money out the door, irreversible
    // without an admin debit; a fat-fingered amount must be catchable
    // before it lands in the ledger.
    if (!confirm(`CREDIT ${fmt(value)} to ${label}'s wallet?\n\nThis adjusts the balance immediately and is logged in the audit trail.`)) return;
    // Guard against double-submit — without busy, a double-clicked
    // "+ Goodwill" button fires two csrGoodwill POSTs and double-credits
    // the wallet (the confirm() already cleared on the first click).
    // Mirrors AdminUsersTab.doCredit's setBusy gate.
    if (busy) return;
    setBusy(true);
    try {
      const res = await csrGoodwill(u.id, value, note);
      if (res.code || res.error) { toast(res.message || res.error, 'err'); return; }
      toast(`Credited. New balance: $${res.newBalance}`, 'ok');
      search();
    } finally { setBusy(false); }
  };
  return h('div', { className: 'profile-panel' },
    h('div', { style: { display: 'flex', gap: 10, marginBottom: 14 } },
      h('input', { className: 'price-input', style: { flex: 1 }, placeholder: 'Steam ID / display name / user #id', value: q, onChange: e => setQ(e.target.value), onKeyDown: e => { if (e.key === 'Enter') search(); } }),
      h('button', { className: 'btn btn-accent', disabled: busy, onClick: search }, busy ? '…' : 'Search')
    ),
    data && ((data.matches || []).length === 0
      ? h('div', { className: 'empty-inline' }, h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'search', size: 26 })),
          h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'No matches.'))
      : (data.matches || []).map(u => h('div', { key: u.id, className: 'csr-user-card' },
          h('div', { style: { display: 'flex', alignItems: 'center', gap: 12, marginBottom: 10 } },
            u.avatarUrl
              ? h('img', { src: u.avatarUrl, alt: u.displayName, style: { width: 44, height: 44, borderRadius: 8 } })
              : h('div', { className: 'db-thumb', style: { width: 44, height: 44 } }, (u.displayName || 'U').substring(0,2).toUpperCase()),
            h('div', { style: { flex: 1 } },
              h('div', { style: { fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' } }, u.displayName || 'Player'),
              h('div', { style: { fontSize: 11, color: 'var(--text-muted)', fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace" } }, u.steamId64 + ' · ' + u.role + (u.banned ? ' · 🚫 BANNED' : '')),
              // Rating chip (batch 489). Single-glance trust signal
              // alongside the banned/chargeback signals further down.
              u.rating && u.rating.count > 0 && h('div', {
                style: { fontSize: 11, color: '#fbbf24', marginTop: 2, fontWeight: 600 }
              }, '★ ', Number(u.rating.average || 0).toFixed(2), ' · ', u.rating.count, ' review', u.rating.count === 1 ? '' : 's')
            ),
            h('div', { style: { textAlign: 'right' } },
              h('div', { style: { fontSize: 10, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: 0.5 } }, 'Balance'),
              h('div', { style: { fontSize: 16, fontWeight: 800, color: 'var(--accent)', fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace" } }, fmt(u.balance || 0))
            ),
            h('button', { className: 'btn btn-accent', style: { marginLeft: 10, padding: '8px 14px' }, disabled: busy, onClick: () => giveCredit(u) }, '+ Goodwill')
          ),
          u.banned && h('div', { style: { fontSize: 11, padding: '8px 10px', background: 'var(--red-dim)', border: '1px solid var(--red)', borderRadius: 6, color: 'var(--red)', marginBottom: 8 } },
            'Ban reason: ', u.banReason || '(none)'),
          // Chargeback context (batch 469). Only renders when there's
          // something to flag — clean accounts stay clean. ACTIVE
          // (red) drives the wallet's withdrawal hold; LIFETIME
          // (amber) is a repeat-offender signal even after staff
          // cleared rows.
          ((u.activeDisputes || 0) > 0 || (u.lifetimeDisputes || 0) > 0) && h('div', {
            style: {
              fontSize: 11, padding: '8px 10px', borderRadius: 6, marginBottom: 8,
              background: u.activeDisputes > 0 ? 'rgba(248,113,113,0.12)' : 'rgba(251,191,36,0.10)',
              border: '1px solid ' + (u.activeDisputes > 0 ? 'var(--red)' : 'rgba(251,191,36,0.4)'),
              color: u.activeDisputes > 0 ? 'var(--red)' : '#fbbf24',
              display: 'flex', gap: 12, alignItems: 'center'
            }
          },
            h('span', { style: { fontSize: 14 } }, '⚠'),
            h('div', { style: { flex: 1, lineHeight: 1.5 } },
              h('strong', null, 'Chargebacks: '),
              u.activeDisputes > 0 && h('span', null, h('b', null, u.activeDisputes), ' active (withdrawal hold) · '),
              h('span', null, h('b', null, u.lifetimeDisputes), ' lifetime')
            )
          ),
          h('div', { style: { fontSize: 10, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: 0.5, fontWeight: 700, marginBottom: 4 } }, 'Recent activity'),
          // Guard recentTx — an omitted/null array would otherwise throw
          // on `.length` and crash the whole match list render.
          (u.recentTx || []).length === 0
            ? h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } }, 'No transactions.')
            : h('div', null, (u.recentTx || []).slice(0, 6).map(t => h('div', { key: t.id, className: 'csr-tx-row' },
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
  // Batch 581 — free-text search. Debounced client-side so typing
  // doesn't thrash the server.
  const [search, setSearch] = useState('');
  const [busy, setBusy]     = useState(false);

  const load = useCallback(async () => { setList(await csrTickets(filter, search)); }, [filter, search]);
  useEffect(() => {
    const h = setTimeout(() => { load(); }, search ? 300 : 0);
    return () => clearTimeout(h);
  }, [load, search]);
  // Guard a null/empty return — matches AdminTicketsTab.open.
  const open = async (id) => {
    const t = await csrTicket(id);
    if (!t || !t.ticket) { toast('Could not open that ticket — it may have been removed.', 'err'); return; }
    setView(t);
  };
  const sendReply = async () => {
    if (!reply.trim() || !viewing?.ticket) return;
    const t = viewing.ticket;
    setBusy(true);
    try {
      const res = await csrTicketReply(t.id, reply);
      if (res && res.error) { toast(res.error, 'err'); return; }
      setReply('');
      // Guard the refetch so a transient failure doesn't eject the CSR
      // from the ticket they just replied to — the reply already landed.
      const fresh = await csrTicket(t.id);
      if (fresh && fresh.ticket) setView(fresh);
      load();
      // Batch 917 — CSR-side confirm toasts. Previously the flow was
      // silent on success, so a CSR had to eyeball the thread to know
      // their reply actually posted. Mirror the user-side support
      // flow's toast (batch 898).
      toast(`Reply posted to ticket #${t.id}. User will see it + get an email.`, 'ok');
    } finally { setBusy(false); }
  };
  const closeTicket = async () => {
    if (!viewing?.ticket) return;
    const t = viewing.ticket;
    const res = await csrCloseTicket(t.id);
    if (res && res.error) { toast(res.error, 'err'); return; }
    // Guard the refetch — the close already succeeded; a failed refresh
    // shouldn't blank the ticket view out from under the CSR.
    const fresh = await csrTicket(t.id);
    if (fresh && fresh.ticket) setView(fresh);
    load();
    // Batch 917 — same toast-on-close as the admin tickets flow so CSR
    // + admin paths feel consistent.
    const subjStr = t.subject ? ` — "${t.subject.length > 40 ? t.subject.slice(0, 40) + '…' : t.subject}"` : '';
    toast(`Ticket #${t.id}${subjStr} closed. User can reopen from the thread.`, 'ok');
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
          h('div', { className: 'support-msg-body' }, linkifyText(m.body, 'st-' + m.id))
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
    h('div', { style: { display: 'flex', gap: 10, marginBottom: 14, alignItems: 'center', flexWrap: 'wrap' } },
      [['WAITING_STAFF','Waiting on us'],['WAITING_USER','Waiting on user'],['','All']].map(([v, l]) =>
        h('button', { key: v || 'all', className: `offer-tab ${filter === v ? 'active' : ''}`, onClick: () => setFilter(v) }, l)
      ),
      // Batch 581 — free-text search mirrors the admin-tickets tab.
      h('input', {
        className: 'price-input',
        style: { flex: 1, minWidth: 160 },
        placeholder: '🔎 Search subject / username / category…',
        value: search,
        onChange: e => setSearch(e.target.value),
        maxLength: 100
      })
    ),
    list === null
      ? h('div', { className: 'spinner' })
      : list.length === 0
        ? h('div', { className: 'empty-inline' }, h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'support_agent', size: 26 })),
            h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'Queue is clear.'))
        : h('table', { className: 'db-table' },
            h('thead', null, h('tr', null,
              h('th', null, 'ID'), h('th', null, 'Subject'), h('th', null, 'User'),
              h('th', null, 'Signals'),
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
                onClick: () => open(t.id),
                // Keyboard access parity with the admin Tickets tab — the
                // CSR rows were mouse-only (no role/tabIndex/onKeyDown).
                role: 'button',
                tabIndex: 0,
                'aria-label': `Open ticket #${t.id} — ${t.subject} from ${t.username || 'user #' + t.userId} (${t.status})`,
                onKeyDown: (e) => {
                  if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(t.id); }
                }
              },
                h('td', { className: 'db-rank' }, '#' + t.id),
                h('td', null, t.subject),
                h('td', { className: 'db-mono', style: { fontSize: 11 } }, t.username || '#' + t.userId),
                // Fraud-signal chips on CSR tickets (batch 527). Same
                // shape as the admin Tickets tab (batch 525).
                h('td', { style: { fontSize: 11, whiteSpace: 'nowrap' } }, (() => {
                  const chips = [];
                  if (t.userCreatedAt) {
                    const ageDays = Math.floor((Date.now() - t.userCreatedAt) / 86400000);
                    const young = ageDays < 7;
                    chips.push(h('span', {
                      key: 'age', title: 'Account age',
                      style: {
                        padding: '2px 6px', borderRadius: 4, marginRight: 4,
                        background: young ? 'rgba(251,191,36,0.15)' : 'var(--bg-elevated)',
                        color: young ? '#fbbf24' : 'var(--text-muted)',
                        border: '1px solid ' + (young ? 'rgba(251,191,36,0.3)' : 'var(--border)'),
                        fontWeight: young ? 700 : 500
                      }
                    }, (ageDays < 1 ? '<1d' : ageDays + 'd')));
                  }
                  if ((t.lifetimeDisputes || 0) > 0) {
                    chips.push(h('span', {
                      key: 'cb', title: `${t.lifetimeDisputes} lifetime chargeback${t.lifetimeDisputes === 1 ? '' : 's'}`,
                      style: {
                        padding: '2px 6px', borderRadius: 4, marginRight: 4,
                        background: 'rgba(248,113,113,0.15)', color: '#fca5a5',
                        border: '1px solid rgba(248,113,113,0.35)', fontWeight: 800
                      }
                    }, '⚠ ' + t.lifetimeDisputes + 'x'));
                  }
                  if (t.walletFrozen) {
                    chips.push(h('span', {
                      key: 'frozen', title: 'Wallet frozen',
                      style: {
                        padding: '2px 6px', borderRadius: 4, marginRight: 4,
                        background: 'rgba(96,165,250,0.15)', color: '#60a5fa',
                        border: '1px solid rgba(96,165,250,0.3)', fontWeight: 700
                      }
                    }, '🔒'));
                  }
                  if (t.userBanned) {
                    chips.push(h('span', {
                      key: 'ban', title: 'Account banned',
                      style: {
                        padding: '2px 6px', borderRadius: 4, marginRight: 4,
                        background: 'rgba(248,113,113,0.2)', color: '#fca5a5',
                        border: '1px solid rgba(248,113,113,0.45)', fontWeight: 800
                      }
                    }, 'BANNED'));
                  }
                  if (t.userEmailVerified === false) {
                    chips.push(h('span', {
                      key: 'noemail', title: 'Email NOT verified',
                      style: {
                        padding: '2px 6px', borderRadius: 4, marginRight: 4,
                        background: 'rgba(248,113,113,0.1)', color: '#fca5a5',
                        border: '1px solid rgba(248,113,113,0.3)', fontWeight: 700
                      }
                    }, '✉✕'));
                  }
                  return chips.length === 0
                    ? h('span', { style: { color: 'var(--text-muted)' } }, '—')
                    : chips;
                })()),
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
