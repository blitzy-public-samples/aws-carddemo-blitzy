package com.carddemo.repository;

import com.carddemo.entity.CardXref;
import com.carddemo.entity.CardXref.CardXrefId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository interface for {@link CardXref} entity providing CRUD operations
 * and custom queries for card cross-reference relationship lookups.
 * 
 * <p><strong>VSAM Replacement Context:</strong></p>
 * <p>This repository replaces COBOL CICS operations on the VSAM CXACAIX alternate index file
 * used for navigating card-customer-account relationships. In the mainframe architecture, these
 * relationships were managed through:</p>
 * <ul>
 *   <li><strong>CXACAIX Alternate Index:</strong> Card cross-reference data storage</li>
 *   <li><strong>COBOL START/READ NEXT:</strong> Sequential traversal of card relationships</li>
 *   <li><strong>CICS READ operations:</strong> Direct access via RIDFLD key positioning</li>
 * </ul>
 * 
 * <p><strong>Query Method Design:</strong></p>
 * <p>Custom query methods follow Spring Data JPA naming conventions and property path navigation
 * for composite primary keys. Since {@link CardXref} uses an embedded composite key
 * ({@link CardXrefId}), query methods reference nested properties using the "id." prefix:</p>
 * <ul>
 *   <li>{@code findByIdCardNumber} → navigates to id.cardNumber field</li>
 *   <li>{@code findByIdCustomerId} → navigates to id.customerId field</li>
 *   <li>{@code findByIdAccountId} → navigates to id.accountId field</li>
 * </ul>
 * 
 * <p><strong>Performance Considerations (Section 0.2 Requirements):</strong></p>
 * <ul>
 *   <li><strong>Response Time Target:</strong> Sub-200ms for cross-reference lookups under 10,000 TPS</li>
 *   <li><strong>Database Indexes:</strong> B-tree indexes on card_number, customer_id, and account_id</li>
 *   <li><strong>Query Optimization:</strong> Direct index access via WHERE clause on indexed columns</li>
 *   <li><strong>Connection Pooling:</strong> HikariCP with 20-50 connections for concurrent access</li>
 * </ul>
 * 
 * <p><strong>Referential Integrity (Section 0.9 Critical Requirement):</strong></p>
 * <p>Cross-reference data relationships are preserved with 100% referential integrity through:</p>
 * <ul>
 *   <li>Foreign key constraints enforced at PostgreSQL database level</li>
 *   <li>CASCADE delete rules ensuring orphaned xref records are automatically removed</li>
 *   <li>Composite primary key constraint preventing duplicate card-customer-account relationships</li>
 *   <li>Service layer validation preventing creation of invalid relationships</li>
 * </ul>
 * 
 * <p><strong>Usage Examples:</strong></p>
 * <pre>
 * // Find all cards for a customer
 * List&lt;CardXref&gt; customerCards = cardXrefRepository.findByIdCustomerId(customerId);
 * 
 * // Find all cards for an account
 * List&lt;CardXref&gt; accountCards = cardXrefRepository.findByIdAccountId(accountId);
 * 
 * // Find cross-reference by card number
 * List&lt;CardXref&gt; cardXrefs = cardXrefRepository.findByIdCardNumber(cardNumber);
 * 
 * // Check if card-customer-account relationship exists
 * CardXrefId xrefId = new CardXrefId(cardNumber, customerId, accountId);
 * boolean exists = cardXrefRepository.existsById(xrefId);
 * 
 * // Create new card cross-reference relationship
 * CardXref newXref = new CardXref();
 * newXref.setId(new CardXrefId(cardNumber, customerId, accountId));
 * cardXrefRepository.save(newXref);
 * </pre>
 * 
 * @see CardXref
 * @see CardXrefId
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.9">Cross-Reference Data Relationships Preservation</a>
 */
@Repository
public interface CardXrefRepository extends JpaRepository<CardXref, CardXrefId> {

