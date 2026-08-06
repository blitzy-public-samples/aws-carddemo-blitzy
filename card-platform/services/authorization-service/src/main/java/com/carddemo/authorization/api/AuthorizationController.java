package com.carddemo.authorization.api;

import com.carddemo.authorization.domain.AuthenticatedActor;
import com.carddemo.authorization.domain.AuthorizationService;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.DeclineReason;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one synchronous surface of the platform, answering {@code POST /authorizations}.
 *
 * <p>{@link AuthorizationRequest} carries the field set of the online capture program
 * {@code app/cbl/COTRN02C.cbl}, which runs under Customer Information Control System (CICS)
 * transaction {@code CT02}. {@code app/cbl/COTRN02C.cbl:L193} reads the account or the card
 * number a terminal operator keyed, and {@code app/cbl/COTRN02C.cbl:L442-L466} assembles the
 * record from the remaining screen fields. This endpoint reads the same values from one request
 * body in JavaScript Object Notation (JSON) and answers with one body.
 *
 * <p>The program the brief named, {@code COPAUA0C}, appears in no member of {@code app/cbl/}, and
 * {@code app/csd/CARDDEMO.CSD} defines no transaction {@code CP00}.
 *
 * <p>Seven fields of {@code app/cpy/COCOM01Y.cpy} reach nothing here. Each is a deliberate
 * omission:
 *
 * <ul>
 *   <li>{@code CDEMO-FROM-TRANID PIC X(04)} at {@code app/cpy/COCOM01Y.cpy:L21}</li>
 *   <li>{@code CDEMO-FROM-PROGRAM PIC X(08)} at {@code app/cpy/COCOM01Y.cpy:L22}</li>
 *   <li>{@code CDEMO-TO-TRANID PIC X(04)} at {@code app/cpy/COCOM01Y.cpy:L23}</li>
 *   <li>{@code CDEMO-TO-PROGRAM PIC X(08)} at {@code app/cpy/COCOM01Y.cpy:L24}</li>
 *   <li>{@code CDEMO-PGM-CONTEXT PIC 9(01)} at {@code app/cpy/COCOM01Y.cpy:L29}, with its two
 *       condition names at {@code app/cpy/COCOM01Y.cpy:L30-L31}</li>
 *   <li>{@code CDEMO-LAST-MAP PIC X(7)} at {@code app/cpy/COCOM01Y.cpy:L43}</li>
 *   <li>{@code CDEMO-LAST-MAPSET PIC X(7)} at {@code app/cpy/COCOM01Y.cpy:L44}</li>
 * </ul>
 *
 * <p>No conversation state survives a call. This endpoint opens no session, reads no cookie and
 * holds no field a second call could observe.
 *
 * <p>A card number travels in the request body alone. No path variable and no query parameter
 * carries one, so a full Primary Account Number reaches no access log through a request line. The
 * response names a transaction, an account and at most one reject code.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@RestController
public class AuthorizationController {

    /** Decides one call and writes the one event a resolved call produces. */
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
     * Authorizes one transaction.
     *
     * <p>An approval answers {@code 200}. A decline answers {@code 422}, carrying the one reject
     * code that stands and the text {@link DeclineReason#description()} holds. No decline answers
     * {@code 500} and none answers {@code 503}.
     *
     * <p>A rejection is a normal outcome in the source. {@code app/cbl/CBTRN02C.cbl:L229} tests the
     * reject count, and {@code app/cbl/CBTRN02C.cbl:L230} moves 4 into the return code of a batch
     * run that rejected records.
     *
     * <p>{@link AuthorizationService#authorize(AuthorizationRequest, String)} answers a decline
     * as a value and raises nothing for it, so this method reads the decision and maps it to a
     * status.
     *
     * <p>The identity the container resolved travels into the service, which records it beside the
     * decision. That identity reaches no response body.
     *
     * @param request the transaction to authorize, validated before this method runs
     * @param caller  the authenticated identity the container resolved, or {@code null} on a call
     *                carrying none
     * @return {@code 200} on an approval, {@code 422} on a decline
     */
    @PostMapping(path = "/authorizations",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuthorizationResponse> authorize(
            @Valid @RequestBody AuthorizationRequest request, Principal caller) {

        AuthorizationService.Outcome outcome =
                authorizations.authorize(request, AuthenticatedActor.actorOf(caller));

        return ResponseEntity.status(statusOf(outcome)).body(bodyOf(outcome));
    }

    /**
     * Names the status one decision answers with.
     *
     * @param outcome the decision the service took
     * @return {@code 200} on an approval, {@code 422} on a decline
     */
    private static HttpStatus statusOf(AuthorizationService.Outcome outcome) {
        return outcome.approved() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_CONTENT;
    }

    /**
     * Builds the body carrying one decision.
     *
     * <p>An approval names the account it authorized against. A decline that resolved an account
     * names that account and its reject code. {@link DeclineReason#INVALID_CARD_NUMBER} resolves
     * no account, so {@link AuthorizationResponse#declineUnresolvedCard(String)} builds that one
     * shape.
     * {@code app/cbl/CBTRN02C.cbl:L383-L384} reads the cross-reference dataset on the card number
     * and takes its {@code INVALID KEY} limb, which leaves no identifier to name.
     *
     * @param outcome the decision the service took
     * @return the body this endpoint returns for that decision
     */
    private static AuthorizationResponse bodyOf(AuthorizationService.Outcome outcome) {
        DeclineReason declineReason = outcome.declineReason().orElse(null);
        String accountId = accountIdentifierOf(outcome.accountId());

        if (declineReason == null) {
            return AuthorizationResponse.approve(outcome.transactionId(), accountId);
        }
        if (accountId == null) {
            return AuthorizationResponse.declineUnresolvedCard(outcome.transactionId());
        }
        return AuthorizationResponse.decline(outcome.transactionId(), accountId, declineReason);
    }

    /**
     * Widens one account identifier to the digits its record field holds.
     *
     * <p>{@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} declares the width, and
     * {@link AuthorizationResponse#ACCOUNT_ID_PATTERN} pins the same one. Account seventy-seven
     * reads as {@code 00000000077}.
     *
     * @param accountId the identifier the decision named, at scale zero, or {@code null} when the
     *                  cross-reference resolved none
     * @return {@value PicClause#XREF_ACCT_ID_WIDTH} decimal digits, or {@code null} when the
     *         decision named no account
     */
    private static String accountIdentifierOf(BigDecimal accountId) {
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
