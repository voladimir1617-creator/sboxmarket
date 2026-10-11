package com.sboxmarket

import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.SupportTicket
import com.sboxmarket.repository.NotificationRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.SupportTicketRepository
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.CsrService
import com.sboxmarket.service.SupportService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Defect pass 19: support notifications open the ticket they are about,
 * not the ticket list.
 */
@SpringBootTest
@ActiveProfiles("test")
class DefectPassNineteenSpec extends Specification {

    @Autowired SupportService supportService
    @Autowired AdminService adminService
    @Autowired CsrService csrService
    @Autowired SteamUserRepository userRepo
    @Autowired SupportTicketRepository ticketRepo
    @Autowired NotificationRepository notificationRepo

    static int seq = 0
    String uniq = String.valueOf(System.nanoTime())

    private SteamUser user(String role = 'USER') {
        seq++
        def tail = (uniq + seq).reverse().take(9).reverse()
        userRepo.save(new SteamUser(steamId64: '7656120' + tail.padLeft(10, '0'),
            displayName: "Pass19-${role}-${seq}-${uniq}", role: role))
    }

    private List<String> replyPaths(Long uid) {
        notificationRepo.findForUser(uid, PageRequest.of(0, 50))
            .findAll { it.kind == 'SUPPORT_REPLY' }*.path
    }

    def "staff replies link to the ticket itself"() {
        given:
        def admin = user('ADMIN')
        def csr = user('CSR')
        def u = user()
        def t = ticketRepo.save(new SupportTicket(userId: u.id, username: u.displayName,
            subject: 'p19 reply', status: 'WAITING_STAFF'))

        when:
        adminService.staffReply(admin.id, t.id, 'hello from admin')
        csrService.reply(csr.id, t.id, 'hello from csr')

        then:
        replyPaths(u.id) == ["/support?ticket=${t.id}", "/support?ticket=${t.id}"]*.toString()
    }

    def "the ticket-opened ping links to the new ticket"() {
        given:
        def u = user()

        when:
        def t = supportService.create(u.id, u.displayName, 'p19 opened', 'OTHER', 'Help please')

        then:
        replyPaths(u.id) == ["/support?ticket=${t.id}".toString()]
    }
}
