package com.carddemo.repository;

import com.carddemo.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for the {@link User} entity (relational table
 * {@code users}), providing data access for both application authentication and
 * administrative user management.
 *
 * <h2>Legacy provenance</h2>
 * <p>This repository replaces the keyed access to the legacy VSAM {@code USRSEC}
 * Key-Sequenced Data Set whose record layout is defined by the COBOL copybook
 * {@code app/cpy/CSUSR01Y.cpy} (record {@code SEC-USER-DATA}, 8-byte key
 * {@code SEC-USR-ID}). The COBOL sign-on program {@code app/cbl/COSGN00C.cbl}
 * performed a single keyed {@code READ} of that file to authenticate a user; that
 * read is reproduced here by the inherited {@link JpaRepository#findById(Object)
 * findById(String)} lookup on the primary key.</p>
 *
 * <h2>Key type</h2>
 * <p>The identifier type is {@link String} because {@link User} declares its
 * {@code @Id} as {@code private String userId} (column {@code user_id VARCHAR(8)},
 * e.g. {@code "ADMIN001"}). The user id is an application-assigned natural key, so
 * the repository is parameterised as {@code JpaRepository<User, String>}.</p>
 *
 * <h2>Consumers and access paths</h2>
 * <ul>
 *   <li><strong>Authentication</strong> &mdash; {@code security.CustomUserDetailsService}
 *       loads a single user with the inherited {@link JpaRepository#findById(Object)
 *       findById(String)} (returning {@code Optional<User>}) and adapts it into a
 *       Spring Security {@code UserDetails}, mirroring the {@code COSGN00C} keyed
 *       read of the security file.</li>
 *   <li><strong>Administrative CRUD</strong> &mdash; {@code service.UserService} performs
 *       admin-only user management using the inherited {@code findAll(Pageable)},
 *       {@code save(User)}, {@code deleteById(String)}, and {@code existsById(String)}
 *       operations.</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>The standard {@link JpaRepository} CRUD and pagination surface is sufficient;
 *       no custom query methods are declared. A {@code findByUserId(String)} derived
 *       method would be redundant because {@code userId} <em>is</em> the {@code @Id},
 *       and is therefore intentionally omitted in favour of the inherited
 *       {@code findById}.</li>
 *   <li>No {@code @Repository} stereotype annotation is required: Spring Data
 *       automatically detects and instantiates a proxy for this interface during
 *       repository scanning.</li>
 *   <li>This interface performs no credential handling. Password hashing and BCrypt
 *       verification are the responsibility of the authentication service; the
 *       repository only reads and writes the persisted {@code users} row.</li>
 * </ul>
 *
 * @see User
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
public interface UserRepository extends JpaRepository<User, String> {
}
