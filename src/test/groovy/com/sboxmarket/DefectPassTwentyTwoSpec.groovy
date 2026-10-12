package com.sboxmarket

import com.sboxmarket.controller.AdminController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.Item
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.SupportTicket
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.SupportTicketRepository
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.CsrService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Defect pass 22: admin item edits only store rarities the market
 * filters know, and the waiting ticket queue never cuts off its
 * longest-waiting tickets.
 */
@SpringBootTest
@ActiveProfiles("test")
class DefectPassTwentyTwoSpec extends Specification {

    @Autowired AdminController adminController
    @Autowired AdminService adminService
    @Autowired CsrService csrService
    @Autowired SteamUserRepository userRepo
    @Autowired ItemRepository itemRepo
    @Autowired SupportTicketRepository ticketRepo

    static int seq = 0
    String uniq = String.valueOf(System.nanoTime())

    private SteamUser user(String role = 'USER') {
        seq++
        def tail = (uniq + seq).reverse().take(9).reverse()
        userRepo.save(new SteamUser(steamId64: '7656122' + tail.padLeft(10, '0'),
            displayName: "Pass22-${role}-${seq}-${uniq}", role: role))
    }

    private MockHttpServletRequest signedIn(Long uid) {
        def req = new MockHttpServletRequest()
        def session = new MockHttpSession()
        session.setAttribute(SteamAuthController.SESSION_USER_ID, uid)
        req.session = session
        req
    }

    private Item item() {
        itemRepo.save(new Item(name: "Pass22 item ${uniq}", category: 'Hats', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal('3.00'), isListed: true, iconEmoji: '🎩'))
    }

    def "an admin item edit rejects a rarity the market filters don't know"() {
        given:
        def admin = user('ADMIN')
        def itm = item()

        when:
        adminController.updateItem(itm.id, [rarity: 'Exceedingly Rare'], signedIn(admin.id))

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_RARITY'
        itemRepo.findById(itm.id).get().rarity == 'Standard'
    }

    def "an admin item edit stores a known rarity in its canonical spelling"() {
        given:
        def admin = user('ADMIN')
        def itm = item()

        when:
        adminController.updateItem(itm.id, [rarity: 'off-market'], signedIn(admin.id))

        then:
        itemRepo.findById(itm.id).get().rarity == 'Off-Market'
    }

    def "an admin item edit rejects a 5-digit accent colour"() {
        given:
        def admin = user('ADMIN')
        def itm = item()

        when:
        adminController.updateItem(itm.id, [accentColor: '#12345'], signedIn(admin.id))

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_COLOR'
    }

    def "the waiting queue keeps its longest-waiting ticket past 500 rows"() {
        given:
        def u = user()
        long now = System.currentTimeMillis()
        def oldest = ticketRepo.save(new SupportTicket(userId: u.id, username: u.displayName,
            subject: "p22 oldest ${uniq}", status: 'WAITING_STAFF',
            createdAt: now - 400L * 86_400_000L, updatedAt: now - 400L * 86_400_000L))
        ticketRepo.saveAll((1..500).collect { i ->
            new SupportTicket(userId: u.id, username: u.displayName, subject: "p22 newer ${i} ${uniq}",
                status: 'WAITING_STAFF', createdAt: now + i, updatedAt: now + i)
        })

        expect:
        csrService.listTickets('WAITING_STAFF').any { it.id == oldest.id }
        adminService.listAllTickets('WAITING_STAFF').any { it.id == oldest.id }
    }
}
