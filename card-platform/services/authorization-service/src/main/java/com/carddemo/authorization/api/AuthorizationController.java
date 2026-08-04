package com.carddemo.authorization.api;

import com.carddemo.authorization.domain.AuthorizationService;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.DeclineReason;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one synchronous surface of the platform.
 *
 * <p>One endpoint replaces one Customer Information Control System (CICS) transaction. The online
 * capture program {@code app/cbl/COTRN02C.cbl} runs under transaction {@code CT02}, sends a mapset,
 * receives it back and re-sends it on every failure. This endpoint takes one request body and returns
 * one response body, so no conversation state survives the call.
 *
 * <p>Two status codes carry the two outcomes, and the distinction is the point.
 *
 * <p>An approval answers {@code 200} with an {@link AuthorizationResponse} whose {@code approved}
 * component reads {@code true}.
 *
 * <p>A decline also answers {@code 200}, carrying {@code approved} {@code false} plus the reject code
 * and its text. A decline is a decision the service took, not a failure of the call.
 * {@code app/cbl/CBTRN02C.cbl:L229-L230} treats a rejection the same way: it ends the batch job with
 * return code 4, which is the source's own word for a normal run that rejected records.
 *
 * <p>A request whose fields fail validation answers {@code 422} with an {@link ApiErrorResponse}
 * carrying the verbatim texts {@code app/cbl/COTRN02C.cbl} emits. Nothing was decided, so no reject
 * code applies.
 *
 * <p>No path variable and no query parameter carries a card number. The card number travels in the
 * request body, so it reaches no access log through a request line. The response carries a reject code
 * and an account identifier and never a card number, masked or otherwise.
 *
 * <p>{@code src/main/resources/openapi.yaml} describes this endpoint, written by hand so no
 * documentation generator joins the classpath.
 */
@RestController
@RequestMapping("/authorizations")
public class AuthorizationController {

    /** Decides one call and writes its event. */
    private final AuthorizationService authorizations;

    /**
     * Takes the service that decides one call.
     *
     * @param authorizations the decision service
     */
    public AuthorizationController(AuthorizationService authorizations) {
        this.authorizations = authorizations;
    }

    /**
     * Authorizes one transaction and publishes exactly one event for it.
     *
     * <p>The decision, its event row and, for an unresolved card, its attempt record commit in one
     * local transaction inside the service. Publication happens afterwards, from the outbox, so this
     * method never waits for a broker.
     *
     * @param request the transaction to authorize, validated before this method runs
     * @return {@code 200} carrying the decision, whether it approves or declines
     */
    @PostMapping
    public ResponseEntity<AuthorizationResponse> authorize(
            @Valid @RequestBody AuthorizationRequest request) {
        AuthorizationService.Outcome outcome = authorizations.authorize(request);

        return ResponseEntity.status(HttpStatus.OK).body(render(outcome));
    }

    /**
     * Turns one decision into the response body this endpoint returns.
     *
     * @param outcome the decision the service took
     * @return the response body carrying that decision
     */
    private static AuthorizationResponse render(AuthorizationService.Outcome outcome) {
        String accountId = accountIdentifierText(outcome.accountId());
        DeclineReason declineReason = outcome.declineReason().orElse(null);

        if (declineReason == null) {
            return AuthorizationResponse.approve(outcome.transactionId(), accountId);
        }
        if (accountId == null) {
            return AuthorizationResponse.declineUnresolvedCard(outcome.transactionId());
        }
        return AuthorizationResponse.decline(outcome.transactionId(), accountId, declineReason);
    }

    /**
     * Renders an account identifier at the width this response body holds.
     *
     * <p>Width {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}, which
     * {@link AuthorizationResponse#ACCOUNT_ID_PATTERN} also pins. Account seven renders as eleven
     * characters ending in seven, so a reader sees the identifier the cross-reference file holds.
     *
     * @param accountId the identifier the decision named, or {@code null} when it named none
     * @return eleven decimal digits, or {@code null} when the decision named no account
     */
    private static String accountIdentifierText(BigDecimal accountId) {
        if (accountId == null) {
            return null;
        }
        String digits = accountId.toBigInteger().toString();
        if (digits.length() >= PicClause.XREF_ACCT_ID_WIDTH) {
            return digits;
        }
        return "0".repeat(PicClause.XREF_ACCT_ID_WIDTH - digits.length()) + digits;
    }
}
