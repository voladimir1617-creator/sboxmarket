package com.sboxmarket

import com.sboxmarket.config.MoneyMode
import com.sboxmarket.service.MoneyResetService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

import javax.sql.DataSource

/**
 * PREVIEW HARNESS — reproduces the operator's live money state in the IN-MEMORY
 * H2 database, with the REAL primary keys, and prints the dry-run report exactly
 * as he would see it on his own box.
 *
 * Rows are inserted through plain JDBC with explicit ids so that wallet 1 is
 * `demo`, wallet 33 is his own account and wallet 65 is the treasury — the
 * report is then byte-identical to a real run rather than merely equivalent.
 *
 * <b>The operator's live H2 file is never opened.</b>
 */
@SpringBootTest
@ActiveProfiles("test")
class MoneyResetDryRunPreviewSpec extends Specification {

    @Autowired ApplicationContext ctx

    def "PREVIEW: the dry-run report the operator would read"() {
        given:
        MoneyResetService service = ctx.getBean(MoneyResetService)
        DataSource ds = ctx.getBean(DataSource)
        def c = ds.connection
        c.createStatement().with { st ->
            st.execute('DELETE FROM TRANSACTIONS')
            st.execute('DELETE FROM WALLETS')
        }

        // ── the nine wallets, with their real ids ──────────────────────
        [[1L, 'demo', '250.00'],
         [2L, 'steam_76561199000000001', '30373.25'],
         [3L, 'steam_76561199000000002', '23.92'],
         [4L, 'steam_76561199000000003', '50.00'],
         [5L, 'steam_76561199000000004', '0.00'],
         [6L, 'steam_76561199000000005', '0.51'],
         [7L, 'steam_76561199000000006', '0.00'],
         [33L, 'steam_76561199839805014', '100.00'],
         [65L, '__platform_treasury__', '0.02']].each { row ->
            def ps = c.prepareStatement(
                'INSERT INTO WALLETS (ID, VERSION, USERNAME, BALANCE, CURRENCY, CREATED_AT, FROZEN, PAYOUTS_ENABLED) ' +
                'VALUES (?,0,?,?,?,?,FALSE,FALSE)')
            ps.setLong(1, row[0] as Long)
            ps.setString(2, row[1] as String)
            ps.setBigDecimal(3, new BigDecimal(row[2] as String))
            ps.setString(4, 'USD')
            ps.setLong(5, System.currentTimeMillis())
            ps.executeUpdate()
        }

        // ── the forty-four transaction rows ───────────────────────────
        long tid = 1000L
        def tx = { long walletId, String type, String amount, String ref ->
            def ps = c.prepareStatement(
                'INSERT INTO TRANSACTIONS (ID, WALLET_ID, TYPE, STATUS, AMOUNT, CURRENCY, ' +
                'STRIPE_REFERENCE, CREATED_AT, UPDATED_AT) VALUES (?,?,?,?,?,?,?,?,?)')
            ps.setLong(1, tid++)
            ps.setLong(2, walletId)
            ps.setString(3, type)
            ps.setString(4, 'COMPLETED')
            ps.setBigDecimal(5, new BigDecimal(amount))
            ps.setString(6, 'USD')
            ps.setString(7, ref)
            ps.setLong(8, System.currentTimeMillis())
            ps.setLong(9, System.currentTimeMillis())
            ps.executeUpdate()
        }

        tx(2L, 'DEPOSIT', '50.00',    'dev_1780588946696')
        tx(2L, 'DEPOSIT', '250.00',   'dev_1780818253792')
        tx(33L,'DEPOSIT', '100.00',   'dev_1782351713871')
        tx(2L, 'DEPOSIT', '10000.00', 'dev_1782449659569')
        tx(2L, 'DEPOSIT', '10000.00', 'dev_1782449659620')
        tx(2L, 'DEPOSIT', '10000.00', 'dev_1782449659657')
        tx(2L, 'DEPOSIT', '100.00',   'dev_1782449730772')
        tx(3L, 'DEPOSIT', '25.00',    'dev_1788248144636')
        tx(4L, 'DEPOSIT', '50.00',    'dev_1788249615583')

        ['0.52','0.54','0.59','1.42','0.60','10.50','15.58','230.00','0.46','0.52',
         '0.60','0.60','0.67','0.68','0.81','0.94','1.01','1.03','1.09','1.16',
         '1.24','1.31','1.32','1.93'].each { tx(2L, 'PURCHASE', it, 'wallet') }
        tx(2L, 'PURCHASE', '0.90', 'auction')
        tx(3L, 'PURCHASE', '1.08', 'wallet')

        ['0.54','0.59','0.90','0.60','15.58','230.00'].each { tx(2L, 'REFUND', it, 'trade_cancel') }

        tx(6L,  'SALE', '0.51', 'trade')
        tx(2L,  'SALE', '1.06', 'trade')
        tx(65L, 'FEE',  '0.02', 'trade')
        c.close()

        when:
        def p = service.plan()
        p.mode = MoneyMode.SIMULATED
        p.executeRequested = false
        String report = service.render(p)
        println report

        then: 'the figures match the live database exactly'
        p.devDepositCount == 9L
        p.devDepositTotal == new BigDecimal('30575.00')
        p.balanceTotal == new BigDecimal('30797.70')
        p.txnTotalCount == 44L
        p.wallets.size() == 9
        p.wallets.findAll { !it.reconciles }*.id == [1L]
    }
}
