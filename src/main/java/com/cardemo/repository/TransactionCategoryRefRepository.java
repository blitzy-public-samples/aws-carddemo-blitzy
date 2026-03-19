package com.cardemo.repository;

import com.cardemo.entity.TransactionCategoryRef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for the {@link TransactionCategoryRef} entity.
 *
 * <p>Provides data access for the TRANCATG VSAM reference dataset containing
 * 18 transaction category records parsed from {@code app/data/ASCII/trancatg.txt}.
 * Each record maps a composite key of transaction type code (2-char, "01"–"07")
 * and category code (integer) to a human-readable description.</p>
 *
 * <h3>COBOL-to-Java Access Pattern Mapping:</h3>
 * <table>
 *   <tr><th>COBOL Access</th><th>Repository Method</th></tr>
 *   <tr><td>VSAM READ (keyed by type+category)</td><td>{@code findById(TransactionCategoryRefId)}</td></tr>
 *   <tr><td>VSAM full sequential scan</td><td>{@code findAll()} — returns all 18 records</td></tr>
 *   <tr><td>VSAM browse by type code prefix</td><td>{@link #findByTypeCode(String)} — derived query</td></tr>
 *   <tr><td>VSAM WRITE/REWRITE</td><td>{@code save()} / {@code saveAll()}</td></tr>
 *   <tr><td>VSAM DELETE</td><td>{@code deleteById()}</td></tr>
 * </table>
 *
 * <h3>Reference Data Distribution (7 types, 18 categories):</h3>
 * <ul>
 *   <li>Type "01" (Purchase): 5 categories — Sales Draft, Cash Advance, Check Debit, ATM, Interest</li>
 *   <li>Type "02" (Payment): 3 categories — Cash, Electronic, Check</li>
 *   <li>Type "03" (Credit): 3 categories — Account Credit, Purchase Balance, Cash Balance</li>
 *   <li>Type "04" (Authorization): 3 categories — Zero Dollar, Online Purchase, Travel Booking</li>
 *   <li>Type "05" (Refund): 1 category — Refund Credit</li>
 *   <li>Type "06" (Reversal): 2 categories — Fraud Reversal, Non-Fraud Reversal</li>
 *   <li>Type "07" (Adjustment): 1 category — Sales Draft Credit Adjustment</li>
 * </ul>
 *
 * <p>Supports batch posting validation in {@code CBTRN02C.cbl} (migrated to
 * {@code DailyPostingService}) and transaction category lookups in the online
 * transaction services.</p>
 *
 * @see TransactionCategoryRef
 * @see TransactionCategoryRef.TransactionCategoryRefId
 * @see <a href="app/data/ASCII/trancatg.txt">trancatg.txt — 18 transaction category records</a>
 */
@Repository
public interface TransactionCategoryRefRepository
        extends JpaRepository<TransactionCategoryRef, TransactionCategoryRef.TransactionCategoryRefId> {

    /**
     * Finds all transaction categories belonging to a specific transaction type.
     *
     * <p>This derived query method maps the COBOL pattern of browsing TRANCATG
     * records by type code prefix (e.g., all categories under type "01" returns
     * 5 records: Regular Sales Draft, Regular Cash Advance, Convenience Check
     * Debit, ATM Cash Advance, Interest Amount).</p>
     *
     * <p>One type code maps to multiple categories. Spring Data JPA automatically
     * derives the query from the method name, generating:</p>
     * <pre>
     * SELECT t FROM TransactionCategoryRef t WHERE t.typeCode = :typeCode
     * </pre>
     *
     * <p>Used by batch posting validation ({@code CBTRN02C.cbl} → {@code DailyPostingService})
     * to verify that a transaction's category code is valid for its transaction type,
     * and by online transaction services for category lookups.</p>
     *
     * @param typeCode the 2-character transaction type code (e.g., "01" through "07")
     * @return list of {@link TransactionCategoryRef} entities for the given type code;
     *         empty list if no categories exist for the specified type
     */
    List<TransactionCategoryRef> findByTypeCode(String typeCode);
}
