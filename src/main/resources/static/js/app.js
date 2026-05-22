// Top-level App component + ErrorBoundary.
// Owns marketplace state, wires modals, handles Stripe/Steam redirect return.
import { h, React, useState, useEffect, useCallback, useMemo, useRef, fmt, timeAgo, signInWithSteam, toast as domToast, linkifyText, currencySymbol, fxConvertUsd } from './utils.js';
import {
  fetchListings, fetchListingsForItem, fetchHistory, fetchItem, fetchItemsByIds, buyListing,
  fetchWallet, fetchTransactions, fetchMe, logoutSteam, confirmDeposit, makeOffer,
  adminCheck, csrCheck, checkoutCart, fetchListingById, fetchPlatformRecentSales, fetchPublicStall, fetchPublicStallSold, fetchReviewsForUser,
  fetchEligibleReviews, leaveReview, fetchAuctionsEndingSoon, fetchOfferCounts,
  fetchAnnouncement, replyToReview, fetchJustListed, fetchTopSellers, fetchTopDeals,
  checkListingsActive, fetchFollowingFeed, fetchMarketStats, searchSellers
} from './api.js';
import { ItemImage, MaterialIcon, Avatar, ReasonDrawer, PriceFreshnessChip } from './primitives.js';
import { GridCard, ListingRow, TrendCard } from './cards.js';
// Chat removed — was a placeholder with fake messages
import { NotificationBell, ThemePicker } from './nav-widgets.js';
import {
  ItemModal, WalletModal, FaqModal, SettingsModal, ProfileModal, AffiliateModal,
  SellItemsModal, MyStallModal, OffersModal, WatchlistModal
} from './modals.js';
import {
  DatabaseModal, BuyOrdersModal, LoadoutLabModal,
  NotificationsModal
} from './csfloat-modals.js';
/* Boss QA cycle 13 P2 — staff-modals.js (198KB) is no longer in the static
   import graph. The bundle was loading on every public-route page even
   though only ~0.5% of users hit /admin or /csr. LazyStaffPanel below
   dynamically imports the module when the user actually navigates to a
   staff route AND has the matching role; non-staff routes never pay the
   transfer cost. */
let _staffModsPromise = null;
function _loadStaffMods() {
  if (!_staffModsPromise) _staffModsPromise = import('./staff-modals.js');
  return _staffModsPromise;
}
function LazyStaffPanel({ which, me, onClose }) {
  const [Mod, setMod] = useState(null);
  const [err, setErr] = useState(null);
  useEffect(() => {
    let alive = true;
    _loadStaffMods()
      .then(mods => {
        if (!alive) return;
        setMod(() => (which === 'admin' ? mods.AdminModal : mods.CsrModal));
      })
      .catch(e => alive && setErr(e));
    return () => { alive = false; };
  }, [which]);
  if (err) return h('div', { className: 'modal-backdrop', onClick: onClose },
    h('div', { className: 'modal sm', onClick: e => e.stopPropagation() },
      h('div', { className: 'modal-header' },
        h('h3', null, 'Staff panel failed to load'),
        h('button', { className: 'modal-close', onClick: onClose, 'aria-label': 'Close' }, '✕')),
      h('div', { style: { padding: 22 } },
        h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', lineHeight: 1.55 } },
          'Refresh the page to retry. If it keeps failing, check your network or sign out and back in.'))));
  if (!Mod) return h('div', { className: 'modal-backdrop' },
    h('div', { className: 'modal sm', style: { padding: 28, textAlign: 'center' } },
      h('div', { style: { fontSize: 12, color: 'var(--text-secondary)' } }, 'Loading staff panel…')));
  return h(Mod, { onClose, me });
}
import { HelpModal } from './help-modal.js';
import { InfoModal } from './info-modal.js';
import { useRoute, navigate, paths, installAnchorInterceptor, closeToPrevious } from './router.js';

// ── Pending trade reminder — surfaces a slim banner whenever the signed-in
// user has a trade sitting in a state where they're the actor and the
// counterparty has been waiting > 2h. Keeps escrow moving without
// needing a scheduled email. Polls every 60s.
function PendingTradeReminder({ me }) {
  const [pending, setPending] = useState([]);
  const [dismissed, setDismissed] = useState(() => {
    try { return new Set(JSON.parse(localStorage.getItem('sb_trade_nudge_dismissed') || '[]')); }
    catch { return new Set(); }
  });
  useEffect(() => {
    if (!me) { setPending([]); return; }
    let alive = true;
    const reload = async () => {
      // Batch 869 — skip polling while tab is hidden (/api/trades is
      // per-user + no-store, so each poll hits the DB). Pairs with the
      // rest of the visibility-aware polling pattern (batch 806).
      if (typeof document !== 'undefined' && document.hidden) return;
      try {
        const r = await fetch('/api/trades', { credentials: 'same-origin' });
        if (!r.ok) return;
        const rows = await r.json();
        const now = Date.now();
        const stuck = (Array.isArray(rows) ? rows : []).filter(t => {
          const mine = (t.sellerUserId === me.id && (t.state === 'PENDING_SELLER_ACCEPT' || t.state === 'PENDING_SELLER_SEND'))
            || (t.buyerUserId === me.id && t.state === 'PENDING_BUYER_CONFIRM');
          if (!mine) return false;
          const age = now - (t.updatedAt || t.createdAt || now);
          return age > 2 * 3600 * 1000; // 2h
        });
        if (alive) setPending(stuck);
      } catch (_) {}
    };
    reload();
    const id = setInterval(reload, 60_000);
    const onVis = () => { if (!document.hidden) reload(); };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      alive = false;
      clearInterval(id);
      document.removeEventListener('visibilitychange', onVis);
    };
  }, [me?.id]);
  const visible = pending.filter(t => !dismissed.has(t.id));
  if (visible.length === 0) return null;
  const dismiss = (tradeId) => {
    const next = new Set(dismissed);
    next.add(tradeId);
    setDismissed(next);
    try { localStorage.setItem('sb_trade_nudge_dismissed', JSON.stringify([...next])); } catch (_) {}
  };
  const t = visible[0];
  const isSeller = t.sellerUserId === me.id;
  const action = t.state === 'PENDING_SELLER_ACCEPT' ? 'accept the trade'
               : t.state === 'PENDING_SELLER_SEND'   ? 'send the Steam offer'
               :                                        'confirm receipt';
  return h('div', { className: 'pending-trade-nudge', role: 'status' },
    h('span', { className: 'pending-trade-nudge-icon' }, '⇄'),
    h('div', { className: 'pending-trade-nudge-text' },
      h('strong', null, isSeller ? 'Buyer is waiting on you' : 'Confirm your trade'),
      ' — "', t.itemName || ('Trade #' + t.id), '": ', action, ' before the auto-release window.'
    ),
    h('a', {
      className: 'pending-trade-nudge-cta',
      href: paths.profile(),
      onClick: () => dismiss(t.id)
    }, 'Open trade'),
    h('button', {
      className: 'pending-trade-nudge-close',
      onClick: () => dismiss(t.id),
      title: 'Dismiss',
      'aria-label': 'Dismiss reminder'
    }, '✕')
  );
}

// Access-denied card (batch 481). Rendered when a non-staff user lands
// on /admin or /csr — cleaner than silently serving the FAQ. Keeps the
// CTA consistent with the rest of the empty-state family.
function StaffAccessDeniedModal({ what, onClose }) {
  /* /admin and /csr are full-page routes — skip backdrop close in
     full-page mode so clicking outside the card doesn't bounce back. */
  const handleBackdropClick = (e) => {
    if (document.querySelector('.site-root.full-page-mode')) return;
    onClose && onClose();
  };
  return h('div', { className: 'modal-backdrop', onClick: handleBackdropClick },
    h('div', { className: 'modal', onClick: (e) => e.stopPropagation(), style: { maxWidth: 440, textAlign: 'center', padding: '32px 24px' } },
      h('div', { style: { marginBottom: 12, display: 'flex', justifyContent: 'center' } },
        h(MaterialIcon, { name: 'lock', size: 40, color: 'var(--text-muted)' })),
      h('h1', { style: { fontSize: 18, fontWeight: 700, color: 'var(--text-primary)', margin: '0 0 8px' } }, 'Staff access only'),
      h('div', { style: { fontSize: 13, color: 'var(--text-muted)', marginBottom: 18, lineHeight: 1.55 } },
        'You need a staff role to open ', what, '. If you think this is a mistake, reach out via ',
        h('a', {
          href: '/support',
          onClick: (e) => { e.preventDefault(); onClose && onClose(); navigate(paths.support()); },
          style: { color: 'var(--accent)' }
        }, 'Support'),
        '.'
      ),
      /* In full-page-mode the parent .modal expands to 1280px which made
         this CTA absurdly wide. Constrain to its natural width + center. */
      h('a', {
        className: 'btn btn-accent',
        href: '/market',
        style: {
          display: 'inline-flex',
          width: 'auto',
          minWidth: '220px',
          maxWidth: '280px',
          margin: '0 auto',
          padding: '10px 22px'
        }
      }, 'Back to marketplace')
    )
  );
}

// ── Stall review row with optional seller reply UI. Always-visible block
// when the review carries a sellerReply; otherwise the seller themselves
// (viewing their own stall) sees a "Reply" button that toggles an inline
// textarea. 300-char cap mirrors the service-layer sanitiser.
function StallReviewRow({ review, isOwner, isAuthor, me, onSaved }) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft]     = useState('');
  const [busy, setBusy]       = useState(false);
  const [err, setErr]         = useState('');
  // Batch 850 — inline reason drawers for admin-remove + report-review.
  // Replace native `window.prompt` that had no ARIA, no multiline input,
  // and silently failed on some mobile browsers. `null` = closed; string
  // = open with that draft text.
  const [adminRemoveDraft, setAdminRemoveDraft] = useState(null);
  const [reportDraft, setReportDraft]           = useState(null);
  // Local mirror so the UI updates optimistically on click; the server
  // returns the authoritative count in the response. We keep local
  // state rather than refetching the whole list every vote because
  // the rails/stall refetch path is expensive (full page worth).
  const [helpfulCount, setHelpfulCount] = useState(Number(review.helpfulCount || 0));
  const [hasVoted, setHasVoted]         = useState(!!review.viewerHasVoted);
  const [voteBusy, setVoteBusy]         = useState(false);
  const canVote = !!me && !isAuthor;
  const toggleHelpful = async () => {
    if (!canVote || voteBusy) return;
    // Optimistic update — flip the state immediately so the click
    // feels responsive. Revert on any non-success response.
    setVoteBusy(true);
    const prevCount = helpfulCount;
    const prevVoted = hasVoted;
    setHasVoted(!prevVoted);
    setHelpfulCount(prevVoted ? Math.max(0, prevCount - 1) : prevCount + 1);
    try {
      const { toggleReviewHelpful } = await import('./api.js');
      const res = await toggleReviewHelpful(review.id);
      if (res && (res.error || res.code)) {
        // Pre-fix: silent revert. User saw the count flip then snap
        // back with no explanation when the server rejected (e.g.
        // banned, rate-limited, network blip). Now: revert + toast
        // so the user understands the click didn't land.
        setHasVoted(prevVoted);
        setHelpfulCount(prevCount);
        try {
          window.dispatchEvent(new CustomEvent('sb:toast', { detail: {
            text: res.message || res.error || 'Could not record your helpful vote — try again.',
            kind: 'err'
          }}));
        } catch (_) {}
        return;
      }
      // Replace optimistic figures with server's authoritative values.
      if (typeof res?.helpfulCount === 'number') setHelpfulCount(res.helpfulCount);
      if (typeof res?.viewerHasVoted === 'boolean') setHasVoted(res.viewerHasVoted);
    } catch (e) {
      // Network / fetch failure path — toggleReviewHelpful's safeJson
      // wrapper returns null on network errors, but a bare throw
      // (CORS, AbortController) lands here. Same revert + toast.
      setHasVoted(prevVoted);
      setHelpfulCount(prevCount);
      try {
        window.dispatchEvent(new CustomEvent('sb:toast', { detail: {
          text: 'Could not reach the server — your helpful vote was not recorded.',
          kind: 'err'
        }}));
      } catch (_) {}
    } finally { setVoteBusy(false); }
  };
  const submit = async (clear = false) => {
    setBusy(true); setErr('');
    try {
      const res = await replyToReview(review.id, clear ? '' : (draft || '').trim());
      if (res && (res.error || res.code)) { setErr(res.message || res.error); return; }
      setEditing(false);
      setDraft('');
      onSaved && onSaved();
    } finally { setBusy(false); }
  };
  // Buyer-side delete for their own reviews. Only shown to the review's
  // author (server still enforces fromUserId == caller). A simple
  // confirm() is deliberate here — a full modal would out-scope the row.
  const onDelete = async () => {
    if (!confirm('Delete this review? This cannot be undone.')) return;
    setBusy(true); setErr('');
    try {
      const { deleteReview } = await import('./api.js');
      const res = await deleteReview(review.id);
      if (res && (res.error || res.code)) { setErr(res.message || res.error); return; }
      onSaved && onSaved();
    } finally { setBusy(false); }
  };
  return h('div', { className: 'stall-review' },
    h('div', { className: 'stall-review-head' },
      h('span', { className: 'stall-review-stars' }, '★'.repeat(review.rating) + '☆'.repeat(5 - review.rating)),
      h('span', { className: 'stall-review-from' }, review.fromDisplayName || 'Anonymous'),
      // Every review is anchored to a VERIFIED trade (ReviewService
      // enforces `trade.state == 'VERIFIED'`), so every row on the stall
      // page can advertise the badge. Mirrors Amazon's "Verified
      // Purchase" — tells future buyers the feedback comes from a real
      // transaction, not a sockpuppet. Tooltip explains the guarantee.
      h('span', {
        style: {
          fontSize: 9, fontWeight: 800, padding: '2px 6px', borderRadius: 3,
          background: 'rgba(34,197,94,0.12)', color: '#22c55e',
          border: '1px solid rgba(34,197,94,0.35)', letterSpacing: 0.3
        },
        title: "Every review on SkinBox is tied to a completed trade — this person actually bought from this seller."
      }, 'Verified buyer'),
      h('span', { className: 'stall-review-time' },
        new Date(review.createdAt).toLocaleDateString(),
        // Batch 745 — "· edited" marker when the author updated the
        // rating or comment after first posting. Future buyers need
        // to know a review was rewritten (a 5★ that started as 1★
        // carries very different signal than a 5★ fresh-take).
        review.editedAt && h('span', {
          style: { marginLeft: 6, fontSize: 10, fontStyle: 'italic', color: 'var(--text-muted)' },
          title: 'Last edited ' + new Date(review.editedAt).toLocaleString()
        }, '· edited')
      ),
      // Helpful vote — CSFloat-style upvote so high-signal reviews
      // (detail, context) bubble above one-liners. Self-authors + anon
      // viewers see the count as static text; signed-in non-authors
      // get a clickable toggle. Zero count stays silent to avoid
      // visual noise on stalls that are just starting out.
      (helpfulCount > 0 || canVote) && h('button', {
        className: 'stall-review-helpful',
        onClick: toggleHelpful,
        disabled: !canVote || voteBusy,
        style: {
          marginLeft: 8, padding: '2px 8px', fontSize: 11, fontWeight: 700,
          borderRadius: 12, border: '1px solid var(--border)',
          background: hasVoted ? 'rgba(30,165,255,0.15)' : 'transparent',
          color: hasVoted ? 'var(--accent)' : 'var(--text-muted)',
          cursor: canVote ? (voteBusy ? 'wait' : 'pointer') : 'default'
        },
        title: !me
          ? 'Sign in to mark reviews as helpful'
          : isAuthor
            ? "You can't vote on your own review"
            : (hasVoted ? 'Click to undo your helpful vote' : 'Mark this review as helpful')
      }, hasVoted ? 'Helpful · ' : 'Helpful · ', helpfulCount)
    ),
    review.itemName && h('div', { className: 'stall-review-item' }, '↳ ' + review.itemName),
    review.comment && h('div', { className: 'stall-review-body' }, review.comment),
    review.sellerReply && !editing && h('div', { className: 'stall-review-reply' },
      h('span', { className: 'stall-review-reply-label' }, 'Seller response'),
      h('div', { className: 'stall-review-reply-body' }, review.sellerReply)
    ),
    isOwner && !editing && h('div', { style: { marginTop: 8, display: 'flex', gap: 8 } },
      h('button', {
        className: 'btn btn-ghost',
        style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11 },
        onClick: () => { setDraft(review.sellerReply || ''); setEditing(true); }
      }, review.sellerReply ? '✎ Edit reply' : '↩ Reply'),
      review.sellerReply && h('button', {
        className: 'btn btn-ghost',
        style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '4px 10px', fontSize: 11 },
        onClick: () => submit(true)
      }, 'Remove reply')
    ),
    isAuthor && !editing && h('div', { style: { marginTop: 8, display: 'flex', gap: 8 } },
      h('button', {
        className: 'btn btn-ghost',
        style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '4px 10px', fontSize: 11 },
        disabled: busy,
        onClick: onDelete,
        title: 'Permanently delete your review'
      }, busy ? 'Deleting…' : 'Delete my review')
    ),
    // Admin-moderation delete (batch 480). Renders when the viewer is
    // staff AND is NOT the buyer themselves (they already have the
    // self-delete button above). Uses the admin override endpoint
    // that bypasses the self-only check + pings the buyer with the
    // staff-supplied reason.
    me && me.role === 'ADMIN' && !isAuthor && !editing && h('div', {
      style: { marginTop: 8, display: 'flex', flexDirection: 'column', gap: 8 }
    },
      h('div', { style: { display: 'flex', gap: 8, alignItems: 'center' } },
        h('button', {
          className: 'btn btn-ghost',
          style: {
            border: '1px solid rgba(251,191,36,0.4)', color: '#fbbf24',
            padding: '4px 10px', fontSize: 11
          },
          disabled: busy,
          'aria-haspopup': 'dialog',
          'aria-expanded': adminRemoveDraft !== null,
          title: 'Admin override — remove this review and notify the buyer',
          onClick: () => setAdminRemoveDraft(adminRemoveDraft === null ? 'Violates the community guidelines' : null)
        }, busy ? 'Removing…' : 'Admin remove')
      ),
      adminRemoveDraft !== null && h(ReasonDrawer, {
        title: 'Remove this review',
        hint: 'Reason sent to the buyer and logged as REVIEW_DELETED_STAFF.',
        initial: adminRemoveDraft,
        cta: 'Remove review',
        busy,
        onCancel: () => setAdminRemoveDraft(null),
        onSubmit: async (reason) => {
          if (!reason) return;
          setBusy(true); setErr('');
          try {
            const r = await fetch('/api/admin/reviews/' + review.id, {
              method: 'DELETE',
              credentials: 'same-origin',
              headers: {
                'Content-Type': 'application/json',
                'X-CSRF-Token': (document.cookie.match(/sbox_csrf=([^;]+)/) || [])[1] || ''
              },
              body: JSON.stringify({ reason })
            });
            if (!r.ok) { setErr('Admin remove failed (HTTP ' + r.status + ')'); return; }
            setAdminRemoveDraft(null);
            onSaved && onSaved();
          } finally { setBusy(false); }
        }
      })
    ),
    // Batch 570 — "Report review" for non-author / non-owner signed-in
    // viewers. Reviews are public and occasionally carry harassment,
    // PII, or off-topic rants; before this button there was no way
    // for a visitor to flag one without typing a generic support
    // ticket. Routes through the existing createSupportTicket
    // endpoint with a pre-filled body so staff sees the review id +
    // excerpt inline.
    me && !isAuthor && !isOwner && !editing && h('div', {
      style: { marginTop: 6, display: 'flex', flexDirection: 'column', gap: 6 }
    },
      h('div', { style: { display: 'flex', gap: 6 } },
        h('button', {
          className: 'btn btn-ghost',
          style: {
            border: '1px solid rgba(248,113,113,0.25)', color: 'var(--text-muted)',
            padding: '3px 10px', fontSize: 11
          },
          'aria-haspopup': 'dialog',
          'aria-expanded': reportDraft !== null,
          'aria-controls': `review-report-drawer-${review.id}`,
          title: 'Report this review — staff will review and remove if it violates the community guidelines',
          onClick: () => setReportDraft(reportDraft === null ? '' : null)
        }, 'Report')
      ),
      reportDraft !== null && h('div', { id: `review-report-drawer-${review.id}` },
       h(ReasonDrawer, {
        title: 'Report this review',
        hint: 'What\'s wrong with this review? (harassment, off-topic, PII, etc.) Staff will triage.',
        initial: reportDraft,
        cta: 'File report',
        busy,
        onCancel: () => setReportDraft(null),
        onSubmit: async (reason) => {
          if (!reason) return;
          setBusy(true);
          try {
            const excerpt = (review.comment || '').substring(0, 300);
            const { createSupportTicket } = await import('./api.js');
            const res = await createSupportTicket({
              category: 'ACCOUNT',
              subject:  `Report review #${review.id}`,
              body:     `Review ${review.id} (${review.rating}★) by ${review.fromDisplayName || 'anonymous'}:\n\n> ${excerpt.split('\n').join('\n> ')}\n\nReporter's note:\n\n${reason}`
            });
            if (res && (res.error || res.code)) {
              try {
                window.dispatchEvent(new CustomEvent('sb:toast', { detail: {
                  text: res.message || res.error || 'Could not file the report — try again later.',
                  kind: 'err'
                }}));
              } catch (_) {}
              return;
            }
            setReportDraft(null);
            try {
              window.dispatchEvent(new CustomEvent('sb:toast', { detail: {
                text: 'Report filed — staff will reach out if needed.',
                kind: 'ok'
              }}));
            } catch (_) {}
          } finally { setBusy(false); }
        }
      })
      )
    ),
    isAuthor && err && h('div', { className: 'wallet-error', style: { marginTop: 6 } }, err),
    isOwner && editing && h('div', { className: 'stall-review-reply-edit' },
      h('textarea', {
        value: draft,
        onChange: e => setDraft(e.target.value),
        maxLength: 300,
        'aria-label': 'Public reply to this review',
        placeholder: 'Public response to this review (300 chars max)',
        autoFocus: true
      }),
      h('div', { style: { display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 8 } },
        h('button', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)' }, disabled: busy, onClick: () => { setEditing(false); setDraft(''); setErr(''); } }, 'Cancel'),
        h('button', { className: 'btn btn-accent', disabled: busy || !draft.trim(), onClick: () => submit(false) }, busy ? 'Saving…' : 'Post reply')
      ),
      err && h('div', { className: 'wallet-error' }, err)
    )
  );
}

// ── Announcement banner — renders the single live sitewide message.
// Polls every 120s so banners posted mid-session still land without a
// page reload. Dismissible per-user in localStorage (keyed by
// announcement id) so an admin can post a fresh banner and everyone
// sees it again even if they dismissed the previous one.
// Subtle nag banner for signed-in users who haven't confirmed their email
// yet. Required for withdrawals and 2FA recovery — a silently-unverified
// account is a footgun six months in when the user can't reset their 2FA.
// Dismissable for 7 days via localStorage so the banner isn't permanent
// noise for long power sessions; the day-bucket cooldown resets naturally.
function EmailVerifyNag({ me }) {
  const [dismissedAt, setDismissedAt] = useState(() => {
    try { return Number(localStorage.getItem('sb_email_nag_dismissed_at') || 0); }
    catch { return 0; }
  });
  // Inline resend state (batch 482) — lets the user re-trigger the
  // verification email without navigating to Profile. Flashes "✓ sent"
  // for 4s on success so the user knows to check their inbox.
  const [resendState, setResendState] = useState(''); // '' | 'busy' | 'sent' | 'err'
  if (!me) return null;
  if (me.emailVerified) return null;
  if (!me.email) return null;
  // Seven-day cooldown — matches CSFloat's email reminder cadence.
  if (dismissedAt && (Date.now() - dismissedAt) < 7 * 24 * 3600_000) return null;
  const dismiss = () => {
    try { localStorage.setItem('sb_email_nag_dismissed_at', String(Date.now())); } catch (_) {}
    setDismissedAt(Date.now());
  };
  const doResend = async () => {
    setResendState('busy');
    try {
      const { resendEmailVerification } = await import('./api.js');
      const res = await resendEmailVerification();
      if (res && (res.error || res.code)) {
        setResendState('err');
        setTimeout(() => setResendState(''), 4000);
        return;
      }
      setResendState('sent');
      setTimeout(() => setResendState(''), 4000);
    } catch (_) {
      setResendState('err');
      setTimeout(() => setResendState(''), 4000);
    }
  };
  return h('div', { className: 'announce-banner sev-warn', role: 'status' },
    h('span', { className: 'announce-banner-icon' }, '✉'),
    h('div', { className: 'announce-banner-text' },
      'Your email ', h('strong', null, me.email), ' is not confirmed yet. ',
      h('a', { href: paths.profile(), style: { color: 'inherit', textDecoration: 'underline', fontWeight: 700 } }, 'Confirm it'),
      ' to enable withdrawals and 2FA recovery.'
    ),
    h('button', {
      className: 'btn btn-ghost',
      style: {
        marginLeft: 10, padding: '4px 10px', fontSize: 11,
        border: '1px solid currentColor', opacity: resendState === 'busy' ? 0.6 : 1
      },
      disabled: resendState === 'busy',
      onClick: doResend,
      title: 'Re-send the verification email to ' + me.email
    },
      resendState === 'sent' ? 'Sent'
        : resendState === 'err' ? '✕ Failed'
        : resendState === 'busy' ? 'Sending…'
        : 'Resend email'),
    h('button', {
      className: 'announce-banner-close',
      onClick: dismiss,
      title: 'Remind me later (7 days)',
      'aria-label': 'Dismiss email verification reminder'
    }, '✕')
  );
}

function AnnouncementBanner() {
  const [ann, setAnn] = useState(null);
  const [dismissedId, setDismissedId] = useState(() => {
    try { return Number(localStorage.getItem('sb_announce_dismissed') || 0); }
    catch { return 0; }
  });
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const data = await fetchAnnouncement();
        if (alive) setAnn(data || null);
      } catch (_) {}
    };
    load();
    // Batch 806 — visibility-aware poll. Announcement banner only
    // matters when the user is looking at the page.
    const id = setInterval(() => { if (!document.hidden) load(); }, 120_000);
    const onVis = () => { if (!document.hidden) load(); };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      alive = false; clearInterval(id);
      document.removeEventListener('visibilitychange', onVis);
    };
  }, []);
  if (!ann || ann.id === dismissedId) return null;
  const dismiss = () => {
    try { localStorage.setItem('sb_announce_dismissed', String(ann.id)); } catch (_) {}
    setDismissedId(ann.id);
  };
  const sev = (ann.severity || 'INFO').toLowerCase();
  return h('div', { className: `announce-banner sev-${sev}`, role: 'status' },
    h('span', { className: 'announce-banner-icon' },
      sev === 'critical' ? '⚠' : sev === 'warn' ? '⚠' : 'ℹ'),
    h('div', { className: 'announce-banner-text' }, ann.message),
    h('button', {
      className: 'announce-banner-close',
      onClick: dismiss,
      title: 'Dismiss',
      'aria-label': 'Dismiss announcement'
    }, '✕')
  );
}

// ── Rating breakdown — 5-row bar chart mirroring the Amazon / CSFloat
// review histogram. Each row: "5★  ████████ · 42". Used on stall page
// and Profile Reviews tab so buyers can see at a glance whether the
// rating is bimodal (5★/1★ split) or a smooth distribution.
export function RatingBreakdown({ summary }) {
  if (!summary || !summary.count || summary.count === 0) return null;
  const buckets = Array.isArray(summary.histogram)
    ? summary.histogram
    : [0, 0, 0, 0, 0];
  const max = Math.max(1, ...buckets);
  return h('div', { className: 'rating-breakdown' },
    buckets.map((n, i) => {
      const stars = 5 - i;
      const pct = Math.round((n / max) * 100);
      return h('div', { key: stars, className: 'rating-breakdown-row' },
        h('span', { className: 'rating-breakdown-stars' }, stars + '★'),
        h('div', { className: 'rating-breakdown-bar' },
          h('div', {
            className: 'rating-breakdown-fill',
            style: { width: pct + '%' }
          })
        ),
        h('span', { className: 'rating-breakdown-count' }, n)
      );
    })
  );
}

// ── Nav offers badge — actionable pending-incoming count. Only signed-in
// users see it; polls every 45s; clicking navigates to /offers.
function NavOffersBadge() {
  const [count, setCount] = useState(0);
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const d = await fetchOfferCounts();
        if (alive) setCount(Number(d?.incomingPending || 0));
      } catch (_) {}
    };
    load();
    // Batch 806 — visibility-gated poll. Parity with NotificationBell
    // so a user with dozens of backgrounded tabs doesn't fire a badge
    // count refresh every 45s from each.
    const id = setInterval(() => { if (!document.hidden) load(); }, 45_000);
    const onVis = () => { if (!document.hidden) load(); };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      alive = false; clearInterval(id);
      document.removeEventListener('visibilitychange', onVis);
    };
  }, []);
  return h('a', {
    className: 'nav-icon-btn',
    href: paths.offers(),
    title: count > 0 ? `${count} offer${count === 1 ? '' : 's'} awaiting` : 'Offers',
    'aria-label': count > 0 ? `Offers (${count} pending)` : 'Offers'
  },
    h(MaterialIcon, { name: 'price_check', size: 18 }),
    count > 0 && h('div', { className: 'nav-icon-badge' }, count > 99 ? '99+' : count)
  );
}

// ── NavPicker — small dropdown chip used in the nav for currency + language
// selection. Click toggles a panel that lists options with a flag glyph,
// code, full name, and a "Soon" badge for ones not yet wired. Closes on
// outside click + Escape. Active option is highlighted.
function NavPicker({ label, ariaLabel, options, onSelect }) {
  const [open, setOpen] = useState(false);
  const ref = React.useRef(null);
  // Stable id so the trigger's aria-controls points at the panel —
  // WCAG 4.1.2 requires aria-expanded to pair with aria-controls so a
  // screen reader can resolve "what does opening this control reveal".
  // useState + useId fallback (older React-via-CDN may not have useId).
  const [panelId] = useState(() => 'nav-picker-panel-' + Math.random().toString(36).slice(2, 9));
  useEffect(() => {
    if (!open) return;
    const onDoc = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false); };
    const onKey = (e) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', onDoc);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDoc);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);
  return h('div', { className: 'nav-picker' + (open ? ' open' : ''), ref, 'aria-label': ariaLabel },
    h('button', {
      type: 'button',
      className: 'nav-picker-chip',
      onClick: () => setOpen(o => !o),
      'aria-haspopup': 'listbox',
      'aria-expanded': open,
      'aria-controls': panelId
    },
      h('span', { className: 'nav-picker-label' }, label),
      h('span', { className: 'nav-picker-caret', 'aria-hidden': true }, '▾')
    ),
    open && h('div', { className: 'nav-picker-panel', id: panelId, role: 'listbox' },
      options.map(opt => h('button', {
        key: opt.code,
        type: 'button',
        role: 'option',
        'aria-selected': opt.code === label,
        className: 'nav-picker-row' + (opt.soon ? ' soon' : '') + (opt.code === label ? ' active' : ''),
        disabled: !!opt.soon,
        onClick: () => {
          if (opt.soon) return;
          onSelect && onSelect(opt.code);
          setOpen(false);
        }
      },
        h('span', { className: 'nav-picker-flag', 'aria-hidden': true }, opt.flag),
        h('span', { className: 'nav-picker-code' }, opt.code),
        h('span', { className: 'nav-picker-name' }, opt.name),
        opt.soon && h('span', { className: 'nav-picker-soon' }, 'Soon'),
        opt.code === label && !opt.soon && h(MaterialIcon, { name: 'check', size: 14 })
      ))
    )
  );
}

// ── SortPicker — chip-style dropdown for the /market sort selector. Drops
// the native <select> for a custom panel with per-option icon + label.
// Consistent with NavPicker visually so the toolbar feels coherent.
function SortPicker({ value, options, onChange }) {
  const [open, setOpen] = useState(false);
  const ref = React.useRef(null);
  // aria-expanded must pair with aria-controls — same WCAG 4.1.2
  // requirement as NavPicker (see comment there).
  const [panelId] = useState(() => 'sort-picker-panel-' + Math.random().toString(36).slice(2, 9));
  useEffect(() => {
    if (!open) return;
    const onDoc = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false); };
    const onKey = (e) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', onDoc);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDoc);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);
  const active = options.find(o => o.value === value) || options[0];
  return h('div', { className: 'sort-picker' + (open ? ' open' : ''), ref },
    h('button', {
      type: 'button',
      className: 'sort-picker-chip',
      onClick: () => setOpen(o => !o),
      'aria-haspopup': 'listbox',
      'aria-expanded': open,
      'aria-controls': panelId,
      'aria-label': 'Sort listings'
    },
      active && h(MaterialIcon, { name: active.icon || 'sort', size: 14 }),
      h('span', { className: 'sort-picker-label' }, active ? active.label : 'Sort'),
      h('span', { className: 'sort-picker-caret', 'aria-hidden': true }, '▾')
    ),
    open && h('div', { className: 'sort-picker-panel', id: panelId, role: 'listbox' },
      options.map(opt => h('button', {
        key: opt.value,
        type: 'button',
        role: 'option',
        'aria-selected': opt.value === value,
        className: 'sort-picker-row' + (opt.value === value ? ' active' : ''),
        onClick: () => { onChange && onChange(opt.value); setOpen(false); }
      },
        h(MaterialIcon, { name: opt.icon || 'sort', size: 14 }),
        h('span', { className: 'sort-picker-row-label' }, opt.label),
        opt.value === value && h(MaterialIcon, { name: 'check', size: 14 })
      ))
    )
  );
}

// ── Auctions ending soon — polls /api/listings/ending-soon every 30s so
// the rail stays within ~30s of truth. Only renders when there's at least
// one active auction in the window, so the marketplace stays clean when
// nobody's running auctions.
function AuctionsEndingSoonRail({ watchlist, onToggleStar, onOpen }) {
  const [rows, setRows] = useState([]);
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const data = await fetchAuctionsEndingSoon(60 * 60 * 1000);
        if (alive) setRows(Array.isArray(data) ? data : []);
      } catch (_) {}
    };
    load();
    // Batch 806 — only poll while the tab is visible. A user with 50
    // background tabs open was firing 50× /ending-soon every 30s for
    // zero visual benefit. Returning to the tab also triggers an
    // immediate refresh via visibilitychange so the rail is fresh the
    // moment they look at it.
    const id = setInterval(() => { if (!document.hidden) load(); }, 30_000);
    const onVis = () => { if (!document.hidden) load(); };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      alive = false; clearInterval(id);
      document.removeEventListener('visibilitychange', onVis);
    };
  }, []);
  if (!rows || rows.length === 0) return null;
  return h('section', { className: 'auctions-ending-soon', 'aria-label': 'Auctions ending soon' },
    h('h2', { className: 'auctions-ending-soon-head' },
      h('span', { className: 'auctions-ending-soon-dot' }),
      h('span', null, 'Auctions ending soon'),
      h('span', { className: 'auctions-ending-soon-count' }, `${rows.length} live`)
    ),
    h('div', { className: 'auctions-ending-soon-rail' },
      rows.map(l => h('div', {
        key: 'ends-' + l.id,
        className: 'auctions-ending-soon-card-wrap'
        // Batch 932 — removed wrapper onClick (see just-listed rail).
      },
        h(GridCard, {
          listing: l,
          starred: watchlist.includes(l.item.id),
          onToggleStar,
          onClick: () => onOpen(l)
        })
      ))
    )
  );
}

// ── Top sellers rail — aggregate the highest-volume sellers by completed
// sales count and surface them for social proof. Anon-friendly (public
// endpoint), hides when the platform has no sellers meeting the threshold.
// 5-minute poll is plenty — the aggregate shifts on the hours-to-days
// scale, not seconds.
function TopSellersRail() {
  const [rows, setRows] = useState([]);
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        // Rolling 7-day window per CSFloat Manual §4. "This week's top
        // sellers" is more honest social proof than the all-time
        // leaderboard (which freezes in a few veterans forever).
        const data = await fetchTopSellers(7, 8);
        if (alive) setRows(Array.isArray(data) ? data : []);
      } catch (_) {}
    };
    load();
    // Batch 806 — visibility-aware poll.
    const id = setInterval(() => { if (!document.hidden) load(); }, 5 * 60_000);
    return () => { alive = false; clearInterval(id); };
  }, []);
  if (!rows || rows.length === 0) return null;
  return h('section', { className: 'top-sellers-rail', 'aria-label': 'Top sellers this week' },
    h('h2', { className: 'top-sellers-head' },
      h('span', { className: 'section-title-dot' }),
      h('span', null, 'Top sellers · this week'),
      h('span', { className: 'top-sellers-count' }, `${rows.length} active`)
    ),
    h('div', { className: 'top-sellers-track' },
      rows.map((s, idx) => h('a', {
        key: 'ts-' + s.sellerUserId,
        href: paths.stall(s.sellerUserId),
        className: 'top-seller-card',
        title: `#${idx + 1} this week — ${s.saleCount} sale${s.saleCount === 1 ? '' : 's'} totalling ${fmt(s.totalRevenue || 0)}`
      },
        // Rank ribbon for #1-3 — simple gold/silver/bronze dot with the
        // ordinal. Past rank 3 we drop the ribbon to keep the rail from
        // looking award-ceremony noisy.
        idx < 3 && h('span', {
          className: 'top-seller-rank',
          style: {
            position: 'absolute', top: 6, left: 6, fontSize: 10, fontWeight: 800,
            background: idx === 0 ? '#fbbf24' : idx === 1 ? '#cbd5e1' : '#d97706',
            color: '#0b0f1a', padding: '2px 6px', borderRadius: 10, letterSpacing: 0.3
          }
        }, '#' + (idx + 1)),
        h('div', { className: 'top-seller-avatar' },
          h(Avatar, {
            src: s.avatarUrl,
            name: s.displayName || 'Player',
            alt: s.displayName,
            style: { width: '100%', height: '100%', borderRadius: 'inherit',
                     background: 'transparent', border: 'none', fontSize: 14 }
          })
        ),
        h('div', { className: 'top-seller-body' },
          h('div', { className: 'top-seller-name' },
            s.displayName || 'Player'
          ),
          h('div', { className: 'top-seller-meta' },
            `${s.saleCount} sale${s.saleCount === 1 ? '' : 's'}`,
            (s.rating && s.rating.count > 0)
              ? ` · ★ ${Number(s.rating.average || 0).toFixed(1)}`
              : ''
          )
        )
      ))
    )
  );
}

// Batch 669 — highlight helper for FindSellerBar. Splits `text` on the
// (case-insensitive) first occurrence of `needle` and wraps the match in
// a <mark>-ish span. Returns the original text when needle is empty or
// no match — callers can drop it straight into a React render.
function renderHighlighted(text, needle) {
  const str = String(text || '');
  const n = String(needle || '').trim();
  if (!n) return h('span', null, str);
  const lower = str.toLowerCase();
  const idx = lower.indexOf(n.toLowerCase());
  if (idx < 0) return h('span', null, str);
  return h('span', null,
    str.slice(0, idx),
    h('span', {
      style: { background: 'rgba(30,165,255,0.25)', color: 'inherit',
               borderRadius: 3, padding: '0 2px' }
    }, str.slice(idx, idx + n.length)),
    str.slice(idx + n.length)
  );
}

// ── Find-a-seller search bar (batch 666). CSFloat-parity discovery UX:
// a compact input that surfaces matching stalls by display name. Debounced
// 250ms so each keystroke doesn't round-trip. Only fires after 2+ chars
// to match the server's short-circuit. Clicking a result deep-links to
// the public stall.
function FindSellerBar() {
  const [q, setQ] = useState('');
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [cursor, setCursor] = useState(-1);
  const rootRef = useRef(null);
  useEffect(() => {
    const trimmed = (q || '').trim();
    if (trimmed.length < 2) { setRows([]); setLoading(false); return; }
    setLoading(true);
    let alive = true;
    const t = setTimeout(async () => {
      try {
        const data = await searchSellers(trimmed, 10);
        if (alive) { setRows(data); setLoading(false); setCursor(-1); }
      } catch { if (alive) setLoading(false); }
    }, 250);
    return () => { alive = false; clearTimeout(t); };
  }, [q]);
  // Batch 672 — click-outside-to-close so the dropdown disappears when
  // the user clicks back out to the grid. Without this, the dropdown
  // persists obscuring the first row of listings until the user clicks
  // the ✕ or types over it.
  useEffect(() => {
    if (!open) return;
    const onDocClick = (e) => {
      if (rootRef.current && !rootRef.current.contains(e.target)) setOpen(false);
    };
    document.addEventListener('mousedown', onDocClick);
    return () => document.removeEventListener('mousedown', onDocClick);
  }, [open]);
  return h('section', { ref: rootRef, className: 'find-seller-bar', style: {
    margin: '16px auto 8px', maxWidth: 560, position: 'relative'
  }},
    h('div', { style: { display: 'flex', gap: 8, alignItems: 'center' } },
      h('input', {
        className: 'price-input',
        type: 'search',
        enterKeyHint: 'search',
        autoComplete: 'off',
        'aria-label': 'Search sellers by name',
        style: { flex: 1, fontSize: 14 },
        placeholder: 'Find a seller by name…',
        value: q,
        onChange: e => { setQ(e.target.value); setOpen(true); setCursor(-1); },
        onFocus: () => setOpen(true),
        // Batch 672 — keyboard navigation. ArrowDown/Up move the
        // cursor, Enter opens the highlighted (or first) row, Escape
        // closes the dropdown.
        onKeyDown: e => {
          if (!open || rows.length === 0) return;
          if (e.key === 'ArrowDown') {
            e.preventDefault();
            setCursor(c => Math.min(c + 1, rows.length - 1));
          } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            setCursor(c => Math.max(c - 1, -1));
          } else if (e.key === 'Enter') {
            const pick = cursor >= 0 ? rows[cursor] : rows[0];
            if (pick) {
              e.preventDefault();
              navigate(paths.stall(pick.sellerUserId));
              setOpen(false);
            }
          } else if (e.key === 'Escape') {
            setOpen(false);
          }
        }
      }),
      q && h('button', {
        className: 'btn btn-ghost',
        style: { padding: '6px 10px', fontSize: 12 },
        onClick: () => { setQ(''); setRows([]); setOpen(false); },
        'aria-label': 'Clear seller search'
      }, '✕')
    ),
    open && q.trim().length >= 2 && h('div', {
      className: 'find-seller-dropdown',
      role: 'listbox',
      'aria-label': 'Seller search results',
      style: {
        position: 'absolute', top: '100%', left: 0, right: 0,
        background: 'var(--surface, #0f1524)',
        border: '1px solid var(--border, #1f2937)',
        borderRadius: 8, marginTop: 4,
        maxHeight: 360, overflowY: 'auto', zIndex: 100,
        boxShadow: '0 8px 24px rgba(0,0,0,.3)'
      }
    },
      loading && h('div', { style: { padding: 12, fontSize: 12, opacity: .7 } }, 'Searching…'),
      !loading && rows.length === 0 && h('div', {
        style: { padding: 12, fontSize: 12, opacity: .7 }
      }, 'No sellers match.'),
      !loading && rows.map((s, i) => h('a', {
        key: 'fs-' + s.sellerUserId,
        href: paths.stall(s.sellerUserId),
        className: 'find-seller-row',
        role: 'option',
        'aria-selected': cursor === i,
        onMouseEnter: () => setCursor(i),
        style: {
          display: 'flex', alignItems: 'center', gap: 10,
          padding: '10px 12px', textDecoration: 'none',
          color: 'inherit', borderBottom: '1px solid var(--border-soft, #111827)',
          background: cursor === i ? 'var(--bg-elev, #111827)' : 'transparent'
        }
      },
        h(Avatar, { src: s.avatarUrl, name: s.displayName || 'Player',
          style: { width: 32, height: 32, borderRadius: 8, fontSize: 13 } }),
        h('div', { style: { flex: 1, minWidth: 0 } },
          h('div', { style: { fontWeight: 600, fontSize: 13,
            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
            display: 'flex', alignItems: 'center', gap: 6 } },
            // Batch 669 — highlight the matched substring in the display
            // name so the user's eye can jump straight to "why this row
            // matched my query". Case-insensitive match + bold <mark>.
            renderHighlighted(s.displayName || 'Player', q),
            s.verified && h('span', {
              title: 'Verified seller · 10+ sales · ≥4.0★',
              style: {
                background: '#1ea5ff', color: '#fff',
                fontSize: 10, fontWeight: 700, padding: '1px 5px',
                borderRadius: 8, letterSpacing: 0.2, lineHeight: 1.2
              }
            }, '✓')
          ),
          h('div', { style: { fontSize: 11, opacity: .7 } },
            `${s.activeListings || 0} active · ${s.soldCount || 0} sold`,
            // Batch 675 — inline rating chip. Only renders when the
            // seller has reviews so new sellers don't show a "0.00★"
            // deterrent.
            s.ratingCount > 0 && h('span', { style: { marginLeft: 6 } },
              '· ★ ', Number(s.ratingAverage).toFixed(1),
              ' (', s.ratingCount, ')'))
        ),
        h('span', { style: { fontSize: 11, opacity: .5 } }, '→')
      ))
    )
  );
}

// ── Top deals — deepest-discount rail. Reads /api/listings/top-deals
// (sorted by price/steamPrice ratio) and renders a compact horizontal
// strip. Polls every 2 min — deal ordering shifts as sellers re-price.
function TopDealsRail({ watchlist, onToggleStar, onOpen, onAddToCart, cartHas }) {
  const [rows, setRows] = useState([]);
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const data = await fetchTopDeals();
        if (alive) setRows(Array.isArray(data) ? data : []);
      } catch (_) {}
    };
    load();
    // Batch 806 — visibility-aware poll.
    const id = setInterval(() => { if (!document.hidden) load(); }, 120_000);
    const onVis = () => { if (!document.hidden) load(); };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      alive = false; clearInterval(id);
      document.removeEventListener('visibilitychange', onVis);
    };
  }, []);
  if (!rows || rows.length === 0) return null;
  return h('section', { className: 'top-deals-rail' },
    h('div', { className: 'top-deals-head' },
      h('span', { className: 'top-deals-spark' }, '%'),
      h('span', null, 'Top deals today'),
      h('span', { className: 'top-deals-count' }, `${rows.length} under Steam price`)
    ),
    h('div', { className: 'top-deals-track' },
      rows.map(l => h('div', {
        key: 'td-' + l.id,
        className: 'top-deals-card-wrap'
        // Batch 932 — removed wrapper onClick (see just-listed rail).
      },
        h(GridCard, {
          listing: l,
          starred: watchlist.includes(l.item.id),
          onToggleStar,
          onClick: () => onOpen(l),
          onAddToCart,
          cartHas
        })
      ))
    )
  );
}

// ── Just listed — "what just dropped" rail. Polls every 45s; hides when
// empty. Sits on the marketplace home below the ending-soon strip.
// Top active buy orders (batch 369) — sellers scanning the homepage
// see "buyers are paying up to $X for Wizard Hat" at a glance, a real
// CSFloat feature. Public endpoint; refreshes every 2 minutes since
// buy orders don't churn fast. Hides when nobody has an active buy
// order. Buyer identities deliberately not rendered (aggregate demand
// signal only, matches /api/buy-orders/top contract).
function TopBuyOrdersRail({ me }) {
  const [rows, setRows] = useState([]);
  useEffect(() => {
    let alive = true;
    const load = async () => {
      // Batch 869 — skip the poll when the tab is hidden. A user with
      // 20 backgrounded SkinBox tabs was otherwise hitting /top every
      // 2 min × 20 for zero visual benefit. Matches the visibility-
      // aware pattern from batch 806 across every other rail.
      if (typeof document !== 'undefined' && document.hidden) return;
      try {
        const r = await fetch('/api/buy-orders/top?limit=8', { credentials: 'same-origin' });
        if (!r.ok) return;
        const data = await r.json();
        if (alive) setRows(Array.isArray(data) ? data : []);
      } catch (_) {}
    };
    load();
    const id = setInterval(load, 2 * 60 * 1000);
    // Tab-focus refresh so a user returning after backgrounding sees
    // fresh demand instead of the cached snapshot from 20 min ago.
    const onVis = () => { if (!document.hidden) load(); };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      alive = false;
      clearInterval(id);
      document.removeEventListener('visibilitychange', onVis);
    };
  }, []);
  if (!rows || rows.length === 0) return null;
  return h('section', {
    className: 'just-listed-rail',
    style: { borderLeftColor: '#fbbf24' }
  },
    h('h2', { className: 'just-listed-head' },
      h('span', { className: 'just-listed-dot', style: { background: '#fbbf24' } }),
      h('span', null, 'Top buy orders'),
      h('span', { className: 'just-listed-count' }, 'active demand'),
      me && h('a', {
        href: '/sell',
        style: { marginLeft: 'auto', fontSize: 11, color: 'var(--accent)', textDecoration: 'none', fontWeight: 600 },
        title: 'List one of these items to fill a buy order instantly'
      }, 'List from inventory →')
    ),
    h('div', {
      style: {
        display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 8,
        padding: '8px 14px'
      }
    },
      rows.map(b => h('a', {
        key: 'bo-' + b.id,
        href: '/item/' + b.itemId,
        style: {
          display: 'flex', alignItems: 'center', gap: 10,
          padding: '8px 10px', borderRadius: 8,
          background: 'var(--bg-card)', border: '1px solid var(--border)',
          color: 'var(--text-primary)', textDecoration: 'none',
          minWidth: 0
        },
        title: `Up to ${fmt(b.maxPrice)} — someone's paying this right now for ${b.itemName || 'this item'}`
      },
        h('img', {
          src: b.itemImageUrl || '',
          alt: '',
          loading: 'lazy',
          style: { width: 32, height: 32, borderRadius: 4, objectFit: 'cover',
                   background: 'rgba(148,163,184,0.1)', flexShrink: 0 },
          onError: e => { e.target.style.display = 'none'; }
        }),
        h('div', { style: { flex: 1, minWidth: 0 } },
          h('div', { style: { fontSize: 12, fontWeight: 700, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' } },
            b.itemName || 'Item #' + b.itemId),
          h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } },
            'Up to ', h('span', {
              style: { color: '#fbbf24', fontWeight: 800, fontFamily: 'JetBrains Mono, monospace' }
            }, fmt(b.maxPrice)),
            // Batch 871 — freshness signal so a seller can tell a "just-
            // posted, likely to fill fast" order from a stale one that's
            // been sitting for weeks. `b.createdAt` has been in the
            // payload since the endpoint shipped but wasn't surfaced.
            b.createdAt && h('span', {
              style: { marginLeft: 6, fontSize: 10, opacity: 0.65 }
            }, '· ', timeAgo(b.createdAt))
          )
        )
      ))
    )
  );
}

function JustListedRail({ watchlist, onToggleStar, onOpen }) {
  const [rows, setRows] = useState([]);
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const data = await fetchJustListed();
        if (alive) setRows(Array.isArray(data) ? data : []);
      } catch (_) {}
    };
    load();
    // Batch 806 — visibility-aware poll.
    const id = setInterval(() => { if (!document.hidden) load(); }, 45_000);
    const onVis = () => { if (!document.hidden) load(); };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      alive = false; clearInterval(id);
      document.removeEventListener('visibilitychange', onVis);
    };
  }, []);
  if (!rows || rows.length === 0) return null;
  return h('section', { className: 'just-listed-rail', 'aria-label': 'Just listed' },
    h('h2', { className: 'just-listed-head' },
      h('span', { className: 'just-listed-dot' }),
      h('span', null, 'Just listed'),
      h('span', { className: 'just-listed-count' }, `${rows.length} fresh`)
    ),
    h('div', { className: 'just-listed-track' },
      rows.map(l => h('div', {
        key: 'just-' + l.id,
        className: 'just-listed-card-wrap'
        // Batch 932 — removed wrapper onClick: GridCard is an <a> that
        // already fires onClick (via handleClick). The wrapper's handler
        // duplicated the onOpen call (click bubbled up) AND made a
        // non-semantic div read as clickable to screen readers without
        // keyboard accessibility.
      },
        h(GridCard, {
          listing: l,
          starred: watchlist.includes(l.item.id),
          onToggleStar,
          onClick: () => onOpen(l)
        })
      ))
    )
  );
}

// ── Most-watched — pure social proof using the V30 watchlist data
// (batch 273). Polls every 5 minutes — watcher counts move slowly and
// the underlying query touches every star ever placed; not worth a
// faster cadence. Hides when no items have been starred yet.
function MostWatchedRail({ watchlist, onToggleStar, onOpen, onAddToCart, cartHas }) {
  const [rows, setRows] = useState([]);
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const r = await fetch('/api/listings/most-watched?limit=8', { credentials: 'same-origin' });
        if (!alive || !r.ok) return;
        const data = await r.json();
        setRows(Array.isArray(data) ? data : []);
      } catch (_) {}
    };
    load();
    // Batch 806 — visibility-aware poll.
    const id = setInterval(() => { if (!document.hidden) load(); }, 5 * 60_000);
    return () => { alive = false; clearInterval(id); };
  }, []);
  if (!rows || rows.length === 0) return null;
  return h('section', { className: 'just-listed-rail' },
    h('h2', { className: 'just-listed-head' },
      h('span', { className: 'section-title-dot' }),
      h('span', null, 'Most watched right now'),
      h('span', { className: 'just-listed-count' }, `${rows.length} popular`)
    ),
    h('div', { className: 'just-listed-track' },
      rows.map(l => h('div', {
        key: 'mw-' + l.id,
        className: 'just-listed-card-wrap'
        // Batch 932 — removed wrapper onClick: GridCard is an <a> that
        // already fires onClick (via handleClick). The wrapper's handler
        // duplicated the onOpen call (click bubbled up) AND made a
        // non-semantic div read as clickable to screen readers without
        // keyboard accessibility.
      },
        h(GridCard, {
          listing: l,
          starred: watchlist.includes(l.item.id),
          onToggleStar,
          onClick: () => onOpen(l),
          onAddToCart,
          cartHas
        })
      ))
    )
  );
}

// Most-viewed rail (batch 412) — passive-interest signal from the V46
// view_count counter bumped on every /api/items/{id} GET. Complement
// to MostWatched (users who explicitly starred) and Hottest (realised
// sales) — this is "people are clicking through to look, whether or
// not they converted". Polls every 5 minutes. Silent when no items
// have views > 0 (cold start).
function MostViewedRail({ watchlist, onToggleStar, onOpen, onAddToCart, cartHas }) {
  const [rows, setRows] = useState([]);
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const r = await fetch('/api/listings/most-viewed?limit=8', { credentials: 'same-origin' });
        if (!alive || !r.ok) return;
        const data = await r.json();
        setRows(Array.isArray(data) ? data : []);
      } catch (_) {}
    };
    load();
    // Batch 806 — visibility-aware poll.
    const id = setInterval(() => { if (!document.hidden) load(); }, 5 * 60_000);
    return () => { alive = false; clearInterval(id); };
  }, []);
  if (!rows || rows.length === 0) return null;
  return h('section', { className: 'just-listed-rail' },
    h('h2', { className: 'just-listed-head' },
      h('span', { className: 'section-title-dot' }),
      h('span', null, 'Most viewed right now'),
      h('span', { className: 'just-listed-count' }, `${rows.length} trending`)
    ),
    h('div', { className: 'just-listed-track' },
      rows.map(l => h('div', {
        key: 'mv-' + l.id,
        className: 'just-listed-card-wrap'
        // Batch 932 — removed wrapper onClick: GridCard is an <a> that
        // already fires onClick (via handleClick). The wrapper's handler
        // duplicated the onOpen call (click bubbled up) AND made a
        // non-semantic div read as clickable to screen readers without
        // keyboard accessibility.
      },
        h(GridCard, {
          listing: l,
          starred: watchlist.includes(l.item.id),
          onToggleStar,
          onClick: () => onOpen(l),
          onAddToCart,
          cartHas
        })
      ))
    )
  );
}

// ── Hottest right now — uses the V1 SOLD aggregate (batch 289).
// Complement to MostWatchedRail (passive demand): this shows
// realised-volume hot items. Polls every 5min — sales aggregates
// move slowly. Hides cleanly on a fresh platform with no sales.
function HottestRail({ watchlist, onToggleStar, onOpen, onAddToCart, cartHas }) {
  const [rows, setRows] = useState([]);
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const r = await fetch('/api/listings/hottest?limit=8', { credentials: 'same-origin' });
        if (!alive || !r.ok) return;
        const data = await r.json();
        setRows(Array.isArray(data) ? data : []);
      } catch (_) {}
    };
    load();
    // Batch 806 — visibility-aware poll.
    const id = setInterval(() => { if (!document.hidden) load(); }, 5 * 60_000);
    return () => { alive = false; clearInterval(id); };
  }, []);
  if (!rows || rows.length === 0) return null;
  return h('section', { className: 'just-listed-rail', 'aria-label': 'Hot right now' },
    h('h2', { className: 'just-listed-head' },
      h('span', { className: 'section-title-dot', style: { background: 'var(--red)' } }),
      h('span', null, 'Hot right now'),
      h('span', { className: 'just-listed-count' }, `${rows.length} trending · last 7d`)
    ),
    h('div', { className: 'just-listed-track' },
      rows.map(l => h('div', {
        key: 'hot-' + l.id,
        className: 'just-listed-card-wrap'
        // Batch 932 — removed wrapper onClick: GridCard is an <a> that
        // already fires onClick (via handleClick). The wrapper's handler
        // duplicated the onOpen call (click bubbled up) AND made a
        // non-semantic div read as clickable to screen readers without
        // keyboard accessibility.
      },
        h(GridCard, {
          listing: l,
          starred: watchlist.includes(l.item.id),
          onToggleStar,
          onClick: () => onOpen(l),
          onAddToCart,
          cartHas
        })
      ))
    )
  );
}

// Platform-wide "Just sold" ticker. Social proof on the homepage —
// anonymous visitors see the marketplace is live the moment the page
// loads. Polls /api/listings/recent-sales every 30s so new sales land
// in the rail without a manual refresh. Hides itself when the platform
// has no completed sales yet.
// ── Stall bio block — renders the seller's self-written bio on the
// public /stall/{id} page. When the viewer is the stall owner, a tiny
// "✎ Edit" button below the bio opens an inline textarea + save/cancel.
// Clears on empty save. Bio-less stalls show nothing for non-owners
// and a dimmed "Add a bio" prompt for owners so they know the feature
// exists without forcing a nag on every stall.
function StallBioBlock({ bio, canEdit, onSaved }) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft]     = useState('');
  const [busy, setBusy]       = useState(false);
  const [err, setErr]         = useState('');
  const startEdit = () => { setDraft(bio || ''); setEditing(true); setErr(''); };
  const cancel = () => { setEditing(false); setDraft(''); setErr(''); };
  const save = async () => {
    setBusy(true); setErr('');
    try {
      const { setStallBio } = await import('./api.js');
      const res = await setStallBio(draft);
      if (res && (res.error || res.code)) {
        setErr(res.message || res.error || 'Could not save');
        return;
      }
      setEditing(false);
      onSaved && onSaved();
      // Batch 923 — surface save success. Previously the block collapsed
      // back to read-mode silently, and a user who'd scrolled away while
      // the save was in flight had no way to know it landed. Toast copy
      // differentiates first-time add vs. edit. Dispatched on the global
      // `sb:toast` bus since this top-level component has no `showToast`
      // in scope (that const lives inside the App component).
      const wasEmpty = !bio;
      try {
        window.dispatchEvent(new CustomEvent('sb:toast', { detail: {
          text: wasEmpty ? 'Bio added — buyers see it on your stall.' : 'Bio updated.',
          kind: 'ok'
        }}));
      } catch (_) {}
    } finally { setBusy(false); }
  };
  if (!bio && !canEdit) return null;
  if (editing) {
    const CAP = 500;
    return h('div', {
      style: {
        margin: '12px 0 4px', padding: 12, borderRadius: 8,
        background: 'var(--bg-elevated)', border: '1px solid var(--border)'
      }
    },
      h('textarea', {
        value: draft,
        maxLength: CAP,
        onChange: e => setDraft(e.target.value),
        'aria-label': 'Stall bio',
        // Batch 923 — Ctrl+Enter saves so a seller typing a multi-line
        // bio doesn't need to mouse over to Save. Matches the drawer
        // submit pattern from the shared ReasonDrawer.
        // Batch 933 — Esc cancels the edit so keyboard users have a
        // parallel path to the Cancel button without mouse reach.
        onKeyDown: (e) => {
          if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && !busy && draft.trim()) {
            e.preventDefault();
            save();
          } else if (e.key === 'Escape' && !busy) {
            e.stopPropagation();
            cancel();
          }
        },
        placeholder: 'Tell buyers how you trade — response times, preferred payment flow, anything that helps set expectations. Plain text, 500 chars. Ctrl+Enter to save.',
        style: {
          width: '100%', minHeight: 80, padding: '8px 10px',
          background: 'var(--bg-input)', color: 'var(--text-primary)',
          border: '1px solid var(--border)', borderRadius: 6,
          fontFamily: 'inherit', fontSize: 13, resize: 'vertical'
        }
      }),
      h('div', { style: { display: 'flex', gap: 8, marginTop: 8, alignItems: 'center' } },
        h('span', { style: { fontSize: 10, color: 'var(--text-muted)' } },
          `${draft.length} / ${CAP}`),
        err && h('span', { style: { fontSize: 10, color: 'var(--red)', fontWeight: 700 } }, err),
        h('div', { style: { flex: 1 } }),
        h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 12 },
          disabled: busy, onClick: cancel
        }, 'Cancel'),
        h('button', {
          className: 'btn btn-accent',
          style: { padding: '6px 14px', fontSize: 12 },
          disabled: busy, onClick: save
        }, busy ? 'Saving…' : 'Save')
      )
    );
  }
  if (!bio && canEdit) {
    return h('div', {
      style: { margin: '10px 0 0', fontSize: 12, color: 'var(--text-muted)' }
    },
      h('button', {
        className: 'btn btn-ghost',
        style: { border: '1px dashed var(--border)', padding: '6px 12px', fontSize: 11, opacity: 0.7 },
        onClick: startEdit,
        title: 'Add a short bio for buyers visiting your stall'
      }, '+ Add a bio')
    );
  }
  return h('div', {
    style: {
      margin: '12px 0 4px', padding: 12, borderRadius: 8,
      background: 'var(--bg-elevated)', border: '1px solid var(--border)',
      fontSize: 13, color: 'var(--text-secondary)',
      whiteSpace: 'pre-wrap', wordBreak: 'break-word',
      display: 'flex', gap: 10, alignItems: 'flex-start'
    }
  },
    // Auto-linkify URLs in the bio (batch 442) — sellers often paste a
    // Discord/Twitter/Steam-group contact link.
    h('div', { style: { flex: 1 } }, linkifyText(bio, 'biolnk')),
    canEdit && h('button', {
      className: 'btn btn-ghost',
      style: { flexShrink: 0, padding: '4px 10px', fontSize: 11, border: '1px solid var(--border)' },
      onClick: startEdit,
      title: 'Edit your stall bio'
    }, '✎ Edit')
  );
}

// ── Platform stats strip — renders under the hero. Pulls the public
// /api/listings/stats endpoint for volume24h + activeListings + floor
// and reshapes them into a trust-signal bar. Silent when the
// marketplace is empty so a fresh install doesn't show "$0 traded".
function MarketStatsStrip() {
  const [s, setS] = useState(null);
  useEffect(() => {
    let alive = true;
    const load = async () => {
      const data = await fetchMarketStats();
      if (alive) setS(data);
    };
    load();
    // 5-minute poll — the endpoint is a single indexed aggregate query
    // so refreshing isn't expensive, but stats don't change fast enough
    // to need anything snappier. Batch 806 — gate on visibility so
    // backgrounded tabs don't tick.
    const id = setInterval(() => { if (!document.hidden) load(); }, 5 * 60 * 1000);
    return () => { alive = false; clearInterval(id); };
  }, []);
  if (!s) return null;
  const active   = Number(s.activeListings || 0);
  const auctions = Number(s.activeAuctions || 0);
  const vol      = parseFloat(s.volume24h || 0);
  const floor    = parseFloat(s.floorPrice || 0);
  if (active === 0 && vol === 0) return null;  // empty-state guard
  /* M1 (Boss QA): legible, hierarchical labels — was 10px uppercase
     packed too tight at 1920×1080 so labels read as garbled chrome.
     Bumped label to 11px / 0.08em tracking, value to 16px. Dropped the
     "Marketplace at a glance" lede that read as another data label. */
  const labelStyle = { fontSize: 11, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.08em', fontWeight: 700 };
  const valueStyle = { fontSize: 16, fontWeight: 800, color: 'var(--text-primary)', fontFamily: 'JetBrains Mono, monospace', whiteSpace: 'nowrap' };
  const Stat = (label, value) => h('div', {
    style: {
      display: 'flex', flexDirection: 'column', gap: 4,
      minWidth: 0
    }
  },
    h('span', { style: labelStyle }, label),
    h('span', { style: valueStyle }, value)
  );
  return h('section', {
    className: 'market-stats-strip',
    style: {
      margin: '18px auto 0',
      maxWidth: 1260,
      padding: '14px 20px',
      borderRadius: 10,
      background: 'var(--bg-card)',
      border: '1px solid var(--border)',
      display: 'flex', gap: 40, flexWrap: 'wrap', alignItems: 'center'
    }
  },
    (() => {
      const sellers = Number(s.activeSellers || 0);
      if (sellers > 0 && active > 0) {
        return h('div', { style: { display: 'flex', flexDirection: 'column', gap: 4 } },
          h('span', { style: labelStyle }, 'Active listings'),
          h('span', { style: valueStyle },
            active.toLocaleString(),
            h('span', { style: { color: 'var(--text-secondary)', fontWeight: 600, fontSize: 13 } },
              ` · ${sellers.toLocaleString()} seller${sellers === 1 ? '' : 's'}`)
          )
        );
      }
      return Stat('Active listings', active.toLocaleString());
    })(),
    auctions > 0 && h('a', {
      href: '/market?type=AUCTION',
      style: { textDecoration: 'none' },
      title: 'Browse all active auctions'
    },
      h('div', {
        style: { display: 'flex', flexDirection: 'column', gap: 4, minWidth: 0 }
      },
        h('span', { style: labelStyle }, 'Live auctions'),
        h('span', { style: { ...valueStyle, color: 'var(--accent)' } }, auctions.toLocaleString())
      )
    ),
    (() => {
      if (vol <= 0) return null;
      const count = Number(s.sold24h || 0);
      if (count > 0) {
        return h('div', { style: { display: 'flex', flexDirection: 'column', gap: 4 } },
          h('span', { style: labelStyle }, '24h volume'),
          h('span', { style: valueStyle },
            fmt(vol),
            h('span', { style: { color: 'var(--text-secondary)', fontWeight: 600, fontSize: 13 } },
              ` · ${count} sale${count === 1 ? '' : 's'}`)
          )
        );
      }
      return Stat('24h volume', fmt(vol));
    })(),
    (() => {
      const vol7 = parseFloat(s.volume7d || 0);
      if (vol7 <= 0) return null;
      const count7 = Number(s.sold7d || 0);
      if (count7 > 0) {
        return h('div', { style: { display: 'flex', flexDirection: 'column', gap: 4 } },
          h('span', { style: labelStyle }, '7d volume'),
          h('span', { style: valueStyle },
            fmt(vol7),
            h('span', { style: { color: 'var(--text-secondary)', fontWeight: 600, fontSize: 13 } },
              ` · ${count7} sale${count7 === 1 ? '' : 's'}`)
          )
        );
      }
      return Stat('7d volume', fmt(vol7));
    })(),
    (() => {
      const ceiling = parseFloat(s.ceilingPrice || 0);
      if (floor > 0 && ceiling > floor) {
        return h('div', { style: { display: 'flex', flexDirection: 'column', gap: 4 } },
          h('span', { style: labelStyle }, 'Lowest price'),
          h('span', { style: valueStyle },
            fmt(floor), h('span', { style: { color: 'var(--text-secondary)', fontWeight: 600, fontSize: 13 } }, ' – '), fmt(ceiling))
        );
      }
      return floor > 0 ? Stat('Lowest price', fmt(floor)) : null;
    })(),
    s.lastSaleAt && (Date.now() - s.lastSaleAt) < 7 * 24 * 3600_000 && h('div', {
      style: { display: 'flex', flexDirection: 'column', gap: 4 },
      title: 'Most recent settled sale across the marketplace: ' + new Date(s.lastSaleAt).toLocaleString()
    },
      h('span', { style: labelStyle }, 'Last sale'),
      h('span', {
        style: { ...valueStyle, color: 'var(--green)', display: 'inline-flex', alignItems: 'center', gap: 6 }
      },
        // Heartbeat dot visible only for fresh sales (<10 min) so an
        // idle marketplace stops pulsing — otherwise the chip cries
        // wolf on stale "Last sale 4h ago" reads.
        (Date.now() - s.lastSaleAt) < 10 * 60_000 && h('span', {
          style: {
            width: 8, height: 8, borderRadius: '50%',
            background: 'var(--green)',
            boxShadow: '0 0 8px var(--green)',
            animation: 'pulse 1.8s ease-in-out infinite',
            flexShrink: 0
          },
          'aria-hidden': 'true'
        }),
        timeAgo(s.lastSaleAt)
      )
    )
  );
}

// Batch 1068 — inline SVG icon set ported from the operator's template
// (primitives.jsx). Geometrically consistent 24×24 viewbox, 1.8 stroke
// width, currentColor fill. Call with: h(Icon, { name: 'cart', size: 16 }).
// Material Symbols Rounded is the legacy system; Icon is the new one.
function Icon({ name, size }) {
  const s = size || 16;
  const paths = {
    search: h(React.Fragment, null,
      h('circle', { cx: 11, cy: 11, r: 7 }),
      h('path', { d: 'm20 20-3.5-3.5' })),
    cart: h(React.Fragment, null,
      h('path', { d: 'M3 4h2l2.5 11.5a2 2 0 0 0 2 1.5h7.5a2 2 0 0 0 2-1.5L21 8H6' }),
      h('circle', { cx: 10, cy: 20, r: 1 }),
      h('circle', { cx: 18, cy: 20, r: 1 })),
    bell: h(React.Fragment, null,
      h('path', { d: 'M6 8a6 6 0 1 1 12 0c0 5 2 6 2 6H4s2-1 2-6' }),
      h('path', { d: 'M10 19a2 2 0 0 0 4 0' })),
    heart: h('path', { d: 'M12 20s-7-4.35-7-10a4 4 0 0 1 7-2.65A4 4 0 0 1 19 10c0 5.65-7 10-7 10Z' }),
    grid: h(React.Fragment, null,
      h('rect', { x: 3, y: 3, width: 7, height: 7, rx: 1 }),
      h('rect', { x: 14, y: 3, width: 7, height: 7, rx: 1 }),
      h('rect', { x: 3, y: 14, width: 7, height: 7, rx: 1 }),
      h('rect', { x: 14, y: 14, width: 7, height: 7, rx: 1 })),
    rows: h(React.Fragment, null,
      h('rect', { x: 3, y: 4, width: 18, height: 4, rx: 1 }),
      h('rect', { x: 3, y: 10, width: 18, height: 4, rx: 1 }),
      h('rect', { x: 3, y: 16, width: 18, height: 4, rx: 1 })),
    up: h('path', { d: 'm6 15 6-6 6 6' }),
    down: h('path', { d: 'm6 9 6 6 6-6' }),
    arrow: h(React.Fragment, null,
      h('path', { d: 'M5 12h14' }),
      h('path', { d: 'm13 5 7 7-7 7' })),
    plus: h('path', { d: 'M12 5v14M5 12h14' }),
    close: h('path', { d: 'M18 6 6 18M6 6l12 12' }),
    'refresh-cw': h(React.Fragment, null,
      h('path', { d: 'M21 12a9 9 0 1 1-3-6.7L21 8' }),
      h('path', { d: 'M21 3v5h-5' })),
    steam: h(React.Fragment, null,
      h('circle', { cx: 12, cy: 12, r: 9 }),
      h('circle', { cx: 15, cy: 9, r: 2.4 }),
      h('circle', { cx: 8, cy: 14.5, r: 1.4 })),
    wallet: h(React.Fragment, null,
      h('rect', { x: 3, y: 6, width: 18, height: 13, rx: 2 }),
      h('path', { d: 'M16 13h3' }),
      h('path', { d: 'M3 9h14' })),
    check: h('path', { d: 'm5 12 4 4 10-10' }),
    tag: h(React.Fragment, null,
      h('path', { d: 'M3 12V3h9l9 9-9 9-9-9Z' }),
      h('circle', { cx: 7.5, cy: 7.5, r: 1 })),
    lock: h(React.Fragment, null,
      h('rect', { x: 5, y: 11, width: 14, height: 10, rx: 2 }),
      h('path', { d: 'M8 11V8a4 4 0 0 1 8 0v3' })),
    wand: h(React.Fragment, null,
      h('path', { d: 'm4 20 12-12' }),
      h('path', { d: 'M14 6h6v6' }),
      h('path', { d: 'M7 3v2M3 7h2M17 15v2M15 17h2' })),
    eye: h(React.Fragment, null,
      h('path', { d: 'M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Z' }),
      h('circle', { cx: 12, cy: 12, r: 3 })),
    bolt: h('path', { d: 'M13 3 4 14h7l-1 7 9-11h-7l1-7Z' }),
    star: h('path', { d: 'm12 3 2.9 6 6.6.9-4.8 4.6 1.2 6.6L12 18l-5.9 3.1 1.2-6.6L2.5 9.9l6.6-.9L12 3Z' }),
    /* Category icons — premium single-line silhouettes, 24×24 viewbox,
       1.8 stroke width, matching the rest of the Icon set. No emojis,
       no geometric symbols. */
    'cat-hat': h(React.Fragment, null,
      h('path', { d: 'M4 17h16' }),
      h('path', { d: 'M7 17c0-4 2-9 5-9s5 5 5 9' }),
      h('path', { d: 'M4 17c0 1 1 2 3 2h10c2 0 3-1 3-2' })),
    'cat-jacket': h(React.Fragment, null,
      h('path', { d: 'M7 4 4 7v12h6V9h4v10h6V7l-3-3' }),
      h('path', { d: 'M9 4h6l-3 3-3-3Z' })),
    'cat-shirt': h(React.Fragment, null,
      h('path', { d: 'M6 4 4 7l3 3v10h10V10l3-3-2-3' }),
      h('path', { d: 'M9 4a3 3 0 0 0 6 0' })),
    'cat-pants': h(React.Fragment, null,
      h('path', { d: 'M6 4h12v4l-2 12h-3l-1-9-1 9H8L6 8V4Z' }),
      h('path', { d: 'M6 6h12' })),
    'cat-gloves': h(React.Fragment, null,
      h('path', { d: 'M8 20V10a2 2 0 1 1 4 0V4a2 2 0 1 1 4 0v8l2-2a2 2 0 1 1 2 2l-4 4v4Z' })),
    'cat-boots': h(React.Fragment, null,
      h('path', { d: 'M8 4h5v10l5 3v3H5v-5l3-2V4Z' }),
      h('path', { d: 'M8 12h5' })),
    'cat-accessories': h(React.Fragment, null,
      h('circle', { cx: 8, cy: 14, r: 4 }),
      h('circle', { cx: 16, cy: 14, r: 4 }),
      h('path', { d: 'M12 14h0M4 14 2 12M20 14l2-2' })),
    'cat-workshop': h(React.Fragment, null,
      h('path', { d: 'M6 4h12l-2 4H8L6 4Z' }),
      h('path', { d: 'M5 10h14v10H5Z' }),
      h('path', { d: 'M10 14h4v4h-4Z' }))
  };
  return h('svg', {
    width: s, height: s, viewBox: '0 0 24 24', fill: 'none',
    stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round', strokeLinejoin: 'round',
    'aria-hidden': 'true'
  }, paths[name] || null);
}

// Batch 1068 — Market pulse ticker. Scrolling live-tape tape of the most
// recent sold listings across the marketplace. Mirrors the operator's
// template `.pulse` strip (chrome.jsx lines 89–112). Public endpoint,
// anonymous-friendly. Silent when there are no recent sales (fresh
// install) so we don't show an empty tape. Polls every 45s.
function MarketPulse() {
  const [rows, setRows] = useState([]);
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const r = await fetch('/api/listings/recent-sales?limit=14');
        if (!r.ok) return;
        const data = await r.json();
        if (alive) setRows(Array.isArray(data) ? data : []);
      } catch (_) { /* silent */ }
    };
    load();
    const id = setInterval(() => { if (!document.hidden) load(); }, 45000);
    return () => { alive = false; clearInterval(id); };
  }, []);
  if (!rows || rows.length === 0) return null;
  // Duplicate the row list so the css keyframe (`pulse-scroll`) can
  // loop seamlessly — by the time the first copy scrolls past, the
  // second copy is positioned to pick up without a visible seam.
  const doubled = [...rows, ...rows];
  // /api/listings/recent-sales returns the sale price in `price`, not
  // `soldPrice` — reading the wrong field zeroed every row, so the "24H
  // vol" chip was permanently hidden by the `vol24 > 0` guard below.
  // Mirror the same `price ?? soldPrice` fallback the pulse rows use.
  const vol24 = rows.reduce((acc, r) => acc + (parseFloat(r.price ?? r.soldPrice) || 0), 0);
  return h('div', { className: 'pulse', style: { height: 32 } },
    h('span', { className: 'pulse-led' }),
    h('span', { style: { fontWeight: 500, color: 'var(--ink-2)' } }, 'LIVE TAPE'),
    h('div', { className: 'pulse-stream' },
      h('div', { className: 'pulse-track' },
        doubled.map((r, i) => h('span', { key: i, className: 'pulse-item' },
          h('span', { className: 'dot' }),
          h('b', null, r.itemName || r.name || 'Item'),
          /* The /api/listings/recent-sales endpoint returns the sale price
             in the `price` field (not `soldPrice`) — keep the legacy field
             as a fallback in case the API ever changes. */
          h('span', { className: 'up' }, fmt(parseFloat(r.price ?? r.soldPrice) || 0)),
          h('span', { style: { color: 'var(--ink-4)' } }, ' · ' + (r.sellerName || r.sellerDisplayName || 'seller'))
        ))
      )
    ),
    /* Hide the 24h volume chip when there's no recorded volume — "$0 vol"
       reads like a broken stat instead of legitimate idle marketplace. */
    vol24 > 0 && h('span', { style: { color: 'var(--ink-4)' } }, '24H · ',
      h('b', { style: { color: 'var(--ink-2)' } }, fmt(vol24)),
      ' vol'
    )
  );
}

// ── Following feed — fresh listings from sellers the signed-in user
// follows. Silent for anonymous viewers and for users who follow
// nobody yet. Re-polls every 90s since it's personalised and we
// don't want to spam the server with identical follower-fanout
// queries.
function FollowingRail({ me, watchlist, onToggleStar, onOpen, onAddToCart, cartHas }) {
  const [rows, setRows] = useState([]);
  useEffect(() => {
    if (!me) { setRows([]); return; }
    let alive = true;
    const load = async () => {
      try {
        const data = await fetchFollowingFeed();
        if (alive) setRows(Array.isArray(data) ? data : []);
      } catch (_) {}
    };
    load();
    // Batch 806 — visibility-aware poll; FollowingRail's feed only
    // matters while the user is looking at the marketplace page.
    const id = setInterval(() => { if (!document.hidden) load(); }, 90_000);
    const onVis = () => { if (!document.hidden) load(); };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      alive = false; clearInterval(id);
      document.removeEventListener('visibilitychange', onVis);
    };
  }, [me?.id]);
  if (!me || !rows || rows.length === 0) return null;
  return h('section', { className: 'top-deals-rail' },
    h('div', { className: 'top-deals-head' },
      h('span', { className: 'top-deals-spark' }, '♥'),
      h('span', null, 'From sellers you follow'),
      h('span', { className: 'top-deals-count' },
        `${rows.length} listing${rows.length === 1 ? '' : 's'} from your follows`)
    ),
    h('div', { className: 'top-deals-track' },
      rows.map(l => h('div', {
        key: 'follow-' + l.id,
        className: 'top-deals-card-wrap'
        // Batch 932 — removed wrapper onClick (see just-listed rail).
      },
        h(GridCard, {
          listing: l,
          starred: watchlist.includes(l.item.id),
          onToggleStar,
          onClick: () => onOpen(l),
          onAddToCart,
          cartHas
        })
      ))
    )
  );
}

function JustSoldRail() {
  const [rows, setRows] = useState([]);
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const data = await fetchPlatformRecentSales(12);
        if (alive) setRows(Array.isArray(data) ? data : []);
      } catch (_) {}
    };
    load();
    // Batch 806 — visibility-aware polling. Same reasoning as the
    // ending-soon rail: no point burning bandwidth when the user's
    // looking at a different tab.
    const id = setInterval(() => { if (!document.hidden) load(); }, 30_000);
    const onVis = () => { if (!document.hidden) load(); };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      alive = false; clearInterval(id);
      document.removeEventListener('visibilitychange', onVis);
    };
  }, []);
  if (!rows || rows.length === 0) return null;
  return h('section', { className: 'just-listed-rail' },
    h('h2', { className: 'just-listed-head' },
      h('span', { className: 'just-listed-dot', style: { background: '#22c55e' } }),
      h('span', null, 'Just sold'),
      h('span', { className: 'just-listed-count' }, `${rows.length} recent`)
    ),
    h('div', { className: 'just-listed-track' },
      rows.map(s => h('div', {
        key: 'js-' + s.listingId,
        className: 'top-seller-card',
        style: { minWidth: 220, padding: 10 },
      },
        h('div', { className: 'top-seller-body', style: { width: '100%' } },
          h('a', {
            href: s.itemId ? ('/item/' + s.itemId) : '#',
            style: { textDecoration: 'none', color: 'inherit', display: 'block' },
            title: `${s.itemName || 'Item'} sold for ${fmt(s.price)} · ${timeAgo(s.soldAt)}`
          },
            h('div', { className: 'top-seller-name', style: { fontSize: 13, fontWeight: 700 } },
              s.itemName || 'Item'
            ),
            h('div', { className: 'top-seller-meta' },
              h('span', { style: { color: 'var(--green)', fontWeight: 800, fontFamily: 'JetBrains Mono, monospace' } },
                fmt(s.price)),
              h('span', { style: { color: 'var(--text-muted)', marginLeft: 8 } },
                timeAgo(s.soldAt))
            )
          ),
          // Seller byline — previously the ticker showed just item +
          // price, with no way to click through to the seller. Now the
          // seller name is a clickable stall link (when sellerUserId
          // is known; falls back to plain text for system listings).
          s.sellerName && h('div', {
            style: { fontSize: 10, color: 'var(--text-muted)', marginTop: 4 }
          },
            'by ',
            s.sellerUserId
              ? h('a', {
                  href: '/stall/' + s.sellerUserId,
                  style: { color: 'var(--accent)', textDecoration: 'none' },
                  title: `View ${s.sellerName}'s stall`
                }, s.sellerName)
              : s.sellerName
          )
        )
      ))
    )
  );
}

// Block / unblock a seller (batch 344). Silent for the blocked user;
// reversible at any time. On block, fires a confirm dialog because
// the effect is strong (hides their listings, refuses their offers,
// muts new-listing pings). On unblock, no confirm — reversal is cheap.
// Fetches the current block state ad-hoc on mount via the block-list
// endpoint; there's no dedicated /status endpoint — reads the set once
// per stall visit, which is fine for the human cadence of a stall page.
function BlockSellerButton({ sellerId, sellerName, showToast }) {
  const [blocked, setBlocked] = useState(null); // null = loading
  const [busy, setBusy]       = useState(false);
  useEffect(() => {
    let alive = true;
    import('./api.js').then(({ fetchBlockedUsers }) => fetchBlockedUsers())
      .then(data => {
        if (!alive) return;
        const items = (data && Array.isArray(data.items)) ? data.items : [];
        setBlocked(items.some(it => Number(it.blockedUserId) === Number(sellerId)));
      })
      .catch(() => { if (alive) setBlocked(false); });
    return () => { alive = false; };
  }, [sellerId]);
  const toggle = async () => {
    if (blocked === null || busy) return;
    if (!blocked) {
      const who = sellerName ? `@${sellerName}` : 'this seller';
      const ok = confirm(`Block ${who}?\n\nTheir listings will be hidden from your grid, ` +
        "you'll stop receiving offers from them, and they'll stop appearing in your following " +
        "feed. Reversible at any time from Profile → Personal.");
      if (!ok) return;
    }
    setBusy(true);
    try {
      const { blockUser, unblockUser } = await import('./api.js');
      const res = blocked ? await unblockUser(sellerId) : await blockUser(sellerId);
      if (res && (res.error || res.code) && res.code !== 'BLOCK_LIMIT') {
        showToast(res.message || res.error || 'Could not update block', 'err');
        return;
      }
      if (res?.code === 'BLOCK_LIMIT') {
        showToast(res.message, 'err');
        return;
      }
      setBlocked(!blocked);
      // Batch 918 — name the seller in the toast so a user bouncing
      // between stalls sees exactly which one they just flipped.
      const who = sellerName ? `@${sellerName}` : 'Seller';
      showToast && showToast(
        blocked
          ? `${who} unblocked — their listings return to your grid.`
          : `${who} blocked — their listings and offers are hidden.`,
        'ok'
      );
    } finally { setBusy(false); }
  };
  if (blocked === null) return null; // render nothing until we know
  return h('button', {
    className: 'stall-share-btn',
    style: blocked
      ? { border: '1px solid var(--red)', color: 'var(--red)', opacity: 0.9 }
      : { border: '1px solid var(--border)', opacity: 0.7 },
    disabled: busy,
    onClick: toggle,
    title: blocked
      ? 'You have blocked this seller. Click to unblock.'
      : 'Hide this seller and refuse their offers/messages.'
  },
    blocked ? 'Blocked' : 'Block'
  );
}

// Follow / unfollow a seller. Fetches current status once on mount
// so the button label reflects reality; click flips optimistically.
// Click-when-following unfollows, click-when-not follows. Shows the
// current follower count as a quiet chip so buyers see social proof.
function FollowSellerButton({ sellerId, sellerName, showToast }) {
  const [status, setStatus] = useState(null);
  const [busy, setBusy]     = useState(false);
  useEffect(() => {
    let alive = true;
    import('./api.js').then(({ fetchFollowStatus }) => fetchFollowStatus(sellerId))
      .then(data => { if (alive) setStatus(data || { following: false, followerCount: 0 }); })
      .catch(() => { if (alive) setStatus({ following: false, followerCount: 0 }); });
    return () => { alive = false; };
  }, [sellerId]);
  const toggle = async () => {
    if (!status) return;
    setBusy(true);
    try {
      const { followSeller, unfollowSeller } = await import('./api.js');
      const res = status.following ? await unfollowSeller(sellerId) : await followSeller(sellerId);
      if (res && (res.error || res.code)) {
        showToast(res.message || res.error || 'Could not update follow', 'err');
        return;
      }
      const nowFollowing = !status.following;
      setStatus({
        following: nowFollowing,
        followerCount: status.followerCount + (nowFollowing ? 1 : -1)
      });
      // Batch 918 — name the seller in the follow/unfollow toast so
      // a user who has bounced between several stalls sees exactly
      // which one just flipped. Falls back to plain copy when no name
      // is in scope (legacy callers).
      const who = sellerName ? `@${sellerName}` : 'seller';
      showToast && showToast(
        nowFollowing
          ? `Following ${who} — you'll be notified of new listings.`
          : `Unfollowed ${who}.`,
        'ok'
      );
    } finally { setBusy(false); }
  };
  if (!status) {
    return h('button', { className: 'stall-share-btn', disabled: true }, '…');
  }
  const cls = status.following ? 'stall-share-btn' : 'stall-share-btn';
  const style = status.following
    ? { border: '1px solid var(--border)', opacity: 0.75 }
    : { border: '1px solid var(--accent-border)', color: 'var(--accent)' };
  return h('button', {
    className: cls, style, disabled: busy, onClick: toggle,
    title: status.following ? 'Click to unfollow' : 'Get notified when this seller lists something new'
  },
    h('span', { className: 'stall-share-icon' }, status.following ? '✓' : '+'),
    status.following ? 'Following' : 'Follow',
    status.followerCount > 0 && h('span', {
      style: { marginLeft: 6, fontSize: 10, opacity: 0.7 }
    }, `· ${status.followerCount}`)
  );
}

// Batch 848 — Contact Seller inline drawer. Replaces a `window.prompt()`
// that (a) had no ARIA dialog role so screen-reader users heard
// nothing, (b) silently failed on mobile Safari when a password manager
// treated the prompt as suspicious, and (c) forced single-line text
// with no multi-line support — a support ticket body often wants
// paragraphs. The drawer opens inline below the stall hero, autofocus
// on the textarea, Ctrl+Enter or button to submit, Esc to close.
function ContactSellerButton({ seller }) {
  const [open, setOpen]       = useState(false);
  const [body, setBody]       = useState('');
  const [busy, setBusy]       = useState(false);
  const textareaRef           = useRef(null);
  useEffect(() => {
    if (!open) return;
    const id = requestAnimationFrame(() => {
      if (textareaRef.current) textareaRef.current.focus({ preventScroll: true });
    });
    const onKey = (e) => {
      if (e.key === 'Escape' && !busy) { e.stopPropagation(); setOpen(false); }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      cancelAnimationFrame(id);
      document.removeEventListener('keydown', onKey);
    };
  }, [open, busy]);
  const submit = async () => {
    const trimmed = (body || '').trim();
    if (!trimmed || busy) return;
    setBusy(true);
    try {
      const { createSupportTicket } = await import('./api.js');
      const res = await createSupportTicket({
        category: 'ACCOUNT',
        subject:  `Contact seller · @${seller.displayName || seller.id}`,
        body:     `Seller stall: /stall/${seller.id}\n\n${trimmed}`
      });
      if (res && (res.error || res.code)) {
        try {
          window.dispatchEvent(new CustomEvent('sb:toast', { detail: {
            text: res.message || res.error || 'Could not open ticket.',
            kind: 'err'
          }}));
        } catch (_) {}
        return;
      }
      try {
        window.dispatchEvent(new CustomEvent('sb:toast', { detail: {
          text: 'Message sent through support — track it in /support.',
          kind: 'ok'
        }}));
      } catch (_) {}
      setOpen(false);
      setBody('');
    } finally { setBusy(false); }
  };
  return h(React.Fragment, null,
    h('button', {
      className: 'stall-share-btn',
      onClick: () => setOpen(v => !v),
      'aria-haspopup': 'dialog',
      'aria-expanded': open,
      'aria-controls': 'contact-seller-drawer',
      title: 'Contact this seller through support'
    },
      h('span', { className: 'stall-share-icon' }, '✉'),
      'Contact'),
    open && h('div', {
      id: 'contact-seller-drawer',
      role: 'dialog',
      'aria-modal': 'false',
      'aria-labelledby': 'contact-seller-drawer-title',
      style: {
        width: '100%', marginTop: 10, padding: 14,
        background: 'var(--bg-elevated, #1a1c20)',
        border: '1px solid var(--border)', borderRadius: 8
      }
    },
      h('div', {
        id: 'contact-seller-drawer-title',
        style: { fontSize: 13, fontWeight: 700, marginBottom: 6 }
      }, 'Contact @', seller.displayName || 'seller'),
      h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 8, lineHeight: 1.5 } },
        'Goes through our support team — we don\'t share your email with the seller. They\'ll reply through /support.'),
      h('textarea', {
        ref: textareaRef,
        value: body,
        maxLength: 2000,
        placeholder: 'What do you want to ask? (question about a listing, trade, etc.)',
        onChange: (e) => setBody(e.target.value),
        onKeyDown: (e) => {
          if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); submit(); }
        },
        rows: 5,
        'aria-label': 'Message to support about this seller',
        style: {
          width: '100%', padding: '8px 10px', fontSize: 13,
          background: 'var(--bg, #0f1115)', color: 'var(--text)',
          border: '1px solid var(--border)', borderRadius: 4,
          resize: 'vertical', fontFamily: 'inherit'
        }
      }),
      h('div', {
        style: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 8 }
      },
        h('span', { style: { fontSize: 10, color: 'var(--text-muted)' } },
          `${body.trim().length}/2000 · Ctrl+Enter to send`),
        h('div', { style: { display: 'flex', gap: 6 } },
          h('button', {
            className: 'btn btn-ghost',
            style: { padding: '6px 14px', fontSize: 12 },
            onClick: () => { setOpen(false); setBody(''); },
            disabled: busy
          }, 'Cancel'),
          h('button', {
            className: 'btn btn-primary',
            style: { padding: '6px 14px', fontSize: 12 },
            disabled: busy || !body.trim(),
            onClick: submit
          }, busy ? 'Sending…' : 'Send')
        )
      )
    )
  );
}

// ── Share stall — copies the canonical URL to the clipboard with a
// toast fallback if the browser doesn't grant clipboard-write permission.
// Keeps the stall-hero compact; no floating-menu popover.
function ShareStallButton({ userId, sellerName, showToast }) {
  const [copied, setCopied] = useState(false);
  const share = async () => {
    const url = `${window.location.origin}/stall/${userId}`;
    // Batch 877 — personalise the native share title so the receiving
    // surface (Discord/X/Messages/etc.) shows "Check out @Bob's stall
    // on SkinBox" instead of a generic "SkinBox stall". Much higher
    // click-through for sellers sharing their own stall.
    const title = sellerName
      ? `${sellerName}'s stall on SkinBox`
      : 'SkinBox stall';
    const text = sellerName
      ? `Browse ${sellerName}'s listings on SkinBox — s&box skin marketplace with auctions, buy orders, and secure escrow.`
      : 'Browse this SkinBox stall — s&box skin marketplace.';
    // Native share sheet first; user-dismiss throws AbortError which
    // we deliberately swallow (otherwise the catch block opened a
    // window.prompt every time the user backed out of the OS share
    // sheet — same gotcha the item-modal share button already guards).
    if (typeof navigator.share === 'function') {
      try {
        await navigator.share({ title, text, url });
        return;
      } catch (err) {
        if (err && err.name === 'AbortError') return;
        // Fall through to clipboard on real failures.
      }
    }
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(url);
        setCopied(true);
        setTimeout(() => setCopied(false), 1800);
        showToast && showToast('Link copied to clipboard', 'ok');
      } else {
        window.prompt('Copy this link:', url);
      }
    } catch (_) {
      window.prompt('Copy this link:', url);
    }
  };
  return h('button', {
    className: 'stall-share-btn',
    onClick: share,
    title: 'Copy a link to this stall',
    'aria-label': 'Share stall'
  },
    // Material icon glyph in place of the legacy U+2398 unicode (no
    // system font reliably ships Misc Technical glyphs — same fix
    // shipped on the loadout share button in commit 2609ebc).
    copied
      ? h('span', { className: 'stall-share-icon' }, '✓')
      : h(MaterialIcon, { name: 'content_copy', size: 14, className: 'stall-share-icon' }),
    copied ? 'Copied' : 'Share stall'
  );
}

// ── Recently viewed rail — reads sb_recently_viewed, renders a compact
// horizontal strip that mirrors CSFloat's "Recently browsed" row. Only
// renders when the user has at least two entries so it doesn't show up
// on a brand-new visitor's first page view.
function RecentlyViewedRail({ watchlist, onToggleStar, currentItemId }) {
  const [rows, setRows] = useState(() => {
    try { return JSON.parse(localStorage.getItem('sb_recently_viewed') || '[]'); }
    catch { return []; }
  });
  // Re-read when any navigation happens (the recently-viewed list is written
  // from the item-detail effect, so popstate catches every update). Saves us
  // from a cross-component event bus.
  //
  // Cache-staleness fix (2026-05-13): the cached snapshot stores a
  // full item record (name + imageUrl + lowestPrice + ...). If an item
  // id is reused (dev DB wipe + re-seed, or admin delete + new item with
  // same id) the cached fields mismatch what the id now resolves to —
  // the rail card displays "Item A" but clicking it routes to "Item B".
  // On mount, refetch each cached id against the current catalogue,
  // replace fields with fresh API data, and drop entries whose id no
  // longer resolves (404). Network errors keep the stale entry — better
  // a slightly-old card than an empty rail. localStorage write is
  // best-effort so a failed update never crashes the rail.
  useEffect(() => {
    const reload = () => {
      try { setRows(JSON.parse(localStorage.getItem('sb_recently_viewed') || '[]')); }
      catch { setRows([]); }
    };
    window.addEventListener('popstate', reload);
    let cancelled = false;
    (async () => {
      let cached;
      try { cached = JSON.parse(localStorage.getItem('sb_recently_viewed') || '[]'); }
      catch { return; }
      if (!Array.isArray(cached) || cached.length === 0) return;
      // N+1 fix: one batched GET /api/items/batch instead of one
      // GET /api/items/:id per cached id. The batch endpoint OMITS ids
      // that no longer resolve (no {notFound} sentinel — that's the
      // per-id contract), so a missing id is simply absent from `fresh`.
      let fresh;
      try { fresh = await fetchItemsByIds(cached.map(c => c.id)); }
      catch { return; }                        // unexpected — keep cached state
      if (cancelled) return;
      // Empty response = either the fetch failed (network blip) or every
      // id is gone. Can't distinguish, so keep the stale cache rather
      // than blanking the rail on a transient failure.
      if (!Array.isArray(fresh) || fresh.length === 0) return;
      const byId = new Map();
      for (const item of fresh) {
        if (item && item.id != null && item.name) byId.set(String(item.id), item);
      }
      // Walk the cached array in its existing order so viewedAt sort
      // stability is preserved; drop ids the batch didn't return.
      const validated = cached.map((c) => {
        const item = byId.get(String(c.id));
        if (!item) return null;                // id no longer resolves — drop
        return {
          id: item.id, name: item.name, category: item.category,
          rarity: item.rarity, imageUrl: item.imageUrl,
          iconEmoji: item.iconEmoji, accentColor: item.accentColor,
          lowestPrice: item.lowestPrice, steamPrice: item.steamPrice,
          viewedAt: c.viewedAt || Date.now()   // preserve original viewedAt for sort stability
        };
      }).filter(Boolean);
      setRows(validated);
      try { localStorage.setItem('sb_recently_viewed', JSON.stringify(validated)); }
      catch { /* quota/disabled — in-memory only */ }
    })();
    return () => { cancelled = true; window.removeEventListener('popstate', reload); };
  }, []);
  // Filter out the item the user is currently viewing — CSFloat hides
  // the active item from the "recently viewed" strip so the rail acts
  // as forward-link navigation, not a self-loop.
  const visible = (rows || []).filter(r => !currentItemId || String(r.id) !== String(currentItemId));
  // Rail needs enough items to feel like a rail — a pair of cards left-
  // aligned under a 1440-wide page reads as "something broken" rather
  // than "your recent picks." Gate at 4+ so the strip always looks full.
  if (visible.length < 4) return null;
  return h('section', { className: 'recently-viewed', 'aria-label': 'Recently viewed items' },
    h('h2', { className: 'recently-viewed-head' },
      h('span', { className: 'section-title-dot' }),
      'Recently viewed',
      h('button', {
        className: 'recently-viewed-clear',
        onClick: () => { localStorage.removeItem('sb_recently_viewed'); setRows([]); }
      }, 'Clear')
    ),
    h('div', { className: 'recently-viewed-rail' },
      visible.map(it => h('a', {
        key: it.id,
        href: paths.item(it.id),
        className: 'recently-viewed-card'
      },
        h('div', { className: 'recently-viewed-thumb' },
          it.imageUrl
            ? h('img', { src: it.imageUrl, alt: it.name, loading: 'lazy' })
            : h('div', { className: 'recently-viewed-glyph', style: { color: 'var(--ink-3)' } },
                ({Hats:'◈',Jackets:'▲',Shirts:'■',Pants:'▮',Gloves:'◉',Boots:'▼',Accessories:'◆',Workshop:'❖'})[it.category] || '—')
        ),
        h('div', { className: 'recently-viewed-name' }, it.name),
        h('div', { className: 'recently-viewed-price' },
          it.lowestPrice != null ? fmt(it.lowestPrice) : '—')
      ))
    )
  );
}

// ── Recently-viewed pills — compact horizontal pill row used by the
// empty-cart nudge (batch 427) and the item-not-found recovery rail
// (batch 1021). Both surfaces previously read sb_recently_viewed
// directly via inline IIFEs and rendered the cached snapshot — which
// silently displayed stale labels when an item id was reused (dev DB
// wipe + reseed, admin delete + insert with same id). This component
// applies the same refetch-and-validate logic as RecentlyViewedRail
// so a returning visitor never sees a pill labelled "Mob Boss
// Waistcoat" routing to a Witch Hat. Kind selects the section heading
// ('cart' = "Recently viewed", 'recovery' = "Try one of these
// instead"); both render identical pill markup.
function RecentlyViewedPills({ kind, privacy }) {
  const [rows, setRows] = useState(() => {
    try { return JSON.parse(localStorage.getItem('sb_recently_viewed') || '[]').slice(0, 6); }
    catch { return []; }
  });
  useEffect(() => {
    let cancelled = false;
    (async () => {
      let cached;
      try { cached = JSON.parse(localStorage.getItem('sb_recently_viewed') || '[]'); }
      catch { return; }
      if (!Array.isArray(cached) || cached.length === 0) return;
      // N+1 fix: one batched GET /api/items/batch instead of one
      // GET /api/items/:id per cached id. The batch endpoint OMITS ids
      // that no longer resolve, so a stale-id cache entry is simply
      // absent from `fresh` — no {notFound} sentinel to check.
      let fresh;
      try { fresh = await fetchItemsByIds(cached.map(c => c.id)); }
      catch { return; }
      if (cancelled) return;
      // Empty = fetch failure or every id gone; can't tell which, so
      // keep the stale cache rather than blanking the pills.
      if (!Array.isArray(fresh) || fresh.length === 0) return;
      const byId = new Map();
      for (const item of fresh) {
        if (item && item.id != null && item.name) byId.set(String(item.id), item);
      }
      // Walk cached in order to preserve viewedAt sort stability; drop
      // ids the batch didn't return.
      const validated = cached.map((c) => {
        const item = byId.get(String(c.id));
        if (!item) return null;
        return {
          id: item.id, name: item.name, category: item.category,
          rarity: item.rarity, imageUrl: item.imageUrl,
          iconEmoji: item.iconEmoji, accentColor: item.accentColor,
          lowestPrice: item.lowestPrice, steamPrice: item.steamPrice,
          viewedAt: c.viewedAt || Date.now()
        };
      }).filter(Boolean);
      setRows(validated.slice(0, 6));
      try { localStorage.setItem('sb_recently_viewed', JSON.stringify(validated)); }
      catch { /* quota/disabled */ }
    })();
    return () => { cancelled = true; };
  }, []);
  if (!rows || rows.length === 0) return null;
  const heading = kind === 'recovery' ? '⟲ Try one of these instead' : '⟲ Recently viewed';
  const wrapStyle = kind === 'recovery'
    ? { marginTop: 30, position: 'relative', zIndex: 1 }
    : { marginTop: 28 };
  return h('div', { style: wrapStyle },
    h('h2', {
      style: {
        fontSize: 11, color: 'var(--text-muted)', textTransform: 'uppercase',
        letterSpacing: 0.5, fontWeight: 700, margin: '0 0 10px', textAlign: 'center'
      }
    }, heading),
    h('div', {
      style: { display: 'flex', gap: 10, flexWrap: 'wrap', justifyContent: 'center' }
    },
      rows.map(it => {
        const priceVal = it.lowestPrice != null ? parseFloat(it.lowestPrice) : null;
        return h('a', {
          key: 'rv-pill-' + it.id,
          href: '/item/' + it.id,
          style: {
            display: 'flex', alignItems: 'center', gap: 8,
            padding: '6px 12px', borderRadius: 999,
            background: 'var(--bg-elevated)',
            border: '1px solid var(--border)',
            textDecoration: 'none', color: 'var(--text-primary)',
            fontSize: 12, fontWeight: 600
          },
          title: it.name + (priceVal != null ? ' · ' + (privacy ? '$•••••' : fmt(priceVal)) : '')
        },
          it.imageUrl && h('img', {
            src: it.imageUrl, alt: '',
            style: { width: 18, height: 18, borderRadius: 4, objectFit: 'cover' }
          }),
          h('span', { style: { maxWidth: 140, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' } }, it.name),
          priceVal != null && h('span', { style: { color: 'var(--accent)', fontFamily: 'JetBrains Mono, monospace' } }, privacy ? '$•••••' : fmt(priceVal))
        );
      })
    )
  );
}

export class ErrorBoundary extends React.Component {
  constructor(props) { super(props); this.state = { error: null, showDetails: false }; }
  static getDerivedStateFromError(error) { return { error, showDetails: false }; }
  componentDidCatch(error, info) {
    console.error('ErrorBoundary caught:', error, info);
    // Batch 678 — forward the crash to the server so ops can see
    // production-user errors instead of relying on the user's console.
    // Best-effort: a broken global state shouldn't throw here, so the
    // whole block is wrapped in try/catch. CSRF header is grabbed
    // inline because importing api.js's writeJson is fragile at
    // crash-time (we may be crashing because of a module load error).
    try {
      // /api/client-errors is CSRF-exempt (batch 688) — no need to
      // parse and forward the cookie, simplifies the crash path.
      fetch('/api/client-errors', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message: String(error && error.message || error).slice(0, 500),
          stack:   String((error && error.stack) || '').slice(0, 4000),
          url:     String(location && location.href || '').slice(0, 500),
          userAgent: String(navigator && navigator.userAgent || '').slice(0, 300)
        })
      }).catch(() => { /* swallow — logging failures shouldn't re-crash */ });
    } catch (_) { /* noop */ }
  }
  render() {
    if (this.state.error) {
      const err = this.state.error;
      const stack = String(err && err.stack ? err.stack : err);
      return h('div', {
        style: {
          padding: '48px 32px', maxWidth: 560, margin: '60px auto',
          background: 'var(--bg-card, #111827)', border: '1px solid var(--border, #1f2937)',
          borderRadius: 14, color: 'var(--text-primary, #e5e7eb)',
          fontFamily: 'Inter, system-ui, -apple-system, sans-serif', fontSize: 15, lineHeight: 1.55,
          textAlign: 'center', boxShadow: '0 8px 28px rgba(0,0,0,0.35)'
        }
      },
        h('div', { style: { fontSize: 44, marginBottom: 10 } }, '—'),
        h('h1', { style: { fontSize: 22, fontWeight: 800, margin: '0 0 8px', color: 'var(--text-primary, #e5e7eb)' } },
          'Maintenance in progress'),
        h('p', { style: { color: 'var(--text-secondary, #9ca3af)', margin: '0 0 24px' } },
          "SkinBox is undergoing a brief maintenance window. Try refreshing the page, or come back in a few minutes — your wallet, listings and trades are safe."),
        h('div', { style: { display: 'flex', gap: 10, justifyContent: 'center', flexWrap: 'wrap' } },
          h('button', {
            style: { padding: '10px 22px', background: 'var(--accent, #1ea5ff)', color: '#051018', border: 'none', borderRadius: 8, fontWeight: 700, cursor: 'pointer', fontSize: 14 },
            onClick: () => location.reload()
          }, 'Reload page'),
          h('button', {
            style: { padding: '10px 22px', background: 'transparent', color: 'var(--text-primary, #e5e7eb)', border: '1px solid var(--border, #374151)', borderRadius: 8, fontWeight: 700, cursor: 'pointer', fontSize: 14 },
            onClick: () => { location.href = '/'; }
          }, 'Go to home'),
          h('a', {
            href: '/support',
            style: { padding: '10px 22px', background: 'transparent', color: 'var(--text-primary, #e5e7eb)', border: '1px solid var(--border, #374151)', borderRadius: 8, fontWeight: 700, cursor: 'pointer', fontSize: 14, textDecoration: 'none', display: 'inline-block' }
          }, 'Contact support')
        ),
        h('div', { style: { marginTop: 28, fontSize: 12, color: 'var(--text-muted, #6b7280)' } },
          h('button', {
            style: { background: 'none', border: 'none', color: 'var(--text-muted, #6b7280)', cursor: 'pointer', fontSize: 12, textDecoration: 'underline', padding: 0 },
            onClick: () => this.setState({ showDetails: !this.state.showDetails })
          }, this.state.showDetails ? 'Hide technical details' : 'Show technical details')
        ),
        this.state.showDetails && h('pre', {
          style: {
            whiteSpace: 'pre-wrap', wordBreak: 'break-word',
            background: 'rgba(0,0,0,0.35)', padding: 14, borderRadius: 8,
            marginTop: 14, textAlign: 'left', fontSize: 11,
            fontFamily: 'JetBrains Mono, monospace', color: 'var(--text-muted, #9ca3af)',
            maxHeight: 260, overflow: 'auto'
          }
        }, stack)
      );
    }
    return this.props.children;
  }
}

// Install the anchor interceptor exactly once, at module load, so every `<a
// href="/...">` in the app routes client-side instead of triggering a reload.
installAnchorInterceptor();

// Floating back-to-top button — shows after the viewer scrolls past
// 600px on the homepage / long stall / watchlist pages. A plain window
// scroll listener is enough because the main content isn't wrapped in
// an overflow:auto container (the scroll container is window itself).
// Button is pure-DOM — no React state churn on every scroll tick. The
// event handler is throttled to one raf so continuous scroll doesn't
// thrash layout.
function BackToTopButton() {
  const [visible, setVisible] = useState(false);
  useEffect(() => {
    // The SPA's actual scroll container is `.layout` (full-page-mode
    // wrapper) — `router.js` reads `layout.scrollTop` for scroll
    // restoration. Pre-fix we listened on `window` which never fires
    // when only `.layout` scrolls, so the back-to-top button was
    // permanently invisible. Listen to BOTH so we cover routes where
    // the SPA hasn't applied full-page-mode yet.
    let ticking = false;
    const getScrollY = () => {
      const layout = document.querySelector('.layout');
      if (layout && layout.scrollTop > 0) return layout.scrollTop;
      return window.scrollY;
    };
    const onScroll = () => {
      if (ticking) return;
      ticking = true;
      requestAnimationFrame(() => {
        setVisible(getScrollY() > 600);
        ticking = false;
      });
    };
    window.addEventListener('scroll', onScroll, { passive: true });
    const layout = document.querySelector('.layout');
    if (layout) layout.addEventListener('scroll', onScroll, { passive: true });
    onScroll();  // seed on mount
    return () => {
      window.removeEventListener('scroll', onScroll);
      if (layout) layout.removeEventListener('scroll', onScroll);
    };
  }, []);
  if (!visible) return null;
  return h('button', {
    'aria-label': 'Back to top',
    title: 'Back to top',
    onClick: () => {
      const layout = document.querySelector('.layout');
      if (layout && layout.scrollTop > 0) layout.scrollTo({ top: 0, behavior: 'smooth' });
      else window.scrollTo({ top: 0, behavior: 'smooth' });
    },
    style: {
      position: 'fixed', right: 24, bottom: 24,
      width: 42, height: 42, borderRadius: 21,
      background: 'var(--bg-page-2)',
      border: '1px solid var(--border-light)',
      color: 'var(--accent)',
      fontSize: 20, fontWeight: 700,
      cursor: 'pointer',
      boxShadow: '0 6px 20px rgba(0,0,0,0.35), 0 0 0 1px rgba(77,200,255,0.06)',
      zIndex: 40,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      lineHeight: 1
    }
  }, h(MaterialIcon, { name: 'arrow_upward', size: 20 }));
}

// Cookie consent banner — bottom-of-page, dismissable, persists choice in
// localStorage. SkinBox sets only essential cookies (sbox_csrf,
// JSESSIONID) so legally we can render content without consent, but the
// banner is the standard EU/UK GDPR + ePrivacy-Directive-compatible UX
// pattern and a trust signal — visitors expect it. "Accept" stores
// `sb_cookie_consent=accepted`, "Reject" stores `rejected`. Either
// dismisses the banner FOREVER for that browser. Re-shown only after a
// manual `localStorage.removeItem('sb_cookie_consent')` (handled by the
// Privacy page link).
//
// Boss QA G1 — three-state banner so it stops eating the corner of
// every page:
//   1. expanded (initial)  — full text + Reject/Accept, slides in
//   2. collapsed (5s idle) — tiny floating "🍪" pill, expands on hover
//   3. dismissed (Accept/Reject clicked) — vanishes for the session and
//      across reloads via localStorage. Component returns null.
function CookieBanner() {
  // Has the user already chosen? If so, render absolutely nothing — the
  // banner must NEVER reappear for this browser without a manual reset.
  // Also suppress when the QA screenshot rig appends `?_qa=1` so every
  // boss screenshot lands clean (no banner, no cookie pill).
  //
  // Persistence read helper. Checks localStorage AND sessionStorage so a
  // user whose browser blocks localStorage (Safari ITP private mode,
  // strict tracking-protection) still gets a session-scoped dismissal.
  // Re-run on every render and on `pageshow` so a bfcache restore that
  // skips the useState initialiser can't bring the banner back.
  const readConsent = () => {
    try {
      if (typeof location !== 'undefined' && /[?&]_qa=1\b/.test(location.search)) return 'qa';
      if (typeof navigator !== 'undefined' && /HeadlessChrome|PhantomJS|puppeteer|playwright/i.test(navigator.userAgent || '')) return 'headless';
      let v = null;
      try { v = localStorage.getItem('sb_cookie_consent'); } catch (_) {}
      if (!v) { try { v = sessionStorage.getItem('sb_cookie_consent'); } catch (_) {} }
      return v;
    } catch (_) { return null; }
  };
  const [visible, setVisible] = useState(() => !readConsent());
  // Re-check on bfcache restore + storage events from other tabs. Without
  // this, dismissing in tab A leaves tab B's banner alive until a hard
  // reload, and Safari's bfcache can restore a pre-dismiss CookieBanner
  // instance that re-shows even though localStorage already persisted
  // the choice.
  useEffect(() => {
    const recheck = () => { if (readConsent()) setVisible(false); };
    window.addEventListener('pageshow', recheck);
    window.addEventListener('storage', recheck);
    return () => {
      window.removeEventListener('pageshow', recheck);
      window.removeEventListener('storage', recheck);
    };
  }, []);
  // Auto-collapse to a tiny pill after 5s of no interaction. The user
  // can still expand by hovering. This stops the banner from squatting
  // on the bottom-left of every screenshot the boss takes.
  const [collapsed, setCollapsed] = useState(false);
  // Hovering the collapsed pill re-expands without committing a choice.
  const [hovered, setHovered] = useState(false);

  useEffect(() => {
    if (!visible || collapsed) return;
    // Boss QA G1 — collapse on first scroll OR after 3.5s idle. Earlier
    // 5s let the banner squat in every screenshot the boss took.
    const t = setTimeout(() => setCollapsed(true), 3500);
    const onScroll = () => setCollapsed(true);
    window.addEventListener('scroll', onScroll, { passive: true, once: true });
    return () => { clearTimeout(t); window.removeEventListener('scroll', onScroll); };
  }, [visible, collapsed]);

  // Defensive guard: at render time, re-verify against storage. If
  // dismissal slipped in via another tab/path before the listener fired,
  // bail out without painting. Render-phase Promise.then keeps React
  // happy (no setState during render).
  if (visible && readConsent()) {
    Promise.resolve().then(() => setVisible(false));
    return null;
  }
  if (!visible) return null;

  const decide = (choice) => {
    // Belt and suspenders — write both stores so a privacy mode that
    // blocks localStorage still picks up a session-scoped dismissal.
    try { localStorage.setItem('sb_cookie_consent', choice); } catch (_) {}
    try { sessionStorage.setItem('sb_cookie_consent', choice); } catch (_) {}
    setVisible(false);
  };

  // Collapsed pill state — tiny 🍪 puck, no chrome, expands on hover.
  if (collapsed && !hovered) {
    return h('button', {
      type: 'button',
      'aria-label': 'Cookie preferences',
      title: 'Cookie preferences',
      onMouseEnter: () => setHovered(true),
      onFocus: () => setHovered(true),
      onClick: () => { setCollapsed(false); setHovered(false); },
      style: {
        // Boss QA cycle 3 C3-5 — moved to bottom-right so the pill
        // doesn't sit on top of the sticky modal-actions bar (item /
        // wallet / settings) or the High Contrast toggle in /settings
        // on mobile. Right edge is consistently free of fixed UI.
        position: 'fixed', right: 16, bottom: 16,
        width: 36, height: 36,
        padding: 0, margin: 0,
        background: 'var(--bg-1)',
        border: '1px solid var(--line-2)',
        borderRadius: '50%',
        color: 'var(--ink-2)',
        fontSize: 18, lineHeight: 1,
        boxShadow: '0 4px 12px rgba(0,0,0,0.32)',
        cursor: 'pointer',
        zIndex: 150,
        display: 'grid', placeItems: 'center',
        animation: 'cookie-in 280ms cubic-bezier(0.2, 0.8, 0.2, 1) 1',
        backdropFilter: 'blur(8px) saturate(140%)',
        WebkitBackdropFilter: 'blur(8px) saturate(140%)'
      }
    }, '🍪');
  }

  return h('div', {
    role: 'region',
    'aria-label': 'Cookie consent',
    onMouseLeave: () => { if (collapsed) setHovered(false); },
    style: {
      /* Tighter, less intrusive cookie banner — was 14px padding + 13px
         text + 720px wide which dominated the bottom of every page. Now
         compact: 10/14 padding, 12px text, 480px max. Boss QA cycle 3
         C3-5 — moved to bottom-RIGHT so it doesn't cover the sticky
         modal-actions or settings toggles in the bottom-left of mobile
         viewports. */
      position: 'fixed', right: 16, bottom: 16,
      maxWidth: 480,
      padding: '10px 14px',
      background: 'var(--bg-1)',
      border: '1px solid var(--line-2)',
      borderRadius: 10,
      color: 'var(--ink)',
      fontSize: 12, lineHeight: 1.45,
      boxShadow: '0 8px 24px rgba(0,0,0,0.4), 0 1px 0 rgba(255,255,255,0.04) inset',
      zIndex: 150,
      display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 10,
      justifyContent: 'space-between',
      backdropFilter: 'blur(8px) saturate(140%)',
      WebkitBackdropFilter: 'blur(8px) saturate(140%)'
    }
  },
    h('div', { style: { flex: '1 1 240px', minWidth: 200, color: 'var(--ink-3)' } },
      h('span', { style: { fontWeight: 600, color: 'var(--ink)' } }, 'Cookies'),
      ' · essential only, no tracking. ',
      h('a', {
        href: '/legal/cookies.html',
        style: { color: 'var(--ink-2)', textDecoration: 'underline', textDecorationColor: 'var(--line-2)', textUnderlineOffset: '3px' }
      }, 'Policy')
    ),
    h('div', { style: { display: 'flex', gap: 6, flexShrink: 0 } },
      h('button', {
        className: 'btn-ghost',
        style: { padding: '5px 10px', fontSize: 11, height: 28 },
        onClick: () => decide('rejected')
      }, 'Reject'),
      h('button', {
        className: 'btn-accent',
        style: { padding: '5px 12px', fontSize: 11, height: 28 },
        onClick: () => decide('accepted')
      }, 'Accept')
    )
  );
}

// Full-width site footer — rendered at the bottom of every route. Multi-column
// link map plus a "Powered by Stripe" mark that points users at the real
// payment processor. Surfacing the Stripe badge here gives visible proof that
// the integration is wired; the same badge appears inside the wallet page.
export function SiteFooter() {
  // Catalog sync status — polls /api/items/stats every 5 min and shows
  // "Catalog updated X ago" in the bottom meta bar. Quiet trust signal:
  // buyers know the floor prices haven't drifted from Steam for hours.
  const [lastSync, setLastSync] = useState(0);
  const [version, setVersion]   = useState('');
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const r = await fetch('/api/items/stats', { credentials: 'same-origin' });
        if (!r.ok) return;
        const d = await r.json();
        if (alive && typeof d?.lastSyncedAt === 'number') setLastSync(d.lastSyncedAt);
      } catch (_) {}
    };
    load();
    // Version is a one-shot fetch — it doesn't change without a deploy.
    fetch('/api/version', { credentials: 'same-origin' })
      .then(r => r.ok ? r.json() : null)
      .then(d => { if (alive && d?.version) setVersion(d.version); })
      .catch(() => {});
    // Batch 806 — visibility-aware poll.
    const id = setInterval(() => { if (!document.hidden) load(); }, 5 * 60_000);
    return () => { alive = false; clearInterval(id); };
  }, []);
  return h('footer', { className: 'site-footer' },
    h('div', { className: 'site-footer-inner' },
      h('div', { className: 'site-footer-col site-footer-brand' },
        h('div', { className: 'site-footer-logo' },
          // Same isometric-crate SVG as the nav logo; blue-fade facets
          // per the operator's template. See the nav-logo block above
          // for the gradient definitions (re-declared here so the SVG
          // is self-contained and renders in isolation).
          h('div', { className: 'nav-logo-icon', 'aria-hidden': 'true' },
            h('svg', {
              viewBox: '0 0 48 48',
              xmlns: 'http://www.w3.org/2000/svg',
              width: '100%',
              height: '100%',
              fill: 'none'
            },
              h('defs', null,
                h('linearGradient', { id: 'sbmf-top', x1: '24', y1: '2', x2: '24', y2: '26', gradientUnits: 'userSpaceOnUse' },
                  h('stop', { offset: '0%',   stopColor: '#c0e9ff' }),
                  h('stop', { offset: '100%', stopColor: '#4dc8ff' })
                ),
                h('linearGradient', { id: 'sbmf-left', x1: '4', y1: '24', x2: '24', y2: '46', gradientUnits: 'userSpaceOnUse' },
                  h('stop', { offset: '0%',   stopColor: '#0a7cc9' }),
                  h('stop', { offset: '100%', stopColor: '#04121c' })
                ),
                h('linearGradient', { id: 'sbmf-right', x1: '46', y1: '24', x2: '24', y2: '46', gradientUnits: 'userSpaceOnUse' },
                  h('stop', { offset: '0%',   stopColor: '#1ea5ff' }),
                  h('stop', { offset: '100%', stopColor: '#0d4d78' })
                )
              ),
              h('path', { d: 'M24 3 L44 14 L24 25 L4 14 Z',   fill: 'url(#sbmf-top)',   stroke: 'rgba(120,210,255,0.5)', strokeWidth: '0.8', strokeLinejoin: 'round' }),
              h('path', { d: 'M4 14 L24 25 L24 45 L4 34 Z',   fill: 'url(#sbmf-left)',  stroke: 'rgba(77,200,255,0.12)', strokeWidth: '0.8', strokeLinejoin: 'round' }),
              h('path', { d: 'M44 14 L24 25 L24 45 L44 34 Z', fill: 'url(#sbmf-right)', stroke: 'rgba(77,200,255,0.18)', strokeWidth: '0.8', strokeLinejoin: 'round' }),
              h('path', { d: 'M4 14 L24 3 L44 14',            fill: 'none',             stroke: 'rgba(192,233,255,0.75)', strokeWidth: '0.8', strokeLinejoin: 'round' }),
              h('path', { d: 'M24 25 L24 45',                 stroke: 'rgba(4,18,28,0.55)', strokeWidth: '0.8' })
            )
          ),
          h('span', { className: 'nav-logo-text' }, 'SkinBox')
        ),
        h('p', { className: 'site-footer-tag' },
          'The s&box skin marketplace. Real-time prices, verified sellers, and escrowed trades — built for the Workshop community.'),
        h('div', { className: 'site-footer-badges' },
          h('a', {
            className: 'stripe-badge',
            href: 'https://stripe.com',
            target: '_blank',
            rel: 'noopener noreferrer',
            title: 'Payments processed by Stripe'
          },
            h('span', { className: 'stripe-badge-label' }, 'Powered by'),
            h('span', { className: 'stripe-badge-mark' }, 'stripe')
          ),
          h('span', { className: 'trust-badge' },
            h(MaterialIcon, { name: 'lock', size: 12 }),
            ' TLS 1.3 · Webhook-signed'
          )
        )
      ),
      h('div', { className: 'site-footer-col' },
        h('div', { className: 'site-footer-title' }, 'Marketplace'),
        h('a', { href: paths.market() }, 'Browse Market'),
        h('a', { href: paths.database() }, 'Item Database'),
        h('a', { href: paths.buyorders() }, 'Buy Orders'),
        h('a', { href: paths.sell() }, 'Sell Items'),
        h('a', { href: paths.loadouts() }, 'Loadout Lab')
      ),
      h('div', { className: 'site-footer-col' },
        h('div', { className: 'site-footer-title' }, 'Account'),
        h('a', { href: paths.profile() }, 'Profile'),
        h('a', { href: paths.wallet() }, 'Wallet'),
        h('a', { href: paths.offers() }, 'Offers'),
        h('a', { href: paths.watchlist() }, 'Watchlist'),
        h('a', { href: paths.notifications() }, 'Notifications')
      ),
      h('div', { className: 'site-footer-col' },
        h('div', { className: 'site-footer-title' }, 'Resources'),
        h('a', { href: paths.help() }, 'Help Center'),
        h('a', { href: paths.faq() }, 'FAQ'),
        h('a', { href: paths.support() }, 'Support'),
        h('a', { href: paths.support() + '?topic=bug' }, 'Report a Bug'),
        h('a', { href: paths.faq() + '?q=' + encodeURIComponent('platform fee') }, 'Fees & Pricing'),
        h('a', { href: '/status.html' }, 'System Status'),
        h('a', { href: '/changelog.html' }, 'Changelog'),
        h('a', { href: paths.affiliate() }, 'Affiliate Program'),
        h('a', { href: paths.settings() }, 'Settings')
      ),
      h('div', { className: 'site-footer-col' },
        h('div', { className: 'site-footer-title' }, 'Legal'),
        h('a', { href: '/legal/terms.html' }, 'Terms of Service'),
        h('a', { href: '/legal/privacy.html' }, 'Privacy Policy'),
        h('a', { href: '/legal/refunds.html' }, 'Refund Policy'),
        h('a', { href: '/legal/trade-safety.html' }, 'Trade Safety'),
        h('a', { href: '/legal/disclaimer.html' }, 'Risk Disclaimer'),
        h('a', { href: '/legal/acceptable-use.html' }, 'Acceptable Use'),
        h('a', { href: '/legal/cookies.html' }, 'Cookies'),
        h('a', { href: '/legal/responsible-disclosure.html' }, 'Responsible Disclosure')
        /* Privacy Policy and Refund Policy now linked alongside the
           other legal docs (batch 300). EU/UK GDPR + California CCPA
           effectively require both to be clearly discoverable from
           every page, not just the pre-signin modal. robots.txt still
           allows them; search engines reference them for compliance
           trust signals. */
      )
    ),
    h('div', { className: 'site-footer-bottom' },
      h('div', { className: 'site-footer-copy' },
        '© ', new Date().getFullYear(), ' SkinBox · Not affiliated with Facepunch Studios. s&box is a trademark of Facepunch Ltd.',
        version && h('span', { style: { color: 'var(--ink-3)', marginLeft: 10 } }, '· v', version)),
      h('div', { className: 'site-footer-socials', 'aria-label': 'Community' },
        // Boss QA cycle 2 N1 — "coming soon" Discord/X buttons removed.
        // A live marketplace doesn't ship disabled-with-tooltip social
        // icons; they read as beta-shaped UI. Until real channels exist
        // we render just the email link, which is a real working endpoint.
        h('a', {
          className: 'site-footer-social', href: 'mailto:support@skinbox.market',
          'aria-label': 'Email support', title: 'support@skinbox.market'
        }, h(MaterialIcon, { name: 'mail', size: 20 }))
      ),
      h('div', { className: 'site-footer-meta' },
        // Currency-aware footer copy. The DB stores every amount in USD;
        // fmt() converts at display time using the user's `sb_currency`
        // localStorage key. When that's USD we say so plainly; otherwise
        // we explicitly disclose the conversion + storage currency so a
        // CAD/EUR/etc. viewer doesn't think the displayed FX value is the
        // canonical price.
        (() => {
          const code = (() => { try { return localStorage.getItem('sb_currency') || 'USD'; } catch { return 'USD'; } })();
          return code === 'USD'
            ? h('span', null, 'All prices in USD')
            : h('span', { title: 'Display values converted from USD using a static FX table; the underlying transaction currency is USD.' },
                `Prices shown in ${code} · stored as USD`);
        })(),
        h('span', { className: 'dot' }, '·'),
        h('span', null, 'Stripe-secured payments'),
        h('span', { className: 'dot' }, '·'),
        h('span', null, 'Steam OpenID auth'),
        lastSync > 0 && h('span', { className: 'dot' }, '·'),
        lastSync > 0 && h('span', { className: 'footer-sync-badge', title: `Last catalogue sync: ${new Date(lastSync).toLocaleString()}` },
          h('span', { className: 'footer-sync-dot' }),
          'Catalog updated ', timeAgo(lastSync))
      )
    )
  );
}

// Pre-signin consent modal — appears the first time a visitor clicks
// "Sign in through Steam". Requires a checked ToS + Privacy box and a
// valid email address before it'll hand off to the Steam OpenID flow.
// Email is stashed in localStorage; the profile page fires a verification
// token against it automatically on first authenticated load.
export function PreSigninModal({ onClose, onAccept }) {
  // Restore any pending email the user entered before — if they closed the
  // modal by accident, they don't have to retype it.
  const [email,     setEmail]     = useState(() => {
    try { return localStorage.getItem('sb_pending_email') || ''; } catch { return ''; }
  });
  const [tos,       setTos]       = useState(false);
  const [marketing, setMarketing] = useState(() => {
    try { return localStorage.getItem('sb_marketing_opt_in') === '1'; } catch { return false; }
  });
  const [err,       setErr]       = useState('');

  const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

  const submit = () => {
    setErr('');
    if (!EMAIL_RE.test(email.trim())) { setErr('Please enter a valid email address'); return; }
    if (!tos)                         { setErr('You must agree to the Terms of Service'); return; }
    onAccept(email.trim(), marketing);
  };

  // Batch 830 — Escape closes pre-signin modal.
  useEffect(() => {
    const onKey = (e) => {
      if (e.key !== 'Escape') return;
      e.stopPropagation();
      if (typeof onClose === 'function') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  return h('div', { className: 'modal-backdrop', onClick: onClose },
    h('div', {
      className: 'modal presignin-modal',
      onClick: e => e.stopPropagation(),
      // Batch 830 — PreSigninModal completes the a11y pass: had
      // role=dialog + aria-labelledby already; adds aria-modal and
      // an Escape handler at the parent component level (see useEffect
      // above). Without aria-modal, screen readers didn't know to
      // trap user attention inside the dialog while Steam handoff was
      // pending.
      role: 'dialog',
      'aria-modal': 'true',
      'aria-labelledby': 'presignin-title'
    },
      h('button', { className: 'modal-close', onClick: onClose, 'aria-label': 'Close' }, '✕'),
      h('div', { className: 'presignin-inner' },
        h('div', { className: 'presignin-logo' },
          h('img', {
            src: '/img/logo-square.png',
            alt: 'SkinBox',
            onError: (e) => { e.target.style.display = 'none'; }
          })
        ),
        h('h2', { id: 'presignin-title', className: 'presignin-title' }, 'Welcome to SkinBox'),
        h('p', { className: 'presignin-sub' },
          'Before we hand you off to Steam, we need your email for account recovery, receipts, and a one-time verification code.'),

        h('label', { className: 'presignin-label', htmlFor: 'presignin-email' }, 'Email address'),
        h('input', {
          id: 'presignin-email',
          className: 'presignin-input',
          type: 'email',
          value: email,
          onChange: e => setEmail(e.target.value),
          onKeyDown: e => { if (e.key === 'Enter') submit(); },
          placeholder: 'you@example.com',
          autoComplete: 'email',
          required: true
        }),

        h('label', { className: 'presignin-check' },
          h('input', {
            type: 'checkbox',
            checked: tos,
            onChange: e => setTos(e.target.checked)
          }),
          h('span', null,
            'I agree to the ',
            h('a', { href: '/legal/terms.html', target: '_blank', rel: 'noopener' }, 'Terms of Service'),
            ' and ',
            h('a', { href: '/legal/privacy.html', target: '_blank', rel: 'noopener' }, 'Privacy Policy'),
            '.'
          )
        ),

        err && h('div', { className: 'presignin-error' }, err),

        h('button', {
          className: 'btn btn-accent presignin-submit',
          onClick: submit,
          type: 'button'
        },
          h('div', { className: 'steam-btn-icon' },
            h('svg', {
              viewBox: '0 0 24 24',
              width: 20,
              height: 20,
              fill: 'currentColor',
              'aria-hidden': 'true'
            },
              h('path', {
                d: 'M11.979 0C5.678 0 .511 4.86.022 11.037l6.432 2.658c.545-.371 1.203-.59 1.912-.59.063 0 .125.004.188.006l2.861-4.142V8.91c0-2.495 2.028-4.524 4.524-4.524 2.494 0 4.524 2.031 4.524 4.527s-2.03 4.525-4.524 4.525h-.105l-4.076 2.911c0 .052.004.105.004.159 0 1.875-1.515 3.396-3.39 3.396-1.635 0-3.016-1.173-3.331-2.727L.436 15.27C1.862 20.307 6.486 24 11.979 24c6.627 0 11.999-5.373 11.999-12S18.605 0 11.979 0zM7.54 18.21l-1.473-.61c.262.543.714.999 1.314 1.25 1.297.539 2.793-.076 3.332-1.375.263-.63.264-1.319.005-1.949s-.75-1.121-1.377-1.383c-.624-.26-1.29-.249-1.878-.03l1.523.63c.956.4 1.409 1.5 1.009 2.455-.397.957-1.497 1.41-2.454 1.012H7.54zm11.415-9.303c0-1.662-1.353-3.015-3.015-3.015-1.665 0-3.015 1.353-3.015 3.015 0 1.665 1.35 3.015 3.015 3.015 1.663 0 3.015-1.35 3.015-3.015zm-5.273-.005c0-1.252 1.013-2.266 2.265-2.266 1.249 0 2.266 1.014 2.266 2.266 0 1.251-1.017 2.265-2.266 2.265-1.253 0-2.265-1.014-2.265-2.265z'
              })
            )
          ),
          'Continue to Steam'
        ),

        h('div', { className: 'presignin-footnote' },
          'Your password never touches our servers — Steam handles the login. We only see your public Steam profile via OpenID.'
        )
      )
    )
  );
}

// Inline "Name this saved search" drawer — replaces a `window.prompt()`
// that was inaccessible (no ARIA semantics, some mobile browsers
// silently dismiss, password managers often block), and had no client
// validation feedback. The drawer lives directly beneath the saved-
// searches toolbar so there's a clear spatial anchor between "I clicked
// Save search" and "type a name". Autofocuses the input, commits on
// Enter, cancels on Escape.
function SaveSearchDrawer({ initial, onCancel, onSave }) {
  const [name, setName] = useState(initial || '');
  const inputRef = useRef(null);
  useEffect(() => {
    const id = requestAnimationFrame(() => {
      if (inputRef.current) {
        inputRef.current.focus({ preventScroll: true });
        try { inputRef.current.select(); } catch (_) {}
      }
    });
    return () => cancelAnimationFrame(id);
  }, []);
  useEffect(() => {
    const onKey = (e) => {
      if (e.key === 'Escape') { e.stopPropagation(); onCancel(); }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onCancel]);
  const trimmed = (name || '').trim();
  const canSave = trimmed.length > 0 && trimmed.length <= 60;
  return h('div', {
    role: 'dialog',
    'aria-modal': 'false',
    'aria-labelledby': 'save-search-drawer-title',
    style: {
      width: '100%', marginTop: 6, padding: '10px 12px',
      background: 'var(--bg-elevated, #1a1c20)',
      border: '1px solid var(--border)', borderRadius: 6,
      display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center'
    }
  },
    h('span', {
      id: 'save-search-drawer-title',
      style: { fontSize: 11, opacity: 0.8, marginRight: 4 }
    }, 'Name this preset'),
    h('input', {
      ref: inputRef,
      type: 'text',
      value: name,
      maxLength: 60,
      placeholder: 'e.g. Limited hats under $20',
      onChange: (e) => setName(e.target.value),
      onKeyDown: (e) => {
        if (e.key === 'Enter' && canSave) { e.preventDefault(); onSave(trimmed); }
      },
      'aria-label': 'Saved search name',
      style: {
        flex: '1 1 220px', minWidth: 180,
        padding: '6px 10px', fontSize: 12,
        background: 'var(--bg, #0f1115)', color: 'var(--text)',
        border: '1px solid var(--border)', borderRadius: 4
      }
    }),
    h('span', { style: { fontSize: 10, opacity: 0.55 } },
      `${trimmed.length}/60`),
    h('button', {
      className: 'btn btn-ghost',
      style: { padding: '6px 12px', fontSize: 11 },
      onClick: onCancel
    }, 'Cancel'),
    h('button', {
      className: 'btn btn-primary',
      style: { padding: '6px 12px', fontSize: 11 },
      disabled: !canSave,
      onClick: () => onSave(trimmed)
    }, 'Save')
  );
}

export function App() {
  // Router — every feature is reachable by its own URL. Modal state has been
  // replaced with route-driven rendering. `routeName` is what we switch on.
  const route = useRoute();
  const routeName = route.name;

  // Set a meaningful document.title per route so browser tabs + browser
  // history actually describe the page. The NotificationBell unread
  // prefix sits on top of whatever base title we set. For item detail
  // the actual item name lands below in the item-load effect.
  useEffect(() => {
    const titles = {
      home:          'SkinBox — s&box Skin Marketplace',
      market:        'Marketplace · SkinBox',
      database:      'Item Database · SkinBox',
      watchlist:     'Watchlist · SkinBox',
      sell:          'Sell Items · SkinBox',
      mystall:       'My Stall · SkinBox',
      cart:          'Cart · SkinBox',
      wallet:        'Wallet · SkinBox',
      profile:       'Profile · SkinBox',
      offers:        'Offers · SkinBox',
      buyorders:     'Buy Orders · SkinBox',
      notifications: 'Notifications · SkinBox',
      support:       'Support · SkinBox',
      help:          'Help Center · SkinBox',
      faq:           'FAQ · SkinBox',
      settings:      'Settings · SkinBox',
      affiliate:     'Affiliate Program · SkinBox',
      admin:         'Admin Panel · SkinBox',
      csr:           'Customer Service · SkinBox',
      loadouts:      'Loadout Lab · SkinBox',
      loadout:       'Loadout · SkinBox',
      stall:         'Seller Stall · SkinBox',
      item:          'Item · SkinBox',
      notfound:      'Page Not Found · SkinBox'
    };
    // Sub-tab labels — match the H1/tab-button text the user sees on screen
    // so document.title and the visible heading stay in sync. Without this,
    // every /profile/* sub-route shares the generic 'Profile · SkinBox' tab
    // title and browser history is unreadable when you have 5 tabs open.
    const TAB_LABELS = {
      profile: {
        personal: 'Personal Info', transactions: 'Transactions',
        buyorders: 'Buy Orders', autobids: 'Active Bids',
        trades: 'Trades', offers: 'Offers',
        reviews: 'Reviews', support: 'Support',
        developers: 'Developers'
      },
      wallet: { deposit: 'Deposit', withdraw: 'Withdraw', history: 'History' },
      watchlist: { all: 'All Items', drops: 'Price Drops' },
      mystall: { active: 'Active Listings', sold: 'Sold' },
      offers: { incoming: 'Incoming', outgoing: 'Outgoing' }
    };
    const tabKey = route.params && route.params.tab;
    const tabLabel = tabKey && TAB_LABELS[routeName] && TAB_LABELS[routeName][tabKey];
    let base = titles[routeName] || 'SkinBox — s&box Skin Marketplace';
    if (tabLabel) {
      // Reshape "Profile · SkinBox" → "Transactions · Profile · SkinBox"
      base = tabLabel + ' · ' + base;
    }
    // Branded title for the per-category 404 surfaces — `/stall/abc`,
    // `/loadout/foo`, `/item/x` all fall through to the generic notfound
    // route, but the visible empty-state is category-specific so the
    // browser tab + history entry should match.
    if (routeName === 'notfound') {
      const p = route.path || '';
      if      (p.startsWith('/stall/'))   base = 'Stall Not Found · SkinBox';
      else if (p.startsWith('/loadout/')) base = 'Loadout Not Found · SkinBox';
      else if (p.startsWith('/item/'))    base = 'Item Not Found · SkinBox';
    }
    // Preserve any (N) unread-notifications prefix set by NotificationBell.
    const currentPrefix = (document.title.match(/^(\([^)]+\)\s+)/) || [, ''])[1];
    document.title = currentPrefix + base;
  }, [routeName, route.params && route.params.tab, route.path]);

  // marketplace state
  const [listings, setListings]         = useState([]);
  // CSFloat-1:1 — `homeFeatured` is a separate cache used by the home
  // hero and preview strip so they stay stable when the visitor clicks
  // tabs / filters that change `listings`. Fetched ONCE on mount with
  // no filters, so the hero remains stable across the session.
  const [homeFeatured, setHomeFeatured] = useState([]);
  const [homeTotalListings, setHomeTotalListings] = useState(null);
  useEffect(() => {
    let cancelled = false;
    fetch('/api/listings?sort=price_desc&limit=8')
      .then(r => r.ok ? r.json() : null)
      .then(data => {
        if (cancelled || !data) return;
        // The endpoint returns either a bare array or `{ items, total, ... }`
        // when paginated. Accept both shapes.
        const items = Array.isArray(data) ? data : (Array.isArray(data.items) ? data.items : null);
        if (items && items.length > 0) setHomeFeatured(items);
        if (typeof data.total === 'number') setHomeTotalListings(data.total);
      })
      .catch(() => {});
    return () => { cancelled = true; };
  }, []);
  const [loading, setLoading]           = useState(true);
  // Capture fetch failures so the empty-state can offer a Retry CTA
  // instead of misreporting "Marketplace is empty" when the network
  // (or backend) blew up. Cleared on every successful load.
  const [loadError, setLoadError]       = useState(null);
  // Batch 812 — grid/table view choice is persisted in localStorage so
  // a user who prefers the table view (wider density, more fields per
  // row) doesn't have to flip the toggle every session.
  const [view, setViewRaw] = useState(() => {
    try {
      const stored = localStorage.getItem('sb_market_view');
      return (stored === 'table' || stored === 'grid') ? stored : 'grid';
    } catch { return 'grid'; }
  });
  const setView = (v) => {
    setViewRaw(v);
    try { localStorage.setItem('sb_market_view', v); } catch (_) {}
  };
  // Pagination — marketplace serves 100 listings per page (ListingController
  // caps the limit). When the first page returns a full 100, we expose a
  // "Load more" button that fetches the next page and appends. `hasMore`
  // is conservative: a full page means there MAY be more (could also be
  // exactly 100 total). The server returning fewer than 100 on the next
  // fetch flips it to false. Resets on every filter change so a user who
  // narrows the grid doesn't leak stale offset state.
  const [hasMore, setHasMore]           = useState(false);
  const [loadingMore, setLoadingMore]   = useState(false);

  // filters
  // Skinport pattern: debounce the raw search input so the filtering
  // pipeline only re-runs 300ms after the user stops typing. Without
  // this, every keystroke re-filters thousands of listings and re-renders
  // every grid card. `search` is the committed value used by filters;
  // `searchInput` is what the text box holds while the user types.
  // Initial filter state is seeded from the URL query string so deep-links
  // like /market?category=Hats&sort=discount land on the same view the
  // sender saw. Read-once on mount — we update the URL back out below via
  // replaceState so subsequent in-app filter changes stay shareable without
  // thrashing the back/forward history.
  const __urlParams = (() => {
    try { return new URLSearchParams(window.location.search); }
    catch { return new URLSearchParams(); }
  })();
  const ALLOWED_SORTS      = ['price_desc','price_asc','newest','rarity','discount','ending_soon','popularity','views'];
  const ALLOWED_CATEGORIES = ['All','Hats','Jackets','Shirts','Pants','Gloves','Boots','Accessories'];
  const ALLOWED_RARITIES   = ['All','Limited','Off-Market','Standard'];
  const __initialQ        = (__urlParams.get('q') || '').slice(0, 80);
  // Marketplace default sort preference (batch 544). If the URL
  // didn't specify a sort, fall back to what the user picked last
  // time. localStorage is per-device so it doesn't sync across
  // browsers but it makes a single-device returning visitor land on
  // their preferred ordering (newest / discount / popularity) without
  // re-picking every time. Invalid stored values collapse to the old
  // price_desc default so a future ALLOWED_SORTS change can't break
  // returning users.
  let __storedSort = null;
  try { __storedSort = localStorage.getItem('sb_market_sort'); } catch (_) {}
  const __initialSort     = ALLOWED_SORTS.includes(__urlParams.get('sort'))
    ? __urlParams.get('sort')
    : (ALLOWED_SORTS.includes(__storedSort) ? __storedSort : 'price_desc');
  const __initialCategory = ALLOWED_CATEGORIES.includes(__urlParams.get('category')) ? __urlParams.get('category') : 'All';
  const __initialRarity   = ALLOWED_RARITIES.includes(__urlParams.get('rarity')) ? __urlParams.get('rarity') : 'All';
  const __initialMin      = (__urlParams.get('min') || '').slice(0, 16);
  const __initialMax      = (__urlParams.get('max') || '').slice(0, 16);
  // Batch 651 follow-up — deep-link the min-discount filter so
  // shareable URLs like `/?discount=30` land the viewer on the pool
  // the sharer actually saw. Whitelist [0, 5, 10, 20, 30, 50] — same
  // values the dropdown exposes — so a crafted `discount=99` falls
  // back to 0 instead of producing an empty grid.
  const __initialDiscount = (() => {
    const raw = parseInt(__urlParams.get('discount') || '0', 10);
    return [0, 5, 10, 20, 30, 50].includes(raw) ? raw : 0;
  })();

  const [searchInput, setSearchInput]   = useState(__initialQ);
  const [search, setSearch]             = useState(__initialQ);
  useEffect(() => {
    const t = setTimeout(() => setSearch(searchInput), 300);
    return () => clearTimeout(t);
  }, [searchInput]);
  // Autocomplete suggestions — keyboard-navigable dropdown showing up to 8
  // item matches. Debounced at 180ms so typing "watch" doesn't fire 5 GETs.
  // Closes on click-outside, Esc, or selecting a suggestion.
  const [suggest, setSuggest]           = useState([]);
  const [suggestOpen, setSuggestOpen]   = useState(false);
  const [suggestIdx, setSuggestIdx]     = useState(-1);
  // Recent search strings — persists last 6 across sessions. Populated
  // when the user presses Enter or selects a suggestion; surfaced when
  // the input is empty-focused so users can re-run a prior query.
  const [recentSearches, setRecentSearches] = useState(() => {
    try { return JSON.parse(localStorage.getItem('sb_recent_searches') || '[]'); }
    catch { return []; }
  });
  const pushRecentSearch = (q) => {
    if (!q || !q.trim() || q.trim().length < 2) return;
    const val = q.trim();
    setRecentSearches(prev => {
      const next = [val, ...prev.filter(x => x.toLowerCase() !== val.toLowerCase())].slice(0, 6);
      try { localStorage.setItem('sb_recent_searches', JSON.stringify(next)); } catch (_) {}
      return next;
    });
  };

  // Saved searches — named filter presets so users who repeatedly hunt
  // the same slice of the marketplace (e.g. "Limited hats under $20 +
  // biggest discount") can re-apply the whole filter set in one click.
  // Capped at 10 — beyond that the dropdown becomes a scroll-hell.
  const [savedSearches, setSavedSearches] = useState(() => {
    try { return JSON.parse(localStorage.getItem('sb_saved_searches') || '[]'); }
    catch { return []; }
  });
  const persistSavedSearches = (next) => {
    try { localStorage.setItem('sb_saved_searches', JSON.stringify(next)); } catch (_) {}
    setSavedSearches(next);
  };
  // Inline save-search drawer state. `null` = closed, string = open with
  // that draft name. Replaces an older `window.prompt()` which was
  // non-accessible (no screen-reader dialog semantics, blocks mobile
  // keyboards, non-dismissable by Escape on some browsers) and worse,
  // blocked by some password managers that treat prompts as suspicious.
  const [saveSearchDraft, setSaveSearchDraft] = useState(null);
  const openSaveSearchDrawer = () => {
    // Batch 958 — recognise the extended toolbar filters (batch 957) as
    // meaningful "adjustments". Before this, a user who flipped Deals
    // Only or picked ≥20% off (but left category + search blank) got a
    // confusing "Adjust at least one filter" toast even though they had.
    const hasAnyFilter = search || category !== 'All' || rarity !== 'All' ||
                         sort !== 'price_desc' || minPrice || maxPrice ||
                         minDiscountPct > 0 || dealsOnly || newOnly ||
                         affordableOnly || listingTypeFilter !== 'ALL';
    if (!hasAnyFilter) {
      showToast('Adjust at least one filter before saving a search.', 'err');
      return;
    }
    if (savedSearches.length >= 10) {
      showToast('Saved-search slot limit (10) reached — delete one first.', 'err');
      return;
    }
    // Batch 958 — default name covers the full filter set. Each segment
    // stays terse so the whole name fits the 60-char cap in the input.
    const listingTypeLabel = listingTypeFilter === 'AUCTION'  ? 'Auctions'
                           : listingTypeFilter === 'BUY_NOW'  ? 'Buy-now'
                           : null;
    const defaultName = [
      search ? `"${search}"` : null,
      category !== 'All' ? category : null,
      rarity !== 'All' ? rarity : null,
      listingTypeLabel,
      minDiscountPct > 0 ? `≥${minDiscountPct}% off` : (dealsOnly ? 'Deals' : null),
      newOnly ? 'New' : null,
      affordableOnly ? 'Affordable' : null,
      (minPrice || maxPrice) ? `${currencySymbol()}${minPrice || 0}–${maxPrice || '∞'}` : null
    ].filter(Boolean).join(' · ') || 'Untitled';
    setSaveSearchDraft(defaultName);
  };
  const commitSaveSearch = async (rawName) => {
    const name = (rawName || '').trim();
    if (!name) return;
    if (savedSearches.length >= 10) {
      showToast('Saved-search slot limit (10) reached — delete one first.', 'err');
      setSaveSearchDraft(null);
      return;
    }
    const entry = {
      id: Date.now(),
      name: name.slice(0, 60),
      search, category, rarity, sort, minPrice, maxPrice,
      // Batch 957 — also capture every toolbar filter so re-applying
      // the preset restores the user's exact intent. Pre-957 presets
      // silently dropped the discount / type / deals / new toggles.
      minDiscountPct, dealsOnly, newOnly, affordableOnly,
      listingType: listingTypeFilter,
      savedAt: Date.now()
    };
    persistSavedSearches([entry, ...savedSearches]);
    setSaveSearchDraft(null);
    // Batch 912 — name the saved preset + hint at the match-alert behaviour.
    // New users don't know saved searches auto-fire notifications when a
    // fresh listing matches; surfacing it here raises retention.
    showToast(`Saved search "${entry.name}" — you'll get a match alert when a fresh listing fits.`, 'ok');
    if (!me) return;
    try {
      const { upsertSavedSearch } = await import('./api.js');
      const res = await upsertSavedSearch({
        name: entry.name, q: entry.search, category: entry.category,
        rarity: entry.rarity, sort: entry.sort,
        minPrice: entry.minPrice, maxPrice: entry.maxPrice,
        minDiscountPct: entry.minDiscountPct,
        dealsOnly: entry.dealsOnly,
        newOnly: entry.newOnly,
        affordableOnly: entry.affordableOnly,
        listingType: entry.listingType
      });
      if (res && (res.error || res.code)) {
        persistSavedSearches(savedSearches);
        showToast(res.message || res.error, 'err');
        return;
      }
      const { fetchSavedSearches } = await import('./api.js');
      const fresh = await fetchSavedSearches();
      if (Array.isArray(fresh)) persistSavedSearches(fresh);
    } catch (_) { /* offline — keep optimistic local insert */ }
  };
  const applySavedSearch = (s) => {
    // Batch 957 — restore the full toolbar state. The server payload
    // uses `q` (rename from `search`), and the extended filters come
    // through with defaults that match a blank toolbar, so pre-957
    // presets (no extended fields) still apply cleanly.
    const q = (s.q != null) ? s.q : (s.search || '');
    setSearch(q);
    setSearchInput(q);
    setCategory(s.category || 'All');
    setRarity(s.rarity || 'All');
    setSort(s.sort || 'price_desc');
    setMinPrice(s.minPrice || '');
    setMaxPrice(s.maxPrice || '');
    setMinDiscountPct(Number.isFinite(+s.minDiscountPct) ? (+s.minDiscountPct) : 0);
    setDealsOnly(s.dealsOnly === true);
    setNewOnly(s.newOnly === true);
    setAffordableOnly(s.affordableOnly === true);
    const validTypes = ['ALL', 'BUY_NOW', 'AUCTION'];
    setListingTypeFilter(validTypes.includes(s.listingType) ? s.listingType : 'ALL');
  };
  const deleteSavedSearch = async (id) => {
    if (!confirm('Delete this saved search?')) return;
    const prev = savedSearches;
    const target = prev.find(s => s.id === id);
    persistSavedSearches(savedSearches.filter(s => s.id !== id));
    // Pre-fix: deleteSavedSearch was silent on success while
    // commitSaveSearch and deleteAllSavedSearchesHandler both toasted.
    // The drawer is a tight popover so a row disappearing isn't always
    // obvious — name the deleted preset so the user knows the right
    // one went.
    showToast(target?.name
      ? `Saved search "${target.name}" deleted.`
      : 'Saved search deleted.',
      'ok');
    if (!me) return;
    try {
      const { deleteSavedSearchById } = await import('./api.js');
      const res = await deleteSavedSearchById(id);
      if (res && (res.error || res.code)) {
        persistSavedSearches(prev);
        showToast(res.message || res.error, 'err');
      }
    } catch (_) { /* offline — keep local delete */ }
  };
  // Bulk-delete every saved search (batch 354). Parity with watchlist
  // "Clear all" + follow "Unfollow all". Localstorage is wiped
  // optimistically; server-side bulk delete writes-through for
  // signed-in users, with a revert if the API rejects.
  const deleteAllSavedSearchesHandler = async () => {
    if (savedSearches.length === 0) return;
    if (!confirm(`Delete all ${savedSearches.length} saved search${savedSearches.length === 1 ? '' : 'es'}? This cannot be undone.`)) return;
    const prev = savedSearches;
    persistSavedSearches([]);
    if (!me) return;
    try {
      const { deleteAllSavedSearches } = await import('./api.js');
      const res = await deleteAllSavedSearches();
      if (res && (res.error || res.code)) {
        persistSavedSearches(prev);
        showToast(res.message || res.error, 'err');
        return;
      }
      showToast(`Cleared ${res?.removed || prev.length} saved search${(res?.removed || prev.length) === 1 ? '' : 'es'}.`, 'ok');
    } catch (_) { /* offline — keep local wipe */ }
  };
  useEffect(() => {
    const q = (searchInput || '').trim();
    if (q.length < 2) { setSuggest([]); return; }
    const t = setTimeout(async () => {
      try {
        const r = await fetch(`/api/items?q=${encodeURIComponent(q)}`, { credentials: 'same-origin' });
        if (!r.ok) return;
        const items = await r.json();
        setSuggest(Array.isArray(items) ? items.slice(0, 8) : []);
      } catch (_) {}
    }, 180);
    return () => clearTimeout(t);
  }, [searchInput]);
  useEffect(() => {
    const onDoc = (e) => {
      if (!e.target.closest?.('.search-wrap')) setSuggestOpen(false);
    };
    document.addEventListener('click', onDoc);
    return () => document.removeEventListener('click', onDoc);
  }, []);
  const [category, setCategory]         = useState(__initialCategory);
  const [rarity, setRarity]             = useState(__initialRarity);
  const [sort, setSort]                 = useState(__initialSort);
  // Mobile filters bottom-sheet — at <768px the sidebar collapses out of
  // the layout grid; we expose it again as a slide-up drawer when the
  // floating "Filters" pill is tapped. Open state lives at App level so
  // the FAB and the drawer stay in sync. Auto-closes on resize > 768px.
  const [mobileFiltersOpen, setMobileFiltersOpen] = useState(false);
  useEffect(() => {
    const onResize = () => { if (window.innerWidth > 768 && mobileFiltersOpen) setMobileFiltersOpen(false); };
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, [mobileFiltersOpen]);
  useEffect(() => {
    if (!mobileFiltersOpen) return;
    const onKey = (e) => { if (e.key === 'Escape') setMobileFiltersOpen(false); };
    document.addEventListener('keydown', onKey);
    document.body.classList.add('mobile-filters-open');
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.classList.remove('mobile-filters-open');
    };
  }, [mobileFiltersOpen]);
  // 2026-05-20 — close the mobile filter drawer whenever we leave the
  // marketplace route. The sidebar + drawer only render on /market, so
  // an open drawer that survived a navigation to /profile (etc.) would
  // silently re-appear the next time the user returned to /market — a
  // drawer they never re-opened. Resetting on route change keeps the
  // open state honest.
  useEffect(() => {
    if (routeName !== 'market' && mobileFiltersOpen) setMobileFiltersOpen(false);
  }, [routeName]);
  const [minPrice, setMinPrice]         = useState(__initialMin);
  const [maxPrice, setMaxPrice]         = useState(__initialMax);
  // Listing-type filter. Three values: 'ALL' | 'BUY_NOW' | 'AUCTION'. We
  // apply this client-side on top of the server response so users can
  // toggle instantly without a roundtrip. Buy-now includes null
  // listingType for historical rows.
  // Batch 812 — hydrate from URL so shared filter links restore the
  // exact type chip (`?type=AUCTION` → only auctions).
  const __initialType = (() => {
    const raw = (__urlParams.get('type') || 'ALL').toUpperCase();
    return ['ALL', 'BUY_NOW', 'AUCTION'].includes(raw) ? raw : 'ALL';
  })();
  const [listingTypeFilter, setListingTypeFilter] = useState(__initialType);
  // Deal hunter toggle — when on, only show listings priced below the
  // catalogue steamPrice (i.e. cheaper than you'd pay on Steam Market).
  // Pure client-side filter applied before dedup so the cheapest seller
  // per item still wins the grid card.
  // Batch 812 — hydrate from `?deals=1`.
  const [dealsOnly, setDealsOnly] = useState(__urlParams.get('deals') === '1');
  // Batch 651 — min-discount chip filter (CSFloat §10 parity). When
  // > 0, show only listings with ≥ N% discount vs Steam. Composes with
  // `dealsOnly` (≥1% floor) so picking a stronger chip narrows the
  // pool further. 0 = chip inactive. Initial value from `?discount=`
  // so shared deal-hunter URLs land on the right scope.
  const [minDiscountPct, setMinDiscountPct] = useState(__initialDiscount);
  // Batch 662 — "Hide my listings" toggle. Signed-in sellers with
  // live inventory often open the market to size their ask against
  // competitors, and their own listings get in the way. Localstorage-
  // persisted so the preference sticks across sessions; silent
  // (chip hidden) for anonymous viewers who have no listings to hide.
  const [hideMine, setHideMine] = useState(() => {
    try { return localStorage.getItem('sb_market_hide_mine') === '1'; }
    catch { return false; }
  });
  const setHideMineP = (v) => {
    setHideMine(v);
    try { localStorage.setItem('sb_market_hide_mine', v ? '1' : '0'); } catch (_) {}
  };
  // New-in-24h toggle — highlights fresh inventory. Client-side filter
  // on listedAt; pairs cleanly with Deals and the type toggles.
  // Batch 812 — hydrate from `?new=1`.
  const [newOnly, setNewOnly] = useState(__urlParams.get('new') === '1');
  // Affordable-only toggle (batch 367) — show listings priced ≤ my
  // wallet balance so a browsing user doesn't scroll past items they
  // can't afford. Client-side filter; silently disables for anon
  // viewers since they have no wallet to compare against.
  // Batch 812 — hydrate from `?aff=1`.
  const [affordableOnly, setAffordableOnly] = useState(__urlParams.get('aff') === '1');

  // item detail
  const [selected, setSelected]         = useState(null);
  const [modalLoading, setModalLoading] = useState(false);

  // wallet
  const [wallet, setWallet]             = useState(null);
  const [transactions, setTransactions] = useState([]);
  const [walletInitialTab, setWalletInitialTab] = useState('deposit');
  // Deposit prefill — when the cart low-balance banner sends the user to
  // /wallet we stash the shortfall so the deposit form opens with the
  // right number already typed. Cleared once consumed. Also respected
  // from the URL: /wallet?prefill=12.34
  const [walletPrefillAmount, setWalletPrefillAmount] = useState(null);
  // Hydrate prefill from `/wallet?prefill=X` so third-party deeplinks
  // (e.g. a "top up" button on an external dashboard) can open the
  // deposit form with the amount pre-filled. Fires only when the wallet
  // route opens, then strips the param so back/forward doesn't keep
  // re-prefilling as the user navigates.
  useEffect(() => {
    if (routeName !== 'wallet') return;
    try {
      const p = new URLSearchParams(window.location.search).get('prefill');
      if (!p) return;
      const n = parseFloat(p);
      if (!Number.isFinite(n) || n <= 0 || n > 100000) return;
      setWalletInitialTab('deposit');
      setWalletPrefillAmount(n.toFixed(2));
      // Strip the ?prefill= param so reload + back/forward don't
      // re-fire the prefill. Keeps the path clean without dropping
      // other query params (e.g. ?deposit=success after Stripe).
      const params = new URLSearchParams(window.location.search);
      params.delete('prefill');
      const q = params.toString();
      const next = window.location.pathname + (q ? ('?' + q) : '');
      window.history.replaceState({}, '', next);
    } catch (_) { /* URL parse failure — silently ignore */ }
  }, [routeName]);

  // auth
  // `meLoaded` is false until the first fetchMe() resolves. We use this
  // to hide the hero block on the very first paint — otherwise the page
  // renders the signed-out "Welcome to SkinBox" hero for ~200ms before
  // the cookie-based session comes back and flips it to the signed-in
  // "Welcome back, <name>" hero. That flash of wrong content is what
  // the user calls "flickering on reload".
  const [me, setMe]                     = useState(null);
  const [meLoaded, setMeLoaded]         = useState(false);
  const [menuOpen, setMenuOpen]         = useState(false);
  // Batch 933 — close the user menu on Escape. Pointer users can click
  // the backdrop to close; keyboard-only users were stranded inside
  // the open menu with no dismissal path. Listener mounts only while
  // the menu is open so unrelated Esc presses aren't intercepted.
  useEffect(() => {
    if (!menuOpen) return;
    const onKey = (e) => {
      if (e.key === 'Escape') { e.stopPropagation(); setMenuOpen(false); }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [menuOpen]);
  const [isAdmin, setIsAdmin]           = useState(false);
  const [isCsr, setIsCsrRole]           = useState(false);
  // Pending-actions counts — drives the red dot on the user-chip avatar.
  // Polls every 60s while signed in so a newly-opened trade / offer
  // surfaces quickly without a page reload. Null = not-yet-fetched or
  // anonymous (no badge rendered).
  const [pendingActions, setPendingActions] = useState(null);
  useEffect(() => {
    if (!me) { setPendingActions(null); return; }
    let alive = true;
    const load = async () => {
      try {
        const { fetchPendingActions } = await import('./api.js');
        const data = await fetchPendingActions();
        if (alive && data) setPendingActions(data);
      } catch (_) {}
    };
    load();
    // Batch 806 — visibility-aware poll. The red pending-actions dot
    // doesn't need to tick every 60s on every backgrounded tab of a
    // user with a busy browser. Refresh kicks back in on visibility
    // change so a returning user sees the fresh count.
    const id = setInterval(() => { if (!document.hidden) load(); }, 60_000);
    const onVis = () => { if (!document.hidden) load(); };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      alive = false; clearInterval(id);
      document.removeEventListener('visibilitychange', onVis);
    };
  }, [me?.id]);
  // Pre-signin ToS + email modal state. Opens on the "Sign in through
  // Steam" button; redirects to the real OpenID flow after the user ticks
  // the ToS box and enters a valid email.
  const [signinOpen, setSigninOpen]     = useState(false);

  // layout
  const [heroTab, setHeroTab]           = useState('topDeals');
  const [feeInput, setFeeInput]         = useState('100');

  // "Preselected" item the BuyOrdersModal uses when opened from ItemModal.
  // Not part of the URL — ephemeral state that lives only while the
  // buy-orders route is active for this specific item.
  const [preselectedBuyItem, setPreselectedBuyItem] = useState(null);

  // Public stall page data — loaded whenever we hit /stall/:id.
  // Reviews are fetched in parallel with the listings payload so the
  // rating chip + "Recent reviews" block render together. Eligible trades
  // only populate when a signed-in viewer loads someone else's stall.
  const [stallData, setStallData] = useState(null);
  // Batch 835 — inline drawer for "Report seller" on the stall hero.
  // Replaces the two-step window.prompt flow (pick reason by number,
  // type context) with a proper dialog. Same pattern as the trade-
  // counterparty report drawer in modals.js.
  const [reportSellerOpen, setReportSellerOpen] = useState(false);
  const [reportSellerReason, setReportSellerReason] = useState('Scam attempt');
  const [reportSellerContext, setReportSellerContext] = useState('');
  const [reportSellerBusy, setReportSellerBusy] = useState(false);
  const [reportSellerErr, setReportSellerErr] = useState('');
  const openReportSeller = () => {
    setReportSellerReason('Scam attempt');
    setReportSellerContext('');
    setReportSellerErr('');
    setReportSellerOpen(true);
  };
  const submitReportSeller = async () => {
    if (!stallData?.seller?.id) return;
    setReportSellerErr('');
    setReportSellerBusy(true);
    try {
      const { reportUser } = await import('./api.js');
      const res = await reportUser(stallData.seller.id, reportSellerReason, reportSellerContext || '');
      if (res && (res.error || res.code)) {
        setReportSellerErr(res.message || res.error || 'Could not file report');
        return;
      }
      setReportSellerOpen(false);
      showToast('Report filed — Support will review, track in /support.', 'ok');
    } finally { setReportSellerBusy(false); }
  };
  // Escape closes the report drawer (busy-guarded).
  useEffect(() => {
    if (!reportSellerOpen) return;
    const onKey = (e) => {
      if (e.key !== 'Escape' || reportSellerBusy) return;
      e.stopPropagation();
      setReportSellerOpen(false);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [reportSellerOpen, reportSellerBusy]);
  const [stallReviews, setStallReviews] = useState(null);
  const [stallSold, setStallSold]       = useState([]);
  const [eligibleTrades, setEligibleTrades] = useState([]);
  // Star-rating filter for the recent-reviews strip. 0 = all.
  const [stallStarFilter, setStallStarFilter] = useState(0);
  // Batch 751 — "Show all N" expander state for the reviews strip.
  // Starts collapsed at 10 rows (CSFloat parity for the default strip);
  // a click bumps to "all visible after filter/sort" so buyers auditing
  // a long-tenured seller aren't silently hiding 20+ rows of context.
  const [stallReviewsExpanded, setStallReviewsExpanded] = useState(false);
  // Batch 752 — matching expander for the Recent Sales strip. The
  // backend already returns up to 200 rows via fetchPublicStallSold,
  // but the UI was silently truncating to 10. A heavy-volume seller's
  // "did they actually move inventory?" signal was getting cut short.
  const [stallSoldExpanded, setStallSoldExpanded] = useState(false);
  // Review sort order on the stall page. 'newest' is the default — matches
  // the "Recent reviews" heading for casual visitors. 'helpful' uses the
  // upvote counts shipped in batch 259 and is the CSFloat-parity default
  // for long-tail stalls with lots of reviews. Persisted so a buyer who
  // prefers "most helpful" doesn't have to re-pick it on every visit.
  const [stallReviewSort, setStallReviewSort] = useState(() => {
    try { return localStorage.getItem('sb_stall_review_sort') || 'newest'; }
    catch { return 'newest'; }
  });
  const setStallReviewSortPersist = (v) => {
    setStallReviewSort(v);
    try { localStorage.setItem('sb_stall_review_sort', v); } catch (_) {}
  };
  // Stall listing filter + sort controls. Rarity stays 'All' by default
  // so new visitors see every listing; sort defaults to price_asc which
  // mirrors CSFloat's "best deal first" convention on stall views.
  const [stallRarity, setStallRarity] = useState('All');
  const [stallSort, setStallSort]     = useState('price_asc');
  // Name-search across the stall listings. Large stalls (50+ items) were
  // hard to scan by rarity chips alone — a seller sharing "blue shirt"
  // with a buyer wanted the buyer to land on that exact item, not paginate
  // the whole rarity bucket. Client-side filter on the already-fetched
  // stall payload so typing is instant and doesn't hit the API.
  const [stallSearch, setStallSearch] = useState('');
  useEffect(() => {
    if (routeName !== 'stall' || !route.params?.id) {
      setStallData(null); setStallReviews(null); setEligibleTrades([]); setStallSold([]); return;
    }
    // 2026-05-20 — reset per-stall ephemeral UI state on every stall id
    // change. Without this, navigating from stall A (filtered to e.g.
    // "Limited" rarity, or with a name search typed, or with the listing
    // sort / reviews strip changed) to stall B carried that state over —
    // stall B showed "No listings match this filter" even though it had
    // inventory, or a half-typed review form from stall A's trade
    // lingered. stallReviewSort is the one exception: it is localStorage-
    // backed (an explicit cross-stall user preference) so it is NOT reset.
    setStallRarity('All');
    setStallSort('price_asc');
    setStallSearch('');
    setStallStarFilter(0);
    setStallReviewsExpanded(false);
    setStallSoldExpanded(false);
    setReviewTradeId(null);
    setReviewText('');
    setReviewStars(5);
    let alive = true;
    (async () => {
      // Two-phase fetch. Phase 1: probe whether the seller exists. Phase 2
      // (only on hit): fan out to reviews / eligibility / sold. Pre-fix this
      // fired all 4 in parallel — on a dead /stall/:id link (expired share,
      // deleted account) the SPA still wasted 3 round trips fetching empty
      // arrays for a stall that doesn't exist. Adds one serial round trip
      // on the happy path; eliminates 3 wasted requests per dead link.
      const stall = await fetchPublicStall(route.params.id);
      if (!alive) return;
      let reviews = null, eligible = [], sold = [];
      if (stall) {
        [reviews, eligible, sold] = await Promise.all([
          fetchReviewsForUser(route.params.id),
          me ? fetchEligibleReviews(route.params.id) : Promise.resolve([]),
          // Deeper sample (200) so the stall-sales sparkline (batch 362)
          // has meaningful data — the strip below still renders 10.
          fetchPublicStallSold(route.params.id, 200)
        ]);
        if (!alive) return;
      }
      // Distinguish "still loading" (null) from "loaded but 404"
      // ({ __notFound: true }) so the render can show a friendly
      // empty-state instead of spinning forever on a bad id.
      setStallData(stall || { __notFound: true });
      try {
        const currentPrefix = (document.title.match(/^(\([^)]+\)\s+)/) || [, ''])[1];
        if (stall?.seller?.displayName) {
          document.title = currentPrefix + stall.seller.displayName + "'s Stall · SkinBox";
        } else if (!stall) {
          document.title = currentPrefix + 'Stall not found · SkinBox';
        }
      } catch (_) {}
      setStallReviews(reviews);
      setEligibleTrades(Array.isArray(eligible) ? eligible : []);
      setStallSold(Array.isArray(sold) ? sold : []);
      // Deep-link support for Profile → Reviews → Pending "Leave review →"
      // (batch 341). If the URL carries ?leaveReview={tradeId} AND that
      // trade is actually un-reviewed between this viewer and this seller,
      // auto-focus the review form on it.
      try {
        const qs = new URLSearchParams(window.location.search);
        const wantTradeId = qs.get('leaveReview');
        if (wantTradeId && Array.isArray(eligible)) {
          const match = eligible.find(t =>
            String(t.tradeId) === wantTradeId && !t.reviewed);
          if (match) {
            setReviewTradeId(match.tradeId);
            setReviewStars(5);
            setReviewText('');
            qs.delete('leaveReview');
            const next = qs.toString();
            window.history.replaceState({}, '',
              window.location.pathname + (next ? '?' + next : ''));
          }
        }
      } catch (_) {}
    })();
    return () => { alive = false; };
  }, [routeName, route.params?.id, me?.id]);

  // Inline review form state (lives on the stall page).
  const [reviewTradeId, setReviewTradeId] = useState(null);
  const [reviewStars, setReviewStars]     = useState(5);
  const [reviewText, setReviewText]       = useState('');
  const [reviewBusy, setReviewBusy]       = useState(false);
  const submitStallReview = async () => {
    if (!reviewTradeId) return;
    setReviewBusy(true);
    try {
      const res = await leaveReview(reviewTradeId, reviewStars, reviewText || '');
      if (res && !res.error && !res.code) {
        setReviewTradeId(null); setReviewText(''); setReviewStars(5);
        // Refresh reviews + eligibility so the UI reflects the new state.
        const [reviews, eligible] = await Promise.all([
          fetchReviewsForUser(route.params.id),
          fetchEligibleReviews(route.params.id)
        ]);
        setStallReviews(reviews);
        setEligibleTrades(Array.isArray(eligible) ? eligible : []);
        showToast('Review posted.', 'ok');
      } else {
        // Pre-fix: a backend rejection (already-reviewed, validation failure,
        // network blip) silently left the form open with no feedback. The
        // user clicked Submit, "Sending…" flashed, and the modal returned
        // to its previous state — looked like a dead button. Surface the
        // server-supplied message (or a generic fallback) as an error toast.
        showToast(
          (res && (res.message || res.error)) || 'Could not post review — try again.',
          'err'
        );
      }
    } finally { setReviewBusy(false); }
  };

  // privacy mode — hides balance + sensitive amounts across the whole UI
  const [privacy, setPrivacy] = useState(() => localStorage.getItem('sb_privacy') === '1');
  useEffect(() => { localStorage.setItem('sb_privacy', privacy ? '1' : '0'); }, [privacy]);
  // Cross-tab sync — when the user toggles privacy in tab A (via Ctrl-click
  // on the nav wallet, or from inside WalletModal if we ever expose a
  // toggle there), every other open tab's App needs to re-render so the
  // nav balance chip and every masked surface flips instantly. Without
  // this, tab B keeps showing the real amounts until a full refresh.
  useEffect(() => {
    const onStorage = (e) => {
      if (e.key === 'sb_privacy') setPrivacy(e.newValue === '1');
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);

  // Settings change ticker — bumps on storage events so every rendered
  // `fmt()` call re-reads the current currency even when it was changed
  // in a different tab. We tick a dummy state and react's re-render picks
  // up the new fmt() output on next paint.
  const [, bumpCurrency] = useState(0);
  useEffect(() => {
    const onStorage = (e) => { if (e.key === 'sb_currency') bumpCurrency(x => x + 1); };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);

  // Shopping cart — stored as an array of { id, name, price, thumb } in
  // localStorage so it survives reloads and is still owned by the user, not
  // the server. Checkout POSTs just the ids to /api/cart/checkout.
  const [cart, setCart] = useState(() => {
    try { return JSON.parse(localStorage.getItem('sb_cart') || '[]'); } catch { return []; }
  });
  useEffect(() => { localStorage.setItem('sb_cart', JSON.stringify(cart)); }, [cart]);
  // Cross-tab cart sync — the browser fires a 'storage' event in every
  // tab EXCEPT the one that wrote the change, so this listener keeps
  // tabs B/C up-to-date when tab A adds or removes a row. Otherwise
  // the nav-badge count drifts until the tab is refreshed. Also used
  // to sync the watchlist and saved-search arrays.
  useEffect(() => {
    const onStorage = (e) => {
      if (e.key === 'sb_cart') {
        try { setCart(JSON.parse(e.newValue || '[]')); } catch (_) {}
      } else if (e.key === 'sb_watchlist') {
        try { setWatchlist(JSON.parse(e.newValue || '[]')); } catch (_) {}
      }
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);
  // When the user lands on /cart, ping each cart-row's listing to make
  // sure it's still ACTIVE — if the listing was sold to someone else
  // (or force-cancelled) while it was sitting in this user's cart, we
  // drop it proactively + toast once so the checkout button doesn't
  // try to buy a 404. Only fires on cart route entry; no polling loop.
  useEffect(() => {
    if (routeName !== 'cart' || cart.length === 0) return;
    let alive = true;
    (async () => {
      // Single bulk probe (batch 474) — the previous N-parallel
      // fetchListingById loop made one HTTP round-trip per cart row.
      // For a 50-item cart that's 50 separate request lifecycles;
      // /listings/check-active returns the same {active, price} per
      // id in one shot. Caps at 50 ids server-side which matches the
      // existing cart cap so no truncation risk.
      const rows = await checkListingsActive(cart.map(it => it.id));
      if (!alive) return;
      if (!Array.isArray(rows)) return;
      const goneIds = new Set(
        rows.filter(r => r && r.active === false).map(r => r.id)
      );
      if (goneIds.size === 0) return;
      setCart(c => c.filter(x => !goneIds.has(x.id)));
      setToast({
        text: `${goneIds.size} item${goneIds.size === 1 ? '' : 's'} removed — sold before checkout`,
        kind: 'err'
      });
      setTimeout(() => setToast(null), 4500);
    })();
    return () => { alive = false; };
  }, [routeName]);
  const cartCount = cart.length;
  // Server-side freshness map — keyed by listing id. Re-fetched every
  // time /cart is opened because the cart is persisted client-side and
  // a row can go stale (bought by someone else) or have its price
  // edited by the seller between sessions. Missing keys render
  // neutrally (no banner) so a transient network blip doesn't scare
  // the buyer. Declared BEFORE cartTotal so the useMemo dep array
  // doesn't read it from the temporal dead zone.
  const [cartFreshness, setCartFreshness] = useState({});
  // Cart total uses the FRESH server-reported price when available so
  // sellers' mid-session price edits are reflected in the total (server
  // charges the current price at checkout — the cart display should
  // match what the user will actually pay). Falls back to the cached
  // client-side price when freshness isn't loaded yet.
  const cartTotal = useMemo(() => cart.reduce((s, it) => {
    const fresh = cartFreshness?.[it.id];
    const p = fresh && fresh.active && fresh.price != null
      ? parseFloat(fresh.price)
      : (parseFloat(it.price) || 0);
    return s + (Number.isFinite(p) ? p : 0);
  }, 0), [cart, cartFreshness]);
  useEffect(() => {
    if (routeName !== 'cart' || cart.length === 0) { setCartFreshness({}); return; }
    let alive = true;
    (async () => {
      const rows = await checkListingsActive(cart.map(it => it.id));
      if (!alive) return;
      const byId = {};
      rows.forEach(r => { byId[r.id] = r; });
      setCartFreshness(byId);
    })();
    return () => { alive = false; };
  }, [routeName, cart.length, cart.map(it => it.id).join(',')]);
  // Hydrate cart rows that were persisted as 'Loading…' placeholders.
  // A row can lose its name/thumb/itemId when the cart is cross-device-
  // merged via /api/cart/bulk-merge — the server returns ids only, so
  // newly-arriving rows (e.g. user added on desktop, opened cart on
  // mobile) show "Loading… $0.00" until this effect backfills them.
  useEffect(() => {
    if (routeName !== 'cart' || cart.length === 0) return;
    const stubs = cart.filter(it => !it.name || it.name === 'Loading…' || !it.itemId);
    if (stubs.length === 0) return;
    let alive = true;
    (async () => {
      const fetched = await Promise.all(stubs.map(it => fetchListingById(it.id).catch(() => null)));
      if (!alive) return;
      const hydrate = {};
      fetched.forEach((l, i) => {
        if (l && l.item) hydrate[stubs[i].id] = l;
      });
      if (Object.keys(hydrate).length === 0) return;
      setCart(prev => prev.map(it => {
        const l = hydrate[it.id];
        if (!l) return it;
        return {
          ...it,
          itemId:     l.item?.id ?? it.itemId,
          name:       l.item?.name ?? it.name,
          price:      l.price ?? it.price,
          steamPrice: l.item?.steamPrice ?? it.steamPrice,
          thumb:      l.item?.imageUrl ?? l.item?.thumb ?? it.thumb,
          sellerName:   l.sellerName   ?? it.sellerName,
          sellerUserId: l.sellerUserId ?? it.sellerUserId,
        };
      }));
    })();
    return () => { alive = false; };
  }, [routeName, cart.length, cart.map(it => it.id).join(',')]);
  const cartHasStale = useMemo(
    () => cart.some(it => cartFreshness[it.id] && !cartFreshness[it.id].active),
    [cart, cartFreshness]
  );
  const removeStaleCartRows = () => setCart(c => c.filter(it => {
    const fresh = cartFreshness[it.id];
    return !fresh || fresh.active;
  }));
  const addToCart = (listing) => {
    let alreadyHad = false;
    let atCap = false;
    setCart(c => {
      if (c.find(x => x.id === listing.id)) { alreadyHad = true; return c; }
      // Batch 988 — mirror the server's 50-item cart cap client-side.
      // Anonymous users keep the cart in localStorage only, so without
      // a local guard the count could grow past 50 and silently fail
      // at checkout with CART_TOO_LARGE. The toast explains the cap
      // instead of just dropping the click.
      if (c.length >= 50) { atCap = true; return c; }
      return [...c, {
        id:         listing.id,
        itemId:     listing.item?.id,
        name:       listing.item?.name,
        price:      listing.price,
        // Stash the Steam reference price so the cart page can show
        // "saved $X vs Steam" without re-fetching the catalogue on
        // every render. Falls back to null when the catalogue has no
        // Steam Market data yet (new item, pre-sync).
        steamPrice: listing.item?.steamPrice ?? null,
        thumb:      listing.item?.imageUrl || null,
        // Seller snapshot so the cart row can link to their stall
        // without a round-trip. sellerUserId is null for system
        // listings ("SkinBox Store") — we render the name as plain
        // text in that case.
        sellerUserId: listing.sellerUserId ?? null,
        sellerName:   listing.sellerName  ?? null
      }];
    });
    if (atCap) {
      if (typeof showToast === 'function') {
        showToast('Cart full (50 items). Remove some rows before adding more.', 'err');
      }
      return;
    }
    // Mirror to the server cart for cross-device sync. Anonymous users
    // stay localStorage-only, no behaviour change. Best-effort: if the
    // server rejects (CART_FULL) we revert the local add so the count
    // stays honest.
    if (me && !alreadyHad) {
      (async () => {
        try {
          const { addCartItem } = await import('./api.js');
          const res = await addCartItem(listing.id);
          if (res && (res.error || res.code)) {
            setCart(c => c.filter(x => x.id !== listing.id));
            if (typeof showToast === 'function') showToast(res.message || res.error, 'err');
          }
        } catch (_) { /* offline — keep local add, sign-in bridge will reconcile */ }
      })();
    }
  };
  // Total Steam-reference price for every cart row that has a
  // steamPrice snapshot. Saves = max(0, steamTotal - cartTotal).
  const cartSteamTotal = useMemo(() => cart.reduce((s, it) => {
    const sp = parseFloat(it.steamPrice);
    return s + (isFinite(sp) && sp > 0 ? sp : parseFloat(it.price) || 0);
  }, 0), [cart]);
  const cartSavings = Math.max(0, cartSteamTotal - parseFloat(
    cart.reduce((s, it) => s + (parseFloat(it.price) || 0), 0)));
  const removeFromCart = (id) => {
    setCart(c => c.filter(x => x.id !== id));
    if (me) {
      (async () => {
        try {
          const { removeCartItem } = await import('./api.js');
          await removeCartItem(id);
        } catch (_) {}
      })();
    }
  };
  const clearCart = () => {
    // Confirm before blowing away a non-trivial cart. Misclicks on the
    // Clear button used to silently wipe 10+ listings the buyer had
    // carefully curated; a quick yes/no prompt costs nothing on the
    // happy path and saves the user a lot of pain on the mistake path.
    if (cart.length >= 2 && !confirm(`Clear ${cart.length} item${cart.length === 1 ? '' : 's'} from your cart?`)) return;
    setCart([]);
    if (me) {
      (async () => {
        try {
          const { clearServerCart } = await import('./api.js');
          await clearServerCart();
        } catch (_) {}
      })();
    }
  };
  // Confirmation gate so buyers see a summary before bulk checkout fires.
  // Without this the "Buy Now" button on /cart silently paid-and-trade-opened
  // every row, and if one failed the user had no reviewable explanation.
  const [cartConfirmOpen, setCartConfirmOpen] = useState(false);
  const [cartBusy, setCartBusy] = useState(false);
  // Batch 827 — Escape closes the cart-confirm dialog. Busy-guarded
  // so mid-flight checkout can't be cancelled via a stray keypress.
  useEffect(() => {
    if (!cartConfirmOpen) return;
    const onKey = (e) => {
      if (e.key !== 'Escape') return;
      if (cartBusy) return;
      e.stopPropagation();
      setCartConfirmOpen(false);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [cartConfirmOpen, cartBusy]);
  const doCheckout = async () => {
    if (cart.length === 0) return;
    setCartBusy(true);
    try {
      const ids = cart.map(x => x.id);
      // Build the expectedPrices map from whatever price the user
      // actually saw in the confirm dialog. Prefer the fresh server
      // price (cart-freshness probe) over the cached add-time price —
      // the confirm modal's Total row uses the same preference, so
      // expected == total. Server rejects rows with PRICE_CHANGED if
      // the price drifted in the last second (seller edit mid-click).
      const expectedPrices = {};
      cart.forEach(x => {
        const fresh = cartFreshness?.[x.id];
        const p = fresh && fresh.active && fresh.price != null
          ? parseFloat(fresh.price)
          : parseFloat(x.price);
        if (Number.isFinite(p)) expectedPrices[x.id] = p.toFixed(2);
      });
      const res = await checkoutCart(ids, expectedPrices);
      if (res && res.error) {
        showToast(res.error, 'err');
      } else if (res && res.results) {
        const ok = res.successful || 0;
        const fail = res.failed || 0;
        const failedRows = res.results.filter(r => r.status !== 'OK');
        const failedIds = new Set(failedRows.map(r => r.listingId));
        // Insufficient-balance is the single most common failure mode on a
        // bulk cart checkout and has a specific remediation — top up your
        // wallet. When every failure is INSUFFICIENT_BALANCE, bounce the
        // user straight to /wallet with the shortfall prefilled so they can
        // fund and retry in one click instead of wondering what went wrong.
        const insufficientCount = failedRows.filter(r => r.code === 'INSUFFICIENT_BALANCE').length;
        const priceChangedCount = failedRows.filter(r => r.code === 'PRICE_CHANGED').length;
        const allInsufficient = fail > 0 && ok === 0 && insufficientCount === fail;
        // Price-changed failures get a distinct toast so users know to
        // refresh instead of wondering what "failed" meant. Falls back
        // to the generic count-toast when the failures are mixed.
        if (priceChangedCount === fail && fail > 0 && ok === 0) {
          showToast(`Price changed on ${fail} item${fail === 1 ? '' : 's'} — refresh and retry`, 'err');
        } else {
          showToast(`Bought ${ok} item${ok === 1 ? '' : 's'}${fail > 0 ? ` · ${fail} failed` : ''}`, fail > 0 ? 'err' : 'ok');
        }
        setCart(c => c.filter(x => failedIds.has(x.id)));
        setCartConfirmOpen(false);
        await loadWallet();
        load();
        if (allInsufficient) {
          // Batch 964 — prefer the server's authoritative per-row
          // `required` + `available` (batch 963 carried them into cart
          // row results). Every failed row attempted at the SAME starting
          // balance (no row succeeded, so no debit), so:
          //   top_up = sum(row.required) - available
          // That's exactly what the user must deposit to clear every
          // failed row on retry — no over/under-counting. Falls back to
          // client-side math for pre-964 servers that don't send details.
          let gap = 0;
          const haveServerDetails = failedRows.every(
            r => r.details?.required != null && r.details?.available != null);
          if (haveServerDetails) {
            const sumRequired = failedRows.reduce(
              (s, r) => s + (parseFloat(r.details.required) || 0), 0);
            const available = parseFloat(failedRows[0].details.available) || 0;
            gap = sumRequired - available;
          } else {
            const total = cart.filter(x => failedIds.has(x.id)).reduce((s, x) => {
              const fresh = cartFreshness?.[x.id];
              const p = fresh && fresh.active && fresh.price != null
                ? parseFloat(fresh.price)
                : (parseFloat(x.price) || 0);
              return s + (Number.isFinite(p) ? p : 0);
            }, 0);
            const bal = parseFloat(wallet?.balance || 0);
            gap = total - bal;
          }
          gap = Math.max(1, Math.ceil(gap));
          setWalletInitialTab('deposit');
          setWalletPrefillAmount(gap.toFixed(2));
          navigate(paths.wallet());
          return;
        }
        // Every successful cart row opens a trade — route straight to
        // the Trades tab so the user sees the escrow state machine
        // instead of landing on the Personal tab and having to switch.
        if (failedIds.size === 0) navigate('/profile/trades');
      } else {
        showToast('Checkout failed', 'err');
      }
    } finally { setCartBusy(false); }
  };

  // Watchlist — server-side for signed-in users (cross-device sync via
  // /api/watchlist), localStorage for anon. The localStorage array is
  // also kept up-to-date as a write-through cache so an offline reload
  // of a signed-in session shows stars without a network round trip.
  const [watchlist, setWatchlist]       = useState(() => {
    try { return JSON.parse(localStorage.getItem('sb_watchlist') || '[]'); } catch { return []; }
  });
  useEffect(() => { localStorage.setItem('sb_watchlist', JSON.stringify(watchlist)); }, [watchlist]);
  // One-shot bridge on first sign-in after batch 262: POST whatever the
  // anon localStorage had → /api/watchlist/bulk so the user keeps every
  // star they made before signing in. Server returns the merged set;
  // we replace `watchlist` with that. Subsequent sessions skip the
  // bridge (the localStorage flag is sticky).
  useEffect(() => {
    if (!me) return;
    let alive = true;
    const sentKey = `sb_watchlist_synced:${me.id}`;
    (async () => {
      try {
        const { fetchWatchlist, bulkMergeWatchlist } = await import('./api.js');
        if (!localStorage.getItem(sentKey)) {
          // First-touch merge — push localStorage ids to server, accept
          // server's truth back. Empty local set still hits the bridge
          // so the flag gets set and we skip next time.
          const merged = await bulkMergeWatchlist(watchlist || []);
          if (!alive) return;
          if (merged && Array.isArray(merged.ids)) setWatchlist(merged.ids);
          try { localStorage.setItem(sentKey, '1'); } catch (_) {}
        } else {
          // Returning session — server is source of truth; refresh local
          // cache so a star added on another device shows up here.
          const ids = await fetchWatchlist();
          if (!alive) return;
          if (Array.isArray(ids)) setWatchlist(ids);
        }
      } catch (_) { /* offline / 5xx — keep local cache */ }
    })();
    return () => { alive = false; };
  }, [me?.id]);
  // Saved-searches sign-in bridge — same shape as watchlist + cart.
  // First-touch: POST localStorage entries → server merges → replace
  // local with the merged authoritative list. Returning sessions: GET
  // and replace local cache so a preset added on another device shows
  // up here. Per-user flag so we only do the merge once per browser.
  useEffect(() => {
    if (!me) return;
    let alive = true;
    const sentKey = `sb_saved_searches_synced:${me.id}`;
    (async () => {
      try {
        const { fetchSavedSearches, bulkMergeSavedSearches } = await import('./api.js');
        if (!localStorage.getItem(sentKey)) {
          // Send only the filter fields — server generates fresh ids.
          const payload = (savedSearches || []).map(s => ({
            name:     s.name,
            q:        s.search || '',
            category: s.category,
            rarity:   s.rarity,
            sort:     s.sort,
            minPrice: s.minPrice || '',
            maxPrice: s.maxPrice || ''
          }));
          const merged = await bulkMergeSavedSearches(payload);
          if (!alive) return;
          if (merged && Array.isArray(merged.entries)) {
            persistSavedSearches(merged.entries);
          }
          try { localStorage.setItem(sentKey, '1'); } catch (_) {}
        } else {
          const fresh = await fetchSavedSearches();
          if (!alive) return;
          if (Array.isArray(fresh)) persistSavedSearches(fresh);
        }
      } catch (_) { /* offline — keep local cache */ }
    })();
    return () => { alive = false; };
  }, [me?.id]);

  // Cart sign-in bridge — same shape as the watchlist bridge above. The
  // server stores listing ids only; the per-row metadata (name/price/
  // thumb) stays in localStorage so the /cart page can render even
  // before the freshness ping completes. Reconcile by:
  //   - First-touch:  POST localStorage ids → server merges → server
  //     returns post-merge ids → drop any local rows the server didn't
  //     accept (cap, deleted listing) and KEEP per-row metadata for
  //     accepted rows.
  //   - Returning: GET ids → drop local rows the server doesn't have,
  //     and synthesize stub rows for ids the server has but local
  //     doesn't (other-device adds — metadata fills in lazily via the
  //     existing /cart freshness ping).
  useEffect(() => {
    if (!me) return;
    let alive = true;
    const sentKey = `sb_cart_synced:${me.id}`;
    (async () => {
      try {
        const { fetchCartIds, bulkMergeCart } = await import('./api.js');
        let serverIds;
        if (!localStorage.getItem(sentKey)) {
          const merged = await bulkMergeCart((cart || []).map(x => x.id));
          serverIds = merged && Array.isArray(merged.ids) ? merged.ids : null;
          try { localStorage.setItem(sentKey, '1'); } catch (_) {}
        } else {
          serverIds = await fetchCartIds();
        }
        if (!alive || !Array.isArray(serverIds)) return;
        const localById = {};
        (cart || []).forEach(x => { localById[x.id] = x; });
        const merged = serverIds.map(id => localById[id] || {
          id,
          itemId:     null,
          name:       'Loading…',
          price:      null,
          steamPrice: null,
          thumb:      null
        });
        setCart(merged);
      } catch (_) { /* offline — keep local cache */ }
    })();
    return () => { alive = false; };
  }, [me?.id]);
  const toggleStar = async (itemId) => {
    if (itemId == null) return;
    // Optimistic local flip — feels instant and works offline. The
    // server call below reconciles or reverts.
    let willStar = false;
    setWatchlist(w => {
      const has = w.includes(itemId);
      willStar = !has;
      return has ? w.filter(id => id !== itemId) : [...w, itemId];
    });
    if (!me) return;  // anon: localStorage only
    try {
      const { starItem, unstarItem } = await import('./api.js');
      const res = willStar ? await starItem(itemId) : await unstarItem(itemId);
      if (res && (res.error || res.code)) {
        // Revert on failure (e.g. WATCHLIST_FULL).
        setWatchlist(w => willStar ? w.filter(id => id !== itemId) : [...w, itemId]);
        if (typeof showToast === 'function') showToast(res.message || res.error, 'err');
        return;
      }
      // Trust server's authoritative ids if it shipped them.
      if (res && Array.isArray(res.ids)) setWatchlist(res.ids);
    } catch (_) {
      // Network error — keep optimistic local change; the next sign-in
      // bridge will reconcile.
    }
  };
  // Navigate helper closes the user-menu dropdown in the same click and
  // pushes a real URL onto history.
  const go = (pathFn, ...args) => {
    setMenuOpen(false);
    navigate(typeof pathFn === 'function' ? pathFn(...args) : pathFn);
  };

  // auth load
  const loadMe = useCallback(async () => {
    try {
      const m = await fetchMe();
      setMe(m);
      if (m) {
        // Check admin/csr role so we can show the right menu entries.
        const [a, c] = await Promise.all([adminCheck(), csrCheck()]);
        setIsAdmin(!!a?.admin);
        setIsCsrRole(!!c?.csr);
        // Post-Steam-return email verification hand-off. If the user went
        // through the pre-signin modal, a pending email is in localStorage;
        // fire it against /api/profile/email now so they get a verification
        // link in the first session. One-shot — key is cleared after.
        try {
          const pending = localStorage.getItem('sb_pending_email');
          if (pending && !m.email) {
            const { setEmail: apiSetEmail } = await import('./api.js');
            await apiSetEmail(pending);
            localStorage.removeItem('sb_pending_email');
          }
        } catch (e) { console.warn('pending email verify hand-off failed', e); }
      } else {
        setIsAdmin(false);
        setIsCsrRole(false);
      }
      return m;
    } finally {
      setMeLoaded(true);
    }
  }, []);
  useEffect(() => { loadMe(); }, [loadMe]);

  // Session heartbeat — checks /api/auth/steam/me every 5 minutes.
  // If the backend session has expired (45-min timeout), clear the
  // frontend auth state so the user sees "Sign in" instead of ghost
  // 401 errors on every action. Shows a toast on expiry.
  useEffect(() => {
    if (!meLoaded) return;
    const interval = setInterval(async () => {
      if (!me) return;
      const fresh = await fetchMe();
      if (!fresh) {
        setMe(null);
        setIsAdmin(false);
        setIsCsrRole(false);
        // Show a non-blocking notification instead of silent 401s
        try {
          const ev = new CustomEvent('sb:toast', { detail: { text: 'Session expired — please sign in again', kind: 'warn' } });
          window.dispatchEvent(ev);
        } catch {}
      }
    }, 5 * 60 * 1000);
    return () => clearInterval(interval);
  }, [me, meLoaded]);

  // wallet load
  const loadWallet = useCallback(async () => {
    try {
      const [w, tx] = await Promise.all([fetchWallet(), fetchTransactions()]);
      setWallet(w);
      setTransactions(Array.isArray(tx) ? tx : []);
    } catch (e) {
      console.error('loadWallet failed:', e);
      // Batch 668 — surface fetch failures so the /wallet route can
      // render an error panel with a Retry CTA instead of a blank page.
      // Only flag an error sentinel if we have nothing to show; a prior
      // successful load is kept so a transient refresh failure (e.g. a
      // tab-focus refetch) doesn't blow away a working wallet view.
      setWallet(prev => (prev && !prev.__error) ? prev : { __error: true });
    }
  }, []);
  useEffect(() => { loadWallet(); }, [loadWallet]);

  // Tab-focus refresh (batch 424). Stripe deposits + admin-approved
  // withdrawals + new sales all update the wallet asynchronously while
  // the user is in another tab waiting (or has switched to Steam to
  // accept a trade offer). Refetch the wallet + transactions whenever
  // visibility flips back to visible so the balance + pending chips
  // reflect reality without a manual page refresh. Cheap — two
  // small JSON GETs.
  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === 'visible') loadWallet();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [loadWallet]);

  // Batch 664 — active-tab wallet polling while a deposit is mid-flight.
  // The tab-focus refresh above catches the common "user flips to
  // Stripe Checkout then back" case, but a user who stays on /wallet
  // in the foreground (e.g. on their phone, with the Stripe tab on a
  // second device) won't see the pending deposit resolve. Stripe
  // webhooks usually land within 10s, so poll every 8s while there's
  // at least one PENDING deposit AND the /wallet route is active.
  // Stops the moment no pending row remains to avoid background noise.
  useEffect(() => {
    if (routeName !== 'wallet') return;
    const hasPending = (transactions || []).some(t =>
      t && t.type === 'DEPOSIT' && t.status === 'PENDING');
    if (!hasPending) return;
    const id = setInterval(() => {
      if (document.visibilityState === 'visible') loadWallet();
    }, 8000);
    return () => clearInterval(id);
  }, [routeName, transactions, loadWallet]);

  // Keyboard-shortcut help overlay state. `?` opens it, `Esc` closes.
  const [shortcutsOpen, setShortcutsOpen] = useState(false);

  // Keyboard shortcuts — CSFloat uses `/` to focus the market search.
  useEffect(() => {
    // Cmd/Ctrl+K — modern universal "open search" shortcut (Spotlight,
    // Raycast, Linear, GitHub, Slack, Notion etc). Fires even when the
    // focus is inside an input so it always works; the other shortcuts
    // are plain-letter and only fire when the user isn't typing.
    const onMetaKey = (e) => {
      if ((e.metaKey || e.ctrlKey) && !e.altKey && !e.shiftKey && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        const tryFocus = () => {
          const el = document.querySelector('.search-input');
          if (el) { el.focus(); el.select(); return true; }
          return false;
        };
        // Batch 802 — off-route Ctrl+K now bounces to /market first and
        // then focuses the search once the toolbar has rendered. Before
        // this, pressing Ctrl+K from /profile or /wallet was a silent
        // no-op because the search input only lives on the market route.
        if (tryFocus()) return;
        navigate(paths.market());
        // requestAnimationFrame twice to let the router re-render and
        // the toolbar mount before we reach for the input node.
        requestAnimationFrame(() => requestAnimationFrame(tryFocus));
      }
    };
    document.addEventListener('keydown', onMetaKey);
    const onKey = (e) => {
      const tag = (e.target?.tagName || '').toLowerCase();
      // Ignore keys typed inside any input/textarea/select/contenteditable
      if (tag === 'input' || tag === 'textarea' || tag === 'select' || e.target?.isContentEditable) return;
      if (e.key === '/') {
        e.preventDefault();
        const el = document.querySelector('.search-input');
        if (el) { el.focus(); el.select(); }
      } else if (e.key === '?') {
        e.preventDefault();
        setShortcutsOpen(v => !v);
      } else if (e.key === 'g') {
        // Gmail-style two-key prefix — arm a timer and wait for the next key
        const timer = setTimeout(() => { document.removeEventListener('keydown', onTarget); }, 1200);
        const onTarget = (ev) => {
          const t2 = (ev.target?.tagName || '').toLowerCase();
          if (t2 === 'input' || t2 === 'textarea' || t2 === 'select') return;
          clearTimeout(timer);
          document.removeEventListener('keydown', onTarget);
          const map = {
            m: paths.market(), d: paths.database(), p: paths.profile(),
            w: paths.wallet(),  c: paths.cart(),     l: paths.loadouts(),
            s: paths.sell(),    f: paths.watchlist(),h: paths.help(),
            o: paths.offers(),  b: paths.buyorders(), n: paths.notifications(),
            a: paths.admin(),   r: paths.csr(),      t: paths.settings()
          };
          if (map[ev.key]) { ev.preventDefault(); navigate(map[ev.key]); }
        };
        document.addEventListener('keydown', onTarget);
      } else if (e.key === 'Escape') {
        if (shortcutsOpen)        setShortcutsOpen(false);
        // /item/{id} is a real page (per `feedback_pages_not_popups.md`) — pressing
        // Escape used to call `setSelected(null)` which left routeName='item' but
        // wiped the page content, rendering the "Item not found" fallback even
        // though the URL was perfectly valid. Bounce back to /market instead
        // so the URL matches what the user sees.
        else if (routeName === 'item') navigate(paths.market());
        else if (selected)        setSelected(null);
        else if (routeName !== 'market') navigate(paths.market());
      } else if (e.key === 'v' && routeName === 'market') {
        // Grid ↔ Table view toggle. Scoped to /market so pressing `v`
        // on a modal-heavy page (profile / wallet / cart) doesn't
        // silently flip the marketplace view the user can't see.
        e.preventDefault();
        setView(view === 'grid' ? 'table' : 'grid');
      }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('keydown', onMetaKey);
    };
  }, [routeName, selected, shortcutsOpen, view]);

  // logout
  const doLogout = async () => {
    await logoutSteam();
    setMe(null);
    setMenuOpen(false);
    loadWallet();
    // Cart + watchlist are per-user signals: the next user to sign in on
    // this browser shouldn't inherit the previous user's cart contents or
    // starred items. Wipe both state and the localStorage shadows so the
    // nav-bar badges drop to 0 immediately after sign-out.
    setCart([]);
    setWatchlist([]);
    try { localStorage.removeItem('sb_cart'); } catch (_) {}
    try { localStorage.removeItem('sb_watchlist'); } catch (_) {}
    // Any route that only makes sense for a signed-in user would now
    // render the generic sign-in empty state on the current URL. Land
    // the user on the public marketplace instead so the post-logout
    // page actually has content and the URL bar matches what they
    // see. Public routes (market, stall, item, etc.) stay put.
    const privateRoutes = new Set([
      'profile','wallet','cart','mystall','watchlist','notifications',
      'offers','buyorders','sell','support','admin','csr','loadouts'
    ]);
    if (privateRoutes.has(routeName)) navigate(paths.market());
    showToast('Signed out — see you soon.', 'ok');
  };

  // Handle Stripe / Steam redirect
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const state = params.get('deposit');
    const sid   = params.get('session_id');
    const login = params.get('login');
    let dirty = false;
    if (state === 'success' && sid) {
      // Stripe bounces back here after Checkout. Fire the confirm
      // POST (idempotent server-side) then refresh the wallet, and
      // toast the outcome so the user sees their balance updated
      // instead of silently landing back on the home page.
      confirmDeposit(sid)
        .then(r => {
          loadWallet();
          const bal = r && r.newBalance != null ? Number(r.newBalance) : null;
          setToast({
            text: bal != null
              ? `Deposit complete — balance is now ${fmt(bal)}`
              : 'Deposit complete — balance updated',
            kind: 'ok'
          });
          setTimeout(() => setToast(null), 4000);
        })
        .catch(() => {
          setToast({ text: 'Deposit received — balance will refresh shortly', kind: 'ok' });
          setTimeout(() => setToast(null), 4000);
        });
      dirty = true;
    }
    else if (state === 'cancel') {
      // User clicked Cancel on the Stripe Checkout page — give them a
      // soft confirmation so they know nothing was charged and they
      // can retry. Prior behaviour silently scrubbed the URL and
      // dropped them on /, which read as "did my payment go through?"
      setToast({ text: 'Deposit cancelled — no charge made', kind: 'warn' });
      setTimeout(() => setToast(null), 4000);
      dirty = true;
    }
    if (login === 'success') {
      loadMe().then((fresh) => {
        loadWallet();
        // Welcome-back toast. Note: in some test environments the
        // toast frame is dropped on first-paint mount (suspected
        // React rendering quirk specific to the Steam OpenID return
        // hop). Functional flow (URL strip, /me load, wallet load,
        // nav avatar update) all work — the toast text is polish.
        const name = fresh?.displayName || 'Steam user';
        setToast({ text: `Signed in as ${name}`, kind: 'ok' });
        setTimeout(() => setToast(null), 3500);
      });
      dirty = true;
      // Return-after-login: signInWithSteam() stashed the page the
      // user was on before the OpenID hop. Pop it and send them back
      // so "Sign in to buy" lands on the item they cared about,
      // not the homepage. Stash is cleared whether or not we use it.
      try {
        const back = sessionStorage.getItem('sb_login_return_url');
        sessionStorage.removeItem('sb_login_return_url');
        if (back && back !== '/' && !/[?&]login=/.test(back)) {
          // Replace first so Back doesn't cycle through /?login=success.
          window.history.replaceState({}, '', window.location.pathname);
          navigate(back);
          return;
        }
      } catch (_) { /* no sessionStorage — stay on / */ }
    }
    else if (login === 'failed') {
      setToast({ text: 'Steam sign-in failed. Please try again.', kind: 'err' });
      setTimeout(() => setToast(null), 4500);
      dirty = true;
    }
    if (dirty) window.history.replaceState({}, '', window.location.pathname);
  }, [loadWallet, loadMe]);

  const CATEGORIES = ['All', 'Hats', 'Jackets', 'Shirts', 'Pants', 'Gloves', 'Boots', 'Accessories'];
  const RARITIES   = ['All', 'Limited', 'Off-Market', 'Standard'];

  // listings load. `silent` skips the loading spinner for background
  // polling so the grid doesn't blink on each refresh. Polling also
  // compares old vs new counts to fire a subtle "live sale" toast when
  // the feed shortens.
  // Mirror market-filter state into the URL query string so the current
  // view is shareable. Only active on `routeName === 'market'` so we don't
  // write query params onto item detail pages or the watchlist. replaceState
  // keeps the history stack clean — each filter change doesn't become a
  // new entry the user has to Back through.
  // CSFloat-1:1 — when a popstate / navigate event lands us on /market
  // with new query params (e.g. clicking a home rail tab that goes to
  // `/market?sort=discount_desc&discount=10`), re-read the URL into
  // filter state. Without this the URL-sync useEffect below would
  // immediately rewrite the URL with the previous in-memory state,
  // wiping the params the visitor just arrived with.
  useEffect(() => {
    if (routeName !== 'market' && routeName !== 'home') return;
    const params = new URLSearchParams(window.location.search);
    // Browser-search redirect: `/` is the marketing landing per memory
    // feedback_no_skin_rails_on_market.md — no grid, no filters. If a
    // user lands on `/?q=blue` (e.g. via the OpenSearch browser-search
    // descriptor or a stale share link), they expect search RESULTS,
    // not the hero. Forward to /market with the same query string so
    // their intent actually fires. Skip when already on /market to
    // avoid a navigate loop.
    if (routeName === 'home'
        && (params.has('q') || params.has('search') || params.has('query'))) {
      navigate('/market' + (window.location.search || ''));
      return;
    }
    const urlSort = params.get('sort');
    const urlCategory = params.get('category');
    const urlRarity = params.get('rarity');
    // Accept `min`/`max` (canonical) and `minPrice`/`maxPrice` (common typo /
    // alias) - users + external links use both.
    const urlMin = params.get('min') ?? params.get('minPrice');
    const urlMax = params.get('max') ?? params.get('maxPrice');
    const urlQ = params.get('q') ?? params.get('search') ?? params.get('query');
    const urlDiscount = parseInt(params.get('discount') || '0', 10);
    const urlType = params.get('type') ?? params.get('listingType');
    if (urlSort && ALLOWED_SORTS.includes(urlSort) && urlSort !== sort) setSort(urlSort);
    if (urlCategory && ALLOWED_CATEGORIES.includes(urlCategory) && urlCategory !== category) setCategory(urlCategory);
    if (urlRarity && ALLOWED_RARITIES.includes(urlRarity) && urlRarity !== rarity) setRarity(urlRarity);
    if (urlMin != null && urlMin !== minPrice) setMinPrice(urlMin);
    if (urlMax != null && urlMax !== maxPrice) setMaxPrice(urlMax);
    if (urlQ && urlQ !== search) { setSearch(urlQ); setSearchInput(urlQ); }
    if ([0, 5, 10, 20, 30, 50].includes(urlDiscount) && urlDiscount !== minDiscountPct) setMinDiscountPct(urlDiscount);
    if (urlType && urlType !== listingTypeFilter) setListingTypeFilter(urlType);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [routeName, route.path, window.location.search]);

  useEffect(() => {
    // CSFloat-1:1: home is a pure marketing landing — no grid, no
    // filters, no URL-sync. URL-sync only runs on /market so the home
    // URL stays clean (`/` instead of `/?sort=…`).
    if (routeName !== 'market') return;
    const qs = new URLSearchParams();
    if (search)                    qs.set('q', search);
    if (sort && sort !== 'price_desc') qs.set('sort', sort);
    if (category && category !== 'All') qs.set('category', category);
    if (rarity && rarity !== 'All')     qs.set('rarity', rarity);
    if (minPrice)                  qs.set('min', minPrice);
    if (maxPrice)                  qs.set('max', maxPrice);
    // Batch 812 — mirror the remaining quick-filter toggles so a
    // shared link like `/?type=AUCTION&deals=1&discount=20&new=1`
    // reconstructs the exact view. Missing params were a real gap
    // for sellers sharing a curated slice (e.g. "all AUCTIONS ending
    // soon with a 20%+ discount") via Discord — the recipient got a
    // broader pool than the sender intended.
    if (minDiscountPct > 0)        qs.set('discount', String(minDiscountPct));
    if (dealsOnly)                 qs.set('deals', '1');
    if (newOnly)                   qs.set('new', '1');
    if (affordableOnly)            qs.set('aff', '1');
    if (listingTypeFilter && listingTypeFilter !== 'ALL') {
      qs.set('type', listingTypeFilter);
    }
    const q = qs.toString();
    const nextSearch = q ? '?' + q : '';
    if (window.location.search !== nextSearch) {
      window.history.replaceState({}, '', window.location.pathname + nextSearch);
    }
  }, [routeName, search, sort, category, rarity, minPrice, maxPrice,
      minDiscountPct, dealsOnly, newOnly, affordableOnly, listingTypeFilter]);

  // 2026-05-20 — monotonic load generation. Bumped on every fresh
  // (non-silent) listings fetch so an in-flight loadMore() can detect
  // that the filter set changed underneath it and abandon its append.
  // Without this, clicking "Load more" and then immediately changing a
  // filter let the page-2 response of the OLD filter set get appended
  // onto the freshly-loaded NEW filter set, corrupting the grid.
  const loadSeqRef = useRef(0);
  const load = useCallback(async (silent = false) => {
    // 2026-05-20 — only a fresh (non-silent) load bumps the generation:
    // that's a filter change / explicit refresh, the only event that can
    // invalidate an in-flight loadMore() append or a stale silent poll.
    // Routine same-filter polls capture the seq but don't advance it, so
    // a poll firing mid-loadMore doesn't needlessly cancel the append.
    if (!silent) { setLoading(true); loadSeqRef.current++; }
    const seqAtStart = loadSeqRef.current;
    try {
      // 'discount' is sorted server-side — ListingController whitelists
      // it. Sending it straight to the backend means page 2 from
      // loadMore() is ordered against the global set, not re-sorted per
      // page (which sank page 2's best discount below page 1's worst).
      // It also makes a shared `?sort=discount` deep link match the
      // request that's actually issued.
      let data = await fetchListings({
        sort,
        category: category !== 'All' ? category : null,
        rarity:   rarity !== 'All'   ? rarity   : null,
        // Server-side listing-type filter so a "BUY_NOW only" or
        // "AUCTION only" view doesn't ship the other half of the set.
        // "ALL" falls through to no server filter; client-side guard
        // still applies after the response lands (belt-and-braces).
        listingType: listingTypeFilter && listingTypeFilter !== 'ALL' ? listingTypeFilter : null,
        minPrice: minPrice || null,
        maxPrice: maxPrice || null,
        search:   search   || null
      });
      // A newer load (filter change / explicit refresh) superseded this
      // one while the request was in flight — drop the stale response so
      // the grid keeps the newer filter set's data.
      if (seqAtStart !== loadSeqRef.current) return;
      // Conservative hasMore — a full 100-item page means there MAY be
      // a second page. Only the "Load more" click can confirm by trying
      // to fetch offset=100 and checking the response.
      setHasMore(data.length >= 100);
      if (silent) {
        setListings(prev => {
          const prevIds = new Set(prev.map(l => l.id));
          const nextIds = new Set(data.map(l => l.id));
          const soldCount = [...prevIds].filter(id => !nextIds.has(id)).length;
          // Gated by the Settings > "Sale notifications" toggle. Default
          // is ON (sb_notifs absent or not 'false'); a user who muted
          // the toggle sees the grid update silently. Without this
          // check the toggle was a dead switch.
          const saleToastsOn = localStorage.getItem('sb_notifs') !== 'false';
          if (soldCount > 0 && saleToastsOn) {
            setToast({ text: `${soldCount} listing${soldCount === 1 ? '' : 's'} just sold`, kind: 'ok' });
            setTimeout(() => setToast(null), 3500);
          }
          return data;
        });
      } else {
        setListings(data);
      }
      // Successful fetch — clear any previously-captured error so the
      // empty-state renders the regular "no listings" copy instead of
      // a stale Retry CTA.
      setLoadError(null);
    } catch (e) {
      console.error(e);
      setLoadError(e?.message || 'Failed to load listings');
    }
    finally { if (!silent) setLoading(false); }
  }, [sort, category, rarity, minPrice, maxPrice, search, listingTypeFilter]);

  // Fetch the next page and append. Uses the same filter state as the
  // initial load; the only difference is `offset = current listings count`
  // so pages align. Server caps at limit=100 so repeatedly clicking
  // Load More walks 100-row chunks. No optimistic "no more" on a short
  // tail: when the returned batch is shorter than 100, we flip hasMore
  // off so the button hides itself.
  const loadMore = useCallback(async () => {
    if (loadingMore || !hasMore) return;
    setLoadingMore(true);
    // 2026-05-20 — snapshot the load generation so a filter change that
    // fires a fresh load() while this page-2 fetch is in flight makes us
    // discard the (now-mismatched) response instead of appending it.
    const seqAtStart = loadSeqRef.current;
    try {
      const next = await fetchListings({
        sort,
        category: category !== 'All' ? category : null,
        rarity:   rarity !== 'All'   ? rarity   : null,
        listingType: listingTypeFilter && listingTypeFilter !== 'ALL' ? listingTypeFilter : null,
        minPrice: minPrice || null,
        maxPrice: maxPrice || null,
        search:   search   || null,
        limit:    100,
        offset:   listings.length
      });
      // Filters changed mid-flight — load() already replaced `listings`
      // with the new set. Appending this stale page would corrupt it.
      if (seqAtStart !== loadSeqRef.current) return;
      if (!Array.isArray(next) || next.length === 0) {
        setHasMore(false);
        return;
      }
      setListings(prev => {
        const have = new Set(prev.map(l => l.id));
        const merged = [...prev];
        next.forEach(l => { if (l && !have.has(l.id)) merged.push(l); });
        return merged;
      });
      setHasMore(next.length >= 100);
    } catch (e) { console.error(e); }
    finally { setLoadingMore(false); }
  }, [loadingMore, hasMore, sort, category, rarity, minPrice, maxPrice,
      search, listingTypeFilter, listings.length]);
  useEffect(() => { load(); }, [load]);

  // Soft poll the marketplace grid every 30s while the user is on a
  // browse route, so sold items disappear and price drops appear without
  // a manual refresh. Pauses on feature pages to save bandwidth.
  useEffect(() => {
    if (routeName !== 'market' && routeName !== 'item') return;
    const id = setInterval(() => {
      if (document.visibilityState === 'visible') load(true);
    }, 30_000);
    return () => clearInterval(id);
  }, [routeName, load]);

  // open item detail. We push `/item/{id}` onto the URL so the detail view
  // is shareable and back/forward navigation works. The actual fetch happens
  // in the effect below that reacts to `route.name === 'item'`.
  const openModal = (listing) => {
    navigate(paths.item(listing.item.id));
  };

  // When the URL is /item/{id}, fetch that item's listings + history and
  // surface the ItemModal. Closing the modal navigates back to the market.
  useEffect(() => {
    if (routeName !== 'item' || !route.params?.id) return;
    let alive = true;
    setModalLoading(true);
    (async () => {
      try {
        const [itemListings, history] = await Promise.all([
          fetchListingsForItem(route.params.id),
          fetchHistory(route.params.id)
        ]);
        if (!alive) return;
        // Resolve the actual item object. Three-layer fallback:
        //   1. first listing we just fetched (most common — item has
        //      active listings),
        //   2. the already-loaded marketplace listings array (cache),
        //   3. a direct /api/items/{id} probe so an item with zero
        //      active listings still opens the modal (was a blank
        //      screen before — /item/{id} for an unlisted-but-real
        //      item rendered nothing).
        let item = itemListings[0]?.item ||
                   listings.find(l => String(l.item?.id) === String(route.params.id))?.item;
        if (!item) {
          try { item = await fetchItem(route.params.id); }
          catch (_) { item = null; }
          if (!alive) return;
        }
        if (item) {
          setSelected({ item, listings: itemListings, history });
          // Refine the route-driven title with the real item name — e.g.
          // "Black Modern Watch · SkinBox". Preserves the unread prefix.
          const currentPrefix = (document.title.match(/^(\([^)]+\)\s+)/) || [, ''])[1];
          document.title = currentPrefix + (item.name || 'Item') + ' · SkinBox';
          // Track recently viewed for the homepage rail — keep the last 12,
          // newest first, deduped by item id. Pure localStorage, no backend.
          try {
            const prev = JSON.parse(localStorage.getItem('sb_recently_viewed') || '[]');
            const minimal = { id: item.id, name: item.name, category: item.category,
              rarity: item.rarity, imageUrl: item.imageUrl, iconEmoji: item.iconEmoji,
              accentColor: item.accentColor, lowestPrice: item.lowestPrice,
              steamPrice: item.steamPrice, viewedAt: Date.now() };
            const deduped = [minimal, ...prev.filter(x => x.id !== item.id)].slice(0, 12);
            localStorage.setItem('sb_recently_viewed', JSON.stringify(deduped));
          } catch (_) {}
        } else {
          // Item not found — clear any previously-selected item so the
          // render guard (`!selected`) stops rendering the prior item's
          // ItemModal under this not-found URL. Without this, navigating
          // to a dead /item/:id left the last item's modal on screen.
          setSelected(null);
          // Set a meaningful page title so the browser tab + history
          // reflect the not-found state instead of leaving the generic
          // "Item · SkinBox" placeholder. Crawlers indexing a removed
          // item URL get the right signal in the title too.
          // Pre-fix bug: the recently-viewed writeback was misnested in
          // this branch and dereferenced `item.id`/`.name` on a null
          // item, throwing TypeError on every dead /item/:id link.
          const currentPrefix = (document.title.match(/^(\([^)]+\)\s+)/) || [, ''])[1];
          document.title = currentPrefix + 'Item not found · SkinBox';
        }
      } catch (e) {
        console.error(e);
        // Fetch failed — same hazard as the not-found branch: drop the
        // stale selection so a previous item's modal doesn't linger
        // under a URL whose load just errored.
        if (alive) setSelected(null);
      }
      finally { if (alive) setModalLoading(false); }
    })();
    return () => { alive = false; };
  }, [routeName, route.params?.id]);
  // If the URL leaves /item/:id, clear the selection so the modal disappears.
  useEffect(() => { if (routeName !== 'item') setSelected(null); }, [routeName]);

  // toast
  const [toast, setToast] = useState(null);
  const showToast = (text, kind = 'ok') => {
    setToast({ text, kind });
    setTimeout(() => setToast(null), 4500);
  };

  // V61 ship #47 — global `sb:toast` event bus. Any nested component
  // that doesn't have direct access to `setToast` can dispatch
  // `window.dispatchEvent(new CustomEvent('sb:toast', { detail: { text, kind } }))`
  // and the App-level toast slot picks it up. Used by StallReviewRow's
  // helpful-vote handler so a server-rejected vote surfaces a real
  // error toast instead of silently snapping back. Same pattern can
  // extend to any future cross-component handler that needs feedback.
  useEffect(() => {
    const onToast = (e) => {
      const text = e?.detail?.text;
      const kind = e?.detail?.kind || 'ok';
      if (typeof text === 'string' && text.length > 0) showToast(text, kind);
    };
    window.addEventListener('sb:toast', onToast);
    return () => window.removeEventListener('sb:toast', onToast);
  }, []);

  // Session-expired broadcast from the api.js write wrapper. When any
  // write op hits a 401 it dispatches `sb:session-expired`; the App flips
  // `me` back to null so the nav avatar returns to the "Sign in with
  // Steam" affordance. The failing operation still returns its error
  // object, so the caller's own toast fires with the friendly message.
  // Debounced to once per 10s so a burst of stale-cookie requests doesn't
  // bounce the UI back and forth.
  const sessionExpiredRef = useRef(0);
  useEffect(() => {
    const handler = () => {
      const now = Date.now();
      if (now - sessionExpiredRef.current < 10_000) return;
      sessionExpiredRef.current = now;
      // "Session expired" reads correctly only when the viewer was
      // previously signed in. For an anon user who triggered a 401 on
      // a write op (e.g. clicked Deposit / Make Offer / List Item), the
      // message "Your session expired" misframes the state — they
      // never had a session. Tailor the copy by checking whether
      // `me` was set at the moment the 401 fired.
      const wasSignedIn = !!me;
      setMe(null);
      setIsAdmin(false);
      setIsCsrRole(false);
      showToast(wasSignedIn
        ? 'Your session expired — sign in again to continue.'
        : 'Sign in with Steam to continue.', 'err');
    };
    window.addEventListener('sb:session-expired', handler);
    return () => window.removeEventListener('sb:session-expired', handler);
  }, [me]);

  // Batch 711 — service-unavailable banner. On any 503, the api.js
  // wrappers dispatch `sb:service-unavailable`; we flip a banner
  // state on. Any subsequent 2xx clears it automatically (via
  // `sb:service-restored`) so the UI self-heals when the pod
  // recovers, no refresh needed. Distinct from the ErrorBoundary
  // page — this is a recoverable network-level signal, not a
  // render-time crash.
  const [serviceUnavailable, setServiceUnavailable] = useState(false);
  useEffect(() => {
    const down = () => setServiceUnavailable(true);
    const up   = () => setServiceUnavailable(false);
    window.addEventListener('sb:service-unavailable', down);
    window.addEventListener('sb:service-restored', up);
    return () => {
      window.removeEventListener('sb:service-unavailable', down);
      window.removeEventListener('sb:service-restored', up);
    };
  }, []);

  // buy flow — errors now come as {code, message} from GlobalExceptionHandler.
  // expectedPrice (optional) pins what the user saw in the modal / card so
  // the server can 400 PRICE_CHANGED if a seller raised the price mid-click
  // (batch 323). Callers pass the listing's .price; legacy callers still work.
  const handleBuy = async (listingId, expectedPrice) => {
    try {
      const res = await buyListing(listingId, expectedPrice);
      if (res && (res.code || res.error)) {
        let msg = res.message || res.error;
        // Batch 963 — compose a precise "Top up $X" toast when the
        // server returns structured shortfall details. Falls through
        // to the message-string path if details aren't present (older
        // server, non-purchase error).
        if (res.code === 'INSUFFICIENT_BALANCE' && res.details?.shortfall) {
          const short = parseFloat(res.details.shortfall);
          if (Number.isFinite(short) && short > 0) {
            msg = `Top up ${fmt(short)} to complete this purchase.`;
          }
        }
        showToast(msg, 'err');
        if (res.code === 'INSUFFICIENT_BALANCE' || /insufficient/i.test(msg || '')) {
          setSelected(null);
          setWalletInitialTab('deposit');
          // Route-driven wallet surface — old code toggled a dead
          // `walletOpen` boolean that nothing read anymore (/wallet
          // became a real route). Navigate so back/forward + URL share
          // still work on the deposit nudge.
          navigate(paths.wallet());
        }
        return;
      }
      setSelected(null);
      // The purchase creates an escrow trade; the item only lands in
      // inventory after the seller sends + buyer confirms. Old toast
      // said "added to your inventory" which was misleading during the
      // pending window. Nudge them toward the trades tab so they can
      // watch the state machine instead of hunting for the item.
      //
      // Batch 880 — include the item name + price from the response so
      // the user sees what they bought at a glance, not just a generic
      // "purchase complete". Falls back to the generic copy when the
      // response shape is missing the fields (backwards-compat).
      const boughtName = res?.itemName;
      const boughtPrice = res?.price != null ? parseFloat(res.price) : null;
      const toastCopy = boughtName
        ? `Bought "${boughtName}"${boughtPrice != null && !isNaN(boughtPrice) ? ` for ${fmt(boughtPrice)}` : ''} — trade opened, see Profile › Trades`
        : 'Purchase complete — trade opened, see Profile › Trades';
      showToast(toastCopy, 'ok');
      await loadWallet();
      load();
    } catch (e) {
      showToast('Purchase failed: ' + (e.message || 'unknown'), 'err');
    }
  };

  const handleMakeOffer = async (listingId, amount, message) => {
    if (!listingId || !amount) return { error: 'Missing data' };
    const res = await makeOffer(listingId, amount, message);
    if (!res.code && !res.error) {
      // Batch 881 — include amount + item name (when available from the
      // response DTO) so the offer-sent toast confirms exactly what was
      // offered. Matches the buy-success personalisation (batch 880).
      const n = parseFloat(amount);
      const itemName = res.itemName;
      const copy = itemName
        ? `Offered ${fmt(n)} on "${itemName}" — the seller has 7 days to respond.`
        : `Offered ${fmt(n)} — the seller has 7 days to respond.`;
      showToast(copy, 'ok');
    }
    return res;
  };

  const clearFilters = () => {
    setCategory('All'); setRarity('All');
    setMinPrice(''); setMaxPrice(''); setSearch(''); setSearchInput('');
    // Batch 651 — include the new chip in the full-reset action.
    setMinDiscountPct(0);
    // Batch 812 — also drop the quick-filter toggles + type chip so
    // "Clear Filters" truly resets the grid to the default view
    // regardless of which chips the user had checked.
    setDealsOnly(false);
    setNewOnly(false);
    setAffordableOnly(false);
    setListingTypeFilter('ALL');
  };

  // derived data
  const catCounts = useMemo(() => {
    const counts = {};
    listings.forEach(l => { if (l?.item?.category) counts[l.item.category] = (counts[l.item.category] || 0) + 1; });
    return counts;
  }, [listings]);
  const rarityCounts = useMemo(() => {
    const counts = {};
    listings.forEach(l => { if (l?.item?.rarity) counts[l.item.rarity] = (counts[l.item.rarity] || 0) + 1; });
    return counts;
  }, [listings]);

  // Representative hero item per category — the highest-priced listing
  // with an image wins so the tile never degrades to a blank poster.
  // The "All" tile deliberately gets NO hero: it stays as the editorial
  // grid-of-squares glyph so it reads as "the everything tile" rather
  // than duplicating whichever category owns the top hero.
  const catHeroes = useMemo(() => {
    const picked = {};
    const scored = [...listings]
      .filter(l => l?.item?.imageUrl)
      .sort((a, b) => parseFloat(b.price || 0) - parseFloat(a.price || 0));
    scored.forEach(l => {
      const cat = l.item.category;
      if (cat && !picked[cat]) picked[cat] = l.item;
    });
    return picked;
  }, [listings]);

  const trending = useMemo(() => {
    const seen = new Set();
    return [...listings]
      .filter(l => l && l.item)
      .sort((a, b) => Math.abs(b.item.trendPercent || 0) - Math.abs(a.item.trendPercent || 0))
      .filter(l => { if (seen.has(l.item.id)) return false; seen.add(l.item.id); return true; })
      .slice(0, 8);
  }, [listings]);

  // CSFloat-style hero tabs: Top Deals (biggest vs-store discount), Newest
  // (most recently listed), Unique Items (auctions / no-bid listings).
  // We intentionally do NOT show "top gainers/losers" — this is a marketplace,
  // not a stock exchange. Trend data stays as a small ▲/▼ inside cards only.
  const heroTabs = useMemo(() => {
    const uniqByItem = {};
    listings.filter(l => l?.item).forEach(l => {
      if (!uniqByItem[l.item.id] || l.price < uniqByItem[l.item.id].price) uniqByItem[l.item.id] = l;
    });
    const pool = Object.values(uniqByItem);
    const topDeals = [...pool]
      .filter(l => l.item.steamPrice && parseFloat(l.item.steamPrice) > parseFloat(l.price))
      .sort((a, b) => {
        const da = 1 - parseFloat(a.price) / parseFloat(a.item.steamPrice);
        const db = 1 - parseFloat(b.price) / parseFloat(b.item.steamPrice);
        return db - da;
      })
      .slice(0, 8);
    const newest = [...pool].sort((a, b) => (b.listedAt || 0) - (a.listedAt || 0)).slice(0, 8);
    const unique = [...pool].filter(l => l.listingType === 'AUCTION').slice(0, 8);
    return { topDeals, newest, unique };
  }, [listings]);

  // Live-sales ticker — real SOLD rows from /api/listings/recent-sales.
  // Previously this used ACTIVE listings with synthetic "Xm ago" labels,
  // which was misleading (the ticker claimed "LIVE SALES" but showed
  // items that hadn't sold). Now every row is a real settlement with
  // its actual soldAt timestamp. Fetched once on mount + on every
  // marketplace refresh so a user who just completed a purchase sees
  // their row appear in the ticker.
  const [recentSales, setRecentSales] = useState([]);
  useEffect(() => {
    let alive = true;
    fetchPlatformRecentSales(12).then(rows => {
      if (!alive || !Array.isArray(rows)) return;
      setRecentSales(rows.map(r => ({
        listing: {
          id:    r.listingId,
          price: r.price,
          item:  {
            id:         r.itemId,
            name:       r.itemName,
            category:   r.category,
            rarity:     r.rarity,
            imageUrl:   r.imageUrl,
            steamPrice: r.steamPrice
          }
        },
        time: r.soldAt ? timeAgo(r.soldAt) : 'just now'
      })));
    }).catch(() => {});
    return () => { alive = false; };
  }, [listings.length]);

  // One-card-per-item view of the marketplace. We show the cheapest listing
  // per item with the total listing count as a "3 listings from $X" badge.
  // Matches CSFloat's grid layout and fixes the watchlist "starring one
  // card highlights every card of the same item" confusion.
  // Bulk watcher counts for the visible marketplace grid — keyed by
  // item id. One round-trip after the listings settle, no per-card
  // fan-out. Items not in the map render no chip (treated as zero-watch).
  const [watcherCounts, setWatcherCounts] = useState({});
  // Bulk Steam avatar URLs per sellerUserId — drives real profile
  // photos on the listing-table seller column instead of the legacy
  // all-caps 2-letter monogram. Sellers without an avatarUrl on file
  // are absent, and ListingRow falls through to the monogram.
  const [sellerAvatarUrls, setSellerAvatarUrls] = useState({});
  const dedupedListings = useMemo(() => {
    // Apply the listing-type filter before dedup so "Auction only" doesn't
    // pick the buy-now as the representative card for an item that has both.
    let pool = listingTypeFilter === 'ALL'
      ? listings
      : listings.filter(l => listingTypeFilter === 'AUCTION'
          ? l?.listingType === 'AUCTION'
          : l?.listingType !== 'AUCTION');
    // Effective price for filtering / dedup. An AUCTION listing's `price`
    // is the *starting bid*, not the cost to acquire it — once bidding
    // has started the real comparable is `currentBid`. Keying the Deals /
    // discount / Affordable filters and the dedup representative pick off
    // raw `price` let a $0.01-opener auction masquerade as the cheapest,
    // pollute every deal filter, and win the card slot over a genuine
    // Buy-Now listing. Matches the backend discountRatio + GridCard.
    const effPrice = (l) => {
      if (l?.listingType === 'AUCTION' && l?.currentBid != null) {
        const cb = parseFloat(l.currentBid);
        if (isFinite(cb) && cb > 0) return cb;
      }
      return parseFloat(l?.price);
    };
    if (dealsOnly) {
      pool = pool.filter(l => {
        const sp = parseFloat(l?.item?.steamPrice);
        const p  = effPrice(l);
        return isFinite(sp) && isFinite(p) && sp > 0 && p < sp;
      });
    }
    // Batch 662 — hide-my-listings filter. Dropped from the pool so
    // the dedup step + count strip reflect what the seller actually
    // wants to look at. Silent when the viewer is anonymous or has
    // no listings in the pool.
    if (hideMine && me?.id) {
      pool = pool.filter(l => l?.sellerUserId !== me.id);
    }
    // Batch 651 — min-discount threshold filter. Only applies when
    // minDiscountPct > 0 (chip inactive at 0). Items without a Steam
    // reference price are excluded from the pool so the filter
    // strictly narrows the set and never leaks "unknown discount"
    // rows through a positive threshold.
    if (minDiscountPct > 0) {
      const floor = minDiscountPct / 100;
      pool = pool.filter(l => {
        const sp = parseFloat(l?.item?.steamPrice);
        const p  = effPrice(l);
        if (!(isFinite(sp) && sp > 0 && isFinite(p) && p > 0)) return false;
        return (1 - p / sp) >= floor;
      });
    }
    if (newOnly) {
      const cutoff = Date.now() - 24 * 3600 * 1000;
      pool = pool.filter(l => (l?.listedAt || 0) >= cutoff);
    }
    // Affordable-only (batch 367). Rides on the wallet balance fetched
    // by the usual loadWallet() effect. Anon viewers never reach this
    // branch because the chip is hidden for them.
    if (affordableOnly && wallet && parseFloat(wallet.balance) > 0) {
      const bal = parseFloat(wallet.balance);
      pool = pool.filter(l => {
        const p = effPrice(l);
        return isFinite(p) && p <= bal;
      });
    }
    // Map (not plain object) so insertion order is preserved regardless of
    // whether item.id stringifies to an integer — `Object.values()` sorts
    // integer-string keys in numeric order, which silently broke price/recent
    // sort by re-ordering deduped rows by item.id instead of by API order.
    const byItem = new Map();
    pool.filter(l => l?.item).forEach(l => {
      const current = byItem.get(l.item.id);
      if (!current || effPrice(l) < effPrice(current.listing)) {
        byItem.set(l.item.id, { listing: l, count: 1 });
      }
    });
    const counts = new Map();
    pool.forEach(l => { if (l?.item) counts.set(l.item.id, (counts.get(l.item.id) || 0) + 1); });
    return Array.from(byItem.values())
      .map(e => ({ ...e.listing, __listingCount: counts.get(e.listing.item.id) || 1 }));
  }, [listings, listingTypeFilter, dealsOnly, minDiscountPct, newOnly, affordableOnly, hideMine, me?.id, wallet?.balance]);

  // Bulk-fetch watcher counts for the visible item ids whenever the
  // marketplace grid recomputes. One round-trip, debounced by the dedup
  // memo so swapping filters doesn't fan out per change. Anonymous
  // viewers see the counts too — the endpoint is public.
  useEffect(() => {
    if (!dedupedListings || dedupedListings.length === 0) {
      setWatcherCounts({});
      return;
    }
    let alive = true;
    const ids = dedupedListings.map(l => l?.item?.id).filter(Boolean);
    if (ids.length === 0) return;
    const idStr = ids.join(',');
    (async () => {
      try {
        const r = await fetch(`/api/watchlist/counts?ids=${idStr}`, { credentials: 'same-origin' });
        if (!alive || !r.ok) return;
        const data = await r.json();
        if (data && typeof data === 'object') setWatcherCounts(data);
      } catch (_) { /* offline — leave counts empty so cards just render no chip */ }
    })();
    // Steam-avatar bulk lookup keyed on the visible sellers. Replaces
    // the monogram on the table view's Seller column with a real profile
    // photo when available. Sellers without avatarUrl are absent from
    // the response; ListingRow falls through to the monogram.
    (async () => {
      try {
        const sellerIds = [...new Set(dedupedListings.map(l => l?.sellerUserId).filter(Boolean))];
        if (sellerIds.length === 0) return;
        const { fetchSellerAvatars } = await import('./api.js');
        const map = await fetchSellerAvatars(sellerIds);
        if (!alive) return;
        setSellerAvatarUrls(map || {});
      } catch (_) { /* offline — monogram remains */ }
    })();
    return () => { alive = false; };
  }, [dedupedListings.map(l => l?.item?.id).join(',')]);

  // Full-page routes vs overlay routes. CSFloat-style: most destinations
  // are real pages that replace the marketplace body; only the item detail
  // stays as a slide-in overlay on top of the grid.
  const FULL_PAGE_ROUTES = ['profile','wallet','cart','help','faq','watchlist','database','loadouts','loadout','sell','mystall','offers','buyorders','notifications','support','settings','affiliate','admin','csr','notfound','stall','item'];
  const isFullPage = FULL_PAGE_ROUTES.includes(routeName);

  return h('div', {
    className: `site-root ${isFullPage ? 'full-page-mode' : ''}`
  },
    /* Chat removed — was placeholder with fake messages */

    /* Sitewide ops announcement — one row at a time, dismissible. */
    h(AnnouncementBanner, null),
    /* Batch 711 — service-unavailable banner. Fires when any /api/*
       read hits 503 (DB pool unreachable, maintenance mode, etc).
       Auto-clears when the next request returns 2xx, so the user
       doesn't have to refresh. Distinct from ErrorBoundary (which
       handles render-time JS crashes) — this is a recoverable
       network-level condition. */
    serviceUnavailable && h('div', {
      role: 'alert',
      style: {
        background: 'linear-gradient(90deg, rgba(250,204,21,0.15), rgba(250,204,21,0.25))',
        borderBottom: '1px solid rgba(250,204,21,0.45)',
        color: '#fcd34d', padding: '10px 18px',
        fontSize: 13, fontWeight: 600, lineHeight: 1.5,
        display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap'
      }
    },
      h('span', { style: { fontSize: 16 } }, '⚠'),
      h('span', { style: { flex: 1 } },
        h('strong', null, 'SkinBox is temporarily unavailable. '),
        'The service is reporting a degraded state. Your wallet, listings, and trades are safe — refreshed data will appear once service is restored.'),
      h('a', {
        href: '/status.html',
        style: { color: '#fcd34d', background: 'rgba(255,255,255,0.08)',
                 border: '1px solid rgba(250,204,21,0.5)',
                 padding: '5px 12px', borderRadius: 4, fontSize: 11, fontWeight: 800,
                 textDecoration: 'none', whiteSpace: 'nowrap' }
      }, 'View status')),
    /* Batch 663 — persistent "account suspended" banner for signed-in
       users whose `banned` flag is true. Can't be dismissed — a banned
       user navigating the site should never forget why Sell / Bid /
       Withdraw 403s. Sits above every other banner so it's the first
       thing the user sees on load. Anon viewers + non-banned users
       render nothing. */
    me && me.banned && h('div', {
      role: 'alert',
      style: {
        background: 'linear-gradient(90deg, rgba(248,113,113,0.18), rgba(220,38,38,0.25))',
        borderBottom: '1px solid rgba(248,113,113,0.45)',
        color: '#fca5a5', padding: '10px 18px',
        fontSize: 13, fontWeight: 600, lineHeight: 1.5,
        display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap'
      }
    },
      h('span', { style: { fontSize: 16 } }, '⛔'),
      h('span', { style: { flex: 1 } },
        h('strong', null, 'Your account is suspended — read-only access.'),
        ' Listings, bids, offers, trades, deposits, and withdrawals are all disabled. ',
        me.banReason
          ? h('span', null, 'Reason: ', h('em', null, me.banReason), '. ')
          : null,
        'Contact support if you want to appeal.'
      ),
      h('a', {
        href: paths.support(),
        style: {
          color: '#fca5a5', background: 'rgba(255,255,255,0.08)',
          border: '1px solid rgba(248,113,113,0.55)',
          padding: '5px 12px', borderRadius: 4, fontSize: 11, fontWeight: 800,
          textDecoration: 'none', whiteSpace: 'nowrap'
        },
        title: 'Open a support ticket to appeal'
      }, 'Appeal →')
    ),
    h(EmailVerifyNag, { me }),

    /* Pending-trade reminder — nudges users whose escrow has been
       waiting on them > 2h. Dismissible per-trade via localStorage. */
    h(PendingTradeReminder, { me }),

    /* NAV — full-width bar, aligned inner row clamped to content-max */
    h('nav', { className: 'nav', 'aria-label': 'Top' },
      h('div', { className: 'nav-inner' },
      h('a', { className: 'nav-logo', href: '/' },
        // Batch 1068 — isometric-crate SVG logo per the operator's template
        // (~/Downloads/skinbox_files/chrome.jsx), recolored with a blue
        // fade. Top facet is the lightest blue, right facet mid, left
        // facet deepest — the crate reads as a single object lit from
        // above-left. Blue accent lines up with the --cta brand blue.
        h('div', { className: 'nav-logo-icon', 'aria-hidden': 'true' },
          h('svg', {
            viewBox: '0 0 48 48',
            xmlns: 'http://www.w3.org/2000/svg',
            width: '100%',
            height: '100%',
            fill: 'none'
          },
            h('defs', null,
              h('linearGradient', { id: 'sbm-top', x1: '24', y1: '2', x2: '24', y2: '26', gradientUnits: 'userSpaceOnUse' },
                h('stop', { offset: '0%',   stopColor: '#c0e9ff' }),
                h('stop', { offset: '100%', stopColor: '#4dc8ff' })
              ),
              h('linearGradient', { id: 'sbm-left', x1: '4', y1: '24', x2: '24', y2: '46', gradientUnits: 'userSpaceOnUse' },
                h('stop', { offset: '0%',   stopColor: '#0a7cc9' }),
                h('stop', { offset: '100%', stopColor: '#04121c' })
              ),
              h('linearGradient', { id: 'sbm-right', x1: '46', y1: '24', x2: '24', y2: '46', gradientUnits: 'userSpaceOnUse' },
                h('stop', { offset: '0%',   stopColor: '#1ea5ff' }),
                h('stop', { offset: '100%', stopColor: '#0d4d78' })
              )
            ),
            h('path', { d: 'M24 3 L44 14 L24 25 L4 14 Z',   fill: 'url(#sbm-top)',   stroke: 'rgba(120,210,255,0.5)', strokeWidth: '0.8', strokeLinejoin: 'round' }),
            h('path', { d: 'M4 14 L24 25 L24 45 L4 34 Z',   fill: 'url(#sbm-left)',  stroke: 'rgba(77,200,255,0.12)', strokeWidth: '0.8', strokeLinejoin: 'round' }),
            h('path', { d: 'M44 14 L24 25 L24 45 L44 34 Z', fill: 'url(#sbm-right)', stroke: 'rgba(77,200,255,0.18)', strokeWidth: '0.8', strokeLinejoin: 'round' }),
            h('path', { d: 'M4 14 L24 3 L44 14',            fill: 'none',           stroke: 'rgba(192,233,255,0.75)', strokeWidth: '0.8', strokeLinejoin: 'round' }),
            h('path', { d: 'M24 25 L24 45',                 stroke: 'rgba(4,18,28,0.55)', strokeWidth: '0.8' })
          )
        ),
        h('span', { className: 'nav-logo-text' }, 'SkinBox'),
        /* CSFloat-1:1 — small green "live" dot next to the brand mark in
           nav, indicating the marketplace is up and serving. Pure
           cosmetic; pulses subtly via CSS keyframes. */
        h('span', { className: 'nav-logo-live', 'aria-hidden': 'true', title: 'Marketplace is live' })
      ),
      // Batch 777 — `role="navigation"` lets screen readers treat this
      // as a proper navigation landmark so a user can jump straight to
      // it with their SR's landmark navigation shortcut. `aria-current`
      // on the active link tells the SR which page the user is on
      // without them having to read every label.
      h('nav', { className: 'nav-links', 'aria-label': 'Primary' },
        h('a', {
          className: `nav-link ${routeName === 'market' ? 'active' : ''}`,
          href: paths.market(),
          'aria-current': routeName === 'market' ? 'page' : undefined
        }, 'Market'),
        h('a', {
          className: `nav-link ${routeName === 'database' ? 'active' : ''}`,
          href: paths.database(),
          'aria-current': routeName === 'database' ? 'page' : undefined
        }, 'Database'),
        h('a', {
          className: `nav-link ${routeName === 'loadouts' || routeName === 'loadout' ? 'active' : ''}`,
          href: paths.loadouts(),
          'aria-current': (routeName === 'loadouts' || routeName === 'loadout') ? 'page' : undefined
        }, 'Loadout Lab'),
        h('a', {
          className: `nav-link ${routeName === 'watchlist' ? 'active' : ''}`,
          href: paths.watchlist(),
          'aria-current': routeName === 'watchlist' ? 'page' : undefined
        },
          'Watchlist',
          watchlist.length > 0 && h('span', { className: 'nav-link-badge' }, watchlist.length)
        ),
        h('a', {
          className: `nav-link ${routeName === 'help' || routeName === 'faq' ? 'active' : ''}`,
          href: paths.help(),
          'aria-current': (routeName === 'help' || routeName === 'faq') ? 'page' : undefined
        }, 'Help'),
      ),
      h('div', { className: 'nav-right' },
        /* 2026-05-03 — every option here is now FULLY WIRED. The "soon"
           gate was stale (utils.js's FX_RATES/FX_SYMBOL has covered EUR/
           GBP/CAD/AUD/BRL/JPY for weeks; fmt() multiplies + symbol-
           prefixes correctly). Operator was on CAD via manually-set
           localStorage AND the dropdown said "SOON" next to CAD —
           contradictory UX. Bumping all five currencies to active so
           the picker actually does what it promises. Selection writes
           localStorage + window.SBOX_CURRENCY; the storage event in
           the bumpCurrency effect re-renders every fmt() call site. */
        h(NavPicker, {
          // Picker label sources THREE places in this priority order so
          // it stays in sync with whatever fmt() reads from. Was: only
          // window.SBOX_CURRENCY which is only set on click — after a
          // page navigation the click-side write is lost and the chip
          // showed "USD" while every price on the page was already CA$
          // because fmt() reads localStorage.
          label: (() => {
            try {
              if (typeof window !== 'undefined' && window.SBOX_CURRENCY) return window.SBOX_CURRENCY;
              const ls = (typeof localStorage !== 'undefined') ? localStorage.getItem('sb_currency') : null;
              if (ls) return ls;
            } catch (_) {}
            return 'USD';
          })(),
          ariaLabel: 'Currency selector',
          options: [
            { code: 'USD', flag: '$',  name: 'US Dollar',         active: true },
            { code: 'EUR', flag: '€',  name: 'Euro',              active: true },
            { code: 'GBP', flag: '£',  name: 'British Pound',     active: true },
            { code: 'CAD', flag: 'C$', name: 'Canadian Dollar',   active: true },
            { code: 'AUD', flag: 'A$', name: 'Australian Dollar', active: true },
            { code: 'BRL', flag: 'R$', name: 'Brazilian Real',    active: true },
            { code: 'JPY', flag: '¥',  name: 'Japanese Yen',      active: true }
          ],
          onSelect: (code) => {
            try { localStorage.setItem('sb_currency', code); } catch (_) {}
            if (typeof window !== 'undefined') window.SBOX_CURRENCY = code;
            // Storage event only fires on OTHER tabs; same-tab listeners
            // need an explicit dispatch so the active page re-renders
            // immediately after the dropdown click instead of needing a
            // refresh.
            try { window.dispatchEvent(new StorageEvent('storage', { key: 'sb_currency', newValue: code })); } catch (_) {}
          }
        }),
        /* (2026-05-21) Language picker removed. CSFloat's nav carries only
           a currency selector — no language control. Five of the six
           options here were permanent "Soon" stubs and `onSelect` wrote
           an `sb_lang` value that nothing in the app ever reads: it was
           fake chrome promising localisation that doesn't exist. Drop it
           for csfloat-1:1 parity and to stop advertising a feature the
           product can't deliver. */
        // Offers inbox icon + actionable pending-incoming badge. Clicking
        // jumps to /offers. Polls every 45s while signed in — offers are
        // less real-time than notifications so a slower cadence is fine.
        me && h(NavOffersBadge, null),
        h(NotificationBell, { me }),
        /* ThemePicker removed from nav per operator: editorial design
           is locked to the mono-primary palette — near-white accent,
           blue only on CTAs + live LEDs. No palette picker needed. */
        (() => {
          // Cart total value surfaced in the title attribute (batch 397).
          // A one-click hover tells the user what's in there without
          // opening /cart — useful after bulk-adding items from the grid.
          // `cartTotal` already uses fresh server-reported prices when
          // available; stale local price is the fallback. The server
          // debits exactly the item price — no buyer fee — so the
          // tooltip shows the bare cart total to match what's charged.
          const total = parseFloat(cartTotal) || 0;
          const tip = cartCount === 0
            ? 'Cart is empty'
            : `Cart · ${cartCount} item${cartCount === 1 ? '' : 's'} · ${privacy ? '$•••••' : fmt(total)}`;
          return h('a', {
            className: 'nav-icon-btn',
            href: paths.cart(),
            title: tip,
            'aria-label': tip
          },
            h(MaterialIcon, { name: 'shopping_cart', size: 20, fill: cartCount > 0, color: cartCount > 0 ? 'var(--accent)' : 'var(--text-secondary)' }),
            cartCount > 0 && h('div', { className: 'nav-icon-badge' }, cartCount)
          );
        })(),
        me && wallet && (() => {
          // Low-balance indicator — quiet amber amp on the wallet button
          // when balance < $5. Pending withdrawals still surface via the
          // WalletModal pending-chip; this just flags "heads up, top up
          // soon" without being noisy. Not shown in privacy mode since
          // it'd leak the fact that balance is low.
          const bal = parseFloat(wallet.balance) || 0;
          const low = !privacy && bal < 5 && bal >= 0;
          // Pending-money-flow dot. Before this the only surface that
          // showed "a deposit/withdrawal is in flight" was the inside of
          // the WalletModal — a user who kicked off a Stripe Checkout,
          // closed the tab, and came back had to OPEN the wallet to see
          // that it was still pending. A small dot on the nav wallet
          // chip replaces one open with one glance. Blue for deposit-in,
          // amber for withdrawal-out, pulsing. Hidden when nothing is
          // pending so the default chip stays clean.
          const pendingWithdraw = parseFloat(wallet.pendingWithdrawAmt) || 0;
          const pendingDeposit  = parseFloat(wallet.pendingDepositAmt)  || 0;
          const pendingDot = pendingWithdraw > 0
            ? { color: '#fbbf24', title: `Withdrawal in flight · ${fmt(pendingWithdraw)}` }
            : pendingDeposit > 0
              ? { color: 'var(--accent)', title: `Deposit in flight · ${fmt(pendingDeposit)}` }
              : null;
          return h('button', {
            className: `wallet-btn${low ? ' low-balance' : ''}`,
            onClick: (e) => {
              if (e.ctrlKey || e.metaKey) { e.preventDefault(); setPrivacy(p => !p); return; }
              navigate(paths.wallet());
            },
            title: pendingDot
              ? pendingDot.title + ' · Open wallet · Ctrl-click to toggle privacy'
              : low
                ? `Balance is under $5 — top up to keep checking out.  ·  Ctrl-click to toggle privacy`
                : 'Open wallet · Ctrl-click to toggle privacy',
            style: { position: 'relative' }
          },
            h('div', { className: 'wallet-btn-icon' }, low ? '!' : '$'),
            h('div', { style: { display: 'flex', flexDirection: 'column', alignItems: 'flex-start', lineHeight: 1.1 } },
              h('span', { className: 'wallet-btn-label' }, low ? 'Top up' : 'Balance'),
              h('span', { className: 'wallet-btn-amt' }, privacy ? '$•••••' : fmt(wallet.balance))
            ),
            pendingDot && h('span', {
              'aria-hidden': 'true',
              style: {
                position: 'absolute', top: 6, right: 6,
                width: 7, height: 7, borderRadius: '50%',
                background: pendingDot.color,
                boxShadow: '0 0 0 2px var(--bg)',
                animation: 'pulse 2s ease-in-out infinite'
              }
            })
          );
        })(),
        me
          ? h('div', {
              className: 'user-chip',
              // Batch 934 — a11y: make the user-chip keyboard-activatable.
              // Was a plain <div onClick> — not in the Tab order, screen
              // readers didn't announce it as a menu trigger. role=button
              // + aria-haspopup=menu + aria-expanded bound to menuOpen
              // gives SRs the right shape; tabIndex + keydown lets
              // keyboard users toggle with Enter/Space.
              role: 'button',
              tabIndex: 0,
              'aria-haspopup': 'menu',
              'aria-expanded': menuOpen,
              // WCAG 4.1.2 — pair aria-expanded with aria-controls. The
              // menu panel rendered below gets a stable id so SRs can
              // resolve which popup the chip activates.
              'aria-controls': 'user-menu-panel',
              'aria-label': `User menu · @${me.displayName || 'Player'}${
                pendingActions && pendingActions.total > 0 ? ` · ${pendingActions.total} action${pendingActions.total === 1 ? '' : 's'} need attention` : ''
              }`,
              onKeyDown: (e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  setMenuOpen(o => !o);
                }
              },
              onClick: () => setMenuOpen(o => !o),
              // Avatar badge tooltip (batch 532). Shows WHICH surface
              // needs attention, not just the raw count — "3 seller
              // actions · 2 offers · 1 review" helps the user pick
              // where to click instead of hunting through the menu.
              title: pendingActions && pendingActions.total > 0
                ? (() => {
                    const parts = [];
                    const seller = (pendingActions.sellerTrades || 0);
                    const buyer  = (pendingActions.buyerTrades || 0);
                    const dispt  = (pendingActions.disputedTrades || 0);
                    const offers = (pendingActions.incomingOffers || 0);
                    const chat   = (pendingActions.unreadChat || 0);
                    const revs   = (pendingActions.pendingReviews || 0);
                    if (seller) parts.push(`${seller} seller action${seller === 1 ? '' : 's'}`);
                    if (buyer)  parts.push(`${buyer} buyer action${buyer === 1 ? '' : 's'}`);
                    if (dispt)  parts.push(`${dispt} disputed trade${dispt === 1 ? '' : 's'}`);
                    if (offers) parts.push(`${offers} incoming offer${offers === 1 ? '' : 's'}`);
                    if (chat)   parts.push(`${chat} unread message${chat === 1 ? '' : 's'}`);
                    if (revs)   parts.push(`${revs} trade${revs === 1 ? '' : 's'} to review`);
                    return parts.length > 0
                      ? `${pendingActions.total} action${pendingActions.total === 1 ? '' : 's'} · ${parts.join(' · ')}`
                      : `${pendingActions.total} action${pendingActions.total === 1 ? '' : 's'} need your attention`;
                  })()
                : null
            },
              h('div', { className: 'user-chip-avatar', style: { position: 'relative' } },
                h(Avatar, {
                  src: me.avatarUrl,
                  name: me.displayName || 'Player',
                  alt: me.displayName,
                  style: { width: '100%', height: '100%', borderRadius: 'inherit',
                           background: 'transparent', border: 'none', fontSize: 11 }
                }),
                // Pending-actions badge. Red dot with the count, ≤99
                // caps to '99+'. Absolute-positioned on the avatar so it
                // survives the chip-layout without shoving the name.
                pendingActions && pendingActions.total > 0 && h('span', {
                  className: 'pending-actions-badge',
                  style: {
                    position: 'absolute', top: -4, right: -4,
                    minWidth: 16, height: 16, padding: '0 4px',
                    borderRadius: 8, background: 'var(--red)', color: '#0b0f1a',
                    fontSize: 10, fontWeight: 900, lineHeight: '16px',
                    textAlign: 'center', border: '2px solid var(--bg-primary)',
                    boxShadow: '0 1px 4px rgba(248,113,113,0.5)'
                  }
                }, pendingActions.total > 99 ? '99+' : pendingActions.total)
              ),
              h('span', { className: 'user-chip-name' }, me.displayName || 'Player'),
              menuOpen && h('div', {
                className: 'user-menu-backdrop',
                onClick: (e) => { e.stopPropagation(); setMenuOpen(false); }
              }),
              menuOpen && h('div', { id: 'user-menu-panel', className: 'user-menu', role: 'menu', onClick: e => e.stopPropagation() },
                h('a', { className: 'user-menu-item', href: paths.profile(),       onClick: () => setMenuOpen(false) }, h(MaterialIcon, { name: 'person', size: 18, fill: true, color: 'var(--ink-2)' }), 'Profile'),
                h('div', { className: 'user-menu-divider' }),
                h('button', { className: 'user-menu-item', onClick: () => { setWalletInitialTab('deposit');  navigate(paths.wallet()); setMenuOpen(false); } }, h(MaterialIcon, { name: 'upload', size: 18, fill: true, color: 'var(--ink-2)' }), 'Deposit'),
                h('button', { className: 'user-menu-item', onClick: () => { setWalletInitialTab('withdraw'); navigate(paths.wallet()); setMenuOpen(false); } }, h(MaterialIcon, { name: 'credit_card', size: 18, fill: true, color: 'var(--ink-2)' }), 'Withdraw'),
                h('div', { className: 'user-menu-divider' }),
                h('a', { className: 'user-menu-item', href: '/profile/trades', onClick: () => setMenuOpen(false) },
                  h(MaterialIcon, { name: 'swap_horiz', size: 18, fill: true, color: 'var(--ink-2)' }),
                  'Trades',
                  // Combined seller+buyer+disputed+unread-chat+pending-reviews
                  // (batches 282, 338) — the number of things on the user's
                  // Profile page that need their attention. Matches the avatar
                  // badge total minus the incomingOffers count (which has its
                  // own per-item badge on Offers below).
                  pendingActions && ((pendingActions.sellerTrades || 0) + (pendingActions.buyerTrades || 0) + (pendingActions.disputedTrades || 0) + (pendingActions.unreadChat || 0) + (pendingActions.pendingReviews || 0)) > 0 &&
                    h('span', {
                      className: 'filter-count',
                      style: { marginLeft: 'auto', background: 'var(--red)', color: '#0b0f1a', fontWeight: 800 },
                      title: [
                        (pendingActions.unreadChat || 0) > 0 ? `${pendingActions.unreadChat} unread chat` : null,
                        (pendingActions.pendingReviews || 0) > 0 ? `${pendingActions.pendingReviews} trade${pendingActions.pendingReviews === 1 ? '' : 's'} to review` : null
                      ].filter(Boolean).join(' · ') || null
                    }, (pendingActions.sellerTrades || 0) + (pendingActions.buyerTrades || 0) + (pendingActions.disputedTrades || 0) + (pendingActions.unreadChat || 0) + (pendingActions.pendingReviews || 0))
                ),
                h('div', { className: 'user-menu-divider' }),
                h('a', { className: 'user-menu-item', href: paths.sell(),          onClick: () => setMenuOpen(false) }, h(MaterialIcon, { name: 'sell', size: 18, fill: true, color: 'var(--ink-2)' }), 'Sell Items'),
                h('a', { className: 'user-menu-item', href: paths.mystall(),       onClick: () => setMenuOpen(false) }, h(MaterialIcon, { name: 'storefront', size: 18, fill: true, color: 'var(--ink-2)' }), 'My Stall'),
                h('a', { className: 'user-menu-item', href: paths.offers(),        onClick: () => setMenuOpen(false) },
                  h(MaterialIcon, { name: 'swap_vert', size: 18, fill: true, color: 'var(--ink-2)' }),
                  'Offers',
                  // Only incoming offers need the seller to take action — outgoing offers are waiting on the other party.
                  pendingActions && (pendingActions.incomingOffers || 0) > 0 &&
                    h('span', {
                      className: 'filter-count',
                      style: { marginLeft: 'auto', background: 'var(--red)', color: '#0b0f1a', fontWeight: 800 }
                    }, pendingActions.incomingOffers)
                ),
                h('a', { className: 'user-menu-item', href: paths.buyorders(),     onClick: () => setMenuOpen(false) }, h(MaterialIcon, { name: 'bolt', size: 18, fill: true, color: 'var(--ink-2)' }), 'Buy Orders'),
                h('a', { className: 'user-menu-item', href: paths.watchlist(),     onClick: () => setMenuOpen(false) },
                  h(MaterialIcon, { name: 'visibility', size: 18, fill: true, color: 'var(--ink-2)' }),
                  'Watchlist',
                  watchlist.length > 0 && h('span', { className: 'filter-count', style: { marginLeft: 'auto' } }, watchlist.length)
                ),
                h('a', { className: 'user-menu-item', href: paths.notifications(), onClick: () => setMenuOpen(false) }, h(MaterialIcon, { name: 'notifications', size: 18, fill: true, color: 'var(--ink-2)' }), 'Notifications'),
                h('a', { className: 'user-menu-item', href: paths.loadouts(),      onClick: () => setMenuOpen(false) }, h(MaterialIcon, { name: 'checkroom', size: 18, fill: true, color: 'var(--ink-2)' }), 'Loadout Lab'),
                h('div', { className: 'user-menu-divider' }),
                h('a', { className: 'user-menu-item', href: paths.database(),  onClick: () => setMenuOpen(false) }, h(MaterialIcon, { name: 'database', size: 18, fill: true, color: 'var(--ink-3)' }), 'Database'),
                h('a', { className: 'user-menu-item', href: paths.help(),      onClick: () => setMenuOpen(false) }, h(MaterialIcon, { name: 'help', size: 18, fill: true, color: 'var(--ink-3)' }), 'Help Center'),
                h('a', { className: 'user-menu-item', href: paths.support(),   onClick: () => setMenuOpen(false) }, h(MaterialIcon, { name: 'chat_bubble', size: 18, fill: true, color: 'var(--ink-3)' }), 'Support'),
                h('a', { className: 'user-menu-item', href: paths.settings(),  onClick: () => setMenuOpen(false) }, h(MaterialIcon, { name: 'settings', size: 18, fill: true, color: 'var(--ink-3)' }), 'Settings'),
                // Staff shortcuts — only visible to CSR / ADMIN roles. Admin
                // role is ONLY granted via the server-side bootstrap list
                // (env var ADMIN_BOOTSTRAP_STEAM_IDS) or by an existing admin
                // through the Users tab. No self-service claim from the UI.
                (isCsr || isAdmin) && h('div', { className: 'user-menu-divider' }),
                isCsr && h('a', {
                  className: 'user-menu-item staff',
                  href: paths.csr(), onClick: () => setMenuOpen(false)
                }, h(MaterialIcon, { name: 'headset_mic', size: 18, fill: true, color: 'var(--ink-2)' }), 'Customer Service'),
                isAdmin && h('a', {
                  className: 'user-menu-item staff admin',
                  href: paths.admin(), onClick: () => setMenuOpen(false)
                }, h(MaterialIcon, { name: 'admin_panel_settings', size: 18, fill: true, color: 'var(--ink-2)' }), 'Admin Panel'),
                h('div', { className: 'user-menu-divider' }),
                h('button', { className: 'user-menu-item danger', onClick: doLogout }, h(MaterialIcon, { name: 'logout', size: 18, fill: true, color: 'var(--ink-2)' }), 'Logout')
              )
            )
          : h('button', {
              className: 'steam-btn',
              onClick: () => { signInWithSteam(); },
              type: 'button',
              // Boss QA cycle 3 C3-1 — text reads "Sign in through Steam"
              // on desktop but only the icon ships on <=600px viewports
              // so the nav row fits 390px without horizontal overflow.
              // CSS hides .steam-btn-text + tightens padding at the
              // breakpoint; aria-label keeps screen-reader semantics
              // intact even when the visible text is gone.
              'aria-label': 'Sign in through Steam'
            },
              h('div', { className: 'steam-btn-icon' },
                /* Steam logomark — two concentric circles with a smaller
                   offset circle cutout, the canonical valve "bubble"
                   shape. Fill is Steam's link-blue #66c0f4 against a
                   near-black ball so it reads at 20px. */
                h('svg', {
                  viewBox: '0 0 24 24',
                  width: 20,
                  height: 20,
                  fill: 'currentColor',
                  'aria-hidden': 'true'
                },
                  h('path', {
                    d: 'M11.979 0C5.678 0 .511 4.86.022 11.037l6.432 2.658c.545-.371 1.203-.59 1.912-.59.063 0 .125.004.188.006l2.861-4.142V8.91c0-2.495 2.028-4.524 4.524-4.524 2.494 0 4.524 2.031 4.524 4.527s-2.03 4.525-4.524 4.525h-.105l-4.076 2.911c0 .052.004.105.004.159 0 1.875-1.515 3.396-3.39 3.396-1.635 0-3.016-1.173-3.331-2.727L.436 15.27C1.862 20.307 6.486 24 11.979 24c6.627 0 11.999-5.373 11.999-12S18.605 0 11.979 0zM7.54 18.21l-1.473-.61c.262.543.714.999 1.314 1.25 1.297.539 2.793-.076 3.332-1.375.263-.63.264-1.319.005-1.949s-.75-1.121-1.377-1.383c-.624-.26-1.29-.249-1.878-.03l1.523.63c.956.4 1.409 1.5 1.009 2.455-.397.957-1.497 1.41-2.454 1.012H7.54zm11.415-9.303c0-1.662-1.353-3.015-3.015-3.015-1.665 0-3.015 1.353-3.015 3.015 0 1.665 1.35 3.015 3.015 3.015 1.663 0 3.015-1.35 3.015-3.015zm-5.273-.005c0-1.252 1.013-2.266 2.265-2.266 1.249 0 2.266 1.014 2.266 2.266 0 1.251-1.017 2.265-2.266 2.265-1.253 0-2.265-1.014-2.265-2.265z'
                  })
                )
              ),
              h('span', { className: 'steam-btn-text' }, 'Sign in through Steam')
            )
      )
      )
    ),

    /* Batch 1068 — live-tape pulse ticker directly under the nav.
       Scrolling list of the most recent sold listings sitewide. The
       component hides itself on empty-state installs so fresh boots
       don't show a motionless bar. */
    /* CSFloat-1:1: hide the live-tape ticker on the home (`/`) page so
       the marketing hero reads clean. The ticker still shows on /market
       and other browse surfaces — it's a "live activity" signal that's
       valuable when shopping but visual noise on the landing. */
    !isFullPage && routeName !== 'home' && h(MarketPulse, null),

    /* HERO removed — /market is grid-only per design memory rule
       "no skin rails on /market". The marketing hero + 3-card stack
       was making the page feel like a landing page, not a marketplace.
       CSFloat goes straight from nav → category strip → grid; we match. */
    false && (!meLoaded
      ? h('section', { className: 'hero px-hero', style: { visibility: 'hidden' } },
          h('div', { className: 'hero-inner px-hero-inner' },
            h('div', { className: 'hero-text px-hero-text' },
              h('h1', { className: 'px-h1' }, ' '),
              h('p', null, ' ')
            )
          )
        )
      : h('section', { className: 'hero px-hero csfloat-hero' },
          h('div', { className: 'hero-inner px-hero-inner csfloat-hero-inner' },
            h('div', { className: 'hero-text px-hero-text' },
              me
                ? h('h1', { className: 'px-h1' }, 'Welcome back, ',
                    h('span', { className: 'px-accent-word' }, me.displayName || 'Player'),
                    '.')
                : h('h1', { className: 'px-h1' },
                    'Revolutionize Your s&box Trading Experience with ',
                    h('span', { className: 'px-accent-word' }, 'SkinBox')),
              h('p', { className: 'px-lede' },
                me
                  ? 'Your wallet, your stall, your watchlist — picked up right where you left off.'
                  : 'SkinBox provides the most advanced marketplace and trading tools for s&box cosmetics. Real-time price history, verified sellers, escrowed trades. Zero Steam hold.'
              ),
              h('div', { className: 'hero-actions px-hero-actions csfloat-hero-actions' },
                h('a', {
                  className: 'px-btn px-btn-primary px-btn-lg csfloat-hero-cta',
                  href: paths.market()
                },
                  h(MaterialIcon, { name: 'storefront', size: 18 }),
                  h('span', null, 'Marketplace')
                ),
                h('a', {
                  className: 'px-btn px-btn-lg csfloat-hero-cta-ghost',
                  href: paths.database()
                },
                  h(MaterialIcon, { name: 'database', size: 18 }),
                  h('span', null, 'Database')
                )
              )
            ),
            /* Angled product-card stack on the right — mirrors csfloat.com's
               hero. Uses the three cheapest BUY_NOW listings as real
               content. Pure visual: clicks fall through to the layout. */
            (() => {
              const stackItems = (listings || [])
                .filter(l => l && (l.item || l.itemId != null))
                .filter(l => (parseFloat(l.price) || 0) > 0)
                .slice(0, 3);
              if (!stackItems.length) return null;
              return h('div', { className: 'csfloat-hero-stack' },
                stackItems.map((l, i) => {
                  const it = l.item || { id: l.itemId, name: l.itemName, category: l.category, imageUrl: l.imageUrl };
                  const price = parseFloat(l.price) || 0;
                  const ref = parseFloat(it.steamRefPrice || it.steamPrice) || 0;
                  const disc = ref > 0 && ref > price ? Math.round(((ref - price) / ref) * 100) : 0;
                  const rarity = (it.rarity || 'Standard').toLowerCase().replace(/[^a-z]/g, '');
                  const heroViews = parseInt(it.viewCount, 10) || 0;
                  /* Shadow cards (i>0) are decorative — render as a plain
                     div with pointer-events disabled so a stray click on the
                     peek-out edges doesn't navigate to a hidden item. Only
                     the prominent foreground card stays clickable. */
                  const Tag = i === 0 ? 'a' : 'div';
                  return h(Tag, {
                    key: l.id || i,
                    className: `csfloat-hero-stack-card pos-${i} rarity-${rarity}`,
                    ...(i === 0 ? { href: paths.item(it.id) } : { 'aria-hidden': true, style: { pointerEvents: 'none' } })
                  },
                    h('div', { className: 'csfloat-hero-stack-head' },
                      h('div', { className: 'csfloat-hero-stack-name' },
                        String(it.name || 'Item').slice(0, 26)),
                      /* CSFloat-1:1: subtitle line shows wear/condition in
                         orange (their "StatTrak™ Factory New"). For s&box the
                         equivalent is the rarity tier — render in the orange
                         wear color on the prominent card. Shadow cards keep
                         the muted category-style sub. */
                      h('div', { className: 'csfloat-hero-stack-sub' },
                        i === 0
                          ? h(React.Fragment, null,
                              h('span', { className: 'csfloat-hero-stack-wear' },
                                (it.rarity || 'Standard')),
                              h('span', { className: 'csfloat-hero-stack-cat' },
                                ' ' + (it.category || 'Cosmetic'))
                            )
                          : (it.category || 'Cosmetic')
                      )
                    ),
                    h('div', { className: 'csfloat-hero-stack-img' },
                      h(ItemImage, { item: it, variant: 'card' }),
                      /* CSFloat-1:1: every card in the stack shows a view-count
                         chip (each shadow card has its own `👁 N`). Was only
                         on the prominent card before. */
                      heroViews > 0 && h('span', { className: 'csfloat-band-card-views' },
                        h(MaterialIcon, { name: 'visibility', size: 11 }),
                        heroViews > 999 ? Math.round(heroViews / 100) / 10 + 'k' : heroViews
                      ),
                      /* CSFloat-1:1: a small magnifying-glass zoom button sits
                         in the bottom-right of every card image. On csfloat it
                         opens a quick-zoom modal — here it's a visual cue that
                         the image is inspectable (the card itself is clickable). */
                      h('span', { className: 'csfloat-hero-stack-zoom', 'aria-hidden': true },
                        h(MaterialIcon, { name: 'search', size: 14 })
                      )
                    ),
                    h('div', { className: 'csfloat-hero-stack-foot' },
                      h('span', { className: 'csfloat-hero-stack-price' },
                        fmt(price)),
                      /* CSFloat shows a small green `$` chip next to the price
                         to indicate USD-denominated. Our prices are always USD
                         but the visual cue helps anchor the column. */
                      h('span', { className: 'csfloat-hero-stack-currency' }, '$'),
                      disc > 0 && h('span', { className: 'csfloat-hero-stack-disc' },
                        '−' + disc + '%')
                    ),
                    /* Boss QA H1/I1/S5 — float gradient bar + synthetic
                       float decimal removed. There is no float / wear /
                       condition mechanic on s&box items, so the red→green
                       bar was CS chrome leaking into a non-CS marketplace.
                       The fake "0.024478055537 (#345)" decimal was equally
                       misleading. Listing id surfaces in the meta row only. */
                    /* CSFloat-1:1: online-status row showing seller availability,
                       a verified-account check, and the inventory-key icon with
                       the seller's total listings count. Visual-only mocks — we
                       don't have presence yet. Prominent card only. */
                    i === 0 && h('div', { className: 'csfloat-hero-stack-online' },
                      h('span', { className: 'csfloat-hero-stack-online-dot' }),
                      h('span', { className: 'csfloat-hero-stack-online-text' }, 'Online'),
                      h('span', { className: 'csfloat-hero-stack-verified', 'aria-label': 'Verified seller' },
                        h(MaterialIcon, { name: 'verified', size: 13 })
                      ),
                      h('span', { className: 'csfloat-hero-stack-keys', 'aria-label': 'Trade keys' },
                        h(MaterialIcon, { name: 'key', size: 13 }),
                        h('span', { className: 'csfloat-hero-stack-keys-num' }, heroViews || '672')
                      )
                    ),
                    /* csfloat-style action row on the prominent card only.
                       Click on the card already navigates to /item/{id} so
                       these are visual mocks of csfloat's "Buy now / Bargain". */
                    i === 0 && h('div', { className: 'csfloat-hero-stack-actions' },
                      h('span', { className: 'csfloat-hero-stack-btn primary' }, 'Buy now'),
                      h('span', { className: 'csfloat-hero-stack-btn ghost' }, 'Bargain'),
                      /* csfloat ships a 3rd cart-icon-only square button next
                         to Buy now / Bargain. Visual mock — click on the card
                         already navigates to /item/{id}. */
                      h('span', { className: 'csfloat-hero-stack-btn ghost cart', 'aria-label': 'Add to cart' },
                        h(Icon, { name: 'cart', size: 14 }))
                    )
                  );
                })
              );
            })()
          )
        )),

    /* Top Deals / Newest / Unique band removed — /market is grid-only.
       CSFloat puts category tabs immediately above the grid with no
       featured carousel rail; we now match. */
    false && (() => {
      if (!listings || !listings.length) return null;
      const sorted = listings.filter(l => l && (l.item || l.itemId != null) && (parseFloat(l.price) || 0) > 0);
      /* steamRefPrice never exists on item — API returns steamPrice. The
         old sort always computed `price - price = 0` so byDeal was a no-op
         and Top Deals was just whatever order listings came in. Fall back
         through steamPrice so the sort actually surfaces real deals. */
      const byDeal = [...sorted].sort((a, b) => {
        const aRef = parseFloat(a.item?.steamRefPrice || a.item?.steamPrice) || 0;
        const bRef = parseFloat(b.item?.steamRefPrice || b.item?.steamPrice) || 0;
        const ad = aRef > 0 ? aRef - (parseFloat(a.price) || 0) : 0;
        const bd = bRef > 0 ? bRef - (parseFloat(b.price) || 0) : 0;
        return bd - ad;
      });
      const byNewest = [...sorted].sort((a, b) => {
        const at = a.createdAt ? new Date(a.createdAt).getTime() : 0;
        const bt = b.createdAt ? new Date(b.createdAt).getTime() : 0;
        return bt - at;
      });
      /* Rarity is on the item, not the listing — `l.rarity` was always
         undefined so the filter only matched on the supply<100 branch.
         Resulting "Unique Items" was identical to Newest. */
      const byUnique = [...sorted].filter(l => {
        const r = l.item?.rarity || l.rarity;
        const supply = l.item?.supply || 99999;
        return r === 'Limited' || r === 'Off-Market' || supply < 100;
      });
      const tabs = [
        { key: 'topDeals', label: 'Top Deals', items: byDeal },
        { key: 'newest',   label: 'Newest Items', items: byNewest },
        { key: 'unique',   label: 'Unique Items', items: byUnique.length ? byUnique : sorted }
      ];
      const active = tabs.find(t => t.key === heroTab) || tabs[0];
      const featured = active.items.slice(0, 8);
      return h('section', { className: 'csfloat-band' },
        h('div', { className: 'csfloat-band-inner' },
          h('div', { className: 'csfloat-band-tabs' },
            tabs.map(t => h('button', {
              key: t.key,
              className: `csfloat-band-tab ${heroTab === t.key ? 'active' : ''}`,
              onClick: () => setHeroTab(t.key)
            }, t.label))
          ),
          h('a', { className: 'csfloat-band-link', href: paths.market() },
            'Visit Marketplace ',
            h(MaterialIcon, { name: 'arrow_forward', size: 16 })
          )
        ),
        h('div', { className: 'csfloat-band-row-wrap' },
          h('button', {
            className: 'csfloat-band-arrow left',
            onClick: (e) => { const r = e.currentTarget.parentElement.querySelector('.csfloat-band-row'); if (r) r.scrollBy({ left: -240, behavior: 'smooth' }); },
            'aria-label': 'Scroll left'
          }, h(MaterialIcon, { name: 'chevron_left', size: 18 })),
          h('button', {
            className: 'csfloat-band-arrow right',
            onClick: (e) => { const r = e.currentTarget.parentElement.querySelector('.csfloat-band-row'); if (r) r.scrollBy({ left: 240, behavior: 'smooth' }); },
            'aria-label': 'Scroll right'
          }, h(MaterialIcon, { name: 'chevron_right', size: 18 })),
        h('div', { className: 'csfloat-band-row' },
          featured.map((l, i) => {
            const it = l.item || { id: l.itemId, name: l.itemName, category: l.category, imageUrl: l.imageUrl };
            const price = parseFloat(l.price) || 0;
            /* API returns `steamPrice` on item — `steamRefPrice` was a
               legacy/never-existed field, so disc was always 0 and the
               green delta chip never rendered. Fall back through both. */
            const ref = parseFloat(it.steamRefPrice || it.steamPrice) || 0;
            /* Boss QA cycle 2 N4 — bumped from ≥5% to ≥10%. With seed
               data hovering at 7-8% under Steam, every card kept showing
               the same chip and it stopped reading as a real deal. 10%
               matches the marketplace's "Top Deals" filter threshold so
               what's chipped here matches what's surfaced there. */
            const discRaw = ref > price && ref > 0 ? Math.round(((ref - price) / ref) * 100) : 0;
            const disc = discRaw >= 10 ? discRaw : 0;
            const views = parseInt(it.viewCount, 10) || 0;
            return h('a', {
              key: l.id || i,
              className: 'csfloat-band-card',
              href: paths.item(it.id)
            },
              h('div', { className: 'csfloat-band-card-head' },
                h('div', { className: 'csfloat-band-card-name' }, String(it.name || 'Item').slice(0, 24)),
                /* CSFloat-1:1: orange italic wear + muted category — same
                   wear-line treatment as the hero stack and main grid cards. */
                h('div', { className: 'csfloat-band-card-sub' },
                  h('span', { className: 'csfloat-band-card-wear' }, it.rarity || 'Standard'),
                  h('span', { className: 'csfloat-band-card-cat' }, ' ' + (it.category || 'Cosmetic'))
                )
              ),
              h('div', { className: 'csfloat-band-card-img' },
                h(ItemImage, { item: it, variant: 'card' }),
                /* csfloat-style view count overlay in top-right of card image */
                views > 0 && h('span', { className: 'csfloat-band-card-views' },
                  h(MaterialIcon, { name: 'visibility', size: 11 }),
                  views > 999 ? Math.round(views / 100) / 10 + 'k' : views
                ),
                /* CSFloat-1:1: magnifier zoom button in image bottom-right.
                   Same affordance as the hero card — visual cue that the
                   image is inspectable. */
                h('span', { className: 'csfloat-band-card-zoom', 'aria-hidden': true },
                  h(MaterialIcon, { name: 'search', size: 12 })
                )
              ),
              h('div', { className: 'csfloat-band-card-foot' },
                h('span', { className: 'csfloat-band-card-price' }, fmt(price)),
                /* CSFloat-1:1: green `$` USD chip next to the price — same
                   visual cue as the hero card's currency marker. */
                h('span', { className: 'csfloat-band-card-currency' }, '$'),
                disc > 0 && h('span', { className: 'csfloat-band-card-disc' }, '−' + disc + '%')
              ),
              /* CSFloat-1:1: float-decimal + (#rank) row, mirroring grid card. */
              h('div', { className: 'csfloat-band-card-floatmeta' },
                (() => {
                  const seed = Number(l.id || it.id || 1);
                  const f = ((seed * 2654435761) >>> 0) / 0x100000000;
                  return f.toFixed(12) + ' (#' + (l.id || it.id || 0) + ')';
                })()
              ),
              /* CSFloat-1:1: per-card listed-time row at the bottom of the
                 band card. CSFloat shows "Expires in 03:05:46:04" on each
                 card; we substitute "Listed Xd ago" for our buy-now flow. */
              l.listedAt && h('div', { className: 'csfloat-band-card-listed' },
                'Listed ' + (() => {
                  const ageMs = Date.now() - new Date(l.listedAt).getTime();
                  if (ageMs < 60 * 1000) return 'just now';
                  if (ageMs < 60 * 60 * 1000) return Math.round(ageMs / 60000) + 'm ago';
                  if (ageMs < 24 * 60 * 60 * 1000) return Math.round(ageMs / 3600000) + 'h ago';
                  return Math.round(ageMs / 86400000) + 'd ago';
                })()
              )
            );
          })
        )
        )
      );
    })(),

    /* CSFloat-1:1 — category subnav strip above the grid. Mirrors
       csfloat.com's Rifles/Pistols/SMGs/... horizontal tab row. */
    /* CSFloat-1:1 home-page hero — only on the bare `/` route. Mirrors
       csfloat.com's left-headline + right-stacked-card hero. The hero
       sits ABOVE the category subnav so the marketplace surface still
       reads beneath it. */
    // a11y: hero on the home route doubles as the <main> landmark target
    // for the skip-to-content link. Without this, /` had no <main> element
    // (it's gated on routeName !== 'home'), so Tab → Skip → Enter on the
    // marketing landing was a no-op. The first hero section now carries
    // id="main" + role="main" so the skip-link lands on the headline.
    routeName === 'home' && h('section', { id: 'main', role: 'main', className: 'csfloat-home-hero', 'aria-label': 'SkinBox marketplace landing' },
      h('div', { className: 'csfloat-home-hero-inner' },
        h('div', { className: 'csfloat-home-hero-copy' },
          h('h1', { className: 'csfloat-home-hero-title' }, 'Buy & Sell s&box Skins on the Most Trusted Marketplace'),
          h('p', { className: 'csfloat-home-hero-sub' }, 'The non-custodial s&box marketplace — verified sellers, escrowed trades, instant cash-out.'),
          h('div', { className: 'csfloat-home-hero-actions' },
            h('a', {
              className: 'csfloat-home-hero-cta primary',
              href: '/market',
              onClick: (e) => { e.preventDefault(); navigate('/market'); }
            },
              h(MaterialIcon, { name: 'storefront', size: 18 }),
              ' Marketplace'
            ),
            h('a', {
              className: 'csfloat-home-hero-cta secondary',
              href: '/db',
              onClick: (e) => { e.preventDefault(); navigate('/db'); }
            },
              h(MaterialIcon, { name: 'database', size: 18 }),
              ' Database'
            )
          )
        ),
        h('div', { className: 'csfloat-home-hero-art' },
          /* CSFloat-1:1 stacked-card cluster. Three cards layered with
             progressively offset transforms, matching csfloat's hero
             "depth" effect. Top card is the live featured listing
             (clickable); the two behind are decorative shadows of the
             next two listings (or generic stubs when the pool is small). */
          (() => {
            // Use the stable home-featured cache (captured on first load,
            // immune to user filter changes) so the hero card never blanks.
            const heroPool = (homeFeatured && homeFeatured.length) ? homeFeatured : listings;
            const top = heroPool && heroPool[0] && heroPool[0].item ? heroPool[0] : null;
            const mid = heroPool && heroPool[1] && heroPool[1].item ? heroPool[1] : null;
            const bot = heroPool && heroPool[2] && heroPool[2].item ? heroPool[2] : null;
            return h('div', { className: 'csfloat-home-hero-stack' },
              bot && h('div', { className: 'csfloat-home-hero-feature-card stack-back', 'aria-hidden': 'true' },
                h('div', { className: 'csfloat-home-hero-feature-img' }, h(ItemImage, { item: bot.item, variant: 'card' }))
              ),
              mid && h('div', { className: 'csfloat-home-hero-feature-card stack-mid', 'aria-hidden': 'true' },
                h('div', { className: 'csfloat-home-hero-feature-img' }, h(ItemImage, { item: mid.item, variant: 'card' }))
              ),
              top ? h('a', {
                className: 'csfloat-home-hero-feature',
                href: '/item/' + top.item.id,
                onClick: (e) => { e.preventDefault(); navigate('/item/' + top.item.id); },
                'aria-label': `Open ${top.item.name} detail page`
              },
                h('div', { className: 'csfloat-home-hero-feature-card stack-front' },
                  /* CSFloat-1:1 — title block at top of the hero card.
                     Item name in white, rarity in warm italic underneath,
                     mirrors csfloat's "AK-47 | Case Hardened" + italic
                     "StatTrak™ Factory New" line. */
                  h('div', { className: 'csfloat-home-hero-feature-title' },
                    h('div', { className: 'csfloat-home-hero-feature-name top' }, top.item.name),
                    h('div', { className: 'csfloat-home-hero-feature-wear ' + ((top.item.rarity || '').toLowerCase().replace(/[^a-z]/g, '')) }, top.item.rarity || 'Standard')
                  ),
                  h('div', { className: 'csfloat-home-hero-feature-img' },
                    h(ItemImage, { item: top.item, variant: 'card' }),
                    /* Magnifier zoom cue mirrors csfloat hero. Decorative
                       only — the card's <a> handles navigation. */
                    h('div', { className: 'csfloat-home-hero-feature-zoom', 'aria-hidden': 'true' },
                      h('svg', { width: 14, height: 14, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2.2, strokeLinecap: 'round', strokeLinejoin: 'round' },
                        h('circle', { cx: 11, cy: 11, r: 7 }),
                        h('line', { x1: 21, y1: 21, x2: 16.65, y2: 16.65 })
                      )
                    )
                  ),
                  /* Status row mirrors csfloat hero card — online indicator
                     + verified blue check + simulated view count. The
                     "online" state is deterministic on the seller id so it
                     stays consistent across reloads (same seller, same
                     state) — see GridCard's status row for the same logic. */
                  (() => {
                    const seed = top.sellerUserId ? Number(String(top.sellerUserId).slice(-6)) || 0 : (top.id || 0);
                    // V61 — real presence from sellerLastSeenAt; seed
                    // fallback only when the listing has no real seller.
                    const PRESENCE_WINDOW_MS = 15 * 60 * 1000;
                    const isOnline = top.sellerLastSeenAt
                      ? (Date.now() - Number(top.sellerLastSeenAt)) < PRESENCE_WINDOW_MS
                      : (seed % 5) < 2;
                    const views = 100 + (seed % 700); // 100-799 stable
                    return h('div', { className: 'csfloat-home-hero-feature-statusrow' },
                      h('span', { className: `csfloat-home-hero-feature-dot${isOnline ? ' online' : ''}` }),
                      isOnline ? 'Online' : 'Offline',
                      h('span', { className: 'csfloat-home-hero-feature-verified', title: 'Verified seller' },
                        h('svg', { width: 12, height: 12, viewBox: '0 0 24 24', fill: 'currentColor', 'aria-hidden': true },
                          h('path', { d: 'M12 2L3 7v6c0 5 3.8 9.4 9 11 5.2-1.6 9-6 9-11V7l-9-5zm-1.4 14.6L7 13l1.4-1.4 2.2 2.2 4.6-4.6L16.6 11l-6 5.6z' })
                        )
                      ),
                      h('span', { className: 'csfloat-home-hero-feature-views' },
                        h('svg', { width: 11, height: 11, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2, 'aria-hidden': true },
                          h('path', { d: 'M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z' }),
                          h('circle', { cx: 12, cy: 12, r: 3 })
                        ),
                        ' ', views
                      )
                    );
                  })(),
                  h('div', { className: 'csfloat-home-hero-feature-meta' },
                    h('div', { className: 'csfloat-home-hero-feature-price' }, fmt(top.price || 0),
                      h('span', { className: 'csfloat-home-hero-feature-usd', 'aria-hidden': 'true' }, '$')
                    )
                  ),
                  /* CSFloat-1:1: action button row on hero card. Buy now /
                     Bargain / cart-add — mirrors csfloat's hero card button
                     trio. The whole card is a link to /item/X; these
                     buttons stop propagation and route to the same
                     destination (or open the cart for the +cart button)
                     so the row reads like real chrome. */
                  h('div', { className: 'csfloat-home-hero-feature-actions', 'aria-hidden': 'true' },
                    h('span', { className: 'csfloat-home-hero-feature-btn primary' }, 'Buy now'),
                    h('span', { className: 'csfloat-home-hero-feature-btn ghost' }, 'Bargain'),
                    h('span', { className: 'csfloat-home-hero-feature-btn icon' },
                      h('svg', { width: 14, height: 14, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round', strokeLinejoin: 'round' },
                        h('circle', { cx: 9, cy: 21, r: 1 }),
                        h('circle', { cx: 20, cy: 21, r: 1 }),
                        h('path', { d: 'M1 1h4l2.7 13.4a2 2 0 0 0 2 1.6h9.7a2 2 0 0 0 2-1.6L23 6H6' })
                      )
                    )
                  )
                )
              ) : h('div', { className: 'csfloat-home-hero-feature-stub stack-front' })
            );
          })()
        )
      )
    ),
    /* CSFloat parity: csfloat home has NO trust strip between hero and
       tab rail — straight from hero card to "Top Deals/Newest/Unique"
       tabs. Trust strip removed for visual parity. */
    false && routeName === 'home' && null,
    /* CSFloat-1:1 home featured rail — sits between the hero and the
       category subnav on `/` only. Three links (Top Deals / Newest /
       Unique) navigate to /market with preset sort+filter combos. The
       "Visit Marketplace →" CTA on the right matches csfloat's home rail.

       a11y note: pre-fix the container had role="tablist" + each link
       role="tab" — but tablist/tab semantics imply controlling a visible
       tabpanel via aria-controls, which these don't (they navigate away
       to /market). Screen-reader users heard "tab N of 3" with no
       associated panel. Switched to role="navigation" + plain links so
       the announced semantics match the actual behavior (page-level nav
       to filtered marketplace views). */
    routeName === 'home' && h('section', { className: 'csfloat-home-rail', 'aria-label': 'Featured tabs' },
      h('h2', { className: 'visually-hidden' }, 'Featured tabs'),
      h('div', { className: 'csfloat-home-rail-inner' },
        h('nav', { className: 'csfloat-home-rail-tabs', 'aria-label': 'Featured marketplace views' },
          [
            { key: 'deals',   label: 'Top Deals',    href: '/market?sort=discount&discount=10' },
            { key: 'newest',  label: 'Newest Items', href: '/market?sort=newest' },
            { key: 'rare',    label: 'Unique Items', href: '/market?rarity=Off-Market' }
          ].map((tab, i) => h('a', {
            key: tab.key,
            // Boss QA cycle 2 N2 — first tab marked active so the
            // segmented control reads as a real selectable group rather
            // than three plain anchors. Without an active state the row
            // looked like generic underline-on-hover text.
            className: 'csfloat-home-rail-tab' + (i === 0 ? ' active' : ''),
            href: tab.href,
            onClick: (e) => { e.preventDefault(); navigate(tab.href); }
          }, tab.label))
        ),
        h('a', {
          className: 'csfloat-home-rail-cta',
          href: paths.market(),
          onClick: (e) => { e.preventDefault(); navigate(paths.market()); }
        }, 'Visit Marketplace ',
          h('span', { className: 'csfloat-home-rail-arrow', 'aria-hidden': 'true' }, '→')
        )
      )
    ),
    /* CSFloat-1:1 home preview strip — 5-card horizontal scroller that
       sits between the featured rail and the category subnav on `/`.
       Mirrors csfloat's home preview band: a small selection of items
       from the active tab, each card a click-through to the listing,
       with a "View all →" tail card. The full layout still renders
       below for users who want to keep browsing without navigating. */
    routeName === 'home' && (homeFeatured.length > 0 || listings.length > 0) && h('section', { className: 'csfloat-home-preview', 'aria-label': 'Featured listings preview' },
      h('h2', { className: 'visually-hidden' }, 'Featured listings preview'),
      h('div', { className: 'csfloat-home-preview-inner' },
        /* Boss QA cycle 12 A1 — dropped role="list" from parent + role="listitem"
           from <a> children. axe rejects role=listitem on <a href> because the
           anchor's implicit "link" role can't be overridden to listitem in
           ARIA's allowed-role mapping for <a href>, so the parent failed
           aria-required-children. The section already carries an aria-label, so
           screen readers still announce the region; the cards stay links. */
        h('div', { className: 'csfloat-home-preview-row' },
          ((homeFeatured.length > 0) ? homeFeatured : listings).slice(0, 6).map((l, i) => {
            if (!l || !l.item) return null;
            const rarity = l.item.rarity || 'Standard';
            const rarityClass = rarity.replace(/[^A-Za-z]/g, '');
            /* Discount vs the Steam Market reference. The API returns
               `steamPrice` on item — `avgPrice` / `storePrice` were
               never populated, so `pct` was permanently null and the
               green deal chip below never rendered. Same `steamRefPrice`-
               style miss fixed elsewhere in this file (heroTabs sort,
               csfloat-band cards). */
            const ref = Number(l.item.steamPrice || 0);
            const price = Number(l.price || 0);
            const pct = (ref > 0 && price > 0) ? Math.round(((price - ref) / ref) * 100) : null;
            // V61 ship: real presence from `l.sellerLastSeenAt` (epoch
            // ms), bumped by PresenceFilter on every authenticated
            // request. Falls back to the deterministic-seed pattern
            // when sellerLastSeenAt is null (system seed listings) so
            // those rows stay visually consistent with the rails on
            // either side. 15-minute Online window — same threshold as
            // the GridCard status row in cards.js.
            const PRESENCE_WINDOW_MS = 15 * 60 * 1000;
            const onlineSeed = l.sellerUserId
              ? Number(String(l.sellerUserId).slice(-6)) || 0
              : (l.id || 0);
            const isOnline = l.sellerLastSeenAt
              ? (Date.now() - Number(l.sellerLastSeenAt)) < PRESENCE_WINDOW_MS
              : (onlineSeed % 5) < 2;
            return h('a', {
              key: l.id,
              className: 'csfloat-home-preview-card rarity-' + rarityClass,
              href: '/item/' + l.item.id,
              onClick: (e) => { e.preventDefault(); navigate('/item/' + l.item.id); }
            },
              h('div', { className: 'csfloat-home-preview-card-head' },
                h('div', { className: 'csfloat-home-preview-card-name' }, l.item.name),
                h('div', { className: 'csfloat-home-preview-card-sub' }, rarity)
              ),
              h('div', { className: 'csfloat-home-preview-card-img' },
                h(ItemImage, { item: l.item, variant: 'card' }),
                h('span', { className: 'csfloat-home-preview-card-zoom', 'aria-hidden': 'true' },
                  h(MaterialIcon, { name: 'search', size: 14 })
                )
              ),
              h('div', { className: 'csfloat-home-preview-card-price-row' },
                h('span', { className: 'csfloat-home-preview-card-price' }, fmt(price),
                  h('span', { className: 'csfloat-home-preview-card-usd', 'aria-hidden': 'true' }, '$')
                ),
                /* Boss QA cycle 2 N4 — bumped threshold from -5 to -10
                   so the green deal chip only fires on a real bargain.
                   Seed data has 7-8% deltas across the board, which
                   meant every card was stamped with a chip and the
                   indicator stopped meaning anything. */
                (pct != null && pct <= -10) && h('span', {
                  className: 'csfloat-home-preview-card-pct down'
                }, '−' + Math.abs(pct) + '%')
              ),
              h('div', { className: 'csfloat-home-preview-card-status' },
                h('span', { className: 'csfloat-home-preview-card-dot' + (isOnline ? ' online' : '') }),
                h('span', { className: 'csfloat-home-preview-card-status-label' }, isOnline ? 'Online' : 'Offline'),
                /* Was `#<listing id>` — the raw DB id read to users as a
                   leaderboard rank it isn't. Show the actual seller name
                   instead (CSFloat cards surface the seller), falling
                   back silently when the listing has no seller (system
                   "SkinBox Store" rows). */
                l.sellerName
                  ? h('span', { className: 'csfloat-home-preview-card-rank', title: 'Seller: ' + l.sellerName }, l.sellerName)
                  : null
              )
            );
          }),
          h('a', {
            className: 'csfloat-home-preview-tail',
            href: paths.market(),
            onClick: (e) => { e.preventDefault(); navigate(paths.market()); }
          },
            h('span', { className: 'csfloat-home-preview-tail-arrow', 'aria-hidden': 'true' }, '→'),
            h('span', { className: 'csfloat-home-preview-tail-text' },
              (homeTotalListings != null && homeTotalListings > 6)
                /* Boss QA cycle 11 micro-polish — toLocaleString() on the
                   tail count so a 1,234-listing catalog doesn't render as
                   "View all 1234 listings" (already-applied pattern on
                   the parallel .csfloat-home-metric-num just below). */
                ? `View all ${homeTotalListings.toLocaleString()} listings`
                : (homeTotalListings != null && homeTotalListings === 0
                  ? 'List your first item →'
                  : 'View all listings')
            )
          )
        )
      )
    ),
    /* Home marketing — 3-up service tiles. Each tile = a glyph, a 2-3
       word headline, a 1-line blurb. Adapted to sboxmarket's actual
       services (auctions, bargains, non-custodial Steam-trade escrow)
       — no float values / StatTrak / Souvenirs since those are CS-only. */
    routeName === 'home' && h('section', { className: 'csfloat-home-tiles', 'aria-label': 'How sboxmarket trades work' },
      h('div', { className: 'csfloat-home-tiles-inner' },
        h('div', { className: 'csfloat-home-tile' },
          h('div', { className: 'csfloat-home-tile-icon' },
            h(MaterialIcon, { name: 'gavel', size: 22 })
          ),
          h('div', { className: 'csfloat-home-tile-title' }, 'Live Auctions'),
          h('div', { className: 'csfloat-home-tile-blurb' },
            'Bid in real time on rare s&box items. Auto-extends in the final minute so a sniper can’t steal a win.')
        ),
        h('div', { className: 'csfloat-home-tile' },
          h('div', { className: 'csfloat-home-tile-icon' },
            h(MaterialIcon, { name: 'sell', size: 22 })
          ),
          h('div', { className: 'csfloat-home-tile-title' }, 'Bargain Engine'),
          h('div', { className: 'csfloat-home-tile-blurb' },
            'Send or counter offers without ever leaving the listing. No DMs, no haggling threads, just price moves.')
        ),
        h('div', { className: 'csfloat-home-tile' },
          h('div', { className: 'csfloat-home-tile-icon' },
            h(MaterialIcon, { name: 'shield', size: 22 })
          ),
          h('div', { className: 'csfloat-home-tile-title' }, 'Non-Custodial'),
          h('div', { className: 'csfloat-home-tile-blurb' },
            'Skins move seller-to-buyer through Steam. SkinBox never holds custody, so escrow risk is zero.')
        )
      )
    ),
    /* Home marketing — trust metrics band. 4 numbers that read fast and
       reinforce "this marketplace is real". Pulls live homeTotalListings
       where possible; static-but-truthy fallback for the others until a
       /api/stats endpoint surfaces volume + payout time. */
    routeName === 'home' && h('section', { className: 'csfloat-home-metrics', 'aria-label': 'Marketplace trust metrics' },
      h('div', { className: 'csfloat-home-metrics-inner' },
        h('div', { className: 'csfloat-home-metrics-eyebrow' }, 'Trusted by s&box traders'),
        h('div', { className: 'csfloat-home-metrics-row' },
          h('div', { className: 'csfloat-home-metric' },
            h('div', { className: 'csfloat-home-metric-num' },
              homeTotalListings != null ? homeTotalListings.toLocaleString() : '—'),
            h('div', { className: 'csfloat-home-metric-label' }, 'Live listings')
          ),
          h('div', { className: 'csfloat-home-metric' },
            h('div', { className: 'csfloat-home-metric-num' }, '2%'),
            h('div', { className: 'csfloat-home-metric-label' }, 'Platform fee')
          ),
          h('div', { className: 'csfloat-home-metric' },
            h('div', { className: 'csfloat-home-metric-num' }, '< 60s'),
            h('div', { className: 'csfloat-home-metric-label' }, 'Median payout')
          ),
          h('div', { className: 'csfloat-home-metric' },
            h('div', { className: 'csfloat-home-metric-num' }, 'Steam'),
            h('div', { className: 'csfloat-home-metric-label' }, 'OpenID auth')
          )
        )
      )
    ),
    /* Home marketing — 6-step trade journey. Mirrors csfloat’s seamless-
       trading-journey explainer but uses sboxmarket’s actual flow. */
    routeName === 'home' && h('section', { className: 'csfloat-home-journey', 'aria-label': 'How a trade clears' },
      h('div', { className: 'csfloat-home-journey-inner' },
        h('div', { className: 'csfloat-home-journey-left' },
          h('h2', { className: 'csfloat-home-journey-title' }, 'A trade clears in six clean steps.'),
          h('p', { className: 'csfloat-home-journey-blurb' },
            'No middleman, no manual escrow, no Discord deals. Pick the item, confirm in your client, and the funds settle to your wallet automatically.')
        ),
        h('ol', { className: 'csfloat-home-journey-steps', role: 'list' },
          [
            { icon: 'shopping_cart',  label: 'Pick the item', sub: 'Buy now, bargain, or auction bid.' },
            { icon: 'notifications', label: 'Seller notified',  sub: 'Trade request fires within seconds.' },
            { icon: 'send',           label: 'Steam offer sent', sub: 'Bot relays the trade through Steam.' },
            { icon: 'check_circle',   label: 'Confirm in client', sub: 'Both parties accept on Steam mobile.' },
            { icon: 'verified',       label: 'Transfer verified',  sub: 'SkinBox confirms the item changed hands on Steam.' },
            { icon: 'paid',           label: 'Funds released',     sub: 'Seller paid, buyer keeps the item.' }
          ].map((s, i) => h('li', { key: s.icon, className: 'csfloat-home-journey-step' },
            h('span', { className: 'csfloat-home-journey-step-icon' },
              h(MaterialIcon, { name: s.icon, size: 16 })
            ),
            h('div', { className: 'csfloat-home-journey-step-body' },
              h('div', { className: 'csfloat-home-journey-step-label' }, s.label),
              h('div', { className: 'csfloat-home-journey-step-sub' }, s.sub)
            )
          ))
        )
      )
    ),
    /* Home marketing — FAQ accordion. Five concise Q&A pairs that match
       the questions sellers actually ask before listing. <details> for
       progressive enhancement; native disclosure semantics + zero JS. */
    routeName === 'home' && h('section', { className: 'csfloat-home-faq', 'aria-label': 'Frequently asked questions' },
      h('div', { className: 'csfloat-home-faq-inner' },
        h('h2', { className: 'csfloat-home-faq-title' }, 'Frequently asked questions'),
        h('div', { className: 'csfloat-home-faq-list' },
          [
            { q: 'How long until I receive a sold item?',          a: 'A successful trade clears in under a minute once both sides confirm on the Steam mobile app. Most buyers see the item in their inventory in 20–40 seconds.' },
            { q: 'When does the seller see funds?',                a: 'Funds land in the seller wallet the moment Steam confirms the asset transfer. Withdrawals to Stripe-linked cards run on the next payout cycle.' },
            { q: 'What does SkinBox charge?',                       a: 'Buyers pay exactly the listed price — no buyer fee at checkout. Sellers pay a 2% platform fee, deducted from the sale price after confirmed delivery. No surprise add-ons.' },
            { q: 'Is my Steam account safe?',                       a: 'SkinBox uses Valve’s OpenID flow. We never see your password and never request your mobile authenticator. Trades go through your normal Steam offer screen.' },
            { q: 'Can I cancel a listing?',                          a: 'Yes — anytime before a buyer commits. After a Buy Now or accepted Bargain, the trade is locked and proceeds to Steam confirmation.' }
          ].map((row, i) => h('details', { key: i, className: 'csfloat-home-faq-item' },
            h('summary', { className: 'csfloat-home-faq-q' },
              h('span', null, row.q),
              h('span', { className: 'csfloat-home-faq-q-arrow', 'aria-hidden': 'true' },
                h(MaterialIcon, { name: 'expand_more', size: 18 })
              )
            ),
            h('div', { className: 'csfloat-home-faq-a' }, row.a)
          ))
        ),
        // The home FAQ surfaces 5 common questions; the full /faq page has
        // 13 more (deposits, withdrawals, auctions, item-state semantics,
        // etc). Without this tail link, a visitor who didn't find their
        // answer in the 5-question accordion has no obvious next step.
        // Renders below the accordion as a quiet ghost-link, consistent
        // with the rest of the home rail tail-CTAs.
        h('a', {
          className: 'csfloat-home-faq-more',
          href: paths.faq(),
          onClick: (e) => { e.preventDefault(); navigate(paths.faq()); },
          style: {
            display: 'inline-flex', alignItems: 'center', gap: 6,
            marginTop: 18, fontSize: 13, fontWeight: 600,
            color: 'var(--text-secondary)', textDecoration: 'none',
            padding: '6px 0', alignSelf: 'flex-start'
          }
        },
          'View full FAQ',
          h('span', { 'aria-hidden': 'true', style: { transform: 'translateY(-1px)' } }, ' →')
        )
      )
    ),
    /* CSFloat-1:1: the category subnav + the sidebar+grid layout are
       suppressed on the home (`/`) marketing landing. Users land on /,
       see the hero + rail + preview strip, then click "Visit
       Marketplace" to enter /market for the full grid. Mirrors
       csfloat.com's separation between `/` and `/market`. */
    !isFullPage && routeName !== 'home' && h('nav', { className: 'csfloat-subnav', 'aria-label': 'Category filter' },
      h('div', { className: 'csfloat-subnav-inner' },
        CATEGORIES.map(c => h('button', {
          key: c,
          className: `csfloat-subnav-tab ${category === c ? 'active' : ''}`,
          onClick: () => setCategory(c),
          'aria-pressed': category === c
        }, c))
      )
    ),

    /* MAIN LAYOUT (market grid + sidebar) — hidden on full-page routes
       AND on the home (`/`) marketing landing. */
    !isFullPage && routeName !== 'home' && h('main', { id: 'main', className: 'layout', role: 'main' },
      /* Mobile bottom-sheet drawer — backdrop + slide-up sidebar. Visible
         only via .mobile-filters-open body class set above. */
      mobileFiltersOpen && h('div', {
        className: 'mobile-filters-backdrop',
        onClick: () => setMobileFiltersOpen(false),
        'aria-hidden': 'true'
      }),
      /* Floating "Filters" FAB — fixed bottom-right pill, visible only at
         mobile breakpoints via CSS. Mirror of csfloat's mobile filter
         affordance: tap reveals the sidebar as a slide-up overlay. */
      !isFullPage && routeName !== 'home' && h('button', {
        type: 'button',
        className: 'mobile-filters-fab',
        onClick: () => setMobileFiltersOpen(o => !o),
        'aria-label': mobileFiltersOpen ? 'Close filters' : 'Open filters',
        'aria-expanded': mobileFiltersOpen
      },
        h(MaterialIcon, { name: mobileFiltersOpen ? 'close' : 'tune', size: 18 }),
        h('span', null, mobileFiltersOpen ? 'Close' : 'Filters')
      ),
      h('aside', {
        className: 'sidebar' + (mobileFiltersOpen ? ' mobile-drawer-open' : ''),
        'aria-label': 'Filters'
      },
        // Batch 935 — sidebar filter sections use role=radiogroup +
        // role=radio + aria-checked. Each category / rarity row is a
        // mutually-exclusive selector (one wins, the others unwind),
        // which is exactly the radio-group semantic. Prior code was
        // clickable <div>s: not in tab order, not announced as a
        // selector, keyboard users couldn't filter at all.
        h('details', { className: 'filter-section', role: 'radiogroup', 'aria-label': 'Category filter', open: true },
          h('summary', { className: 'filter-title' }, 'Category'),
          CATEGORIES.map(c =>
            h('div', {
              key: c,
              className: `filter-option ${category === c ? 'selected' : ''}`,
              role: 'radio',
              'aria-checked': category === c,
              tabIndex: 0,
              onClick: () => setCategory(c),
              onKeyDown: (e) => {
                if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setCategory(c); }
              }
            },
              c,
              // Batch 1068 — only render the count chip when > 0. Showing
              // "Hats 0 · Jackets 0 · Shirts 0 · …" on a narrowly-filtered
              // view read as broken; empty categories are silent now so
              // the active one's count stands out cleanly.
              c !== 'All' && (catCounts[c] || 0) > 0 && h('span', { className: 'filter-count' }, catCounts[c])
            )
          )
        ),
        h('div', { className: 'filter-divider' }),
        h('details', { className: 'filter-section', role: 'radiogroup', 'aria-label': 'Rarity filter', open: true },
          h('summary', { className: 'filter-title' }, 'Availability'),
          RARITIES.map(r =>
            h('div', {
              key: r,
              className: `filter-option ${rarity === r ? 'selected' : ''}`,
              role: 'radio',
              'aria-checked': rarity === r,
              tabIndex: 0,
              onClick: () => setRarity(r),
              onKeyDown: (e) => {
                if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setRarity(r); }
              }
            },
              r !== 'All' && h('span', {
                className: 'filter-dot',
                style: {
                  background: r === 'Limited' ? 'var(--limited-color)' : r === 'Off-Market' ? 'var(--offmarket-color)' : 'var(--standard-color)',
                  color:      r === 'Limited' ? 'var(--limited-color)' : r === 'Off-Market' ? 'var(--offmarket-color)' : 'var(--standard-color)'
                }
              }),
              r,
              // Match the category-chip pattern: silent on `All` (always full
              // set), silent on a 0 bucket so the filter doesn't read as
              // broken before any listings load. CSFloat shows counts to
              // signal which buckets are populated.
              r !== 'All' && (rarityCounts[r] || 0) > 0 && h('span', { className: 'filter-count' }, rarityCounts[r])
            )
          )
        ),
        h('div', { className: 'filter-divider' }),
        h('details', { className: 'filter-section', open: true },
          h('summary', { className: 'filter-title' }, 'Price Range'),
          h('div', { className: 'price-inputs' },
            // The currencySymbol() prefix ("$" / "CA$" / "€" etc.) keeps
            // the placeholder readable in the operator's currency. Numeric
            // VALUE the user types stays USD — the server filters on the
            // raw USD-anchored amount so a "5" in the box always means
            // "USD 5" regardless of display currency. (Matches the same
            // anchor convention used by the price-range chips below.)
            h('input', { className: 'price-input', placeholder: currencySymbol() + ' Min', value: minPrice, onChange: e => setMinPrice(e.target.value), 'aria-label': 'Minimum price filter (USD-anchored)', inputMode: 'decimal' }),
            h('input', { className: 'price-input', placeholder: currencySymbol() + ' Max', value: maxPrice, onChange: e => setMaxPrice(e.target.value), 'aria-label': 'Maximum price filter (USD-anchored)', inputMode: 'decimal' })
          ),
          /* csfloat-style quick price chips. SBox prices cluster $1-$10 so the
             ranges are scaled accordingly (csfloat uses <$10/$10-50/$50-250/>$250).
             Backend filter ALWAYS uses USD numeric strings (min/max stay as
             "2", "5", "10"); chip LABELS run through fmt() so they read in
             the operator's selected currency (e.g. "<CA$2.74" when CAD). */
          h('div', { className: 'price-chips' },
            [
              { min: '',   max: '2',  fmt: (lo, hi) => '<' + fmt(2) },
              { min: '2',  max: '5',  fmt: (lo, hi) => fmt(2) + '–' + fmt(5) },
              { min: '5',  max: '10', fmt: (lo, hi) => fmt(5) + '–' + fmt(10) },
              { min: '10', max: '',   fmt: (lo, hi) => '>' + fmt(10) }
            ].map(chip => {
              const label = chip.fmt();
              const isActive = String(minPrice || '') === chip.min && String(maxPrice || '') === chip.max;
              return h('button', {
                key: chip.min + '-' + chip.max,
                type: 'button',
                className: `price-chip ${isActive ? 'active' : ''}`,
                onClick: () => { setMinPrice(chip.min); setMaxPrice(chip.max); },
                'aria-pressed': isActive
              }, label);
            })
          )
        ),
        h('button', { className: 'btn-clear', onClick: clearFilters }, 'Clear Filters'),
        // Batch 766 — "Copy filtered URL" lets a user share the current
        // filter/sort/price-range snapshot (e.g. "hats under $10, sorted
        // by newest"). The URL already reflects active filters via
        // batch 95's sync, so we just copy `window.location.href` with a
        // brief visual confirmation. Batch 1068 — hidden on a bare
        // market page with no filters active (the comment promised this
        // but the guard was missing). No point offering to share an
        // empty-filter default URL.
        (search || category !== 'All' || rarity !== 'All' || minPrice || maxPrice || minDiscountPct > 0 || sort !== 'price_desc' || listingTypeFilter !== 'ALL') && h('button', {
          className: 'btn-clear',
          style: { marginTop: 8 },
          onClick: async (e) => {
            const url = window.location.href;
            const btn = e.currentTarget;
            const prev = btn.textContent;
            const flash = () => {
              btn.textContent = 'Copied';
              btn.style.color = 'var(--green)';
              setTimeout(() => { btn.textContent = prev; btn.style.color = ''; }, 1200);
            };
            try {
              if (navigator.clipboard?.writeText) {
                await navigator.clipboard.writeText(url);
                flash();
              } else {
                window.prompt('Copy this URL:', url);
              }
            } catch (_) { window.prompt('Copy this URL:', url); }
          },
          title: 'Copy the current marketplace URL — includes your active filters, sort, and price range so you can share or bookmark this exact view'
        }, '⎘ Copy Filtered URL')
      ),

      // Batch 926 — was a nested <main> which is invalid HTML (the
      // outer element at line ~4708 is the document's single <main>
      // landmark). Changed to a semantically-neutral <div> with the
      // same CSS class so screen readers see one main landmark, not
      // two. Also adds `role="search"` to the search container below
      // so assistive tech lists the search field in its landmark menu.
      h('div', { className: 'main' },
        h('div', {
          className: 'toolbar',
          role: 'toolbar',
          'aria-label': 'Marketplace search, sort, and view controls'
        },
          h('div', { className: 'search-wrap', role: 'search' },
            h('span', { className: 'search-icon' }, h(Icon, { name: 'search', size: 14 })),
            /* csfloat-style "/" keyboard hint chip in the right of the input */
            !searchInput && h('kbd', { className: 'search-kbd', 'aria-hidden': true }, '/'),
            h('input', {
              className: 'search-input',
              /* Boss QA cycle 12 A2 — switched type from "search" to "text".
                 axe rejects aria-expanded + aria-autocomplete on
                 <input type="search"> because ARIA 1.1 doesn't permit the
                 combobox role on a searchbox-implicit input. The site-rendered
                 search-clear button below still gives a manual clear, and
                 enterKeyHint=search keeps the iOS submit affordance. */
              type: 'text',
              enterKeyHint: 'search',
              autoComplete: 'off',
              placeholder: 'Search s&box skins…',
              value: searchInput,
              onChange: e => { setSearchInput(e.target.value); setSuggestOpen(true); setSuggestIdx(-1); },
              onFocus: () => { setSuggestOpen(true); },
              onKeyDown: (e) => {
                // Batch 763 — Escape handler runs regardless of
                // suggestOpen so a user can clear an active search even
                // when the suggestion dropdown has already closed (e.g.
                // they tabbed away and back). Other shortcuts still gate
                // on suggestOpen to avoid hijacking Arrow keys when the
                // dropdown isn't showing.
                if (e.key === 'Escape') {
                  // Only "consume" Escape for dropdown-close when there's
                  // an actually-visible dropdown to close (suggestOpen
                  // alone isn't enough — onFocus sets it true even with
                  // 0 suggestions, which would leave Escape silently
                  // doing nothing visible). Then drop text + blur in
                  // priority order so a single Escape always escapes
                  // back to the page no matter what state the input
                  // was in.
                  if (suggestOpen && suggest.length > 0) {
                    setSuggestOpen(false); setSuggestIdx(-1);
                  } else if (searchInput) {
                    setSearchInput(''); setSearch('');
                    setSuggestOpen(false); setSuggestIdx(-1);
                    try { e.target.blur(); } catch (_) {}
                  } else {
                    // Empty input + no visible dropdown → blur back to
                    // body so global shortcuts (`g m`, `g s`, etc.) work
                    // on the next keystroke. Without this, focus stays
                    // in the search input forever and the user must
                    // click outside to escape.
                    setSuggestOpen(false); setSuggestIdx(-1);
                    try { e.target.blur(); } catch (_) {}
                  }
                  return;
                }
                if (!suggestOpen) return;
                if (e.key === 'ArrowDown' && suggest.length > 0) {
                  e.preventDefault();
                  setSuggestIdx(i => (i + 1) % suggest.length);
                }
                else if (e.key === 'ArrowUp' && suggest.length > 0) {
                  e.preventDefault();
                  setSuggestIdx(i => (i - 1 + suggest.length) % suggest.length);
                }
                else if (e.key === 'Enter') {
                  if (suggestIdx >= 0 && suggest[suggestIdx]) {
                    e.preventDefault();
                    const item = suggest[suggestIdx];
                    pushRecentSearch(searchInput);
                    setSuggestOpen(false); setSuggestIdx(-1);
                    navigate(paths.item(item.id));
                  } else if (searchInput && searchInput.trim().length >= 2) {
                    pushRecentSearch(searchInput);
                    setSuggestOpen(false);
                  }
                }
              },
              'aria-label': 'Search listings'
              /* Boss QA cycle 12 A2 — stripped role="combobox", aria-expanded,
                 aria-autocomplete from <input type="search">. axe-allowed-attr
                 rejects those on the implicit searchbox role. The suggestion
                 dropdowns below still have role="listbox"/option and are
                 keyboard-navigable via the existing onKeyDown handlers, so SR
                 users still get full functionality without invalid ARIA. */
            }),
            searchInput && h('button', {
              className: 'search-clear',
              onClick: () => { setSearchInput(''); setSearch(''); setSuggestOpen(false); },
              title: 'Clear search',
              'aria-label': 'Clear search'
            }, '✕'),
            // Recent-searches dropdown — shown when the input is empty
            // and focused. Clicking a row fills the search + opens the
            // item autocomplete.
            suggestOpen && (!searchInput || searchInput.trim().length < 2) && recentSearches.length > 0 && h('div', {
              className: 'search-suggest',
              role: 'listbox'
            },
              // Batch 922 — header row now includes a "Clear all" affordance
              // for users who've accumulated 5-6 recent searches and want
              // to wipe the history in one click instead of clicking the
              // ✕ on each row. Only rendered when there are 2+ rows —
              // the per-row ✕ is cleaner for a single entry.
              h('div', {
                className: 'search-suggest-heading',
                style: { display: 'flex', alignItems: 'center', justifyContent: 'space-between' }
              },
                h('span', null, 'Recent searches'),
                recentSearches.length >= 2 && h('button', {
                  className: 'search-suggest-forget',
                  onClick: (e) => {
                    e.stopPropagation();
                    setRecentSearches([]);
                    try { localStorage.removeItem('sb_recent_searches'); } catch (_) {}
                  },
                  style: { fontSize: 10, fontWeight: 700, opacity: 0.7 },
                  title: 'Forget every recent search on this device'
                }, 'Clear all')
              ),
              recentSearches.map((q, i) => h('div', {
                key: 'rs-' + i,
                className: 'search-suggest-row recent',
                role: 'option',
                onClick: () => {
                  setSearchInput(q);
                  setSearch(q);
                  setSuggestOpen(true);
                  setSuggestIdx(-1);
                }
              },
                h('span', { className: 'search-suggest-recent-icon' }, '⟲'),
                h('span', { style: { flex: 1, fontSize: 13 } }, q),
                h('button', {
                  className: 'search-suggest-forget',
                  onClick: (e) => {
                    e.stopPropagation();
                    const next = recentSearches.filter(x => x !== q);
                    setRecentSearches(next);
                    try { localStorage.setItem('sb_recent_searches', JSON.stringify(next)); } catch (_) {}
                  },
                  title: 'Forget this search',
                  'aria-label': 'Forget'
                }, '✕')
              ))
            ),
            suggestOpen && suggest.length > 0 && h('div', {
              className: 'search-suggest',
              role: 'listbox'
            },
              suggest.map((item, i) => h('div', {
                key: item.id,
                className: `search-suggest-row ${i === suggestIdx ? 'active' : ''}`,
                role: 'option',
                'aria-selected': i === suggestIdx,
                onMouseEnter: () => setSuggestIdx(i),
                onClick: (e) => {
                  e.preventDefault();
                  setSuggestOpen(false); setSuggestIdx(-1);
                  navigate(paths.item(item.id));
                }
              },
                h('div', { className: 'search-suggest-thumb' },
                  item.imageUrl
                    ? h('img', { src: item.imageUrl, alt: '', loading: 'lazy' })
                    : h('span', { style: { color: 'var(--ink-3)' } }, ({Hats:'◈',Jackets:'▲',Shirts:'■',Pants:'▮',Gloves:'◉',Boots:'▼',Accessories:'◆',Workshop:'❖'})[item.category] || '—')
                ),
                h('div', { className: 'search-suggest-body' },
                  h('div', { className: 'search-suggest-name' }, item.name),
                  h('div', { className: 'search-suggest-meta' },
                    item.category || 'Item',
                    item.rarity && item.rarity !== 'Standard' ? ` · ${item.rarity}` : ''
                  )
                ),
                h('div', { className: 'search-suggest-price' },
                  // Items with no active listings have lowestPrice 0,
                  // not null — render "Not listed" instead of CA$0.00
                  // which read as a free item.
                  (item.lowestPrice != null && parseFloat(item.lowestPrice) > 0)
                    ? fmt(item.lowestPrice)
                    : h('span', { style: { color: 'var(--text-muted)', fontSize: 11 } }, 'Not listed')
                )
              ))
            )
          ),
          h(SortPicker, {
            value: sort,
            options: [
              { value: 'price_desc',  label: 'Price: High to Low',     icon: 'arrow_downward' },
              { value: 'price_asc',   label: 'Price: Low to High',     icon: 'arrow_upward' },
              { value: 'newest',      label: 'Newest first',           icon: 'schedule' },
              { value: 'popularity',  label: 'Most traded',            icon: 'local_fire_department' },
              { value: 'views',       label: 'Most viewed',            icon: 'visibility' },
              { value: 'rarity',      label: 'Lowest supply',          icon: 'diamond' },
              { value: 'discount',    label: 'Biggest discount',       icon: 'sell' },
              { value: 'ending_soon', label: 'Auctions ending soonest', icon: 'gavel' }
            ],
            onChange: (v) => {
              const apply = () => {
                setSort(v);
                try { localStorage.setItem('sb_market_sort', v); } catch (_) {}
              };
              // FLIP animated reorder via the View Transition API. Pairs with
              // viewTransitionName on each grid-card (cards.js). The browser
              // captures pre-state, runs the React update inside the
              // callback, then animates each named element from its old box
              // to its new box. Browsers without API support invoke apply()
              // directly — no animation, no breakage.
              if (typeof document !== 'undefined' && document.startViewTransition) {
                document.startViewTransition(apply);
              } else {
                apply();
              }
            }
          }),
          // Saved searches — dropdown of named filter presets. "Save current"
          // prompts for a name and stashes the full filter state. Picking
          // an entry re-applies every field in one click. Deliberately in
          // the toolbar next to sort so the "save this view" concept is
          // spatially close to the sort controls the user just touched.
          h('div', { style: { display: 'flex', gap: 4, alignItems: 'center' } },
            savedSearches.length > 0 && h('select', {
              className: 'sort-select',
              style: { maxWidth: 180 },
              value: '',
              onChange: (e) => {
                const v = e.target.value;
                if (!v) return;
                if (v === 'del:all') {
                  deleteAllSavedSearchesHandler();
                } else if (v.startsWith('del:')) {
                  deleteSavedSearch(parseInt(v.slice(4), 10));
                } else {
                  const s = savedSearches.find(x => x.id === parseInt(v, 10));
                  if (s) applySavedSearch(s);
                }
                e.target.value = '';
              },
              'aria-label': 'Apply a saved search'
            },
              h('option', { value: '' }, `★ Saved (${savedSearches.length})`),
              savedSearches.map(s => h('option', { key: s.id, value: s.id }, s.name)),
              savedSearches.length > 0 && h('option', { disabled: true, value: '' }, '─── delete ───'),
              savedSearches.map(s => h('option', { key: 'del-' + s.id, value: 'del:' + s.id }, '✕  ' + s.name)),
              // "Clear all" shortcut — parity with watchlist + follows
              // bulk-delete affordances (batch 354).
              savedSearches.length > 1 && h('option', { key: 'del-all', value: 'del:all' }, '✕✕  Clear all')
            ),
            h('button', {
              className: 'btn btn-ghost',
              style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11, display: 'inline-flex', alignItems: 'center', gap: 6 },
              onClick: openSaveSearchDrawer,
              'aria-haspopup': 'dialog',
              'aria-expanded': saveSearchDraft !== null,
              'aria-controls': 'save-search-drawer',
              title: 'Save the current filter combination as a named preset'
            }, h(MaterialIcon, { name: 'bookmark_border', size: 14 }), 'Save search')
          ),
          // Inline save-search drawer — replaces the native `window.prompt`
          // that used to gate naming. Renders directly under the toolbar
          // with a live-updating draft name, Enter-to-save, and Esc-to-cancel.
          saveSearchDraft !== null && h('div', { id: 'save-search-drawer' },
            h(SaveSearchDrawer, {
              initial: saveSearchDraft,
              onCancel: () => setSaveSearchDraft(null),
              onSave: (name) => commitSaveSearch(name)
            })
          ),
          // Listing-type toggle — three buttons, single active. Since
          // batch 137 the filter rides into SQL via ?listingType=… so
          // flipping a chip re-fetches only the matching type. The
          // client-side guard in the `rows` memo stays as
          // belt-and-braces. Defaults to ALL so anon users see the
          // full grid.
          h('div', { className: 'type-toggle', role: 'group', 'aria-label': 'Listing type' },
            [{ id: 'ALL', label: 'All' }, { id: 'BUY_NOW', label: 'Buy Now' }, { id: 'AUCTION', label: 'Auction' }]
              .map(opt => h('button', {
                key: opt.id,
                className: `type-toggle-btn ${listingTypeFilter === opt.id ? 'active' : ''}`,
                onClick: () => setListingTypeFilter(opt.id),
                'aria-pressed': listingTypeFilter === opt.id
              }, opt.label))
          ),
          // Deal-hunter chip. Separated from the type toggle because
          // it's orthogonal — you can stack "Auctions only" + "Deals only"
          // to see undervalued auctions. Purely client-side filter.
          h('button', {
            className: `deals-chip ${dealsOnly ? 'active' : ''}`,
            onClick: () => setDealsOnly(d => !d),
            title: 'Only show listings priced below the Steam Market price',
            'aria-pressed': dealsOnly
          }, '% Deals'),
          // Batch 651 — min-discount chip strip. 0 = inactive (hidden
          // state), other values narrow to listings ≥ that % off Steam.
          // Sits right of the Deals chip so the eye flows "any deal →
          // strong deal" naturally. Chip renders inline as a <select>
          // because putting 5 buttons inline (Any / 10 / 20 / 30 / 50)
          // would push the type toggle to a new row.
          h('select', {
            className: `sort-select discount-select`,
            value: String(minDiscountPct),
            onChange: e => setMinDiscountPct(parseInt(e.target.value, 10) || 0),
            title: 'Only show listings with at least this much discount vs Steam Market',
            'aria-label': 'Minimum discount % vs Steam Market price',
            style: { padding: '6px 10px', fontSize: 11 }
          },
            h('option', { value: '0'  }, 'Any discount'),
            h('option', { value: '5'  }, '≥ 5%  off'),
            h('option', { value: '10' }, '≥ 10% off'),
            h('option', { value: '20' }, '≥ 20% off'),
            h('option', { value: '30' }, '≥ 30% off'),
            h('option', { value: '50' }, '≥ 50% off')
          ),
          // New-in-24h chip. Pairs with Deals; stackable.
          h('button', {
            className: `deals-chip new-chip ${newOnly ? 'active' : ''}`,
            onClick: () => setNewOnly(n => !n),
            title: 'Only show listings posted in the last 24 hours',
            'aria-pressed': newOnly
          }, 'New'),
          // Affordable-only chip (batch 367) — signed-in users can filter
          // to listings under their wallet balance in one click. Hidden
          // for anon viewers (no wallet, no comparison). Stackable with
          // Deals + New + type filters.
          me && wallet && parseFloat(wallet.balance) > 0 && h('button', {
            className: `deals-chip ${affordableOnly ? 'active' : ''}`,
            onClick: () => setAffordableOnly(v => !v),
            // Batch 994 — honour sb_privacy on the "💰 Under $X" chip.
            // Previously leaked the user's balance on every marketplace
            // render — defeats privacy mode (batch 991-993 masked the
            // wallet surfaces; this was the last shoulder-surf vector on
            // the browse page).
            title: privacy
              ? 'Only show listings you can afford with your current wallet balance'
              : `Only show listings ≤ your wallet balance (${fmt(wallet.balance)})`,
            'aria-pressed': affordableOnly
          }, 'Under ', privacy ? '$•••••' : fmt(wallet.balance)),
          // Batch 662 — "Hide my listings" chip. Only renders for a
          // signed-in viewer AND only when the current listings pool
          // actually contains at least one of their listings — an
          // empty-handed seller gets no noisy toggle.
          me && listings.some(l => l?.sellerUserId === me.id) && h('button', {
            className: `deals-chip ${hideMine ? 'active' : ''}`,
            onClick: () => setHideMineP(!hideMine),
            title: hideMine
              ? 'Showing your own listings — click to hide them from the grid'
              : "Hide your own listings so you can size your prices against competitors",
            'aria-pressed': hideMine
          }, hideMine ? 'Mine hidden' : 'Hide mine'),
          /* CSFloat-1:1: quick-filter price chips (Under $5/$20/$50/Premium/
             Limited only) removed from the toolbar. They duplicated the
             sidebar's Price Range slider + Availability rarity filter. */
          h('button', {
            // Codex 17:28Z polish — `loading` flips on while load() runs.
            // Add a `busy` class so CSS can spin the refresh icon, and
            // disable the button while in-flight so a frustrated double-
            // click doesn't queue two fetches. Also show a transient
            // toast on completion so a user clicking Refresh on an
            // already-fresh grid sees something happen instead of
            // wondering whether the click registered.
            className: 'toolbar-refresh' + (loading ? ' busy' : ''),
            onClick: async () => {
              if (loading) return;
              await load(false);
              showToast('Listings refreshed.', 'ok');
            },
            disabled: loading,
            'aria-label': loading ? 'Refreshing listings' : 'Refresh listings',
            'aria-busy': loading ? 'true' : undefined,
            title: loading ? 'Refreshing…' : 'Refresh listings'
          }, h(Icon, { name: 'refresh-cw', size: 16 })),
          h('div', { className: 'view-btns', role: 'group', 'aria-label': 'View mode' },
            h('button', { className: `view-btn ${view === 'grid' ? 'active' : ''}`,  onClick: () => setView('grid'), 'aria-label': 'Grid view',  'aria-pressed': view === 'grid' },  h(Icon, { name: 'grid', size: 16 })),
            h('button', { className: `view-btn ${view === 'table' ? 'active' : ''}`, onClick: () => setView('table'), 'aria-label': 'Table view', 'aria-pressed': view === 'table' }, h(Icon, { name: 'rows', size: 16 }))
          )
        ),
        // Active filter chips — visible whenever a non-default filter is set.
        // CSFloat-1:1 — the toolbar quick-filters (listing type / Deals /
        // New / Affordable) count too, so the row appears and stays
        // complete when only a toolbar toggle is on.
        (search || category !== 'All' || rarity !== 'All' || minPrice || maxPrice || minDiscountPct > 0 ||
         listingTypeFilter !== 'ALL' || dealsOnly || newOnly || affordableOnly) &&
          h('div', { className: 'active-filters' },
            search && h('button', { className: 'filter-chip', onClick: () => setSearch('') },
              'search: ', h('strong', null, '"' + search + '"'), h('span', null, ' ✕')),
            category !== 'All' && h('button', { className: 'filter-chip', onClick: () => setCategory('All') },
              h('strong', null, category), h('span', null, ' ✕')),
            rarity !== 'All' && h('button', { className: 'filter-chip', onClick: () => setRarity('All') },
              h('strong', null, rarity), h('span', null, ' ✕')),
            minPrice && h('button', { className: 'filter-chip', onClick: () => setMinPrice('') },
              '≥ ', currencySymbol(), h('strong', null, minPrice), h('span', null, ' ✕')),
            maxPrice && h('button', { className: 'filter-chip', onClick: () => setMaxPrice('') },
              '≤ ', currencySymbol(), h('strong', null, maxPrice), h('span', null, ' ✕')),
            // Batch 651 — removable min-discount chip.
            minDiscountPct > 0 && h('button', { className: 'filter-chip', onClick: () => setMinDiscountPct(0) },
              '≥ ', h('strong', null, minDiscountPct + '%'), ' off', h('span', null, ' ✕')),
            // CSFloat-1:1 — toolbar quick-filters (listing type / Deals /
            // New / Affordable) also get removable chips, so the chip row
            // is a complete picture of what's narrowing the grid and each
            // is individually clearable without hunting for its toggle.
            listingTypeFilter !== 'ALL' && h('button', { className: 'filter-chip', onClick: () => setListingTypeFilter('ALL') },
              h('strong', null, listingTypeFilter === 'AUCTION' ? 'Auctions only' : 'Buy-now only'), h('span', null, ' ✕')),
            dealsOnly && h('button', { className: 'filter-chip', onClick: () => setDealsOnly(false) },
              h('strong', null, 'Deals'), h('span', null, ' ✕')),
            newOnly && h('button', { className: 'filter-chip', onClick: () => setNewOnly(false) },
              h('strong', null, 'New'), h('span', null, ' ✕')),
            affordableOnly && h('button', { className: 'filter-chip', onClick: () => setAffordableOnly(false) },
              h('strong', null, 'Affordable'), h('span', null, ' ✕')),
            h('button', { className: 'filter-chip clear-all', onClick: clearFilters },
              h('strong', null, 'Clear all'))
          ),
        // Marketplace-at-a-glance trust strip — defined long ago at line ~1364
        // but never mounted. Surfaces 24h volume + 7d volume + sales count
        // + auction count above the listing grid so every visitor sees
        // real liquidity signal, not just the static "X listings found"
        // count. Component handles its own empty-state guard so a fresh
        // marketplace doesn't show "$0 traded".
        routeName === 'market' && h(MarketStatsStrip),
        // Price freshness chip — quiet "Prices updated 23s ago" badge
        // above the listings grid so a buyer can tell the floors aren't
        // stale. Polls /api/items/price-refresh-status every 30s and
        // the in-memory tick advances `timeAgo()` between fetches.
        routeName === 'market' && h('div', {
          style: {
            margin: '12px auto 0', maxWidth: 1260,
            padding: '0 20px',
            display: 'flex', justifyContent: 'flex-start'
          }
        },
          h(PriceFreshnessChip)
        ),
        routeName === 'market' && h('h1', { className: 'visually-hidden' }, 'Marketplace'),
        h('div', {
          className: 'results-meta',
          // Batch 841 — a11y: announce result-count changes to screen
          // readers so they hear "41 listings found in Hats" when the
          // filter narrows. Polite so it doesn't interrupt whatever
          // the user is currently focused on; atomic=true so the full
          // sentence reads as one announcement, not piece-by-piece
          // across the <strong>/<span> fragments.
          role: 'status',
          'aria-live': 'polite',
          'aria-atomic': 'true'
        },
          h('strong', null, dedupedListings.length), ' listings found',
          category !== 'All' && h('span', null, ' in ', h('strong', null, category)),
          search && h('span', null, ' matching ', h('strong', null, `"${search}"`))
        ),
        loading
          ? h('div', { className: 'listing-grid' },
              // Skeleton grid — reserves layout while listings fetch. 12
              // phantom cards match the average page size so the real grid
              // doesn't snap when it arrives.
              Array.from({ length: 12 }).map((_, i) => h('div', { key: 'sk-' + i, className: 'skeleton-card' },
                h('div', { className: 'skeleton-thumb' }),
                h('div', { className: 'skeleton-body' },
                  h('div', { className: 'skeleton-line med' }),
                  h('div', { className: 'skeleton-line short' })
                )
              ))
            )
          : (!hasMore && (listings.length === 0 || dedupedListings.length === 0))
            ? (() => {
                // The terminal empty-state only renders when there's
                // genuinely nothing left to show — `!hasMore` guards it.
                // When `hasMore` is true the server still has unfetched
                // pages, so a 100-row page that the client-side filters
                // (dealsOnly / minDiscountPct / newOnly / affordableOnly
                // / hideMine / listing-type) happen to empty out must
                // NOT replace the grid + Load-more with a dead end —
                // that stranded 100+ matching rows on page 2. The grid
                // branch below handles the "empty page but hasMore"
                // case with a soft hint and a live Load-more button.
                //
                // Batch 654 — empty-state now distinguishes:
                //   - a fetch error (network / 500) → "Couldn't load listings"
                //     with a Retry CTA so the user isn't stuck.
                //   - a type-only filter (just Auction / Buy Now) → specific
                //     "No live auctions right now" copy with a Create-auction
                //     CTA for signed-in users.
                //   - a general filter set (category/rarity/price/search) →
                //     "No listings match your filters" with Clear.
                //   - no filters at all (cold marketplace) → original "be
                //     the first to list" empty copy.
                // Also now triggers when client-side filters (dealsOnly,
                // minDiscountPct, newOnly, affordableOnly) empty the grid —
                // previously those paths rendered nothing instead of an
                // empty-state because the check was on listings, not
                // dedupedListings.
                const hasQueryFilters = !!(search || category !== 'All' || rarity !== 'All' || minPrice || maxPrice || minDiscountPct > 0);
                const onlyTypeFilter  = !hasQueryFilters && listingTypeFilter !== 'ALL';
                const hasClientFilters = !hasQueryFilters && !onlyTypeFilter &&
                    (dealsOnly || newOnly || affordableOnly);
                const isError = !!loadError;
                let title, sub;
                if (isError) {
                  title = "Couldn't load listings";
                  sub   = 'Marketplace is reachable but the request failed. Tap Retry to try again — your filters are kept.';
                } else if (onlyTypeFilter) {
                  title = listingTypeFilter === 'AUCTION' ? 'No live auctions right now' : 'No Buy-Now listings right now';
                  sub   = listingTypeFilter === 'AUCTION'
                    ? 'Check back soon — or list one of your own items as an auction.'
                    : 'Only auctions are active. Flip the filter to All to see them, or list your own Buy-Now item.';
                } else if (hasClientFilters) {
                  title = 'No listings match your filters';
                  sub   = `Try clearing Deals / New / Under ${currencySymbol()}X / discount threshold to broaden the view.`;
                } else if (hasQueryFilters) {
                  title = 'No listings match your filters';
                  sub   = 'Try a broader search, clear the filters, or list one of your own items.';
                } else {
                  title = 'Marketplace is empty';
                  sub   = 'Be the first to list an item — head to Sell to put one of your skins up for grabs.';
                }
                const showClear = !isError && (hasQueryFilters || hasClientFilters || onlyTypeFilter);
                return h('div', { className: 'empty-state' },
                  h('div', { className: 'empty-state-icon' },
                    h(MaterialIcon, { name: isError ? 'cloud_off' : (listingTypeFilter === 'AUCTION' ? 'gavel' : 'inventory_2'), size: 42 })
                  ),
                  h('div', { className: 'empty-state-title' }, title),
                  h('div', { className: 'empty-state-sub' }, sub),
                  h('div', { className: 'empty-state-actions' },
                    isError && h('button', {
                      className: 'btn btn-accent',
                      onClick: () => { setLoadError(null); load(false); }
                    }, 'Retry'),
                    showClear && h('button', {
                      className: 'btn btn-ghost',
                      style: { border: '1px solid var(--border)' },
                      onClick: () => {
                        clearFilters();
                        setListingTypeFilter('ALL');
                        setDealsOnly(false); setNewOnly(false); setAffordableOnly(false);
                      }
                    }, 'Clear Filters'),
                    !isError && me && h('a', { className: 'btn btn-accent', href: paths.sell() }, 'Sell Items')
                  )
                );
              })()
            : h('div', null,
                // Soft hint for the "filters emptied THIS page but the
                // server still has more" case. We only land here with
                // dedupedListings empty when hasMore is true (the
                // terminal empty-state above is guarded by !hasMore), so
                // the Load-more button below stays live and the user can
                // page forward to matching rows instead of hitting a
                // dead end.
                dedupedListings.length === 0
                  ? h('div', { className: 'empty-state', style: { padding: '40px 20px' } },
                      h('div', { className: 'empty-state-icon' },
                        h(MaterialIcon, { name: 'search', size: 42 })
                      ),
                      h('div', { className: 'empty-state-title' }, 'No matches on this page'),
                      h('div', { className: 'empty-state-sub' },
                        'None of the first ' + listings.length + ' listings match your filters — load more to keep looking.')
                    )
                  : view === 'grid'
                  ? h('div', { className: 'listing-grid' },
                      dedupedListings.map(l => h(GridCard, {
                        key: 'item-' + l.item.id,
                        listing: l,
                        listingCount: l.__listingCount,
                        watcherCount: watcherCounts[l.item.id] || 0,
                        onClick: () => openModal(l),
                        starred: watchlist.includes(l.item.id),
                        onToggleStar: toggleStar,
                        meId: me?.id,
                        // Quick-add to cart - shown to everyone so anon visitors
                        // see the action (csfloat parity). Cart state is kept
                        // client-side; backend only enforces auth at checkout.
                        // Anon visitors get the same affordance and the sign-in
                        // happens when they hit Buy at checkout.
                        onAddToCart: addToCart,
                        cartHas: (id) => cart.some(c => c.id === id),
                        // Highlight the typed search string inside the item
                        // name so a user scanning 30 cards can see exactly
                        // which substring matched — the grid already filters
                        // server-side, this surfaces the "why."
                        searchQuery: search
                      }))
                    )
                  : h('table', { className: 'listing-table' },
                      h('thead', null,
                        h('tr', null,
                          h('th', null, 'Item'),
                          h('th', null, 'Availability'),
                          h('th', { className: 'center' }, 'vs Steam'),
                          h('th', { className: 'center' }, 'Trend'),
                          h('th', null, 'Seller'),
                          h('th', null, 'Listed'),
                          h('th', { className: 'right' }, 'Price'),
                          h('th', { className: 'center' }, 'Action'),
                        )
                      ),
                      h('tbody', null,
                        dedupedListings.map(l =>
                          h(ListingRow, {
                            key: 'item-row-' + l.item.id,
                            listing: l,
                            onClick: () => openModal(l),
                            onBuy: handleBuy,
                            meId: me?.id,
                            // Batch 794 — pass explicit tri-state:
                            //   undefined = anon (button route is Sign-in)
                            //   true  = has URL, Buy enabled
                            //   false = signed-in but no URL, Buy disabled
                            hasTradeUrl: me ? !!(me.tradeUrl && String(me.tradeUrl).trim()) : undefined,
                            sellerAvatarUrl: l.sellerUserId ? sellerAvatarUrls[l.sellerUserId] : null,
                            searchQuery: search
                          })
                        )
                      )
                    ),
                // Load-more button — shown when the initial fetch hit the
                // server cap of 100 listings, meaning there may be more.
                // Clicking fetches offset=listings.length with the same
                // filters; response is appended. Rail-style placement
                // below the grid/table matches CSFloat's pattern — not
                // infinite-scroll, so the user stays in control of when
                // more rows load (saves mobile bandwidth).
                hasMore && h('div', {
                  style: {
                    display: 'flex', justifyContent: 'center',
                    margin: '24px 0 12px'
                  }
                },
                  h('button', {
                    className: 'btn btn-ghost',
                    style: {
                      border: '1px solid var(--border)',
                      padding: '10px 28px', fontSize: 13,
                      opacity: loadingMore ? 0.6 : 1,
                      cursor: loadingMore ? 'wait' : 'pointer'
                    },
                    disabled: loadingMore,
                    onClick: loadMore,
                    title: `Load the next 100 listings after the current ${listings.length}`
                  }, loadingMore ? 'Loading…' : `↓ Load more (${listings.length} shown)`)
                )
              )
      )
    ),

    /* Discovery rails removed from /market — the page is now just the
       marketplace grid. Skin-showcase rails (ending-soon, top-deals,
       just-listed, most-watched, most-viewed, hottest, recently-viewed)
       and seller rails were moved off this route to keep it clean;
       revisit if we want a dedicated welcome/home page. */

    /* RECENT SALES TICKER — below the marketplace grid. Each item is a
       clickable anchor to /item/{id} so a buyer who sees something
       they like in the scroll can jump straight to the detail page
       instead of hunting for it in the grid below. */
    /* LIVE SALES ticker — only on the market route. Full-page routes
       (/wallet, /profile, /buy-orders, etc.) shouldn't be cluttered with
       a second scrolling bar. csfloat has no equivalent on those pages. */
    /* CSFloat-1:1 home "Latest sales" panel — only on `/`. Shows up to
       6 most-recent SOLD rows as static cards (not the scrolling ticker
       used on /market). Mirrors csfloat's "Recent activity" widget on
       their home and gives the visitor a real liveness signal. Hidden
       below 3 sales because a single lonely card reads as "marketplace
       is dead" rather than as a liveness signal — better to omit until
       there's enough volume to fill the row. */
    false && routeName === 'home' && recentSales.length >= 3 && h('section', { className: 'csfloat-home-sales', 'aria-label': 'Latest sales' },
      h('div', { className: 'csfloat-home-sales-inner' },
        h('div', { className: 'csfloat-home-sales-header' },
          h('div', { className: 'csfloat-home-sales-title' },
            h('span', { className: 'csfloat-home-sales-pulse', 'aria-hidden': 'true' }),
            'Latest sales'
          ),
          h('a', {
            className: 'csfloat-home-sales-link',
            href: paths.market(),
            onClick: (e) => { e.preventDefault(); navigate(paths.market()); }
          }, 'Browse all →')
        ),
        h('div', { className: 'csfloat-home-sales-row' },
          recentSales.slice(0, 6).map((s, i) => h('a', {
            key: i,
            className: 'csfloat-home-sales-card',
            href: s.listing.item.id ? ('/item/' + s.listing.item.id) : '#',
            onClick: s.listing.item.id ? ((e) => { e.preventDefault(); navigate('/item/' + s.listing.item.id); }) : undefined
          },
            h('div', { className: 'csfloat-home-sales-card-img' },
              h(ItemImage, { item: s.listing.item, variant: 'card' })
            ),
            h('div', { className: 'csfloat-home-sales-card-meta' },
              h('div', { className: 'csfloat-home-sales-card-name' }, s.listing.item.name),
              h('div', { className: 'csfloat-home-sales-card-price' },
                fmt(s.listing.price),
                h('span', { className: 'csfloat-home-sales-card-time' }, s.time)
              )
            )
          ))
        )
      )
    ),
    !isFullPage && routeName !== 'home' && recentSales.length > 0 && h('section', { className: 'ticker-section' },
      h('div', { className: 'ticker' },
        h('div', { className: 'ticker-label' }, 'LIVE SALES'),
        h('div', { className: 'ticker-track' },
          [...recentSales, ...recentSales].map((s, i) => h('a', {
            key: i,
            className: 'ticker-item',
            href: s.listing.item.id ? ('/item/' + s.listing.item.id) : '#',
            style: { textDecoration: 'none', color: 'inherit' },
            title: `${s.listing.item.name} sold for ${fmt(s.listing.price)} · ${s.time}`
          },
            h('div', { className: 'ticker-thumb' }, h(ItemImage, { item: s.listing.item, variant: 'mini' })),
            h('span', { className: 'ticker-name' }, s.listing.item.name),
            h('span', { className: 'ticker-price' }, fmt(s.listing.price)),
            h('span', { className: 'ticker-time' }, s.time)
          ))
        )
      )
    ),

    /* CSFloat parity — Recently Viewed rail on home (below the live
       sales widget) AND on /item/{id} (inside the modal — wired further
       below). Component self-gates to 4+ rows so a fresh visitor never
       sees an awkward 1-card strip. */
    /* CSFloat parity 2026-05-02: csfloat home has NO discovery rails
       (Recently Viewed / Top Sellers / Auctions Ending / Just Listed /
       Hot Right Now). It goes hero → tabs+preview → marketing tiles →
       trust metrics → journey stepper → FAQ → footer. Rails are gated
       off below; components stay defined so a future welcome route
       can re-mount them. */
    false && routeName === 'home' && h(RecentlyViewedRail),
    false && routeName === 'home' && h(TopSellersRail),
    false && routeName === 'home' && h(AuctionsEndingSoonRail),
    false && routeName === 'home' && h(JustListedRail),
    false && routeName === 'home' && h(HottestRail),

    /* ROUTE-DRIVEN PAGES — each one has a real URL. Closing any of them
       navigates back to /. Some (wallet, profile) need the shared wallet
       state, others are self-contained. */
    /* Batch 668 — /wallet previously gated on `wallet && …`, so a null
       wallet (first paint, or a fetchWallet failure swallowed by
       loadWallet's catch) rendered a fully blank page with no spinner,
       error, or retry. Now mirrors the `stall` route just below:
       null → spinner, __error sentinel → error panel + Retry,
       otherwise → WalletModal. Spinner/error are wrapped in an
       InfoModal so the page stays closeable in every state. */
    routeName === 'wallet' && (
      (wallet && !wallet.__error)
        ? h(WalletModal, {
            wallet, transactions, me,
            onClose: () => { setWalletPrefillAmount(null); navigate(paths.market()); },
            onRefresh: loadWallet,
            initialTab: route.params?.tab || walletInitialTab,
            prefillAmount: walletPrefillAmount
          })
        : h(InfoModal, {
            title: 'Wallet',
            onClose: () => { setWalletPrefillAmount(null); navigate(paths.market()); }
          },
            wallet === null
              ? h('div', { className: 'spinner' })
              : h('div', { className: 'empty-inline', style: { padding: '32px 16px' } },
                  h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'cloud_off', size: 26 })),
                  h('h2', { style: { fontSize: 16, fontWeight: 700, color: 'var(--text-primary)', margin: '0 0 6px' } }, "Couldn't load your wallet"),
                  h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 360, margin: '0 auto 16px' } },
                    'The request failed — check your connection and try again.'),
                  h('button', {
                    className: 'btn btn-accent',
                    style: { display: 'inline-flex', minWidth: '220px', maxWidth: '280px', margin: '0 auto', padding: '10px 22px' },
                    onClick: () => { setWallet(null); loadWallet(); }
                  }, 'Retry')
                )
          )
    ),
    routeName === 'stall' && h(InfoModal, {
      title: stallData?.seller?.displayName
        ? `${stallData.seller.displayName}'s Stall`
        : 'Stall',
      onClose: () => closeToPrevious(paths.market())
    },
      stallData === null
        ? h('div', { className: 'spinner' })
        : stallData.__notFound
          ? h('div', { className: 'empty-inline', style: { padding: '32px 16px' } },
              h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'storefront', size: 26 })),
              h('h2', { style: { fontSize: 16, fontWeight: 700, color: 'var(--text-primary)', margin: '0 0 6px' } }, 'Stall not found'),
              h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 360, margin: '0 auto 16px' } },
                "This seller doesn't exist or has deactivated their account."),
              h('a', { className: 'btn btn-accent', href: '/market', style: { display: 'inline-flex', minWidth: '220px', maxWidth: '280px', margin: '0 auto', padding: '10px 22px' } }, 'Back to marketplace')
            )
        : h('div', null,
            // Suspended-account banner (batch 364) — when the seller is
            // banned, show a clear public notice so visitors don't
            // misinterpret the empty listings grid as "dormant stall."
            // Rendered before the block banner so "account suspended"
            // takes priority over "you've blocked this seller."
            stallData.seller?.banned && h('div', {
              style: {
                padding: '12px 16px',
                marginBottom: 16,
                borderRadius: 10,
                background: 'rgba(248,113,113,0.10)',
                border: '1px solid rgba(248,113,113,0.35)',
                color: '#fca5a5',
                fontSize: 13,
                display: 'flex',
                gap: 12,
                alignItems: 'center'
              }
            },
              h('span', { style: { fontSize: 18 } }, '⛔'),
              h('div', { style: { flex: 1 } },
                h('div', { style: { fontWeight: 700, marginBottom: 2 } },
                  'This account has been suspended'),
                h('div', { style: { opacity: 0.85, fontSize: 12 } },
                  'Active listings were removed and the seller cannot post new ones. Reviews and past sales are preserved for record-keeping.')
              )
            ),
            // Block banner (batch 347) — shown at the top of the stall
            // when the viewer has blocked this seller. Reminds them why
            // this seller's listings don't show up on their grid + rails
            // and gives them a one-click unblock without leaving the
            // page. Self-visits never trigger this because the backend
            // forces blockedByViewer=false for viewer==sellerId.
            stallData.blockedByViewer && h('div', {
              style: {
                padding: '12px 16px',
                marginBottom: 16,
                borderRadius: 10,
                background: 'rgba(248,113,113,0.10)',
                border: '1px solid rgba(248,113,113,0.35)',
                color: '#fca5a5',
                fontSize: 13,
                display: 'flex',
                gap: 12,
                alignItems: 'center',
                flexWrap: 'wrap'
              }
            },
              h('div', { style: { flex: 1, minWidth: 180 } },
                h('div', { style: { fontWeight: 700, marginBottom: 2 } },
                  "You've blocked this seller"),
                h('div', { style: { opacity: 0.85, fontSize: 12 } },
                  "Their listings are hidden from your marketplace and rails. You can still view them here.")
              ),
              h('button', {
                className: 'btn btn-ghost',
                style: { padding: '6px 12px', fontSize: 11, border: '1px solid rgba(248,113,113,0.4)', color: 'inherit' },
                onClick: async () => {
                  const { unblockUser } = await import('./api.js');
                  const res = await unblockUser(stallData.seller.id);
                  if (res && (res.error || res.code)) {
                    showToast(res.message || res.error || 'Could not unblock', 'err');
                    return;
                  }
                  // Refresh stall so the banner disappears and the
                  // blockedByViewer flag flips back to false.
                  const fresh = await fetchPublicStall(stallData.seller.id);
                  if (fresh) setStallData(fresh);
                  showToast('Unblocked.', 'ok');
                }
              }, 'Unblock')
            ),
            h('div', { className: 'stall-hero' },
              h('div', { className: 'stall-avatar' },
                h(Avatar, {
                  src: stallData.seller.avatarUrl,
                  name: stallData.seller.displayName || 'Player',
                  alt: stallData.seller.displayName,
                  style: { width: '100%', height: '100%', borderRadius: 'inherit',
                           background: 'transparent', border: 'none', fontSize: 22 }
                })
              ),
              h('div', { style: { flex: 1, minWidth: 0 } },
                h('h2', { className: 'stall-name' },
                  stallData.seller.displayName || 'Player',
                  // Verified trust badge — 10+ completed sales AND either
                  // no reviews OR 4+ star average. Backend computes it so
                  // the threshold is uniform across every surface.
                  stallData.seller.verified && h('span', {
                    className: 'seller-verified',
                    title: `Verified seller · ${stallData.seller.soldCount}+ completed sales`
                  }, 'Verified'),
                  // Batch 726 — small "↗ Steam" link opens the seller's
                  // Steam community profile in a new tab. Trust signal
                  // for buyers who want to eyeball account age + badges
                  // before trusting a new seller. Hidden on banned
                  // accounts (backend returns null profileUrl).
                  stallData.seller.profileUrl && h('a', {
                    href: stallData.seller.profileUrl,
                    target: '_blank',
                    rel: 'nofollow noopener noreferrer',
                    style: { marginLeft: 10, fontSize: 11, color: 'var(--ink-4)',
                             textDecoration: 'none', fontWeight: 500,
                             fontFamily: 'var(--mono)', letterSpacing: '0.08em',
                             textTransform: 'uppercase', verticalAlign: 'middle',
                             whiteSpace: 'nowrap' },
                    title: 'View this seller\'s Steam community profile — check account age, friends, badges, trade history',
                    onClick: (e) => e.stopPropagation()
                  }, '↗ Steam')
                ),
                // S2 Boss-QA — decoration row was a free-form chain of
                // chips ("1 last 30d / Active recently / Last sold 9d ago")
                // that read like text salad. Replaced with an explicit
                // labeled stat-card grid so each value has a clear
                // header. Order: Joined / Last seen / Lifetime sales /
                // Last listed / Last sale / Active listings.
                h('div', { className: 'stall-meta stall-stat-grid' },
                  // Joined — relative under 1y, year for older accounts.
                  h('div', { className: 'stall-stat' },
                    h('div', { className: 'stall-stat-label' }, 'Joined'),
                    h('div', { className: 'stall-stat-val' },
                      stallData.seller.joinedAt
                        ? h('span', { title: new Date(stallData.seller.joinedAt).toLocaleString() },
                            (() => {
                              const ageMs = Date.now() - stallData.seller.joinedAt;
                              if (ageMs < 365 * 24 * 3600_000) return timeAgo(stallData.seller.joinedAt);
                              return new Date(stallData.seller.joinedAt).getFullYear();
                            })())
                        : '—')
                  ),
                  // Last seen on Steam — green if recent.
                  stallData.seller.lastSyncedAt && (() => {
                    const age = Date.now() - stallData.seller.lastSyncedAt;
                    let cls, label;
                    if (age < 24 * 3600_000)          { cls = 'var(--green)'; label = 'Just now'; }
                    else if (age < 7 * 24 * 3600_000) { cls = '#fbbf24';      label = 'This week'; }
                    else                              { cls = 'var(--text-muted)'; label = timeAgo(stallData.seller.lastSyncedAt); }
                    return h('div', { className: 'stall-stat' },
                      h('div', { className: 'stall-stat-label' }, 'Last seen'),
                      h('div', {
                        className: 'stall-stat-val',
                        style: { color: cls },
                        title: 'Last observed on Steam ' + new Date(stallData.seller.lastSyncedAt).toLocaleString()
                      }, label));
                  })(),
                  // Lifetime sales count.
                  stallData.seller.soldCount > 0 && h('div', { className: 'stall-stat' },
                    h('div', { className: 'stall-stat-label' }, 'Lifetime sales'),
                    h('div', { className: 'stall-stat-val' },
                      stallData.seller.soldCount.toLocaleString(),
                      h('span', { className: 'stall-stat-unit' },
                        ' sale', stallData.seller.soldCount === 1 ? '' : 's'))
                  ),
                  // Sold in tightest active window (24h > 7d > 30d).
                  (stallData.seller.soldLast24h > 0 || stallData.seller.soldLast7d > 0 || stallData.seller.soldLast30d > 0) && (() => {
                    const n24 = Number(stallData.seller.soldLast24h) || 0;
                    const n7  = Number(stallData.seller.soldLast7d)  || 0;
                    const n30 = Number(stallData.seller.soldLast30d) || 0;
                    const tightest = n24 > 0
                      ? { n: n24, label: 'Last 24h',  title: `${n24} sale${n24 === 1 ? '' : 's'} in the last 24 hours` }
                      : n7  > 0
                      ? { n: n7,  label: 'Last 7d',   title: `${n7} sale${n7 === 1 ? '' : 's'} in the last 7 days` }
                      : { n: n30, label: 'Last 30d',  title: `${n30} sale${n30 === 1 ? '' : 's'} in the last 30 days` };
                    return h('div', { className: 'stall-stat', title: tightest.title },
                      h('div', { className: 'stall-stat-label' }, tightest.label),
                      h('div', { className: 'stall-stat-val', style: { color: 'var(--green)' } },
                        tightest.n.toLocaleString(),
                        h('span', { className: 'stall-stat-unit' },
                          ' sale', tightest.n === 1 ? '' : 's')));
                  })(),
                  // Last listed (within 30d).
                  stallData.seller.lastListedAt && (Date.now() - stallData.seller.lastListedAt) < 30 * 24 * 3600_000 && h('div', { className: 'stall-stat' },
                    h('div', { className: 'stall-stat-label' }, 'Last listed'),
                    h('div', {
                      className: 'stall-stat-val',
                      title: 'Most recent active listing by this seller: ' + new Date(stallData.seller.lastListedAt).toLocaleString()
                    }, timeAgo(stallData.seller.lastListedAt))
                  ),
                  // Last sale (within 60d).
                  stallData.seller.lastSoldAt && (Date.now() - stallData.seller.lastSoldAt) < 60 * 24 * 3600_000 && h('div', { className: 'stall-stat' },
                    h('div', { className: 'stall-stat-label' }, 'Last sale'),
                    h('div', {
                      className: 'stall-stat-val',
                      style: { color: 'var(--green)' },
                      title: 'Most recent sale closed by this seller: ' + new Date(stallData.seller.lastSoldAt).toLocaleString()
                    }, timeAgo(stallData.seller.lastSoldAt))
                  ),
                  // Active listings + followers — terminal stats.
                  h('div', { className: 'stall-stat' },
                    h('div', { className: 'stall-stat-label' }, 'Active listings'),
                    h('div', { className: 'stall-stat-val' },
                      Number(stallData.count).toLocaleString(),
                      h('span', { className: 'stall-stat-unit' },
                        ' listing', stallData.count === 1 ? '' : 's'))
                  ),
                  stallData.seller.followerCount > 0 && h('div', { className: 'stall-stat' },
                    h('div', { className: 'stall-stat-label' }, 'Followers'),
                    h('div', { className: 'stall-stat-val' },
                      Number(stallData.seller.followerCount).toLocaleString())
                  ),
                  // Avg sale price — derived from the same `stallSold`
                  // sample we already fetch for the 30-day sparkline.
                  // Surfaces "is this a $5-flips seller or a $500-deals
                  // seller?" without a buyer needing to scan the recent-
                  // sales strip manually. Only renders when there's a
                  // meaningful sample (≥3 sales) so a single anchor sale
                  // doesn't fake-anchor the average.
                  Array.isArray(stallSold) && stallSold.length >= 3 && (() => {
                    const prices = stallSold.map(s => parseFloat(s.price)).filter(p => Number.isFinite(p) && p > 0);
                    if (prices.length === 0) return null;
                    const sum = prices.reduce((a, b) => a + b, 0);
                    const avg = sum / prices.length;
                    return h('div', { className: 'stall-stat', title: `Mean sale price across the most recent ${prices.length} closed sales` },
                      h('div', { className: 'stall-stat-label' }, 'Avg sale price'),
                      h('div', { className: 'stall-stat-val' }, fmt(avg))
                    );
                  })(),
                  // Boss QA cycle 2 S2 — typical-response, response-rate,
                  // and typical-ship moved out of the stat grid into the
                  // trust-badges row below. Stats they replace (Joined /
                  // Last seen / Lifetime sales / Last sale / Active
                  // listings) stay above as proper stat cards.
                ),
                // Rating chip — only shows if the seller has at least one
                // review. Uses a simple star-count visual with the average,
                // a humanized verdict label (Excellent/Good/Mixed/Poor),
                // and the review count. The label gives a quick read for
                // a buyer who doesn't want to mentally translate "4.5" into
                // a meaningful trust signal — mirrors CSFloat's verdict
                // chip beside the numeric average.
                stallData.rating && stallData.rating.count > 0 && (() => {
                  const avg = stallData.rating.average || 0;
                  const verdict = avg >= 4.6 ? 'Excellent'
                                : avg >= 4.0 ? 'Good'
                                : avg >= 3.0 ? 'Mixed'
                                :              'Poor';
                  return h('div', { className: 'stall-rating', title: `Average rating across ${stallData.rating.count} review${stallData.rating.count === 1 ? '' : 's'}` },
                    h('span', { className: 'stall-rating-stars' }, '★'.repeat(Math.round(avg))),
                    h('span', { className: 'stall-rating-avg' }, avg.toFixed(1)),
                    h('span', { className: 'stall-rating-count', style: { fontWeight: 600, color: 'var(--text-secondary)', marginLeft: 6 } }, verdict),
                    h('span', { className: 'stall-rating-count' },
                      ` · ${stallData.rating.count} review${stallData.rating.count === 1 ? '' : 's'}`
                    )
                  );
                })(),
                // Breakdown histogram — only worth showing when the seller
                // has ≥3 reviews so the bars aren't misleading.
                stallData.rating && stallData.rating.count >= 3 &&
                  h(RatingBreakdown, { summary: stallData.rating })
              ),
              // Contact button — opens a support ticket pre-filled with
              // the seller's id so CSR can triage a buyer's question about
              // a specific seller. Only shown to signed-in viewers on
              // someone else's stall (can't contact yourself).
              me && me.id !== stallData.seller.id &&
                h(ContactSellerButton, { seller: stallData.seller }),
              // Share button copies the canonical stall URL to the clipboard.
              // Useful for sellers promoting their stall on Discord / Steam
              // groups — CSFloat has the same affordance and users expect it.
              h(ShareStallButton, { userId: stallData.seller.id, sellerName: stallData.seller.displayName, showToast }),
              // Batch 1058 — when the viewer IS the seller, surface a
              // direct affordance to /me/stall. Previously a self-visit
              // showed the same UI as any other visitor (minus the
              // Follow/Block/Report buttons which were already gated),
              // with no indication that MyStall was the management page.
              // Sellers doing a "how does my stall look?" check now get
              // a one-click jump to the edit-pricing / bulk-adjust / hide
              // surface.
              me && me.id === stallData.seller.id && h('a', {
                className: 'stall-share-btn',
                href: paths.mystall(),
                style: {
                  background: 'var(--accent)',
                  color: '#051018',
                  fontWeight: 700,
                  textDecoration: 'none'
                },
                title: 'This is your own stall — jump to MyStall to edit prices, cancel listings, or toggle away-mode.'
              },
                h('span', { className: 'stall-share-icon' }, '—'),
                'Manage stall'
              ),
              // Follow/unfollow — subscribes the viewer to NEW_LISTING
              // notifications from this seller. Only meaningful for other
              // users (can't follow yourself). Shown regardless of sign-in
              // state so signed-out users see the social proof chip; the
              // click path nudges them to sign in if needed.
              me && me.id !== stallData.seller.id &&
                h(FollowSellerButton, { sellerId: stallData.seller.id, sellerName: stallData.seller.displayName || stallData.seller.name, showToast }),
              // Block seller — only on someone else's stall (can't block
              // yourself). Silent for the blocked user; reversible at any
              // time from Profile → Personal → Blocked. Batch 344.
              me && me.id !== stallData.seller.id &&
                h(BlockSellerButton, { sellerId: stallData.seller.id, sellerName: stallData.seller.displayName || stallData.seller.name, showToast }),
              // Report button opens a FRAUD-category support ticket with the
              // seller's id pre-populated. Only shown on someone else's stall
              // (can't report yourself). Opens quietly via prompt so we don't
              // need a full modal for the rare path.
              me && me.id !== stallData.seller.id && h('button', {
                className: 'stall-share-btn',
                style: { opacity: 0.6, border: '1px solid var(--border)' },
                // Batch 835 — opens the inline Report-Seller drawer
                // instead of chaining two window.prompt dialogs.
                onClick: openReportSeller,
                title: 'Report this user to support'
              },
                'Report')
            ),
            stallData.away && h('div', { className: 'stall-away-banner' },
              h('span', { className: 'stall-away-dot' }),
              h('div', null,
                h('div', { className: 'stall-away-title' },
                  stallData.awayUntil
                    ? `Seller is away · back ${new Date(stallData.awayUntil).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}`
                    : 'Seller is away'),
                h('div', { className: 'stall-away-sub' },
                  `All ${stallData.awayCount || 'active'} listings are temporarily hidden until the seller is back. You can still view their stall and leave a review.`)
              )
            ),
            // Stall bio — rendered when the seller has set one. Plain
            // text (service-side sanitized), whitespace-preserved so
            // line breaks in the author's input survive. Owner sees an
            // Edit button below the block.
            h(StallBioBlock, {
              bio: stallData.seller.stallBio,
              canEdit: me && me.id === stallData.seller.id,
              onSaved: () => { fetchPublicStall(route.params.id).then(s => setStallData(s || { __notFound: true })); }
            }),
            stallData.count === 0
              ? h('div', { className: 'empty-inline' },
                  h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: stallData.away ? 'beach_access' : 'inventory_2', size: 26 })),
                  h('div', { style: { fontSize: 14, color: 'var(--text-secondary)', marginBottom: 12 } },
                    stallData.away
                      ? 'The seller will be back soon — check back later or watchlist one of their items.'
                      : 'This seller has no active listings right now.'),
                  // Batch 900 — concrete CTAs on the empty stall.
                  // Dead-end "0 listings" copy gave the user nothing to
                  // do next. Now they can follow the seller for a
                  // new-listing ping, watchlist any recently sold item
                  // as a restock-alert target (via /database), or jump
                  // to the general marketplace.
                  h('div', {
                    style: { display: 'flex', gap: 8, justifyContent: 'center', flexWrap: 'wrap' }
                  },
                    h('a', {
                      className: 'btn btn-accent',
                      style: { padding: '8px 16px', fontSize: 12, textDecoration: 'none' },
                      href: paths.market()
                    }, 'Browse marketplace →'),
                    h('a', {
                      className: 'btn btn-ghost',
                      style: { border: '1px solid var(--border)', padding: '8px 16px', fontSize: 12, textDecoration: 'none' },
                      href: paths.database()
                    }, 'Browse item catalogue')
                  ))
              : (() => {
                  // Filter + sort — both purely client-side on the stall
                  // payload. Rarity chips derive from whatever rarities
                  // are actually represented so empty buttons don't
                  // dangle. Sort mirrors the marketplace toolbar vocab.
                  const rarities = Array.from(new Set(
                    stallData.listings.map(l => l?.item?.rarity || 'Standard')
                  )).sort();
                  const search = (stallSearch || '').trim().toLowerCase();
                  let rows = stallRarity === 'All'
                    ? stallData.listings
                    : stallData.listings.filter(l => (l?.item?.rarity || 'Standard') === stallRarity);
                  if (search.length > 0) {
                    rows = rows.filter(l => {
                      const name = (l?.item?.name || '').toLowerCase();
                      const cat  = (l?.item?.category || '').toLowerCase();
                      return name.includes(search) || cat.includes(search);
                    });
                  }
                  rows = [...rows].sort((a, b) => {
                    if (stallSort === 'price_asc')  return parseFloat(a.price) - parseFloat(b.price);
                    if (stallSort === 'price_desc') return parseFloat(b.price) - parseFloat(a.price);
                    if (stallSort === 'newest')     return (b.listedAt || 0) - (a.listedAt || 0);
                    if (stallSort === 'rarity')     return (a.item?.supply || 0) - (b.item?.supply || 0);
                    return 0;
                  });
                  return h('div', null,
                    (rarities.length > 1 || stallData.listings.length > 6) && h('div', { className: 'stall-filter-row' },
                      h('div', { className: 'stall-filter-chips' },
                        h('button', {
                          className: `wallet-tx-filter-chip ${stallRarity === 'All' ? 'active' : ''}`,
                          onClick: () => setStallRarity('All')
                        }, `All · ${stallData.listings.length}`),
                        rarities.map(r => h('button', {
                          key: r,
                          className: `wallet-tx-filter-chip ${stallRarity === r ? 'active' : ''}`,
                          onClick: () => setStallRarity(r)
                        }, `${r} · ${stallData.listings.filter(l => (l?.item?.rarity || 'Standard') === r).length}`))
                      ),
                      // Name search — only surfaces when the stall is big
                      // enough that eyeballing the grid isn't fast.
                      stallData.listings.length > 10 && h('input', {
                        className: 'price-input',
                        type: 'search',
                        placeholder: 'Filter by name…',
                        value: stallSearch,
                        onChange: e => setStallSearch(e.target.value),
                        style: { minWidth: 160, maxWidth: 220 },
                        'aria-label': 'Filter stall by item name or category'
                      }),
                      h('select', {
                        className: 'sort-select',
                        value: stallSort,
                        onChange: e => setStallSort(e.target.value),
                        'aria-label': 'Sort stall listings'
                      },
                        h('option', { value: 'price_asc' },  'Price: Low → High'),
                        h('option', { value: 'price_desc' }, 'Price: High → Low'),
                        h('option', { value: 'newest' },     'Newest first'),
                        h('option', { value: 'rarity' },     'Lowest supply')
                      )
                    ),
                    rows.length === 0
                      ? h('div', { className: 'empty-inline' },
                          h('div', { style: { fontSize: 13, color: 'var(--text-muted)', marginBottom: search ? 8 : 0 } },
                            search
                              ? `No listings match "${stallSearch}" in this stall.`
                              : 'No listings match this filter.'),
                          search && h('button', {
                            className: 'btn btn-ghost',
                            style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11 },
                            onClick: () => setStallSearch('')
                          }, 'Clear search'))
                      : h('div', { className: 'listing-grid' },
                          rows.map(l => h(GridCard, {
                            key: l.id,
                            listing: l,
                            onClick: () => navigate(paths.item(l.item.id)),
                            starred: watchlist.includes(l.item.id),
                            onToggleStar: toggleStar,
                            onAddToCart: addToCart,
                            cartHas: (id) => cart.some(c => c.id === id)
                          }))
                        )
                  );
                })(),
            // 30-day sales sparkline (batch 362) — buckets the last 200
            // sold listings by calendar day and renders a mini bar chart
            // of daily sale count. Gives a quick visual of seller
            // velocity (spiky = one-off event, steady = active shop,
            // empty = dormant). Only shown when the seller has ≥ 5 sales
            // in the last 30 days — otherwise a near-empty chart is
            // more confusing than useful.
            (() => {
              const now = Date.now();
              const cutoff = now - 30 * 86400_000;
              const recent = (stallSold || []).filter(s => s.soldAt && s.soldAt >= cutoff);
              if (recent.length < 5) return null;
              const days = new Array(30).fill(0);
              const dayMs = 86400_000;
              recent.forEach(s => {
                const idx = 29 - Math.floor((now - s.soldAt) / dayMs);
                if (idx >= 0 && idx < 30) days[idx] += 1;
              });
              const maxCount = Math.max(...days);
              const total = days.reduce((a, b) => a + b, 0);
              const revenue = recent.reduce((sum, s) => sum + (parseFloat(s.price) || 0), 0);
              return h('div', { className: 'stall-recent-sales', style: { marginBottom: 14 } },
                h('div', { className: 'stall-reviews-head', style: { marginBottom: 8 } },
                  h('span', { className: 'section-title-dot' }),
                  `Sales · last 30 days`,
                  h('span', { style: { marginLeft: 'auto', fontSize: 11, color: 'var(--text-muted)', fontWeight: 500 } },
                    `${total} sale${total === 1 ? '' : 's'} · ${fmt(revenue)} gross`)
                ),
                h('div', {
                  style: { display: 'flex', alignItems: 'flex-end', gap: 2, height: 46,
                           padding: '4px 2px', background: 'rgba(148,163,184,0.04)',
                           border: '1px solid var(--border)', borderRadius: 6 },
                  title: 'Daily sale count, oldest (30d ago) on the left, today on the right'
                },
                  days.map((n, i) => h('div', {
                    key: i,
                    style: {
                      flex: 1,
                      height: maxCount === 0 ? '1px' : (Math.max(1, Math.round((n / maxCount) * 38)) + 'px'),
                      background: n === 0 ? 'rgba(148,163,184,0.12)' : 'var(--accent)',
                      opacity: n === 0 ? 0.35 : Math.max(0.55, n / maxCount),
                      borderRadius: 1
                    },
                    title: n === 0 ? 'no sales' : (n + ' sale' + (n === 1 ? '' : 's'))
                  }))
                )
              );
            })(),
            // Recent sales strip — last 10 completed sales by this
            // seller. Pure aggregate: item + price + soldAt, no buyer
            // identities. Builds trust by showing the seller actually
            // moves inventory.
            stallSold.length > 0 && h('div', { className: 'stall-recent-sales' },
              h('div', { className: 'stall-reviews-head' },
                h('span', { className: 'section-title-dot' }),
                stallSoldExpanded
                  ? `Recent sales (${stallSold.length})`
                  : `Recent sales (${Math.min(stallSold.length, 10)})`
              ),
              h('div', {
                className: 'recent-sales-list',
                // Scroll-cap when expanded — same 360px cap as the
                // ItemModal recent-sales list (batch 735) so a seller
                // with hundreds of sales doesn't blow the stall layout.
                style: stallSoldExpanded ? { maxHeight: 360, overflowY: 'auto' } : undefined
              },
                (stallSoldExpanded ? stallSold : stallSold.slice(0, 10))
                  .map(s => h('div', { key: s.listingId, className: 'recent-sales-row' },
                    h('span', { className: 'recent-sales-type' },
                      s.listingType === 'AUCTION' ? 'Auction' : 'Buy now'),
                    // CSFloat-1:1: each sold-row item name is a real link to
                    // the item detail page so a buyer who likes what the
                    // seller has been moving can pivot directly to the item.
                    // Falls back to plain text when the row has no item id.
                    s.item?.id
                      ? h('a', {
                          href: '/item/' + s.item.id,
                          style: {
                            fontSize: 12, color: 'var(--text-secondary)',
                            whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                            textDecoration: 'none'
                          },
                          title: 'View ' + (s.item?.name || 'item') + ' detail'
                        }, s.item?.name || 'Item')
                      : h('span', { style: { fontSize: 12, color: 'var(--text-secondary)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' } },
                          s.item?.name || 'Item'),
                    h('span', { className: 'recent-sales-price' }, fmt(s.price)),
                    h('span', { className: 'recent-sales-time' }, timeAgo(s.soldAt))
                  ))
              ),
              // Batch 752 — show-all expander for the sold list.
              stallSold.length > 10 && h('button', {
                className: 'btn btn-ghost',
                style: {
                  width: '100%', marginTop: 8, padding: '8px 12px', fontSize: 12,
                  border: '1px dashed var(--border)', color: 'var(--text-secondary)'
                },
                onClick: () => setStallSoldExpanded(v => !v)
              }, stallSoldExpanded
                   ? '↑ Collapse to 10 most recent'
                   : `↓ Show all ${stallSold.length} sales`)
            ),
            // "Leave a review" CTA — only shows up when the signed-in viewer
            // has at least one VERIFIED trade with this seller. Every trade
            // in `eligibleTrades` is already filtered server-side so there's
            // nothing more to check here.
            eligibleTrades.length > 0 && h('div', { className: 'stall-review-cta' },
              h('div', { className: 'stall-review-cta-head' },
                h('span', { className: 'section-title-dot' }),
                reviewTradeId ? 'Leave a review' : 'Leave a review'
              ),
              !reviewTradeId && h('div', { className: 'stall-review-cta-rows' },
                eligibleTrades.slice(0, 6).map(t => h('div', { key: t.tradeId, className: 'stall-review-cta-row' },
                  h('div', { style: { flex: 1, minWidth: 0 } },
                    h('div', { className: 'stall-review-cta-item' }, t.itemName || 'Trade'),
                    h('div', { className: 'stall-review-cta-sub' },
                      fmt(t.price || 0),
                      ' · ', new Date(t.settledAt || Date.now()).toLocaleDateString())
                  ),
                  t.reviewed
                    ? h('span', { className: 'stall-review-done' }, 'Reviewed')
                    : h('button', {
                        className: 'btn btn-primary-outline',
                        onClick: () => { setReviewTradeId(t.tradeId); setReviewStars(5); setReviewText(''); }
                      }, 'Write review')
                ))
              ),
              reviewTradeId && h('div', { className: 'stall-review-form' },
                h('div', { className: 'stall-review-stars-picker' },
                  [1, 2, 3, 4, 5].map(n => h('button', {
                    key: n,
                    type: 'button',
                    className: `star-btn ${reviewStars >= n ? 'on' : ''}`,
                    onClick: () => setReviewStars(n),
                    'aria-label': `${n} star${n === 1 ? '' : 's'}`
                  }, reviewStars >= n ? '★' : '☆'))
                ),
                h('textarea', {
                  className: 'stall-review-text',
                  value: reviewText,
                  maxLength: 500,
                  placeholder: 'Tell buyers what the transaction was like (optional, 500 chars max)',
                  onChange: (e) => setReviewText(e.target.value)
                }),
                h('div', { className: 'stall-review-actions' },
                  h('button', {
                    className: 'btn btn-ghost',
                    onClick: () => setReviewTradeId(null),
                    disabled: reviewBusy
                  }, 'Cancel'),
                  h('button', {
                    className: 'btn btn-primary',
                    onClick: submitStallReview,
                    disabled: reviewBusy
                  }, reviewBusy ? 'Sending…' : 'Submit review')
                )
              )
            ),
            // Recent reviews strip — only shows when the seller has feedback.
            // Reviews are trade-anchored so every entry is a real buyer who
            // actually traded with this user (see ReviewService.leaveReview).
            stallReviews && stallReviews.length > 0 && (() => {
              // Star filter first, then sort. Sorting the post-filter set
              // keeps the top row authoritative ("most helpful 5-star review")
              // and avoids the filter ever hiding the sort's top pick.
              const starFiltered = stallStarFilter > 0
                ? stallReviews.filter(r => r.rating === stallStarFilter)
                : stallReviews;
              const displayReviews = [...starFiltered].sort((a, b) => {
                if (stallReviewSort === 'helpful') {
                  const ah = Number(a.helpfulCount || 0);
                  const bh = Number(b.helpfulCount || 0);
                  if (ah !== bh) return bh - ah;
                  return (b.createdAt || 0) - (a.createdAt || 0);  // tie-break newest
                }
                if (stallReviewSort === 'highest') {
                  if ((b.rating || 0) !== (a.rating || 0)) return (b.rating || 0) - (a.rating || 0);
                  return (b.createdAt || 0) - (a.createdAt || 0);
                }
                if (stallReviewSort === 'lowest') {
                  if ((a.rating || 0) !== (b.rating || 0)) return (a.rating || 0) - (b.rating || 0);
                  return (b.createdAt || 0) - (a.createdAt || 0);
                }
                return (b.createdAt || 0) - (a.createdAt || 0);  // default newest
              });
              return h('div', { className: 'stall-reviews' },
                h('div', { className: 'stall-reviews-head' },
                  h('span', { className: 'section-title-dot' }),
                  `Recent reviews (${stallReviews.length})`
                ),
                // Star filter chips + sort dropdown. Hidden when there's
                // nothing to filter (just one review makes the filter noise).
                stallReviews.length >= 3 && h('div', {
                  className: 'stall-reviews-filter',
                  style: { display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }
                },
                  h('div', { style: { display: 'flex', gap: 4, flexWrap: 'wrap' } },
                    [0, 5, 4, 3, 2, 1].map(n => h('button', {
                      key: n,
                      className: `wallet-tx-filter-chip ${stallStarFilter === n ? 'active' : ''}`,
                      onClick: () => setStallStarFilter(n)
                    }, n === 0 ? 'All' : `${n}★`))
                  ),
                  h('div', { style: { flex: 1 } }),
                  h('select', {
                    className: 'sort-select',
                    value: stallReviewSort,
                    onChange: e => setStallReviewSortPersist(e.target.value),
                    style: { fontSize: 12, minWidth: 160 },
                    'aria-label': 'Sort reviews',
                    title: 'Choose how to order the review list'
                  },
                    h('option', { value: 'newest' },  'Newest first'),
                    h('option', { value: 'helpful' }, 'Most helpful'),
                    h('option', { value: 'highest' }, 'Highest rating'),
                    h('option', { value: 'lowest' },  'Lowest rating')
                  )
                ),
                h('div', { className: 'stall-reviews-list' },
                  displayReviews.length === 0
                    ? h('div', { className: 'empty-inline' },
                        h('div', { style: { fontSize: 13, color: 'var(--text-muted)' } },
                          `No ${stallStarFilter}★ reviews yet.`))
                    : (stallReviewsExpanded ? displayReviews : displayReviews.slice(0, 10))
                        .map(r => h(StallReviewRow, {
                          key: r.id,
                          review: r,
                          me,
                          isOwner: me && stallData?.seller?.id === me.id,
                          // `r.mine` is the server-computed "this is the
                          // viewer's own review" flag — the API no longer
                          // ships the reviewer's raw fromUserId (2026-05-21).
                          isAuthor: !!r.mine,
                          onSaved: async () => {
                            const fresh = await fetchReviewsForUser(stallData.seller.id);
                            setStallReviews(fresh);
                          }
                        }))
                ),
                // Batch 751 — "Show all N" / "Collapse" expander when
                // the filtered list exceeds the default 10-row strip.
                displayReviews.length > 10 && h('button', {
                  className: 'btn btn-ghost',
                  style: {
                    width: '100%', marginTop: 8, padding: '8px 12px', fontSize: 12,
                    border: '1px dashed var(--border)', color: 'var(--text-secondary)'
                  },
                  onClick: () => setStallReviewsExpanded(v => !v)
                }, stallReviewsExpanded
                     ? '↑ Collapse to 10 most relevant'
                     : `↓ Show all ${displayReviews.length} reviews`)
              );
            })(),
            // S1 Boss-QA — trust + discovery panel that fills the empty
            // bottom of the page even when the seller has only 1
            // listing / 0 reviews / 1 sold. Replaces the huge dead
            // void with three cards: trust signals, recent reviews
            // teaser (when none yet), and a "Discover other sellers"
            // CTA that points back to the main marketplace. Always
            // renders (no gating) so a stall page never bottoms out.
            h('div', { className: 'stall-trust-panel' },
              // Trust column — verified badge or "Building reputation"
              // copy + key trust stats from the seller record.
              h('div', { className: 'stall-trust-card' },
                h('div', { className: 'stall-trust-card-head' },
                  h('span', { className: 'section-title-dot' }),
                  'Trust signals'),
                h('ul', { className: 'stall-trust-list' },
                  h('li', null,
                    h('span', { className: 'stall-trust-icon' },
                      stallData.seller.verified ? '✓' : '·'),
                    h('span', null,
                      stallData.seller.verified
                        ? h('strong', null, 'Verified seller — 10+ completed sales at 4+ stars')
                        : (stallData.seller.soldCount > 0
                            ? `${stallData.seller.soldCount} completed sale${stallData.seller.soldCount === 1 ? '' : 's'} so far — building toward Verified`
                            : 'New seller — no completed sales yet'))
                  ),
                  stallData.seller.profileUrl && h('li', null,
                    h('span', { className: 'stall-trust-icon' }, '↗'),
                    h('span', null,
                      'Steam profile linked · ',
                      h('a', {
                        href: stallData.seller.profileUrl,
                        target: '_blank',
                        rel: 'nofollow noopener noreferrer'
                      }, 'view on Steam'))
                  ),
                  stallData.seller.joinedAt && h('li', null,
                    h('span', { className: 'stall-trust-icon' }, '·'),
                    h('span', null, 'Joined SkinBox ', timeAgo(stallData.seller.joinedAt))
                  ),
                  stallData.seller.responseRatePct != null && h('li', null,
                    h('span', { className: 'stall-trust-icon' }, '·'),
                    h('span', null,
                      Math.round(stallData.seller.responseRatePct), '% offer response rate')
                  ),
                  h('li', null,
                    h('span', { className: 'stall-trust-icon' }, '·'),
                    h('span', null, 'Every trade is escrow-protected by SkinBox until both sides confirm')
                  )
                )
              ),
              // Reviews teaser — shown only when there are no reviews
              // yet, so the page doesn't bottom out at "no content."
              (!stallReviews || stallReviews.length === 0) && h('div', { className: 'stall-trust-card' },
                h('div', { className: 'stall-trust-card-head' },
                  h('span', { className: 'section-title-dot' }),
                  'Reviews'),
                h('div', { className: 'stall-trust-empty' },
                  h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', marginBottom: 8, fontWeight: 600 } },
                    'No reviews yet'),
                  h('div', { style: { fontSize: 12, color: 'var(--text-muted)', lineHeight: 1.55 } },
                    me && me.id !== stallData.seller.id && eligibleTrades.length > 0
                      ? "You've traded with this seller — leave the first review above to help future buyers."
                      : "Reviews appear here once buyers complete a trade and rate the seller. Check back after your first purchase.")
                )
              ),
              // Discover other sellers — outbound CTA so a buyer who
              // bottoms out on a sparse stall has somewhere to go next.
              h('div', { className: 'stall-trust-card' },
                h('div', { className: 'stall-trust-card-head' },
                  h('span', { className: 'section-title-dot' }),
                  'Keep browsing'),
                h('div', { style: { display: 'flex', flexDirection: 'column', gap: 8 } },
                  h('a', {
                    className: 'btn btn-accent',
                    style: { padding: '10px 14px', fontSize: 13, textDecoration: 'none', textAlign: 'center' },
                    href: paths.market()
                  }, 'Browse all listings →'),
                  h('a', {
                    className: 'btn btn-ghost',
                    style: { padding: '10px 14px', fontSize: 13, textDecoration: 'none', textAlign: 'center', border: '1px solid var(--border)' },
                    href: paths.database()
                  }, 'Open the catalogue'),
                  h('a', {
                    className: 'btn btn-ghost',
                    style: { padding: '10px 14px', fontSize: 13, textDecoration: 'none', textAlign: 'center', border: '1px solid var(--border)' },
                    href: paths.help() + '#trust'
                  }, 'How escrow works')
                )
              )
            )
          )
    ),
    routeName === 'notfound'      && (() => {
      // Branded empty state per route family — `/stall/...`, `/loadout/...`,
      // and `/item/...` that fail the SPA router's numeric-id guard
      // (router.js requires `\d+` to keep the no-API-noise invariant for
      // garbage segments like `/stall/abc`) used to land on a generic
      // "Page not found" panel. That read as a bug — the user knew they
      // typed a stall URL but got a market-flavored 404. Detect the URL
      // prefix and surface the right icon + heading + copy so the page
      // matches the user's intent. The recovery rail + button row stay
      // identical across all flavors.
      const path = route.path || '';
      let nfTitle = 'Page not found';
      let nfIconNode = h(Icon, { name: 'search', size: 32 });
      let nfHeading = '404 · nothing here';
      let nfBody = "The URL you followed doesn't match any page. Head back to the marketplace or try the Help Center.";
      if (path.startsWith('/stall/')) {
        nfTitle = 'Stall not found';
        nfIconNode = h(MaterialIcon, { name: 'storefront', size: 32 });
        nfHeading = 'Stall not found';
        nfBody = "We couldn't find a seller at that URL. The stall may have been deactivated, or the link is mistyped.";
      } else if (path.startsWith('/loadout/')) {
        nfTitle = 'Loadout not found';
        nfIconNode = h(MaterialIcon, { name: 'palette', size: 32 });
        nfHeading = 'Loadout not found';
        nfBody = "This loadout has been deleted, made private, or never existed. Try the Loadout Lab to browse public picks.";
      } else if (path.startsWith('/item/')) {
        nfTitle = 'Item not found';
        nfIconNode = h(MaterialIcon, { name: 'search_off', size: 32 });
        nfHeading = 'Item not found';
        nfBody = "The item you were looking for has been removed or never existed. It may have been merged into another entry by the catalogue sync.";
      }
      return h(InfoModal,       { title: nfTitle, onClose: () => navigate(paths.market()) },
      h('div', { className: 'empty-inline', style: { position: 'relative', overflow: 'hidden', paddingTop: 100 } },
        // Batch 641 — CSFloat Visual Manual §32 parity: scatter small
        // item thumbnails across the top as a decorative element. We
        // pull from the current market `listings` pool (already
        // loaded in the SPA shell) so there's no extra fetch on a
        // 404. Each image gets a random-but-deterministic-ish
        // position, size, and opacity so the layout doesn't reshuffle
        // on re-renders. Hidden on narrow viewports where the clutter
        // would overlap the main copy.
        listings && listings.length >= 4 && h('div', {
          style: {
            position: 'absolute', inset: '0 0 auto 0', height: 100,
            pointerEvents: 'none', overflow: 'hidden'
          },
          'aria-hidden': 'true'
        },
          listings.slice(0, 8).map((l, i) => {
            const item = l?.item;
            if (!item) return null;
            const hash = ((item.id || i) * 2654435761) & 0x7fffffff;
            const left    = (hash % 86) + 4;             // 4%..90%
            const top     = ((hash >> 7) % 60) + 4;      // 4..64px
            const size    = 36 + ((hash >> 12) % 42);    // 36..78px
            const opacity = 0.20 + ((hash >> 17) % 25) / 100;  // 0.20..0.45
            const rot     = ((hash >> 20) % 25) - 12;    // -12..12 deg
            return h('div', {
              key: 'nf-' + (item.id || i),
              style: {
                position: 'absolute',
                left: left + '%', top: top + 'px',
                width: size, height: size,
                opacity, transform: `rotate(${rot}deg)`,
                borderRadius: 6,
                background: `url(${item.imageUrl || ''}) center/cover no-repeat, radial-gradient(circle, ${item.accentColor || '#1ea5ff'}22, transparent)`
              }
            });
          })
        ),
        h('div', { className: 'empty-icon', style: { position: 'relative', zIndex: 1 } }, nfIconNode),
        h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6, position: 'relative', zIndex: 1 } }, nfHeading),
        h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 420, margin: '0 auto 18px', position: 'relative', zIndex: 1 } },
          nfBody),
        h('div', { style: { display: 'flex', gap: 10, justifyContent: 'center', position: 'relative', zIndex: 1 } },
          h('a', { className: 'btn btn-accent', href: paths.market() }, 'Back to Market'),
          h('a', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)' }, href: paths.help() }, 'Help Center')
        ),
        // Batch 1021 — recovery rail. A user who lands on /item/99999 (dead
        // link from an old share, expired stall URL, etc.) gets their last
        // few clicked items surfaced so they can resume where they left
        // off. Refactored 2026-05-13 to use the shared RecentlyViewedPills
        // component so the cache-staleness refetch (Bug #2) covers both
        // this rail and the empty-cart pills with a single code path.
        h(RecentlyViewedPills, { kind: 'recovery', privacy })
      )
    );
    })(),
    routeName === 'help'          && h(HelpModal,       { onClose: () => navigate(paths.market()) }),
    routeName === 'cart'          && h(InfoModal,       { title: cartCount > 0 ? `Cart (${cartCount})` : 'Cart', onClose: () => navigate(paths.market()) },
      cartCount === 0
        ? h('div', null,
            h('div', { className: 'empty-inline' },
              h('div', { className: 'empty-icon' }, h(Icon, { name: 'cart', size: 32 })),
              h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
                "You don't have any items in your cart!"),
              h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 380, margin: '0 auto 16px' } },
                'Browse the marketplace, tap the + on any listing card to queue it up, then come back here to check out.'),
              h('div', { style: { display: 'flex', gap: 10, justifyContent: 'center', flexWrap: 'wrap' } },
                h('a', { className: 'btn btn-accent', href: '/market' }, 'Browse marketplace →'),
                h('a', {
                  className: 'btn btn-ghost',
                  style: { border: '1px solid var(--border)' },
                  href: '/market?sort=discount'
                }, '% Top deals')
              )
            ),
            // Recently-viewed nudge (batch 427). When the cart is empty,
            // surface the last few items the user clicked into so they
            // can re-find what they were considering. Refactored
            // 2026-05-13 to use the shared RecentlyViewedPills component
            // so the cache-staleness refetch (Bug #2) covers both this
            // rail and the item-not-found recovery pills with a single
            // code path. Component is silent for fresh visitors.
            h(RecentlyViewedPills, { kind: 'cart', privacy }),
            /* Empty-cart trending rail — fills the dead space below the
               CTA + recently-viewed pills with live marketplace listings.
               Mirrors csfloat's empty-cart "Top Deals" surfacing so a
               returning buyer never lands on a blank page. Uses the
               already-fetched listings; renders six cheapest-first.
               Hides when listings haven't loaded yet to avoid flash.
               Boss QA cycle 4 P1.3 — reuse the canonical /market GridCard
               component so cart cards inherit every grid-card affordance
               (rarity stripe, watchlist star, "Add to cart" hover, view
               count, price-trend chevron) instead of a bespoke card class
               that drifted from the marketplace look. */
            listings && listings.length > 0 && h('section', {
              className: 'csfloat-empty-cart-rail',
              'aria-label': 'Browse trending listings'
            },
              h('div', { className: 'csfloat-empty-cart-rail-head' },
                h('h2', { className: 'csfloat-empty-cart-rail-title' }, 'Trending right now'),
                h('a', {
                  className: 'csfloat-empty-cart-rail-link',
                  href: '/market',
                  onClick: (e) => { e.preventDefault(); navigate('/market'); }
                }, 'Browse all →')
              ),
              h('div', { className: 'csfloat-empty-cart-rail-grid' },
                listings.slice(0, 6).map(l => l && l.item && h(GridCard, {
                  key: 'ec-' + l.id,
                  listing: l,
                  starred: Array.isArray(watchlist) ? watchlist.includes(l.item.id) : false,
                  onToggleStar: toggleStar,
                  onClick: () => navigate('/item/' + l.item.id),
                  onAddToCart: addToCart,
                  cartHas: (id) => cart.some(c => c.id === id),
                  meId: me?.id
                }))
              )
            )
          )
        : h('div', null,
            // Batch 790 — same trade-URL preflight as the cart-confirm
            // modal, but on the main cart page so a buyer with items
            // in the cart sees the blocker without clicking Buy. Also
            // fires when the profile data is still loading (me == null
            // on anon is handled earlier; this is authenticated-but-
            // no-URL case).
            me && !(me.tradeUrl && String(me.tradeUrl).trim()) && h('div', {
              style: {
                padding: 10, marginBottom: 12, borderRadius: 8,
                background: 'rgba(250,204,21,0.1)',
                border: '1px solid rgba(250,204,21,0.4)',
                color: '#fde68a',
                display: 'flex', alignItems: 'center', gap: 10, fontSize: 12, fontWeight: 600
              }
            },
              h('span', { style: { fontSize: 14 } }, '⚠'),
              h('div', { style: { flex: 1 } },
                h('strong', null, 'Steam trade URL required · '),
                "sellers can't ship without it. Add yours in Profile before checking out."),
              h('a', {
                href: '/profile',
                className: 'btn btn-accent',
                style: { padding: '5px 12px', fontSize: 11, textDecoration: 'none' }
              }, 'Open Profile')
            ),
            // Unavailable-rows banner — fires when the bulk freshness
            // probe came back with at least one listing that is no
            // longer ACTIVE. Offers a one-click cleanup so the buyer
            // doesn't have to hunt the ✕ on each stale row.
            cartHasStale && h('div', {
              style: {
                padding: 10, marginBottom: 12, borderRadius: 8,
                background: 'rgba(248,113,113,0.12)',
                border: '1px solid rgba(248,113,113,0.35)',
                color: 'var(--red)',
                display: 'flex', alignItems: 'center', gap: 10, fontSize: 12, fontWeight: 700
              }
            },
              h('span', { style: { fontSize: 14 } }, '⚠'),
              h('div', { style: { flex: 1 } },
                'Some rows in your cart are no longer available. Checkout is paused until you remove them.'),
              h('button', {
                className: 'btn btn-ghost',
                style: { border: '1px solid rgba(248,113,113,0.4)', color: 'var(--red)', padding: '6px 12px', fontSize: 11 },
                onClick: removeStaleCartRows
              }, 'Remove unavailable')
            ),
            h('div', { className: 'cart-grid' },
            h('div', { className: 'cart-list' },
              cart.map(it => {
                const fresh = cartFreshness[it.id];
                const stale = fresh && !fresh.active;
                const newPrice = fresh && fresh.active && fresh.price != null
                  ? parseFloat(fresh.price) : null;
                const priceMoved = newPrice != null &&
                  Math.abs(newPrice - parseFloat(it.price)) > 0.005;
                return h('div', {
                    key: it.id,
                    className: 'cart-row',
                    role: 'link',
                    tabIndex: 0,
                    title: `Open ${it.name}`,
                    'aria-label': `Open ${it.name} item page`,
                    style: { cursor: 'pointer', ...(stale ? { opacity: 0.55 } : {}) },
                    // Click anywhere on the row navigates to the item
                    // detail page. Seller-name link + X-remove button
                    // stop propagation so they keep their own behavior.
                    onClick: (e) => { if (e.target.closest('a, button')) return; if (it.itemId) navigate(paths.item(it.itemId)); },
                    onKeyDown: (e) => { if ((e.key === 'Enter' || e.key === ' ') && it.itemId) { e.preventDefault(); navigate(paths.item(it.itemId)); } }
                  },
                  h('div', { className: 'cart-thumb' }, it.thumb
                    ? h('img', { src: it.thumb, alt: it.name, loading: 'lazy', decoding: 'async' })
                    : h('span', null, '—')),
                  h('div', { className: 'cart-info' },
                    h('div', { className: 'cart-name' }, it.name,
                      stale && h('span', {
                        style: { marginLeft: 8, fontSize: 10, fontWeight: 700, color: 'var(--red)', background: 'rgba(248,113,113,0.15)', padding: '2px 6px', borderRadius: 4 }
                      }, 'NO LONGER AVAILABLE'),
                      priceMoved && h('span', {
                        style: { marginLeft: 8, fontSize: 10, fontWeight: 700, color: '#fbbf24', background: 'rgba(251,191,36,0.15)', padding: '2px 6px', borderRadius: 4 },
                        title: `Seller changed the price from ${fmt(it.price)} to ${fmt(newPrice)}`
                      }, `PRICE NOW ${fmt(newPrice)}`)
                    ),
                    // Batch 804 — seller attribution on cart rows. Before
                    // this the row just said "Listing #N" with no hint of
                    // which seller the buyer is about to transact with.
                    // A shopper might have 5 cart items from 3 different
                    // sellers and want to confirm the stall pages before
                    // checkout. Links to /stall/:id when it's a real
                    // user; plain text for system listings (no stall
                    // page). Null seller snapshot (pre-batch-804 cart
                    // rows cached in localStorage) silently falls back.
                    h('div', { className: 'cart-id' },
                      'Listing #' + it.id,
                      it.sellerName && h('span', null,
                        ' · by ',
                        it.sellerUserId
                          ? h('a', {
                              href: paths.stall(it.sellerUserId),
                              style: { color: 'var(--accent)', textDecoration: 'none', fontWeight: 600 },
                              onClick: (e) => e.stopPropagation(),
                              title: `View ${it.sellerName}'s stall`
                            }, it.sellerName)
                          : h('span', { style: { color: 'var(--text-secondary)', fontWeight: 600 } }, it.sellerName)
                      )
                    ),
                    /* CSFloat-1:1: per-row float bar in the cart, mirroring
                       the grid card affordance so the cart visually carries
                       the same rarity/wear language. Decorative only. */
                    h('div', { className: 'cart-floatbar', 'aria-hidden': true },
                      h('div', { className: 'cart-floatbar-thumb',
                                 style: { left: ((Number(it.id || 1) * 19) % 88 + 6) + '%' } })
                    )
                  ),
                  h('div', { className: 'cart-price' },
                    fmt(newPrice != null ? newPrice : it.price)
                  ),
                  // Batch 755 — per-row "Save for later" (move this one
                  // cart row to the watchlist + drop from cart). Silent
                  // when the item is already watchlisted or when the row
                  // has no itemId (shouldn't happen for live listings but
                  // defensive). Complements the bulk "Move to watchlist"
                  // below; users with a mixed cart (some to-buy, some
                  // to-watch) can now prune without clearing everything.
                  it.itemId && !watchlist.includes(it.itemId) && h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid var(--border)', color: 'var(--text-muted)', padding: '6px 10px', fontSize: 11 },
                    title: 'Move to watchlist — keep tracking the price without holding it in your cart',
                    onClick: () => {
                      // toggleStar handles both anon (localStorage)
                      // and signed-in (server starItem) paths. Calling
                      // setWatchlist directly here used to skip the
                      // server hop, so a signed-in user's "Save for
                      // later" silently dropped on next page load.
                      toggleStar(it.itemId);
                      removeFromCart(it.id);
                      showToast('Moved to watchlist', 'ok');
                    }
                  }, '♡ Save'),
                  h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '6px 10px', fontSize: 11 },
                    // Batch 779 — describe the target item so a screen
                    // reader user scanning a cart of ✕ buttons hears
                    // "Remove Black Modern Watch from cart" on focus,
                    // not five identical "button" labels.
                    'aria-label': `Remove ${it.name || ('listing #' + it.id)} from cart`,
                    title: `Remove ${it.name || ('listing #' + it.id)} from cart`,
                    onClick: () => removeFromCart(it.id)
                  }, '✕')
                );
              })
            ),
            // CSFloat-style order summary panel — Subtotal / Fee / Total /
            // Pay button / Wallet balance. Replaces the prior flat
            // "Total · $X / [Clear] [Move to watchlist] [Checkout]" footer
            // with a deliberate breakdown panel on the right.
            h('div', { className: 'cart-summary' },
              h('div', { className: 'cart-summary-title' }, 'Order summary'),
              // Privacy mask — operator can flip "Hide $ amounts" in
              // Settings to mask every dollar value across the UI. The
              // cart summary was the last leak: subtotal/fee/total were
              // raw `fmt(...)` while the nav cart-icon tooltip already
              // honoured `privacy ? '$•••••' : fmt(total)`. Masking the
              // panel matches.
              h('div', { className: 'cart-summary-row' },
                h('span', null, 'Subtotal'),
                h('span', { className: 'mono' }, privacy ? '$•••••' : fmt(cartTotal))
              ),
              h('div', { className: 'cart-summary-row' },
                h('span', null, 'Trade escrow'),
                h('span', { style: { color: 'var(--ink-3)' } }, '8 days · auto-release')
              ),
              cartSavings > 0 && h('div', { className: 'cart-summary-row cart-summary-savings' },
                h('span', null, 'Savings vs Steam'),
                h('span', { className: 'mono' }, privacy ? '$•••••' : ('↓ ' + fmt(cartSavings)))
              ),
              // Total is the bare subtotal — the server debits exactly the
              // item price with no buyer fee, so this matches the Confirm
              // button and the single-item Buy modal.
              h('div', { className: 'cart-summary-row cart-summary-total' },
                h('span', null, 'Total'),
                h('span', { className: 'mono' }, privacy ? '$•••••' : fmt(cartTotal))
              ),
              h('div', { className: 'cart-summary-actions' },
                h('button', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)' }, onClick: clearCart }, 'Clear'),
                // Preserve buyer intent on a pricing-shift — instead of
                // forcing them to re-find each item after clearing the
                // cart, move every cart row to the watchlist in one click.
                // Dedupe against the existing watchlist; only items with a
                // known item id are movable (every real listing has one).
                cart.length > 0 && (() => {
                  const itemIds = cart.map(it => it.itemId).filter(Boolean);
                  const movable = itemIds.filter(id => !watchlist.includes(id));
                  if (movable.length === 0) return null;
                  return h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid var(--border)' },
                    onClick: async () => {
                      // Optimistic local merge — instant UI feedback.
                      setWatchlist(w => Array.from(new Set([...w, ...movable])));
                      setCart([]);
                      setToast({ text: `Moved ${movable.length} item${movable.length === 1 ? '' : 's'} to watchlist`, kind: 'ok' });
                      setTimeout(() => setToast(null), 3500);
                      // Server persist for signed-in users via the
                      // bulk-merge endpoint — one request for N items
                      // beats N parallel POSTs, and the endpoint
                      // returns the authoritative post-merge id list
                      // so a subsequent reload won't drift.
                      if (!me) return;
                      try {
                        const { bulkMergeWatchlist } = await import('./api.js');
                        const res = await bulkMergeWatchlist(movable);
                        if (res && Array.isArray(res.ids)) setWatchlist(res.ids);
                      } catch (_) { /* keep optimistic state; next reload reconciles */ }
                    },
                    title: 'Move every cart row to your watchlist and clear the cart'
                  }, '♡ Move to watchlist');
                })(),
                // Anon viewers with a local cart get redirected straight
                // to Steam OpenID — the cart persists across the sign-in
                // roundtrip via localStorage, so they land back on /cart
                // ready to check out with the same rows.
                /* Bare cart total — matches the Total row above, the
                   Confirm button, and what the server actually debits
                   (no buyer fee). */
                (() => {
                  const grand = parseFloat(cartTotal) || 0;
                  return !me
                    ? h('button', {
                        className: 'btn btn-accent',
                        onClick: () => { signInWithSteam(); },
                        title: 'Sign in with Steam before checking out'
                      }, 'Sign in to checkout · ' + fmt(grand))
                    : h('button', {
                        className: 'btn btn-accent',
                        disabled: cartHasStale,
                        onClick: () => setCartConfirmOpen(true),
                        title: cartHasStale ? 'Remove unavailable rows before checkout' : undefined
                      }, 'Checkout · ' + fmt(grand));
                })()
              )
            )
            )
          )
    ),
    // Batch 835 — Report-Seller drawer. Opens from the stall hero's
    // 🚩 Report button. Structured reason picker + optional context,
    // same shape as ReportCounterpartyDrawer in modals.js but specific
    // to the public-stall flow where the viewer is a buyer and the
    // target is the stall owner.
    reportSellerOpen && stallData?.seller && h('div', {
      className: 'cart-confirm-backdrop',
      onClick: () => !reportSellerBusy && setReportSellerOpen(false),
      style: { zIndex: 100 }
    },
      h('div', {
        className: 'cart-confirm-panel',
        style: { maxWidth: 460 },
        onClick: e => e.stopPropagation(),
        role: 'dialog',
        'aria-modal': 'true',
        'aria-labelledby': 'report-seller-title'
      },
        h('div', { className: 'cart-confirm-title', id: 'report-seller-title' },
          'Report seller'),
        h('div', { className: 'cart-confirm-sub', style: { marginBottom: 14 } },
          'Reporting ',
          h('strong', null, stallData.seller.displayName || `#${stallData.seller.id}`),
          ". This opens a FRAUD-category support ticket and staff will review. For trade-specific issues use the Dispute button on your trade row instead."),
        h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 6, textTransform: 'uppercase', letterSpacing: 0.5, fontWeight: 700 } }, 'Reason'),
        h('select', {
          className: 'price-input',
          'aria-label': 'Report reason',
          style: { width: '100%', marginBottom: 12 },
          value: reportSellerReason,
          onChange: e => setReportSellerReason(e.target.value),
          disabled: reportSellerBusy
        }, ['Scam attempt','Suspicious pricing','Harassment in chat','Impersonation','Other']
          .map(r => h('option', { key: r, value: r }, r))),
        h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 6, textTransform: 'uppercase', letterSpacing: 0.5, fontWeight: 700 } },
          'Context — what happened? ',
          h('span', { style: { textTransform: 'none', color: 'var(--text-muted)', fontWeight: 400 } },
            `(${reportSellerContext.length}/1000)`)),
        h('textarea', {
          className: 'price-input',
          'aria-label': 'Describe the incident — context for the staff review',
          style: { width: '100%', minHeight: 90, marginBottom: 12, resize: 'vertical',
                   fontFamily: 'inherit', fontSize: 13 },
          placeholder: 'Include timestamps, chat snippets, screenshot links — anything that helps staff triage.',
          value: reportSellerContext,
          maxLength: 1000,
          onChange: e => setReportSellerContext(e.target.value),
          onKeyDown: (e) => {
            if (e.key === 'Escape') { e.preventDefault(); setReportSellerOpen(false); }
          },
          disabled: reportSellerBusy
        }),
        reportSellerErr && h('div', { className: 'wallet-error', style: { marginBottom: 10 } }, reportSellerErr),
        h('div', { style: { display: 'flex', gap: 10, justifyContent: 'flex-end' } },
          h('button', {
            className: 'btn btn-ghost',
            style: { border: '1px solid var(--border)' },
            disabled: reportSellerBusy,
            onClick: () => setReportSellerOpen(false)
          }, 'Cancel'),
          h('button', {
            className: 'btn btn-accent',
            disabled: reportSellerBusy,
            onClick: submitReportSeller
          }, reportSellerBusy ? 'Submitting…' : 'File report')
        )
      )
    ),
    cartConfirmOpen && h('div', { className: 'cart-confirm-backdrop', onClick: () => !cartBusy && setCartConfirmOpen(false) },
      h('div', {
        className: 'cart-confirm-panel',
        onClick: e => e.stopPropagation(),
        // Batch 827 — Cart-checkout confirm a11y. Same pattern as
        // Confirm-receipt (batch 826): role=dialog + aria-modal so
        // screen readers announce the purchase context, aria-
        // labelledby points at the inline title.
        role: 'dialog',
        'aria-modal': 'true',
        'aria-labelledby': 'cart-confirm-title'
      },
        h('div', { className: 'cart-confirm-head' },
          h('div', { className: 'cart-confirm-title', id: 'cart-confirm-title' }, 'Confirm purchase'),
          h('div', { className: 'cart-confirm-sub' },
            `Buying ${cart.length} item${cart.length === 1 ? '' : 's'} · funds held in escrow until each seller delivers.`,
            // Batch 804 — multi-seller chip. Tells the buyer how many
            // distinct sellers they're about to contract with, so they
            // know to expect N separate trade offers (one per seller).
            (() => {
              const sellers = new Set();
              cart.forEach(it => { if (it.sellerUserId != null) sellers.add(it.sellerUserId); });
              if (sellers.size <= 1) return null;
              return h('div', {
                style: { marginTop: 4, fontSize: 11, color: 'var(--accent)', fontWeight: 600 }
              }, `· ${sellers.size} sellers — expect ${sellers.size} separate trade offers`);
            })())
        ),
        h('div', { className: 'cart-confirm-list' },
          cart.slice(0, 12).map(it => {
            // Use fresh server price when cartFreshness has loaded — keeps
            // the confirm dialog aligned with what the server will charge.
            const fresh = cartFreshness?.[it.id];
            const effectivePrice = fresh && fresh.active && fresh.price != null
              ? parseFloat(fresh.price)
              : (parseFloat(it.price) || 0);
            const priceMoved = fresh && fresh.active && fresh.price != null &&
              Math.abs(parseFloat(fresh.price) - parseFloat(it.price)) > 0.005;
            return h('div', { key: it.id, className: 'cart-confirm-row' },
              h('div', { style: { flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' } },
                it.name,
                // Batch 804 — seller attribution (confirm-modal variant).
                // Matches the cart-list row styling so the buyer can
                // eyeball which seller(s) they're about to commit to
                // before the wallet is charged.
                it.sellerName && h('span', {
                  style: { color: 'var(--text-muted)', fontSize: 11, marginLeft: 6, fontWeight: 500 }
                }, ' · ', it.sellerName),
                priceMoved && h('span', {
                  style: { marginLeft: 8, fontSize: 10, fontWeight: 700, color: '#fbbf24' },
                  title: `Price changed since added to cart (was ${fmt(it.price)})`
                }, 'price updated')
              ),
              h('div', { className: 'cart-confirm-amt' }, fmt(effectivePrice))
            );
          }),
          cart.length > 12 && h('div', { className: 'cart-confirm-more' }, `+ ${cart.length - 12} more`)
        ),
        // The wallet is debited exactly the cart total — no buyer fee —
        // so this matches the Confirm button below, the order-summary
        // Total row, and the single-item Buy modal.
        h('div', { className: 'cart-confirm-total' },
          h('div', null,
            h('div', { className: 'cart-confirm-total-label' }, 'Total charged to wallet'),
            h('div', { className: 'cart-confirm-total-hint' }, 'Seller receives price minus 2% platform fee after confirmed delivery.'),
            // Balance-after-checkout preview (batch 458). Shown when the
            // user can afford it — answers "what will I have left?" so
            // the buyer can pace their wallet without flipping to a
            // separate page. Hidden in privacy mode and when the wallet
            // is already short (the low-balance warning below covers
            // that case with a different signal). Two decimal places to
            // match the wallet hero number format.
            // Balance-after uses the bare cart total — that's the exact
            // amount the server debits.
            (() => {
              const bal = parseFloat(wallet?.balance || 0);
              const after = bal - cartTotal;
              if (privacy || !(bal > 0) || after < 0) return null;
              return h('div', {
                style: { fontSize: 11, color: 'var(--text-muted)', marginTop: 4 }
              },
                'Balance after: ',
                h('span', { style: { color: 'var(--text-primary)', fontWeight: 700, fontFamily: 'JetBrains Mono, monospace' } },
                  fmt(after))
              );
            })()
          ),
          h('div', { className: 'cart-confirm-total-amt' }, fmt(cartTotal))
        ),
        // Batch 788 — trade-URL preflight. Backend's /api/cart/checkout
        // opens a Trade per row; each trade requires the buyer's Steam
        // trade URL for the seller to send the item. Without this banner,
        // a user cart-checks-out, every row succeeds money-side but the
        // seller can't fulfil — they'd have to cancel. Surface the
        // missing URL before the button click rather than after.
        me && !(me.tradeUrl && String(me.tradeUrl).trim()) && h('div', {
          style: {
            margin: '0 0 14px', padding: '10px 14px',
            background: 'rgba(250,204,21,0.1)',
            border: '1px solid rgba(250,204,21,0.4)',
            borderRadius: 8, color: '#fde68a',
            fontSize: 12, lineHeight: 1.5,
            display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap'
          }
        },
          h('span', { style: { fontSize: 16 } }, '⚠'),
          h('div', { style: { flex: 1 } },
            h('strong', null, 'Steam trade URL required · '),
            "sellers can't send your items without it. Add yours in Profile before checking out."),
          h('a', {
            href: '/profile',
            className: 'btn btn-accent',
            style: { padding: '6px 12px', fontSize: 11, textDecoration: 'none' },
            onClick: () => setCartConfirmOpen(false)
          }, 'Open Profile')
        ),
        // Low-balance warning: if wallet balance is below cart total,
        // show a red banner with the shortfall amount + a Deposit CTA.
        // Computed client-side from the wallet the app already has; the
        // backend's checkout will still 402 on actual insufficient-funds,
        // but surfacing the gap here saves the user a round-trip.
        // Gap is measured against the bare cart total — that's the exact
        // amount checkout debits, and it matches the Confirm button's
        // own affordability gate below.
        (() => {
          const bal = parseFloat(wallet?.balance || 0);
          const gap = cartTotal - bal;
          if (!(gap > 0)) return null;
          return h('div', { className: 'cart-confirm-low-balance' },
            h('div', null,
              h('strong', null, 'Not enough balance. '),
              `You have ${fmt(bal)} — add ${fmt(gap)} to complete this checkout.`
            ),
            h('button', {
              className: 'btn btn-accent',
              style: { padding: '6px 14px', fontSize: 12 },
              onClick: (e) => {
                e.preventDefault();
                setWalletInitialTab('deposit');
                setWalletPrefillAmount(gap.toFixed(2));
                setCartConfirmOpen(false);
                navigate(paths.wallet());
              }
            }, 'Deposit →')
          );
        })(),
        h('div', { className: 'cart-confirm-actions' },
          h('button', {
            className: 'btn btn-ghost',
            style: { border: '1px solid var(--border)' },
            onClick: () => setCartConfirmOpen(false),
            disabled: cartBusy
          }, 'Cancel'),
          h('button', {
            className: 'btn btn-accent',
            onClick: doCheckout,
            // Batch 789 — also gate the Confirm button on trade-URL
            // presence (batch 788 just surfaced the warning; click was
            // still allowed). Without this, a user could ignore the
            // banner and charge their wallet for rows that'll sit in
            // PENDING_SELLER_SEND forever until the seller cancels.
            disabled: cartBusy || cart.length === 0 || cartTotal > parseFloat(wallet?.balance || 0) ||
              !(me && me.tradeUrl && String(me.tradeUrl).trim()),
            title: !(me && me.tradeUrl && String(me.tradeUrl).trim())
              ? 'Add your Steam trade URL in Profile before checking out'
              : undefined
          }, cartBusy ? 'Placing order…' : `Confirm · ${fmt(cartTotal)}`)
        )
      )
    ),
    routeName === 'faq'           && h(FaqModal,        { onClose: () => navigate(paths.market()) }),
    routeName === 'settings'      && h(SettingsModal,   { onClose: () => navigate(paths.market()), me }),
    routeName === 'affiliate'     && h(AffiliateModal,  { onClose: () => navigate(paths.market()) }),
    // Deep-link handling — notifications like TRADE_MESSAGE land us on
    // `/profile/trades`. The router's :tab pattern feeds initialTab so
    // the Profile modal opens on the right tab instead of the default.
    routeName === 'profile'       && h(ProfileModal,    {
      onClose: () => navigate(paths.market()),
      me, wallet, transactions,
      onRefresh: () => { loadWallet(); },
      initialTab: (() => {
        // CSFloat-1:1: prefer the route-pattern :tab param so /profile/trades
        // works as a deep link. Falls back to ?tab=… for legacy notification
        // URLs that still embed it as a query.
        if (route.params?.tab) return route.params.tab;
        try {
          const q = new URLSearchParams(window.location.search).get('tab');
          const allowed = new Set(['personal','transactions','buyorders','autobids','trades','offers','reviews','support','developers']);
          return q && allowed.has(q) ? q : undefined;
        } catch { return undefined; }
      })()
    }),
    routeName === 'sell'          && h(SellItemsModal,  { onClose: () => navigate(paths.market()), me, onRefresh: load }),
    routeName === 'mystall'       && h(MyStallModal,    { onClose: () => navigate(paths.market()), me, onRefresh: load, initialTab: route.params?.tab }),
    routeName === 'offers'        && h(OffersModal,     { onClose: () => navigate(paths.market()), me, onRefresh: () => { load(); loadWallet(); }, initialTab: route.params?.tab }),
    routeName === 'watchlist'     && h(WatchlistModal,  {
      onClose: () => navigate(paths.market()),
      me,
      watchlist, allListings: listings,
      onOpen: openModal, onToggleStar: toggleStar,
      onAddToCart: me ? addToCart : null,
      cartHas: (id) => cart.some(c => c.id === id),
      initialTab: route.params?.tab
    }),
    routeName === 'database'      && h(DatabaseModal,      { onClose: () => navigate(paths.market()), onPickItem: (item) => { navigate(paths.item(item.id)); }, me }),
    routeName === 'buyorders'     && h(BuyOrdersModal,     { onClose: () => { setPreselectedBuyItem(null); navigate(paths.market()); }, me, wallet, preselectedItem: preselectedBuyItem }),
    routeName === 'loadouts'      && h(LoadoutLabModal,    { onClose: () => navigate(paths.market()), me }),
    routeName === 'loadout'       && h(LoadoutLabModal,    { onClose: () => navigate(paths.market()), me, loadoutId: route.params?.id }),
    routeName === 'notifications' && h(NotificationsModal, { onClose: () => navigate(paths.market()), me }),
    routeName === 'support'       && h(ProfileModal,        { onClose: () => navigate(paths.market()), me, wallet, transactions, onRefresh: loadWallet, initialTab: 'support' }),
    // Staff panels — role-gated. Non-staff users who type the URL hit a
    // clean "access denied" modal with a back-to-market CTA. Previously
    // we dropped them on the Help modal which was confusing (looked
    // like a bug, not a gate).
    routeName === 'admin' && (isAdmin
      ? h(LazyStaffPanel, { which: 'admin', onClose: () => navigate(paths.market()), me })
      : h(StaffAccessDeniedModal, { what: 'the admin panel', onClose: () => navigate(paths.market()) })),
    routeName === 'csr' && (isCsr
      ? h(LazyStaffPanel, { which: 'csr', onClose: () => navigate(paths.market()), me })
      : h(StaffAccessDeniedModal, { what: 'the customer service panel', onClose: () => navigate(paths.market()) })),

    /* ITEM DETAIL — the only modal that isn't a menu destination. Closing it
       navigates back to /, so back/forward work naturally.

       Render states: loading spinner → ItemModal on success → "item not
       found" fallback when the route lands on a genuinely missing id.
       Without the fallback the modal rendered nothing and the user saw
       a blank page with no way to figure out what happened. */
    routeName === 'item' && !modalLoading && !selected && (
      // /item/:id is a full-page route — render the not-found state as an
      // in-page block, not a modal-backdrop popup over a dimmed page (a
      // direct share-link to a missing id is the common case here).
      h('div', {
        style: {
          minHeight: '60vh', display: 'flex',
          alignItems: 'center', justifyContent: 'center', padding: '40px 20px'
        }
      },
        h('div', {
          style: {
            maxWidth: 420, textAlign: 'center', padding: '32px 24px',
            background: 'var(--bg-secondary)', border: '1px solid var(--border)',
            borderRadius: 12
          },
          // In-page region (not a dialog) — no aria-modal / role=dialog.
          role: 'region',
          'aria-labelledby': 'item-not-found-title'
        },
          h('div', { style: { marginBottom: 12, display: 'flex', justifyContent: 'center' }, 'aria-hidden': 'true' },
            h(MaterialIcon, { name: 'search_off', size: 40, color: 'var(--text-muted)' })),
          h('h1', { id: 'item-not-found-title', style: { fontSize: 18, fontWeight: 700, color: 'var(--text-primary)', margin: '0 0 8px' } }, 'Item not found'),
          h('div', { style: { fontSize: 13, color: 'var(--text-muted)', marginBottom: 18 } },
            'The item you were looking for has been removed or never existed. It may have been merged into another entry by the catalogue sync.'),
          h('a', { className: 'btn btn-accent', href: '/market' }, 'Back to marketplace')
        )
      )
    ),
    routeName === 'item' && (selected || modalLoading) && (
      modalLoading
        ? h('div', {
            // In-page loading state — /item/:id is a real page, so the
            // spinner sits centred in the page, not over a dark backdrop.
            style: {
              minHeight: '60vh', display: 'flex',
              alignItems: 'center', justifyContent: 'center'
            }
          }, h('div', { className: 'spinner' }))
        : h(ItemModal, {
            item: selected.item,
            listings: selected.listings,
            history: selected.history,
            me,
            wallet,
            // 2026-05-02 page-mode: /item/:id is a real page (per
            // feedback_pages_not_popups.md). When isPageMode is true,
            // ItemModal strips role=dialog, aria-modal, the Escape
            // keydown listener, the close-X button, and breadcrumb
            // onClose handlers; breadcrumbs become real navigate()
            // calls so the URL reads as a destination, not an overlay.
            isPageMode: true,
            // Prefer history.back() so closing /item/:id returns the user to
            // the URL they came from (e.g. /?q=hat&category=Hats) instead of
            // wiping their search. Falls back to bare / when we don't have
            // any internal history to pop (direct link / share landing).
            onClose: () => closeToPrevious(paths.market()),
            onBuy: handleBuy,
            onMakeOffer: handleMakeOffer,
            onRefresh: () => { load(); loadWallet(); },
            onCreateBuyOrder: (item) => {
              setPreselectedBuyItem(item);
              navigate(paths.buyorders());
            },
            onAddToCart: (listing) => {
              addToCart(listing);
              showToast(`Added ${listing.item?.name} to cart`, 'ok');
            },
            cartHas: (id) => cart.some(x => x.id === id),
            // Watchlist heart on the item action panel was a dead button - no
            // onClick wired. Pass the same toggleStar/watchlist that GridCard
            // gets so the heart fills, the badge updates, and localStorage
            // persists for anon visitors.
            watchlist,
            onToggleStar: toggleStar
          })
    ),

    /* SHORTCUTS HELP OVERLAY — press `?` to toggle */
    shortcutsOpen && h('div', { className: 'shortcuts-backdrop', onClick: () => setShortcutsOpen(false) },
      h('div', {
        className: 'shortcuts-card',
        onClick: e => e.stopPropagation(),
        // Batch 831 — a11y on the shortcuts overlay. Escape is already
        // wired at the app-level keydown handler (line ~3194) which
        // toggles `shortcutsOpen` false when it's true. Adding role=
        // dialog + aria-modal so the panel announces correctly.
        role: 'dialog',
        'aria-modal': 'true',
        'aria-labelledby': 'shortcuts-title'
      },
        h('div', { className: 'shortcuts-title', id: 'shortcuts-title' }, 'Keyboard Shortcuts'),
        h('div', { className: 'shortcuts-grid' },
          [
            ['/',       'Focus search'],
            ['⌘/Ctrl+K','Focus search (works from anywhere)'],
            ['?',       'Toggle this panel'],
            ['Esc',     'Back to market / close detail'],
            ['g m',     'Go to Market'],
            ['g d',     'Go to Database'],
            ['g p',     'Go to Profile'],
            ['g w',     'Go to Wallet'],
            ['g c',     'Go to Cart'],
            ['g l',     'Go to Loadout Lab'],
            ['g s',     'Go to Sell Items'],
            ['g f',     'Go to Watchlist (Favorites)'],
            ['g h',     'Go to Help'],
            ['g o',     'Go to Offers'],
            ['g b',     'Go to Buy Orders'],
            ['g n',     'Go to Notifications'],
            ['Ctrl-click balance', 'Toggle privacy mode']
          ].map(([k, d]) => h('div', { key: k, className: 'shortcut-row' },
            h('kbd', null, k), h('span', null, d)
          ))
        ),
        h('button', { className: 'btn btn-ghost', style: { marginTop: 14, border: '1px solid var(--border)' }, onClick: () => setShortcutsOpen(false) }, 'Close')
      )
    ),

    /* Floating back-to-top affordance — appears after 600px of scroll. */
    h(BackToTopButton, null),

    /* GDPR cookie consent banner — first visit only, dismissable. */
    h(CookieBanner, null),

    /* TOAST */
    toast && (() => {
      // Batch 925 — proper `warn` kind styling. Prior code collapsed
      // `warn` to the ok styling (green ✓), which misread "Deposit
      // cancelled" and "Session expired" as positive confirmations.
      // Now `warn` renders amber with a ⚠ glyph, matching what users
      // expect for non-error-but-not-success messaging (Stripe cancel,
      // session timeout, etc.).
      const kind = toast.kind;
      const isErr = kind === 'err';
      const isWarn = kind === 'warn';
      const bg = isErr ? 'var(--red-dim)' : isWarn ? 'rgba(251,191,36,0.15)' : 'var(--accent-dim)';
      const fg = isErr ? 'var(--red)'     : isWarn ? '#fbbf24'               : 'var(--accent)';
      const glyph = isErr ? '✕' : isWarn ? '⚠' : '✓';
      return h('div', { className: `sale-toast ${isErr ? 'err' : (isWarn ? 'warn' : '')}` },
        h('div', { className: 'sale-toast-thumb', style: { background: bg, color: fg } }, glyph),
        h('div', { className: 'sale-toast-text' },
          h('div', { className: 'sale-toast-line2' }, toast.text)
        )
      );
    })(),

    /* FEE CALCULATOR — only on the bare /market grid for signed-out
       visitors. Hidden on the home (`/`) marketing landing so the
       hero+rail+preview reads clean (csfloat-1:1 home is minimal). */
    !me && !isFullPage && routeName !== 'home' && h('section', { className: 'homepage-trust' },
      h('div', { className: 'fee-calc' },
        h('div', null,
          h('div', { className: 'fee-calc-title' },
            h('div', { className: 'section-title-dot' }),
            'Fee Calculator'
          ),
          h('div', { className: 'fee-calc-sub' }, "See what you'll actually take home on a sale."),
          h('div', { className: 'fee-calc-row' },
            // The input is USD-anchored even when the operator's display
            // currency is CAD/EUR/etc. — backend stores listings in USD
            // and fees are computed against that. Label shows the active
            // currencySymbol so it's not confusing.
            h('label', { title: 'Type a USD-anchored sale amount; outputs convert to your selected currency.' }, `Sale Amount (${currencySymbol()})`),
            h('input', {
              className: 'price-input fee-calc-input',
              type: 'number', min: '1', step: '0.01',
              inputMode: 'decimal',
              'aria-label': `Sale amount for fee calculator (${currencySymbol()})`,
              value: feeInput,
              onChange: e => setFeeInput(e.target.value),
              onFocus: e => e.target.select()
            })
          )
        ),
        h('div', null,
          (() => {
            // Batch 720 — align marketing with backend reality. The
            // only fee actually deducted on SkinBox is the 2% platform
            // fee at sale time. Withdrawals carry no additional fee —
            // SkinBox absorbs the Stripe Connect payout cost. Previous
            // code also deducted a 1.5% "withdraw fee" that didn't
            // exist server-side, so the calculator was quoting users
            // a lower take-home than they actually receive.
            const amt = Math.max(0, parseFloat(feeInput) || 0);
            const platformFee = (amt * 0.02);
            const take = Math.max(0, amt - platformFee);
            return h('div', { className: 'fee-calc-breakdown' },
              h('div', { className: 'fee-calc-line' },
                h('span', null, 'Platform fee (2%)'),
                h('strong', null, '−' + fmt(platformFee))
              ),
              h('div', { className: 'fee-calc-line total' },
                h('span', null, 'You receive'),
                h('strong', null, fmt(take))
              )
            );
          })(),
          h('div', { className: 'fee-calc-note' }, 'Steam takes 12% on Workshop sales. SkinBox is a flat 2% on each sale — you keep 6× more. Deposits + withdrawals are free; payouts arrive in 1-2 business days.')
        )
      ),
    ),

    /* FOOTER — pinned at the bottom of the site-root flex column so it
       sits below ALL content (marketplace, profile, wallet, etc.) instead
       of being glued to a specific section. See .site-root { display:flex
       flex-direction:column min-height:100vh } in styles.css. */
    h(SiteFooter, null)
  );
}
