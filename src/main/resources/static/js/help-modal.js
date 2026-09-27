// Standalone Help Center — FAQ accordion, getting started guide, keyboard
// shortcut reference, and a Contact Support CTA. Lives on its own route
// `/help`, but also accepts being opened from any button via the same shell.
import { h, useState, useEffect, highlightMatch } from './utils.js';
import { InfoModal } from './info-modal.js';
import { MaterialIcon } from './primitives.js';
import { navigate, paths } from './router.js';

const FAQ = [
  {
    q: 'What is SkinBox?',
    a: "SkinBox is a peer-to-peer marketplace for s&box cosmetic items. Sellers set their own price, and every listing is backed by a real Steam account. The platform is cheaper than going through the Steam store — a flat 2% on each sale vs Steam's 12%. Moving money in and out is separate and is not free: card deposits and bank payouts carry the payment processor's own charge, passed through at cost with no markup, and the wallet screen shows you the exact figure before you confirm."
  },
  {
    q: 'How do I sign in?',
    a: "Click the ‘Sign in through Steam’ button in the top-right of any page. You'll be redirected to steamcommunity.com to approve the login. Your password never touches our servers — we only see your public Steam profile via OpenID."
  },
  {
    q: 'How do I list an item I own on Steam?',
    a: "Open Sell Items from the user menu. The Steam Inventory tab fetches your real Steam inventory for s&box (appid 590830) — click any item, pick Buy Now or Auction, set a price (or starting bid + duration for auctions), and hit List for Sale. The listing appears on the marketplace immediately and a 2% platform fee is deducted when it sells."
  },
  {
    q: 'How do I run an auction (as a seller)?',
    a: "In the Sell Items flow, toggle Listing type to Auction instead of Buy Now. Pick a duration (6h / 12h / 1d / 2d / 3d / 7d), set a starting bid, and hit List for Sale. Bidders push the price up from there until the timer runs out; the top bidder's wallet is charged automatically. Anti-snipe: any bid placed in the final 30 seconds extends the auction by another 30 seconds so nobody can win purely on timing. You can cancel an auction any time, but once it has bids you can't change the starting price — cancel + relist instead. When the auction ends with no bids, the item returns to your inventory automatically."
  },
  {
    q: 'How does depositing money work?',
    a: "Open Wallet → Deposit, enter any amount between $1 and $10,000, and you'll be handed off to Stripe Checkout. Once the payment clears, the webhook credits your balance automatically. There's a rolling $5,000 cap on how much you can deposit in any 24-hour window — the Deposit form shows how much you have left for the day before you submit."
  },
  {
    q: 'How do withdrawals work?',
    a: "Wallet → Withdraw. You'll need a verified email address first (verify it from Profile → Personal). The first time, Set up cash-out links your bank account or debit card through Stripe (this is also the identity check). Then enter an amount; if you have 2FA enabled you'll also enter a fresh 6-digit code. Your balance is debited and the payout is sent to your Stripe cash-out account straight away; Stripe pays it on to your bank, usually within 1–2 business days. A rolling $5,000 cap applies per 24-hour window, and withdrawals are paused while any deposit on your account is under a chargeback dispute."
  },
  {
    q: 'What are Buy Orders?',
    a: "A standing offer to auto-purchase a specific item as soon as a listing drops to your max price. Open any item, hit Place Buy Order, pick a max, and walk away. When a matching listing appears, we charge your wallet and assign the item automatically. Cancel from your Profile → Buy Orders tab."
  },
  {
    q: 'What are Offers?',
    a: "A soft bargain on an existing listing. Go to an item's detail view, hit Make Offer, and enter an amount below the asking price. The seller sees it in their Offers tab and can Accept, Reject, or counter. Accepting auto-transfers funds + item through the same flow as a direct purchase."
  },
  {
    q: 'How do Auto-accept offers work (sellers)?',
    a: "When you list an item for Buy Now, there's an optional Auto-accept % field (1–50%). Setting it to 10%, for example, means any offer at or above 90% of your asking price auto-accepts — funds transfer and the trade opens without you having to click Accept. Offers below your threshold still queue for manual review. Great for high-volume sellers who don't want to babysit the Offers tab. The same control appears on the MyStall edit form and the bulk-list action bar, so you can flip it on existing listings or apply it uniformly across a batch. Set it to blank (or 0) to go back to manual review for every offer."
  },
  {
    q: 'How do auctions work?',
    a: "Auction listings show a countdown timer and a Bid input. Enter your bid — the minimum is the current price plus one increment (the increment scales with price, from $0.05 on cheap items up to $100 on four-figure ones). You can also set an auto-bid cap, and the proxy-bidder will raise your bid by the minimum increment until it hits your cap. When the timer runs out, the winner's wallet is charged and the seller is credited (minus 2%)."
  },
  {
    q: 'Is my money safe?',
    a: "Deposits and refunds go through Stripe, the same processor used by millions of websites. When you buy an item the payment is held in escrow — not paid to the seller — until you confirm receipt or the 8-day trade-hold window passes, so a seller can't take your money and ghost you. Withdrawals are queued for manual admin approval before payout and require a verified email (plus a 2FA code if you've enabled it). Session cookies are HttpOnly and SameSite-strict in production, and every wallet write is logged with a correlation id for audit."
  },
  {
    q: 'Why is SkinBox cheaper than Steam?',
    a: "Steam charges 12% on Workshop sales. SkinBox charges a flat 2% to the seller at sale time — that is the only fee SkinBox keeps, and it is the only one that funds the site. Moving money is a separate cost and is not free: card deposits and bank payouts carry Stripe's own charge, which is passed through at cost with no markup added. The wallet screen shows the exact deduction and the exact amount you will receive before you confirm anything. The green '−X%' chip on each card shows how much you save versus the Steam store price."
  },
  {
    q: 'My purchase is stuck, what do I do?',
    a: "Open a ticket from Profile → Support → + New Ticket and include the transaction id from your Trades tab. Clara, the automated first-responder, replies instantly with the most likely fix; a human agent follows up within 4 hours for trade, payment, and refund tickets (these jump the queue), or within 24 hours for everything else."
  },
  {
    q: 'How do I report a suspicious listing or user?',
    a: "On any item detail page, the Report button next to each active listing opens a short report form (reason + optional note). To report a user directly — e.g. chat harassment or a scam attempt before the trade opens — open the seller's stall page and hit Report. Both flows route to the support queue; the reporter is notified when staff acts."
  },
  {
    q: 'Is there a system status page?',
    a: "Yes — /status.html probes the core services (marketplace, catalogue, auctions, API) every 30 seconds and shows green/amber/red for each. There's a direct link under Resources in the site footer."
  },
  {
    q: 'Is there a Chrome / Edge extension to value my Steam inventory?',
    a: "Yes — the SkinBox Inventory Valuer extension adds a per-tile price badge on your Steam inventory page and a floating panel with Steam Market vs SkinBox totals. Install it from the Chrome Web Store (link on the site footer when it ships). No account is required — the extension uses Steam's public priceoverview endpoint + the SkinBox public listings API."
  },
  {
    q: 'Can I cancel a pending withdrawal?',
    a: "Yes. Wallet → Transactions has a 'Cancel' button next to every PENDING withdrawal row. The full amount is credited back immediately and the row flips to CANCELLED. You don't need to wait for an admin rejection."
  },
  {
    q: 'Why did my buy order auto-expire?',
    a: "Standing buy orders auto-expire after 30 days of inactivity. This prevents a long-forgotten order from surprise-firing months after you stopped wanting the item. If you're still interested, re-create it from Profile → Buy Orders — the wallet hold is released automatically so nothing is lost."
  },
  {
    q: 'How do I earn the ✓ Verified seller badge?',
    a: "Complete 10+ sales AND maintain a rating of 4★ or higher (or have no reviews yet). Your progress bar is visible on My Stall — it shows exactly how many more sales or what rating improvement you need. Verified sellers get a badge on every listing they own, which lifts buyer conversion and trust."
  },
  {
    q: 'I lost access to my 2FA device — what do I do?',
    a: "Open a support ticket (Profile → Support → + New Ticket) with category Account and include your Steam ID. Staff will verify ownership via Steam OpenID handshake + ban-appeal questions and reset your 2FA. Once reset, every active session on your account is revoked for security, and you'll need to re-enrol a new device from Profile → 2FA on your next sign-in."
  },
  {
    q: 'How do I delete my account?',
    a: "Profile → Personal → Delete account. Staff review deletion requests within 1-2 business days; while the request is pending you can cancel it via the same page. Once finalized: display name, avatar, email, trade URL and 2FA are wiped; listings and trade history stay on the ledger for audit. Funds in your wallet must be withdrawn first — the request is rejected if there's a pending withdrawal or open trade."
  },
  {
    q: 'What if the item I bought never arrives — or arrives wrong?',
    a: "Your money sits in escrow, not the seller's wallet, until you confirm receipt. If the seller never sends the Steam trade offer, the trade auto-cancels with a full refund after 3 days of seller inactivity — you don't have to do anything. If the offer was sent but the item is wrong or missing, you have 8 days from purchase to confirm receipt or file a dispute: go to Profile → Trades, find the row, and click 'Report issue' / 'Dispute'. Include the Steam offer URL (or lack thereof) and any screenshots. Staff triage disputes in rotating CSR shifts and will either release escrow, cancel with refund, or escalate. After the 8-day window the trade auto-releases to the seller, but you can still open a support ticket for up to 30 days for staff manual review."
  },
  {
    q: 'What happens if I get banned? Can I appeal?',
    a: "Banned accounts can no longer list, bid, buy, offer, trade, or withdraw. Your Steam session is revoked immediately and future sign-ins land on a read-only state. To appeal, email appeals@skinbox.market from the Steam-verified address on your account with your Steam ID and a description of the incident — or simply reply to the suspension email. Appeals are reviewed by a senior admin (not the reviewer who banned you) within 1-2 business days. If the ban is lifted, your listings are NOT auto-restored — you'll need to re-list anything you want back on the market."
  },
  {
    q: 'Can I see who bought my item?',
    a: "Yes — Profile → Trades. Each completed trade row shows the buyer's display name, Steam ID, and the exact time they confirmed receipt. You can also leave a review on any VERIFIED trade, and their review shows on your stall. For privacy, wallet balances and real-name data are never exposed; buyer identity is limited to their public Steam profile."
  },
  {
    q: 'I think I found a security bug — how do I report it?',
    a: "Email security@skinbox.market with the subject '[disclosure] short title' and a reproduction you're comfortable sharing. We acknowledge within 72h and send a first status update within 7 days. Good-faith researchers get a safe-harbor commitment — see /legal/responsible-disclosure.html for the full policy, scope, and Hall of Fame. The machine-readable manifest lives at /.well-known/security.txt."
  },
  {
    q: 'How do I use the API from a bot or browser extension?',
    a: "Mint an API key at Profile → Developers → + New Key. Pick RW (full access — can buy / sell / move funds) or RO (read-only — fetches listings + wallet balance but can't mutate anything). Send the raw token as Authorization: Bearer sbx_live_… on every request. The server accepts this in place of session cookies on every /api/* route, so no CSRF token is needed. Active-key cap is 20 per user — revoke stale ones first if you hit the wall. Every mint fires a security-alert email; if you see one you didn't trigger, click 'Revoke all active keys' in Profile → Developers and open a ticket."
  },
  {
    q: 'How do I audit my own account activity?',
    a: "Two collapsed panels on Profile → Personal show your activity trail: Recent Sign-Ins (last 20 Steam logins with IP + browser) and Security Activity (last 50 events where your account was the subject — 2FA resets, API key mints, withdrawals, disputes, admin force-actions). Staff actions are marked with a blue STAFF chip so you can tell admin-initiated events apart from your own. If you see something you didn't trigger, use 'Sign out everywhere' + 'Revoke all active keys' on the same tab, then open a support ticket."
  }
];

