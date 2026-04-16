// All modal dialogs. Each modal is a narrow component with a focused prop
// surface — none of them receive the full App state.
import { h, useState, useEffect, useCallback, useMemo, fmt, timeAgo, discountPct } from './utils.js';
import { ItemImage, RarityBadge, Sparkline, SteamMarketLink, MaterialIcon } from './primitives.js';
import { GridCard } from './cards.js';
import { InfoModal } from './info-modal.js';
import { AuctionBidPanel } from './csfloat-modals.js';
import {
  fetchInventory, fetchMyStall, relistItem, cancelListing,
  fetchIncomingOffers, fetchOutgoingOffers, acceptOffer, rejectOffer, cancelOffer, counterOffer,
  fetchOfferThread, fetchSimilar,
  depositFunds, withdrawFunds, updateStallListing, setAwayMode,
  fetchProfile, fetchSteamInventory, syncSteam, listFromSteam,
  fetchBuyOrders, fetchAutoBids, fetchApiKeys, createApiKey, revokeApiKey,
  fetchSupportTickets, fetchSupportTicket, createSupportTicket, replySupportTicket, resolveSupportTicket,
  fetchTrades, tradeAccept, tradeMarkSent, tradeConfirm, tradeDispute, tradeCancel,
  setEmail, verifyEmail, enroll2fa, confirm2fa, disable2fa,
  fetchListings, fetchItem, leaveReview, fetchReviewSummary
} from './api.js';

export { InfoModal };

// ── Item detail ──────────────────────────────────────────────────
export function ItemModal({ item, listings, history, onClose, onBuy, onMakeOffer, me, onRefresh, onCreateBuyOrder, onAddToCart, cartHas }) {
  const [offerOpen, setOfferOpen] = useState(false);
  const [thread, setThread] = useState(null);
  const [chartRange, setChartRange] = useState('30D');
  const [similar, setSimilar] = useState(null);
  // Seller rating cache — { sellerUserId → { count, average } }. Populated
  // in parallel when the modal opens so every row in the Active Listings
  // section can show an inline reputation chip next to the seller name.
  const [sellerRatings, setSellerRatings] = useState({});
  // Pull the offer thread for the cheapest listing so buyers can see any
  // existing counter conversation before they bargain themselves.
  useEffect(() => {
    if (!listings[0]) return;
    fetchOfferThread(listings[0].id).then(setThread);
  }, [listings[0]?.id]);
  useEffect(() => {
    if (!item) return;
    fetchSimilar(item.id).then(setSimilar);
  }, [item?.id]);
  useEffect(() => {
    const ids = [...new Set(listings.map(l => l.sellerUserId).filter(Boolean))];
    if (ids.length === 0) return;
    let alive = true;
    Promise.all(ids.map(id =>
      fetchReviewSummary(id).then(s => ({ id, summary: s })).catch(() => ({ id, summary: null }))
    )).then(results => {
      if (!alive) return;
      const next = {};
      results.forEach(r => { if (r.summary) next[r.id] = r.summary; });
      setSellerRatings(next);
    });
    return () => { alive = false; };
  }, [listings.map(l => l.id).join(',')]);

  // Filter the history for the selected chart range. Our seed data is 30
  // days, so 7D = last 7 rows, 30D = all, ALL = all. Once we have longer
  // history this still works unchanged.
  const slicedHistory = useMemo(() => {
    if (!history || history.length === 0) return history;
    if (chartRange === '7D')  return history.slice(-7);
    if (chartRange === '30D') return history.slice(-30);
    return history;
  }, [history, chartRange]);
  const [offerAmt, setOfferAmt]   = useState('');
  const [offerErr, setOfferErr]   = useState('');
  const [offerBusy, setOfferBusy] = useState(false);
  const trendUp = item.trendPercent > 0, trendFlat = item.trendPercent === 0;
  const change30d = history.length > 1
    ? (parseFloat(item.lowestPrice) - parseFloat(history[0]?.price || item.lowestPrice)).toFixed(2)
    : '0.00';
  const changePct = history.length > 1 && parseFloat(history[0]?.price)
    ? ((change30d / parseFloat(history[0].price)) * 100).toFixed(1)
    : '0.0';

  return h('div', { className: 'modal-backdrop', onClick: onClose },
    h('div', { className: 'modal', onClick: e => e.stopPropagation() },
      h('button', { className: 'modal-close', onClick: onClose, 'aria-label': 'Close item details' }, '✕'),
      h('div', { className: 'modal-header' },
        h('div', { className: 'modal-preview' },
          h(ItemImage, { item, variant: 'hero' }),
          h(RarityBadge, { rarity: item.rarity })
        ),
        h('div', null,
          h('div', { className: 'modal-cat' }, item.category),
          h('div', { className: 'modal-name' }, item.name),
          h('div', { className: 'modal-stats' },
            h('div', { className: 'modal-stat-box' },
              h('div', { className: 'modal-stat-label' }, 'Floor Price'),
              h('div', { className: 'modal-stat-val accent' }, fmt(item.lowestPrice))
            ),
            h('div', { className: 'modal-stat-box' },
              h('div', { className: 'modal-stat-label' }, 'Steam Price'),
              item.steamPrice
                ? h('div', null,
                    h('div', { className: 'modal-stat-val', style: { textDecoration: 'line-through', color: 'var(--text-muted)' } }, fmt(item.steamPrice)),
                    h('div', { style: { fontSize: 11, fontWeight: 700, color: 'var(--green)', marginTop: 2 } },
                      `Save ${discountPct(item.lowestPrice, item.steamPrice)}%`
                    )
                  )
                : h('div', { className: 'modal-stat-val' }, '—')
            ),
            h('div', { className: 'modal-stat-box' },
              h('div', { className: 'modal-stat-label' }, '30D Change'),
              h('div', { className: `modal-stat-val ${trendFlat ? '' : trendUp ? 'green' : 'red'}` },
                `${trendUp ? '+' : ''}${change30d} (${changePct}%)`
              )
            ),
            h('div', { className: 'modal-stat-box' },
              h('div', { className: 'modal-stat-label' }, 'Supply'),
              h('div', { className: 'modal-stat-val' }, Number(item.supply).toLocaleString())
            )
          )
        )
      ),

      h('div', { className: 'modal-body' },
        listings[0] && listings[0].listingType === 'AUCTION' && h(AuctionBidPanel, {
          listing: listings[0], me, onPlaced: onRefresh
        }),
        h('div', { className: 'modal-section-title' },
          h('div', { className: 'section-title-dot' }),
          'Price History',
          h('div', { className: 'chart-range' },
            ['7D','30D','ALL'].map(r => h('button', {
              key: r,
              className: `chart-range-btn ${chartRange === r ? 'active' : ''}`,
              onClick: () => setChartRange(r)
            }, r))
          )
        ),
        h('div', { className: 'chart-wrap' },
          h(Sparkline, { data: slicedHistory, color: trendUp ? '#4ade80' : trendFlat ? '#60a5fa' : '#f87171', height: 150 })
        ),

        thread && thread.length > 0 && h('div', null,
          h('div', { className: 'modal-section-title' }, h('div', { className: 'section-title-dot' }), `Offer thread (${thread.length})`),
          h('div', { className: 'item-offer-thread' },
            thread.slice(-8).map(o => h('div', {
              key: o.id,
              className: `item-offer-bubble ${o.author === 'SELLER' ? 'seller' : 'buyer'} ${o.status !== 'PENDING' ? 'past' : ''}`
            },
              h('div', { className: 'item-offer-head' },
                o.author === 'SELLER' ? 'Seller counter' : (o.buyerName || 'buyer'),
                ' · ', timeAgo(o.createdAt)
              ),
              h('div', { className: 'item-offer-amt' }, fmt(o.amount)),
              h('div', { className: 'item-offer-status' }, o.status)
            ))
          )
        ),
        h('div', { className: 'modal-section-title' }, h('div', { className: 'section-title-dot' }), `Active Listings (${listings.length})`),
        h('div', { className: 'modal-listings' },
          listings.length === 0
            ? h('div', { style: { color: 'var(--text-muted)', fontSize: 13, padding: '12px 0' } }, 'No active listings')
            : listings.slice(0, 6).map(l => {
                const rating = l.sellerUserId ? sellerRatings[l.sellerUserId] : null;
                return h('div', { key: l.id, className: 'modal-listing-row' },
                  h('div', { className: 'modal-seller-av' }, (l.sellerAvatar || l.sellerName?.substring(0,2) || 'US').toUpperCase()),
                  h('div', { className: 'modal-seller-info' },
                    h('span', { className: 'modal-seller-name' }, l.sellerName),
                    rating && rating.count > 0 && h('span', { className: 'modal-seller-rating', title: `${rating.count} review${rating.count === 1 ? '' : 's'}` },
                      '★ ', rating.average.toFixed(1),
                      h('span', { className: 'modal-seller-rating-count' }, ` (${rating.count})`)
                    )
                  ),
                  h('span', { className: 'modal-listing-condition' }, '#' + l.id),
                  h('div', { className: 'modal-listing-rarity-bar' }),
                  h('span', { className: 'modal-listing-price' }, fmt(l.price)),
                  h('button', { className: 'buy-btn', onClick: () => onBuy(l.id) }, 'Buy')
                );
              })
        ),

        // ── Similar items strip ────────────────────────────────────
        similar && similar.length > 0 && h('div', null,
          h('div', { className: 'modal-section-title', style: { marginTop: 22 } },
            h('div', { className: 'section-title-dot' }), 'You might also like'),
          h('div', { className: 'similar-strip' },
            similar.map(it => h('a', {
              key: it.id,
              className: 'similar-card',
              href: '/item/' + it.id,
              title: it.name
            },
              h('div', { className: 'similar-thumb' }, h(ItemImage, { item: it, variant: 'thumb' })),
              h('div', { className: 'similar-name' }, it.name),
              h('div', { className: 'similar-price' }, fmt(it.lowestPrice || 0))
            ))
          )
        )
      ),

      false && h('div', { style: { padding: '0 30px 14px' } },
        h('div', { className: 'wallet-input-label' }, 'Make an offer (must be below floor) [moved below]'),
        h('div', { style: { display: 'flex', gap: 10 } },
          h('input', {
            className: 'wallet-amount-input',
            type: 'number', min: '0.01', step: '0.01',
            placeholder: (parseFloat(item.lowestPrice) * 0.85).toFixed(2),
            value: offerAmt,
            onChange: e => setOfferAmt(e.target.value),
            style: { flex: 1 }
          }),
          h('button', {
            className: 'btn btn-accent',
            style: { padding: '0 22px', fontSize: 13 },
            disabled: offerBusy || !offerAmt,
            onClick: async () => {
              setOfferErr('');
              setOfferBusy(true);
              try {
                const res = await onMakeOffer(listings[0]?.id, parseFloat(offerAmt));
                if (res && res.error) { setOfferErr(res.message || res.error); return; }
                setOfferOpen(false);
                setOfferAmt('');
                onClose();
              } finally { setOfferBusy(false); }
            }
          }, offerBusy ? '...' : 'Send')
        ),
        offerErr && h('div', { className: 'wallet-error' }, offerErr)
      ),

      h('div', { className: 'modal-actions' },
        h('button', {
          className: 'btn btn-accent',
          disabled: !listings[0],
          onClick: () => listings[0] && onBuy(listings[0].id),
          'aria-label': listings[0] ? `Buy for ${fmt(listings[0].price)}` : 'Out of stock'
        },
          listings[0] ? `Buy Now · ${fmt(listings[0].price)}` : 'Out of Stock'
        ),
        h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)' },
          onClick: () => setOfferOpen(o => !o),
          disabled: !listings[0]
        }, offerOpen ? 'Cancel Offer' : 'Make Offer'),
        onCreateBuyOrder && h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)' },
          onClick: () => onCreateBuyOrder(item),
          title: 'Create a standing buy order for this item'
        }, h(MaterialIcon, { name: 'bolt', size: 16 }), ' Place Buy Order'),
        onAddToCart && listings[0] && h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)' },
          disabled: cartHas && cartHas(listings[0].id),
          onClick: () => onAddToCart(listings[0]),
          title: 'Add cheapest listing to cart'
        }, h(MaterialIcon, { name: 'shopping_cart', size: 16 }),
          cartHas && cartHas(listings[0].id) ? ' In Cart' : ' Add to Cart'),
        h(SteamMarketLink, { item }),
        h('button', { className: 'btn btn-ghost btn-wishlist', style: { border: '1px solid var(--border)' } }, '♡')
      ),

      offerOpen && h('div', { style: { padding: '14px 30px', background: 'var(--bg-secondary)', borderRadius: 8, margin: '10px 30px' } },
        h('div', { className: 'wallet-input-label', style: { marginBottom: 8, fontSize: 13, color: 'var(--text-secondary)' } }, 'Your offer (must be below asking price)'),
        h('div', { style: { display: 'flex', gap: 10 } },
          h('input', {
            className: 'wallet-amount-input',
            type: 'number', min: '0.01', step: '0.01',
            placeholder: (parseFloat(item.lowestPrice) * 0.85).toFixed(2),
            value: offerAmt,
            onChange: e => setOfferAmt(e.target.value),
            style: { flex: 1 },
            autoFocus: true
          }),
          h('button', {
            className: 'btn btn-accent',
            style: { padding: '0 22px', fontSize: 13 },
            disabled: offerBusy || !offerAmt,
            onClick: async () => {
              setOfferErr('');
              setOfferBusy(true);
              try {
                const res = await onMakeOffer(listings[0]?.id, parseFloat(offerAmt));
                if (res && res.error) { setOfferErr(res.message || res.error); return; }
                setOfferOpen(false);
                setOfferAmt('');
                onClose();
              } finally { setOfferBusy(false); }
            }
          }, offerBusy ? '...' : 'Send Offer')
        ),
        offerErr && h('div', { style: { color: 'var(--red)', fontSize: 12, marginTop: 6 } }, offerErr)
      )
    )
  );
}

