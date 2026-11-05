/*
 * UserSecurityRepository.java — Spring Data JPA Repository for USRSEC VSAM dataset
 *
 * Source COBOL Programs:
 *   - COSGN00C.cbl (Sign-on Authentication) — EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID)
 *   - COUSR00C.cbl (User List)              — EXEC CICS STARTBR/READNEXT/READPREV for paginated browse
 *   - COUSR01C.cbl (User Add)               — EXEC CICS WRITE DATASET('USRSEC') FROM(SEC-USER-DATA)
 *   - COUSR02C.cbl (User Update)            — EXEC CICS READ UPDATE / REWRITE
 *   - COUSR03C.cbl (User Delete)            — EXEC CICS DELETE DATASET('USRSEC') RIDFLD(WS-USER-ID)
 *
 * Source Copybook:
 *   - CSUSR01Y.cpy — SEC-USER-DATA (80 bytes, SEC-USR-ID PIC X(08) primary key)
 *
 * VSAM Dataset: AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS
 *   Record Length: 80 bytes
 *   Primary Key:   SEC-USR-ID (8 bytes, position 0)
 *   Organization:  KSDS (Key-Sequenced Data Set)
 *
 * COBOL Access Pattern to JPA Method Mapping:
 *   EXEC CICS READ DATASET('USRSEC') RIDFLD(key)          -> findById(String) / findByUserId(String)
 *   EXEC CICS READ UPDATE DATASET('USRSEC') RIDFLD(key)   -> findById() + @Version optimistic lock
 *   EXEC CICS REWRITE DATASET('USRSEC') FROM(record)      -> save(UserSecurity entity)
 *   EXEC CICS WRITE DATASET('USRSEC') FROM(record)        -> save(UserSecurity entity)
 *   EXEC CICS DELETE DATASET('USRSEC') RIDFLD(key)        -> deleteById(String userId)
 *   EXEC CICS STARTBR / READNEXT / READPREV               -> findAll(Pageable)
 *
 * Ver: CardDemo_v1.0 — Migrated from COBOL to Java 25 + Spring Boot 3.5.x
 */
package com.cardemo.repository;

