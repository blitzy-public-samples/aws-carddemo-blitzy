package com.carddemo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Immutable JSON request body for administrative user creation.
 *
 * <p>This DTO is the REST/JSON re-expression of the legacy 3270 user-add screen and its
 * backing online program. It is consumed by {@code UserController}'s create endpoint
 * ({@code POST /users}), which is guarded by {@code @PreAuthorize("hasRole('ADMIN')")} so
 * that only administrators may register new users (AAP &sect;0.4.1.4, UserController &larr;
 * {@code COUSR01C} + {@code COUSR01.bms}).</p>
 *
 * <h2>Source-of-truth lineage</h2>
 * <p>The field set, lengths, and validation messages are ported verbatim from the mainframe
 * artifacts so that behavior remains at 100% functional parity (AAP &sect;0.7.1):</p>
 * <ul>
 *   <li>BMS user-add map {@code COUSR01} ({@code app/cpy-bms/COUSR01.CPY}) &mdash; defines the
 *       on-screen input fields {@code FNAMEI X(20)}, {@code LNAMEI X(20)}, {@code USERIDI X(8)},
 *       {@code PASSWDI X(8)}, {@code USRTYPEI X(1)}.</li>
 *   <li>Online program {@code COUSR01C} ({@code app/cbl/COUSR01C.cbl}) &mdash; performs the
 *       non-empty checks whose messages are preserved here, then maps the inputs onto
 *       {@code SEC-USR-*} before writing the user record.</li>
 *   <li>Record copybook {@code CSUSR01Y} ({@code app/cpy/CSUSR01Y.cpy}) &mdash; the 80-byte
 *       {@code SEC-USER-DATA} layout that fixes the maximum field lengths
 *       ({@code SEC-USR-ID X(08)}, {@code SEC-USR-FNAME X(20)}, {@code SEC-USR-LNAME X(20)},
 *       {@code SEC-USR-PWD X(08)}, {@code SEC-USR-TYPE X(01)}).</li>
 * </ul>
 *
 * <h2>Validation</h2>
 * <p>Bean Validation constraints (Jakarta Validation) reproduce the legacy field checks. When the
 * controller binds the body with {@code @Valid @RequestBody}, a constraint violation is translated
 * by the global exception handler into an HTTP 400 response carrying these field-level messages.
 * The legacy "{@code ... can NOT be empty...}" wording (including the trailing ellipsis) is kept
 * intact for parity with {@code COUSR01C}'s {@code PROCESS-ENTER-KEY} paragraph.</p>
 *
 * <h2>Security contract</h2>
 * <p>{@code password} carries the <strong>plaintext</strong> secret exactly as typed by the
 * administrator. This DTO never hashes or transforms it; the service layer
 * ({@code UserService}) is solely responsible for encoding it with BCrypt (strength 12) before
 * persistence (AAP &sect;0.6.7). This type is <strong>request-only</strong>: it is never returned
 * from any endpoint, and the password is never serialized back to a client (the read side is
 * served by a separate response DTO). {@code userType} is constrained to {@code "A"} (admin) or
 * {@code "U"} (user) and is persisted as {@code SEC-USR-TYPE}.</p>
 *
 * @param userId    the unique 8-character user identifier (maps to {@code SEC-USR-ID})
 * @param firstName the user's given name, up to 20 characters (maps to {@code SEC-USR-FNAME})
 * @param lastName  the user's family name, up to 20 characters (maps to {@code SEC-USR-LNAME})
 * @param password  the plaintext password, up to 8 characters, hashed downstream by the service
 *                  layer (maps to {@code SEC-USR-PWD})
 * @param userType  the account role, {@code "A"} for admin or {@code "U"} for regular user
 *                  (maps to {@code SEC-USR-TYPE})
 */
public record UserCreateRequest(

        @NotBlank(message = "User ID can NOT be empty...")
        @Size(max = 8, message = "User ID must be at most 8 characters")
        String userId,

        @NotBlank(message = "First Name can NOT be empty...")
        @Size(max = 20, message = "First Name must be at most 20 characters")
        String firstName,

        @NotBlank(message = "Last Name can NOT be empty...")
        @Size(max = 20, message = "Last Name must be at most 20 characters")
        String lastName,

        @NotBlank(message = "Password can NOT be empty...")
        @Size(max = 8, message = "Password must be at most 8 characters")
        String password,

        @NotBlank(message = "User Type can NOT be empty...")
        @Pattern(regexp = "[AU]", message = "User Type must be 'A' (admin) or 'U' (user)")
        String userType

) {
}