    /**
     * Find all card cross-reference entries for a specific card number.
     * 
     * <p>This query method navigates to the composite key's cardNumber field and retrieves
     * all cross-reference entries where the card is linked. This replaces COBOL CICS READ
     * operations on the CXACAIX file with RIDFLD(CARD-NUMBER).</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * EXEC CICS START
     *      FILE('CXACAIX')
     *      RIDFLD(WS-CARD-NUMBER)
     *      GTEQ
     * END-EXEC.
     * EXEC CICS READNEXT
     *      FILE('CXACAIX')
     *      INTO(CARD-XREF-RECORD)
     * END-EXEC.
     * </pre>
     * 
     * <p><strong>SQL Generated:</strong></p>
     * <pre>
     * SELECT * FROM card_xref WHERE card_number = ?
     * </pre>
     * 
     * @param cardNumber the 16-character card number to search for
     * @return list of CardXref entities matching the card number (typically one entry)
     * @see CardXref
     */
    List<CardXref> findByIdCardNumber(String cardNumber);

    /**
     * Find all card cross-reference entries for a specific customer.
     * 
     * <p>This query method retrieves all cards associated with a given customer ID,
     * enabling the card list view functionality required by COCRDLIC.cbl program migration
     * (CardListService). This replaces VSAM CXACAIX alternate index traversal by customer ID.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * EXEC CICS START
     *      FILE('CXACAIX')
     *      RIDFLD(WS-CUSTOMER-ID)
     *      GTEQ
     * END-EXEC.
     * PERFORM UNTIL END-OF-FILE
     *   EXEC CICS READNEXT
     *        FILE('CXACAIX')
     *        INTO(CARD-XREF-RECORD)
     *   END-EXEC
     *   IF XREF-CUST-ID = WS-CUSTOMER-ID
     *      PERFORM PROCESS-CARD
     *   END-IF
     * END-PERFORM.
     * </pre>
     * 
     * <p><strong>SQL Generated:</strong></p>
     * <pre>
     * SELECT * FROM card_xref WHERE customer_id = ?
     * </pre>
     * 
     * <p><strong>Usage in Services:</strong></p>
     * <ul>
     *   <li>CardListService.getCardsForCustomer(customerId) → display all cards for a customer</li>
     *   <li>CardDetailService.validateCardOwnership(cardNumber, customerId) → authorization check</li>
     * </ul>
     * 
     * @param customerId the 9-digit customer identifier
     * @return list of CardXref entities for all cards associated with the customer
     * @see CardXref
     */
    List<CardXref> findByIdCustomerId(Long customerId);

    /**
     * Find all card cross-reference entries for a specific account.
     * 
     * <p>This query method retrieves all cards linked to a given account ID, supporting
     * account-based card management operations required by COACTVWC.cbl and COACTUPC.cbl
     * program migrations. Multiple cards can be associated with a single account (primary
     * cardholder + authorized users).</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * EXEC CICS START
     *      FILE('CXACAIX')
     *      RIDFLD(WS-ACCOUNT-ID)
     *      GTEQ
     * END-EXEC.
     * PERFORM UNTIL END-OF-FILE
     *   EXEC CICS READNEXT
     *        FILE('CXACAIX')
     *        INTO(CARD-XREF-RECORD)
     *   END-EXEC
     *   IF XREF-ACCT-ID = WS-ACCOUNT-ID
     *      PERFORM PROCESS-CARD
     *   END-IF
     * END-PERFORM.
     * </pre>
     * 
     * <p><strong>SQL Generated:</strong></p>
     * <pre>
     * SELECT * FROM card_xref WHERE account_id = ?
     * </pre>
     * 
     * <p><strong>Usage in Services:</strong></p>
     * <ul>
     *   <li>AccountViewService.getAccountDetails(accountId) → include associated cards</li>
     *   <li>CardListService.getCardsForAccount(accountId) → account-based card listing</li>
     *   <li>TransactionCreationService.validateCardForAccount(cardNumber, accountId)</li>
     * </ul>
     * 
     * @param accountId the 11-digit account identifier
     * @return list of CardXref entities for all cards associated with the account
     * @see CardXref
     */
    List<CardXref> findByIdAccountId(Long accountId);
}
