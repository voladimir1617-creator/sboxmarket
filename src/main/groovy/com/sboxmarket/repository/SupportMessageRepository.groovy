package com.sboxmarket.repository

import com.sboxmarket.model.SupportMessage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface SupportMessageRepository extends JpaRepository<SupportMessage, Long> {

    @Query("SELECT m FROM SupportMessage m WHERE m.ticketId = :tid ORDER BY m.createdAt ASC")
    List<SupportMessage> findByTicket(@Param("tid") Long ticketId)
}
