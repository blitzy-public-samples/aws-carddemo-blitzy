package com.aws.carddemo.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.aws.carddemo.domain.UserSecurity;

/**
 * Spring Data JPA repository for the {@link UserSecurity} entity, replacing the legacy VSAM
 * {@code USRSEC} KSDS I/O in the AWS CardDemo COBOL&rarr;Java migration.
 *
 * <p>In the mainframe system the online signon program and the user-administration programs reached
 * the user-security store through {@code EXEC CICS READ/WRITE/REWRITE/DELETE} (and, in batch,
 * {@code FILE SECTION} record I/O) against the {@code USRSEC} key-sequenced data set. This interface
 * expresses those same access paths as Spring Data operations over the relational
 * {@code user_security} table on PostgreSQL, preserving the VSAM key semantics exactly: the
 * key-sequenced primary key {@code SEC-USR-ID} (an 8-character user id) becomes the JPA identifier
 * {@link UserSecurity#getUsrId() usrId} of type {@link String}, so the repository is typed
 * {@code JpaRepository<UserSecurity, String>} (AAP &sect;0.6.2).</p>
 *
 * <p>The inherited {@link JpaRepository} operations provide the primary-key CRUD used by the
 * user-administration screens &mdash; {@code findById}/{@code save}/{@code deleteById} back the
 * legacy list, add, update, and delete programs {@code COUSR00C}, {@code COUSR01C}, {@code COUSR02C},
 * and {@code COUSR03C} respectively. The one declared finder, {@link #findByUsrId(String)}, expresses
 * the single signon authentication access path (see below); no other finders are declared, in keeping
 * with the &quot;no feature expansion&quot; parity constraint (AAP &sect;0.7.1).</p>
 *
 * <p><strong>Authentication:</strong> {@link #findByUsrId(String)} is the login lookup consumed by the
 * Spring Security {@code com.aws.carddemo.security.CardDemoUserDetailsService}. It mirrors the legacy
 * signon program {@code COSGN00C}, which reads {@code USRSEC} by {@code SEC-USR-ID}, compares the
 * (cleartext) {@code SEC-USR-PWD}, and uses {@code SEC-USR-TYPE} ({@code "A"}/{@code "U"}) for
 * role-based routing (AAP &sect;0.6.7). Password verification and the {@code ROLE_ADMIN}/{@code ROLE_USER}
 * mapping live in the security layer; this repository only retrieves the record.</p>
 *
 * <p><strong>Origin (traceability, AAP &sect;0.6.10):</strong> Origin: {@code legacy/cpy/CSUSR01Y.cpy}
 * ({@code SEC-USER-DATA}, RECLN 80); VSAM {@code USRSEC} per {@code legacy/csd/CARDDEMO.CSD};
 * authentication path from {@code legacy/cbl/COSGN00C.cbl}.</p>
 *
 * @see UserSecurity
 */
@Repository
public interface UserSecurityRepository extends JpaRepository<UserSecurity, String> {

    /**
     * Finds a user-security record by its user id, the signon authentication lookup.
     *
     * <p>This is the Spring Data equivalent of the legacy signon program {@code COSGN00C} reading the
     * {@code USRSEC} KSDS by its primary key {@code SEC-USR-ID}. Although {@code usrId} is also the JPA
     * identifier (so this overlaps {@link #findById(Object)}), the explicitly named finder documents
     * the authentication intent and is the exact method invoked by
     * {@code com.aws.carddemo.security.CardDemoUserDetailsService} while loading a principal by login
     * id (AAP &sect;0.4.1, &sect;0.6.7).</p>
     *
     * @param usrId the user id ({@code SEC-USR-ID}, up to 8 characters); the VSAM KSDS primary key
     * @return an {@link Optional} containing the matching {@link UserSecurity} if a record exists for
     *         {@code usrId}, or an empty {@link Optional} if none is found
     */
    Optional<UserSecurity> findByUsrId(String usrId);

