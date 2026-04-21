-- V45 — optional seller reply on an offer (batch 387).
--
-- Complements V43's buyer-supplied `message` field. Lets the seller
-- attach a short reason alongside a rejection ("already committed to
-- another buyer, sorry") so the buyer understands why and can walk
-- away without wondering. Sanitised via TextSanitizer.cleanShort +
-- capped at 280 chars in OfferService, same as the buyer message.
--
-- Stored on the rejected offer row itself rather than a new thread
-- entry so the buyer sees the rejection context inline on their
-- existing Offers card, not buried in a counter chain.
ALTER TABLE offers
    ADD COLUMN IF NOT EXISTS seller_reply VARCHAR(280);
