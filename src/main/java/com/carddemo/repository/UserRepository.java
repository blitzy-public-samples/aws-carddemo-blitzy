package com.carddemo.repository;

import com.carddemo.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link User} authentication entities.
 *
 * <p>Replaces the VSAM {@code USRSEC} keyed file access from
 * {@code app/cbl/COSGN00C.cbl} paragraph {@code READ-USER-SEC-FILE}
 * (lines 211-257), which performed a record-keyed read of the security file:</p>
 *
 * <pre>{@code
 * EXEC CICS READ DATASET(WS-USRSEC-FILE) INTO(SEC-USER-DATA)
 *            LENGTH(LENGTH OF SEC-USER-DATA)
 *            RIDFLD(WS-USER-ID) KEYLENGTH(LENGTH OF WS-USER-ID)
 *            RESP(WS-RESP-CD)
 * }</pre>
 *
 * <p>The COBOL {@code RIDFLD(WS-USER-ID)} keyed read maps directly to the inherited
 * {@code findById(String userId)}: a {@code WS-RESP-CD} of {@code 0} (DFHRESP NORMAL,
 * record found) corresponds to a present {@code Optional}; a {@code WS-RESP-CD} of
 * {@code 13} (DFHRESP NOTFND, "User not found") corresponds to {@code Optional.empty()}.</p>
 *
 * <p><strong>Primary consumer &mdash; {@code UserDetailsServiceImpl} (AAP &sect;0.4.1.6):</strong>
 * the Spring Security {@code UserDetailsService} contract is satisfied entirely by the
 * inherited {@code findById}:</p>
 *
 * <pre>{@code
 * @Override
 * public UserDetails loadUserByUsername(String username) {
 *     return userRepository.findById(username)
 *         .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
 * }
 * }</pre>
 *
 * <p>Because {@link User} implements {@code org.springframework.security.core.userdetails.UserDetails},
 * the entity returned by {@code findById} is handed straight to Spring Security. The
 * {@code DaoAuthenticationProvider} then verifies the submitted plaintext password against
 * the returned entity's BCrypt-hashed {@code secUsrPwd} field (60 chars per PR-17) via
 * {@code BCryptPasswordEncoder.matches(rawPassword, storedHash)}. This modernizes the
 * original COBOL plaintext comparison {@code IF SEC-USR-PWD = WS-USER-PWD} (PR-17) without
 * any password handling occurring in this repository.</p>
 *
 * <p><strong>Other consumers:</strong></p>
 * <ul>
 *   <li>{@code UserService} (AAP &sect;0.4.1.6) &mdash; CRUD operations (using the inherited
 *       {@code findAll}, {@code save}, {@code existsById}, {@code deleteById}, {@code count})
 *       for the {@code @PreAuthorize("hasRole('ADMIN')")}-protected {@code /api/admin/users}
 *       endpoints. PR-18 closes the documented programmatic-auth gap from the original
 *       {@code COUSR00C}-{@code COUSR03C} programs, which relied on menu routing only.</li>
 *   <li>{@code UserSeedingJobConfig} &mdash; the DUSRSECJ ({@code app/jcl/DUSRSECJ.jcl})
 *       replacement that BCrypt-rehashes the literal {@code "PASSWORD"} for the 10 default
 *       users ({@code ADMIN001}-{@code ADMIN005}, {@code USER0001}-{@code USER0005}) and
 *       persists them via {@code save}/{@code saveAll} per AAP &sect;0.6.8.</li>
 * </ul>
 *
 * <p>The primary key is {@code String userId} (length 8) matching the COBOL
 * {@code SEC-USR-ID PIC X(08)} field from {@code app/cpy/CSUSR01Y.cpy} per PR-13 &mdash;
 * hence the {@code <User, String>} type parameters (an {@code Integer}/{@code Long} ID
 * would be incorrect). The uppercase-ID convention (e.g., {@code "ADMIN001"},
 * {@code "USER0001"}) is preserved from the original system.</p>
 *
 * <p>No custom finder methods are declared. Per the Repository Pattern (AAP &sect;0.3.3 #1)
 * this interface extends {@code JpaRepository} (not {@code CrudRepository}) to inherit the
 * full CRUD/paging API. The inherited {@code findById(String)} already satisfies the
 * {@code UserDetailsServiceImpl.loadUserByUsername} contract directly, so a
 * {@code findByUsername(String)} method would be redundant &mdash; the username <em>is</em>
 * the primary key. This is a pure data-access component carrying no business logic
 * (BCrypt hashing, role mapping, and {@code @PreAuthorize} enforcement live in the
 * service, entity, and controller layers respectively).</p>
 *
 * <p>The explicit {@code @Repository} stereotype marks the interface as a persistence
 * component for component scanning and activates Spring's
 * {@code PersistenceExceptionTranslationPostProcessor}, translating provider-specific
 * exceptions into the {@code org.springframework.dao.DataAccessException} hierarchy &mdash;
 * particularly useful on authentication paths.</p>
 *
 * @see com.carddemo.entity.User
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@Repository
public interface UserRepository extends JpaRepository<User, String> {
}
