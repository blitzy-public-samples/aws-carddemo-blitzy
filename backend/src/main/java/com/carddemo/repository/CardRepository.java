package com.carddemo.repository;

import com.carddemo.entity.Card;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for Card entity operations.
 * 
 * Provides data access methods for credit card master data, replacing VSAM CARDDAT KSDS file 
 * access with indexed PostgreSQL queries. Supports card management operations, transaction 
 * processing, and card-account relationship queries per COCRDLIC and COCRDSLC program 
 * migration requirements.
 * 
 * Maintains PCI-DSS compliance for sensitive cardholder data per Section 0.4 security requirements.
 */
@Repository
public interface CardRepository extends JpaRepository<Card, String> {
    
    /**
     * Find card by card number (primary key).
     * Used for card validation in transaction processing (CBTRN01C batch job migration).
     * 
     * Maps to COBOL XREF file lookup: MOVE DALYTRAN-CARD-NUM TO XREF-CARD-NUM
     * Replaces VSAM READ operation with indexed PostgreSQL SELECT.
     * 
     * @param cardNumber 16-character card number
     * @return Optional containing Card if found, empty Optional otherwise
     */
    Optional<Card> findByCardNumber(String cardNumber);
    
    /**
     * Find all cards for a given account ID.
     * Supports card list view operations (COCRDLIC program migration).
     * 
     * @param accountId 11-digit account ID
     * @return List of cards associated with the account
     */
    List<Card> findByAccountId(Long accountId);
    
    /**
     * Find all cards for a given account ID with a specific status.
     * Supports filtered card list operations.
     * 
     * @param accountId 11-digit account ID
     * @param activeStatus card status ('Y', 'N', 'B', 'E')
     * @return List of cards matching criteria
     */
    List<Card> findByAccountIdAndActiveStatus(Long accountId, String activeStatus);
    
    /**
     * Find all active cards for a given account.
     * Convenience method for retrieving only active cards.
     * 
     * @param accountId 11-digit account ID
     * @return List of active cards (status = 'Y')
     */
    @Query("SELECT c FROM Card c WHERE c.accountId = :accountId AND c.activeStatus = 'Y'")
    List<Card> findActiveCardsByAccountId(@Param("accountId") Long accountId);
    
    /**
     * Check if a card exists by card number.
     * Efficient existence check without loading full entity.
     * 
     * @param cardNumber 16-character card number
     * @return true if card exists, false otherwise
     */
    boolean existsByCardNumber(String cardNumber);
    
    /**
     * Count cards for a given account.
     * Used for pagination and account summary operations.
     * 
     * @param accountId 11-digit account ID
     * @return count of cards associated with the account
     */
    long countByAccountId(Long accountId);
}
