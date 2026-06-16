package com.carddemo.service;

import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.dto.PageResponse;
import com.carddemo.dto.UserCreateRequest;
import com.carddemo.dto.UserResponse;
import com.carddemo.dto.UserUpdateRequest;
import com.carddemo.entity.User;
import com.carddemo.exception.BusinessRuleException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.mapper.UserMapper;
import com.carddemo.repository.UserRepository;
import com.carddemo.util.CardDemoConstants;

/**
 * Application service encapsulating the administrative user-management business operations of the
 * AWS CardDemo application: the paginated user list, the single-user detail view, and the
 * create / update / delete maintenance flows.
 *
 * <h2>Legacy lineage &mdash; the four CICS online programs this service replaces</h2>
 * <p>This {@code @Service} is the layered-monolith re-expression of the four admin-only CICS
 * pseudo-conversational online programs whose presentation tier (the BMS 3270 screens) has been
 * retired in favour of a stateless REST/JSON contract. Each public method ports exactly one
 * program's business logic (AAP &sect;0.3.2, &sect;0.4.1.3 &mdash; UserService row:
 * "Admin-only CRUD; pagination"):</p>
 * <table border="1">
 *   <caption>COBOL online program &rarr; service operation</caption>
 *   <tr><th>Legacy program</th><th>Function</th><th>Service method</th></tr>
 *   <tr><td>{@code app/cbl/COUSR00C.cbl}</td>
 *       <td>List Users &mdash; browses the {@code USRSEC} file, a fixed 7 rows per screen
 *           (transaction {@code CU00}; PF7 backward / PF8 forward paging)</td>
 *       <td>{@link #listUsers(int)}</td></tr>
 *   <tr><td>{@code app/cbl/COUSR01C.cbl}</td>
 *       <td>Add User &mdash; validates the five input fields, then {@code WRITE}s a new
 *           {@code SEC-USER-DATA} record; a duplicate key ({@code DUPKEY}/{@code DUPREC}) is
 *           rejected with "User ID already exist..." (transaction {@code CU01})</td>
 *       <td>{@link #createUser(UserCreateRequest)} (single-user detail via {@link #getUser(String)})</td></tr>
 *   <tr><td>{@code app/cbl/COUSR02C.cbl}</td>
 *       <td>Update User &mdash; reads the record by id, performs field-by-field change detection,
 *           then {@code REWRITE}s it (transaction {@code CU02})</td>
 *       <td>{@link #updateUser(String, UserUpdateRequest)}</td></tr>
 *   <tr><td>{@code app/cbl/COUSR03C.cbl}</td>
 *       <td>Delete User &mdash; reads the record by id, then {@code DELETE}s it
 *           (transaction {@code CU03})</td>
 *       <td>{@link #deleteUser(String)}</td></tr>
 * </table>
 * <p>The underlying record layout for all four is the COBOL {@code SEC-USER-DATA} of copybook
 * {@code app/cpy/CSUSR01Y.cpy} (record length 80): {@code SEC-USR-ID X(08)},
 * {@code SEC-USR-FNAME X(20)}, {@code SEC-USR-LNAME X(20)}, {@code SEC-USR-PWD X(08)} and
 * {@code SEC-USR-TYPE X(01)}.</p>
 *
 * <h2>Position in the layered architecture</h2>
 * <p>Strict Controller &rarr; Service &rarr; Repository &rarr; Entity flow (AAP &sect;0.3.2). This
 * service holds the business logic; it delegates all persistence to {@link UserRepository} (the
 * relational replacement for the VSAM {@code USRSEC} KSDS) and all entity&harr;DTO translation to
 * {@link UserMapper}. It exposes only DTOs ({@link UserResponse}, {@link PageResponse}) to its
 * callers and never leaks a {@link User} JPA entity across the service boundary. Collaborators are
 * supplied exclusively through <strong>constructor injection</strong>, which replaces the static
 * {@code CALL}/{@code XCTL} linkage of the COBOL programs and keeps the class trivially
 * unit-testable with mocks.</p>
 *
 * <h2>Authorization &mdash; admin-only (defense in depth)</h2>
 * <p>Every operation on this service corresponds to an admin-only CICS transaction reached through
 * the legacy administrator menu ({@code COADM01C}). In the migrated system that restriction is
 * enforced declaratively at the web tier &mdash; {@code @PreAuthorize("hasRole('ADMIN')")} on
 * {@code UserController} and an {@code /users/**} &rarr; {@code hasRole('ADMIN')} rule in
 * {@code com.carddemo.security.SecurityConfig}. This service deliberately does <em>not</em>
 * re-check the caller's role: the security decision lives in a single, auditable place, and
 * duplicating it here would risk drift. The class is documented as admin-only so callers are aware
 * of the surrounding contract.</p>
 *
 * <h2>Credential parity &mdash; uppercase id and password (AAP &sect;0.6.7)</h2>
 * <p>The legacy programs stored the five fields exactly as typed. The sign-on program
 * {@code COSGN00C}, however, UPPER-CASES both the entered user id and the entered password before
 * comparing them, and the migrated {@code CustomUserDetailsService} likewise upper-cases the lookup
 * id before {@code findById}. To guarantee that a user created here can subsequently authenticate
 * &mdash; and to keep the {@code users} table internally consistent &mdash; this service:</p>
 * <ul>
 *   <li>stores the {@code userId} primary key in <strong>upper case</strong>; and</li>
 *   <li>BCrypt-encodes the <strong>upper-cased</strong> password (strength 12, via the injected
 *       {@link PasswordEncoder}).</li>
 * </ul>
 * <p>Consequently the id is upper-cased before every {@code existsById}/{@code findById}/
 * {@code deleteById} so user ids are treated case-insensitively throughout, exactly matching the
 * effective (upper-cased) login semantics of the legacy system.</p>
 *
 * <h2>Transaction semantics</h2>
 * <p>The class is annotated {@link Transactional} so every public method runs inside a transaction
 * boundary. The two read operations ({@link #listUsers(int)}, {@link #getUser(String)}) additionally
 * declare {@code @Transactional(readOnly = true)}, allowing the persistence provider to optimise for
 * read-only access; the three mutating operations inherit the class-level read-write boundary so
 * each load-mutate-save (or delete) unit commits atomically, mirroring the legacy
 * {@code WRITE}/{@code READ}&hellip;{@code REWRITE}/{@code DELETE} file operations.</p>
 *
 * <h2>Cross-cutting MUST rules (AAP &sect;0.6.8 / &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>Password / hash suppression.</strong> The BCrypt hash is persisted on the
 *       {@link User} entity but MUST NEVER be serialized, returned, or logged. This service enforces
 *       that <em>structurally</em>: {@link UserResponse} declares no password component,
 *       {@link UserMapper#toResponse(User)} never reads the password accessor, and this class never
 *       logs the request, the entity, or the raw/encoded credential. "You cannot leak a field you
 *       never emit."</li>
 *   <li><strong>User-id immutability.</strong> The 8-character {@code userId} is the application
 *       primary key. It is assigned exactly once, on creation; the update path never reassigns it
 *       ({@link UserUpdateRequest} carries no id and is sourced from the URL path variable).</li>
 *   <li><strong>Fixed page size of 7.</strong> The legacy 3270 user-list browse renders exactly 7
 *       rows per screen. {@link #listUsers(int)} sources that value from
 *       {@link CardDemoConstants#PAGE_SIZE} (== 7); it is a parity constant and is never made
 *       configurable.</li>
 * </ul>
 *
 * <p>This service is stateless and therefore thread-safe: its only fields are the three injected,
 * immutable singleton collaborators.</p>
 *
 * @see UserRepository
 * @see UserMapper
 * @see UserResponse
 * @see UserCreateRequest
 * @see UserUpdateRequest
 * @see PageResponse
 * @see ResourceNotFoundException
 * @see BusinessRuleException
 * @see CardDemoConstants#PAGE_SIZE
 * @see <a href="file:app/cbl/COUSR00C.cbl">app/cbl/COUSR00C.cbl (list source-of-truth)</a>
 * @see <a href="file:app/cbl/COUSR01C.cbl">app/cbl/COUSR01C.cbl (add source-of-truth)</a>
 * @see <a href="file:app/cbl/COUSR02C.cbl">app/cbl/COUSR02C.cbl (update source-of-truth)</a>
 * @see <a href="file:app/cbl/COUSR03C.cbl">app/cbl/COUSR03C.cbl (delete source-of-truth)</a>
 */
