package com.cardemo.repository;

import com.cardemo.entity.TransactionTypeRef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link TransactionTypeRef} entity.
 *
 * <p>Provides data access for the TRANTYPE VSAM reference dataset, which
 * contains 7 static transaction type records mapped from
 * {@code app/data/ASCII/trantype.txt}:</p>
 *
 * <ul>
 *   <li>01 — Purchase</li>
 *   <li>02 — Payment</li>
 *   <li>03 — Credit</li>
 *   <li>04 — Authorization</li>
 *   <li>05 — Refund</li>
 *   <li>06 — Reversal</li>
 *   <li>07 — Adjustment</li>
 * </ul>
 *
 * <h3>COBOL-to-Java Mapping:</h3>
 * <table>
 *   <caption>VSAM access pattern translation</caption>
 *   <tr><th>VSAM Operation</th><th>Repository Method</th></tr>
 *   <tr><td>READ by TRAN-TYPE key</td><td>{@code findById(String typeCode)}</td></tr>
 *   <tr><td>STARTBR / READNEXT (full scan)</td><td>{@code findAll()}</td></tr>
 *   <tr><td>WRITE</td><td>{@code save(TransactionTypeRef)}</td></tr>
 *   <tr><td>DELETE</td><td>{@code deleteById(String typeCode)}</td></tr>
 * </table>
 *
 * <p>No custom query methods are required beyond those inherited from
 * {@link JpaRepository}. This is a static reference data table with a
 * 2-character type code primary key ({@code String}).</p>
 *
 * @see TransactionTypeRef
 */
@Repository
public interface TransactionTypeRefRepository extends JpaRepository<TransactionTypeRef, String> {

    // All required data access methods are inherited from JpaRepository:
    //
    //   findById(String typeCode)    — Keyed lookup by 2-byte type code (VSAM READ)
    //   findAll()                    — Returns all 7 reference records (VSAM browse)
    //   findAll(Pageable)            — Paginated retrieval
    //   save(TransactionTypeRef)     — Reference data persistence (VSAM WRITE/REWRITE)
    //   saveAll(Iterable)            — Bulk reference data persistence
    //   deleteById(String typeCode)  — Reference data removal (VSAM DELETE)
    //   delete(TransactionTypeRef)   — Entity-based removal
    //   existsById(String typeCode)  — Type code existence check
    //   count()                      — Total record count (expected: 7)
}
