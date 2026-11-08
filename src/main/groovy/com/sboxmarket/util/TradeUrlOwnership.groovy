package com.sboxmarket.util

/**
 * Whether a stored Steam trade URL belongs to the user it is stored on.
 * ProfileController refuses someone else's URL at save time; the bot paths
 * re-check at send time so a URL stored before that rule, or by another
 * path, never sends a skin (or a deposit request) to a different account.
 */
class TradeUrlOwnership {

    static final long STEAM_ID64_BASE = 76561197960265728L

    private static final java.util.regex.Pattern PARTNER = ~/[?&]partner=(\d{1,10})(?:&|$)/

    /** False only when both ids parse and the URL's partner is not this
     *  user's account; unparseable input is left to the existing checks. */
    static boolean ownedBy(String tradeUrl, String steamId64) {
        if (!tradeUrl || !steamId64) return true
        def m = PARTNER.matcher(tradeUrl)
        if (!m.find()) return true
        try {
            return Long.parseLong(m.group(1)) == Long.parseLong(steamId64) - STEAM_ID64_BASE
        } catch (NumberFormatException ignored) {
            return true
        }
    }
}