import com.cardemo.entity.UserSecurity;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link UserSecurity} entity.
 *
 * <p>Provides data access abstraction for the USRSEC VSAM KSDS dataset
 * (80-byte records, CSUSR01Y.cpy, 8-byte SEC-USR-ID primary key). This
 * repository supports two primary use cases in the CardDemo application:</p>
 *
 * <ol>
 *   <li><strong>Authentication lookup</strong> (COSGN00C.cbl) — Primary key
 *       read of user records for sign-on verification. The COBOL program
 *       reads by {@code SEC-USR-ID} (mapped to {@link UserSecurity#getUserId()})
 *       and compares the stored password ({@link UserSecurity#getPassword()})
 *       against the entered credential. CICS response code 13 (record not found)
 *       maps to {@link Optional#empty()}.</li>
 *   <li><strong>Admin user management</strong> (COUSR00C–COUSR03C.cbl) —
 *       Full CRUD operations restricted to administrators
 *       ({@link UserSecurity#getUserType()} = ADMIN). Includes paginated browse
 *       (PF7/PF8 navigation, 10 users per page), user creation, update with
 *       optimistic locking, and deletion.</li>
 * </ol>
 *
 * <h3>COBOL-to-JPA Access Pattern Mapping</h3>
 * <table>
 *   <tr><th>COBOL Operation</th><th>JPA Method</th><th>Program</th></tr>
 *   <tr><td>READ by RIDFLD (auth)</td><td>{@code findByUserId(String)}</td><td>COSGN00C</td></tr>
 *   <tr><td>READ by RIDFLD</td><td>{@code findById(String)}</td><td>COUSR02C, COUSR03C</td></tr>
 *   <tr><td>READ UPDATE + REWRITE</td><td>{@code findById()} + {@code save()}</td><td>COUSR02C</td></tr>
 *   <tr><td>WRITE</td><td>{@code save()}</td><td>COUSR01C</td></tr>
 *   <tr><td>DELETE</td><td>{@code deleteById()}</td><td>COUSR03C</td></tr>
 *   <tr><td>STARTBR / READNEXT / READPREV</td><td>{@code findAll(Pageable)}</td><td>COUSR00C</td></tr>
 *   <tr><td>Existence check (duplicate)</td><td>{@code existsById()}</td><td>COUSR01C</td></tr>
 *   <tr><td>Record count</td><td>{@code count()}</td><td>Administrative</td></tr>
 * </table>
 *
 * <h3>Inherited JpaRepository Methods</h3>
 * <ul>
 *   <li>{@code findById(String userId)} — Primary key READ by 8-char SEC-USR-ID
 *       (COSGN00C auth, COUSR02C update, COUSR03C delete)</li>
 *   <li>{@code save(UserSecurity entity)} — WRITE/REWRITE equivalent
 *       (COUSR01C add, COUSR02C update) with {@code @Version} optimistic locking</li>
 *   <li>{@code deleteById(String userId)} — DELETE equivalent (COUSR03C delete)</li>
 *   <li>{@code findAll()} — Full sequential browse for administrative reports</li>
 *   <li>{@code findAll(Pageable pageable)} — Paginated browse
 *       (COUSR00C STARTBR/READNEXT/READPREV with PF7/PF8)</li>
 *   <li>{@code existsById(String userId)} — Duplicate user detection during add
 *       (COUSR01C pre-write validation)</li>
 *   <li>{@code count()} — Total user record count</li>
 * </ul>
 *
 * @see UserSecurity
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@Repository
public interface UserSecurityRepository extends JpaRepository<UserSecurity, String> {

    /**
     * Finds a user security record by user ID for authentication lookup.
     *
     * <p>This is a Spring Data derived query method that resolves to the same
     * query as {@code findById(String)}, but provides improved readability in
     * authentication service code (SignonService). The method name explicitly
     * communicates the intent of looking up a user by their identifier.</p>
     *
     * <p><strong>COBOL origin:</strong> Maps directly to {@code COSGN00C.cbl}'s
     * authentication read pattern:</p>
     * <pre>
     *     EXEC CICS READ
     *          DATASET   (WS-USRSEC-FILE)
     *          INTO      (SEC-USER-DATA)
     *          RIDFLD    (WS-USER-ID)
     *          KEYLENGTH (LENGTH OF WS-USER-ID)
     *          RESP      (WS-RESP-CD)
     *     END-EXEC.
     * </pre>
     *
     * <p><strong>Response mapping:</strong></p>
     * <ul>
     *   <li>CICS RESP=0 (normal) → {@link Optional#of(Object)} with the found
     *       {@link UserSecurity} record. Caller then verifies the password via
     *       {@link UserSecurity#getPassword()} comparison (BCrypt in Java,
     *       replacing the COBOL plaintext {@code IF SEC-USR-PWD = WS-USER-PWD}
     *       check) and determines the user role via
     *       {@link UserSecurity#getUserType()}.</li>
     *   <li>CICS RESP=13 (NOTFND) → {@link Optional#empty()}, indicating the
     *       user ID does not exist. Maps to the COBOL error message
     *       "User not found. Try again ..."</li>
     * </ul>
     *
     * @param userId the user identifier to look up (max 8 characters, case-sensitive);
     *               corresponds to COBOL field {@code SEC-USR-ID PIC X(08)} and
     *               maps to {@link UserSecurity#getUserId()}
     * @return an {@link Optional} containing the matching {@link UserSecurity}
     *         record if found, or {@link Optional#empty()} if no user exists
     *         with the given ID
     */
    Optional<UserSecurity> findByUserId(String userId);

    /**
     * Returns a paginated list of all user security records.
     *
     * <p>Overrides the inherited {@code findAll(Pageable)} to provide COBOL
     * traceability documentation. The underlying behavior is identical to the
     * {@link JpaRepository} default implementation.</p>
     *
     * <p><strong>COBOL origin:</strong> Maps to {@code COUSR00C.cbl}'s admin
     * user listing browse pattern using CICS sequential file access:</p>
     * <pre>
     *     EXEC CICS STARTBR DATASET(WS-USRSEC-FILE) ...
     *     EXEC CICS READNEXT DATASET(WS-USRSEC-FILE) INTO(SEC-USER-DATA) ...
     *     EXEC CICS READPREV DATASET(WS-USRSEC-FILE) INTO(SEC-USER-DATA) ...
     * </pre>
     *
     * <p>The COBOL browse pattern supports PF7 (page backward) and PF8
     * (page forward) navigation with 10 users per page (defined by the
     * {@code USER-REC OCCURS 10 TIMES} working-storage table in COUSR00C).
     * In Java, this maps to Spring Data's {@link Pageable} abstraction with
     * configurable page size and sort order.</p>
     *
     * <p><strong>Usage in admin user list (COUSR00C equivalent):</strong></p>
     * <pre>
     *     Page&lt;UserSecurity&gt; page = userSecurityRepository
     *         .findAll(PageRequest.of(pageNumber, 10, Sort.by("userId")));
     * </pre>
     *
     * @param pageable pagination and sorting parameters specifying the page
     *                 number (zero-based), page size (COBOL default: 10),
     *                 and sort order (default: by userId ascending, matching
     *                 VSAM KSDS key sequence)
     * @return a {@link Page} of {@link UserSecurity} records for the requested
     *         page, including total element count and total page count for
     *         navigation
     */
    @Override
    Page<UserSecurity> findAll(Pageable pageable);
}
