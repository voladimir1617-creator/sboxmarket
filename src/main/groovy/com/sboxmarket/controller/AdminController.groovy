package com.sboxmarket.controller

import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.SboxApiService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Admin-only endpoints. Every route calls `AdminService.requireAdmin` before
 * doing anything else — the `role` column on SteamUser is the single source
 * of truth. There is no separate admin password or second login.
 */
@RestController
@RequestMapping("/api/admin")
@Slf4j
class AdminController {

    @Autowired AdminService adminService
    @Autowired SteamUserRepository steamUserRepository
    @Autowired SboxApiService sboxApiService
    @Autowired com.sboxmarket.service.StripeService stripeService
    @Autowired com.sboxmarket.service.AuditService auditService
    @Autowired com.sboxmarket.service.TradeService tradeService
    @Autowired com.sboxmarket.service.AdminSimulatorService adminSimulatorService
    @Autowired com.sboxmarket.service.FraudAnalysisService fraudAnalysisService
    @Autowired com.sboxmarket.repository.ItemRepository itemRepository
    @Autowired(required = false) com.sboxmarket.service.ReviewService reviewService
    @Autowired(required = false) com.sboxmarket.repository.ApiKeyRepository apiKeyRepository

    private Long requireAdmin(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        adminService.requireAdmin(uid)
        uid
    }

    // ── Dashboard ───────────────────────────────────────────────────

    @GetMapping("/stats")
    ResponseEntity<Map> stats(HttpServletRequest req) {
        requireAdmin(req)
        ResponseEntity.ok(adminService.dashboardStats())
    }

    /** System-health snapshot — JVM memory, thread count, Hikari pool
     *  state, DB connection health, and a sprinkling of last-run
     *  metadata for the scheduled jobs. Useful for ops to eyeball
     *  "is everything humming" without jumping to a separate
     *  observability stack. Admin-gated because the counters reveal
     *  internal container state. */
    @GetMapping("/health")
    ResponseEntity<Map> systemHealth(HttpServletRequest req) {
        requireAdmin(req)
        ResponseEntity.ok(adminService.systemHealth())
    }

