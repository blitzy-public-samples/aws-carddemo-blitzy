package com.carddemo.mapper;

import java.util.List;

import com.carddemo.dto.user.UserCreateRequest;
import com.carddemo.dto.user.UserDto;
import com.carddemo.entity.User;

import org.springframework.stereotype.Component;

/**
 * Hand-coded mapper between the {@link User} JPA entity and the user
 * administration REST DTOs ({@link UserDto} outbound, {@link UserCreateRequest}
 * inbound).
 *
 * <p>This class is a Spring {@code @Component} so it can be constructor-injected
 * into {@code UserController} (REST layer) and {@code UserService} (business
 * layer). It carries no injectable dependencies — the default constructor is
 * sufficient — and therefore needs no Lombok {@code @RequiredArgsConstructor}
 * (PR-29: constructor injection only, no field injection).</p>
 *
 * <p><strong>CRITICAL SECURITY BOUNDARY (PR-17).</strong> This mapper is the
 * authoritative translation point for user data crossing the REST boundary. The
 * BCrypt password hash stored in {@code User.secUsrPwd} is server-side
 * credential material and MUST NEVER be exposed outbound. Accordingly
 * {@link #toDto(User)} (and {@link #toDtoList(List)}, which delegates to it)
 * deliberately omits the password hash — it never calls {@code getSecUsrPwd()}.
 * The companion {@link UserDto} has no password field at all, providing a
 * second, structural layer of defense.</p>
 *
 * <p><strong>RAW password handling (PR-17).</strong> On the inbound path,
 * {@link #toEntity(UserCreateRequest)} and {@link #updateEntity(UserCreateRequest, User)}
 * copy the <em>raw, plaintext</em> password from the request straight into
 * {@code User.secUsrPwd}. Encoding is intentionally <em>not</em> performed here:
 * the mapper is kept free of security dependencies (it has no
 * {@code PasswordEncoder}). The calling {@code UserService} MUST invoke
 * {@code BCryptPasswordEncoder.encode(rawPassword)} on the field before the
 * entity is persisted / before the transaction commits.</p>
 *
 * <p><strong>Role discriminator (PR-19).</strong> {@code userType} carries the
 * COBOL one-character role flag — {@code 'A'} (ADMIN, → {@code ROLE_ADMIN}) or
 * {@code 'U'} (USER, → {@code ROLE_USER}). It is passed through verbatim in both
 * directions; the entity's {@code getAuthorities()} performs the actual mapping
 * to a Spring Security {@code GrantedAuthority}.</p>
 *
 * <p>Field provenance — the original mainframe record layout
 * (source COBOL {@code app/cpy/CSUSR01Y.cpy}, {@code SEC-USER-DATA}):</p>
 * <pre>
 * 01 SEC-USER-DATA.
 *   05 SEC-USR-ID                 PIC X(08).   --&gt; userId    (PK, immutable)
 *   05 SEC-USR-FNAME              PIC X(20).   --&gt; firstName
 *   05 SEC-USR-LNAME              PIC X(20).   --&gt; lastName
 *   05 SEC-USR-PWD                PIC X(08).   --&gt; secUsrPwd (widened to 60 for BCrypt; never exposed)
 *   05 SEC-USR-TYPE               PIC X(01).   --&gt; userType  ('A' / 'U')
 *   05 SEC-USR-FILLER             PIC X(23).   --&gt; not mapped
 * </pre>
 *
 * @see com.carddemo.entity.User
 * @see com.carddemo.dto.user.UserDto
 * @see com.carddemo.dto.user.UserCreateRequest
 */
@Component
public class UserMapper {