// ── FAQ / Support ───────────────────────────────────────────────
export function FaqModal({ onClose }) {
  const Q = (q, a) => h('div', { style: { marginBottom: 20 } },
    h('div', { style: { fontWeight: 700, color: 'var(--text-primary)', marginBottom: 6, fontSize: 14 } }, q),
    h('div', { style: { color: 'var(--text-secondary)', lineHeight: 1.6, fontSize: 13 } }, a)
  );
  return h(InfoModal, { title: 'Support & Help', onClose },
    h('div', { style: { fontSize: 12, color: 'var(--text-muted)', marginBottom: 20, padding: '10px 14px', background: 'var(--accent-dim)', border: '1px solid var(--accent-border)', borderRadius: 8 } },
      'Need something not covered here? Drop a message in the Live Chat on the left and someone will pick it up.'),
    Q('What is SkinBox?',
      "SkinBox is a peer-to-peer marketplace for s&box cosmetic items. Every listing comes from a real seller who sets their own price — we're the middle layer that makes transactions safe, fast, and cheaper than going through the Steam store."),
    Q('How do I sign in?',
      "Click the blue Steam button in the top-right. You'll bounce to steamcommunity.com, approve the login, and land back here already authenticated. Your Steam password never touches our servers — everything goes through OpenID."),
    Q('How do I buy something?',
      "Top up your wallet first, then click any item and hit Buy. Funds are charged from your balance instantly — there's no bid-and-wait or 7-day trade hold like the Steam market."),
    Q('How does depositing work?',
      "Open your Wallet, pick Deposit, enter an amount (anything from $1 to $10,000), and you'll be handed to Stripe's checkout page. Once the payment clears, our webhook credits your balance automatically."),
    Q('How do withdrawals work?',
      "From your Wallet, pick Withdraw, enter a destination (Stripe Connect id, email, or a note) and the amount. Your balance is debited immediately and the payout is processed within 24 hours. A small network fee may apply depending on destination."),
    Q('Why is SkinBox cheaper than Steam?',
      "Steam charges 12% in platform fees on Workshop sales and forces sellers into their pricing ladder. On SkinBox, sellers set whatever price they like — usually 10-30% below what the Steam store asks. The green '−%' chip on each card shows exactly how much you save versus Steam."),
    Q('Do s&box items have wear levels?',
      "No. That's a Counter-Strike thing. s&box cosmetics are single items without Factory-New / Field-Tested / Battle-Scarred variants — closer to how Rust skins work. The item you pick is the exact item you receive."),
    Q('Can I sell the items I own?',
      "Yes. Anything you've bought on SkinBox appears under Sell Items. Pick an item, set a price, and it goes live in your stall under My Stall. When it sells, the buyer's payment (minus a 2% platform fee) drops straight into your wallet."),
    Q('What is a Stall?',
      "Your Stall is your personal storefront — the list of items you currently have up for sale. Other users can browse it via your profile. You can cancel any listing from My Stall and the item returns to your inventory."),
    Q('What are Offers?',
      "Offers are non-binding price suggestions. A buyer can propose less than your asking price; you get a notification and can accept or reject from the Offers tab."),
    Q('Is my money safe?',
      "Deposits go through Stripe, the same processor used by millions of websites. We never store card details — only the amount and a Stripe reference. Withdrawal requests are logged and reviewed before payout. All balances are held in USD."),
    Q('I found a bug / my purchase is stuck',
      "Open Live Chat on the left or the Support entry in the user menu and include the transaction id from your Trades tab. We'll refund or retry as needed.")
  );
}

// ── Settings ────────────────────────────────────────────────────
export function SettingsModal({ onClose }) {
  const [currency, setCurrency] = useState(localStorage.getItem('sb_currency') || 'USD');
  const [notifs, setNotifs]     = useState(localStorage.getItem('sb_notifs') !== 'false');
  const [sounds, setSounds]     = useState(localStorage.getItem('sb_sounds') !== 'false');
  const [reduceMotion, setRM]   = useState(localStorage.getItem('sb_reduce_motion') === '1');
  const [highContrast, setHC]   = useState(localStorage.getItem('sb_contrast') === '1');

  useEffect(() => {
    localStorage.setItem('sb_currency', currency);
    // Fire a storage event so other tabs update immediately; the fmt() helper
    // reads localStorage on every call so the next render already shows the
    // new currency in THIS tab.
  }, [currency]);
  useEffect(() => { localStorage.setItem('sb_notifs', notifs); }, [notifs]);
  useEffect(() => { localStorage.setItem('sb_sounds', sounds); }, [sounds]);
  useEffect(() => {
    localStorage.setItem('sb_reduce_motion', reduceMotion ? '1' : '0');
    document.documentElement.classList.toggle('reduce-motion', reduceMotion);
  }, [reduceMotion]);
  useEffect(() => {
    localStorage.setItem('sb_contrast', highContrast ? '1' : '0');
    document.documentElement.classList.toggle('high-contrast', highContrast);
  }, [highContrast]);

  const Row = (label, sublabel, control) => h('div', { className: 'settings-row' },
    h('div', null,
      h('div', { className: 'settings-label' }, label),
      sublabel && h('div', { className: 'settings-sublabel' }, sublabel)
    ),
    control
  );
  const Toggle = (on, onChange) => h('div', {
    className: `chat-toggle ${on ? '' : 'off'}`,
    onClick: onChange
  });

  const resetLocal = () => {
    if (!confirm('Reset all local preferences (currency, theme, cart, watchlist)? Your account data is not affected.')) return;
    ['sb_currency','sb_notifs','sb_sounds','sb_reduce_motion','sb_contrast',
     'sb_privacy','sb_watchlist','sb_cart','sb_theme'].forEach(k => localStorage.removeItem(k));
    location.reload();
  };

  return h(InfoModal, { title: 'Settings', onClose },
    Row('Currency', 'Prices shown in your chosen currency (stored as USD)', h('select', {
        className: 'sort-select', value: currency,
        onChange: e => setCurrency(e.target.value)
      },
      h('option', { value: 'USD' }, 'USD · $'),
      h('option', { value: 'EUR' }, 'EUR · €'),
      h('option', { value: 'GBP' }, 'GBP · £'),
      h('option', { value: 'CAD' }, 'CAD · CA$'),
      h('option', { value: 'AUD' }, 'AUD · A$'),
      h('option', { value: 'BRL' }, 'BRL · R$'),
      h('option', { value: 'JPY' }, 'JPY · ¥')
    )),
    Row('Sale notifications', 'Toast when someone buys', Toggle(notifs, () => setNotifs(v => !v))),
    Row('Sound effects',      'Play sounds on actions', Toggle(sounds, () => setSounds(v => !v))),
    Row('Reduce motion',      'Disable animations for card hover + ticker scroll',
      Toggle(reduceMotion, () => setRM(v => !v))),
    Row('High contrast',      'Boost text / border contrast for readability',
      Toggle(highContrast, () => setHC(v => !v))),
    Row('Accent colour',      'Cycle through theme presets in the top-nav 🎨 menu.',
      h('span', { style: { color: 'var(--text-muted)', fontSize: 12 } }, 'Dark theme')
    ),
    h('div', { style: { marginTop: 24, paddingTop: 18, borderTop: '1px solid var(--border)' } },
      h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 10 } },
        'These preferences live in your browser. Your account data (wallet, listings, trades) is stored server-side and is not affected by this button.'),
      h('button', {
        className: 'btn btn-ghost',
        style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '8px 14px', fontSize: 11 },
        onClick: resetLocal
      }, 'Reset local preferences')
    )
  );
}

// ── Profile (tabbed — mirrors the CSFloat profile screen) ───────
export function ProfileModal({ onClose, me, wallet, transactions, onRefresh }) {
  const [tab, setTab]             = useState('personal');
  const [profile, setProfile]     = useState(null);
  const [privacy, setPrivacy]     = useState(() => localStorage.getItem('sb_privacy') === '1');
  const [syncing, setSyncing]     = useState(false);

  useEffect(() => {
    localStorage.setItem('sb_privacy', privacy ? '1' : '0');
  }, [privacy]);

  useEffect(() => {
    if (!me) return;
    fetchProfile().then(setProfile);
  }, [me]);

  if (!me) return h(InfoModal, { title: 'Profile', onClose },
    h('div', { className: 'empty-inline' },
      h('div', { className: 'empty-icon' }, '🔒'),
      h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'Sign in to view your profile.')));

  const maskAmount = (val) => privacy ? '$•••••' : fmt(val);

  const runSync = async () => {
    setSyncing(true);
    try { await syncSteam(); const fresh = await fetchProfile(); setProfile(fresh); }
    finally { setSyncing(false); }
  };

  const TABS = [
    { id: 'personal',     label: 'Personal Info' },
    { id: 'transactions', label: 'Transactions' },
    { id: 'buyorders',    label: 'Buy Orders' },
    { id: 'autobids',     label: 'Auto-Bids' },
    { id: 'trades',       label: 'Trades' },
    { id: 'offers',       label: 'Offers' },
    { id: 'support',      label: 'Support' },
    { id: 'developers',   label: 'Developers' },
  ];

  return h(InfoModal, { title: 'Profile', onClose },
    /* Hero: avatar + name + earnings privacy toggle + account standing bar */
    h('div', { className: 'profile-hero' },
      h('div', { className: 'profile-avatar' },
        me.avatarUrl ? h('img', { src: me.avatarUrl, alt: me.displayName }) : (me.displayName || 'U').substring(0, 2).toUpperCase()
      ),
      h('div', { style: { flex: 1, minWidth: 0 } },
        h('div', { className: 'profile-name' }, me.displayName || 'Player'),
        h('div', { className: 'profile-id' },
          'Steam ID · ',
          h('span', { style: { fontFamily: 'JetBrains Mono, monospace' } }, me.steamId64)
        ),
        me.profileUrl && h('a', { href: me.profileUrl, target: '_blank', rel: 'noopener noreferrer', className: 'profile-link' }, 'View Steam profile ↗')
      ),
      h('button', {
        className: 'profile-privacy',
        onClick: () => setPrivacy(p => !p),
        title: privacy ? 'Show amounts' : 'Hide amounts'
      }, privacy ? '👁  Show amounts' : '🙈  Hide amounts')
    ),

    /* Hero stats — amounts optionally masked */
    h('div', { className: 'profile-stats' },
      h('div', { className: 'profile-stat' },
        h('div', { className: 'profile-stat-label' }, 'Balance'),
        h('div', { className: 'profile-stat-val accent' }, maskAmount(wallet?.balance || 0))
      ),
      h('div', { className: 'profile-stat' },
        h('div', { className: 'profile-stat-label' }, 'Total Sold'),
        h('div', { className: 'profile-stat-val' }, privacy ? '$•••••' : fmt(profile?.stats?.totalSold || 0))
      ),
      h('div', { className: 'profile-stat' },
        h('div', { className: 'profile-stat-label' }, 'Total Purchased'),
        h('div', { className: 'profile-stat-val' }, privacy ? '$•••••' : fmt(profile?.stats?.totalPurchased || 0))
      ),
      h('div', { className: 'profile-stat' },
        h('div', { className: 'profile-stat-label' }, 'Net'),
        h('div', {
          className: `profile-stat-val ${parseFloat(profile?.stats?.net || 0) >= 0 ? 'green' : 'red'}`
        }, privacy ? '$•••••' : fmt(profile?.stats?.net || 0))
      )
    ),

    /* Tabs */
    h('div', { className: 'profile-tabs' },
      TABS.map(t => h('button', {
        key: t.id,
        className: `profile-tab ${tab === t.id ? 'active' : ''}`,
        onClick: () => setTab(t.id)
      }, t.label))
    ),

    tab === 'personal' && h(ProfilePersonalTab, { me, profile, syncing, onSync: runSync }),
    tab === 'transactions' && h(ProfileTransactionsTab, { transactions, privacy }),
    tab === 'buyorders'   && h(ProfileBuyOrdersTab, null),
    tab === 'autobids'    && h(ProfileAutoBidsTab, null),
    tab === 'trades'      && h(ProfileTradesTab, { me, privacy }),
    tab === 'offers'      && h(ProfileOffersTab, null),
    tab === 'support'     && h(ProfileSupportTab, null),
    tab === 'developers'  && h(ProfileDevelopersTab, null)
  );
}

