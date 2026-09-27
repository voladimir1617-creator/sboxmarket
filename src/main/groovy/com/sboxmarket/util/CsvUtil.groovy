package com.sboxmarket.util

/**
 * Shared CSV-cell escaping helper (batch 978). Every `.csv` export in
 * the app (wallet transactions, admin withdrawals / tickets / audit /
 * fraud / disputes / trades / users / per-user listings + transactions,
 * buy-orders) used to roll its own inline `{ v -> if (v.contains(',') …}`
 * escape. None of them handled the OWASP CSV Injection case: a cell
 * whose first character is `= + - @ \t \r` is interpreted as a FORMULA
 * by Excel / LibreOffice / Google Sheets when the file is opened — so
 * an attacker who can write to any exported field (display name, deposit
 * note, trade message, support ticket subject, …) could plant a hostile
 * formula that runs the moment a user/admin/accountant opens their
 * exported CSV.
 *
 * `safeCell(raw)` returns a fully-quoted-and-escaped CSV cell string
 * that's safe to concatenate with a trailing comma or newline. The
 * single-quote prefix for formula-triggering first characters is
 * invisible once the cell renders in a spreadsheet (Excel eats it) so
 * benign payloads look identical to a plain `esc(v)` round-trip.
 */
final class CsvUtil {

    private CsvUtil() {}

    /** Escape a single CSV cell, defending against formula injection. */
    static String safeCell(Object raw) {
        if (raw == null) return ''
        String s = raw.toString()
        if (s.isEmpty()) return ''
        char c = s.charAt(0)
        // Excel treats these as formula triggers. Prefix with ' so the
        // spreadsheet renders the payload as literal text.
        if (c == ((char) '=') || c == ((char) '+') ||
                c == ((char) '-') || c == ((char) '@') ||
                c == ((char) '\t') || c == ((char) '\r')) {
            s = "'" + s
        }
        if (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')) {
            return '"' + s.replace('"', '""') + '"'
        }
        s
    }
}
