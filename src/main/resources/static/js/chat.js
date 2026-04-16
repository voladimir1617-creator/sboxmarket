// Left-rail live chat. Messages are client-side only for now (no backend).
import { h, React, useState, useEffect } from './utils.js';

const CHAT_SEED = [
  { name: 'Metastable Nebb',  lvl: 457,  msg: 'RUST STORE',                                                                      shop: 'ee31…e850', time: '22:21' },
  { name: 'Roi aka Floi',     lvl: 271,  msg: '',                                                                                 shop: '1141…e850', time: '03:46' },
  { name: 'Random Particles', lvl: 228,  msg: 's&box store',                                                                      shop: '06b6…e850', time: '05:01' },
  { name: 'Seamless Ramekin', lvl: 51,   msg: 'is crypto withdraw back up?',                                                      shop: null,        time: '07:39' },
  { name: 'Waspish Eddo',     lvl: 109,  msg: '30% off ALL Wizard skins. Get them fast! I need all of these sold.',               shop: '9933…8117', time: '09:33' },
  { name: 'Dietetic Scotsman',lvl: 2468, msg: '',                                                                                 shop: '827f…b577', time: '10:12' },
  { name: 'Dietetic Scotsman',lvl: 2468, msg: 's&box store',                                                                      shop: null,        time: '10:12' },
  { name: 'Dietetic Scotsman',lvl: 2468, msg: 'buying sbx keys 1.75 need 220',                                                    shop: null,        time: '10:24' },
  { name: 'Metastable Nebb',  lvl: 457,  msg: 'SBX STORE',                                                                        shop: 'ee31…e850', time: '11:02' },
  { name: 'Unglazed Bilges',  lvl: 39,   msg: '',                                                                                 shop: '1647…11fe', time: '11:49' },
  { name: 'waSted',           lvl: 0,    msg: 'ughhh',                                                                            shop: null,        time: '22:37' },
  { name: 'waSted',           lvl: 0,    msg: 'all these cool skins and i dont have any',                                         shop: null,        time: '22:37' },
  { name: 'SkinKing99',       lvl: 812,  msg: 'wts Wizard Hat, taking offers',                                                    shop: '4a21…9f12', time: '23:02' },
  { name: 'ShadowTrade',      lvl: 1344, msg: 'buy order: Skull Helmet @ $220',                                                   shop: null,        time: '23:11' },
  { name: 'NeonSeller',       lvl: 66,   msg: 'giveaway tonight, 5 winners',                                                      shop: '88aa…ff00', time: '23:24' },
  { name: 'ProFlip',          lvl: 189,  msg: 'anyone know the floor on Bear Head?',                                              shop: null,        time: '23:41' },
];

export function ChatPanel({ hidden, onToggle, me }) {
  const [messages, setMessages] = useState(CHAT_SEED);
  const [draft, setDraft]       = useState('');
  const [hideName, setHN]       = useState(true);
  const listRef = React.useRef(null);

  // Stable "online users" count — computed once on mount so it does NOT change
  // when the user types or sends a message. The previous version reused
  // `messages.length` which made the number visibly tick up on every keystroke
  // / send, and the green dot next to it read as "live users online" — so the
  // header looked like a broken user counter. Keep it as a small random drift.
  const [online] = useState(() => 980 + Math.floor(Math.random() * 240));

  useEffect(() => {
    if (listRef.current) listRef.current.scrollTop = listRef.current.scrollHeight;
  }, [messages]);

  const send = () => {
    const text = draft.trim();
    if (!text) return;
    const now = new Date();
    const time = String(now.getHours()).padStart(2, '0') + ':' + String(now.getMinutes()).padStart(2, '0');
    const senderName = me?.displayName || 'You';
    setMessages(m => [...m, { name: senderName, lvl: 1, msg: text, shop: null, time, mine: true }]);
    setDraft('');
  };

  // When hidden, render just a narrow "reopen" tab on the left edge of the
  // viewport. Clicking it brings the full panel back. Before this, closing
  // the chat was a one-way trip until page reload.
  if (hidden) {
    return h('button', {
      className: 'chat-reopen',
      onClick: onToggle,
      title: 'Open live chat',
      'aria-label': 'Open live chat'
    },
      h('span', { className: 'chat-reopen-icon' }, '›'),
      h('span', { className: 'chat-reopen-label' }, 'Chat'),
      h('span', { className: 'chat-reopen-dot' })
    );
  }

  return h('aside', { className: 'chat-panel' },
    h('div', { className: 'chat-header' },
      h('div', { className: 'chat-header-title' },
        h('span', { className: 'chat-header-dot' }),
        'Live Chat',
        h('span', { className: 'chat-header-count' }, online + ' online')
      ),
      h('button', { className: 'chat-collapse', onClick: onToggle, title: 'Hide chat', 'aria-label': 'Hide chat' }, '‹')
    ),
    h('div', { className: 'chat-messages', ref: listRef },
      messages.map((m, i) =>
        h('div', { key: i, className: `chat-msg ${m.mine ? 'mine' : ''}` },
          h('div', { className: 'chat-msg-head' },
            h('div', { className: 'chat-msg-avatar' }, (m.name || 'U').substring(0, 2).toUpperCase()),
            h('span', { className: 'chat-msg-name' }, m.name),
            h('span', { className: 'chat-msg-level' }, 'Lvl ' + m.lvl),
            h('span', { className: 'chat-msg-time' }, m.time)
          ),
          m.msg && h('div', { className: 'chat-msg-body' }, m.msg),
          m.shop && h('a', { className: 'chat-shop-link', href: '#' }, `USER SHOP (${m.shop}) ↗`)
        )
      )
    ),
    h('div', { className: 'chat-input-wrap' },
      h('input', {
        className: 'chat-input',
        placeholder: me ? 'Send a message...' : 'Sign in to chat',
        value: draft,
        disabled: !me,
        onChange: e => setDraft(e.target.value),
        onKeyDown: e => { if (e.key === 'Enter') send(); },
        maxLength: 240
      }),
      h('button', {
        className: 'chat-send',
        onClick: send,
        disabled: !me || !draft.trim(),
        title: 'Send',
        'aria-label': 'Send chat message'
      }, '↑')
    ),
    h('div', { className: 'chat-footer' },
      'Hide my Steam name',
      h('div', { className: `chat-toggle ${hideName ? '' : 'off'}`, onClick: () => setHN(v => !v) })
    )
  );
}
