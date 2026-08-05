package com.carddemo.account.api;

import com.carddemo.account.api.dto.CycleCloseResponse;
import com.carddemo.account.domain.BillingCycleService;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.cobol.PicClause;
import jakarta.validation.constraints.Pattern;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Closes one billing cycle, which is the one operation this platform needs from a program it does not
 * migrate.
 *
 * <p>Interest calculation stays a batch process, and the plan says so. One statement pair inside it
 * cannot stay there, because the authorization service depends on it: the credit-limit rule at
 * {@code app/cbl/CBTRN02C.cbl:L403-L413} computes cycle credit minus cycle debit plus the amount and
 * compares the result against the credit limit, and the only code in the whole repository that returns
 * those two accumulators to zero is {@code app/cbl/CBACT04C.cbl:L353-L354}, inside the interest
 * program.
 *
 * <p>Without a substitute the consequence is not subtle. Every posted transaction adds to one
 * accumulator and nothing ever subtracts, so the tested balance climbs, available credit shrinks, and
 * within a demonstration of any length every transaction declines with reject code {@code 0102}. That
 * is the most likely way a working platform gets reported as broken.
 *
 * <p>This endpoint therefore reproduces those two statements and nothing else. It computes no interest,
 * reads no disclosure group, and writes no transaction. Calling it an operational endpoint rather than
 * a migration of the interest program is the accurate description, and
 * {@code card-platform/docs/decision-log.md} carries the reasoning.
 *
 * <p>The method is a {@code POST} rather than a {@code PUT} because it is not idempotent in the way a
 * {@code PUT} promises: it is idempotent in its effect on the two accumulators, which are zero after
 * one call and after ten, and it is not idempotent in its events, because each call produces one.
 * A caller that means to close a cycle once should call it once.
 *
 * <p>{@code config/SecurityConfig} restricts this route to {@code ROLE_ADMIN}. An ordinary identity
 * closing a cycle would clear the accumulators the decline rules read, which is a decision about the
 * whole account and not about one cardholder's own data.
 *
 * <p>{@code src/main/resources/openapi.yaml} describes this endpoint, written by hand so no
 * documentation generator joins the classpath.
 */
@RestController
@RequestMapping("/accounts")
@Validated
public class BillingCycleController {

    /** Writes the diagnostic lines this class emits, none carrying an amount. */
    private static final Logger LOG = LoggerFactory.getLogger(BillingCycleController.class);

    /**
     * The width and the alphabet of an account identifier in a path.
     *
     * <p>{@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}, the same width
     * {@link AccountController} pins, and the width {@link CycleCloseResponse} requires of the
     * identifier it carries.
     */
    static final String ACCOUNT_ID_PATTERN = "^[0-9]{" + PicClause.ACCT_ID_WIDTH + "}$";

    /** Zeroes both accumulators and produces the one event that records it. */
    private final BillingCycleService billingCycles;

    /**
     * Takes the service that closes one cycle.
     *
     * @param billingCycles the cycle-close service
     * @throws NullPointerException if the argument is {@code null}
     */
    public BillingCycleController(BillingCycleService billingCycles) {
        this.billingCycles = Objects.requireNonNull(billingCycles, "billingCycles must be present");
    }

    /**
     * Closes the billing cycle of one account.
     *
     * <p>The service locks the row, sets both accumulators to zero at the scale
     * {@code PIC S9(10)V99} holds, writes the row, and writes one {@code AccountStateChanged} event
     * into the outbox, all in one local transaction. The authorization service consumes that event and
     * refreshes its own copy of the two accumulators, which is how a cycle close reaches the decline
     * rules without either service calling the other.
     *
     * <p>The response carries both accumulators after the close, so a caller can see that they are
     * zero rather than take it on trust.
     *
     * @param accountId the account whose cycle to close, eleven decimal digits
     * @return {@code 200} carrying both accumulators at zero, or {@code 404} when this service holds
     *         no such row
     */
    @PostMapping("/{accountId}/cycle-close")
    public ResponseEntity<?> closeBillingCycle(
            @PathVariable
            @Pattern(regexp = ACCOUNT_ID_PATTERN, message = AccountController.ACCOUNT_ID_MESSAGE)
            String accountId) {

        Optional<AccountEntity> closed = billingCycles.closeBillingCycle(accountId);
        if (closed.isEmpty()) {
            LOG.info("A cycle close found no account row");
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(ApiProblem.of(HttpStatus.NOT_FOUND.value(), ApiProblem.NOT_FOUND,
                            ApiProblem.NOT_FOUND_DETAIL));
        }

        AccountEntity account = closed.get();
        LOG.info("A billing cycle was closed and one state change entered the outbox");
        return ResponseEntity.ok(new CycleCloseResponse(account.getAccountId(),
                account.getCurrentCycleCredit(), account.getCurrentCycleDebit()));
    }
}