function ProfilePersonalTab({ me, profile, syncing, onSync }) {
  const [editingEmail, setEditingEmail] = useState(false);
  const [emailDraft, setEmailDraft]     = useState('');
  const [emailToken, setEmailToken]     = useState('');
  const [emailResult, setEmailResult]   = useState(null);

  const [enrolling, setEnrolling]   = useState(false);
  const [twofaSecret, setTwofaSecret] = useState('');
  const [twofaUrl, setTwofaUrl]     = useState('');
  const [twofaCode, setTwofaCode]   = useState('');
  const [twofaErr, setTwofaErr]     = useState('');
  const [twofaBusy, setTwofaBusy]   = useState(false);

  const hasEmail = !!profile?.user?.email;
  const emailVerified = profile?.user?.emailVerified;
  const has2fa = !!profile?.twoFactorEnabled;

  const saveEmail = async () => {
    setEmailResult(null);
    if (!emailDraft.trim()) return;
    const res = await setEmail(emailDraft.trim());
    if (res.code || res.error) { setEmailResult({ err: res.message || res.error }); return; }
    setEmailResult({ ok: true, token: res.token });
    setEditingEmail(false);
  };
  const confirmEmail = async () => {
    if (!emailToken.trim()) return;
    const res = await verifyEmail(emailToken.trim());
    if (res.code || res.error) { setEmailResult({ err: res.message || res.error }); return; }
    setEmailResult({ ok: true, verified: true });
    setEmailToken('');
  };

  const startEnroll = async () => {
    setTwofaErr('');
    setTwofaBusy(true);
    try {
      const res = await enroll2fa();
      if (res.code || res.error) { setTwofaErr(res.message || res.error); return; }
      setTwofaSecret(res.secret);
      setTwofaUrl(res.otpauthUrl);
      setEnrolling(true);
    } finally { setTwofaBusy(false); }
  };
  const finishEnroll = async () => {
    setTwofaErr('');
    if (!/^\d{6}$/.test(twofaCode)) { setTwofaErr('Enter the 6-digit code'); return; }
    setTwofaBusy(true);
    try {
      const res = await confirm2fa(twofaCode);
      if (res.code || res.error) { setTwofaErr(res.message || res.error); return; }
      setEnrolling(false); setTwofaCode(''); setTwofaSecret(''); setTwofaUrl('');
      alert('✓ Two-factor authentication enabled. Your next withdrawal will ask for a code.');
    } finally { setTwofaBusy(false); }
  };
  const disableTwofa = async () => {
    const code = prompt('Enter a current 6-digit code to confirm disabling 2FA:');
    if (!code) return;
    const res = await disable2fa(code);
    if (res.code || res.error) { alert(res.message || res.error); return; }
    alert('2FA disabled.');
  };

  return h('div', { className: 'profile-panel' },
    h('div', { className: 'profile-row' },
      h('div', { className: 'profile-row-label' }, 'Display Name'),
      h('div', { className: 'profile-row-value' }, me.displayName || '—')
    ),
    h('div', { className: 'profile-row' },
      h('div', { className: 'profile-row-label' }, 'Steam ID 64'),
      h('div', { className: 'profile-row-value mono' }, me.steamId64)
    ),

    // ── Email ────────────────────────────────────────────────────
    h('div', { className: 'profile-row' },
      h('div', { className: 'profile-row-label' }, 'Email'),
      h('div', { className: 'profile-row-value', style: { flexDirection: 'column', alignItems: 'flex-start', gap: 8 } },
        !editingEmail && h('div', { style: { display: 'flex', alignItems: 'center', gap: 10 } },
          h('span', { className: 'mono' }, profile?.user?.email || '(not set)'),
          hasEmail && h('span', {
            style: {
              fontSize: 10, fontWeight: 700, padding: '2px 8px', borderRadius: 4,
              background: emailVerified ? 'var(--green-dim)' : 'rgba(251,191,36,0.15)',
              color: emailVerified ? 'var(--green)' : '#fbbf24'
            }
          }, emailVerified ? '✓ Verified' : 'Unverified'),
          h('button', {
            className: 'btn btn-ghost',
            style: { padding: '5px 10px', fontSize: 11, border: '1px solid var(--border)' },
            onClick: () => { setEditingEmail(true); setEmailDraft(profile?.user?.email || ''); }
          }, 'Edit')
        ),
        editingEmail && h('div', { style: { display: 'flex', gap: 6, width: '100%' } },
          h('input', {
            className: 'price-input', style: { flex: 1 },
            type: 'email', placeholder: 'you@example.com',
            value: emailDraft, onChange: e => setEmailDraft(e.target.value)
          }),
          h('button', { className: 'buy-btn', onClick: saveEmail }, 'Save'),
          h('button', { className: 'btn btn-ghost', style: { padding: '6px 10px', fontSize: 11 }, onClick: () => setEditingEmail(false) }, '✕')
        ),
        // If /email returned a token (dev-mode), show the verify input
        emailResult?.token && h('div', { style: { padding: 10, background: 'var(--accent-dim)', border: '1px solid var(--accent-border)', borderRadius: 6, width: '100%' } },
          h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 6 } },
            'Dev mode: use this token to verify the email.'),
          h('div', { className: 'mono', style: { fontSize: 11, color: 'var(--accent)', marginBottom: 6 } }, emailResult.token),
          h('div', { style: { display: 'flex', gap: 6 } },
            h('input', { className: 'price-input', style: { flex: 1 }, placeholder: 'Paste token', value: emailToken, onChange: e => setEmailToken(e.target.value) }),
            h('button', { className: 'buy-btn', onClick: confirmEmail }, 'Verify')
          )
        ),
        emailResult?.verified && h('div', { style: { fontSize: 11, color: 'var(--green)' } }, '✓ Email verified'),
        emailResult?.err && h('div', { className: 'wallet-error' }, emailResult.err)
      )
    ),

    // ── 2FA ──────────────────────────────────────────────────────
    h('div', { className: 'profile-row' },
      h('div', { className: 'profile-row-label' }, 'Two-Factor Auth'),
      h('div', { className: 'profile-row-value', style: { flexDirection: 'column', alignItems: 'flex-start', gap: 8 } },
        !enrolling && !has2fa && h('div', { style: { display: 'flex', alignItems: 'center', gap: 10 } },
          h('span', { style: { fontSize: 12, color: 'var(--text-muted)' } }, 'Disabled'),
          h('button', { className: 'btn btn-accent', style: { padding: '6px 14px', fontSize: 11 }, disabled: twofaBusy, onClick: startEnroll }, 'Enable 2FA')
        ),
        !enrolling && has2fa && h('div', { style: { display: 'flex', alignItems: 'center', gap: 10 } },
          h('span', { style: { fontSize: 12, color: 'var(--green)', fontWeight: 700 } }, '✓ Enabled'),
          h('button', { className: 'btn btn-ghost', style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '5px 10px', fontSize: 11 }, onClick: disableTwofa }, 'Disable')
        ),
        enrolling && h('div', { className: 'twofa-enroll' },
          h('div', { style: { fontSize: 12, color: 'var(--text-secondary)', marginBottom: 8 } },
            'Scan the QR below in Google Authenticator, Authy, or 1Password — OR paste the secret manually:'),
          h('div', { style: { display: 'flex', gap: 14, alignItems: 'center' } },
            h('img', {
              alt: '2FA QR',
              style: { width: 160, height: 160, background: 'white', borderRadius: 6, padding: 4 },
              src: 'https://api.qrserver.com/v1/create-qr-code/?size=160x160&data=' + encodeURIComponent(twofaUrl)
            }),
            h('div', null,
              h('div', { style: { fontSize: 10, fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: 0.5 } }, 'Secret'),
              h('div', { className: 'mono', style: { fontSize: 12, color: 'var(--accent)', wordBreak: 'break-all', userSelect: 'all' } }, twofaSecret)
            )
          ),
          h('div', { className: 'wallet-input-label', style: { marginTop: 14 } }, '6-digit code from your app'),
          h('input', {
            className: 'wallet-amount-input',
            type: 'text',
            inputMode: 'numeric',
            maxLength: 6,
            placeholder: '000000',
            value: twofaCode,
            onChange: e => setTwofaCode(e.target.value.replace(/\D/g, ''))
          }),
          twofaErr && h('div', { className: 'wallet-error' }, twofaErr),
          h('div', { style: { display: 'flex', gap: 10, marginTop: 10 } },
            h('button', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)' }, onClick: () => setEnrolling(false) }, 'Cancel'),
            h('button', { className: 'btn btn-accent', disabled: twofaBusy || twofaCode.length !== 6, onClick: finishEnroll }, 'Confirm & Enable')
          )
        )
      )
    ),

    h('div', { className: 'profile-row' },
      h('div', { className: 'profile-row-label' }, 'Steam Inventory Size'),
      h('div', { className: 'profile-row-value mono' }, (profile?.user?.steamInventorySize ?? 0) + ' items')
    ),
    h('div', { className: 'profile-row' },
      h('div', { className: 'profile-row-label' }, 'Last Steam Sync'),
      h('div', { className: 'profile-row-value' },
        profile?.user?.lastSyncedAt ? timeAgo(profile.user.lastSyncedAt) : 'Never',
        h('button', { className: 'btn btn-ghost', style: { marginLeft: 12, padding: '5px 10px', fontSize: 11, border: '1px solid var(--border)' }, disabled: syncing, onClick: onSync },
          syncing ? 'Syncing…' : 'Sync Now')
      )
    ),
    h('div', { className: 'profile-row' },
      h('div', { className: 'profile-row-label' }, 'Account Created'),
      h('div', { className: 'profile-row-value' }, profile?.user?.createdAt ? new Date(profile.user.createdAt).toLocaleDateString() : '—')
    )
  );
}

function ProfileTransactionsTab({ transactions, privacy }) {
  if (!transactions || transactions.length === 0) {
    return h('div', { className: 'empty-inline' },
      h('div', { className: 'empty-icon' }, '📋'),
      h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'No transactions yet.'));
  }
  return h('table', { className: 'db-table' },
    h('thead', null, h('tr', null,
      h('th', null, 'ID'),
      h('th', null, 'Type'),
      h('th', null, 'Description'),
      h('th', { className: 'right' }, 'Amount'),
      h('th', { className: 'right' }, 'Status')
    )),
    h('tbody', null,
      transactions.map(tx => h('tr', { key: tx.id, className: 'db-row' },
        h('td', { className: 'db-rank' }, '#' + tx.id),
        h('td', { style: { fontSize: 11, fontWeight: 700, color: 'var(--text-secondary)' } }, tx.type),
        h('td', { style: { fontSize: 11, color: 'var(--text-muted)' } }, tx.description || tx.stripeReference),
        h('td', { className: 'right db-mono' }, privacy ? '$•••••' : fmt(tx.amount)),
        h('td', { className: 'right', style: { fontSize: 10, fontWeight: 700 } }, tx.status)
      ))
    )
  );
}

function ProfileBuyOrdersTab() {
  const [orders, setOrders] = useState(null);
  useEffect(() => { fetchBuyOrders().then(setOrders); }, []);
  if (orders === null) return h('div', { className: 'spinner' });
  if (orders.length === 0) return h('div', { className: 'empty-inline' },
    h('div', { className: 'empty-icon' }, '🛒'),
    h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'No active buy orders. Create one from the user menu.'));
  return h('div', { className: 'buyorder-list' },
    orders.map(o => h('div', { key: o.id, className: `buyorder-row ${(o.status || '').toLowerCase()}` },
      h('div', { style: { flex: 1, minWidth: 0 } },
        h('div', { className: 'buyorder-title' }, o.itemName || ((o.category || 'Any') + ' · ' + (o.rarity || 'any rarity'))),
        h('div', { className: 'buyorder-sub' }, 'Qty ', h('strong', null, o.quantity), ' / ', o.originalQuantity, ' · ', timeAgo(o.createdAt))
      ),
      h('div', { style: { textAlign: 'right' } },
        h('div', { className: 'buyorder-cap' }, '≤ ' + fmt(o.maxPrice)),
        h('div', { className: `buyorder-status ${o.status}` }, o.status)
      )
    ))
  );
}

