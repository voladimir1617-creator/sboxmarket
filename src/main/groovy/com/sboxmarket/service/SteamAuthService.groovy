package com.sboxmarket.service

import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import groovy.xml.XmlSlurper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

import java.util.regex.Pattern

@Service
@Slf4j
class SteamAuthService {

    static final String STEAM_OPENID = "https://steamcommunity.com/openid/login"
    static final Pattern STEAMID_REGEX = ~/^https?:\/\/steamcommunity\.com\/openid\/id\/(\d+)$/

    @Value('${steam.api-key:}')            String steamApiKey
    @Value('${steam.realm:http://localhost:8080/}')                   String realm
    @Value('${steam.return-url:http://localhost:8080/api/auth/steam/return}') String returnUrl

    @Autowired SteamUserRepository steamUserRepository
    @Autowired WalletRepository walletRepository
    // Lazy to break the cycle: AdminService → SteamUserRepository → SteamAuthService
    @Autowired(required = false) @Lazy AdminService adminService
    @Autowired(required = false) NotificationService notificationService

    /** One-time-use guard for OpenID assertions (security QA P1).
     *  Steam's `check_authentication` endpoint returns `is_valid:true`
     *  REPEATEDLY for the same signed assertion — it does NOT enforce
     *  one-time use. Without tracking, an attacker who captures a
     *  victim's full `/return?...` callback URL can replay it verbatim
     *  and log in as the victim. We record each consumed
     *  `openid.response_nonce` and reject any assertion whose nonce we
     *  have already seen. Cap at 5000 with FIFO eviction so a long-lived
     *  container can't accumulate unbounded state — same pattern as
     *  StripeService.seenEventIds. */
    private static final int SEEN_NONCES_CAP = 5000
    private final java.util.LinkedHashSet<String> seenNonces = new java.util.LinkedHashSet<>()

    /** Atomically check-and-record an OpenID nonce. Returns true if the
     *  nonce is fresh (and is now recorded as consumed), false if it has
     *  already been seen — i.e. a replay. A null/blank nonce is treated
     *  as a replay (rejected): a valid Steam assertion always carries
     *  `openid.response_nonce`, so its absence means a malformed or
     *  tampered callback. Synchronized because OpenID returns land on
     *  the Tomcat worker pool — two concurrent replays could otherwise
     *  race past the check. */
    private synchronized boolean consumeNonce(String nonce) {
        if (nonce == null || nonce.isEmpty()) return false
        if (seenNonces.contains(nonce)) return false
        if (seenNonces.size() >= SEEN_NONCES_CAP) {
            def oldest = seenNonces.iterator().next()
            seenNonces.remove(oldest)
        }
        seenNonces.add(nonce)
        return true
    }

    /**
     * Extract a single parameter's value from a raw, still-URL-encoded
     * query string and URL-decode it. We parse the raw string (rather
     * than a servlet-decoded map) so this stays consistent with the
     * verbatim string we forward to Steam. Returns null if the param is
     * absent. The key is matched at a parameter boundary (start-of-string
     * or `&`) so `openid.return_to` can't be matched inside a sibling
     * like `x_openid.return_to=...`.
     */
    private static String paramFromQuery(String rawQueryString, String key) {
        if (!rawQueryString) return null
        def m = rawQueryString =~ ('(?:^|&)' + Pattern.quote(key) + '=([^&]*)')
        if (!m.find()) return null
        try {
            return URLDecoder.decode(m.group(1), 'UTF-8')
        } catch (Exception e) {
            log.warn("Failed to decode query param ${key}: ${e.message}")
            return null
        }
    }

    /** Build the URL we redirect the browser to so Steam can authenticate the user. */
    String buildLoginUrl() {
        def params = [
            'openid.ns'         : 'http://specs.openid.net/auth/2.0',
            'openid.mode'       : 'checkid_setup',
            'openid.return_to'  : returnUrl,
            'openid.realm'      : realm,
            'openid.identity'   : 'http://specs.openid.net/auth/2.0/identifier_select',
            'openid.claimed_id' : 'http://specs.openid.net/auth/2.0/identifier_select',
        ]
        def query = params.collect { k, v ->
            "${URLEncoder.encode(k, 'UTF-8')}=${URLEncoder.encode(v, 'UTF-8')}"
        }.join('&')
        "${STEAM_OPENID}?${query}"
    }

