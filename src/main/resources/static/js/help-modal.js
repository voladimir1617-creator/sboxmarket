// Standalone Help Center — FAQ accordion, getting started guide, keyboard
// shortcut reference, and a Contact Support CTA. Lives on its own route
// `/help`, but also accepts being opened from any button via the same shell.
import { h, useState } from './utils.js';
import { InfoModal } from './info-modal.js';
import { MaterialIcon } from './primitives.js';
import { navigate, paths } from './router.js';

const FAQ = [
  {
    q: 'What is SkinBox?',
    a: "SkinBox is a peer-to-peer marketplace for s&box cosmetic items. Sellers set their own price, and every listing is backed by a real Steam account. The platform is cheaper than going through the Steam store — 3.5% total (2% platform + 1.5% payout) vs Steam's 12%."
  },
  {
    q: 'How do I sign in?',
    a: "Click the blue Steam button in the top-right of any page. You'll be redirected to steamcommunity.com to approve the login. Your password never touches our servers — we only see your public Steam profile via OpenID."
  },
  {
    q: 'How do I list an item I own on Steam?',
    a: "Open Sell Items from the user menu. The Steam Inventory tab fetches your real Steam inventory for s&box (appid 590830) — click any item, set a price, and hit List for Sale. The listing appears on the marketplace immediately and a 2% platform fee is deducted when it sells."
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
    q: 'How do auctions work?',
    a: "Auction listings show a countdown timer and a Bid input. Enter your bid — the minimum is the current price plus $0.05. You can also set an auto-bid cap, and our bot will raise your bid by the minimum increment until it hits your cap. When the timer runs out, the winner's wallet is charged and the seller is credited (minus 2%)."
  },
  {
    q: 'Is my money safe?',
    a: "Deposits and refunds go through Stripe, the same processor used by millions of websites. Withdrawals are queued for manual approval before payout. Session cookies are HttpOnly and SameSite-strict in production, and every wallet write is logged with a correlation id for audit."
  },
  {
    q: 'Why is SkinBox cheaper than Steam?',
    a: "Steam charges 12% on Workshop sales. SkinBox charges 2% to the seller plus 1.5% on withdrawal — 3.5% total. The green '−X%' chip on each card shows exactly how much you save versus the Steam store price."
  },
  {
    q: 'My purchase is stuck, what do I do?',
    a: "Open a ticket from Profile → Support → + New Ticket and include the transaction id from your Trades tab. An agent (or Clara, the automated first-responder) will reply within the hour."
  }
];

const SHORTCUTS = [
  { keys: '/',      desc: 'Focus the marketplace search box' },
  { keys: 'Esc',    desc: 'Close the current item detail / modal / page' },
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
  const [openIdx, setOpenIdx] = useState(0);
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
    h('div', { className: 'help-faq' },
      FAQ.map((item, i) => h('div', {
        key: i, className: `help-faq-row ${openIdx === i ? 'open' : ''}`
      },
        h('button', { className: 'help-faq-q', onClick: () => setOpenIdx(openIdx === i ? -1 : i) },
          h('span', null, item.q),
          h(MaterialIcon, { name: openIdx === i ? 'expand_less' : 'expand_more', size: 20 })
        ),
        openIdx === i && h('div', { className: 'help-faq-a' }, item.a)
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
        onClick: () => { onClose && onClose(); navigate(paths.profile()); }
      }, h(MaterialIcon, { name: 'support_agent', size: 18 }), 'Contact Support')
    )
  );
}
