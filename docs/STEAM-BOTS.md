# Real Steam bots: how Skin.Club does it, and where SkinBox stands

Written 2026-10-15 for whoever runs SkinBox. Skin.Club facts come from its own
public FAQ (skin.club/en/faq) and help articles (community.skin.club), read
2026-10-15. Nothing was scraped and no prices were copied.

## 1. How Skin.Club runs real skins

| Step | What Skin.Club does |
|---|---|
| Bots | "All trades are carried out automatically using Steam bots." Its bots hold the house's skins and send and receive every trade. Staff never add users on Steam; anyone claiming to be a Skin.Club "trade bot" in your friend list is a scammer. |
| Sign-in | Steam or Google login. Before a user can pay with skins or withdraw, they add a Steam trade URL. |
| Trade URL check | On the first withdrawal the user signs in through Steam and Skin.Club checks that the SteamID in the trade URL matches. Each trade URL can be on one account only, can change once every 30 days, and is locked for 8 days while a skin payment is on hold. Errors: bad format, already linked, outdated (regenerated in Steam). |
| Paying with skins (deposit) | The user sends skins from their inventory to a bot. Since Steam's Trade Protection, the deposit sits "On Hold" for **8 days** before it becomes balance. The user must tick a box accepting the hold, sees the exact credit time, and tracks it under Profile, Payments, On Hold. If the trade is not reversed in that time, the balance is added automatically. |
| Withdrawing | The user picks a skin and presses Take or Send. A bot prepares a trade offer, a Get button opens it in Steam, the user accepts. "Withdrawals typically take only a few minutes." There is no cash withdrawal. |
| Before a withdrawal | The user's Steam inventory must be public, they must not be trade banned, and at least 7 days must have passed since a password change. |
| Reversals | If a user reverses a withdrawal with Steam's Trade Protection, the skin goes back, nothing is refunded, and Steam locks the user's account from trading for 30 days. |
| Stock | When a case lacks one of its skins it shows "Temporarily Unavailable", and "bots are monitoring the trade platform and purchase the missing items all the time." |
| Owner | Moontain Limited, Cyprus (from the site footer). |

Skin.Club is a case site that holds a house balance. SkinBox is a plain
marketplace, with no cases, coinflip or raffles, so only the bot and custody
parts carry over: bots that receive and send skins, the Trade Protection hold,
trade URL checks, and watching what the bots really hold.

## 2. What SkinBox already had (main, before this change)

All of this is real code and switched off. Nothing runs unless
`STEAM_BOT_BASE_URL` is set, and the bot itself refuses to start without
`STEAM_BOT_LIVE=yes`. It has only ever run against a fake Steam.

- **Bot sidecar** (`steam-bot/`), on steam-user, steamcommunity and
  steam-tradeoffer-manager: it sends offers, requests skins, reads offer status
  and lists its inventory. It confirms only offers it sent itself, never sends
  one skin in two offers, refuses any incoming offer that takes its skins, and
  logs in again with backoff.
- **Deposit for a listing**: a seller lists, the bot asks for that one skin,
  and the listing goes live when the skin is in the bot. Steam's 7 to 15 day
  mobile-authenticator trade hold is handled.
- **Delivery**: a buyer pays, the bot sends the skin, and the seller is paid
  only after the buyer accepts.
- **Return**: an unsold or cancelled listing goes back to the seller, with
  retries and a re-send when the seller declines.
- **Trade URL check**: the URL's account id must match the signed-in SteamID,
  and no two accounts may share one (`ProfileController.setTradeUrl`).
- **Security code** on every bot offer, shown on the site too.

## 3. What was missing, and what this change builds

| Gap | Now |
|---|---|
| The bot asked Steam for tradable skins only, so a deposit under Trade Protection never appeared and would have sat as "missing". | `GET /inventory` returns every skin with Steam's `tradable` flag. |
| A deposit went on sale the moment it reached the bot. Under Trade Protection the bot cannot send it for 7 days, and the seller can reverse it, keeping the skin while the listing stays up. | Skin.Club's hold, copied: custody `IN_TRADE_PROTECTION`, listing off sale for `steam.escrow.trade-protection-hours` (default 192, 8 days) **and** until Steam says tradable. The seller sees "goes live on (date)" in My Stall and gets a notification. Cancelling during the hold queues the return. |
| Nothing noticed a reversed deposit. | A skin that leaves the bot during the hold (seen on two reads) marks the deposit `REVERSED` and cancels the listing. Nothing was sold, so no money moves. |
| Nothing compared the database with what the bot really holds. | An hourly inventory check takes off sale any live listing whose skin is gone and lists skins nobody expects. Staff: `GET` or `POST /api/admin/steam-bot/inventory-check`. It never sends a skin. |

Still not built, on purpose:
- **More than one bot.** One account is enough for a marketplace this size.
  More bots means more accounts, phones and secrets, which is more for the
  operator to hold.
- **Storing skins without listing them, or skins for balance.** That is the
  Skin.Club wallet model. Holding goods and balances for users is a bigger
  legal and money step, and SkinBox is a plain market.
- **House stock delivered by the bot.** House listings exist only in the
  database, and no real skins back them.

## 4. Only the owner (or the operator) can do these

The code is ready for these steps, and none of them can be done for you:
1. Make a new Steam account for the bot, never a personal one, and spend $5 on
   it so it can trade.
2. Turn on the Steam Mobile Authenticator on that account's phone, then wait
   out Steam's new-authenticator trade hold (up to 15 days).
3. Put the bot's own `shared_secret` and `identity_secret` and a long
   `BOT_API_TOKEN` into `steam-bot/.env` yourself (see steam-bot/README.md).
   Optionally create the bot's Steam Web API key at
   steamcommunity.com/dev/apikey while signed in as the bot.
4. Run the bot on an always-on machine next to the SkinBox server. It listens
   on 127.0.0.1 only.
5. Say "go live". Then set `STEAM_BOT_LIVE=yes` on the bot and
   `STEAM_BOT_BASE_URL` on the server.
6. First real test: deposit one cheap skin, cancel it, and check it comes back.
   If it arrives in the bot already tradable, s&box is not under Trade
   Protection and `trade-protection-hours` can be set to 0.