    /**
     * Verify the OpenID response. We forward Steam's original query string
     * verbatim with only `openid.mode` changed to `check_authentication` —
     * this avoids any re-encoding drift that would invalidate the signature.
     * Returns the SteamID64 on success, or null if verification fails.
     */
    String verifyReturn(String rawQueryString, String claimedIdParam) {
        if (!rawQueryString) {
            log.warn("Steam verify: empty query string")
            return null
        }

        // Validate the signed `openid.return_to` (security QA P2) BEFORE
        // the network round-trip. Steam signs return_to, so a mismatch
        // already implies a broken signature — but asserting it
        // explicitly is a standard OpenID relying-party requirement and
        // defence-in-depth. Checking it up front also avoids a wasted
        // check_authentication call on an obviously-bad callback. Reject
        // unless it equals our configured return URL.
        def returnTo = paramFromQuery(rawQueryString, 'openid.return_to')
        if (returnTo != returnUrl) {
            log.warn("Steam OpenID return_to mismatch")
            return null
        }

        // Replace openid.mode=id_res with openid.mode=check_authentication,
        // without touching any other characters. The match is anchored to a
        // parameter boundary (start-of-string or `&`) so it can only ever
        // rewrite the real `openid.mode` param — an unanchored pattern would
        // also match a sibling like `x_openid.mode=...`, leaving the genuine
        // `openid.mode=id_res` in place and breaking verification.
        def body = rawQueryString.replaceFirst(/(^|&)openid\.mode=[^&]*/, '$1openid.mode=check_authentication')

        String response = checkAuthentication(body)
        if (response == null) {
            // Network/transport failure — already logged in checkAuthentication.
            return null
        }

        if (!response.contains('is_valid:true')) {
            log.warn("Steam OpenID reports invalid")
            return null
        }

        // Reject replayed assertions (security QA P1). Steam's
        // check_authentication returns is_valid:true repeatedly for the
        // SAME assertion, so a captured /return URL can be replayed
        // verbatim. consumeNonce records the response_nonce on first use
        // and returns false on any subsequent sighting (or if the nonce
        // is missing entirely).
        def nonce = paramFromQuery(rawQueryString, 'openid.response_nonce')
        if (!consumeNonce(nonce)) {
            log.warn("Steam OpenID assertion rejected: nonce missing or already used (replay)")
            return null
        }

        def m = claimedIdParam =~ STEAMID_REGEX
        if (!m.find()) {
            log.warn("Unexpected claimed_id format: $claimedIdParam")
            return null
        }
        m.group(1)
    }

    /**
     * POST the (mode-rewritten) assertion body to Steam's
     * `check_authentication` endpoint and return the raw text response,
     * or null on any transport failure. Split out from {@link #verifyReturn}
     * both for readability and so it can be stubbed in tests — the same
     * reason {@code fetchViaWebApi}/{@code fetchViaPublicXml} are their
     * own methods.
     */
    protected String checkAuthentication(String body) {
        String response
        int status
        try {
            def conn = (HttpURLConnection) new URL(STEAM_OPENID).openConnection()
            conn.requestMethod = 'POST'
            conn.doOutput = true
            conn.setRequestProperty('Content-Type', 'application/x-www-form-urlencoded')
            conn.setRequestProperty('Accept', 'text/plain')
            conn.setRequestProperty('User-Agent', 'SkinBox/1.0')
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.outputStream.withWriter('UTF-8') { it.write(body) }
            status = conn.responseCode
            def stream = (status >= 200 && status < 300) ? conn.inputStream : conn.errorStream
            response = stream ? stream.getText('UTF-8') : ''
        } catch (Exception e) {
            log.warn("Steam verification request threw: ${e.message}", e)
            return null
        }
        log.info("Steam verify HTTP $status, body: ${response?.replaceAll(/\s+/, ' ')?.take(200)}")
        return response
    }

