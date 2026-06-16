package com.carddemo.dto;

/**
 * Immutable JSON response projection for a single security user.
 *
 * <p>This DTO is the canonical, credential-free representation of a CardDemo
 * application user. It serves two endpoints exposed by {@code UserController}
 * (the Java migration of the legacy admin-only online programs
 * {@code COUSR00C}&ndash;{@code COUSR03C}):</p>
 * <ul>
 *   <li>{@code GET /users/{id}} &mdash; the single-user view response, and</li>
 *   <li>{@code GET /users} &mdash; each row of the paginated user list,
 *       returned wrapped in {@code PageResponse&lt;UserResponse&gt;}.</li>
 * </ul>
 *
 * <p>The same DTO is intentionally reused for both the detail view and the
 * list row because the legacy 3270 user-list screen {@code COUSR00}
 * (copybook {@code COUSR00.CPY}) displays exactly these four attributes per
 * row &mdash; user id, first name, last name and user type &mdash; so no
 * separate list-item type is required.</p>
 *
 * <h2>Source-of-truth lineage</h2>
 * <p>The field shapes are derived from the security-user record copybook
 * {@code CSUSR01Y.cpy} ({@code 01 SEC-USER-DATA}):</p>
 * <pre>
 *   05 SEC-USR-ID     PIC X(08)  -&gt; userId
 *   05 SEC-USR-FNAME  PIC X(20)  -&gt; firstName
 *   05 SEC-USR-LNAME  PIC X(20)  -&gt; lastName
 *   05 SEC-USR-PWD    PIC X(08)  -&gt; DELIBERATELY EXCLUDED (see PII note)
 *   05 SEC-USR-TYPE   PIC X(01)  -&gt; userType ("A"=admin, "U"=regular user)
 * </pre>
 *
 * <h2>PII / security note</h2>
 * <p>By design this projection carries <strong>no credential of any kind</strong>.
 * The COBOL {@code SEC-USR-PWD} field is intentionally dropped so that a user's
 * password (now stored as a BCrypt hash) can never be serialized into an API
 * response or written to a log. This honors the PII-suppression mandate of the
 * migration (passwords are never returned). Do not add a password, hash, or any
 * other secret field to this record.</p>
 *
 * <p>Implemented as a Java&nbsp;17 {@code record}, which provides immutable
 * components, value-based {@code equals}/{@code hashCode}, and a descriptive
 * {@code toString}. Jackson serializes the record by its component names,
 * producing JSON of the form:</p>
 * <pre>
 *   {"userId":"ADMIN001","firstName":"Admin","lastName":"User","userType":"A"}
 * </pre>
 *
 * @param userId    the unique user identifier, up to 8 characters
 *                  (from {@code SEC-USR-ID PIC X(08)}); never contains a credential
 * @param firstName the user's first/given name, up to 20 characters
 *                  (from {@code SEC-USR-FNAME PIC X(20)})
 * @param lastName  the user's last/family name, up to 20 characters
 *                  (from {@code SEC-USR-LNAME PIC X(20)})
 * @param userType  the single-character user role
 *                  (from {@code SEC-USR-TYPE PIC X(01)}):
 *                  {@code "A"} for administrator, {@code "U"} for a regular user
 */
public record UserResponse(
        String userId,
        String firstName,
        String lastName,
        String userType
) {
}
