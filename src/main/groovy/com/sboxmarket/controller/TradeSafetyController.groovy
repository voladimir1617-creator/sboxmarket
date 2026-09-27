package com.sboxmarket.controller

import com.sboxmarket.service.SteamEscrowService
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

import java.nio.charset.StandardCharsets

/**
 * Serves <code>/legal/trade-safety.html</code> with the custody claims filled in
 * from the mode the platform is ACTUALLY running in.
 *
 * ── The defect this exists to make impossible ─────────────────────────────
 * The safety page is the page a seller reads before deciding whether a Steam
 * trade offer is genuine. It used to state, unconditionally:
 *
 *     "SkinBox is non-custodial. We never hold your skins... No bot account
 *      ever touches the items."
 *     "Never accept a trade offer from a SkinBox 'bot' — we don't have one.
 *      ...If someone DMs you claiming to be 'the SkinBox bot', it is a scam."
 *
 * Both sentences are true only while {@code STEAM_BOT_BASE_URL} is unset. Set
 * it, and {@code SteamEscrowService.requestDepositForListing} sends every
 * seller precisely that offer to deposit their item into bot custody — so the
 * page would be instructing sellers to refuse the platform's own trade offer
 * as a scam, and telling every user that a custodial platform is non-custodial.
 * It was a factual custody claim on a money page with NOTHING in the codebase
 * connecting it to the config value that falsifies it.
 *
 * The fix is not to delete the claim — the claim is load-bearing safety advice
 * while it holds. It is to make the page describe the mode the system is in.
 * {@link SteamEscrowService#isEscrowEnabled()} is the single source of truth
 * for that mode (it is what actually gates the deposit offer), and it is read
 * here on every request, so the page cannot lag a config change.
 *
 * ── Fail-safe ─────────────────────────────────────────────────────────────
 * The placeholders in the HTML are comments. If this controller is ever
 * bypassed and the raw file is served by the static resource handler, the
 * reader sees a page carrying NO custody claim — incomplete, but never false.
 * The false-when-custodial sentences do not exist in the file any more.
 *
 * A controller mapping wins over the static resource handler (Spring's
 * RequestMappingHandlerMapping is ordered ahead of the resource
 * SimpleUrlHandlerMapping), so this is what the URL actually serves.
 */
@RestController
@Slf4j
class TradeSafetyController {

    /** Optional so unit specs can construct the controller bare; when absent we
     *  report the non-custodial mode, which matches a build with no escrow
     *  service wired at all. */
    @Autowired(required = false) SteamEscrowService steamEscrowService

    static final String MODE_BOT_CUSTODY   = 'BOT_CUSTODY'
    static final String MODE_NON_CUSTODIAL = 'NON_CUSTODIAL'

    /** True when the platform takes physical custody of sellers' items — i.e.
     *  when the escrow bot is configured and WILL send deposit offers. */
    boolean isCustodial() {
        return steamEscrowService != null && steamEscrowService.escrowEnabled
    }

    String custodyMode() { custodial ? MODE_BOT_CUSTODY : MODE_NON_CUSTODIAL }

    /**
     * Machine-readable custody mode. Public and unauthenticated — it describes
     * how the product works, which is already stated on the safety page. The
     * SPA reads it so its own "non-custodial" product copy cannot outlive the
     * config either.
     */
    @GetMapping(['/api/custody', '/api/custody/'])
    Map custody() {
        [custodial: custodial, mode: custodyMode()]
    }