function ProfileAutoBidsTab() {
  const [bids, setBids] = useState(null);
  useEffect(() => { fetchAutoBids().then(setBids); }, []);
  if (bids === null) return h('div', { className: 'spinner' });
  if (bids.length === 0) return h('div', { className: 'empty-inline' },
    h('div', { className: 'empty-icon' }, '⚡'),
    h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'No active auto-bids. Place one from any auction listing.'));
  return h('table', { className: 'db-table' },
    h('thead', null, h('tr', null,
      h('th', null, 'ID'), h('th', null, 'Listing'),
      h('th', { className: 'right' }, 'Current'),
      h('th', { className: 'right' }, 'Max'),
      h('th', { className: 'right' }, 'Placed')
    )),
    h('tbody', null, bids.map(b => h('tr', { key: b.id, className: 'db-row' },
      h('td', { className: 'db-rank' }, '#' + b.id),
      h('td', null, 'Listing #' + b.listingId),
      h('td', { className: 'right db-mono accent' }, fmt(b.amount)),
      h('td', { className: 'right db-mono' }, fmt(b.maxAmount || b.amount)),
      h('td', { className: 'right', style: { fontSize: 11, color: 'var(--text-muted)' } }, timeAgo(b.createdAt))
    )))
  );
}

function ProfileTradesTab({ me, privacy }) {
  // Real escrow-state trades via /api/trades. Replaces the old "list every
  // PURCHASE/SALE transaction" behaviour — those are in Transactions tab now.
  // Each row shows the full state machine and only renders the button that
  // applies to the viewing user (seller-accept, seller-sent, buyer-confirm,
  // dispute, cancel).
  const [trades, setTrades] = useState(null);
  const [busy, setBusy]     = useState(false);
  const [filter, setFilter] = useState('ALL');
  // Review modal state — which trade we're reviewing, current star pick,
  // comment text, and whether the submit is in flight. Null = closed.
  const [reviewTrade, setReviewTrade] = useState(null);
  const [reviewStars, setReviewStars] = useState(5);
  const [reviewText,  setReviewText]  = useState('');
  const [reviewBusy,  setReviewBusy]  = useState(false);
  const [reviewErr,   setReviewErr]   = useState('');
  const [reviewDone,  setReviewDone]  = useState(false);

  const openReview = (trade) => {
    setReviewTrade(trade);
    setReviewStars(5);
    setReviewText('');
    setReviewErr('');
    setReviewDone(false);
  };
  const closeReview = () => {
    setReviewTrade(null);
    setReviewErr('');
    setReviewDone(false);
  };
  const submitReview = async () => {
    if (!reviewTrade) return;
    setReviewBusy(true); setReviewErr('');
    try {
      const res = await leaveReview(reviewTrade.id, reviewStars, reviewText);
      if (res.code || res.error) {
        setReviewErr(res.message || res.error || 'Review failed');
        return;
      }
      setReviewDone(true);
      setTimeout(closeReview, 900);
    } finally { setReviewBusy(false); }
  };

  const load = useCallback(async () => { setTrades(await fetchTrades()); }, []);
  useEffect(() => { load(); }, [load]);

  if (!me) return h('div', { className: 'empty-inline' },
    h('div', { className: 'empty-icon' }, '🔒'),
    h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'Sign in to view your trades.'));
  if (trades === null) return h('div', { className: 'spinner' });

  const filtered = filter === 'ALL'
    ? trades
    : filter === 'OPEN'
      ? trades.filter(t => !['VERIFIED','CANCELLED'].includes(t.state))
      : trades.filter(t => t.state === filter);

  const onAccept  = async (id) => { setBusy(true); try { await tradeAccept(id);  await load(); } finally { setBusy(false); } };
  const onSent    = async (id) => { setBusy(true); try { await tradeMarkSent(id); await load(); } finally { setBusy(false); } };
  const onConfirm = async (id) => { setBusy(true); try { await tradeConfirm(id); await load(); } finally { setBusy(false); } };
  const onDispute = async (id) => {
    const reason = prompt('Why are you disputing this trade?');
    if (!reason) return;
    setBusy(true); try { await tradeDispute(id, reason); await load(); } finally { setBusy(false); }
  };
  const onCancel  = async (id) => {
    if (!confirm('Cancel this trade? The buyer will be refunded.')) return;
    setBusy(true); try { await tradeCancel(id, 'User cancelled'); await load(); } finally { setBusy(false); }
  };

  const STATE_LABEL = {
    PENDING_SELLER_ACCEPT:  { label: 'Awaiting seller accept',  color: '#fbbf24', step: 1 },
    PENDING_SELLER_SEND:    { label: 'Awaiting Steam offer',    color: '#fbbf24', step: 2 },
    PENDING_BUYER_CONFIRM:  { label: 'Awaiting buyer confirm',  color: '#60a5fa', step: 3 },
    VERIFIED:               { label: 'Verified · funds released', color: '#4ade80', step: 4 },
    DISPUTED:               { label: 'Disputed',                color: '#f87171', step: 0 },
    CANCELLED:              { label: 'Cancelled',               color: '#8590b3', step: 0 },
  };

  return h('div', null,
    h('div', { className: 'trade-filter-bar' },
      ['ALL','OPEN','PENDING_SELLER_ACCEPT','PENDING_SELLER_SEND','PENDING_BUYER_CONFIRM','VERIFIED','DISPUTED','CANCELLED'].map(f =>
        h('button', {
          key: f,
          className: `offer-tab ${filter === f ? 'active' : ''}`,
          onClick: () => setFilter(f)
        }, f.replace(/_/g, ' ').toLowerCase())
      )
    ),
    filtered.length === 0
      ? h('div', { className: 'empty-inline' },
          h('div', { className: 'empty-icon' }, '⇄'),
          h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'No trades in this filter.'))
      : h('div', { className: 'trade-list' },
          filtered.map(t => {
            const isSeller = me && t.sellerUserId === me.id;
            const isBuyer  = me && t.buyerUserId === me.id;
            const meta = STATE_LABEL[t.state] || { label: t.state, color: '#8590b3', step: 0 };
            return h('div', { key: t.id, className: `trade-row ${(t.state || '').toLowerCase()}` },
              h('div', { className: 'trade-main' },
                h('div', { className: 'trade-title' },
                  (isSeller ? '→ ' : '← ') + (t.itemName || ('Trade #' + t.id)),
                  h('span', { className: 'trade-role' }, isSeller ? 'You are selling' : 'You are buying')
                ),
                h('div', { className: 'trade-state', style: { color: meta.color } }, meta.label),
                h('div', { className: 'trade-progress' },
                  [1,2,3,4].map(i => h('div', {
                    key: i,
                    className: `trade-dot ${i <= meta.step ? 'on' : ''} ${t.state === 'DISPUTED' ? 'disputed' : ''} ${t.state === 'CANCELLED' ? 'cancelled' : ''}`
                  }))
                ),
                t.note && h('div', { className: 'trade-note' }, '"' + t.note + '"')
              ),
              h('div', { className: 'trade-side' },
                h('div', { className: 'trade-price' }, privacy ? '$•••••' : fmt(t.price)),
                h('div', { className: 'trade-date' }, timeAgo(t.createdAt))
              ),
              h('div', { className: 'trade-actions' },
                isSeller && t.state === 'PENDING_SELLER_ACCEPT' &&
                  h('button', { className: 'buy-btn', disabled: busy, onClick: () => onAccept(t.id) }, 'Accept'),
                isSeller && t.state === 'PENDING_SELLER_SEND' &&
                  h('button', { className: 'buy-btn', disabled: busy, onClick: () => onSent(t.id) }, 'Mark Sent'),
                isBuyer && t.state === 'PENDING_BUYER_CONFIRM' &&
                  h('button', { className: 'buy-btn', disabled: busy, onClick: () => onConfirm(t.id) }, 'Confirm'),
                !['VERIFIED','CANCELLED','DISPUTED'].includes(t.state) &&
                  h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid var(--border)', padding: '6px 10px', fontSize: 11 },
                    disabled: busy, onClick: () => onDispute(t.id)
                  }, 'Dispute'),
                (isSeller || isBuyer) && ['PENDING_SELLER_ACCEPT','PENDING_SELLER_SEND'].includes(t.state) &&
                  h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '6px 10px', fontSize: 11 },
                    disabled: busy, onClick: () => onCancel(t.id)
                  }, 'Cancel'),
                // Leave review — only the buyer of a VERIFIED trade. Opens
                // the proper review modal below instead of native prompts.
                // Backend is idempotent so re-reviewing updates the same row.
                isBuyer && t.state === 'VERIFIED' &&
                  h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid var(--border)', padding: '6px 10px', fontSize: 11 },
                    disabled: busy,
                    onClick: () => openReview(t)
                  }, '★ Leave Review')
              )
            );
          })
        ),
    // Review modal — overlays the trades tab when `reviewTrade` is set.
    // Clean star picker + textarea + submit, no native prompts.
    reviewTrade && h('div', { className: 'modal-backdrop', onClick: closeReview },
      h('div', {
        className: 'modal review-modal',
        onClick: e => e.stopPropagation(),
        style: { maxWidth: 480, padding: 0 }
      },
        h('button', { className: 'modal-close', onClick: closeReview, 'aria-label': 'Close' }, '✕'),
        h('div', { style: { padding: '24px 26px 20px' } },
          h('div', { style: { fontSize: 18, fontWeight: 800, color: 'var(--text-primary)', marginBottom: 4 } },
            'Leave a review'),
          h('div', { style: { fontSize: 12, color: 'var(--text-muted)', marginBottom: 18 } },
            'For "', h('strong', { style: { color: 'var(--text-secondary)' } }, reviewTrade.itemName || ('Trade #' + reviewTrade.id)), '"'),

          // Star picker
          h('div', {
            style: { display: 'flex', justifyContent: 'center', gap: 6, marginBottom: 18 },
            role: 'radiogroup',
            'aria-label': 'Star rating'
          },
            [1, 2, 3, 4, 5].map(n => h('button', {
              key: n,
              onClick: () => setReviewStars(n),
              'aria-label': n + ' stars',
              'aria-pressed': reviewStars === n,
              style: {
                background: 'transparent',
                border: 'none',
                padding: 4,
                cursor: 'pointer',
                fontSize: 36,
                lineHeight: 1,
                color: n <= reviewStars ? '#fbbf24' : 'var(--border-light)',
                textShadow: n <= reviewStars ? '0 0 12px rgba(251, 191, 36, 0.45)' : 'none',
                transition: 'color 0.12s, transform 0.12s',
                transform: n <= reviewStars ? 'scale(1.05)' : 'scale(1)'
              }
            }, '★'))
          ),

          h('div', {
            style: { textAlign: 'center', fontSize: 12, color: 'var(--text-muted)', marginBottom: 16 }
          },
            {1: 'Terrible experience', 2: 'Poor', 3: 'Okay', 4: 'Good', 5: 'Excellent'}[reviewStars]
          ),

          h('textarea', {
            className: 'price-input',
            placeholder: "Optional — what went well or didn't? (max 500 chars)",
            value: reviewText,
            maxLength: 500,
            onChange: e => setReviewText(e.target.value),
            style: {
              width: '100%',
              minHeight: 88,
              padding: '10px 12px',
              fontSize: 13,
              fontFamily: 'inherit',
              resize: 'vertical',
              lineHeight: 1.5,
              marginBottom: 4
            }
          }),
          h('div', { style: { fontSize: 10, color: 'var(--text-muted)', textAlign: 'right', marginBottom: 14 } },
            reviewText.length + ' / 500'
          ),

          reviewErr && h('div', {
            style: {
              padding: '10px 12px',
              background: 'var(--red-dim)',
              border: '1px solid rgba(248, 113, 113, 0.3)',
              color: 'var(--red)',
              borderRadius: 6,
              fontSize: 12,
              marginBottom: 12
            }
          }, reviewErr),

          reviewDone && h('div', {
            style: {
              padding: '10px 12px',
              background: 'var(--green-dim)',
              border: '1px solid rgba(74, 222, 128, 0.3)',
              color: 'var(--green)',
              borderRadius: 6,
              fontSize: 12,
              marginBottom: 12,
              textAlign: 'center',
              fontWeight: 700
            }
          }, '✓ Review saved'),

          h('div', { style: { display: 'flex', gap: 10 } },
            h('button', {
              className: 'btn btn-ghost',
              style: { flex: 1, border: '1px solid var(--border)', justifyContent: 'center' },
              onClick: closeReview,
              disabled: reviewBusy
            }, 'Cancel'),
            h('button', {
              className: 'btn btn-accent',
              style: { flex: 2, justifyContent: 'center' },
              onClick: submitReview,
              disabled: reviewBusy || reviewDone
            }, reviewBusy ? 'Submitting…' : (reviewDone ? 'Saved' : 'Submit review'))
          )
        )
      )
    )
  );
}

