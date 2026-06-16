package com.carddemo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for the admin user-update operation
 * ({@code PUT /users/{userId}} on {@code UserController}, admin-only).
 *
 * <p><strong>Legacy lineage.</strong> This DTO is the Java/Spring re-expression of the
 * CICS user-maintenance transaction {@code CU02}, implemented by COBOL program
 * {@code COUSR02C} over BMS screen {@code COUSR02} and the security-user record
 * {@code CSUSR01Y} (the {@code USRSEC} VSAM dataset). The original screen exposed five
 * fields &mdash; User&nbsp;ID, First&nbsp;Name, Last&nbsp;Name, Password and User&nbsp;Type
 * &mdash; of which only the latter four are editable. The User&nbsp;ID identifies the record
 * being modified and is therefore carried by the URL <em>path variable</em>
 * ({@code userId}); it is deliberately <strong>not</strong> part of this request body.</p>
 *
 * <p><strong>Why every field is mandatory.</strong> The legacy program does not perform a
 * partial (delta) update. In {@code UPDATE-USER-INFO}, {@code COUSR02C} re-validates that
 * each editable field is non-empty, pre-fills the screen with the existing record values,
 * then re-writes the <em>full</em> {@code SEC-USER-DATA} record. To preserve 100% functional
 * parity (AAP &sect;0.7.1), all four components below are required via {@link NotBlank};
 * a blank or absent value reproduces the legacy "... can NOT be empty..." rejections.</p>
 *
 * <p><strong>Field shapes</strong> mirror the source-of-truth widths from {@code COUSR02}
 * (BMS field-layout copybook) and {@code CSUSR01Y} (record copybook):</p>
 * <ul>
 *   <li>{@code firstName} &mdash; {@code FNAMEI}/{@code SEC-USR-FNAME} {@code PIC X(20)} &rarr; max 20 chars</li>
 *   <li>{@code lastName}  &mdash; {@code LNAMEI}/{@code SEC-USR-LNAME} {@code PIC X(20)} &rarr; max 20 chars</li>
 *   <li>{@code password}  &mdash; {@code PASSWDI}/{@code SEC-USR-PWD}  {@code PIC X(08)} &rarr; max 8 chars</li>
 *   <li>{@code userType}  &mdash; {@code USRTYPEI}/{@code SEC-USR-TYPE} {@code PIC X(01)} &rarr; single char, {@code 'A'} (admin) or {@code 'U'} (user)</li>
 * </ul>
 *
 * <p><strong>Security boundary.</strong> {@code password} is <em>input-only</em>: this DTO is
 * never used to serialize a response, and the value supplied here is BCrypt-hashed (strength 12)
 * by {@code UserService} before persistence (AAP &sect;0.6.7). The plaintext password is never
 * echoed back to the client and never logged. Hashing is intentionally <em>not</em> performed in
 * this DTO &mdash; this type is a pure, immutable validation-bearing transport object.</p>
 *
 * <p>Being a {@code record}, instances are immutable and expose canonical accessors
 * ({@link #firstName()}, {@link #lastName()}, {@link #password()}, {@link #userType()}).
 * Bean Validation constraints are evaluated when the controller annotates the bound parameter
 * with {@code @Valid}; failures are translated by the global exception handler into an
 * HTTP 400 response carrying the legacy message text.</p>
 *
 * @param firstName the user's given name; required, at most 20 characters
 *                  (legacy {@code FNAMEI} / {@code SEC-USR-FNAME}, {@code PIC X(20)})
 * @param lastName  the user's family name; required, at most 20 characters
 *                  (legacy {@code LNAMEI} / {@code SEC-USR-LNAME}, {@code PIC X(20)})
 * @param password  the new plaintext password; required, at most 8 characters; input-only and
 *                  BCrypt-hashed downstream (legacy {@code PASSWDI} / {@code SEC-USR-PWD}, {@code PIC X(08)})
 * @param userType  the role flag; required, exactly {@code 'A'} (admin) or {@code 'U'} (user)
 *                  (legacy {@code USRTYPEI} / {@code SEC-USR-TYPE}, {@code PIC X(01)})
 */
public record UserUpdateRequest(

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

    /**
     * Fixed-width mask substituted for the password in every textual rendering of this record.
     * A constant mask is used deliberately so that neither the password value nor its length is
     * ever disclosed.
     */
    private static final String PASSWORD_MASK = "********";

    /**
     * Returns a diagnostic-safe string representation of this request.
     *
     * <p>A {@code record}'s compiler-generated {@code toString()} renders every component, which
     * would include the plaintext {@code password} and could leak the credential into application
     * logs (for example via {@code log.debug("req={}", request)}). This override always replaces
     * the password with a fixed mask, honoring the rule that the password must never be logged or
     * echoed (AAP &sect;0.6.7, &sect;0.7.1); the non-sensitive fields are rendered as-is to retain
     * their diagnostic value.</p>
     *
     * @return a string representation in which {@code password} is replaced by a fixed mask
     */
    @Override
    public String toString() {
        return "UserUpdateRequest[firstName=" + firstName
                + ", lastName=" + lastName
                + ", password=" + PASSWORD_MASK
                + ", userType=" + userType
                + "]";
    }
}
