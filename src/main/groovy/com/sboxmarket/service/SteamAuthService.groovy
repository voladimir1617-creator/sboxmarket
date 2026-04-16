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

        // Replace openid.mode=id_res with openid.mode=check_authentication,
        // without touching any other characters.
        def body = rawQueryString.replaceFirst(/openid\.mode=[^&]*/, 'openid.mode=check_authentication')

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

        if (!response?.contains('is_valid:true')) {
            log.warn("Steam OpenID reports invalid")
            return null
        }

        def m = claimedIdParam =~ STEAMID_REGEX
        if (!m.find()) {
            log.warn("Unexpected claimed_id format: $claimedIdParam")
            return null
        }
        m.group(1)
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