function ProfileOffersTab() {
  // Two tabs (incoming / outgoing) with accept / reject / counter / cancel
  // buttons right on each row. Countering opens an inline input, so the user
  // never leaves the list. Reject + cancel confirm via native prompt.
  const [data, setData] = useState(null);
  const [tab, setTab]   = useState('incoming');
  const [busy, setBusy] = useState(false);
  const [counterFor, setCounterFor] = useState(null);
  const [counterAmt, setCounterAmt] = useState('');

  const load = useCallback(async () => {
    setData(null);
    const [i, o] = await Promise.all([fetchIncomingOffers(), fetchOutgoingOffers()]);
    setData({ incoming: i, outgoing: o });
  }, []);
  useEffect(() => { load(); }, [load]);

  if (data === null) return h('div', { className: 'spinner' });

  const run = async (fn) => {
    setBusy(true);
    try { await fn(); await load(); }
    finally { setBusy(false); }
  };
  const doAccept  = (id) => run(() => acceptOffer(id));
  const doReject  = (id) => run(() => rejectOffer(id));
  const doCancel  = (id) => run(() => cancelOffer(id));
  const doCounter = async (id) => {
    const amt = parseFloat(counterAmt);
    if (!amt || amt <= 0) return;
    setBusy(true);
    try {
      const res = await counterOffer(id, amt);
      if (res.code || res.error) { alert(res.message || res.error); return; }
      setCounterFor(null); setCounterAmt('');
      await load();
    } finally { setBusy(false); }
  };

  const row = (o, isIncoming) => {
    const pct = Math.round(
      (1 - parseFloat(o.amount) / parseFloat(o.askingPrice || o.amount)) * 100
    );
    const isCountering = counterFor === o.id;
    const isPending = o.status === 'PENDING';
    const fromLabel = isIncoming
      ? ((o.author === 'SELLER' ? 'Your counter to ' : '') + (o.buyerName || 'anon'))
      : (o.author === 'SELLER' ? 'Seller counter' : 'Your offer');

    return h('div', { key: o.id, className: 'offer-row' },
      // Tiny thread chain indicator if this row is a counter or was countered
      o.parentOfferId && h('div', { className: 'offer-thread-tag' }, '↳ counter to #' + o.parentOfferId),
      h('div', { className: 'offer-body' },
        h('div', { className: 'offer-title' }, o.itemName || ('Listing #' + o.listingId)),
        h('div', { className: 'offer-sub' },
          fromLabel, ' · ', timeAgo(o.createdAt),
          o.askingPrice && h('span', { style: { marginLeft: 8, color: 'var(--text-faint)' } },
            'Ask: ', fmt(o.askingPrice))
        )
      ),
      h('div', { className: 'offer-price' },
        h('div', { className: 'offer-amt' }, fmt(o.amount)),
        pct > 0 && h('div', { className: 'offer-pct' }, '−' + pct + '%')
      ),
      h('div', { className: 'offer-status-col' },
        h('div', { className: `wallet-tx-status ${o.status}` }, o.status),
        isPending && isIncoming && !isCountering && h('div', { style: { display: 'flex', gap: 4, marginTop: 6 } },
          h('button', { className: 'buy-btn', disabled: busy, onClick: () => doAccept(o.id), 'aria-label': 'Accept offer', title: 'Accept' }, '✓'),
          h('button', {
            className: 'btn btn-ghost',
            style: { border: '1px solid var(--border)', padding: '5px 8px', fontSize: 10 },
            disabled: busy, onClick: () => { setCounterFor(o.id); setCounterAmt(String(parseFloat(o.amount) + 1)); },
            'aria-label': 'Counter offer', title: 'Counter'
          }, '⇄'),
          h('button', {
            className: 'btn btn-ghost',
            style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '5px 8px', fontSize: 10 },
            disabled: busy, onClick: () => doReject(o.id),
            'aria-label': 'Reject offer', title: 'Reject'
          }, '✕')
        ),
        isPending && !isIncoming && !isCountering && h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)', padding: '5px 10px', fontSize: 11, marginTop: 6 },
          disabled: busy, onClick: () => doCancel(o.id)
        }, 'Cancel')
      ),
      isCountering && h('div', { className: 'offer-counter-form' },
        h('input', {
          className: 'price-input',
          type: 'number', step: '0.01', min: '0.01',
          placeholder: 'Counter price',
          value: counterAmt,
          onChange: e => setCounterAmt(e.target.value)
        }),
        h('button', { className: 'buy-btn', disabled: busy, onClick: () => doCounter(o.id) }, 'Send counter'),
        h('button', { className: 'btn btn-ghost', style: { padding: '6px 10px', fontSize: 11 }, onClick: () => setCounterFor(null) }, '✕')
      )
    );
  };

  const list = tab === 'incoming' ? data.incoming : data.outgoing;

  return h('div', null,
    h('div', { className: 'offer-tabs' },
      h('button', { className: `offer-tab ${tab === 'incoming' ? 'active' : ''}`, onClick: () => setTab('incoming') },
        'Incoming', h('span', { className: 'filter-count', style: { marginLeft: 6 } }, data.incoming.filter(o => o.status === 'PENDING').length)),
      h('button', { className: `offer-tab ${tab === 'outgoing' ? 'active' : ''}`, onClick: () => setTab('outgoing') },
        'Outgoing', h('span', { className: 'filter-count', style: { marginLeft: 6 } }, data.outgoing.filter(o => o.status === 'PENDING').length))
    ),
    list.length === 0
      ? h('div', { className: 'empty-inline' },
          h('div', { className: 'empty-icon' }, '💬'),
          h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } },
            tab === 'incoming' ? 'No incoming offers. Any time a buyer bargains on your listings, they show up here.'
                               : 'No outgoing offers. Use "Make Offer" from any item detail to bargain with a seller.'))
      : h('div', { className: 'offer-list' }, list.map(o => row(o, tab === 'incoming')))
  );
}

function ProfileSupportTab() {
  const [tickets, setTickets] = useState(null);
  const [viewing, setViewing] = useState(null);
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState({ subject: '', category: 'OTHER', body: '' });
  const [reply, setReply] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => { setTickets(await fetchSupportTickets()); }, []);
  useEffect(() => { load(); }, [load]);

  const openTicket = async (id) => {
    setViewing(await fetchSupportTicket(id));
  };

  const submitCreate = async () => {
    if (!form.subject.trim() || !form.body.trim()) return;
    setBusy(true);
    try {
      await createSupportTicket(form);
      setCreating(false);
      setForm({ subject: '', category: 'OTHER', body: '' });
      load();
    } finally { setBusy(false); }
  };

  const submitReply = async () => {
    if (!reply.trim() || !viewing?.ticket) return;
    setBusy(true);
    try {
      await replySupportTicket(viewing.ticket.id, reply);
      setReply('');
      setViewing(await fetchSupportTicket(viewing.ticket.id));
    } finally { setBusy(false); }
  };

  const resolve = async () => {
    if (!viewing?.ticket) return;
    await resolveSupportTicket(viewing.ticket.id);
    setViewing(await fetchSupportTicket(viewing.ticket.id));
    load();
  };

  if (viewing) {
    return h('div', { className: 'profile-panel' },
      h('div', { style: { display: 'flex', gap: 8, alignItems: 'center', marginBottom: 14 } },
        h('button', { className: 'btn btn-ghost', onClick: () => setViewing(null) }, '← Tickets'),
        h('div', { style: { flex: 1 } },
          h('div', { style: { fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' } }, '#' + viewing.ticket.id + ' · ' + viewing.ticket.subject),
          h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } }, viewing.ticket.category + ' · ' + viewing.ticket.status)
        ),
        viewing.ticket.status !== 'RESOLVED' && h('button', { className: 'btn btn-ghost', onClick: resolve, style: { border: '1px solid var(--border)' } }, 'Mark Resolved')
      ),
      h('div', { className: 'support-thread' },
        viewing.messages.map(m => h('div', { key: m.id, className: `support-msg ${m.author === 'STAFF' ? 'staff' : 'user'}` },
          h('div', { className: 'support-msg-head' }, m.authorName, ' · ', timeAgo(m.createdAt)),
          h('div', { className: 'support-msg-body' }, m.body)
        ))
      ),
      viewing.ticket.status !== 'RESOLVED' && h('div', { style: { display: 'flex', gap: 8, marginTop: 14 } },
        h('input', { className: 'chat-input', style: { flex: 1 }, placeholder: 'Reply…', value: reply, onChange: e => setReply(e.target.value), onKeyDown: e => { if (e.key === 'Enter') submitReply(); } }),
        h('button', { className: 'btn btn-accent', disabled: busy || !reply.trim(), onClick: submitReply }, 'Send')
      )
    );
  }

  return h('div', { className: 'profile-panel' },
    h('button', { className: 'btn btn-accent', style: { marginBottom: 14 }, onClick: () => setCreating(c => !c) },
      creating ? 'Cancel' : '+ New Ticket'),
    creating && h('div', { className: 'buyorder-form' },
      h('div', { className: 'wallet-input-label' }, 'Subject'),
      h('input', { className: 'wallet-amount-input', value: form.subject, onChange: e => setForm({ ...form, subject: e.target.value }), placeholder: 'Short subject line…' }),
      h('div', { className: 'wallet-input-label' }, 'Category'),
      h('select', { className: 'sort-select', value: form.category, onChange: e => setForm({ ...form, category: e.target.value }) },
        ['TRADE','PAYMENT','ACCOUNT','BUG','OTHER'].map(c => h('option', { key: c, value: c }, c))
      ),
      h('div', { className: 'wallet-input-label' }, 'Message'),
      h('textarea', { className: 'wallet-amount-input', style: { minHeight: 100, fontFamily: 'inherit' }, value: form.body, onChange: e => setForm({ ...form, body: e.target.value }), placeholder: 'Describe your issue…' }),
      h('button', { className: 'btn btn-accent wallet-submit', disabled: busy, onClick: submitCreate }, busy ? 'Submitting…' : 'Submit Ticket')
    ),
    tickets === null
      ? h('div', { className: 'spinner' })
      : tickets.length === 0
        ? h('div', { className: 'empty-inline' },
            h('div', { className: 'empty-icon' }, '🎧'),
            h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'No tickets yet. Open one above if you need help.'))
        : h('table', { className: 'db-table' },
            h('thead', null, h('tr', null,
              h('th', null, 'ID'), h('th', null, 'Subject'), h('th', null, 'Category'), h('th', null, 'Status'), h('th', { className: 'right' }, 'Updated'))),
            h('tbody', null,
              tickets.map(t => h('tr', { key: t.id, className: 'db-row', onClick: () => openTicket(t.id) },
                h('td', { className: 'db-rank' }, '#' + t.id),
                h('td', null, t.subject),
                h('td', { className: 'db-cat' }, t.category),
                h('td', { style: { fontSize: 10, fontWeight: 700 } }, t.status),
                h('td', { className: 'right', style: { fontSize: 11, color: 'var(--text-muted)' } }, timeAgo(t.updatedAt))
              ))
            )
          )
  );
}

function ProfileDevelopersTab() {
  const [keys, setKeys] = useState(null);
  const [label, setLabel] = useState('');
  const [newKey, setNewKey] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => { setKeys(await fetchApiKeys()); }, []);
  useEffect(() => { load(); }, [load]);

  const mint = async () => {
    setBusy(true);
    try {
      const res = await createApiKey(label || 'Untitled');
      setNewKey(res);
      setLabel('');
      load();
    } finally { setBusy(false); }
  };

  const revoke = async (id) => {
    if (!confirm('Revoke this API key? Applications using it will stop working immediately.')) return;
    await revokeApiKey(id);
    load();
  };

  return h('div', { className: 'profile-panel' },
    h('div', { style: { fontSize: 12, color: 'var(--text-muted)', marginBottom: 14, padding: 10, background: 'var(--accent-dim)', border: '1px solid var(--accent-border)', borderRadius: 8 } },
      'API keys authenticate third-party bots and browser extensions. They carry your account rights — never paste them into a public file or chat.'),
    h('div', { style: { display: 'flex', gap: 8, marginBottom: 14 } },
      h('input', { className: 'price-input', placeholder: 'Label (e.g. my-bot)', style: { flex: 1 }, value: label, onChange: e => setLabel(e.target.value) }),
      h('button', { className: 'btn btn-accent', disabled: busy, onClick: mint }, busy ? 'Minting…' : '+ New Key')
    ),
    newKey && h('div', { className: 'api-key-new' },
      h('div', { style: { fontSize: 11, color: 'var(--yellow)', fontWeight: 700, marginBottom: 6, letterSpacing: 0.4 } },
        '⚠ COPY THIS NOW — it will not be shown again'),
      h('div', { className: 'api-key-token' }, newKey.token),
      h('button', { className: 'btn btn-ghost', style: { marginTop: 10, border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11 }, onClick: () => setNewKey(null) }, 'Dismiss')
    ),
    keys === null
      ? h('div', { className: 'spinner' })
      : keys.length === 0
        ? h('div', { className: 'empty-inline' },
            h('div', { className: 'empty-icon' }, '🔑'),
            h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'No API keys yet.'))
        : h('table', { className: 'db-table' },
            h('thead', null, h('tr', null,
              h('th', null, 'Label'),
              h('th', null, 'Prefix'),
              h('th', null, 'Created'),
              h('th', null, 'Last Used'),
              h('th', { className: 'right' }, 'Action'))),
            h('tbody', null, keys.map(k => h('tr', { key: k.id, className: 'db-row' },
              h('td', null, k.label || '—'),
              h('td', { className: 'db-mono' }, k.publicPrefix + '…'),
              h('td', { style: { fontSize: 11, color: 'var(--text-muted)' } }, new Date(k.createdAt).toLocaleDateString()),
              h('td', { style: { fontSize: 11, color: 'var(--text-muted)' } }, k.lastUsedAt ? timeAgo(k.lastUsedAt) : 'Never'),
              h('td', { className: 'right' },
                k.revoked
                  ? h('span', { style: { fontSize: 10, color: 'var(--red)', fontWeight: 700 } }, 'REVOKED')
                  : h('button', { className: 'btn btn-ghost', style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '5px 10px', fontSize: 11 }, onClick: () => revoke(k.id) }, 'Revoke')
              )
            )))
          )
  );
}

