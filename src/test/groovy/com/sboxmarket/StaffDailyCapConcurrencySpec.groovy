package com.sboxmarket

import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.CsrService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Security review 2026-10-15, finding 14: the CSR and admin daily credit
 * caps were read and then acted on with no per-staff lock, so a burst of
 * parallel credits (each to a different account, so no wallet lock
 * serialized them) could all read the same "used today" total and pass
 * together. The staff member's own user row is now locked before the sum,
 * so the burst takes turns and the cap holds.
 */
@SpringBootTest
@ActiveProfiles("test")
class StaffDailyCapConcurrencySpec extends Specification {

    @Autowired ApplicationContext ctx

    SteamUserRepository   userRepo
    WalletRepository      walletRepo
    TransactionRepository txRepo

    def setup() {
        userRepo   = ctx.getBean(SteamUserRepository)
        walletRepo = ctx.getBean(WalletRepository)
        txRepo     = ctx.getBean(TransactionRepository)
    }

    private List<Long> targets(int n, String tag) {
        (1..n).collect { i ->
            def u = userRepo.save(new SteamUser(steamId64: "${tag}${i}", displayName: "T${i}"))
            walletRepo.save(new Wallet(username: "steam_${tag}${i}", balance: BigDecimal.ZERO, currency: 'USD'))
            u.id
        }
    }

    private List<String> burst(List<Long> targetIds, Closure<Object> credit) {
        def pool = Executors.newFixedThreadPool(targetIds.size())
        def go = new CountDownLatch(1)
        def futures = targetIds.collect { Long t ->
            pool.submit({ ->
                go.await()
                try { credit(t); 'OK' } catch (Exception e) { e.class.simpleName }
            } as Callable<String>)
        }
        go.countDown()
        def results = futures.collect { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        results
    }

    def "a burst of parallel CSR goodwill credits never passes the daily cap"() {
        given: 'a CSR with the default $25 per credit and $200 per day, and 16 eligible accounts'
        def uniq = System.nanoTime()
        def csr = userRepo.save(new SteamUser(steamId64: "csr_${uniq}", displayName: 'CSR', role: 'CSR'))
        def ids = targets(16, "csrt_${uniq}_")
        def csrService = ctx.getBean(CsrService)

        when: '16 credits of $25 race each other'
        def results = burst(ids) { Long t ->
            csrService.issueGoodwillCredit(csr.id, t, new BigDecimal('25.00'), 'race test')
        }

        then: 'exactly 8 go through ($200) and the total issued is within the cap'
        results.count('OK') == 8
        txRepo.sumByTypeReferenceSince('ADJUSTMENT_CREDIT', "csr_${csr.id}".toString(), 0L) == new BigDecimal('200.00')
    }

    def "a burst of parallel admin wallet credits never passes the daily cap"() {
        given: 'an admin with the default $10,000 per credit and $25,000 per day, and 6 accounts'
        def uniq = System.nanoTime()
        def admin = userRepo.save(new SteamUser(steamId64: "adm_${uniq}", displayName: 'Admin', role: 'ADMIN'))
        def ids = targets(6, "admt_${uniq}_")
        def adminService = ctx.getBean(AdminService)

        when: '6 credits of $10,000 race each other'
        def results = burst(ids) { Long t ->
            adminService.creditWallet(admin.id, t, new BigDecimal('10000.00'), 'race test')
        }

        then: 'only 2 go through ($20,000); a third would have made $30,000'
        results.count('OK') == 2
        txRepo.sumByTypeReferenceSince('ADJUSTMENT_CREDIT', "admin_${admin.id}".toString(), 0L) == new BigDecimal('20000.00')
    }
}
