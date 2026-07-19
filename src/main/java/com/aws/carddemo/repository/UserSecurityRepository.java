package com.aws.carddemo.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