// ── Trades ──────────────────────────────────────────────────────
export function TradesModal({ onClose, transactions }) {
  const trades = transactions.filter(t => t.type === 'PURCHASE' || t.type === 'SALE');
  return h(InfoModal, { title: `Trades (${trades.length})`, onClose },
    trades.length === 0
      ? h('div', { className: 'empty-inline' },
          h('div', { className: 'empty-icon' }, '⇄'),
          h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'No trades yet. Purchases and sales will show up here.'))
      : h('div', { className: 'wallet-tx-list', style: { maxHeight: 'none' } },
          trades.map(tx => {
            const inbound = tx.type === 'SALE';
            return h('div', { key: tx.id, className: 'wallet-tx' },
              h('div', { className: `wallet-tx-icon ${inbound ? 'in' : 'out'}` }, inbound ? '↓' : '↑'),
              h('div', { className: 'wallet-tx-main' },
                h('div', { className: 'wallet-tx-type' }, tx.type === 'PURCHASE' ? 'Purchase' : 'Sale'),
                h('div', { className: 'wallet-tx-desc' }, tx.description || '—')
              ),
              h('div', { className: 'wallet-tx-right' },
                h('div', { className: `wallet-tx-amt ${inbound ? 'in' : 'out'}` }, (inbound ? '+' : '−') + fmt(tx.amount)),
                h('div', { className: `wallet-tx-status ${tx.status}` }, tx.status)
              )
            );
          })
        )
  );
}

// ── Sell Items (from Steam inventory + from in-market inventory) ──
//
// Two sources:
//   1) Steam inventory — items the user actually owns on Steam (live fetch
//      from the Steam community API via our /api/steam/inventory endpoint).
//      Picking one and entering a price calls POST /api/steam/list which
//      creates the catalogue entry on the fly if the item is new.
//   2) sboxmarket inventory — items the user bought *on sboxmarket* and
//      wants to relist. Same flow as before via relistItem().
//
// CSFloat has the same split — "Your Steam items" and "Owned on platform".
export function SellItemsModal({ onClose, me, onRefresh }) {
  const [source, setSource]       = useState('steam'); // steam | internal
  const [steamData, setSteamData] = useState(null);    // {items, count, lastSyncedAt}
  const [internal, setInternal]   = useState(null);
  const [picking, setPicking]     = useState(null);    // { kind: 'steam'|'internal', item: {...} }
  const [price, setPrice]         = useState('');
  const [busy, setBusy]           = useState(false);
  const [error, setError]         = useState('');
  const [syncing, setSyncing]     = useState(false);

  const loadSteam = useCallback(async () => {
    setSteamData(await fetchSteamInventory());
  }, []);
  const loadInternal = useCallback(async () => {
    setInternal(await fetchInventory());
  }, []);

  useEffect(() => {
    if (!me) return;
    loadSteam();
    loadInternal();
  }, [me, loadSteam, loadInternal]);

  if (!me) return h(InfoModal, { title: 'Sell Items', onClose },
    h('div', { className: 'empty-inline' },
      h('div', { className: 'empty-icon' }, '🔒'),
      h('div', { style: { fontSize: 14, color: 'var(--text-secondary)', marginBottom: 14 } }, 'Sign in with Steam to sell items.'),
      h('a', { className: 'steam-btn', href: '/api/auth/steam/login' },
        h('div', { className: 'steam-btn-icon' }, '◆'), 'Sign in through Steam')));

  const resync = async () => {
    setSyncing(true);
    try { await syncSteam(); await loadSteam(); }
    finally { setSyncing(false); }
  };

  const startPickSteam = (si) => {
    setPicking({ kind: 'steam', item: si });
    setPrice(parseFloat(si.suggestedPrice || 0).toFixed(2));
    setError('');
  };
  const startPickInternal = (l) => {
    setPicking({ kind: 'internal', item: l.item, listingId: l.id });
    setPrice(parseFloat(l.item.lowestPrice).toFixed(2));
    setError('');
  };

  const submit = async () => {
    setError('');
    const p = parseFloat(price);
    if (!p || p <= 0) { setError('Enter a valid price'); return; }
    setBusy(true);
    try {
      let res;
      if (picking.kind === 'steam') {
        res = await listFromSteam(picking.item.assetId, p);
      } else {
        res = await relistItem(picking.listingId, p);
      }
      if (res.code || res.error) { setError(res.message || res.error); return; }
      await onRefresh();
      onClose();
    } finally { setBusy(false); }
  };

  if (picking) {
    const item = picking.item;
    const isSteam = picking.kind === 'steam';
    const suggested = isSteam ? parseFloat(item.suggestedPrice || 0).toFixed(2) : parseFloat(item.lowestPrice).toFixed(2);
    return h(InfoModal, { title: 'List Item for Sale', onClose },
      h('div', { style: { display: 'flex', gap: 18, marginBottom: 20 } },
        h('div', { style: { width: 120, aspectRatio: '1', borderRadius: 10, background: 'radial-gradient(ellipse at 50% 35%, rgba(30,165,255,0.14) 0%, transparent 65%), linear-gradient(180deg, #1a2236 0%, #0d1320 100%)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, padding: 8 } },
          h(ItemImage, {
            item: isSteam
              ? { imageUrl: item.imageUrl || item.iconUrl, name: item.name, category: item.category, iconEmoji: '👕' }
              : item,
            variant: 'card'
          })
        ),
        h('div', null,
          h('div', { style: { fontSize: 11, color: 'var(--text-muted)', textTransform: 'uppercase', fontWeight: 700, marginBottom: 4 } }, item.category || 'Steam item'),
          h('div', { style: { fontSize: 18, fontWeight: 800, color: 'var(--text-primary)', marginBottom: 10 } }, item.name),
          isSteam && !item.tradable && h('div', { style: { fontSize: 11, color: 'var(--red)', marginBottom: 8, fontWeight: 700 } }, '⚠ Not tradable on Steam right now'),
          h(RarityBadge, { rarity: item.rarity || 'Standard' }),
          h('div', { style: { fontSize: 12, color: 'var(--text-muted)', marginTop: 12 } },
            'Suggested price: ', h('span', { style: { color: 'var(--accent)', fontWeight: 700 } }, '$' + suggested))
        )
      ),
      h('div', { className: 'wallet-input-label' }, 'Your asking price (USD)'),
      h('input', {
        className: 'wallet-amount-input',
        type: 'number', min: '0.01', step: '0.01',
        placeholder: suggested,
        value: price,
        onChange: e => setPrice(e.target.value)
      }),
      h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginTop: 8 } },
        'A 2% platform fee is deducted when the item sells.'),
      error && h('div', { className: 'wallet-error' }, error),
      h('div', { style: { display: 'flex', gap: 10, marginTop: 20 } },
        h('button', { className: 'btn btn-ghost', style: { flex: 1, border: '1px solid var(--border)', justifyContent: 'center', padding: 13 }, onClick: () => setPicking(null) }, 'Back'),
        h('button', { className: 'btn btn-accent', style: { flex: 1, justifyContent: 'center', padding: 13 }, disabled: busy || (isSteam && !item.tradable), onClick: submit }, busy ? 'Listing…' : 'List for Sale')
      )
    );
  }

  const steamList = steamData?.items || [];
  const internalList = internal || [];

  return h(InfoModal, { title: 'Sell Items', onClose },
    h('div', { className: 'sell-source-tabs' },
      h('button', { className: `offer-tab ${source === 'steam' ? 'active' : ''}`, onClick: () => setSource('steam') },
        '🎮 Steam Inventory', steamData && h('span', { className: 'filter-count', style: { marginLeft: 6 } }, steamList.length)),
      h('button', { className: `offer-tab ${source === 'internal' ? 'active' : ''}`, onClick: () => setSource('internal') },
        '📦 Platform Inventory', internal && h('span', { className: 'filter-count', style: { marginLeft: 6 } }, internalList.length)),
      h('div', { style: { flex: 1 } }),
      source === 'steam' && h('button', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11 }, disabled: syncing, onClick: resync }, syncing ? 'Syncing…' : 'Sync Steam')
    ),

    source === 'steam' && steamData === null && h('div', { className: 'spinner' }),
    source === 'steam' && steamData && steamList.length === 0 && h('div', { className: 'empty-inline' },
      h('div', { className: 'empty-icon' }, '🎮'),
      h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } }, 'No s&box items in your Steam inventory'),
      h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 360, margin: '0 auto 18px' } },
        'Either your Steam inventory is private, or there are no s&box cosmetics in it. Click Sync Steam to retry.')
    ),
    source === 'steam' && steamData && steamList.length > 0 && h('div', null,
      // Summary chips: tradable / matched / new. Lets the seller see the
      // state of their inventory at a glance instead of counting rows.
      h('div', { className: 'sell-summary' },
        h('div', { className: 'sell-summary-chip' },
          h('span', { className: 'sell-summary-num' }, steamList.length), ' total'),
        h('div', { className: 'sell-summary-chip ok' },
          h('span', { className: 'sell-summary-num' }, steamList.filter(s => s.tradable).length), ' tradable'),
        h('div', { className: 'sell-summary-chip warn' },
          h('span', { className: 'sell-summary-num' }, steamList.filter(s => !s.tradable).length), ' locked'),
        h('div', { className: 'sell-summary-chip accent' },
          h('span', { className: 'sell-summary-num' }, steamList.filter(s => s.catalogueId).length), ' already in catalogue'),
        h('div', { className: 'sell-summary-chip' },
          h('span', { className: 'sell-summary-num' }, steamList.filter(s => !s.catalogueId).length), ' new to sboxmarket')
      ),
      h('div', { className: 'inventory-grid' },
        steamList.map(si => h('div', {
          key: si.assetId,
          className: `inventory-item ${si.tradable ? '' : 'disabled'}`,
          onClick: () => startPickSteam(si),
          role: 'button',
          tabIndex: si.tradable ? 0 : -1,
          'aria-disabled': !si.tradable
        },
          !si.catalogueId && h('div', { className: 'inventory-new-badge' }, 'NEW'),
          h('div', { className: 'inventory-thumb' },
            // Wrap the Steam-shape record into the shape ItemImage expects so it
            // gets the same lazy-load + poster-fallback treatment as every other
            // thumbnail. If Steam's CDN 404s we end up with a category glyph
            // instead of a broken-image icon.
            h(ItemImage, { item: { imageUrl: si.imageUrl || si.iconUrl, name: si.name, category: si.category, iconEmoji: '👕' }, variant: 'card' })
          ),
          h('div', { className: 'inventory-name' }, si.name),
          h('div', { className: 'inventory-floor' }, si.catalogueId ? 'Floor ' + fmt(si.suggestedPrice) : 'Set your price'),
          !si.tradable && h('div', { style: { fontSize: 9, color: 'var(--red)', fontWeight: 700, marginTop: 2 } }, 'NOT TRADABLE')
        ))
      )
    ),

    source === 'internal' && internal === null && h('div', { className: 'spinner' }),
    source === 'internal' && internal && internalList.length === 0 && h('div', { className: 'empty-inline' },
      h('div', { className: 'empty-icon' }, '📦'),
      h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } }, 'Platform inventory empty'),
      h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 360, margin: '0 auto 18px' } },
        'Items you buy on sboxmarket appear here. You can relist any of them at a new price.')
    ),
    source === 'internal' && internalList.length > 0 && h('div', { className: 'inventory-grid' },
      internalList.map(l => h('div', {
        key: l.id, className: 'inventory-item',
        onClick: () => startPickInternal(l)
      },
        h('div', { className: 'inventory-thumb' }, h(ItemImage, { item: l.item })),
        h('div', { className: 'inventory-name' }, l.item.name),
        h('div', { className: 'inventory-floor' }, 'Floor ' + fmt(l.item.lowestPrice))
      ))
    )
  );
}