const SHORTCUTS = [
  { keys: '/',           desc: 'Focus the marketplace search box' },
  { keys: '⌘/Ctrl + K',  desc: 'Focus search from anywhere (works even while typing in another input)' },
  { keys: '?',           desc: 'Toggle the keyboard shortcut cheat-sheet overlay' },
  { keys: 'Esc',    desc: 'Close the current item detail / modal / page' },
  { keys: 'g m',    desc: 'Go to Market' },
  { keys: 'g d',    desc: 'Go to Item Database' },
  { keys: 'g p',    desc: 'Go to Profile' },
  { keys: 'g w',    desc: 'Go to Wallet' },
  { keys: 'g c',    desc: 'Go to Cart' },
  { keys: 'g l',    desc: 'Go to Loadout Lab' },
  { keys: 'g s',    desc: 'Go to Sell Items' },
  { keys: 'g f',    desc: 'Go to Watchlist' },
  { keys: 'g n',    desc: 'Go to Notifications' },
  { keys: 'g o',    desc: 'Go to Offers' },
  { keys: 'g b',    desc: 'Go to Buy Orders' },
  { keys: 'g h',    desc: 'Go to Help Center' },
  { keys: 'g t',    desc: 'Go to Settings' },
  { keys: 'v',      desc: 'Toggle marketplace grid ↔ table view (on /market)' },
  { keys: 'Ctrl+click balance', desc: 'Toggle privacy mode — masks every dollar amount across the UI' },
];

