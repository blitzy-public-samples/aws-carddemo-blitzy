package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for user accounts (Spring Security integration).
 *
 * <p>Maps the 80-byte {@code SEC-USER-DATA} record from {@code app/cpy/CSUSR01Y.cpy}
 * (5 user fields + 23-byte filler — filler is <em>not</em> represented in Java).
 * The original COBOL layout (version {@code CardDemo_v1.0-15-g27d6c6f-68}) is:</p>
 *
 * <pre>
 * 01 SEC-USER-DATA.
 *   05 SEC-USR-ID                 PIC X(08).   --&gt; userId    (PK, length 8)
 *   05 SEC-USR-FNAME              PIC X(20).   --&gt; firstName (length 20)
 *   05 SEC-USR-LNAME              PIC X(20).   --&gt; lastName  (length 20)
 *   05 SEC-USR-PWD                PIC X(08).   --&gt; secUsrPwd (modernized to length 60, BCrypt)
 *   05 SEC-USR-TYPE               PIC X(01).   --&gt; userType  (length 1)
 *   05 SEC-USR-FILLER             PIC X(23).   --&gt; NOT mapped (reserved padding)
 * </pre>
 *
 * <p><strong>PR-17 — Implements {@code UserDetails}:</strong> This entity implements
 * the Spring Security {@code UserDetails} interface so it can be returned directly
 * from {@code UserDetailsServiceImpl.loadUserByUsername(...)} and used by
 * {@code DaoAuthenticationProvider} during the login flow. It is the only CardDemo
 * entity that implements a Spring Security contract.</p>
 *
 * <p><strong>PR-17 — BCrypt password storage:</strong> The {@code secUsrPwd} column
 * stores a 60-character BCrypt hash (NOT the 8-character plaintext from the original
 * COBOL system). Per AAP §0.6.8 the 10 default users seeded by {@code UserSeedingJobConfig}
 * receive BCrypt hashes of the literal {@code "PASSWORD"} — each user gets a distinct
 * hash thanks to BCrypt's embedded salt. Authentication therefore performs
 * {@code BCryptPasswordEncoder.matches(rawPassword, secUsrPwd)} rather than the original
 * COBOL plaintext comparison {@code IF SEC-USR-PWD = WS-USER-PWD}.</p>
 *
 * <p><strong>PR-19 — Role mapping:</strong> {@code userType = 'A'} →
 * {@code ROLE_ADMIN}; {@code userType = 'U'} → {@code ROLE_USER}. This is implemented
 * in {@link #getAuthorities()} and enforced by Spring Security
 * {@code @PreAuthorize("hasRole('ADMIN')")} annotations on all
 * {@code UserController} and {@code BatchAdminController} endpoints (PR-18 —
 * closes the documented programmatic-auth gap from the COBOL system, where the
 * {@code COUSR00C}-{@code COUSR03C} programs relied on menu routing only).</p>
 *
 * <p><strong>AAP §0.6.12 — JPA auditing:</strong> {@code @EntityListeners(AuditingEntityListener.class)}
 * together with the {@code @CreatedDate}/{@code @LastModifiedDate}/{@code @CreatedBy}/
 * {@code @LastModifiedBy} fields auto-populates the audit columns on persist/update,
 * addressing the audit-log gap documented for the legacy system. Auditing only
 * activates when {@code @EnableJpaAuditing} is present in the application context.</p>
 *
 * <p>No {@code @Version} (optimistic-lock) field is declared: per AAP §0.3.3 optimistic
 * locking is mandated only for {@code Account}, {@code Card}, {@code Customer} and
 * {@code Transaction}. Identity-based {@link #equals(Object)}/{@link #hashCode()} are
 * derived from {@code userId} alone, the natural primary key.</p>
 *
 * @see org.springframework.security.core.userdetails.UserDetails
 */
@Entity
@Table(name = "users")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User implements UserDetails {

    /**
     * Maps COBOL {@code SEC-USR-ID PIC X(08)} — primary key. Uppercase convention
     * (e.g., {@code "ADMIN001"}, {@code "USER0001"}). Also serves as the Spring
     * Security username via {@link #getUsername()}.
     */
    @Id
    @Column(name = "user_id", length = 8, nullable = false)
    private String userId;

    /** Maps COBOL {@code SEC-USR-FNAME PIC X(20)}. */
    @Column(name = "first_name", length = 20)
    private String firstName;

    /** Maps COBOL {@code SEC-USR-LNAME PIC X(20)}. */
    @Column(name = "last_name", length = 20)
    private String lastName;

    /**
     * <strong>BCrypt hash</strong> (60 characters) per PR-17. Original COBOL
     * {@code SEC-USR-PWD} was {@code PIC X(08)} plaintext; modernized to a BCrypt
     * hash stored as {@code VARCHAR(60)}. Default seeded users have the hash of the
     * literal {@code "PASSWORD"} per AAP §0.6.8. Exposed to Spring Security through
     * {@link #getPassword()}.
     */
    @Column(name = "sec_usr_pwd", length = 60, nullable = false)
    private String secUsrPwd;

    /**
     * Maps COBOL {@code SEC-USR-TYPE PIC X(01)}. {@code 'A'} = ADMIN
     * ({@code ROLE_ADMIN}), {@code 'U'} = USER ({@code ROLE_USER}) per PR-19. Drives
     * the authority returned by {@link #getAuthorities()}.
     */
    @Column(name = "user_type", length = 1, nullable = false)
    private String userType;

    /**
     * Audit field (AAP §0.6.12) — set once on initial persist by
     * {@code AuditingEntityListener}. Immutable thereafter ({@code updatable = false}).
     */
    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * Audit field (AAP §0.6.12) — refreshed on every update by
     * {@code AuditingEntityListener}.
     */
    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Audit field (AAP §0.6.12) — principal that created the row, supplied by the
     * configured {@code AuditorAware} bean. Immutable thereafter
     * ({@code updatable = false}).
     */
    @CreatedBy
    @Column(name = "created_by", length = 64, updatable = false)
    private String createdBy;

    /**
     * Audit field (AAP §0.6.12) — principal that last modified the row, supplied by
     * the configured {@code AuditorAware} bean.
     */
    @LastModifiedBy
    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    // -------------------- UserDetails implementation (PR-17 + PR-19) --------------------

    /**
     * Returns the single granted authority derived from {@link #userType} (PR-19).
     * {@code 'A'} → {@code ROLE_ADMIN}; any other non-null value → {@code ROLE_USER}.
     * The {@code "ROLE_"} prefix is mandatory for Spring Security {@code hasRole(...)}
     * semantics — {@code hasRole('ADMIN')} checks for the authority {@code "ROLE_ADMIN"}.
     * When {@code userType} is {@code null} an empty (immutable) collection is returned
     * to avoid a {@code NullPointerException} during authority resolution.
     *
     * @return an immutable single-element authority collection, or an empty collection
     *         when {@code userType} is {@code null}
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (userType == null) {
            return Collections.emptyList();
        }
        String role = "A".equals(userType) ? "ROLE_ADMIN" : "ROLE_USER";
        return List.of(new SimpleGrantedAuthority(role));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the stored 60-character BCrypt hash ({@link #secUsrPwd}); Spring
     * Security's {@code DaoAuthenticationProvider} compares the raw login password
     * against this hash via {@code BCryptPasswordEncoder.matches(...)}.</p>
     */
    @Override
    public String getPassword() {
        return secUsrPwd;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The Spring Security username is the primary key {@link #userId}.</p>
     */
    @Override
    public String getUsername() {
        return userId;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@code true}: this demonstration application models no account
     * expiration.</p>
     */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@code true}: this demonstration application models no account
     * lockout.</p>
     */
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@code true}: this demonstration application models no credential
     * expiration.</p>
     */
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@code true}: this demonstration application has no disabled-user
     * status.</p>
     */
    @Override
    public boolean isEnabled() {
        return true;
    }

    // -------------------- equals / hashCode (primary-key identity) --------------------

    /**
     * Identity equality based solely on the {@link #userId} primary key. Two
     * {@code User} instances are equal when both have a non-null, equal {@code userId}.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof User)) {
            return false;
        }
        User that = (User) o;
        return userId != null && userId.equals(that.userId);
    }

    /**
     * Hash code derived from the {@link #userId} primary key (0 when {@code userId}
     * is {@code null}), consistent with {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        return userId != null ? userId.hashCode() : 0;
    }
}