    /** Find-or-create a Steam user (and their wallet) after a successful login. */
    @Transactional
    SteamUser upsertUser(String steamId64) {
        def user = steamUserRepository.findBySteamId64(steamId64)
        def isNew = (user == null)
        if (isNew) {
            user = new SteamUser(
                steamId64:   steamId64,
                displayName: "Player_${steamId64.takeRight(6)}"
            )
            user = steamUserRepository.save(user)
            walletRepository.save(new Wallet(
                username: "steam_${steamId64}",
                balance : BigDecimal.ZERO
            ))
            log.info("Created new Steam user: $steamId64")
            // Welcome push — first-time users otherwise land on an empty
            // bell + empty wallet with zero guidance. A single notification
            // anchored to the Profile page's setup checklist gives them
            // a concrete next step (set trade URL, verify email, top up).
            // Non-fatal: any failure here is logged but doesn't abort
            // the login.
            try {
                notificationService?.push(user.id, 'WELCOME',
                    "Welcome to SkinBox",
                    "Set your Steam trade URL, verify your email, and top up your wallet to start buying and selling.",
                    null,
                    '/profile')
            } catch (Exception e) {
                log.warn("Welcome notification failed for new user ${steamId64}: ${e.message}")
            }
        } else {
            user.lastLoginAt = System.currentTimeMillis()
            user = steamUserRepository.save(user)
        }

        // Fetch profile. Prefer the Steam Web API (if a key is configured),
        // but fall back to the public profile XML endpoint which needs no key.
        def profile = null
        if (steamApiKey) {
            try { profile = fetchViaWebApi(steamId64) }
            catch (Exception e) { log.warn("Steam Web API lookup failed: ${e.message}") }
        }
        if (profile == null) {
            try { profile = fetchViaPublicXml(steamId64) }
            catch (Exception e) { log.warn("Steam XML lookup failed: ${e.message}") }
        }

        if (profile != null) {
            if (profile.displayName) user.displayName = profile.displayName
            if (profile.avatarUrl)   user.avatarUrl   = profile.avatarUrl
            if (profile.profileUrl)  user.profileUrl  = profile.profileUrl
            user = steamUserRepository.save(user)
        }

        // Bootstrap-admin promotion — if this steamId64 is listed in
        // admin.bootstrap-steam-ids it gets auto-promoted on every login.
        // Safe to call even when the service is null (no env var set).
        try { adminService?.promoteBootstrapAdmin(user) }
        catch (Exception e) { log.warn("Admin bootstrap check failed: ${e.message}") }

        user
    }

    /** Call the official Steam Web API. Requires STEAM_API_KEY. */
    private Map fetchViaWebApi(String steamId64) {
        def apiUrl = "https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v0002/?key=${steamApiKey}&steamids=${steamId64}"
        def text = new URL(apiUrl).getText('UTF-8')
        def json = new JsonSlurper().parseText(text)
        def p = json?.response?.players?.find { it.steamid == steamId64 }
        if (p == null) return null
        [displayName: p.personaname, avatarUrl: p.avatarfull, profileUrl: p.profileurl]
    }

    /**
     * Scrape the PUBLIC Steam profile XML — works for anyone without an API key.
     * Every Steam profile has a ?xml=1 variant that exposes displayName + avatars.
     * This is the same mechanism CSFloat-style sites use so users never have to
     * set up any credentials.
     */
    private Map fetchViaPublicXml(String steamId64) {
        def xmlUrl = "https://steamcommunity.com/profiles/${steamId64}/?xml=1"
        def conn = (HttpURLConnection) new URL(xmlUrl).openConnection()
        conn.setRequestProperty('User-Agent', 'SkinBox/1.0')
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        def text = conn.inputStream.getText('UTF-8')
        def profile = new XmlSlurper().parseText(text)
        [
            displayName: profile.steamID?.text() ?: null,
            avatarUrl  : profile.avatarFull?.text() ?: profile.avatarMedium?.text() ?: null,
            profileUrl : "https://steamcommunity.com/profiles/${steamId64}"
        ]
    }
}
