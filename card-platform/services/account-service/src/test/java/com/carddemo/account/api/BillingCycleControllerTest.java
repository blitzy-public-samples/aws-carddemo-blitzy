package com.carddemo.account.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.account.api.dto.CycleCloseResponse;
import com.carddemo.account.domain.BillingCycleService;
import com.carddemo.account.entity.AccountEntity;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Behaviour tests for {@link BillingCycleController}.
 *
 * <p>The endpoint under test reproduces two statements, {@code app/cbl/CBACT04C.cbl:L353-L354}, and
 * nothing else. It exists because the credit-limit rule at {@code app/cbl/CBTRN02C.cbl:L403-L413}
 * authorizes against cycle credit minus cycle debit, and the only code in the repository that returns
 * those accumulators to zero sits inside the interest program, which stays batch.
 *
 * <p>What these tests assert is that the response lets a caller see both accumulators at zero rather
 * than take it on trust, and that a missing row answers 404 without a write.
 *
 * <p>No application context, no database and no broker takes part.
 */
@DisplayName("the cycle-close surface")
class BillingCycleControllerTest {

    /** Row one of {@code app/data/ASCII/acctdata.txt}, its account identifier. */
    private static final String ACCOUNT_ID = "00000000050";

    /** Zero at the two fractional digits {@code PIC S9(10)V99} holds. */
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private BillingCycleService billingCycles;
    private BillingCycleController controller;

    /** Builds the controller over a stubbed service before each test. */
    @BeforeEach
    void buildController() {
        billingCycles = mock(BillingCycleService.class);
        controller = new BillingCycleController(billingCycles);
    }

    /**
     * Asserts a closed cycle answers 200 carrying both accumulators at zero.
     *
     * <p>Carrying them back is the point of the response body. Without them a caller has to read the
     * account again to learn whether the call did what it says, and the most likely reason to call this
     * endpoint is that a demonstration has started declining every transaction.
     */
    @Test
    void aClosedCycleAnswersBothAccumulatorsAtZero() {
        when(billingCycles.closeBillingCycle(ACCOUNT_ID)).thenReturn(Optional.of(closedAccount()));

        ResponseEntity<?> response = controller.closeBillingCycle(ACCOUNT_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode(), "a closed cycle answers 200");
        CycleCloseResponse body = assertInstanceOf(CycleCloseResponse.class, response.getBody());
        assertEquals(ACCOUNT_ID, body.accountId(), "the identifier keeps its leading zeros");
        assertEquals(ZERO, body.currentCycleCredit(), "app/cbl/CBACT04C.cbl:L353 zeroes this one");
        assertEquals(ZERO, body.currentCycleDebit(), "app/cbl/CBACT04C.cbl:L354 zeroes this one");
    }

    /**
     * Asserts both accumulators come back at the scale the column holds.
     *
     * <p>{@code NUMERIC(12,2)} holds two fractional digits, and zero at scale 0 and zero at scale 2 are
     * equal in value and different on the wire. The wire form is what a caller reads.
     */
    @Test
    void bothAccumulatorsCarryTheScaleTheColumnHolds() {
        when(billingCycles.closeBillingCycle(ACCOUNT_ID)).thenReturn(Optional.of(closedAccount()));

        CycleCloseResponse body = assertInstanceOf(CycleCloseResponse.class,
                controller.closeBillingCycle(ACCOUNT_ID).getBody());

        assertEquals(2, body.currentCycleCredit().scale(),
                "PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L12 holds two fractional digits");
        assertEquals(2, body.currentCycleDebit().scale(),
                "and so does PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L13");
    }

    /**
     * Asserts a cycle close on an unknown account answers 404 with no identifier in the detail.
     *
     * <p>The service returns an empty result rather than raising, because a keyed read that missed is
     * an outcome the source has too: it moves a text onto the screen and continues.
     */
    @Test
    void anUnknownAccountAnswersNotFoundAndNamesNothing() {
        when(billingCycles.closeBillingCycle(ACCOUNT_ID)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.closeBillingCycle(ACCOUNT_ID);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(), "no row answers 404");
        ApiProblem problem = assertInstanceOf(ApiProblem.class, response.getBody());
        assertEquals(ApiProblem.NOT_FOUND, problem.title(), "one fixed title");
        assertFalse(problem.detail().contains(ACCOUNT_ID),
                "the detail repeats no identifier back to its sender");
        assertNull(problem.messages(), "no field failed, so no field text is carried");
    }

    /**
     * Asserts the response carries nothing beyond the two accumulators and the identifier.
     *
     * <p>{@code app/cbl/CBACT04C.cbl:L353-L354} changes two fields and this endpoint reproduces those
     * two statements alone. A response carrying a balance or a credit limit would suggest the call had
     * looked at them.
     */
    @Test
    void theResponseCarriesNothingBeyondWhatTheCallChanged() {
        assertEquals(3, CycleCloseResponse.class.getRecordComponents().length,
                "the identifier and the two accumulators the two statements zero");
    }

    /**
     * Asserts the path pattern pins eleven digits, the same width the account route pins.
     *
     * <p>{@link CycleCloseResponse} refuses any other width when it is built, so a path admitting one
     * would turn a caller's mistake into a 500 rather than a 422.
     */
    @Test
    void thePathPatternPinsElevenDigits() {
        assertEquals(AccountController.ACCOUNT_ID_PATTERN, BillingCycleController.ACCOUNT_ID_PATTERN,
                "one width for one identifier, on both routes that carry it");
        assertTrue(ACCOUNT_ID.matches(BillingCycleController.ACCOUNT_ID_PATTERN),
                "eleven digits with leading zeros is the shape a row carries");
        assertFalse("50".matches(BillingCycleController.ACCOUNT_ID_PATTERN),
                "an unpadded identifier matches no row and is refused before a write");
    }

    /** @return the account as it stands after a close, from row one of app/data/ASCII/acctdata.txt */
    private static AccountEntity closedAccount() {
        AccountEntity closed = new AccountEntity();
        closed.setAccountId(ACCOUNT_ID);
        closed.setActiveStatus("Y");
        closed.setCurrentBalance(new BigDecimal("1010.00"));
        closed.setCreditLimit(new BigDecimal("10000.00"));
        closed.setCashCreditLimit(new BigDecimal("5000.00"));
        closed.setOpenDate("2015-03-01");
        closed.setExpirationDate("2025-02-28");
        closed.setReissueDate("2020-03-01");
        closed.setCurrentCycleCredit(ZERO);
        closed.setCurrentCycleDebit(ZERO);
        closed.setAddressZip("72112");
        closed.setGroupId("ZEROAPR");
        return closed;
    }
}
