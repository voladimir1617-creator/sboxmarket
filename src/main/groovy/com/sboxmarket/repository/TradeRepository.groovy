package com.sboxmarket.repository

import com.sboxmarket.model.Trade
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface TradeRepository extends JpaRepository<Trade, Long> {

    @Query("SELECT t FROM Trade t WHERE t.buyerUserId = :uid ORDER BY t.createdAt DESC")
    List<Trade> findByBuyer(@Param("uid") Long uid)

    @Query("SELECT t FROM Trade t WHERE t.sellerUserId = :uid ORDER BY t.createdAt DESC")
    List<Trade> findBySeller(@Param("uid") Long uid)

    @Query("SELECT t FROM Trade t WHERE (t.buyerUserId = :uid OR t.sellerUserId = :uid) ORDER BY t.createdAt DESC")
    List<Trade> findByParticipant(@Param("uid") Long uid)

    @Query("SELECT t FROM Trade t WHERE t.state IN :states ORDER BY t.updatedAt ASC")
    List<Trade> findByStateIn(@Param("states") List<String> states)

    Trade findByListingId(Long listingId)

    /** Admin trade queue — newest-updated-first, optional state filter.
     *  Empty-string sentinel for "no filter" per the Postgres type
     *  inference rule; see ItemRepository.searchCatalogue. */
    @Query("""
        SELECT t FROM Trade t
        WHERE (:state = '' OR t.state = :state)
        ORDER BY t.updatedAt DESC
    """)
    List<Trade> findForAdmin(@Param("state") String state)

    /** Trade-sweeper auto-release query — pulls only the
     *  PENDING_BUYER_CONFIRM trades whose updatedAt is older than the
     *  auto-release cutoff, so the scheduled job doesn't have to fetch
     *  every pending row and filter in memory. */
    @Query("""
        SELECT t FROM Trade t
        WHERE t.state = 'PENDING_BUYER_CONFIRM'
          AND t.updatedAt <= :cutoff
        ORDER BY t.updatedAt ASC
    """)
    List<Trade> findPendingConfirmOlderThan(@Param("cutoff") Long cutoff)
}