    /**
     * Converts a {@link User} entity to a {@link UserDto} for outbound REST
     * responses.
     *
     * <p><strong>Security boundary (PR-17):</strong> this method does NOT and
     * MUST NEVER include {@code secUsrPwd} (the BCrypt password hash). The hash
     * is server-side authentication credential material that must never traverse
     * the REST API boundary. Only the four public fields are copied:
     * {@code userId}, {@code firstName}, {@code lastName} and {@code userType}.</p>
     *
     * <p>Returns {@code null} if the input is {@code null}.</p>
     *
     * @param user the {@code User} entity (may be {@code null})
     * @return a {@code UserDto} without the password hash, or {@code null} if the
     *         input is {@code null}
     */
    public UserDto toDto(User user) {
        if (user == null) {
            return null;
        }
        return UserDto.builder()
                .userId(user.getUserId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .userType(user.getUserType())
                .build();
        // NOTE (PR-17): user.getSecUsrPwd() is intentionally never read here —
        // the BCrypt hash must remain server-side.
    }

    /**
     * Builds a transient {@link User} entity from a {@link UserCreateRequest} for
     * the {@code POST /api/admin/users} create flow.
     *
     * <p><strong>IMPORTANT (PR-17):</strong> this method copies the <em>raw</em>
     * plaintext password from the request into {@code User.secUsrPwd}. The caller
     * ({@code UserService}) MUST then encode it via
     * {@code BCryptPasswordEncoder.encode(rawPassword)} BEFORE persisting the
     * entity. The mapper has no access to the encoder by design — encoding is a
     * service-layer responsibility, keeping mappers free of security
     * dependencies.</p>
     *
     * <p>{@code userType} is copied verbatim ({@code 'A'} / {@code 'U'}) per
     * PR-19. Returns {@code null} if the input is {@code null}.</p>
     *
     * @param request the {@code UserCreateRequest} from the REST API (may be
     *                {@code null})
     * @return a transient {@code User} entity holding the RAW (not-yet-hashed)
     *         password, or {@code null} if the input is {@code null}
     */
    public User toEntity(UserCreateRequest request) {
        if (request == null) {
            return null;
        }
        User user = new User();
        user.setUserId(request.getUserId());
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setSecUsrPwd(request.getPassword()); // RAW; service MUST encode before save
        user.setUserType(request.getUserType());
        return user;
    }

    /**
     * Updates an existing managed {@link User} entity in place from a
     * {@link UserCreateRequest} for the {@code PUT /api/admin/users/{userId}}
     * update flow.
     *
     * <p>The {@code existing} entity is mutated directly (PATCH-like semantics)
     * so JPA dirty-checking detects the changes and issues the appropriate
     * {@code UPDATE} SQL within the active transaction.</p>
     *
     * <p><strong>Password handling (PR-17):</strong> if
     * {@code request.getPassword()} is {@code null} or blank, the existing
     * password hash is preserved (allowing administrators to edit other fields
     * without forcing a password rotation). If a new password is supplied, the
     * RAW value is written to {@code secUsrPwd} and the caller
     * ({@code UserService}) MUST encode it via
     * {@code BCryptPasswordEncoder.encode(rawPassword)} BEFORE the transaction
     * commits.</p>
     *
     * <p>{@code userId} is NOT updated: it is the primary key and is immutable by
     * business rule (changing it would break referential integrity). {@code userType}
     * is copied verbatim per PR-19.</p>
     *
     * @param request  the update request (must not be {@code null})
     * @param existing the managed JPA entity to mutate (must not be {@code null})
     * @throws NullPointerException if {@code request} or {@code existing} is
     *                              {@code null}
     */
    public void updateEntity(UserCreateRequest request, User existing) {
        if (request == null || existing == null) {
            throw new NullPointerException("request and existing must not be null");
        }
        existing.setFirstName(request.getFirstName());
        existing.setLastName(request.getLastName());
        existing.setUserType(request.getUserType());
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            existing.setSecUsrPwd(request.getPassword()); // RAW; service MUST encode before commit
        }
        // userId intentionally NOT updated (PK is immutable per business rule).
    }

    /**
     * Converts a list of {@link User} entities to a list of {@link UserDto}
     * objects, preserving order.
     *
     * <p>Each element is mapped via {@link #toDto(User)}, so the PR-17 password
     * exclusion applies uniformly to every entry. Returns an empty (immutable)
     * list when the input is {@code null} or empty; the result is never
     * {@code null}.</p>
     *
     * @param users the list of {@code User} entities (may be {@code null})
     * @return a list of {@code UserDto} in the same order as the input (never
     *         {@code null})
     */
    public List<UserDto> toDtoList(List<User> users) {
        if (users == null || users.isEmpty()) {
            return List.of();
        }
        return users.stream()
                .map(this::toDto)
                .toList();
    }
}