// ── My Stall ────────────────────────────────────────────────────
export function MyStallModal({ onClose, me, onRefresh }) {
  const [stall, setStall] = useState(null);
  const [editing, setEditing] = useState(null); // listing id being edited inline
  const [editPrice, setEditPrice] = useState('');
  const [editDesc, setEditDesc]   = useState('');
  const [away, setAway] = useState(false);
  const load = useCallback(() => { fetchMyStall().then(setStall); }, []);
  useEffect(() => { if (me) load(); }, [me, load]);

  if (!me) return h(InfoModal, { title: 'My Stall', onClose },
    h('div', { className: 'empty-inline' },
      h('div', { className: 'empty-icon' }, '🔒'),
      h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'Sign in to view your stall.')));
  if (stall === null) return h(InfoModal, { title: 'My Stall', onClose }, h('div', { className: 'spinner' }));

  const doCancel = async (id) => {
    if (!confirm('Remove this listing?')) return;
    await cancelListing(id);
    load();
    onRefresh && onRefresh();
  };

  const startEdit = (l) => {
    setEditing(l.id);
    setEditPrice(parseFloat(l.price).toFixed(2));
    setEditDesc(l.description || '');
  };

  const saveEdit = async (id) => {
    await updateStallListing(id, {
      price: parseFloat(editPrice),
      description: editDesc
    });
    setEditing(null);
    load();
    onRefresh && onRefresh();
  };

  const toggleHidden = async (l) => {
    await updateStallListing(l.id, { hidden: !l.hidden });
    load();
  };

  const toggleAway = async () => {
    const next = !away;
    setAway(next);
    await setAwayMode(next);
    load();
  };

  return h(InfoModal, { title: `My Stall · ${stall.length} active`, onClose },
    h('div', { className: 'stall-toolbar' },
      h('button', {
        className: `chat-toggle ${away ? '' : 'off'}`,
        style: { display: 'inline-block', verticalAlign: 'middle', marginRight: 10 },
        onClick: toggleAway
      }),
      h('span', { style: { fontSize: 12, color: 'var(--text-secondary)' } },
        'Away Mode — ', away ? 'all listings hidden' : 'listings visible'
      )
    ),
    stall.length === 0
      ? h('div', { className: 'empty-inline' },
          h('div', { className: 'empty-icon' }, '🏪'),
          h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } },
            'Your stall is empty. Use Sell Items to list something.'))
      : h('div', { className: 'stall-list' },
          stall.map(l => h('div', { key: l.id, className: `stall-row ${l.hidden ? 'hidden-listing' : ''}` },
            h('div', { className: 'item-thumb', style: { width: 48, height: 48 } }, h(ItemImage, { item: l.item })),
            h('div', { style: { flex: 1, minWidth: 0 } },
              h('div', { className: 'item-name' }, l.item.name,
                l.hidden && h('span', { style: { marginLeft: 8, fontSize: 10, color: 'var(--text-muted)', fontWeight: 700 } }, '· HIDDEN')
              ),
              editing === l.id
                ? h('div', { style: { marginTop: 6, display: 'flex', gap: 6 } },
                    h('input', { className: 'price-input', value: editPrice, onChange: e => setEditPrice(e.target.value), placeholder: 'price', style: { width: 90 } }),
                    h('input', { className: 'price-input', value: editDesc, maxLength: 32, onChange: e => setEditDesc(e.target.value), placeholder: 'description (32 chars)', style: { flex: 1 } })
                  )
                : h('div', { className: 'item-sub' },
                    l.item.category + ' · listed ' + timeAgo(l.listedAt),
                    l.description && h('span', { style: { marginLeft: 8, fontStyle: 'italic' } }, '"' + l.description + '"')
                  )
            ),
            editing === l.id
              ? h('div', { style: { display: 'flex', gap: 6 } },
                  h('button', { className: 'buy-btn', onClick: () => saveEdit(l.id) }, 'Save'),
                  h('button', { className: 'btn btn-ghost', style: { padding: '7px 12px', fontSize: 11 }, onClick: () => setEditing(null) }, '✕')
                )
              : h('div', { style: { display: 'flex', gap: 6, alignItems: 'center' } },
                  h('div', { className: 'price-val', style: { marginRight: 10 } }, fmt(l.price)),
                  h('button', { className: 'btn btn-ghost', style: { padding: '7px 10px', fontSize: 11 }, onClick: () => startEdit(l) }, '✎ Edit'),
                  h('button', { className: 'btn btn-ghost', style: { padding: '7px 10px', fontSize: 11 }, onClick: () => toggleHidden(l) }, l.hidden ? '👁 Show' : '🙈 Hide'),
                  h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '7px 10px', fontSize: 11 },
                    onClick: () => doCancel(l.id)
                  }, '🗑')
                )
          ))
        )
  );
}

// ── Offers (incoming/outgoing) ───────────────────────────────────
export function OffersModal({ onClose, me, onRefresh }) {
  const [tab, setTab]       = useState('incoming');
  const [incoming, setIn]   = useState(null);
  const [outgoing, setOut]  = useState(null);
  const [busy, setBusy]     = useState(false);

  const load = useCallback(async () => {
    const [i, o] = await Promise.all([fetchIncomingOffers(), fetchOutgoingOffers()]);
    setIn(i);
    setOut(o);
  }, []);
  useEffect(() => { if (me) load(); }, [me, load]);

  if (!me) return h(InfoModal, { title: 'Offers', onClose },
    h('div', { className: 'empty-inline' },
      h('div', { className: 'empty-icon' }, '🔒'),
      h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'Sign in to view your offers.')));

  const handleAccept = async (id) => {
    if (busy) return;
    setBusy(true);
    try {
      const res = await acceptOffer(id);
      if (res.code || res.error) { alert(res.message || res.error); return; }
      await load();
      onRefresh && onRefresh();
    } finally { setBusy(false); }
  };
  const handleReject = async (id) => {
    if (busy) return;
    setBusy(true);
    try { await rejectOffer(id); await load(); }
    finally { setBusy(false); }
  };
  const handleCancel = async (id) => {
    if (busy) return;
    setBusy(true);
    try { await cancelOffer(id); await load(); }
    finally { setBusy(false); }
  };

  const renderOffer = (offer, isIncoming) => {
    const diff   = parseFloat(offer.askingPrice) - parseFloat(offer.amount);
    const pctOff = Math.round(diff / parseFloat(offer.askingPrice) * 100);
    return h('div', { key: offer.id, className: 'offer-row' },
      h('div', { className: 'item-thumb', style: { width: 56, height: 56 } },
        offer.itemImageUrl
          ? h('img', { src: offer.itemImageUrl, alt: offer.itemName })
          : h('span', null, '📦')
      ),
      h('div', { style: { flex: 1, minWidth: 0 } },
        h('div', { className: 'item-name' }, offer.itemName || ('Listing #' + offer.listingId)),
        h('div', { className: 'item-sub' },
          isIncoming ? `From ${offer.buyerName}` : 'Your offer',
          ' · ', timeAgo(offer.createdAt)
        )
      ),
      h('div', { style: { textAlign: 'right', marginRight: 14 } },
        h('div', { style: { fontSize: 14, fontWeight: 800, color: 'var(--accent)', fontFamily: 'JetBrains Mono, monospace' } }, fmt(offer.amount)),
        h('div', { style: { fontSize: 11, color: 'var(--text-muted)', textDecoration: 'line-through', fontFamily: 'JetBrains Mono, monospace' } }, fmt(offer.askingPrice)),
        h('div', { style: { fontSize: 10, fontWeight: 700, color: pctOff > 0 ? 'var(--green)' : 'var(--text-muted)' } }, pctOff > 0 ? `−${pctOff}%` : '')
      ),
      offer.status === 'PENDING'
        ? (isIncoming
            ? h('div', { style: { display: 'flex', gap: 6 } },
                h('button', { className: 'buy-btn', onClick: () => handleAccept(offer.id), disabled: busy }, 'Accept'),
                h('button', {
                  className: 'btn btn-ghost',
                  style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '7px 11px', fontSize: 11 },
                  onClick: () => handleReject(offer.id), disabled: busy
                }, 'Reject')
              )
            : h('button', {
                className: 'btn btn-ghost',
                style: { border: '1px solid var(--border)', padding: '7px 14px', fontSize: 12 },
                onClick: () => handleCancel(offer.id), disabled: busy
              }, 'Cancel'))
        : h('div', { className: `wallet-tx-status ${offer.status}`, style: { padding: '4px 10px', borderRadius: 5, background: 'var(--bg-elevated)', fontSize: 10 } }, offer.status)
    );
  };

  const list = tab === 'incoming' ? incoming : outgoing;
  return h(InfoModal, { title: 'Offers', onClose },
    h('div', { className: 'offer-tabs' },
      h('button', { className: `offer-tab ${tab === 'incoming' ? 'active' : ''}`, onClick: () => setTab('incoming') },
        'Incoming', incoming && h('span', { className: 'filter-count', style: { marginLeft: 6 } }, incoming.filter(o => o.status === 'PENDING').length)),
      h('button', { className: `offer-tab ${tab === 'outgoing' ? 'active' : ''}`, onClick: () => setTab('outgoing') },
        'Outgoing', outgoing && h('span', { className: 'filter-count', style: { marginLeft: 6 } }, outgoing.filter(o => o.status === 'PENDING').length))
    ),
    list === null
      ? h('div', { className: 'spinner' })
      : list.length === 0
        ? h('div', { className: 'empty-inline' },
            h('div', { className: 'empty-icon' }, '💬'),
            h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } },
              tab === 'incoming'
                ? "No incoming offers. They'll show up here when buyers make offers on your listings."
                : 'No outgoing offers. Make an offer on any listing using the "Make Offer" button.'))
        : h('div', { className: 'offer-list' }, list.map(o => renderOffer(o, tab === 'incoming')))
  );
}

// ── Watchlist ───────────────────────────────────────────────────
export function WatchlistModal({ onClose, watchlist, allListings, onOpen, onToggleStar }) {
  // Dedupe by item id (one card per item) and compute price drop since the
  // item was first starred. We store a { itemId → price } snapshot in
  // localStorage so each card can show "−$X since you watchlisted" even
  // across sessions. Also supports a Drops Only filter.
  const [showDropsOnly, setShowDropsOnly] = useState(false);
  const [snapshots, setSnapshots] = useState(() => {
    try { return JSON.parse(localStorage.getItem('sb_watchlist_snap') || '{}'); }
    catch { return {}; }
  });

  // The parent passes in the currently-filtered marketplace view, which
  // means a watchlisted item is invisible here whenever it falls outside
  // the active category/rarity/search. We fetch an unfiltered page AND,
  // for any watched ID that still has no listing (because the market is
  // empty or the items have all sold), we fall back to /api/items/{id}
  // and show a stub row marked "No active listings". Previously this
  // modal silently showed "empty" whenever the pool was zero which made
  // the nav badge and the page disagree — a real bug.
  const [pool, setPool] = useState(() => allListings || []);
  const [fallbackItems, setFallbackItems] = useState({}); // id → item
  useEffect(() => {
    let alive = true;
    fetchListings({ limit: 500 }).then(rows => {
      if (alive && Array.isArray(rows)) setPool(rows);
    }).catch(() => {});
    return () => { alive = false; };
  }, []);

  // For every watched ID that's missing from the pool, fetch the item
  // directly so we can render a card with "No active listings" rather
  // than silently dropping it.
  useEffect(() => {
    let alive = true;
    const present = new Set((pool || []).map(l => l?.item?.id).filter(Boolean));
    const missing = watchlist.filter(id => !present.has(id) && fallbackItems[id] == null);
    if (missing.length === 0) return;
    Promise.all(missing.map(id => fetchItem(id).catch(() => null))).then(results => {
      if (!alive) return;
      const next = { ...fallbackItems };
      missing.forEach((id, i) => { if (results[i]) next[id] = results[i]; });
      setFallbackItems(next);
    });
    return () => { alive = false; };
  }, [watchlist, pool]);

  // Walk the unfiltered pool → one row per starred item (cheapest listing).
  // Any starred ID without a listing becomes a synthetic "stub" listing
  // built from the item catalogue so the card still renders.
  const starred = useMemo(() => {
    const byItem = {};
    (pool || []).forEach(l => {
      if (!watchlist.includes(l.item.id)) return;
      const cur = byItem[l.item.id];
      if (!cur || parseFloat(l.price) < parseFloat(cur.price)) byItem[l.item.id] = l;
    });
    watchlist.forEach(id => {
      if (byItem[id] != null) return;
      const item = fallbackItems[id];
      if (!item) return;
      byItem[id] = {
        id: `stub-${id}`,
        price: item.lowestPrice ?? null,
        item,
        __noListing: true
      };
    });
    return Object.values(byItem);
  }, [watchlist, pool, fallbackItems]);

  useEffect(() => {
    const next = { ...snapshots };
    let changed = false;
    starred.forEach(l => {
      if (next[l.item.id] == null) {
        next[l.item.id] = parseFloat(l.price);
        changed = true;
      }
    });
    // Drop snapshots for items that were unstarred
    Object.keys(next).forEach(k => {
      if (!watchlist.includes(parseInt(k, 10))) { delete next[k]; changed = true; }
    });
    if (changed) {
      setSnapshots(next);
      localStorage.setItem('sb_watchlist_snap', JSON.stringify(next));
    }
  }, [starred, watchlist]);

  const rows = starred.map(l => {
    const snap = snapshots[l.item.id];
    const cur = parseFloat(l.price);
    const delta = snap != null ? cur - snap : 0;
    const pct = snap && snap > 0 ? (delta / snap) * 100 : 0;
    return { listing: l, snap, delta, pct };
  });
  const filtered = showDropsOnly ? rows.filter(r => r.delta < 0) : rows;

  return h(InfoModal, { title: `Watchlist · ${starred.length} items`, onClose },
    starred.length === 0
      ? h('div', { className: 'empty-inline' },
          h('div', { className: 'empty-icon' }, '♡'),
          h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } }, 'Nothing on your watchlist'),
          h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 360, margin: '0 auto 14px' } },
            'Click the ♡ on any item card and it will show up here with a live price-drop alert.'),
          h('a', { className: 'btn btn-accent', href: '/' }, 'Browse marketplace →')
        )
      : h('div', null,
          h('div', { style: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 } },
            h('button', {
              className: `offer-tab ${!showDropsOnly ? 'active' : ''}`,
              onClick: () => setShowDropsOnly(false)
            }, 'All ', h('span', { className: 'filter-count', style: { marginLeft: 6 } }, rows.length)),
            h('button', {
              className: `offer-tab ${showDropsOnly ? 'active' : ''}`,
              onClick: () => setShowDropsOnly(true)
            }, 'Price drops ', h('span', { className: 'filter-count', style: { marginLeft: 6 } }, rows.filter(r => r.delta < 0).length))
          ),
          filtered.length === 0
            ? h('div', { className: 'empty-inline' },
                h('div', { className: 'empty-icon' }, '📉'),
                h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } },
                  'No price drops yet. We remember what each item cost when you starred it and show the diff here.'))
            : h('div', { className: 'listing-grid', style: { gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))' } },
                filtered.map(r => h('div', { key: r.listing.id, style: { position: 'relative' } },
                  h(GridCard, {
                    listing: r.listing,
                    starred: true,
                    onToggleStar,
                    onClick: () => { if (!r.listing.__noListing) { onClose(); onOpen(r.listing); } }
                  }),
                  /* Stub-listing overlay: the item is watched but there's
                     nothing active on the market right now. */
                  r.listing.__noListing && h('div', {
                    className: 'watchlist-delta',
                    style: {
                      background: 'rgba(15, 20, 36, 0.85)',
                      color: 'var(--text-muted)',
                      top: 12, left: 12,
                      letterSpacing: '0.04em',
                      fontWeight: 700,
                      padding: '4px 8px',
                      border: '1px solid var(--border)'
                    }
                  }, 'NO LISTINGS'),
                  !r.listing.__noListing && r.snap != null && r.delta !== 0 && h('div', {
                    className: 'watchlist-delta ' + (r.delta < 0 ? 'drop' : 'rise'),
                    title: `Starred at ${fmt(r.snap)} · now ${fmt(r.listing.price)}`
                  },
                    r.delta < 0 ? '▼ ' : '▲ ',
                    fmt(Math.abs(r.delta)),
                    ' (', (r.pct >= 0 ? '+' : ''), r.pct.toFixed(1), '%)'
                  )
                )))
        )
  );
}

