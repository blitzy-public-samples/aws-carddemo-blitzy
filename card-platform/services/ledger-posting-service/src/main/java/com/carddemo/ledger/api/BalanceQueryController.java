package com.carddemo.ledger.api;

import com.carddemo.events.EventEnvelope;
import com.carddemo.ledger.entity.AccountBalanceProjectionEntity;
import com.carddemo.ledger.repository.AccountBalanceProjectionRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one synchronous surface of the ledger posting service.
 *
 * <p>One operation answers, {@code GET /balances/{accountId}}, and it returns four values: the
 * account identifier, the account balance and the two billing-cycle accumulators. The selection
 * follows {@code 1200-SETUP-SCREEN-VARS} at {@code app/cbl/COACTVWC.cbl:L460}, which moves ten
 * account fields. The account service owns the six this operation leaves out.
 *
 * <p>Each returned value has one source field:
 *
 * <ul>
 * <li>the account identifier, {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}</li>
 * <li>the balance, {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L7}, moved
 *     at {@code app/cbl/COACTVWC.cbl:L475}</li>
 * <li>the cycle credit, {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} at
 *     {@code app/cpy/CVACT01Y.cpy:L13}, moved at {@code app/cbl/COACTVWC.cbl:L482}</li>
 * <li>the cycle debit, {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} at
 *     {@code app/cpy/CVACT01Y.cpy:L14}, moved at {@code app/cbl/COACTVWC.cbl:L485}</li>
 * </ul>
 *
 * <p>Five answers, and {@link LedgerApiExceptionHandler} shapes the last three. A stored account
 * answers {@code 200}. An account the table does not hold answers {@code 404} with no body. A path
 * variable that misses the eleven-digit pattern answers {@code 400}. A projection table this service
 * cannot reach answers {@code 503}, and anything else answers {@code 500}.
 *
 * <p>The three failing answers used to be one framework default body, which carried the resolved
 * request path. A caller naming an account identifier read that identifier back and copied it into its
 * own access log, and a paused datastore answered {@code 500} with nothing an operator could act on.
 *
 * <p>The operation reads and changes no row. An event drives the balance update at
 * {@code app/cbl/CBTRN02C.cbl:L545-L560}, and no route here reaches it.
 *
 * <p>Eight source constructs have no counterpart here:
 *
 * <ul>
 * <li>omitted: {@code CDEMO-FROM-TRANID PIC X(04)} at {@code app/cpy/COCOM01Y.cpy:L21}</li>
 * <li>omitted: {@code CDEMO-FROM-PROGRAM PIC X(08)} at {@code app/cpy/COCOM01Y.cpy:L22}</li>
 * <li>omitted: {@code CDEMO-TO-TRANID PIC X(04)} at {@code app/cpy/COCOM01Y.cpy:L23}</li>
 * <li>omitted: {@code CDEMO-TO-PROGRAM PIC X(08)} at {@code app/cpy/COCOM01Y.cpy:L24}</li>
 * <li>omitted: {@code CDEMO-PGM-CONTEXT PIC 9(01)} at {@code app/cpy/COCOM01Y.cpy:L29}, with its
 *     condition names at {@code app/cpy/COCOM01Y.cpy:L30} and
 *     {@code app/cpy/COCOM01Y.cpy:L31}</li>
 * <li>omitted: {@code CDEMO-LAST-MAP PIC X(7)} at {@code app/cpy/COCOM01Y.cpy:L43}</li>
 * <li>omitted: {@code CDEMO-LAST-MAPSET PIC X(7)} at {@code app/cpy/COCOM01Y.cpy:L44}</li>
 * <li>omitted: the first-entry length test at {@code app/cbl/COACTVWC.cbl:L462}</li>
 * </ul>
 */
@RestController
@RequestMapping("/balances")
public class BalanceQueryController {

    /**
     * Shape of the account identifier this route accepts: exactly eleven digits, from
     * {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5} and the eleven-byte key
     * {@code KEYS(11 0)} declares at {@code app/jcl/ACCTFILE.jcl:L40}.
     */
    private static final String ACCOUNT_ID_PATTERN = "^[0-9]{11}$";

    /** Reads the balance projection this service keeps. */
    private final AccountBalanceProjectionRepository balances;

    /**
     * Takes the repository this controller reads.
     *
     * @param balances the balance projection repository
     */
    public BalanceQueryController(AccountBalanceProjectionRepository balances) {
        this.balances = balances;
    }

    /**
     * Returns one account's balance and its two billing-cycle accumulators.
     *
     * @param accountId the eleven-digit account identifier
     * @return {@code 200} carrying the four values, or {@code 404} when the table holds no such
     *         account
     */
    @GetMapping(path = "/{accountId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccountBalance> balanceOfAccount(
            @PathVariable
            @NotBlank
            @Pattern(regexp = ACCOUNT_ID_PATTERN)
            String accountId) {
        return balances.findById(accountId)
                .map(AccountBalance::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The four values one balance answer carries.
     *
     * <p>Each amount is a decimal string holding two places, the scale that
     * {@code PIC S9(10)V99} and column {@code NUMERIC(12,2)} both declare. An accumulator may
     * carry a minus sign: {@code app/cbl/CBTRN02C.cbl:L551} adds a negative amount to the cycle
     * debit.
     *
     * @param accountId      the eleven-digit account identifier, from {@code ACCT-ID} at
     *                       {@code app/cpy/CVACT01Y.cpy:L5}
     * @param currentBalance the balance, from {@code ACCT-CURR-BAL} at
     *                       {@code app/cpy/CVACT01Y.cpy:L7}
     * @param cycleCredit    the cycle credit accumulator, from {@code ACCT-CURR-CYC-CREDIT} at
     *                       {@code app/cpy/CVACT01Y.cpy:L13}
     * @param cycleDebit     the cycle debit accumulator, from {@code ACCT-CURR-CYC-DEBIT} at
     *                       {@code app/cpy/CVACT01Y.cpy:L14}
     */
    public record AccountBalance(String accountId, String currentBalance, String cycleCredit,
            String cycleDebit) {

        /**
         * Renders one projection row.
         *
         * @param projection the row to render
         * @return the four values, each amount a decimal string holding two places
         */
        static AccountBalance of(AccountBalanceProjectionEntity projection) {
            return new AccountBalance(projection.getAccountId(),
                    projection.getCurrentBalance().toPlainString(),
                    projection.getCycleCredit().toPlainString(),
                    projection.getCycleDebit().toPlainString());
        }

        /**
         * Names this record and withholds all four values.
         *
         * <p>This override replaces the representation the compiler generates for a record, which
         * prints the account identifier and the three amounts. A response record reaches a log
         * whenever a framework renders a handler argument or a return value, and an account
         * identifier is stable across every request that names it.
         *
         * @return the record name and four withheld components, never {@code null}
         */
        @Override
        public String toString() {
            return "AccountBalance[accountId=" + EventEnvelope.WITHHELD + ", currentBalance="
                    + EventEnvelope.WITHHELD + ", cycleCredit=" + EventEnvelope.WITHHELD
                    + ", cycleDebit=" + EventEnvelope.WITHHELD + "]";
        }
    }
}