@Service
@Transactional
public class UserService {

    /**
     * Spring Data repository for the {@code users} table &mdash; the relational replacement for the
     * VSAM {@code USRSEC} KSDS (key {@code SEC-USR-ID}). Supplies the primary-key access
     * ({@code findById}/{@code existsById}/{@code save}/{@code deleteById}) and the paginated browse
     * ({@code findAll(Pageable)}). Immutable, injected once at construction.
     */
    private final UserRepository userRepository;

    /**
     * Stateless entity&harr;DTO mapper. Produces the credential-free {@link UserResponse} projection
     * and the {@link PageResponse} list envelope, builds a transient {@link User} from a create
     * request, and applies the editable profile fields of an update request. Never reads or writes
     * the password &mdash; that is this service's responsibility. Immutable, injected once at
     * construction.
     */
    private final UserMapper userMapper;

    /**
     * The application's single {@link PasswordEncoder}: the {@code BCryptPasswordEncoder} at
     * strength 12 declared as a {@code @Bean} in {@code com.carddemo.security.SecurityConfig}.
     * Injected by its interface type so this service depends only on the abstraction. Used to hash
     * the (upper-cased) password on create and on a requested password change during update; the
     * strength must match the {@code V4__seed_users.sql} seed hashes so seeded and created users
     * verify identically. Immutable, injected once at construction.
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * Creates a {@code UserService} with its required collaborators.
     *
     * <p>Constructor injection (the idiomatic replacement for COBOL {@code CALL}/{@code XCTL} static
     * linkage) makes all three dependencies {@code final} and mandatory, and keeps the service
     * trivially testable with mock collaborators. Spring autowires the single constructor without an
     * explicit {@code @Autowired} annotation.</p>
     *
     * @param userRepository  the user persistence gateway; must not be {@code null}
     * @param userMapper      the entity&harr;DTO mapper; must not be {@code null}
     * @param passwordEncoder the BCrypt (strength 12) password encoder; must not be {@code null}
     */
    public UserService(UserRepository userRepository,
                       UserMapper userMapper,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Returns one page of users, reproducing the "List Users" browse of {@code COUSR00C}.
     *
     * <p><strong>Legacy behaviour (COUSR00C).</strong> The original program sequentially browsed the
     * {@code USRSEC} file and rendered a fixed window of rows per screen, with PF7 paging backward and
     * PF8 paging forward. That fixed window is preserved here: the page is requested as
     * {@code PageRequest.of(page, CardDemoConstants.PAGE_SIZE)} where {@link CardDemoConstants#PAGE_SIZE}
     * is exactly {@code 7} (AAP &sect;0.6.4 / &sect;0.7.1), so the returned {@link PageResponse} carries
     * the same 7-row window plus the {@code totalElements}/{@code first}/{@code last} metadata that
     * reproduces the COBOL forward/backward paging indicators.</p>
     *
     * <p>Each row is a credential-free {@link UserResponse}; the password hash is never read or
     * emitted (see {@link UserMapper#toResponse(User)}).</p>
     *
     * @param page the zero-based page index to retrieve; must be {@code >= 0}
     *             ({@link PageRequest#of(int, int)} rejects a negative index with
     *             {@link IllegalArgumentException})
     * @return an immutable {@link PageResponse} of {@link UserResponse} rows whose {@code size} is 7,
     *         never {@code null} (possibly with empty {@code content})
     * @throws IllegalArgumentException if {@code page} is negative
     */
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> listUsers(int page) {
        // Fixed legacy screen window of 7 rows (COUSR00C); sourced from the single parity constant
        // rather than a magic literal (AAP 0.6.4 / 0.7.1).
        Page<User> result = userRepository.findAll(PageRequest.of(page, CardDemoConstants.PAGE_SIZE));

        // Delegate the page-and-element conversion to the mapper, which emits credential-free
        // UserResponse rows and preserves every pagination attribute (page, size==7, totalElements,
        // first, last) — reproducing COUSR00C's PF7/PF8 paging indicators.
        return userMapper.toPageResponse(result);
    }

    /**
     * Returns the detail of a single user by id, the read used to back the user-detail view and to
     * pre-fill the legacy maintenance screens ({@code COUSR02C}/{@code COUSR03C} keyed {@code READ}).
     *
     * <p><strong>Case-insensitive lookup (AAP &sect;0.6.7).</strong> The id is upper-cased (with
     * {@link Locale#ROOT}, matching {@code CustomUserDetailsService}) before the primary-key lookup so
     * a user is found regardless of the casing supplied by the caller, consistent with the sign-on
     * path. A missing record is signalled by a {@link ResourceNotFoundException}, which
     * {@code GlobalExceptionHandler} translates into <em>HTTP&nbsp;404&nbsp;Not&nbsp;Found</em>.</p>
     *
     * <p>The result is produced by {@link UserMapper#toResponse(User)}, which never reads the password
     * accessor; the {@link UserResponse} contract declares no password component, so the credential can
     * never reach the client.</p>
     *
     * @param userId the user identifier to look up (case-insensitive); must not be {@code null}
     * @return the {@link UserResponse} detail projection for the user (never {@code null},
     *         credential-free)
     * @throws ResourceNotFoundException if no user exists with the given id (&rarr; HTTP 404)
     */
    @Transactional(readOnly = true)
    public UserResponse getUser(String userId) {
        // Primary-key read (the relational analogue of COUSR02C/COUSR03C's keyed READ of USRSEC),
        // trimming and upper-casing the id for case-insensitive, sign-on-consistent access (AAP 0.6.7)
        // — the identical normalization applied by AuthService.signon and CustomUserDetailsService.
        // Absence -> 404 via GlobalExceptionHandler. The original userId (not the normalized form) is
        // echoed in the not-found message so the caller sees exactly what they requested.
        User user = userRepository.findById(normalizeUserId(userId))
                .orElseThrow(() -> ResourceNotFoundException.of("User", userId));

        // Map to the credential-free response DTO; the mapper never touches the password hash.
        return userMapper.toResponse(user);
    }

    /**
     * Creates a new user, reproducing the "Add User" flow of {@code COUSR01C}.
     *
     * <p><strong>Legacy behaviour (COUSR01C).</strong> The original program validated that the five
     * input fields (First Name, Last Name, User ID, Password, User Type) were non-empty, mapped them
     * onto {@code SEC-USER-DATA}, then {@code WRITE}s the record keyed by {@code SEC-USR-ID}. A
     * duplicate key ({@code DUPKEY}/{@code DUPREC}) was rejected with "User ID already exist...". The
     * five non-empty checks are enforced upstream by the {@code @NotBlank}/{@code @Size}/{@code @Pattern}
     * constraints on {@link UserCreateRequest} at the controller boundary (preserving the legacy
     * "... can NOT be empty..." messages), so they are not repeated defensively here.</p>
     *
     * <p><strong>Credential parity (AAP &sect;0.6.7).</strong> The {@code userId} primary key is stored
     * <em>trimmed and upper-cased</em> (the same normalization applied by {@code AuthService.signon} and
     * {@code CustomUserDetailsService}), while the password is BCrypt-encoded (strength 12) <em>after</em>
     * being upper-cased only &mdash; the password is deliberately <strong>not</strong> trimmed, matching
     * {@code AuthService.signon}, which upper-cases the password but does not trim it, so embedded/leading/
     * trailing spaces remain significant. Both transformations use {@link Locale#ROOT}, so the new user can
     * authenticate through the sign-on path regardless of the JVM default locale. The raw password is never
     * logged and is discarded once the hash is computed; the entity is built by
     * {@link UserMapper#toEntity(UserCreateRequest)}, which never copies the raw password.</p>
     *
     * @param request the validated create request carrying the id, profile fields, plaintext password,
     *                and user type; must not be {@code null}
     * @return the credential-free {@link UserResponse} for the newly created user (never {@code null})
     * @throws BusinessRuleException if a user already exists with the (upper-cased) id, carrying the
     *                               legacy {@code "User ID already exist..."} message (&rarr; HTTP 400)
     */
    public UserResponse createUser(UserCreateRequest request) {
        // Parity normalization (AAP 0.6.7): the id is the natural key and is stored trimmed + upper-cased
        // so it matches the sign-on lookup (AuthService.signon and CustomUserDetailsService both
        // trim().toUpperCase(Locale.ROOT) before findById). Trimming here closes the gap where a
        // whitespace-padded id could be persisted but be unreachable at sign-on. Field-level
        // "... can NOT be empty..." validation is enforced upstream by the @NotBlank/@Size/@Pattern
        // constraints on UserCreateRequest at the controller boundary.
        String userId = normalizeUserId(request.userId());

        // COUSR01C duplicate-key guard: a WRITE that hit DUPKEY/DUPREC was rejected with
        // "User ID already exist...". Here an existing primary key surfaces the same message as a
        // BusinessRuleException -> HTTP 400. (Deliberately NOT ConcurrentModificationException, which
        // is reserved for optimistic-lock 409 conflicts.)
        if (userRepository.existsById(userId)) {
            throw new BusinessRuleException(MessageService.USER_ALREADY_EXISTS);
        }

        // Build the entity from the request (id + profile fields; the mapper never sets the password),
        // then overwrite the id with its trimmed + upper-cased form and set the BCrypt hash of the
        // upper-cased password (strength 12). The password is upper-cased ONLY (via toUpperCaseRoot, no
        // trim) so it stays byte-for-byte consistent with AuthService.signon, which upper-cases but does
        // not trim the password — this keeps create consistent with the sign-on path so the new user can
        // subsequently authenticate.
        User user = userMapper.toEntity(request);
        user.setUserId(userId);
        user.setPassword(passwordEncoder.encode(toUpperCaseRoot(request.password())));

        // WRITE-USER-SEC-FILE: persist the new record (COUSR01C NORMAL path).
        userRepository.save(user);

        // Return the credential-free projection; the controller may build the
        // "User <id> has been added ..." confirmation via MessageService.userAdded(userId).
        return userMapper.toResponse(user);
    }

    /**
     * Updates an existing user, reproducing the "Update User" flow of {@code COUSR02C}.
     *
     * <p><strong>Legacy behaviour (COUSR02C).</strong> The original program read the record by
     * {@code SEC-USR-ID} (rejecting a missing record with "User ID NOT found..."), performed
     * field-by-field change detection against the existing values, and &mdash; if any field changed
     * &mdash; {@code REWRITE}s the full record. The id itself is never editable.</p>
     *
     * <p><strong>Java translation.</strong> The user is loaded by primary key (404 if absent, with the
     * id trimmed and upper-cased for case-insensitive, sign-on-consistent access per AAP &sect;0.6.7), the editable
     * profile fields (first name, last name, user type) are applied by
     * {@link UserMapper#applyUpdate(UserUpdateRequest, User)}, and the managed entity is persisted with
     * {@link UserRepository#save(Object) save}. The whole sequence runs in the class-level read-write
     * transaction so the load-mutate-save unit commits atomically, mirroring the legacy
     * {@code READ}&hellip;{@code REWRITE} guard.</p>
     *
     * <p><strong>Password change detection (AAP &sect;0.6.7).</strong> {@code COUSR02C} rewrites the
     * password only when {@code PASSWDI} differs from the stored value. Here a new password is applied
     * only when one is supplied (non-{@code null} and non-blank); it is then BCrypt-encoded
     * (strength 12) after being upper-cased with {@link Locale#ROOT} for sign-on consistency. A
     * {@code null}/blank password leaves the existing hash untouched. The id and the password hash are
     * never reassigned by the mapper &mdash; password handling is exclusively this service's
     * responsibility.</p>
     *
     * @param userId  the identifier of the user to update (case-insensitive); must not be {@code null}
     * @param request the validated update request carrying the new profile fields, plaintext password,
     *                and user type; must not be {@code null}
     * @return the credential-free {@link UserResponse} reflecting the updated user (never {@code null})
     * @throws ResourceNotFoundException if no user exists with the given id (&rarr; HTTP 404)
     */
    public UserResponse updateUser(String userId, UserUpdateRequest request) {
        // COUSR02C keyed READ of USRSEC; a missing record was rejected with "User ID NOT found...".
        // The id is trimmed and upper-cased for case-insensitive, sign-on-consistent lookup (AAP 0.6.7),
        // matching AuthService.signon and CustomUserDetailsService; absence -> 404 via
        // GlobalExceptionHandler. The original userId is echoed in the not-found message.
        User user = userRepository.findById(normalizeUserId(userId))
                .orElseThrow(() -> ResourceNotFoundException.of("User", userId));

        // Apply the editable profile fields (firstName/lastName/userType). The mapper never touches the
        // id (immutable, path-supplied) or the password — this mirrors COUSR02C applying the changed
        // fields onto the record it read for update.
        userMapper.applyUpdate(request, user);

        // Password change handling (COUSR02C compares PASSWDI to SEC-USR-PWD and rewrites on a
        // difference): apply a new password only when one was supplied. The password is upper-cased
        // ONLY (via toUpperCaseRoot, no trim) and BCrypt-encoded (strength 12), staying byte-for-byte
        // consistent with AuthService.signon (which upper-cases but does not trim the password) for
        // sign-on consistency; a null/blank password leaves the existing hash untouched.
        // (UserUpdateRequest validation normally requires a non-blank password, so this guard is the
        // defensive lower bound for direct/non-validated callers.)
        String newPassword = request.password();
        if (newPassword != null && !newPassword.isBlank()) {
            user.setPassword(passwordEncoder.encode(toUpperCaseRoot(newPassword)));
        }

        // UPDATE-USER-SEC-FILE (REWRITE): persist the mutated record. JPA dirty-checking would flush
        // the managed entity automatically; an explicit save makes the write intent unambiguous.
        userRepository.save(user);

        // Return the credential-free projection; the controller may build the
        // "User <id> has been updated ..." confirmation via MessageService.userUpdated(userId).
        return userMapper.toResponse(user);
    }

    /**
     * Deletes a user, reproducing the "Delete User" flow of {@code COUSR03C}.
     *
     * <p><strong>Legacy behaviour (COUSR03C).</strong> The original program read the record by
     * {@code SEC-USR-ID} (rejecting a missing record with "User ID NOT found...") and then
     * {@code DELETE}s it. The existence check is reproduced here so that deleting an unknown id
     * surfaces as <em>HTTP&nbsp;404&nbsp;Not&nbsp;Found</em> rather than a silent no-op, faithfully
     * preserving the legacy not-found path.</p>
     *
     * <p>The id is trimmed and upper-cased (with {@link Locale#ROOT}) for case-insensitive,
     * sign-on-consistent access (AAP &sect;0.6.7) &mdash; the same normalization applied by
     * {@code AuthService.signon} and {@code CustomUserDetailsService}. The whole operation runs in the
     * class-level read-write transaction.</p>
     *
     * @param userId the identifier of the user to delete (case-insensitive); must not be {@code null}
     * @throws ResourceNotFoundException if no user exists with the given id (&rarr; HTTP 404)
     */
    public void deleteUser(String userId) {
        // Trim and upper-case the id for case-insensitive, sign-on-consistent access (AAP 0.6.7),
        // matching AuthService.signon and CustomUserDetailsService.
        String id = normalizeUserId(userId);

        // COUSR03C keyed READ before DELETE; a missing record was rejected with "User ID NOT found...".
        // Existence is checked first so a delete of an unknown id surfaces as 404 (not a silent no-op),
        // reproducing the legacy not-found path. The original userId is echoed in the not-found message.
        if (!userRepository.existsById(id)) {
            throw ResourceNotFoundException.of("User", userId);
        }

        // DELETE-USER-SEC-FILE: remove the record (COUSR03C NORMAL path). The controller may build the
        // "User <id> has been deleted ..." confirmation via MessageService.userDeleted(userId).
        userRepository.deleteById(id);
    }

    /**
     * Normalizes a user id by <em>trimming</em> leading/trailing whitespace and then upper-casing with
     * {@link Locale#ROOT} &mdash; the canonical, locale-independent user-id transformation shared with
     * the authentication paths {@code com.carddemo.service.AuthService#signon} and
     * {@code com.carddemo.security.CustomUserDetailsService#loadUserByUsername}, both of which apply
     * {@code trim().toUpperCase(Locale.ROOT)} before the keyed {@code findById}.
     *
     * <p><strong>Why trim AND upper-case.</strong> The user id is the natural primary key. Persisting an
     * id that has not been trimmed (e.g. {@code "user0001 "}) would store a row that the sign-on lookup
     * &mdash; which <em>does</em> trim &mdash; can never reach, leaving the account unreachable at
     * authentication. Applying the identical {@code trim().toUpperCase(Locale.ROOT)} here on every id
     * path (create/update/delete/get) guarantees a user created or modified by this service can
     * subsequently authenticate (AAP &sect;0.6.7).</p>
     *
     * <p><strong>Why {@link Locale#ROOT}.</strong> {@link Locale#ROOT} is used deliberately instead of
     * the no-arg {@link String#toUpperCase()} so the result never varies with the JVM's default locale
     * &mdash; most notably the Turkish dotted-I rule, under which {@code "admin".toUpperCase()} would
     * yield {@code "ADM\u0130N"} and fail to match the {@code "ADMIN"} produced by the sign-on path.</p>
     *
     * @param value the user id to normalize; may be {@code null}
     * @return the trimmed, upper-cased id, or {@code null} if {@code value} was {@code null}
     */
    private static String normalizeUserId(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Upper-cases the supplied <em>password</em> using {@link Locale#ROOT} <strong>without trimming</strong>.
     *
     * <p>This is the credential transformation, used exclusively on the password before BCrypt encoding
     * in {@link #createUser(UserCreateRequest)} and {@link #updateUser(String, UserUpdateRequest)}. It is
     * intentionally distinct from {@link #normalizeUserId(String)}: it must mirror
     * {@code com.carddemo.service.AuthService#signon}, which upper-cases the password with
     * {@code toUpperCase(Locale.ROOT)} but <em>does not</em> trim it. Trimming the password here would
     * change the bytes fed to BCrypt relative to the sign-on path, so a password with leading/trailing
     * spaces would no longer authenticate &mdash; therefore the password is upper-cased only.</p>
     *
     * <p>{@link Locale#ROOT} is used for the same locale-stability reason described on
     * {@link #normalizeUserId(String)} (the Turkish dotted-I rule), keeping the stored credential hash
     * consistent with the sign-on transformation regardless of the JVM default locale (AAP &sect;0.6.7).</p>
     *
     * @param value the password to upper-case; may be {@code null}
     * @return the upper-cased value, or {@code null} if {@code value} was {@code null}
     */
    private static String toUpperCaseRoot(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }
}
