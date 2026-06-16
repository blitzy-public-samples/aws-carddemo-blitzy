package com.carddemo.mapper;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import com.carddemo.dto.PageResponse;
import com.carddemo.dto.UserCreateRequest;
import com.carddemo.dto.UserResponse;
import com.carddemo.dto.UserUpdateRequest;
import com.carddemo.entity.User;

/**
 * Stateless, hand-written boundary mapper that converts between the {@link User}
 * JPA entity and the user-facing request/response DTOs consumed by
 * {@code UserController} (the Java migration of the legacy admin-only online
 * programs {@code COUSR00C}&ndash;{@code COUSR03C}; AAP &sect;0.4.1.4).
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>{@link #toResponse(User)} &mdash; project a persisted {@link User} onto the
 *       credential-free {@link UserResponse} (single-user view and list-row shape).</li>
 *   <li>{@link #toPageResponse(Page)} &mdash; wrap a Spring Data {@link Page} of
 *       {@link User} into the stable {@link PageResponse} pagination envelope, mapping
 *       each element through {@link #toResponse(User)}.</li>
 *   <li>{@link #toEntity(UserCreateRequest)} &mdash; build a fresh {@link User} from an
 *       administrative create request, mapping the identity and profile fields only.</li>
 *   <li>{@link #applyUpdate(UserUpdateRequest, User)} &mdash; apply the editable profile
 *       fields of an update request onto an already-loaded {@link User} (mirrors the
 *       {@code COUSR02C} maintenance flow).</li>
 * </ul>
 *
 * <h2>Security boundary &mdash; password suppression (AAP &sect;0.6.7, &sect;0.6.8, &sect;0.7.1)</h2>
 * <p>This mapper is a <strong>credential-suppression boundary</strong> and enforces it
 * structurally rather than by filtering:</p>
 * <ul>
 *   <li><strong>Outbound:</strong> {@link UserResponse} has no password component by
 *       design, so the password hash is never read from the entity
 *       ({@link User#getPassword()} is never called here) and can never be serialized
 *       into an API response or written to a log.</li>
 *   <li><strong>Inbound:</strong> the raw plaintext password carried by the create/update
 *       requests is <em>never</em> set on the entity by this mapper. BCrypt encoding
 *       (strength&nbsp;12) and assignment of the resulting hash are the sole responsibility
 *       of {@code UserService}; {@link User#setPassword(String)} is intentionally never
 *       invoked from this class.</li>
 * </ul>
 *
 * <h2>Immutability of the natural key</h2>
 * <p>The 8-character {@code userId} (COBOL {@code SEC-USR-ID}) is the application-assigned
 * primary key. It is assigned exactly once, on creation, from
 * {@link UserCreateRequest#userId()}. The update path
 * ({@link #applyUpdate(UserUpdateRequest, User)}) never mutates it &mdash; the identifier
 * of the record being edited is supplied by the URL path variable, and
 * {@link UserUpdateRequest} deliberately carries no {@code userId} component.</p>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>The mapper is a stateless Spring {@link Component}: it holds no fields and requires
 *       no constructor injection, so a single shared instance is safe for concurrent use.</li>
 *   <li>Mapping is plain, explicit Java &mdash; no MapStruct, ModelMapper, or Lombok &mdash;
 *       which keeps the credential-suppression rules auditable at a glance.</li>
 *   <li>Field shapes trace to the security-user record copybook
 *       {@code app/cpy/CSUSR01Y.cpy} ({@code 01 SEC-USER-DATA}): {@code SEC-USR-ID X(08)},
 *       {@code SEC-USR-FNAME X(20)}, {@code SEC-USR-LNAME X(20)}, {@code SEC-USR-PWD X(08)}
 *       (suppressed / service-encoded), {@code SEC-USR-TYPE X(01)}.</li>
 * </ul>
 *
 * @see User
 * @see UserResponse
 * @see UserCreateRequest
 * @see UserUpdateRequest
 * @see PageResponse
 */
@Component
public class UserMapper {