    /** Lightweight probe the frontend uses to decide whether to show the
     *  Admin menu entry — returns {admin: true} or {admin: false}. */
    @GetMapping("/check")
    ResponseEntity<Map> check(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) return ResponseEntity.ok([admin: false])
        def user = steamUserRepository.findById(uid).orElse(null)
        ResponseEntity.ok([admin: user?.role == 'ADMIN'])
    }

    // Admin bootstrap is ONLY via the server-side env var
    // `ADMIN_BOOTSTRAP_STEAM_IDS` (comma-separated Steam64s) or by an
    // existing admin promoting another user through the Users tab.
    // There is deliberately NO self-service "claim" endpoint — exposing
    // one (even gated by "only if no admin exists") creates a first-user
    // race on every fresh deploy.

    // ── Withdrawals ─────────────────────────────────────────────────

    @GetMapping("/withdrawals")
    ResponseEntity<List<Map>> withdrawals(
            @RequestParam(required = false, defaultValue = "PENDING") String status,
            HttpServletRequest req) {
        requireAdmin(req)
        ResponseEntity.ok(adminService.listWithdrawals(status))
    }

    /** Admin withdrawals CSV export (batch 577). Mirrors the JSON
     *  queue's status filter so the download matches what staff are
     *  looking at. Hard-capped at 5000 rows like the other admin CSVs
     *  (audit / trades / tickets). Columns include the fraud-signal
     *  fields staff already see as chips (ownerBanned / walletFrozen /
     *  activeDisputes / lifetimeDisputes) so Excel-side ops can sort
     *  on them. */
    @GetMapping(value = "/withdrawals.csv", produces = "text/csv")
    ResponseEntity<String> withdrawalsCsv(
            @RequestParam(required = false, defaultValue = "PENDING") String status,
            HttpServletRequest req) {
        requireAdmin(req)
        def rows = (adminService.listWithdrawals(status) ?: []).take(5000)
        def df = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        df.timeZone = java.util.TimeZone.getTimeZone("UTC")
        // Batch 978 — shared csv-safe escape. Defends against OWASP
        // CSV injection (formula-trigger first chars). The admin export
        // is especially exposed because destination strings are user-
        // writable ("Stripe Connect acct_..." or a free-text payout
        // note) and an admin opening the CSV would execute any formula.
        def esc = com.sboxmarket.util.CsvUtil.&safeCell
        def sb = new StringBuilder()
        sb.append('id,status,amount,currency,destination,description,walletBalance,' +
                  'ownerUserId,ownerDisplayName,ownerEmailVerified,ownerBanned,walletFrozen,' +
                  'activeDisputes,lifetimeDisputes,createdAt,updatedAt\n')
        rows.each { r ->
            sb.append(r.id ?: '').append(',')
              .append(esc(r.status)).append(',')
              .append(r.amount ?: '').append(',')
              .append(esc(r.currency)).append(',')
              .append(esc(r.destination)).append(',')
              .append(esc(r.description)).append(',')
              .append(r.walletBalance ?: '').append(',')
              .append(r.ownerUserId ?: '').append(',')
              .append(esc(r.ownerDisplayName)).append(',')
              .append(r.ownerEmailVerified ? 'true' : 'false').append(',')
              .append(r.ownerBanned ? 'true' : 'false').append(',')
              .append(r.walletFrozen ? 'true' : 'false').append(',')
              .append(r.activeDisputes ?: 0).append(',')
              .append(r.lifetimeDisputes ?: 0).append(',')
              .append(df.format(new Date(r.createdAt ?: 0))).append(',')
              .append(df.format(new Date(r.updatedAt ?: 0))).append('\n')
        }
        ResponseEntity.ok()
            .header('Content-Disposition',
                "attachment; filename=\"skinbox-withdrawals-${status}-${df.format(new Date()).replace(':','-')}.csv\"")
            .header('Content-Type', 'text/csv; charset=utf-8')
            .header('Cache-Control', 'no-store')
            .body(sb.toString())
    }

    @PostMapping("/withdrawals/{id}/approve")
    ResponseEntity<Map> approveWithdrawal(@PathVariable Long id,
                                          @RequestBody(required = false) Map body,
                                          HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.approveWithdrawal(uid, id, body?.payoutRef as String))
    }

    @PostMapping("/withdrawals/{id}/reject")
    ResponseEntity<Map> rejectWithdrawal(@PathVariable Long id,
                                         @RequestBody(required = false) Map body,
                                         HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.rejectWithdrawal(uid, id, body?.reason as String))
    }

    // ── Catalogue items (staff override) ────────────────────────────

    /** Override a catalogue item's display fields without waiting for the
     *  next SCMM sync. Admins use this to fix a stale image, correct a
     *  wrong rarity classification, or manually set a price point for an
     *  item the upstream feed doesn't know about. Validates upper bounds
     *  and sanitises string inputs. */
    @PutMapping("/items/{id}")
    @org.springframework.transaction.annotation.Transactional
    ResponseEntity<Map> updateItem(@PathVariable Long id,
                                   @RequestBody Map body,
                                   HttpServletRequest req) {
        def adminUid = requireAdmin(req)
        def item = itemRepository.findById(id)
            .orElseThrow { new com.sboxmarket.exception.NotFoundException("Item", id) }
        if (body.containsKey('steamPrice')) {
            def raw = body.steamPrice
            if (raw == null || raw.toString().isEmpty()) {
                item.steamPrice = null
            } else {
                BigDecimal p
                try { p = new BigDecimal(raw.toString()) }
                catch (NumberFormatException ignored) {
                    throw new com.sboxmarket.exception.BadRequestException("INVALID_PRICE", "steamPrice must be a number")
                }
                if (p < BigDecimal.ZERO) throw new com.sboxmarket.exception.BadRequestException("INVALID_PRICE", "steamPrice must be ≥ 0")
                if (p > new BigDecimal("100000")) throw new com.sboxmarket.exception.BadRequestException("PRICE_TOO_HIGH", 'steamPrice must not exceed $100,000')
                item.steamPrice = p
            }
        }
        if (body.containsKey('rarity')) {
            def r = (body.rarity as String ?: '').trim()
            if (r.length() > 40) throw new com.sboxmarket.exception.BadRequestException("INVALID_RARITY", "rarity too long")
            item.rarity = r.isEmpty() ? 'Standard' : r
        }
        if (body.containsKey('imageUrl')) {
            def u = (body.imageUrl as String ?: '').trim()
            if (u.length() > 500) throw new com.sboxmarket.exception.BadRequestException("INVALID_URL", "imageUrl too long")
            if (u && !u.startsWith('https://')) {
                throw new com.sboxmarket.exception.BadRequestException("INVALID_URL", "imageUrl must be an https:// URL")
            }
            item.imageUrl = u.isEmpty() ? null : u
        }
        if (body.containsKey('accentColor')) {
            def c = (body.accentColor as String ?: '').trim()
            if (c && !(c ==~ /#[0-9A-Fa-f]{3,8}/)) {
                throw new com.sboxmarket.exception.BadRequestException("INVALID_COLOR", "accentColor must be a #hex string")
            }
            item.accentColor = c.isEmpty() ? null : c
        }
        itemRepository.save(item)
        auditService?.log(com.sboxmarket.service.AuditService.ITEM_EDITED, adminUid, null, item.id,
            "Admin edited ${item.name}: " + body.keySet().join(','))
        ResponseEntity.ok([
            id:          item.id,
            name:        item.name,
            rarity:      item.rarity,
            steamPrice:  item.steamPrice,
            imageUrl:    item.imageUrl,
            accentColor: item.accentColor
        ])
    }

    // ── Users ───────────────────────────────────────────────────────

    @GetMapping("/users")
    ResponseEntity<List<SteamUser>> users(@RequestParam(required = false) String search,
                                          @RequestParam(required = false) String role,
                                          @RequestParam(required = false) Boolean banned,
                                          HttpServletRequest req) {
        requireAdmin(req)
        ResponseEntity.ok(adminService.listUsers(search, role, banned))
    }

    /** Users CSV export — used by ops for reporting, tax auditing, and
     *  ad-hoc data pulls. Identical filter surface as /users so the CSV
     *  represents what the admin is currently looking at. No PII beyond
     *  what the admin panel already renders. */
    @GetMapping(value = "/users.csv", produces = "text/csv")
    ResponseEntity<String> usersCsv(@RequestParam(required = false) String search,
                                    @RequestParam(required = false) String role,
                                    @RequestParam(required = false) Boolean banned,
                                    HttpServletRequest req) {
        requireAdmin(req)
        def rows = adminService.listUsers(search, role, banned)
        def df = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        df.timeZone = java.util.TimeZone.getTimeZone("UTC")
        def esc = com.sboxmarket.util.CsvUtil.&safeCell   // batch 978
        def sb = new StringBuilder()
        sb.append('id,steamId64,displayName,role,banned,email,emailVerified,createdAt,lastLoginAt\n')
        rows.each { u ->
            sb.append(u.id ?: '').append(',')
              .append(esc(u.steamId64 ?: '')).append(',')
              .append(esc(u.displayName ?: '')).append(',')
              .append(esc(u.role ?: 'USER')).append(',')
              .append((u.banned ? 'true' : 'false')).append(',')
              .append(esc(u.email ?: '')).append(',')
              .append((u.emailVerified ? 'true' : 'false')).append(',')
              .append(df.format(new Date(u.createdAt ?: 0))).append(',')
              .append(df.format(new Date(u.lastLoginAt ?: 0))).append('\n')
        }
        ResponseEntity.ok()
            .header('Content-Disposition',
                "attachment; filename=\"skinbox-users-${df.format(new Date()).replace(':','-')}.csv\"")
            .header('Content-Type', 'text/csv; charset=utf-8')
            .header('Cache-Control', 'no-store')
            .body(sb.toString())
    }

    /**
     * Consolidated per-user staff summary (batch 549). One round-trip for
     * the detail drawer: wallet balance + freeze state, active/lifetime
     * dispute counts, pending withdraw total, open trade count, 2FA + email
     * verification state. Saves staff from bouncing across tabs to triage.
     */
    @GetMapping("/users/{id}/summary")
    ResponseEntity<Map> userSummary(@PathVariable Long id, HttpServletRequest req) {
        requireAdmin(req)
        ResponseEntity.ok(adminService.userSummary(id))
    }

    @PostMapping("/users/{id}/ban")
    ResponseEntity<SteamUser> ban(@PathVariable Long id,
                                  @RequestBody(required = false) Map body,
                                  HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.banUser(uid, id, body?.reason as String))
    }

    @PostMapping("/users/{id}/unban")
    ResponseEntity<SteamUser> unban(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.unbanUser(uid, id))
    }

    /**
     * Revoke every live session the user holds. Staff use this on
     * compromise reports or when an obviously-not-the-owner sign-in
     * shows up in the sign-in history audit. Does NOT ban the account —
     * the user can just sign in again. Audit-logged as
     * {@code USER_FORCE_LOGOUT} so the forensic record distinguishes it
     * from a ban.
     */
    @PostMapping("/users/{id}/force-logout")
    ResponseEntity<Map> forceLogout(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireAdmin(req)
        def user = adminService.forceLogout(uid, id)
        ResponseEntity.ok([id: user.id, sessionEpoch: user.sessionEpoch])
    }

    /**
     * Fraud-triage helper (batch 700). Staff pastes a prefix fragment
     * from a suspicious log line (e.g. `sbx_live_abc12`) and gets
     * back every matching key with owner id + label + scope +
     * revoked-flag + last-used timestamp. Lets an ops person pivot
     * from "this log line has a weird pattern" to "this user's key
     * needs revoking" in one lookup.
     *
     * Clamps `prefix` to [3, 64] chars so a one-char query doesn't
     * dump the whole key table. Auth gated on admin role.
     */
    @GetMapping("/api-keys/lookup")
    ResponseEntity<List<Map>> lookupApiKey(@RequestParam String prefix, HttpServletRequest req) {
        requireAdmin(req)
        if (prefix == null) return ResponseEntity.ok([])
        prefix = prefix.trim().replace('\u0000', '')
        if (prefix.length() < 3) return ResponseEntity.ok([])   // too short — noise guard
        if (prefix.length() > 64) prefix = prefix.substring(0, 64)
        if (apiKeyRepository == null) return ResponseEntity.ok([])
        def rows = apiKeyRepository.findByPublicPrefixStartsWith(prefix)
        def out = rows.collect { k ->
            [
                id:           k.id,
                userId:       k.userId,
                publicPrefix: k.publicPrefix,
                label:        k.label,
                scope:        k.scope,
                revoked:      k.revoked,
                createdAt:    k.createdAt,
                lastUsedAt:   k.lastUsedAt
            ]
        }
        ResponseEntity.ok(out)
    }

    @PostMapping("/users/{id}/grant-admin")
    ResponseEntity<SteamUser> grant(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.grantAdmin(uid, id))
    }

    @PostMapping("/users/{id}/revoke-admin")
    ResponseEntity<SteamUser> revoke(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.revokeAdmin(uid, id))
    }

    @PostMapping("/users/{id}/grant-csr")
    ResponseEntity<SteamUser> grantCsr(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.grantCsr(uid, id))
    }

    @PostMapping("/users/{id}/revoke-csr")
    ResponseEntity<SteamUser> revokeCsr(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.revokeCsr(uid, id))
    }

    /** Admin-only 2FA reset for a locked-out user. */
    @PostMapping("/users/{id}/reset-2fa")
    ResponseEntity<Map> reset2fa(@PathVariable Long id,
                                 @RequestBody(required = false) Map body,
                                 HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.reset2faFor(uid, id, body?.note as String))
    }

    /** Users who have requested account deletion (GDPR). */
    @GetMapping("/users/deletion-requests")
    ResponseEntity<List<Map>> deletionRequests(HttpServletRequest req) {
        requireAdmin(req)
        ResponseEntity.ok(adminService.listDeletionRequests())
    }

    /** Finalise a user's deletion request — PII scrub + ban + request cleared. */
    @PostMapping("/users/{id}/finalize-deletion")
    ResponseEntity<Map> finalizeDeletion(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.finalizeDeletion(uid, id))
    }

    /** Fire a one-off SMTP test email to validate config end-to-end. */
    @PostMapping("/test-email")
    ResponseEntity<Map> sendTestEmail(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.sendTestEmail(uid,
            body?.to as String, body?.subject as String, body?.body as String))
    }

    /** Send a one-off ADMIN_MESSAGE notification to a specific user
     *  (batch 580). Body: {title, body?, path?}. Same validation as
     *  broadcast; rejects banned targets server-side. */
    @PostMapping("/users/{id}/message")
    ResponseEntity<Map> messageUser(@PathVariable Long id,
                                    @RequestBody Map body,
                                    HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.sendDirectMessage(uid, id,
            body?.title as String, body?.body as String, body?.path as String))
    }

    /** Broadcast an ADMIN_BROADCAST notification to every non-banned,
     *  non-deletion-pending user (batch 566). Body: {title, body?, path?}.
     *  title is required and capped at 120 chars; body at 500; path must
     *  start with "/" if supplied. Hard-capped at 10k users server-side. */
    @PostMapping("/broadcast")
    ResponseEntity<Map> broadcast(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.broadcastNotification(uid,
            body?.title as String, body?.body as String, body?.path as String))
    }

    /** Redact a trade message — removes the row. Used for abuse takedowns. */
    @DeleteMapping("/trade-messages/{id}")
    ResponseEntity<Map> deleteTradeMessage(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireAdmin(req)
        // Delegate to TradeService — keeps the auth + audit + delete in one place.
        tradeService.deleteMessage(uid, id)
        ResponseEntity.ok([deleted: id])
    }

    /** Read staff-only internal notes on a user. */
    @GetMapping("/users/{id}/notes")
    ResponseEntity<Map> readNotes(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.readAdminNotes(uid, id))
    }

    /** Write/update staff-only internal notes on a user. */
    @PutMapping("/users/{id}/notes")
    ResponseEntity<Map> writeNotes(@PathVariable Long id,
                                   @RequestBody Map body,
                                   HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.writeAdminNotes(uid, id, body?.notes as String))
    }

    /** Admin wallet-transaction drill-down for a target user (batch 568).
     *  100 most-recent rows. Staff uses this from the user detail drawer
     *  for fraud triage — who funded the wallet, where did the money go. */
    @GetMapping("/users/{id}/transactions")
    ResponseEntity<List<Map>> userTransactions(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.listTransactionsFor(uid, id))
    }

    /** Admin CSV drill-down of a single user's full listing history
     *  (batch 589). Used by fraud triage — "what did this account post
     *  and when?" Capped at 5000 rows like every other admin CSV. */
    @Autowired(required = false) com.sboxmarket.repository.ListingRepository listingRepositoryForCsv
    @GetMapping(value = "/users/{id}/listings.csv", produces = "text/csv")
    ResponseEntity<String> userListingsCsv(@PathVariable Long id, HttpServletRequest req) {
        requireAdmin(req)
        if (listingRepositoryForCsv == null) return ResponseEntity.status(503).body('')
        def rows = listingRepositoryForCsv.findAllBySeller(id,
            org.springframework.data.domain.PageRequest.of(0, 5000)) ?: []
        def df = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        df.timeZone = java.util.TimeZone.getTimeZone("UTC")
        def esc = com.sboxmarket.util.CsvUtil.&safeCell   // batch 978
        def sb = new StringBuilder()
        sb.append('id,status,listingType,itemId,itemName,price,buyNowPrice,currentBid,buyerUserId,hidden,listedAt,soldAt,expiresAt\n')
        rows.each { l ->
            sb.append(l.id ?: '').append(',')
              .append(esc(l.status)).append(',')
              .append(esc(l.listingType)).append(',')
              .append(l.item?.id ?: '').append(',')
              .append(esc(l.item?.name)).append(',')
              .append(l.price ?: '').append(',')
              .append(l.buyNowPrice ?: '').append(',')
              .append(l.currentBid ?: '').append(',')
              .append(l.buyerUserId ?: '').append(',')
              .append(l.hidden ? 'true' : 'false').append(',')
              .append(l.listedAt ? df.format(new Date(l.listedAt)) : '').append(',')
              .append(l.soldAt ? df.format(new Date(l.soldAt)) : '').append(',')
              .append(l.expiresAt ? df.format(new Date(l.expiresAt)) : '').append('\n')
        }
        ResponseEntity.ok()
            .header('Content-Disposition',
                "attachment; filename=\"skinbox-user-${id}-listings-${df.format(new Date()).replace(':','-')}.csv\"")
            .header('Content-Type', 'text/csv; charset=utf-8')
            .header('Cache-Control', 'no-store')
            .body(sb.toString())
    }

    /** Admin CSV drill-down of a single user's wallet transactions
     *  (batch 586). Same 100-row projection as the JSON endpoint but
     *  packaged for Excel. Used for post-hoc chargeback reconciliation
     *  against the Stripe dashboard. */
    @GetMapping(value = "/users/{id}/transactions.csv", produces = "text/csv")
    ResponseEntity<String> userTransactionsCsv(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireAdmin(req)
        def rows = adminService.listTransactionsFor(uid, id) ?: []
        def df = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        df.timeZone = java.util.TimeZone.getTimeZone("UTC")
        def esc = com.sboxmarket.util.CsvUtil.&safeCell   // batch 978
        def sb = new StringBuilder()
        sb.append('id,type,status,amount,currency,description,stripeReference,createdAt,updatedAt\n')
        rows.each { r ->
            sb.append(r.id ?: '').append(',')
              .append(esc(r.type)).append(',')
              .append(esc(r.status)).append(',')
              .append(r.amount ?: '').append(',')
              .append(esc(r.currency)).append(',')
              .append(esc(r.description)).append(',')
              .append(esc(r.stripeReference)).append(',')
              .append(df.format(new Date(r.createdAt ?: 0))).append(',')
              .append(df.format(new Date(r.updatedAt ?: 0))).append('\n')
        }
        ResponseEntity.ok()
            .header('Content-Disposition',
                "attachment; filename=\"skinbox-user-${id}-transactions-${df.format(new Date()).replace(':','-')}.csv\"")
            .header('Content-Type', 'text/csv; charset=utf-8')
            .header('Cache-Control', 'no-store')
            .body(sb.toString())
    }

    @PostMapping("/users/{id}/credit")
    ResponseEntity<Map> credit(@PathVariable Long id,
                               @RequestBody Map body,
                               HttpServletRequest req) {
        def uid = requireAdmin(req)
        if (body?.amount == null) {
            throw new com.sboxmarket.exception.BadRequestException("INVALID_AMOUNT", "amount is required")
        }
        BigDecimal amt
        try { amt = new BigDecimal(body.amount.toString()) }
        catch (NumberFormatException ignored) {
            throw new com.sboxmarket.exception.BadRequestException("INVALID_AMOUNT", "amount must be a valid number")
        }
        ResponseEntity.ok(adminService.creditWallet(uid, id, amt, body.note as String))
    }

    /** Freeze a user's wallet — refuses money-in/out without nuking the
     *  account (batch 509). Reason is required for the audit trail. */
    @PostMapping("/users/{id}/wallet/freeze")
    ResponseEntity<Map> freeze(@PathVariable Long id,
                                @RequestBody Map body,
                                HttpServletRequest req) {
        def uid = requireAdmin(req)
        def reason = (body?.reason as String)?.trim()
        if (!reason) {
            throw new com.sboxmarket.exception.BadRequestException("REASON_REQUIRED", "Freeze reason is required")
        }
        ResponseEntity.ok(adminService.freezeWallet(uid, id, reason))
    }

    /** Lift a wallet freeze. Idempotent — no-op if already unfrozen. */
    @PostMapping("/users/{id}/wallet/unfreeze")
    ResponseEntity<Map> unfreeze(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.unfreezeWallet(uid, id))
    }

    // ── Listings moderation ─────────────────────────────────────────

    @PostMapping("/listings/{id}/remove")
    ResponseEntity<Map> removeListing(@PathVariable Long id,
                                      @RequestBody(required = false) Map body,
                                      HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.forceCancelListing(uid, id, body?.reason as String))
    }

    /** User-reported listings queue, sorted by report count DESC. */
    @GetMapping("/listings/reported")
    ResponseEntity<List<Map>> reportedListings(@RequestParam(required = false) Integer limit,
                                               HttpServletRequest req) {
        requireAdmin(req)
        ResponseEntity.ok(adminService.findReportedListings(limit ?: 50))
    }

    /** Dismiss reports without cancelling — admin reviewed and found no issue. */
    @PostMapping("/listings/{id}/dismiss-reports")
    ResponseEntity<Map> dismissReports(@PathVariable Long id,
                                       @RequestBody(required = false) Map body,
                                       HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.dismissListingReports(uid, id, body?.note as String))
    }

    // ── Support ─────────────────────────────────────────────────────

    @GetMapping("/tickets")
    ResponseEntity<List<Map>> tickets(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) String search,
                                      HttpServletRequest req) {
        requireAdmin(req)
        ResponseEntity.ok(adminService.listAllTickets(status, search))
    }

    /** Admin support-tickets CSV export (batch 559). Same `status` filter as
     *  the JSON endpoint. Capped at 5000 rows; a long-running site should
     *  narrow the status filter first for a full-history download. */
    @GetMapping(value = "/tickets.csv", produces = "text/csv")
    ResponseEntity<String> ticketsCsv(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) String search,
                                      HttpServletRequest req) {
        requireAdmin(req)
        def rows = (adminService.listAllTickets(status, search) ?: []).take(5000)
        def df = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        df.timeZone = java.util.TimeZone.getTimeZone("UTC")
        def esc = com.sboxmarket.util.CsvUtil.&safeCell   // batch 978
        def sb = new StringBuilder()
        sb.append('id,status,category,userId,username,subject,userBanned,walletFrozen,lifetimeDisputes,createdAt,updatedAt\n')
        rows.each { r ->
            sb.append(r.id ?: '').append(',')
              .append(esc(r.status)).append(',')
              .append(esc(r.category)).append(',')
              .append(r.userId ?: '').append(',')
              .append(esc(r.username)).append(',')
              .append(esc(r.subject)).append(',')
              .append(r.userBanned ? 'true' : 'false').append(',')
              .append(r.walletFrozen ? 'true' : 'false').append(',')
              .append(r.lifetimeDisputes ?: 0).append(',')
              .append(df.format(new Date(r.createdAt ?: 0))).append(',')
              .append(df.format(new Date(r.updatedAt ?: 0))).append('\n')
        }
        def filter = status ?: 'all'
        ResponseEntity.ok()
            .header('Content-Disposition',
                "attachment; filename=\"skinbox-tickets-${filter}-${df.format(new Date()).replace(':','-')}.csv\"")
            .header('Content-Type', 'text/csv; charset=utf-8')
            .header('Cache-Control', 'no-store')
            .body(sb.toString())
    }

    @GetMapping("/tickets/{id}")
    ResponseEntity<Map> ticket(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.getTicket(uid, id))
    }

    @PostMapping("/tickets/{id}/reply")
    ResponseEntity<Map> reply(@PathVariable Long id, @RequestBody Map body, HttpServletRequest req) {
        def uid = requireAdmin(req)
        def msg = adminService.staffReply(uid, id, body.body as String)
        ResponseEntity.ok([id: msg.id, body: msg.body])
    }

    @PostMapping("/tickets/{id}/close")
    ResponseEntity<Map> close(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireAdmin(req)
        def t = adminService.closeTicket(uid, id)
        ResponseEntity.ok([id: t.id, status: t.status])
    }

    // ── Audit log ───────────────────────────────────────────────────

    @GetMapping("/audit")
    ResponseEntity<List> audit(@RequestParam(required = false) String event,
                               @RequestParam(required = false) Long actor,
                               @RequestParam(required = false) Long subject,
                               @RequestParam(required = false) Long since,
                               HttpServletRequest req) {
        requireAdmin(req)
        // `since` is wall-clock millis; null/0 means no date floor.
        // Added batch 556 so staff can narrow to "last 24h / 7d" without
        // scrolling through weeks of rows. Defensive: negative or
        // future-dated values are coerced to null so a typo doesn't
        // produce an empty screen with no error.
        Long sinceMs = (since != null && since > 0L && since <= System.currentTimeMillis()) ? since : null
        def rows
        if (event)        rows = auditService.byEvent(event, sinceMs)
        else if (actor)   rows = auditService.byActor(actor, sinceMs)
        else if (subject) rows = auditService.bySubject(subject, sinceMs)
        else              rows = auditService.recent(sinceMs)
        ResponseEntity.ok(rows)
    }

    /** Audit log CSV export — honors the same event/actor/subject filters
     *  as the JSON endpoint. Dumps up to the last 500 rows depending on
     *  what the underlying AuditService returns. Every field is
     *  quote-escaped so commas / quotes / newlines inside the
     *  `description` column don't corrupt the file. */
    @GetMapping(value = "/audit.csv", produces = "text/csv")
    ResponseEntity<String> auditCsv(@RequestParam(required = false) String event,
                                    @RequestParam(required = false) Long actor,
                                    @RequestParam(required = false) Long subject,
                                    @RequestParam(required = false) Long since,
                                    HttpServletRequest req) {
        requireAdmin(req)
        Long sinceMs = (since != null && since > 0L && since <= System.currentTimeMillis()) ? since : null
        def rows
        if (event)        rows = auditService.byEvent(event, sinceMs)
        else if (actor)   rows = auditService.byActor(actor, sinceMs)
        else if (subject) rows = auditService.bySubject(subject, sinceMs)
        else              rows = auditService.recent(sinceMs)
        // Cap at 5000 rows (batch 493) — matches the trades CSV cap.
        // A long-running site's audit table is unbounded; without this
        // cap the CSV response could OOM the JVM on a download click.
        // Admin UI shows a hint that older rows need a narrower filter
        // + re-download.
        rows = (rows ?: []).take(5000)

        def df = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        df.timeZone = java.util.TimeZone.getTimeZone("UTC")
        def esc = com.sboxmarket.util.CsvUtil.&safeCell   // batch 978
        def sb = new StringBuilder()
        // Column names match the AuditLog entity field names (batch 493).
        // The old CSV wrote columns like `description`, `ip`, `userAgent`
        // that pulled null from Groovy's property access because the
        // entity fields are named `summary`, `ipAddress`, `userAgent` —
        // 3/9 columns shipped blank on every download. Now the header
        // and body track the entity shape.
        sb.append('id,createdAt,eventType,actorUserId,actorName,subjectUserId,subjectName,resourceId,summary,ipAddress,userAgent\n')
        rows.each { r ->
            sb.append(r.id ?: '').append(',')
              .append(df.format(new Date(r.createdAt ?: 0))).append(',')
              .append(esc(r.eventType)).append(',')
              .append(r.actorUserId ?: '').append(',')
              .append(esc(r.actorName)).append(',')
              .append(r.subjectUserId ?: '').append(',')
              .append(esc(r.subjectName)).append(',')
              .append(r.resourceId ?: '').append(',')
              .append(esc(r.summary)).append(',')
              .append(esc(r.ipAddress)).append(',')
              .append(esc(r.userAgent)).append('\n')
        }
        ResponseEntity.ok()
            .header('Content-Disposition',
                "attachment; filename=\"skinbox-audit-${df.format(new Date()).replace(':','-')}.csv\"")
            .header('Content-Type', 'text/csv; charset=utf-8')
            .header('Cache-Control', 'no-store')
            .body(sb.toString())
    }

    // ── Fraud signals ───────────────────────────────────────────────
    //
    // Read-only rollup of AuditLog rows into triage signals — multiple
    // IPs per user, shared IPs across accounts, rapid withdraw-after-
    // deposit, high-velocity purchases. No new schema; just a query over
    // the last 24h of audit history. Admin UI polls this to surface
    // actionable patterns without requiring a data-warehouse layer.

    @GetMapping("/fraud")
    ResponseEntity<List<Map>> fraud(HttpServletRequest req) {
        requireAdmin(req)
        ResponseEntity.ok(fraudAnalysisService.computeSignals())
    }

    /** Admin fraud-signal CSV export (batch 588). Rollup is computed
     *  from the last 24h of audit rows on every call, so download
     *  timing matters — two downloads seconds apart can differ as
     *  the audit log evolves. Output shape flattens the signal Map so
     *  ops can sort / filter in Excel. */
    @GetMapping(value = "/fraud.csv", produces = "text/csv")
    ResponseEntity<String> fraudCsv(HttpServletRequest req) {
        requireAdmin(req)
        def rows = fraudAnalysisService.computeSignals() ?: []
        def df = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        df.timeZone = java.util.TimeZone.getTimeZone("UTC")
        def esc = com.sboxmarket.util.CsvUtil.&safeCell   // batch 978
        def sb = new StringBuilder()
        sb.append('type,severity,userId,userName,ip,count,summary,latestAt\n')
        rows.each { r ->
            sb.append(esc(r.type)).append(',')
              .append(esc(r.severity)).append(',')
              .append(r.userId ?: '').append(',')
              .append(esc(r.userName)).append(',')
              .append(esc(r.ip)).append(',')
              .append(r.count ?: 0).append(',')
              .append(esc(r.summary)).append(',')
              .append(r.createdAt ? df.format(new Date(r.createdAt)) : '').append('\n')
        }
        ResponseEntity.ok()
            .header('Content-Disposition',
                "attachment; filename=\"skinbox-fraud-signals-${df.format(new Date()).replace(':','-')}.csv\"")
            .header('Content-Type', 'text/csv; charset=utf-8')
            .header('Cache-Control', 'no-store')
            .body(sb.toString())
    }

    /**
     * All deposit transactions currently flagged DISPUTED via a Stripe
     * chargeback webhook (batch 461). Each row carries the wallet id,
     * tx amount, the user info for quick lookup, and the timestamps so
     * staff can sort by recency. Hard-capped at 200 — past that the
     * platform has bigger problems than dashboard pagination. Used by
     * the admin chargeback queue UI.
     */
    @GetMapping("/disputes")
    ResponseEntity<List<Map>> disputes(HttpServletRequest req) {
        requireAdmin(req)
        ResponseEntity.ok(adminService.listDisputedDeposits())
    }

    /** Admin disputes CSV export (batch 578). Mirrors the JSON
     *  dispute queue. Columns include the fraud-signal chips staff
     *  already see (userBanned / walletFrozen / lifetimeDisputes) so
     *  Excel-side ops can sort on them. 5000-row cap like the other
     *  admin CSVs. */
    @GetMapping(value = "/disputes.csv", produces = "text/csv")
    ResponseEntity<String> disputesCsv(HttpServletRequest req) {
        requireAdmin(req)
        def rows = (adminService.listDisputedDeposits() ?: []).take(5000)
        def df = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        df.timeZone = java.util.TimeZone.getTimeZone("UTC")
        def esc = com.sboxmarket.util.CsvUtil.&safeCell   // batch 978
        def sb = new StringBuilder()
        sb.append('id,amount,currency,description,walletBalance,userId,userDisplayName,' +
                  'userEmailVerified,userBanned,walletFrozen,lifetimeDisputes,createdAt,updatedAt\n')
        rows.each { r ->
            sb.append(r.id ?: '').append(',')
              .append(r.amount ?: '').append(',')
              .append(esc(r.currency)).append(',')
              .append(esc(r.description)).append(',')
              .append(r.walletBalance ?: '').append(',')
              .append(r.userId ?: '').append(',')
              .append(esc(r.userDisplayName)).append(',')
              .append(r.userEmailVerified ? 'true' : 'false').append(',')
              .append(r.userBanned ? 'true' : 'false').append(',')
              .append(r.walletFrozen ? 'true' : 'false').append(',')
              .append(r.lifetimeDisputes ?: 0).append(',')
              .append(df.format(new Date(r.createdAt ?: 0))).append(',')
              .append(df.format(new Date(r.updatedAt ?: 0))).append('\n')
        }
        ResponseEntity.ok()
            .header('Content-Disposition',
                "attachment; filename=\"skinbox-disputes-${df.format(new Date()).replace(':','-')}.csv\"")
            .header('Content-Type', 'text/csv; charset=utf-8')
            .header('Cache-Control', 'no-store')
            .body(sb.toString())
    }

    /**
     * Clear a chargeback hold (batch 467). Flips a DISPUTED deposit
     * transaction back to COMPLETED so the user's withdrawal hold
     * (batch 465) lifts. Used when:
     *   - the cardholder dropped the dispute with their bank
     *   - we won the dispute via Stripe
     *   - it was a false positive (e.g. friendly fraud the staff verified)
     *
     * Body: `{reason: "..."}` (optional). Audited as DISPUTE_CLEARED so
     * staff actions are accountable.
     */
    @PostMapping("/transactions/{txId}/clear-dispute")
    ResponseEntity<Map> clearDispute(@PathVariable Long txId,
                                     @RequestBody(required = false) Map body,
                                     HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.clearDisputeHold(uid, txId, body?.reason as String))
    }

    /**
     * Remove a review that violates TOS (batch 479) — profanity, PII,
     * harassment, etc. Buyer is notified with the reason so they can
     * rewrite cleanly or escalate. Audited as REVIEW_DELETED_STAFF.
     */
    @DeleteMapping("/reviews/{id}")
    ResponseEntity<Map> removeReview(@PathVariable Long id,
                                     @RequestBody(required = false) Map body,
                                     HttpServletRequest req) {
        def uid = requireAdmin(req)
        // reviewService is @Autowired(required = false) — mirror the
        // removeLoadout guard so an unwired ReviewService returns a clean
        // error instead of NPE-ing into a 500.
        if (reviewService == null) {
            throw new com.sboxmarket.exception.BadRequestException("REVIEW_UNAVAILABLE",
                'Review service is not wired')
        }
        reviewService.adminDeleteReview(uid, id, body?.reason as String)
        ResponseEntity.ok([id: id, status: 'REMOVED'])
    }

    /** Admin-initiated loadout takedown (batch 583). Used for TOS
     *  violations: PII in descriptions, harassment in names,
     *  prohibited imagery. Owner is notified + audited. */
    @Autowired(required = false) com.sboxmarket.service.LoadoutService loadoutService
    @DeleteMapping("/loadouts/{id}")
    ResponseEntity<Map> removeLoadout(@PathVariable Long id,
                                      @RequestBody(required = false) Map body,
                                      HttpServletRequest req) {
        def uid = requireAdmin(req)
        if (loadoutService == null) {
            throw new com.sboxmarket.exception.BadRequestException("LOADOUT_UNAVAILABLE",
                'Loadout service is not wired')
        }
        loadoutService.adminDelete(uid, id, body?.reason as String)
        ResponseEntity.ok([id: id, status: 'REMOVED'])
    }

    // ── Trade moderation ────────────────────────────────────────────

    @GetMapping("/trades")
    ResponseEntity<List> trades(@RequestParam(required = false, defaultValue = "ALL") String state,
                                HttpServletRequest req) {
        requireAdmin(req)
        ResponseEntity.ok(adminService.listTrades(state))
    }

    /** Admin trades CSV export (batch 559). Honors the same `state` filter
     *  as the JSON endpoint so the download matches what staff are
     *  looking at. Cap at 5000 rows — same ceiling as /audit.csv +
     *  /profile/trades.csv; narrower filter + re-download for a full
     *  history. Columns match the AdminService.listTrades projection
     *  which already inlines buyer/seller display names + fraud chips. */
    @GetMapping(value = "/trades.csv", produces = "text/csv")
    ResponseEntity<String> tradesCsv(@RequestParam(required = false, defaultValue = "ALL") String state,
                                     HttpServletRequest req) {
        requireAdmin(req)
        def rows = (adminService.listTrades(state) ?: []).take(5000)
        def df = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        df.timeZone = java.util.TimeZone.getTimeZone("UTC")
        def esc = com.sboxmarket.util.CsvUtil.&safeCell   // batch 978
        def sb = new StringBuilder()
        // Batch 782 — tradeOfferUrl column so dispute-triage spreadsheets
        // can link straight to the Steam offer from Excel/Sheets. Kept as
        // the final column so legacy column-indexed consumers don't break.
        sb.append('id,state,price,feeAmount,buyerUserId,buyerName,sellerUserId,sellerName,itemName,createdAt,updatedAt,settledAt,sentAt,tradeOfferUrl\n')
        rows.each { r ->
            sb.append(r.id ?: '').append(',')
              .append(esc(r.state)).append(',')
              .append(r.price ?: '').append(',')
              .append(r.feeAmount ?: '').append(',')
              .append(r.buyerUserId ?: '').append(',')
              .append(esc(r.buyerName)).append(',')
              .append(r.sellerUserId ?: '').append(',')
              .append(esc(r.sellerName)).append(',')
              .append(esc(r.itemName)).append(',')
              .append(df.format(new Date(r.createdAt ?: 0))).append(',')
              .append(df.format(new Date(r.updatedAt ?: 0))).append(',')
              .append(r.settledAt ? df.format(new Date(r.settledAt)) : '').append(',')
              .append(r.sentAt    ? df.format(new Date(r.sentAt))    : '').append(',')
              .append(esc(r.tradeOfferUrl)).append('\n')
        }
        ResponseEntity.ok()
            .header('Content-Disposition',
                "attachment; filename=\"skinbox-trades-${state}-${df.format(new Date()).replace(':','-')}.csv\"")
            .header('Content-Type', 'text/csv; charset=utf-8')
            .header('Cache-Control', 'no-store')
            .body(sb.toString())
    }

    @PostMapping("/trades/{id}/release")
    ResponseEntity<Map> releaseTrade(@PathVariable Long id,
                                     @RequestBody(required = false) Map body,
                                     HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.forceReleaseTrade(uid, id, body?.reason as String))
    }

    @PostMapping("/trades/{id}/cancel")
    ResponseEntity<Map> cancelTrade(@PathVariable Long id,
                                    @RequestBody(required = false) Map body,
                                    HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.forceCancelTrade(uid, id, body?.reason as String))
    }

    // ── Stripe refunds (admin-only) ─────────────────────────────────

    @PostMapping("/deposits/{id}/refund")
    ResponseEntity<Map> refundDeposit(@PathVariable Long id,
                                      @RequestBody(required = false) Map body,
                                      HttpServletRequest req) {
        requireAdmin(req)
        BigDecimal amount = null
        if (body?.amount != null) {
            try { amount = new BigDecimal(body.amount.toString()) }
            catch (NumberFormatException ignored) {
                throw new com.sboxmarket.exception.BadRequestException("INVALID_AMOUNT", "amount must be a valid number")
            }
        }
        ResponseEntity.ok(stripeService.refundDeposit(id, amount))
    }

    // ── Simulator (seed fake listings for QA) ───────────────────────

    @PostMapping("/simulate/listings")
    ResponseEntity<Map> simulateListings(@RequestBody(required = false) Map body,
                                         HttpServletRequest req) {
        def uid = requireAdmin(req)
        int count
        try { count = (body?.count ?: 20) as int }
        catch (Exception ignored) { count = 20 }
        if (count < 1) count = 1
        if (count > 200) count = 200
        ResponseEntity.ok(adminSimulatorService.simulateListings(uid, count))
    }

    @PostMapping("/simulate/clear")
    ResponseEntity<Map> clearSimulated(HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminSimulatorService.clearSimulated(uid))
    }

    @GetMapping("/simulate/count")
    ResponseEntity<Map> countSimulated(HttpServletRequest req) {
        requireAdmin(req)
        ResponseEntity.ok(adminSimulatorService.countSimulated())
    }

    // ── Legacy sync endpoint (kept — still used) ────────────────────

    @PostMapping("/sync-scmm")
    ResponseEntity<Map> syncScmm(HttpServletRequest req) {
        requireAdmin(req)
        try {
            def result = sboxApiService.syncFromScmm()
            ResponseEntity.ok(result)
        } catch (Exception e) {
            log.error("SCMM sync failed", e)
            ResponseEntity.status(500).body([error: 'Sync failed — check server logs'])
        }
    }

    /** Force an immediate Steam Market price sync (batch 376). The
     *  background @Scheduled sync runs every 30 min; this endpoint lets
     *  admin kick one off on demand (e.g. after catalogue import, or
     *  when a user reports stale prices). 200 on success with the
     *  counts the scheduled job logs; 500 on failure. Admin-only. */
    @Autowired(required = false)
    com.sboxmarket.service.SteamMarketPriceService steamMarketPriceService

    @PostMapping("/sync-prices")
    ResponseEntity<Map> syncPrices(HttpServletRequest req) {
        requireAdmin(req)
        if (steamMarketPriceService == null) {
            return ResponseEntity.status(503).body([error: 'Price sync service not available'])
        }
        try {
            // Spawn in a non-request thread so the HTTP response returns
            // immediately — a full sync can take ~11 minutes (80 items ×
            // 8s throttle). The admin UI doesn't need to block on it.
            new Thread({
                try { steamMarketPriceService.syncPricesFromSteam() }
                catch (Exception e) { log.error("Manual Steam price sync failed", e) }
            } as Runnable, 'manual-price-sync').start()
            ResponseEntity.ok([started: true, message: 'Price sync started in background; check logs for progress.'])
        } catch (Exception e) {
            log.error("Failed to launch manual price sync", e)
            ResponseEntity.status(500).body([error: 'Could not launch sync — check server logs'])
        }
    }
}