    /**
     * Reads a user-security record by its user id ({@code SEC-USR-ID}) while acquiring a row-level
     * <strong>pessimistic write lock</strong> (JPA {@link LockModeType#PESSIMISTIC_WRITE};
     * PostgreSQL {@code SELECT ... FOR UPDATE}).
     *
     * <p><strong>Origin / parity (AAP &sect;0.3.3, &sect;0.6.5):</strong> the user-update program
     * {@code legacy/cbl/COUSR02C.cbl} ({@code READ ... UPDATE} at line 328 &rarr; {@code REWRITE} at
     * line 360) and the user-delete program {@code legacy/cbl/COUSR03C.cbl} ({@code READ ... UPDATE}
     * at line 275 &rarr; {@code DELETE} at line 307) re-read {@code USRSEC} under an exclusive record
     * lock held until the rewrite/delete. Under the migrated {@code READ COMMITTED} stack the plain
     * {@link #findByUsrId(String)} does not lock the row, leaving a lost-update window (CWE-362) the
     * COBOL never had. Acquiring this write lock inside the update/delete service's
     * {@code @Transactional} unit-of-work, held until commit, serializes concurrent administrators of
     * the same user and reproduces the legacy record-lock semantics. This is the <em>same</em>
     * primary-key access path as {@link #findByUsrId(String)} with a lock added; it is not a new
     * finder and therefore not feature expansion. The user-administration paths lock a single record
     * (the user) only, so no lock-order/deadlock concern arises. Must be invoked within an active
     * transaction.</p>
     *
     * @param usrId the user id ({@code SEC-USR-ID}, up to 8 characters); the VSAM KSDS primary key
     * @return an {@link Optional} containing the locked {@link UserSecurity} if present, otherwise empty
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserSecurity u where u.usrId = :usrId")
    Optional<UserSecurity> findByUsrIdForUpdate(@Param("usrId") String usrId);

    /**
     * Reads a bounded, ascending window of user-security records whose user id is
     * <strong>greater than or equal to</strong> {@code usrId}, ordered ascending by
     * {@code SEC-USR-ID} and capped at {@code limit} rows &mdash; the forward paging window of the
     * user-list browse.
     *
     * <p><strong>Origin / parity (review finding #21; AAP &sect;0.6.2):</strong> the user-list
     * program {@code legacy/cbl/COUSR00C.cbl} browses {@code USRSEC} with {@code STARTBR}
     * (positioning at the first key <em>&ge;</em> the start key) followed by up to a page's worth of
     * {@code READNEXT}s (plus a one-record look-ahead). The initial Java migration positioned by
     * materialising the <em>entire</em> {@code USRSEC} table with {@code findAll(Sort)} and scanning
     * it in memory, which does not scale. This finder reproduces the VSAM {@code STARTBR}-at-key plus
     * forward {@code READNEXT} sequence as a single bounded, key-ordered query: it returns just the
     * slice the browse can consume in one screen paint (one page plus the skip-one and look-ahead
     * reads), and never more than {@code limit} rows. The ascending {@code SEC-USR-ID} order is the
     * VSAM key order; the {@code user_security} table is created with the {@code C} collation so the
     * ordering is the same byte-wise ordering the legacy KSDS browse relied on. The service positions
     * within this window with the identical greater-than-or-equal comparison the COBOL browse used,
     * so the emitted rows are byte-identical to the legacy paging. No filtering predicate is applied
     * beyond the key bound, so no record is silently dropped &mdash; the browse has no in-loop record
     * filter (unlike the card browse), keeping this a pure, parity-faithful paging window.</p>
     *
     * @param usrId the inclusive lower-bound user id (already right-padded to the 8-byte
     *              {@code SEC-USR-ID} width by the caller); pass the empty string for the
     *              low-values start that selects the first page
     * @param limit the maximum number of rows to return (the page size plus the browse's skip-one
     *              and look-ahead reads); must be positive
     * @return the matching records, ascending by user id, at most {@code limit} in size (possibly
     *         empty when no user id is at or beyond {@code usrId})
     */
    List<UserSecurity> findByUsrIdGreaterThanEqualOrderByUsrIdAsc(String usrId, Limit limit);

    /**
     * Reads a bounded, <strong>descending</strong> window of user-security records whose user id is
     * <strong>less than or equal to</strong> {@code usrId}, ordered descending by {@code SEC-USR-ID}
     * and capped at {@code limit} rows &mdash; the backward paging window of the user-list browse.
     *
     * <p><strong>Origin / parity (review finding #21; AAP &sect;0.6.2):</strong> paragraph
     * {@code PROCESS-PAGE-BACKWARD} in {@code legacy/cbl/COUSR00C.cbl} browses {@code USRSEC}
     * backward: it {@code STARTBR}s at the first key already displayed and issues up to a page of
     * {@code READPREV}s (plus a look-ahead). This finder returns the page-sized slice ending at
     * {@code usrId} in descending key order, capped at {@code limit}. The caller reverses the slice to
     * ascending before handing it to the shared in-memory browse cursor (which always walks an
     * ascending list, positioning at the greater-than-or-equal index and reading backward from it),
     * so the backward-paging output is byte-identical to the legacy {@code READPREV} sequence. As with
     * the forward window, ordering uses the table's {@code C} collation to match the VSAM key order and
     * no predicate beyond the key bound is applied.</p>
     *
     * @param usrId the inclusive upper-bound user id (already right-padded to the 8-byte
     *              {@code SEC-USR-ID} width by the caller)
     * @param limit the maximum number of rows to return (the page size plus the browse's skip-one
     *              and look-ahead reads); must be positive
     * @return the matching records, <em>descending</em> by user id, at most {@code limit} in size
     *         (the caller reverses them to ascending for the browse cursor)
     */
    List<UserSecurity> findByUsrIdLessThanEqualOrderByUsrIdDesc(String usrId, Limit limit);
}
