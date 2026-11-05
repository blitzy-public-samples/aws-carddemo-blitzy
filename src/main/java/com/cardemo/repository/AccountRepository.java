/*
 * AccountRepository.java — Spring Data JPA Repository for ACCTDATA VSAM dataset
 *
 * Source COBOL Programs:
 *   - COACTVWC.cbl (Account View) — EXEC CICS READ DATASET('ACCTDAT') RIDFLD(WS-ACCT-ID)
 *   - COACTUPC.cbl (Account Update) — EXEC CICS READ UPDATE / REWRITE
 *   - CBACT04C.cbl (Interest Calculation) — Sequential read, ACCT-GROUP-ID lookup
 *   - CBTRN02C.cbl (Daily Posting) — READ KEY IS FD-ACCT-ID / REWRITE
 *
 * Source Copybook:
 *   - CVACT01Y.cpy — ACCOUNT-RECORD (300 bytes, ACCT-ID PIC 9(11) primary key)
 *
 * VSAM Dataset: AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS
 *   Record Length: 300 bytes
 *   Primary Key:   ACCT-ID (11 bytes, position 0)
 *   Organization:  KSDS (Key-Sequenced Data Set)
 *
 * COBOL Access Pattern to JPA Method Mapping:
 *   EXEC CICS READ DATASET('ACCTDAT') RIDFLD(key)            -> findById(String acctId)
 *   EXEC CICS READ UPDATE DATASET('ACCTDAT') RIDFLD(key)     -> findById() + @Version optimistic lock
 *   EXEC CICS REWRITE DATASET('ACCTDAT') FROM(record)        -> save(Account entity)
 *   EXEC CICS WRITE DATASET('ACCTDAT') FROM(record)          -> save(Account entity)
 *   EXEC CICS DELETE DATASET('ACCTDAT') RIDFLD(key)          -> deleteById(String acctId)
 *   EXEC CICS STARTBR DATASET('ACCTDAT') / READNEXT          -> findAll(Pageable)
 *   Sequential batch READ with ACCT-ACTIVE-STATUS filter      -> findByActiveStatus(status, Pageable)
 *   Batch READ by ACCT-GROUP-ID (CBACT04C interest groups)    -> findByGroupId(String groupId)
 *
 * Ver: CardDemo_v1.0 — Migrated from COBOL to Java 25 + Spring Boot 3.5.x
 */
package com.cardemo.repository;

import com.cardemo.entity.Account;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link Account} entity.
 *
 * <p>Provides data access abstraction for the ACCTDATA VSAM KSDS dataset
 * (300-byte records, CVACT01Y.cpy, 11-byte ACCT-ID primary key). This is
 * the most heavily accessed dataset in the CardDemo system — referenced by
 * online programs (COACTVWC for account view, COACTUPC for account update)
 * and batch programs (CBACT04C for interest calculation, CBTRN02C for daily
 * transaction posting validation).</p>
 *
 * <h3>COBOL-to-JPA Access Pattern Mapping</h3>
 * <table>
 *   <tr><th>COBOL Operation</th><th>JPA Method</th><th>Program</th></tr>
 *   <tr><td>READ by RIDFLD</td><td>{@code findById(String)}</td><td>COACTVWC, COACTUPC, CBTRN02C</td></tr>
 *   <tr><td>READ UPDATE + REWRITE</td><td>{@code findById()} + {@code save()}</td><td>COACTUPC, CBTRN02C</td></tr>
 *   <tr><td>WRITE</td><td>{@code save()}</td><td>CBACT01C (seed)</td></tr>
 *   <tr><td>DELETE</td><td>{@code deleteById()}</td><td>Administrative</td></tr>
 *   <tr><td>STARTBR / READNEXT</td><td>{@code findAll(Pageable)}</td><td>CBACT04C (batch iteration)</td></tr>
 *   <tr><td>Browse with status filter</td><td>{@code findByActiveStatus(String, Pageable)}</td><td>Online browse</td></tr>
 *   <tr><td>Group lookup for interest</td><td>{@code findByGroupId(String)}</td><td>CBACT04C</td></tr>
 * </table>
 *
 * <h3>Inherited JpaRepository Methods</h3>
 * <ul>
 *   <li>{@code findById(String acctId)} — Primary keyed READ (COACTVWC, COACTUPC, CBTRN02C)</li>
 *   <li>{@code save(Account entity)} — WRITE/REWRITE with {@code @Version} optimistic locking</li>
 *   <li>{@code findAll()} — Full sequential read for batch processing</li>
 *   <li>{@code findAll(Pageable pageable)} — Paginated browse (STARTBR/READNEXT equivalent)</li>
 *   <li>{@code deleteById(String acctId)} — DELETE equivalent</li>
 *   <li>{@code existsById(String acctId)} — Existence check for validation</li>
 *   <li>{@code count()} — Total record count</li>
 * </ul>
 *
 * @see Account
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, String> {

    /**
     * Finds accounts filtered by active status with pagination support.
     *
     * <p>Translates the COBOL CICS STARTBR/READNEXT browse pattern with
     * status-based filtering into a Spring Data derived query. The VSAM browse
     * operation in online CICS programs filters accounts by
     * {@code ACCT-ACTIVE-STATUS PIC X(01)} — typical values 'Y' (active)
     * or 'N' (inactive).</p>
     *
     * <p>Spring Data auto-generates the query from the method name, matching
     * on the {@code activeStatus} field of the {@link Account} entity
     * (mapped from COBOL {@code ACCT-ACTIVE-STATUS PIC X(01)}).</p>
     *
     * <p><strong>Usage examples:</strong></p>
     * <ul>
     *   <li>List all active accounts: {@code findByActiveStatus("Y", pageable)}</li>
     *   <li>List all inactive accounts: {@code findByActiveStatus("N", pageable)}</li>
     * </ul>
     *
     * @param activeStatus the account active status flag to filter by
     *                     ('Y' for active, 'N' for inactive)
     * @param pageable     pagination and sorting parameters (page number,
     *                     page size, sort order)
     * @return a {@link Page} of {@link Account} entities matching the given
     *         active status, wrapped with pagination metadata (total elements,
     *         total pages, current page, etc.)
     */
    Page<Account> findByActiveStatus(String activeStatus, Pageable pageable);

    /**
     * Finds all accounts belonging to a specific discount group.
     *
     * <p>Translates the batch interest calculation lookup pattern from
     * CBACT04C.cbl where accounts are grouped by {@code ACCT-GROUP-ID PIC X(10)}
     * to determine applicable interest rates. During the interest calculation
     * batch job, the program reads the discount group file (DISCGRP) to obtain
     * interest rates and then locates all accounts sharing the same group ID
     * for group-based interest computation.</p>
     *
     * <p>Spring Data auto-generates the query from the method name, matching
     * on the {@code groupId} field of the {@link Account} entity
     * (mapped from COBOL {@code ACCT-GROUP-ID PIC X(10)}).</p>
     *
     * @param groupId the discount group identifier to filter by
     *                (up to 10 characters, matching ACCT-GROUP-ID)
     * @return a {@link List} of {@link Account} entities belonging to the
     *         specified discount group; empty list if no accounts match
     */
    List<Account> findByGroupId(String groupId);
}
