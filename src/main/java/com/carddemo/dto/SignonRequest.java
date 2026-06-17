package com.carddemo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Immutable JSON request body for the {@code POST /auth/signon} endpoint exposed by
 * {@code AuthController}.
 *
 * <p><strong>Lineage.</strong> This DTO is the Spring Boot re-expression of the legacy CICS
 * signon screen {@code COSGN00} (BMS field copybook {@code app/cpy-bms/COSGN00.CPY}) driven by the
 * online program {@code app/cbl/COSGN00C.cbl}. It captures exactly the two operator-entered fields
 * of the original 3270 signon panel:</p>
 * <ul>
 *   <li>{@code USERIDI PIC X(8)} &rarr; {@link #userId()} — the user identifier, at most
 *       8&nbsp;characters.</li>
 *   <li>{@code PASSWDI PIC X(8)} &rarr; {@link #password()} — the clear-text password as typed, at
 *       most 8&nbsp;characters.</li>
 * </ul>
 *
 * <p><strong>Validation parity.</strong> The original program rejected an empty user id or password
 * with the operator messages {@code "Please enter User ID ..."} and {@code "Please enter Password
 * ..."} respectively (paragraph {@code PROCESS-ENTER-KEY} of {@code COSGN00C}, which tested each
 * field for {@code SPACES OR LOW-VALUES}). Those messages are reproduced verbatim on the
 * {@link NotBlank} constraints so that the migrated REST API preserves message parity; together
 * with the {@link Size} bound that mirrors the {@code PIC X(8)} field width, they surface as
 * HTTP&nbsp;400 field errors through the global exception handler whenever a controller binds a
 * {@code @Valid @RequestBody SignonRequest}.</p>
 *
 * <p><strong>Scope boundary.</strong> This type is a pure input carrier and performs no business
 * logic. The upper-casing of the user id and the credential verification that {@code COSGN00C}
 * performed (its {@code FUNCTION UPPER-CASE} move and {@code READ-USER-SEC-FILE} lookup) are the
 * responsibility of the service/security tier ({@code AuthService} /
 * {@code CustomUserDetailsService}); the plain-text comparison is additionally hardened to a BCrypt
 * verification there. None of that belongs in this DTO, which only conveys raw input plus
 * field-shape validation.</p>
 *
 * <p><strong>Sensitivity.</strong> The {@code password} component is input-only: it is never placed
 * in any response DTO and must never be logged or echoed. To prevent accidental disclosure through
 * diagnostic logging or exception messages, {@link #toString()} is overridden to mask the password
 * — the record's implicit {@code toString()} would otherwise render the credential in clear text.</p>
 *
 * @param userId   the operator-supplied user identifier; required (non-blank) and at most
 *                 8&nbsp;characters, mirroring {@code USERIDI PIC X(8)}
 * @param password the operator-supplied clear-text password; required (non-blank) and at most
 *                 8&nbsp;characters, mirroring {@code PASSWDI PIC X(8)}
 */
public record SignonRequest(

        @NotBlank(message = "Please enter User ID ...")
        @Size(max = 8, message = "User ID must be at most 8 characters")
        String userId,

        @NotBlank(message = "Please enter Password ...")
        @Size(max = 8, message = "Password must be at most 8 characters")
        String password

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
     * <p>Unlike the record's implicit {@code toString()}, this override never includes the
     * {@code password} value — it is always replaced by a fixed mask — honoring the rule that the
     * credential must never be logged or echoed. The {@code userId} is a non-secret identifier and
     * is rendered as-is to retain its diagnostic value.</p>
     *
     * @return a string containing the user id and a masked password, never the real password
     */
    @Override
    public String toString() {
        return "SignonRequest[userId=" + userId + ", password=" + PASSWORD_MASK + "]";
    }
}