const STEPS = [
  { icon: 'login',        title: 'Sign in with Steam',  body: 'No password — OpenID handshake via steamcommunity.com.' },
  { icon: 'account_balance_wallet', title: 'Top up your wallet', body: 'Stripe Checkout with a test card or real money. Funds arrive in seconds.' },
  { icon: 'search',       title: 'Browse the marketplace', body: 'Filter by category, rarity, price. Press / to jump straight into search.' },
  { icon: 'shopping_cart', title: 'Buy Now or Place a Buy Order', body: 'Buy a listing instantly, or set a standing max price and walk away.' },
  { icon: 'checkroom',    title: 'Receive the item', body: 'Listings you buy appear in your Platform Inventory on the Sell Items page, ready to relist.' },
  { icon: 'verified',     title: 'Trade safely', body: 'Every write is signed, rate-limited, and audited. Support is one click away.' },
];

export function HelpModal({ onClose }) {
  // Batch 708 — read `?q=` from the URL so deep links like
  // `/help?q=API` open with the search pre-filled. Lets other parts
  // of the app link to specific FAQ entries without embedding the
  // full answer inline. First-match auto-expands below.
  const initialQ = (() => {
    try {
      const u = new URLSearchParams(window.location.search);
      return (u.get('q') || '').slice(0, 60);
    } catch { return ''; }
  })();
  const [openIdx, setOpenIdx] = useState(0);
  const [search, setSearch]   = useState(initialQ);
  const q = search.trim().toLowerCase();
  const filteredFaq = q.length === 0
    ? FAQ.map((item, origIdx) => ({ ...item, origIdx }))
    : FAQ.map((item, origIdx) => ({ ...item, origIdx }))
         .filter(item => item.q.toLowerCase().includes(q) || item.a.toLowerCase().includes(q));
  // Batch 708 — auto-expand the first match on a query-prefilled open
  // so a deep-linker lands on the answer, not a collapsed list. Only
  // runs once per initial query change.
  const [_autoExpanded, setAutoExpanded] = useState(false);
  useEffect(() => {
    if (_autoExpanded) return;
    if (initialQ && filteredFaq.length > 0) {
      setOpenIdx(filteredFaq[0].origIdx);
      setAutoExpanded(true);
    }
  }, [initialQ, filteredFaq, _autoExpanded]);
  return h(InfoModal, { title: 'Help Center', onClose },
    h('div', { className: 'help-intro' },
      h(MaterialIcon, { name: 'info', size: 22 }),
      h('div', null,
        h('div', { style: { fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' } }, "New to SkinBox?"),
        h('div', { style: { fontSize: 12, color: 'var(--text-muted)', marginTop: 4 } },
          "Work through the six-step walkthrough below, or jump straight to the FAQ. Still stuck? Open a support ticket at the bottom of this page.")
      )
    ),

    h('h2', { className: 'help-section-title' },
      h(MaterialIcon, { name: 'route', size: 18 }), 'Getting Started'),
    h('div', { className: 'help-steps' },
      STEPS.map((s, i) => h('div', { key: i, className: 'help-step' },
        h('div', { className: 'help-step-num' }, i + 1),
        h(MaterialIcon, { name: s.icon, size: 28 }),
        h('div', { className: 'help-step-title' }, s.title),
        h('div', { className: 'help-step-body' }, s.body)
      ))
    ),

    h('h2', { className: 'help-section-title' },
      h(MaterialIcon, { name: 'quiz', size: 18 }), 'Frequently Asked Questions'),
    // Boss QA F2 — surface a 3-question preview just below the section
    // heading. Pre-fix the heading was followed only by a search box,
    // leaving the section visually empty until the user clicked. Each
    // preview row, when clicked, expands the full accordion entry
    // below + scrolls it into view.
    !q && h('div', { className: 'help-faq-preview', 'aria-label': 'Top FAQ questions' },
      FAQ.slice(0, 3).map((item, i) => h('button', {
        key: 'preview-' + i,
        type: 'button',
        className: 'help-faq-preview-row',
        onClick: () => {
          setOpenIdx(i);
          // Defer one frame so the accordion has rendered the panel.
          requestAnimationFrame(() => {
            const el = document.getElementById('help-faq-panel-' + i);
            if (el && typeof el.scrollIntoView === 'function') {
              el.scrollIntoView({ behavior: 'smooth', block: 'center' });
            }
          });
        }
      },
        h('span', { className: 'help-faq-preview-q' }, item.q),
        h('span', { className: 'help-faq-preview-arrow' }, '→')
      ))
    ),
    h('input', {
      className: 'price-input',
      type: 'search',
      enterKeyHint: 'search',
      'aria-label': 'Search frequently asked questions',
      style: { width: '100%', marginBottom: 10, fontSize: 13 },
      placeholder: 'Search FAQs…',
      value: search,
      onChange: e => setSearch(e.target.value)
    }),
    filteredFaq.length === 0
      ? h('div', {
          // Batch 928 — richer zero-match state: headline + concrete CTAs
          // (clear search, open a ticket). Previous text-only copy pointed
          // the user "below" for the support link but left them on a
          // dead-ended search box.
          style: {
            fontSize: 13, color: 'var(--text-secondary)',
            padding: '20px 10px', textAlign: 'center'
          }
        },
          h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
            'No FAQs match "', h('strong', { style: { color: 'var(--accent)' } }, search.trim()), '"'),
          h('div', { style: { maxWidth: 420, margin: '0 auto 14px', lineHeight: 1.55 } },
            'Try a broader keyword, or open a ticket — a CSR replies within 24 hours (within 4 hours for trade, payment, and refund issues).'),
          h('div', { style: { display: 'flex', gap: 10, justifyContent: 'center', flexWrap: 'wrap' } },
            h('button', {
              className: 'btn btn-ghost',
              style: { border: '1px solid var(--border)', padding: '8px 14px', fontSize: 12 },
              onClick: () => setSearch('')
            }, 'Clear search'),
            h('a', {
              className: 'btn btn-accent',
              style: { padding: '8px 14px', fontSize: 12 },
              href: '/support',
              // Use the navigate() helper rather than a raw pushState +
              // synthetic PopStateEvent: the helper increments the
              // router's internalPushes counter and sets the
              // dispatchingInternalPop flag so the popstate listener
              // doesn't mistake this for a browser back/forward. A bare
              // pushState here desynced that counter and broke
              // closeToPrevious() for later modal-close buttons.
              onClick: (e) => { e.preventDefault(); onClose && onClose(); navigate(paths.support()); }
            }, 'Open a ticket →')
          )
        )
      : h('div', { className: 'help-faq' },
          filteredFaq.map(item => {
            const isOpen = openIdx === item.origIdx;
            const panelId = `help-faq-panel-${item.origIdx}`;
            const btnId = `help-faq-q-${item.origIdx}`;
            return h('div', {
              key: item.origIdx, className: `help-faq-row ${isOpen ? 'open' : ''}`
            },
              // aria-expanded + aria-controls form the standard accordion
              // pattern so screen readers announce "collapsed/expanded"
              // and can jump to the panel. Without these, blind users
              // hear an unlabelled button with no state.
              h('button', {
                id: btnId,
                className: 'help-faq-q',
                'aria-expanded': isOpen,
                'aria-controls': panelId,
                onClick: () => setOpenIdx(isOpen ? -1 : item.origIdx)
              },
                // Highlight the matched substring so a user searching "2FA"
                // across five FAQ rows can see where the match is without
                // expanding every answer. Case-insensitive; rendered as a
                // <mark> so screen-readers can optionally announce emphasis.
                highlightMatch(item.q, q),
                h(MaterialIcon, { name: isOpen ? 'expand_less' : 'expand_more', size: 20 })
              ),
              // role=region needs a discernible name or axe flags it; point
              // aria-labelledby at the question button so SR announces the
              // panel as "<question>, region".
              isOpen && h('div', { id: panelId, role: 'region', 'aria-labelledby': btnId, className: 'help-faq-a' }, highlightMatch(item.a, q))
            );
          })
        ),

    h('h2', { className: 'help-section-title' },
      h(MaterialIcon, { name: 'keyboard', size: 18 }), 'Keyboard Shortcuts'),
    h('div', { className: 'help-shortcuts' },
      SHORTCUTS.map((s, i) => h('div', { key: i, className: 'help-shortcut-row' },
        h('kbd', null, s.keys),
        h('span', null, s.desc)
      ))
    ),

    h('div', { className: 'help-contact' },
      h('div', null,
        h('div', { style: { fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' } }, "Still need a hand?"),
        h('div', { style: { fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 } },
          "Open a support ticket and a CSR will reply within 24 hours — within 4 hours for trade, payment, and refund issues. Include a transaction id if it's about a purchase.")
      ),
      h('button', {
        className: 'btn btn-accent',
        // Batch 787 — route directly to /support instead of /profile.
        // Before: click landed on Profile → Personal and the user had
        // to hunt the Support tab. Now a single click drops them at
        // the ticket list + New Ticket button in one step.
        onClick: () => { onClose && onClose(); navigate(paths.support()); }
      }, h(MaterialIcon, { name: 'support_agent', size: 18 }), 'Contact Support')
    )
  );
}
