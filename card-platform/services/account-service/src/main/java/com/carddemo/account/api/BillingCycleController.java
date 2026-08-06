package com.carddemo.account.api;

import com.carddemo.account.api.dto.CycleCloseResponse;
import com.carddemo.account.domain.BillingCycleService;
import com.carddemo.account.entity.AccountEntity;
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
 * The billing-cycle close endpoint, which returns both cycle accumulators of one account to zero.
 *
 * <p>This class reproduces two statements of paragraph {@code 1050-UPDATE-ACCOUNT} at
 * {@code app/cbl/CBACT04C.cbl:L350}: {@code MOVE 0 TO ACCT-CURR-CYC-CREDIT} at
 * {@code app/cbl/CBACT04C.cbl:L353} and {@code MOVE 0 TO ACCT-CURR-CYC-DEBIT} at
 * {@code app/cbl/CBACT04C.cbl:L354}. The two fields are {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99}
 * at {@code app/cpy/CVACT01Y.cpy:L13} and {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} at
 * {@code app/cpy/CVACT01Y.cpy:L14}.
 *
 * <p>The remaining statement of that paragraph, {@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL} at
 * {@code app/cbl/CBACT04C.cbl:L352}, has no counterpart here. A close leaves
 * {@code ACCT-CURR-BAL} at {@code app/cpy/CVACT01Y.cpy:L7} as it found it, and
 * {@code card-platform/docs/traceability-matrix.md} records that omission.
 *
 * <p>Both accumulators feed one authorization rule. {@code app/cbl/CBTRN02C.cbl:L403-L405} computes
 * a working balance from cycle credit minus cycle debit plus the transaction amount, and
 * {@code app/cbl/CBTRN02C.cbl:L407} compares that working balance with
 * {@code ACCT-CREDIT-LIMIT}. A failed comparison assigns reject reason 102 at
 * {@code app/cbl/CBTRN02C.cbl:L410-L412}.
 *
 * <p>The scope is those two statements. This endpoint computes no accrual, reads no disclosure
 * group, writes no transaction, and consults {@code ACCT-ACTIVE-STATUS} at
 * {@code app/cpy/CVACT01Y.cpy:L6} for nothing. {@code domain/BillingCycleService} holds the lock,
 * the transaction boundary and the write.
 *
 * <p>{@code src/main/resources/openapi.yaml} describes this route, and no generator reconciles that
 * description against this class.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@RestController
@RequestMapping("/accounts")
@Validated
public class BillingCycleController {

    /** Writes the diagnostic lines this class emits, none carrying a submitted value. */
    private static final Logger LOG = LoggerFactory.getLogger(BillingCycleController.class);

    /**
     * The width and the alphabet of an account identifier in a path.
     *
     * <p>{@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}. The leading zeros belong to
     * the value, so account fifty is {@code 00000000050} and not {@code 50}. This constant holds
     * {@link AccountController#ACCOUNT_ID_PATTERN}, the width every route carrying an account
     * identifier pins.
     */
    static final String ACCOUNT_ID_PATTERN = AccountController.ACCOUNT_ID_PATTERN;

    /** Zeroes both cycle accumulators of one account and stores the row. */
    private final BillingCycleService billingCycles;

    /**
     * Takes the service that closes one billing cycle.
     *
     * @param billingCycles the cycle-close service; must not be {@code null}
     * @throws NullPointerException if the argument is {@code null}
     */
    public BillingCycleController(BillingCycleService billingCycles) {
        this.billingCycles = Objects.requireNonNull(billingCycles, "billingCycles must be present");
    }

    /**
     * Closes the billing cycle of one account.
     *
     * <p>The path identifier is the whole input, and this route reads no request body.
     *
     * <p>The response carries the account identifier and both accumulators as they stand after the
     * close, each at the two fractional digits {@code PIC S9(10)V99} holds.
     *
     * @param accountId the account whose cycle to close, eleven decimal digits
     * @return {@code 200} carrying both accumulators at zero, or {@code 404} when this service
     *         holds no row for that identifier
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
        LOG.info("A billing cycle closed");
        return ResponseEntity.ok(new CycleCloseResponse(account.getAccountId(),
                account.getCurrentCycleCredit(), account.getCurrentCycleDebit()));
    }
}
