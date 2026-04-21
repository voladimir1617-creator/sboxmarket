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
    a: "SkinBox is a peer-to-peer marketplace for s&box cosmetic items. Sellers set their own price, and every listing is backed by a real Steam account. The platform is cheaper than going through the Steam store — a flat 2% on each sale vs Steam's 12%. Deposits and withdrawals are free."
  },
  {
    q: 'How do I sign in?',
    a: "Click the blue Steam button in the top-right of any page. You'll be redirected to steamcommunity.com to approve the login. Your password never touches our servers — we only see your public Steam profile via OpenID."
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
    a: "Open Wallet → Deposit, enter any amount between $1 and $10,000, and you'll be handed off to Stripe Checkout. Once the payment clears, the webhook credits your balance automatically. In dev mode without Stripe keys, deposits credit instantly so you can click through the UI."
  },
  {
    q: 'How do withdrawals work?',
    a: "Wallet → Withdraw. Enter an amount and a destination (Stripe Connect ID or payout notes) and your balance is debited immediately into a PENDING withdrawal. An admin approves the payout within 24 hours and the funds are released. If rejected, the full amount is refunded to your wallet and you get a notification."
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
    a: "Auction listings show a countdown timer and a Bid input. Enter your bid — the minimum is the current price plus $0.05. You can also set an auto-bid cap, and our bot will raise your bid by the minimum increment until it hits your cap. When the timer runs out, the winner's wallet is charged and the seller is credited (minus 2%)."
  },
  {
    q: 'Is my money safe?',
    a: "Deposits and refunds go through Stripe, the same processor used by millions of websites. Withdrawals are queued for manual approval before payout. Session cookies are HttpOnly and SameSite-strict in production, and every wallet write is logged with a correlation id for audit."
  },
  {
    q: 'Why is SkinBox cheaper than Steam?',
    a: "Steam charges 12% on Workshop sales. SkinBox charges a flat 2% to the seller at sale time — that's the only fee. Deposits and withdrawals are free; Stripe merchant fees are paid by SkinBox, not the user. The green '−X%' chip on each card shows exactly how much you save versus the Steam store price."
  },
  {
    q: 'My purchase is stuck, what do I do?',
    a: "Open a ticket from Profile → Support → + New Ticket and include the transaction id from your Trades tab. An agent (or Clara, the automated first-responder) will reply within the hour."
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
    a: "Yes — the SkinBox Inventory Valuer extension adds a per-tile price badge on your Steam inventory page and a floating panel with Steam Market vs SkinBox totals. Install it from the Chrome Web Store (link on the site footer when it ships), or load it locally for testing: open chrome://extensions, enable Developer mode, click Load unpacked, and pick the `extension/` folder from the SkinBox repo. No account is required — the extension uses Steam's public priceoverview endpoint + the SkinBox public listings API."
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
    a: "You have 8 days from purchase to confirm receipt or file a dispute. Go to Profile → Trades, find the row, and click 'Report issue' / 'Dispute'. Include the Steam offer URL (or lack thereof) and any screenshots. Staff triage disputes in rotating CSR shifts and will either release escrow, cancel with refund, or escalate. After the 8-day window the trade auto-releases to the seller, but you can still open a support ticket for up to 30 days for staff manual review."
  },
  {
    q: 'What happens if I get banned? Can I appeal?',
    a: "Banned accounts can no longer list, bid, buy, offer, trade, or withdraw. Your Steam session is revoked immediately and future sign-ins land on a read-only state. To appeal, email support@skinbox.market from the Steam-verified address on your account with your Steam ID and a description of the incident. Appeals are reviewed by a senior admin (not the reviewer who banned you) within 3-5 business days. If the ban is lifted, your listings are NOT auto-restored — you'll need to re-list anything you want back on the market."
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

    h('div', { className: 'help-section-title' },
      h(MaterialIcon, { name: 'route', size: 18 }), 'Getting Started'),
    h('div', { className: 'help-steps' },
      STEPS.map((s, i) => h('div', { key: i, className: 'help-step' },
        h('div', { className: 'help-step-num' }, i + 1),
        h(MaterialIcon, { name: s.icon, size: 28 }),
        h('div', { className: 'help-step-title' }, s.title),
        h('div', { className: 'help-step-body' }, s.body)
      ))
    ),

    h('div', { className: 'help-section-title' },
      h(MaterialIcon, { name: 'quiz', size: 18 }), 'Frequently Asked Questions'),
    h('input', {
      className: 'price-input',
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
            'Try a broader keyword, or open a ticket and a CSR will reply within the hour.'),
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
              onClick: (e) => { e.preventDefault(); onClose && onClose(); window.history.pushState({}, '', '/support'); window.dispatchEvent(new PopStateEvent('popstate')); }
            }, 'Open a ticket →')
          )
        )
      : h('div', { className: 'help-faq' },
          filteredFaq.map(item => h('div', {
            key: item.origIdx, className: `help-faq-row ${openIdx === item.origIdx ? 'open' : ''}`
          },
            h('button', { className: 'help-faq-q', onClick: () => setOpenIdx(openIdx === item.origIdx ? -1 : item.origIdx) },
              // Highlight the matched substring so a user searching "2FA"
              // across five FAQ rows can see where the match is without
              // expanding every answer. Case-insensitive; rendered as a
              // <mark> so screen-readers can optionally announce emphasis.
              highlightMatch(item.q, q),
              h(MaterialIcon, { name: openIdx === item.origIdx ? 'expand_less' : 'expand_more', size: 20 })
            ),
            openIdx === item.origIdx && h('div', { className: 'help-faq-a' }, highlightMatch(item.a, q))
          ))
        ),

    h('div', { className: 'help-section-title' },
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
        h('div', { style: { fontSize: 12, color: 'var(--text-muted)', marginTop: 4 } },
          "Open a support ticket and a CSR will reply within the hour. Include a transaction id if it's about a purchase.")
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