    /**
     * Projects a persisted {@link User} entity onto its credential-free
     * {@link UserResponse} representation.
     *
     * <p>The four components are passed positionally in the exact canonical-constructor
     * order declared by {@link UserResponse}
     * ({@code userId}, {@code firstName}, {@code lastName}, {@code userType}).</p>
     *
     * <p><strong>Password suppression:</strong> the entity's password hash is never read.
     * {@link UserResponse} has no password component, so credentials cannot leak through
     * this projection (AAP &sect;0.6.8).</p>
     *
     * @param u the source entity; may be {@code null}
     * @return an immutable {@link UserResponse}, or {@code null} when {@code u} is
     *         {@code null} (null-in / null-out policy, convenient for optional lookups)
     */
    public UserResponse toResponse(User u) {
        if (u == null) {
            return null;
        }
        return new UserResponse(
                u.getUserId(),
                u.getFirstName(),
                u.getLastName(),
                u.getUserType());
    }

    /**
     * Wraps a Spring Data {@link Page} of {@link User} entities into the stable
     * {@link PageResponse} pagination envelope, converting each element through
     * {@link #toResponse(User)} so the emitted rows are credential-free.
     *
     * <p>Every pagination attribute (page index, size, total elements, total pages,
     * first/last flags) is preserved verbatim from the source page. In particular the
     * page <em>size</em> reflects whatever the repository {@link org.springframework.data.domain.Pageable Pageable}
     * supplied &mdash; for the user-list browse that is the legacy fixed size of
     * <strong>7</strong> rows, enforced by the caller rather than hardcoded here.</p>
     *
     * @param page the source page of entities; must not be {@code null}
     * @return a {@link PageResponse} of {@link UserResponse} mirroring the source page's
     *         content and metadata
     * @throws NullPointerException if {@code page} is {@code null} (per the
     *         {@link PageResponse#from(Page, java.util.function.Function)} contract)
     */
    public PageResponse<UserResponse> toPageResponse(Page<User> page) {
        return PageResponse.from(page, this::toResponse);
    }

    /**
     * Builds a brand-new {@link User} entity from an administrative create request,
     * mapping the identity and profile fields only.
     *
     * <p>The natural-key {@code userId} and the profile fields ({@code firstName},
     * {@code lastName}, {@code userType}) are copied from the request. The
     * {@code password} is deliberately left unset on the returned entity.</p>
     *
     * <p><strong>Password handling:</strong> the raw plaintext password from
     * {@link UserCreateRequest#password()} is intentionally <em>not</em> copied here.
     * {@code UserService} encodes it with BCrypt (strength&nbsp;12) and sets the resulting
     * hash before persistence (AAP &sect;0.6.7). Leaving the field unset guarantees a raw
     * credential can never be written to the database through this mapper.</p>
     *
     * @param req the create request; may be {@code null}
     * @return a new, transient {@link User} populated with id and profile fields (password
     *         unset), or {@code null} when {@code req} is {@code null}
     */
    public User toEntity(UserCreateRequest req) {
        if (req == null) {
            return null;
        }
        User user = new User();
        user.setUserId(req.userId());
        user.setFirstName(req.firstName());
        user.setLastName(req.lastName());
        user.setUserType(req.userType());
        // password encoded & set by UserService (BCrypt-12); never set the raw value here.
        return user;
    }

    /**
     * Applies the editable profile fields of an update request onto an already-loaded
     * {@link User} entity (mirrors the {@code COUSR02C} user-maintenance flow).
     *
     * <p>Each field is applied only when its request value is non-{@code null}, making the
     * operation partial-update friendly even though {@link UserUpdateRequest} validation
     * normally requires every field to be present. The {@code userId} primary key is
     * <strong>never</strong> mutated: it is immutable and is supplied via the URL path,
     * and {@link UserUpdateRequest} carries no {@code userId} component.</p>
     *
     * <p><strong>Password handling:</strong> a requested password change is <em>not</em>
     * applied here. As on the create path, {@code UserService} performs BCrypt
     * (strength&nbsp;12) encoding and sets the hash; {@link User#setPassword(String)} is
     * never invoked by this mapper (AAP &sect;0.6.7).</p>
     *
     * <p>The call is a no-op when either argument is {@code null}.</p>
     *
     * @param req the update request carrying the new profile values; may be {@code null}
     * @param u   the managed entity to mutate in place; may be {@code null}
     */
    public void applyUpdate(UserUpdateRequest req, User u) {
        if (req == null || u == null) {
            return;
        }
        if (req.firstName() != null) {
            u.setFirstName(req.firstName());
        }
        if (req.lastName() != null) {
            u.setLastName(req.lastName());
        }
        if (req.userType() != null) {
            u.setUserType(req.userType());
        }
        // userId is the immutable, path-supplied primary key — never reassigned here.
        // password encoded & set by UserService (BCrypt-12); never set the raw value here.
    }
}
