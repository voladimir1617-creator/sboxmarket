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
    // Optional so existing specs that construct the service directly aren't
    // forced to wire it. When absent, sanitizeName falls back to a plain cap.
    @Autowired(required = false) TextSanitizer textSanitizer

    /**
     * Sanitize a Steam-supplied display name once at the trust boundary.
     * displayName is the most-reused user-influenced string in the app (OG
     * tags, email subjects/bodies, CSV exports, notification payloads); every
     * sink escapes it today, but sanitizing at the WRITE site means a future
     * sink that forgets to escape inherits a safe value. Strips HTML / control
     * chars via the shared sanitizer and caps at 64. Null/blank result (or a
     * hostile name the sanitizer empties out) falls back to the stable
     * `Player_<id6>` placeholder so the column is never blank.
     */
    private String sanitizeName(String raw, String steamId64) {
        def cleaned = textSanitizer != null ? textSanitizer.cleanShort(raw) : raw
        cleaned = (cleaned ?: '').trim().take(64)
        cleaned ?: "Player_${steamId64.takeRight(6)}".toString()
    }

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

    /** True if any `openid.*` key occurs more than once in the raw query.
     *  Keys are compared URL-decoded, so `openid%2Eclaimed_id` counts as a
     *  repeat of `openid.claimed_id`. */
    static boolean hasDuplicateOpenIdKey(String rawQueryString) {
        if (!rawQueryString) return false
        Set<String> seen = new HashSet<>()
        for (String pair : rawQueryString.split('&')) {
            if (!pair) continue
            int eq = pair.indexOf('=')
            String rawKey = eq >= 0 ? pair.substring(0, eq) : pair
            String key
            try {
                key = URLDecoder.decode(rawKey, 'UTF-8')
            } catch (Exception e) {
                return true   // undecodable key: refuse rather than guess
            }
            if (key.startsWith('openid.') && !seen.add(key)) return true
        }
        return false
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
     *
     * The SteamID64 is extracted from `openid.claimed_id` AS IT APPEARS IN
     * THE RAW, VERIFIED QUERY STRING — never from a servlet-decoded
     * parameter passed in alongside it. Steam's check_authentication only
     * attests the bytes of `rawQueryString`; a value sourced any other way
     * (e.g. `req.getParameter`, which can disagree with the raw string when
     * `openid.claimed_id` is duplicated) is unverified and must not decide
     * which account we log in. The optional `claimedIdParam` is accepted
     * for caller convenience / logging only and is NOT trusted: when it is
     * supplied it must match the raw value or verification fails.
     */
    String verifyReturn(String rawQueryString, String claimedIdParam = null) {
        if (!rawQueryString) {
            log.warn("Steam verify: empty query string")
            return null
        }

        // Reject any callback that repeats an `openid.*` key (2026-09-28
        // payments review). We read the FIRST value of each key, but Steam's
        // check_authentication parser may keep the LAST one, so an attacker
        // could prepend `openid.claimed_id=<victim>` to their own genuine,
        // signed callback: Steam validates the attacker's values and we log
        // in as the victim. A real Steam callback never repeats a key.
        if (hasDuplicateOpenIdKey(rawQueryString)) {
            log.warn("Steam OpenID callback rejected: an openid.* parameter appears more than once")
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

        // Match `is_valid:true` as a whole key:value LINE. Steam's
        // check_authentication response is line-oriented `key:value`
        // text; a substring check would also accept a hypothetical
        // `is_valid:true_x` or `not_is_valid:true`. Anchor to a line
        // boundary so only the genuine field satisfies it.
        if (!(response =~ /(?m)^is_valid:true\s*$/)) {
            log.warn("Steam OpenID reports invalid")
            return null
        }

        // The signature Steam just attested only covers the fields named
        // in `openid.signed`. A relying party MUST confirm that the
        // identity-bearing fields are actually in that set — otherwise an
        // attacker can take any validly-signed assertion, drop
        // `claimed_id`/`identity` out of `openid.signed`, and substitute
        // an arbitrary `openid.claimed_id` that check_authentication will
        // still report valid (the signature verifies over the REDUCED
        // field set). Require both identity fields to be signed.
        def signed = paramFromQuery(rawQueryString, 'openid.signed')
        def signedFields = signed ? signed.split(',').collect { it.trim() } as Set : [] as Set
        if (!signedFields.contains('claimed_id') || !signedFields.contains('identity')) {
            log.warn("Steam OpenID assertion rejected: claimed_id/identity not covered by openid.signed")
            return null
        }

        // Extract the SteamID64 from the claimed_id IN THE RAW, VERIFIED
        // query string — this is the value Steam signed and attested, not
        // whatever a servlet param map happened to decode.
        def claimedId = paramFromQuery(rawQueryString, 'openid.claimed_id')
        if (claimedId == null) {
            log.warn("Steam OpenID assertion rejected: no claimed_id in callback")
            return null
        }
        def m = claimedId =~ STEAMID_REGEX
        if (!m.find()) {
            log.warn("Unexpected claimed_id format: $claimedId")
            return null
        }
        def steamId64 = m.group(1)

        // Defence-in-depth: if the caller passed a claimed_id alongside
        // the raw query (the controller reads req.getParameter), it must
        // agree with the verified value. A mismatch means the servlet
        // decoded a different duplicate of openid.claimed_id than the one
        // Steam signed — reject rather than silently trusting either.
        if (claimedIdParam != null && claimedIdParam != claimedId) {
            log.warn("Steam OpenID assertion rejected: claimed_id param disagrees with signed value")
            return null
        }

        // Reject replayed assertions (security QA P1). Steam's
        // check_authentication returns is_valid:true repeatedly for the
        // SAME assertion, so a captured /return URL can be replayed
        // verbatim. consumeNonce records the response_nonce on first use
        // and returns false on any subsequent sighting (or if the nonce
        // is missing entirely).
        //
        // Deliberately runs LAST — after every other gate (is_valid, the
        // openid.signed coverage check, claimed_id format, and the param
        // cross-check) has passed. Burning the nonce on an obviously-
        // malformed assertion would punish the user for a Steam-side or
        // network glitch (their next genuine retry of the same URL would
        // be rejected as a replay). The synchronized consume + the gates
        // above mean only fully-valid first-use assertions ever record
        // a nonce; concurrent replays still race-lose on the synchronized
        // LinkedHashSet, so the TOCTOU window between is_valid and
        // session establishment in the controller is closed by this
        // atomic check-and-record.
        def nonce = paramFromQuery(rawQueryString, 'openid.response_nonce')
        if (!consumeNonce(nonce)) {
            log.warn("Steam OpenID assertion rejected: nonce missing or already used (replay)")
            return null
        }
        steamId64
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
        //
        // Was: a single try/catch on each fetch that swallowed EVERY
        // exception to a single warn line — indistinguishable whether the
        // user had no Steam profile (rare, permanent) or Steam was having
        // a transient blip (common, retryable). For first-ever logins the
        // consequence was severe: the user is permanently saved with the
        // placeholder displayName `Player_<6 digits>` until they happen to
        // sign in again. We now retry transient failures (IOException/
        // SocketTimeoutException — Steam down, DNS hiccup, container
        // network blip) once with a short back-off before giving up, and
        // log the give-up at WARN with the kind clearly named so alerts
        // can distinguish a true Steam outage from a single missing profile.
        def profile = fetchProfileWithRetry(steamId64)

        if (profile != null) {
            if (profile.displayName) user.displayName = sanitizeName(profile.displayName as String, steamId64)
            if (profile.avatarUrl)   user.avatarUrl   = profile.avatarUrl
            if (profile.profileUrl)  user.profileUrl  = profile.profileUrl
            user = steamUserRepository.save(user)
        } else if (isNew) {
            // Transient Steam blip on a brand-new user means they're saved
            // with the `Player_<digits>` placeholder. Surface this at WARN
            // so the operator can notice a pattern (a Steam outage during
            // a wave of new signups) rather than digging it out of
            // per-fetch debug noise. Existing users keep their last-known
            // displayName/avatar so the impact is invisible to them.
            log.warn("Steam profile fetch returned null on FIRST login for ${steamId64} — user saved with placeholder name")
        }

        // Bootstrap-admin promotion — if this steamId64 is listed in
        // admin.bootstrap-steam-ids it gets auto-promoted on every login.
        // Safe to call even when the service is null (no env var set).
        try { adminService?.promoteBootstrapAdmin(user) }
        catch (Exception e) { log.warn("Admin bootstrap check failed: ${e.message}") }

        user
    }

    /**
     * Try to fetch the user's Steam profile, retrying once on transient
     * failures. Returns the populated map (per {@link #fetchViaWebApi} /
     * {@link #fetchViaPublicXml}) on success, or null if both fetch paths
     * either return null (no profile found) or both attempts hit transient
     * errors. Never throws.
     *
     * Retry policy: a single retry with a 500 ms back-off. Cheap enough to
     * still complete inside the login HTTP round-trip but enough to ride
     * out a one-off Steam glitch. Permanent failures (404, malformed JSON,
     * etc.) short-circuit immediately — there's no point retrying a 404
     * twelve times.
     */
    protected Map fetchProfileWithRetry(String steamId64) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String kind = 'unknown'
            try {
                if (steamApiKey) {
                    kind = 'WebAPI'
                    def m = fetchViaWebApi(steamId64)
                    if (m != null) return m
                }
                kind = 'XML'
                def m = fetchViaPublicXml(steamId64)
                if (m != null) return m
                // Both paths returned null → no profile (e.g. private/deleted).
                // This is not a transient failure; don't retry.
                return null
            } catch (java.net.SocketTimeoutException te) {
                log.warn("Steam ${kind} lookup TIMED OUT on attempt ${attempt + 1} for ${steamId64}: ${te.message}")
            } catch (java.io.IOException ioe) {
                // IOException covers 5xx, connection refused, DNS failure,
                // and most other transport-level problems. Treat as transient.
                log.warn("Steam ${kind} lookup transport error on attempt ${attempt + 1} for ${steamId64}: ${ioe.message}")
            } catch (Exception e) {
                // Anything else (malformed JSON/XML, surprise exception) is
                // probably permanent — don't burn the second retry on it.
                log.warn("Steam ${kind} lookup failed (non-transient) for ${steamId64}: ${e.message}")
                return null
            }
            // Transient — back off a touch then retry.
            if (attempt == 0) {
                try { Thread.sleep(500L) } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null }
            }
        }
        return null
    }

    /** Call the official Steam Web API. Requires STEAM_API_KEY.
     *
     *  Routes 4xx (permanent: bad key, private profile) to a return-null
     *  short-circuit and lets 5xx / network errors bubble as IOException
     *  so {@link #fetchProfileWithRetry} can distinguish them and retry
     *  the transient ones. Was: `new URL(apiUrl).getText()` which threw
     *  IOException for every non-2xx — making "Steam is rate-limiting us
     *  RIGHT NOW" indistinguishable from "this Steam ID doesn't exist".
     */
    /** Overridable in tests so we can point fetchViaWebApi at a local
     *  HttpServer without rewriting the URL inline. Prod default is the
     *  real Steam Web API endpoint. */
    protected String webApiUrl(String steamId64) {
        "https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v0002/?key=${steamApiKey}&steamids=${steamId64}"
    }

    protected Map fetchViaWebApi(String steamId64) {
        def apiUrl = webApiUrl(steamId64)
        def conn = (HttpURLConnection) new URL(apiUrl).openConnection()
        conn.setRequestProperty('User-Agent', 'SkinBox/1.0')
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        int status = conn.responseCode
        if (status >= 500) {
            // Drain error stream so the connection can be reused, then
            // signal transient via IOException → the retry wrapper picks
            // it up.
            try { conn.errorStream?.getText('UTF-8') } catch (Exception ignore) {}
            throw new java.io.IOException("Steam Web API returned ${status}")
        }
        if (status < 200 || status >= 300) {
            // Permanent client error (bad key 401/403, malformed request
            // 400, etc.). Don't retry — log and treat as "no profile".
            // Drain the error stream first so the underlying socket can
            // return to the keep-alive pool — Steam private-profile 403s
            // fire on every login of a user with a non-public profile, so
            // a never-drained error stream slow-leaks file descriptors on
            // a long-lived container under sustained login pressure.
            try { conn.errorStream?.getText('UTF-8') } catch (Exception ignore) {}
            log.warn("Steam Web API returned ${status} for ${steamId64} — not retrying (permanent)")
            return null
        }
        def text = conn.inputStream.getText('UTF-8')
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
     *
     * Mirrors {@link #fetchViaWebApi}'s status handling: 5xx is transient
     * (IOException → retry), 4xx is permanent (log + null), 2xx is parsed.
     * Was: a bare `conn.inputStream.getText(...)` which threw IOException
     * for every non-2xx — collapsing "Steam down" and "profile private/
     * deleted" into the same swallowed warning, with no retry ever
     * triggered. That left first-ever-login users stuck on the
     * `Player_<digits>` placeholder forever after a single Steam blip.
     */
    /** Overridable in tests so we can point fetchViaPublicXml at a local
     *  HttpServer without rewriting the URL inline. Prod default is the
     *  real Steam Community profile XML endpoint. */
    protected String publicXmlUrl(String steamId64) {
        "https://steamcommunity.com/profiles/${steamId64}/?xml=1"
    }

    protected Map fetchViaPublicXml(String steamId64) {
        def xmlUrl = publicXmlUrl(steamId64)
        def conn = (HttpURLConnection) new URL(xmlUrl).openConnection()
        conn.setRequestProperty('User-Agent', 'SkinBox/1.0')
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        int status = conn.responseCode
        if (status >= 500) {
            try { conn.errorStream?.getText('UTF-8') } catch (Exception ignore) {}
            throw new java.io.IOException("Steam XML endpoint returned ${status}")
        }
        if (status < 200 || status >= 300) {
            // Drain the error stream so the socket can return to the
            // keep-alive pool — see fetchViaWebApi for the same rationale.
            try { conn.errorStream?.getText('UTF-8') } catch (Exception ignore) {}
            log.warn("Steam XML returned ${status} for ${steamId64} — not retrying (permanent)")
            return null
        }
        def text = conn.inputStream.getText('UTF-8')
        // Harden the XML parse against XXE (defense in depth). The body comes
        // from a fixed Steam host today so this isn't currently reachable, but
        // auth code is exactly where a not-reachable-yet entity-expansion / file-
        // read primitive must never exist if the fetch is ever MITM'd or the URL
        // builder changes. disallow-doctype-decl rejects any DOCTYPE outright;
        // Steam profile XML has none, so legitimate parsing is unaffected.
        def slurper = new XmlSlurper()
        slurper.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
        slurper.setFeature('http://xml.org/sax/features/external-general-entities', false)
        slurper.setFeature('http://xml.org/sax/features/external-parameter-entities', false)
        def profile = slurper.parseText(text)
        [
            displayName: profile.steamID?.text() ?: null,
            avatarUrl  : profile.avatarFull?.text() ?: profile.avatarMedium?.text() ?: null,
            profileUrl : "https://steamcommunity.com/profiles/${steamId64}"
        ]
    }
}