    @GetMapping(['/legal/trade-safety.html'])
    ResponseEntity<String> tradeSafety() {
        String html
        try {
            html = new ClassPathResource('static/legal/trade-safety.html')
                    .inputStream.getText(StandardCharsets.UTF_8.name())
        } catch (Exception e) {
            log.warn("Could not read the trade-safety template: ${e.message}")
            return ResponseEntity.status(500).body('Trade Safety is temporarily unavailable.')
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(render(html))
    }

    /** Substitute every custody placeholder for the current mode. */
    String render(String template) {
        boolean bot = custodial
        return template
                .replace('<!--SB:CUSTODY_CALLOUT-->',        bot ? CALLOUT_BOT        : CALLOUT_P2P)
                .replace('<!--SB:CUSTODY_BOT_RULE-->',       bot ? BOT_RULE_BOT       : BOT_RULE_P2P)
                .replace('<!--SB:CUSTODY_MIDDLEMAN_RULE-->', bot ? MIDDLEMAN_BOT      : MIDDLEMAN_P2P)
                .replace('<!--SB:CUSTODY_VERIFY_STEP-->',    bot ? VERIFY_STEP_BOT    : VERIFY_STEP_P2P)
    }

    // ── Copy: peer-to-peer (no bot configured) ────────────────────────────
    // Exactly what the page said before, preserved verbatim. It is TRUE in
    // this mode and it is good advice.

    private static final String CALLOUT_P2P = '''<div class="callout">
      <strong>SkinBox is non-custodial.</strong> We never hold your skins. When a
      trade clears, the seller sends a Steam trade offer <em>directly</em> to the
      buyer using the Steam trade URL on file, and the buyer's wallet funds release
      to the seller only after both sides confirm receipt in the SkinBox trade
      thread. No bot account ever touches the items.
    </div>'''

    private static final String BOT_RULE_P2P =
            '''<li><strong>Never accept a trade offer from a SkinBox "bot" — we don't have one.</strong> Every legitimate trade comes from the other user in the transaction (their Steam profile matches the seller/buyer shown in the SkinBox trade page). If someone DMs you claiming to be "the SkinBox bot", it is a scam.</li>'''

    private static final String MIDDLEMAN_P2P =
            '''<li><strong>Never send items to a "middle man".</strong> There is no such thing on SkinBox. The buyer's wallet is locked by Stripe on purchase, the seller sends the Steam trade offer directly, and both sides confirm in the trade thread before the cash releases.</li>'''

    private static final String VERIFY_STEP_P2P =
            '''<li>As the buyer, when the seller's Steam offer arrives in your mobile app or desktop client, verify the sender matches the seller shown on the SkinBox trade page. As the seller, verify the receiver matches the buyer's trade URL SkinBox passed you.</li>'''

    // ── Copy: bot custody (STEAM_BOT_BASE_URL is set) ─────────────────────
    // The platform DOES hold the items, and it DOES send sellers a trade
    // offer. Saying otherwise here would train sellers to refuse it.

    private static final String CALLOUT_BOT = '''<div class="callout">
      <strong>SkinBox holds listed items in escrow.</strong> When you list an item,
      our escrow bot sends you a Steam trade offer <em>requesting</em> that item, and
      your listing goes live once we have received it. We hold it until it sells,
      then the bot sends it on to the buyer; if the listing is cancelled or expires,
      the bot sends it back to you. The buyer's wallet funds release to the seller
      only after the buyer has actually received the item.
      <strong>This means SkinBox will send you trade offers</strong> — see below for
      how to tell ours apart from an impostor's.
    </div>'''

    private static final String BOT_RULE_BOT =
            '''<li><strong>Only ever accept a bot trade offer you started yourself on SkinBox.</strong> Our escrow bot sends you exactly two kinds of offer, and both are ones you triggered: an offer <em>requesting</em> an item right after you list it, and an offer <em>sending</em> you an item you bought. Every one of them is listed on your SkinBox trade page (<code>/profile/trades</code>) with the bot's Steam profile URL printed next to it — check the offer against that page before accepting. An unprompted offer, an offer for an item you did not list or buy, or anyone who DMs you on Discord, Steam or Telegram claiming to be "the SkinBox bot" is a scam.</li>'''

    private static final String MIDDLEMAN_BOT =
            '''<li><strong>Never send items to a "middle man".</strong> The only SkinBox account that will ever ask you for an item is the escrow bot shown on your own trade page, and only for an item you just listed. Nobody at SkinBox will ever ask you to send an item to a personal account, to a "verifier", or to anyone who contacts you first.</li>'''

    private static final String VERIFY_STEP_BOT =
            '''<li>When a Steam offer arrives in your mobile app or desktop client, verify the sender is the SkinBox escrow bot whose Steam profile URL is printed on your SkinBox trade page — not merely an account with a similar name or avatar. If the offer is not shown on that page, do not accept it.</li>'''
}
