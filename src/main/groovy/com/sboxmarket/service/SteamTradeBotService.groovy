package com.sboxmarket.service

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Groovy HTTP client for the Steam bot sidecar (steam-bot/index.js).
 *
 * Mirrors the sidecar's endpoints:
 *   - {@link #sendOffer(String, List, String)}      -> POST /offers/send
 *   - {@link #getOfferStatus(String)}               -> GET  /offers/:id
 *   - {@link #acceptIncoming(String)}               -> POST /offers/incoming/:id/accept
 *   - {@link #fetchBotInventory()}                  -> GET  /inventory
 *
 * Configuration via @Value:
 *   STEAM_BOT_BASE_URL  -> base URL of the sidecar (e.g. http://127.0.0.1:4000).
 *                          When BLANK, this service is DISABLED: every call returns a
 *                          typed DISABLED result and performs no network I/O, so dev/test
 *                          environments need no running bot.
 *   BOT_API_TOKEN       -> shared bearer token sent as Authorization: Bearer <token>.
 *
 * Never throws on transport/protocol failures; always returns a typed
 * {@link SteamBotResult}. Timeouts are bounded.
 */
@Service
class SteamTradeBotService {

    private static final Logger log = LoggerFactory.getLogger(SteamTradeBotService)

    @Value('${steam.bot.base-url:${STEAM_BOT_BASE_URL:}}')
    String baseUrl

    @Value('${steam.bot.api-token:${BOT_API_TOKEN:}}')
    String apiToken

    @Value('${steam.bot.connect-timeout-ms:5000}')
    long connectTimeoutMs = 5000L

    @Value('${steam.bot.request-timeout-ms:15000}')
    long requestTimeoutMs = 15000L

    private final JsonSlurper jsonSlurper = new JsonSlurper()
    private HttpClient httpClient

    /** True only when a sidecar base URL is configured. */
    boolean isEnabled() {
        return baseUrl != null && !baseUrl.trim().isEmpty()
    }

    private HttpClient client() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                    .build()
        }
        return httpClient
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Send a trade offer giving {@code assetIds} (app 590830) to {@code partnerTradeUrl}. */
    SteamBotResult sendOffer(String partnerTradeUrl, List assetIds, String message) {
        if (!enabled) return SteamBotResult.disabled()
        if (partnerTradeUrl == null || partnerTradeUrl.trim().isEmpty()) {
            return SteamBotResult.error('BAD_REQUEST', 'partnerTradeUrl is required')
        }
        if (assetIds == null || assetIds.isEmpty()) {
            return SteamBotResult.error('BAD_REQUEST', 'assetIds must be non-empty')
        }
        Map body = [
                partnerTradeUrl: partnerTradeUrl,
                assetIds       : assetIds.collect { String.valueOf(it) },
                message        : message ?: ''
        ]
        return request('POST', '/offers/send', body)
    }

    /**
     * Send a trade offer that REQUESTS (receives, gives nothing) {@code assetIds}
     * (app 590830) FROM {@code partnerTradeUrl} — the deposit/escrow leg, where
     * the seller hands their item to the bot. Mirrors the sidecar's
     * {@code POST /offers/request} which uses steam-tradeoffer-manager's
     * {@code addTheirItem}. Returns the deposit offer id.
     */
    SteamBotResult requestItems(String partnerTradeUrl, List assetIds, String message) {
        if (!enabled) return SteamBotResult.disabled()
        if (partnerTradeUrl == null || partnerTradeUrl.trim().isEmpty()) {
            return SteamBotResult.error('BAD_REQUEST', 'partnerTradeUrl is required')
        }
        if (assetIds == null || assetIds.isEmpty()) {
            return SteamBotResult.error('BAD_REQUEST', 'assetIds must be non-empty')
        }
        Map body = [
                partnerTradeUrl: partnerTradeUrl,
                assetIds       : assetIds.collect { String.valueOf(it) },
                message        : message ?: ''
        ]
        return request('POST', '/offers/request', body)
    }

    /** Get the normalized status of an offer by id. */
    SteamBotResult getOfferStatus(String offerId) {
        if (!enabled) return SteamBotResult.disabled()
        if (offerId == null || offerId.trim().isEmpty()) {
            return SteamBotResult.error('BAD_REQUEST', 'offerId is required')
        }
        return request('GET', '/offers/' + enc(offerId), null)
    }

    /** Accept an incoming offer by id (and confirm it on the sidecar). */
    SteamBotResult acceptIncoming(String offerId) {
        if (!enabled) return SteamBotResult.disabled()
        if (offerId == null || offerId.trim().isEmpty()) {
            return SteamBotResult.error('BAD_REQUEST', 'offerId is required')
        }
        return request('POST', '/offers/incoming/' + enc(offerId) + '/accept', null)
    }

    /** Fetch the bot's own app-590830 inventory. */
    SteamBotResult fetchBotInventory() {
        if (!enabled) return SteamBotResult.disabled()
        return request('GET', '/inventory', null)
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private static String enc(String s) {
        return URLEncoder.encode(s, 'UTF-8')
    }

    /**
     * Perform a request and map it to a typed result. Decodes the JSON body and
     * uses the sidecar's {@code ok}/{@code error} convention. Never throws.
     */
    private SteamBotResult request(String method, String path, Map jsonBody) {
        String url = trimTrailingSlash(baseUrl) + path
        RawResponse resp
        try {
            resp = doRequest(method, url, jsonBody == null ? null : JsonOutput.toJson(jsonBody))
        } catch (java.net.http.HttpConnectTimeoutException e) {
            log.warn("Steam bot connect timeout: {} {}", method, path)
            return SteamBotResult.error('TIMEOUT', 'connect timeout: ' + e.message)
        } catch (java.net.http.HttpTimeoutException e) {
            log.warn("Steam bot request timeout: {} {}", method, path)
            return SteamBotResult.error('TIMEOUT', 'request timeout: ' + e.message)
        } catch (IOException e) {
            log.warn("Steam bot transport error on {} {}: {}", method, path, e.message)
            return SteamBotResult.error('TRANSPORT_ERROR', e.message)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            return SteamBotResult.error('TRANSPORT_ERROR', 'interrupted')
        } catch (Exception e) {
            log.warn("Steam bot unexpected error on {} {}: {}", method, path, e.message)
            return SteamBotResult.error('TRANSPORT_ERROR', String.valueOf(e.message))
        }

        Map parsed = null
        if (resp.body != null && !resp.body.trim().isEmpty()) {
            try {
                Object decoded = jsonSlurper.parseText(resp.body)
                if (decoded instanceof Map) parsed = (Map) decoded
            } catch (Exception e) {
                log.warn("Steam bot returned non-JSON body (status {}): {}", resp.status, e.message)
                return SteamBotResult.error('BAD_RESPONSE', 'non-JSON body (HTTP ' + resp.status + ')')
            }
        }

        boolean okBody = parsed != null && parsed.ok == true
        if (resp.status >= 200 && resp.status < 300 && okBody) {
            return SteamBotResult.success(parsed)
        }

        // Failure: prefer the sidecar's normalized error code/message.
        String code = parsed?.error != null ? String.valueOf(parsed.error) : ('HTTP_' + resp.status)
        String msg = parsed?.message != null ? String.valueOf(parsed.message) : ('HTTP ' + resp.status)
        return SteamBotResult.error(code, msg, parsed)
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith('/') ? s.substring(0, s.length() - 1) : s
    }

    /**
     * Raw HTTP transport. Extracted so tests can override it (and the bearer-token
     * handling) without a live server. Returns status + body. May throw IOException /
     * timeouts which {@link #request} maps to typed results.
     */
    protected RawResponse doRequest(String method, String url, String jsonBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(requestTimeoutMs))
                .header('Accept', 'application/json')
                .header('Authorization', 'Bearer ' + (apiToken ?: ''))

        if (jsonBody != null) {
            b = b.header('Content-Type', 'application/json')
            b = b.method(method, HttpRequest.BodyPublishers.ofString(jsonBody))
        } else if (method == 'GET') {
            b = b.GET()
        } else {
            b = b.method(method, HttpRequest.BodyPublishers.noBody())
        }

        HttpResponse<String> resp = client().send(b.build(), HttpResponse.BodyHandlers.ofString())
        return new RawResponse(status: resp.statusCode(), body: resp.body())
    }

    /** Minimal transport response holder. */
    static class RawResponse {
        int status
        String body
    }
}