// ── Wallet (deposit/withdraw/history) ───────────────────────────
export function WalletModal({ wallet, transactions, onClose, onRefresh, initialTab }) {
  const [tab, setTab]       = useState(initialTab || 'deposit');
  const [amount, setAmount] = useState('');
  const [dest, setDest]     = useState('');
  const [totpCode, setTotpCode] = useState('');
  const [busy, setBusy]     = useState(false);
  const [error, setError]   = useState('');

  // Fee preview for deposits and withdrawals. Matches the rates in the
  // homepage fee calculator so users see the same numbers everywhere.
  const amt = parseFloat(amount) || 0;
  const depositFee = amt * 0.028 + (amt > 0 ? 0.30 : 0);   // 2.8% + $0.30 (Stripe card)
  const depositNet = Math.max(0, amt - depositFee);
  const withdrawFee = amt * 0.015;                         // 1.5% platform
  const withdrawNet = Math.max(0, amt - withdrawFee);

  const submit = async () => {
    setError('');
    const num = parseFloat(amount);
    if (!num || num <= 0) { setError('Enter a valid amount'); return; }
    if (num > 10000) { setError('Maximum per transaction is $10,000'); return; }
    setBusy(true);
    try {
      if (tab === 'deposit') {
        const res = await depositFunds(num);
        if (res.code || res.error) { setError(res.message || res.error); return; }
        if (res.live && res.checkoutUrl) {
          window.location.href = res.checkoutUrl;
          return;
        }
        setAmount('');
        await onRefresh();
      } else {
        const res = await withdrawFunds(num, dest, totpCode);
        if (res.code || res.error) {
          // If 2FA required but missing, hint at the TOTP input instead
          // of just showing the raw message.
          if (res.code === 'TOTP_REQUIRED' || res.code === 'TOTP_INVALID') {
            setError(res.message || 'Two-factor code required');
          } else {
            setError(res.message || res.error);
          }
          return;
        }
        setAmount(''); setDest(''); setTotpCode('');
        await onRefresh();
      }
    } catch (e) {
      setError(e.message || 'Request failed');
    } finally {
      setBusy(false);
    }
  };

  const presets = tab === 'deposit' ? [25, 50, 100, 250, 500] : [25, 50, 100, 250];

  return h('div', { className: 'modal-backdrop', onClick: onClose },
    h('div', { className: 'modal wallet-modal', onClick: e => e.stopPropagation() },
      h('button', { className: 'modal-close', onClick: onClose, 'aria-label': 'Close wallet' }, '✕'),
      h('div', { className: 'wallet-hero' },
        h('div', { className: `wallet-mode-pill ${wallet.stripeLive ? 'live' : 'dev'}` },
          wallet.stripeLive ? '● STRIPE LIVE' : '● DEV MODE'),
        h('div', { className: 'wallet-hero-label' }, 'Wallet Balance'),
        h('div', { className: 'wallet-hero-balance' }, fmt(wallet.balance)),
        h('div', { className: 'wallet-hero-user' }, '@' + wallet.username)
      ),
      h('div', { className: 'wallet-tabs' },
        h('button', { className: `wallet-tab ${tab === 'deposit' ? 'active' : ''}`,  onClick: () => { setTab('deposit');  setError(''); } }, 'Deposit'),
        h('button', { className: `wallet-tab ${tab === 'withdraw' ? 'active' : ''}`, onClick: () => { setTab('withdraw'); setError(''); } }, 'Withdraw'),
        h('button', { className: `wallet-tab ${tab === 'history' ? 'active' : ''}`,  onClick: () => { setTab('history');  setError(''); } }, 'History'),
      ),
      h('div', { className: 'wallet-panel' },
        tab === 'history'
          ? h('div', { className: 'wallet-tx-list' },
              transactions.length === 0
                ? h('div', { className: 'wallet-tx-empty' }, 'No transactions yet')
                : transactions.map(tx => {
                    const inbound = tx.type === 'DEPOSIT' || tx.type === 'SALE' || tx.type === 'REFUND' || tx.type === 'ADJUSTMENT_CREDIT';
                    // Defensive: tx.type should always be a string from the
                    // server, but some historical rows under ddl-auto: update
                    // had null types. Guard so one bad row doesn't crash the
                    // whole Transactions tab.
                    const typeLabel = (tx.type || 'UNKNOWN').toString();
                    const prettyType = typeLabel.charAt(0) + typeLabel.slice(1).toLowerCase().replace('_', ' ');
                    return h('div', { key: tx.id, className: 'wallet-tx' },
                      h('div', { className: `wallet-tx-icon ${inbound ? 'in' : 'out'}` }, inbound ? '↓' : '↑'),
                      h('div', { className: 'wallet-tx-main' },
                        h('div', { className: 'wallet-tx-type' }, prettyType),
                        h('div', { className: 'wallet-tx-desc' }, tx.description || tx.stripeReference)
                      ),
                      h('div', { className: 'wallet-tx-right' },
                        h('div', { className: `wallet-tx-amt ${inbound ? 'in' : 'out'}` }, (inbound ? '+' : '−') + fmt(tx.amount)),
                        h('div', { className: `wallet-tx-status ${tx.status}` }, tx.status)
                      )
                    );
                  })
            )
          : h('div', null,
              h('div', { className: 'wallet-input-label' },
                tab === 'deposit' ? 'Amount to Deposit (USD)' : 'Amount to Withdraw (USD)'),
              h('input', {
                className: 'wallet-amount-input',
                type: 'number', min: '0', max: '10000', step: '0.01',
                placeholder: '0.00',
                value: amount,
                onChange: e => setAmount(e.target.value),
                'aria-label': tab === 'deposit' ? 'Deposit amount' : 'Withdrawal amount'
              }),
              h('div', { className: 'wallet-quick' },
                presets.map(a =>
                  h('button', { key: a, className: 'wallet-quick-btn', onClick: () => setAmount(String(a)) }, '$' + a)
                )
              ),

              // Fee breakdown — appears once a non-zero amount is entered.
              amt > 0 && h('div', { className: 'wallet-fee-breakdown' },
                h('div', { className: 'wallet-fee-row' },
                  h('span', null, tab === 'deposit' ? 'Amount' : 'Withdraw'),
                  h('strong', null, fmt(amt))
                ),
                h('div', { className: 'wallet-fee-row' },
                  h('span', null, tab === 'deposit' ? 'Stripe fee (2.8% + $0.30)' : 'Platform fee (1.5%)'),
                  h('strong', { style: { color: 'var(--red)' } }, '−' + fmt(tab === 'deposit' ? depositFee : withdrawFee))
                ),
                h('div', { className: 'wallet-fee-row total' },
                  h('span', null, tab === 'deposit' ? 'You receive (credited)' : 'You receive (payout)'),
                  h('strong', { style: { color: 'var(--accent)' } }, fmt(tab === 'deposit' ? depositNet : withdrawNet))
                )
              ),

              tab === 'withdraw' && h('div', { style: { marginTop: 16 } },
                h('div', { className: 'wallet-input-label' }, 'Destination'),
                h('input', {
                  className: 'price-input',
                  style: { width: '100%', padding: '12px 14px', fontSize: 13 },
                  placeholder: 'acct_1ABC… · Stripe Connect account or payout notes',
                  value: dest,
                  onChange: e => setDest(e.target.value)
                }),
                h('div', { className: 'wallet-input-label', style: { marginTop: 14 } },
                  'Two-factor code (only if you enabled 2FA)'),
                h('input', {
                  className: 'wallet-amount-input',
                  type: 'text', inputMode: 'numeric', maxLength: 6,
                  placeholder: '000000',
                  value: totpCode,
                  onChange: e => setTotpCode(e.target.value.replace(/\D/g, '')),
                  style: { letterSpacing: '4px' }
                })
              ),

              error && h('div', { className: 'wallet-error' }, error),
              h('button', {
                className: 'btn btn-accent wallet-submit',
                disabled: busy || !amt,
                onClick: submit
              }, busy ? 'Processing…' : tab === 'deposit' ? (wallet.stripeLive ? 'Continue to Stripe →' : 'Deposit (dev mode)') : 'Request Withdrawal'),
              h('div', { className: 'wallet-note' },
                tab === 'deposit'
                  ? (wallet.stripeLive
                      ? 'You will be redirected to Stripe Checkout. The $0.30 Stripe fee is waived for deposits above $250.'
                      : 'Dev mode: no Stripe keys configured — funds credited instantly for local testing.')
                  : (wallet.stripeLive
                      ? 'Payouts require a Stripe Connect destination. Withdrawals are PENDING until an admin approves them.'
                      : 'Dev mode: balance debited immediately and withdrawal marked completed.')
              ),
              tab === 'deposit' && h('div', { className: 'wallet-stripe-row' },
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
                h('div', { className: 'wallet-stripe-info' },
                  h('div', { className: 'wallet-stripe-title' }, 'Secure card payments'),
                  h('div', { className: 'wallet-stripe-sub' }, 'Visa · Mastercard · Amex · Apple Pay · Google Pay')
                )
              )
            )
      )
    )
  );
}

// ── My Listings (legacy alias for older nav buttons) ───────────
export function MyListingsModal({ onClose, me }) {
  return h(InfoModal, { title: 'My Listings', onClose },
    me
      ? h('div', { className: 'empty-inline' },
          h('div', { className: 'empty-icon' }, '📦'),
          h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } }, 'See My Stall instead'),
          h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 360, margin: '0 auto 18px' } },
            "Use the 'My Stall' menu item to manage what you're selling."),
          h('button', { className: 'btn btn-accent', onClick: onClose }, 'Browse market →')
        )
      : h('div', { className: 'empty-inline' },
          h('div', { className: 'empty-icon' }, '🔒'),
          h('div', { style: { fontSize: 14, color: 'var(--text-secondary)', marginBottom: 14 } }, 'Sign in with Steam to manage your listings.'),
          h('a', { className: 'steam-btn', href: '/api/auth/steam/login' },
            h('div', { className: 'steam-btn-icon' }, '◆'),
            'Sign in through Steam'
          )
        )
  );
}
