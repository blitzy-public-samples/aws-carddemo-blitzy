package com.carddemo.account.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.account.api.dto.AccountDataRequest;
import com.carddemo.account.api.dto.AccountUpdateRequest;
import com.carddemo.account.api.dto.AccountUpdateResponse;
import com.carddemo.account.api.dto.AccountView;
import com.carddemo.account.domain.AccountSnapshot;
import com.carddemo.account.domain.AccountUpdateOutcome;
import com.carddemo.account.api.dto.CustomerDataRequest;
import com.carddemo.account.domain.AccountUpdateService;
import com.carddemo.account.domain.ConcurrentChangeDetector;
import com.carddemo.account.domain.validation.EditResult;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CustomerRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Behaviour tests for {@link AccountController}.
 *
 * <p>Two Customer Information Control System (CICS) transactions are under test here.
 * {@code app/cbl/COACTVWC.cbl} runs under {@code CAVW} and displays one account;
 * {@code app/cbl/COACTUPC.cbl} runs under {@code CAUP} and updates one account and its customer. What
 * these tests assert is the translation of their outcomes into status codes, and the merge rule that
 * lets a partial body mean what a whole-screen submit meant.
 *
 * <p>The service is stubbed, so nothing here re-tests the edits or the concurrency check. Those belong
 * to {@code domain/AccountUpdateServiceTest}, which drives them directly.
 *
 * <p>No application context, no database and no broker takes part.
 */
@DisplayName("the account read and update outcomes and the body merge rule")
class AccountControllerOutcomeAndMergeTest {

    /** Row one of {@code app/data/ASCII/acctdata.txt}, its account identifier. */
    private static final String ACCOUNT_ID = "00000000050";

    /** The customer the update names, nine digits. */
    private static final String CUSTOMER_ID = "000000050";

    /** The postal code row one of {@code app/data/ASCII/acctdata.txt} carries. */
    private static final String STORED_ADDRESS_ZIP = "72112";

    /** The Social Security Number row one of {@code app/data/ASCII/custdata.txt} carries. */
    private static final String STORED_SOCIAL_SECURITY_NUMBER = "429541163";

    /** The government-issued identifier that same row carries. */
    private static final String STORED_GOVERNMENT_ISSUED_ID = "AR8829114";

    private AccountRepository accounts;
    private CustomerRepository customers;
    private AccountUpdateService accountUpdates;
    private AccountController controller;

    /** Builds the controller over stubbed collaborators before each test. */
    @BeforeEach
    void buildController() {
        accounts = mock(AccountRepository.class);
        customers = mock(CustomerRepository.class);
        accountUpdates = mock(AccountUpdateService.class);
        controller = new AccountController(accounts, customers, accountUpdates);
    }

    /** The read path. */
    @Nested
    @DisplayName("reading one account")
    class ReadingOneAccount {

        /** Asserts a stored account is returned with every one of the eleven view components. */
        @Test
        void aStoredAccountIsReturnedInFull() {
            when(accounts.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(storedAccount()));

            ResponseEntity<?> response = controller.readAccount(ACCOUNT_ID);

            assertEquals(HttpStatus.OK, response.getStatusCode(), "a stored row answers 200");
            AccountView view = assertInstanceOf(AccountView.class, response.getBody());
            assertEquals(ACCOUNT_ID, view.accountId(), "the identifier keeps its leading zeros");
            assertEquals(new BigDecimal("10000.00"), view.creditLimit(),
                    "the credit limit the authorization service authorizes against");
            assertEquals(new BigDecimal("1010.00"), view.currentCycleDebit(),
                    "the accumulator a cycle close returns to zero");
            assertEquals("2025-02-28", view.expirationDate(),
                    "the expiry stays ten characters of text, per app/cbl/CBTRN02C.cbl:L414-L420");
            assertEquals("ZEROAPR", view.groupId(), "the disclosure group the interest program reads");
        }

        /**
         * Asserts a read that missed answers 404 carrying the text the source builds.
         *
         * <p>{@code 9300-GETACCTDATA-BYACCT} concatenates four literals around the account
         * identifier, a response code and a reason code at {@code app/cbl/COACTVWC.cbl:L796-L806}.
         * The identifier is the one the caller put in the path. The source carries no space after
         * {@code file.} and none after either colon, and the answer keeps that spacing.
         */
        @Test
        void aReadThatMissedAnswersNotFoundWithTheSourceText() {
            when(accounts.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.readAccount(ACCOUNT_ID);

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(), "no row answers 404");
            ApiProblem problem = assertInstanceOf(ApiProblem.class, response.getBody());
            assertEquals(ApiProblem.NOT_FOUND, problem.title(), "one fixed title");
            assertEquals(
                    "Account:00000000050 not found in Acct Master file.Resp:404 Reas:Not Found",
                    problem.detail(),
                    "app/cbl/COACTVWC.cbl:L796-L806 character for character");
            assertNull(problem.messages(),
                    "one text travels, and the detail is the slot it travels in");
        }

        /**
         * Asserts the payload carries the eleven values the source moves to the screen and no other.
         *
         * <p>{@code 1200-SETUP-SCREEN-VARS} moves them at {@code app/cbl/COACTVWC.cbl:L468-L490}.
         * {@code ACCT-ADDR-ZIP} at {@code app/cpy/CVACT01Y.cpy:L15} is not among them, and
         * {@code ACCOUNT-RECORD} at {@code app/cpy/CVACT01Y.cpy:L4-L17} declares no customer
         * identifier for a twelfth component to carry.
         */
        @Test
        void thePayloadCarriesElevenValuesAndNeitherTheZipNorACustomer() throws Exception {
            when(accounts.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(storedAccount()));

            ResponseEntity<?> response = controller.readAccount(ACCOUNT_ID);

            AccountView view = assertInstanceOf(AccountView.class, response.getBody());
            assertEquals(java.util.List.of("accountId", "activeStatus", "currentBalance",
                            "creditLimit", "cashCreditLimit", "currentCycleCredit",
                            "currentCycleDebit", "openDate", "expirationDate", "reissueDate",
                            "groupId"),
                    java.util.Arrays.stream(AccountView.class.getRecordComponents())
                            .map(java.lang.reflect.RecordComponent::getName).toList(),
                    "the eleven values of app/cbl/COACTVWC.cbl:L468-L490, in source order");
            assertFalse(renderedValuesOf(view).contains(STORED_ADDRESS_ZIP),
                    "the postal code the record holds reaches no component");
        }

        @Test
        void aReadWritesNothing() {
            when(accounts.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(storedAccount()));

            controller.readAccount(ACCOUNT_ID);

            verify(accounts, never()).save(any());
            verify(accountUpdates, never()).updateAccount(any(), any(), any(), any());
        }
    }

    /** The update path, and the four answers it can give. */
    @Nested
    @DisplayName("updating one account")
    class UpdatingOneAccount {

        /** Asserts a written pair answers 200 with the applied text and the stored row. */
        @Test
        void aWrittenPairAnswersTheAppliedText() {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            ResponseEntity<?> response = controller.updateAccount(ACCOUNT_ID, requestRaising());

            assertEquals(HttpStatus.OK, response.getStatusCode(), "a written pair answers 200");
            AccountUpdateResponse body =
                    assertInstanceOf(AccountUpdateResponse.class, response.getBody());
            assertEquals(AccountController.UPDATE_APPLIED_MESSAGE, body.message(),
                    "the caller reads that both rows were written");
            assertNotNull(body.account(), "the stored row as it now stands travels back");
        }

        /**
         * Asserts an unchanged pair answers 200 carrying the source's own no-change text.
         *
         * <p>{@code app/cbl/COACTUPC.cbl:L1463-L1467} returns before any field edit and before any
         * write, and saying so is more useful to a caller than an empty success.
         */
        @Test
        void anUnchangedPairAnswersTheNoChangeText() {
            resolveBoth();
            String noChange = "No change detected with respect to values fetched.";
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(new EditResult(true, noChange)));

            ResponseEntity<?> response = controller.updateAccount(ACCOUNT_ID, requestRaising());

            assertEquals(HttpStatus.OK, response.getStatusCode(),
                    "nothing changed is still not a failure");
            AccountUpdateResponse body =
                    assertInstanceOf(AccountUpdateResponse.class, response.getBody());
            assertEquals(noChange, body.message(), "the source text reaches the caller verbatim");
        }

        /**
         * Asserts a lost race answers 409 and carries the changed-record text.
         *
         * <p>{@code app/cbl/COACTUPC.cbl:L3947-L3952} detects it by comparing the re-read rows field
         * by field. A caller reading this answer should read the row again and resubmit, which is why
         * it is not a 422.
         */
        @Test
        void aChangedRecordAnswersConflict() {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any())).thenReturn(AccountUpdateOutcome.of(
                    EditResult.failure(ConcurrentChangeDetector.RECORD_CHANGED_MESSAGE)));

            ResponseEntity<?> response = controller.updateAccount(ACCOUNT_ID, requestRaising());

            assertEquals(HttpStatus.CONFLICT, response.getStatusCode(),
                    "a lost race is worth retrying, so it is not a 422");
            ApiProblem problem = assertInstanceOf(ApiProblem.class, response.getBody());
            assertEquals(ApiProblem.CONFLICT, problem.title(), "one fixed title");
            assertEquals(ConcurrentChangeDetector.RECORD_CHANGED_MESSAGE, problem.detail(),
                    "the source text reaches the caller verbatim");
        }

        /** Asserts each of the two lock failures answers 409 rather than 422. */
        @Test
        void bothLockFailuresAnswerConflict() {
            for (String lockFailure : AccountUpdateService.lockFailureMessages()) {
                buildController();
                resolveBoth();
                when(accountUpdates.updateAccount(any(), any(), any(), any()))
                        .thenReturn(AccountUpdateOutcome.of(EditResult.failure(lockFailure)));

                ResponseEntity<?> response = controller.updateAccount(ACCOUNT_ID, requestRaising());

                assertEquals(HttpStatus.CONFLICT, response.getStatusCode(),
                        "a row that could not be locked is worth retrying: " + lockFailure);
            }
        }

        /** Asserts a failed edit answers 422 carrying the verbatim text as one message. */
        @Test
        void aFailedEditAnswersUnprocessableWithItsSourceText() {
            resolveBoth();
            String ficoMessage = "FICO Score: should be between 300 and 850";
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(AccountUpdateOutcome.of(EditResult.failure(ficoMessage)));

            ResponseEntity<?> response = controller.updateAccount(ACCOUNT_ID, requestRaising());

            assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, response.getStatusCode(),
                    "a field a caller can correct answers 422");
            ApiProblem problem = assertInstanceOf(ApiProblem.class, response.getBody());
            assertEquals(ApiProblem.VALIDATION_FAILED, problem.title(), "one fixed title");
            assertEquals(ficoMessage, problem.detail(),
                    "the edit text reaches the caller character for character");
            assertNull(problem.messages(),
                    "one edit produced one text, so no list carries it");
        }

        /** Asserts an unknown account answers 404 and never reaches the service. */
        @Test
        void anUnknownAccountAnswersNotFoundAndUpdatesNothing() {
            when(accounts.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.updateAccount(ACCOUNT_ID, requestRaising());

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(), "no account answers 404");
            verify(accountUpdates, never()).updateAccount(any(), any(), any(), any());
        }

        /** Asserts an unknown customer answers 404 and never reaches the service. */
        @Test
        void anUnknownCustomerAnswersNotFoundAndUpdatesNothing() {
            when(accounts.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(storedAccount()));
            when(customers.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.updateAccount(ACCOUNT_ID, requestRaising());

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(), "no customer answers 404");
            verify(accountUpdates, never()).updateAccount(any(), any(), any(), any());
        }

        /**
         * Asserts a body naming no customer answers 422 and reads nothing.
         *
         * <p>An account record declares no customer identifier at
         * {@code app/cpy/CVACT01Y.cpy:L4-L17}, and this service owns no cross-reference table, so
         * there is nothing to resolve one from and the caller has to name it.
         */
        @Test
        void aBodyNamingNoCustomerAnswersUnprocessable() {
            ResponseEntity<?> response = controller.updateAccount(ACCOUNT_ID,
                    new AccountUpdateRequest(accountDataRaising(), null));

            assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, response.getStatusCode(),
                    "a caller that names no customer has not made a complete request");
            ApiProblem problem = assertInstanceOf(ApiProblem.class, response.getBody());
            assertEquals(AccountController.CUSTOMER_ID_REQUIRED_MESSAGE, problem.detail(),
                    "the answer says which component was missing");
            assertNull(problem.messages(), "one text travels, and no list carries it");
            verify(accounts, never()).findByAccountId(any());
        }

        /**
         * Asserts an identifier that is not nine digits answers 422 and reads no row.
         *
         * <p>{@code CUST-ID PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L5} is nine digits and
         * {@code ck_customer_customer_id_digits} in
         * {@code src/main/resources/db/migration/V1__schema.sql} carries the same rule, so no stored
         * row can hold another shape. Without this check the value reached
         * {@code CustomerRepository.findByCustomerId} and came back as {@code 404}, which told a
         * caller the customer does not exist rather than that the identifier is not one.
         *
         * <p>{@code AccountUpdateRequest} declares no cascade marker, so the {@code Pattern} on
         * {@code CustomerDataRequest.customerId} does not run through this route and the controller
         * holds the rule. {@code DtoValidationWiringTest.theRequestDeclaresNoCascade} is what fixes
         * that absence.
         */
        @ParameterizedTest
        @ValueSource(strings = {"1", "0000000012", "00000000A", "00000000 ", "-00000001",
                "000 000 0"})
        void anIdentifierOfAnotherShapeAnswersUnprocessable(String submitted) {
            ResponseEntity<?> response = controller.updateAccount(ACCOUNT_ID,
                    new AccountUpdateRequest(accountDataRaising(),
                            new CustomerDataRequest(submitted, null, null, null, null, null, null,
                                    null, null, null, null, null, null, null, null, null, null, null,
                                    null, null)));

            assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, response.getStatusCode(),
                    "an identifier of another width is a caller's to correct");
            ApiProblem problem = assertInstanceOf(ApiProblem.class, response.getBody());
            assertEquals(CustomerDataRequest.CUSTOMER_ID_MESSAGE, problem.detail(),
                    "the answer names the shape the identifier holds");
            assertFalse(problem.detail().contains(submitted),
                    "and repeats nothing the caller sent");
            assertNull(problem.messages(), "one text travels, and no list carries it");
            verify(customers, never()).findByCustomerId(any());
        }

        @Test
        void theNineDigitsTheSourceKeysOnStillPass() {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            ResponseEntity<?> response = controller.updateAccount(ACCOUNT_ID, requestRaising());

            assertEquals(HttpStatus.OK, response.getStatusCode(),
                    CUSTOMER_ID + " is the nine digits app/cpy/CVCUS01Y.cpy:L5 declares");
            verify(customers).findByCustomerId(CUSTOMER_ID);
        }
    }

    /** The merge rule: what a partial body keeps and what it replaces. */
    @Nested
    @DisplayName("merging a submitted body over the stored row")
    class MergingTheBody {

        /** Asserts a component the body carries replaces the stored value. */
        @Test
        void aSubmittedComponentReplacesTheStoredValue() {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            controller.updateAccount(ACCOUNT_ID, requestRaising());

            assertEquals(new BigDecimal("12000.00"), proposedAccount().getCreditLimit(),
                    "the submitted credit limit reached the service at the column's scale");
        }

        /**
         * Asserts a component the body omits and no mandatory edit reads keeps the stored value.
         *
         * <p>Six customer components and one account component carry no mandatory edit, and the
         * openapi document does not name them in the required list of their block. Those are the ones
         * a caller may leave out, and leaving one out has to mean the stored value stands. The
         * property has to hold for every one of them, because a merge that guards one field and not
         * its neighbour is a merge a later change quietly breaks.
         */
        @Test
        void anOmittedOptionalComponentKeepsTheStoredValue() {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            controller.updateAccount(ACCOUNT_ID, requestRaising());

            assertEquals("ZEROAPR", proposedAccount().getGroupId(),
                    "the disclosure group was not submitted, so it stands as stored");

            CustomerEntity proposed = proposedCustomer();
            assertEquals("Fay", proposed.getMiddleName(), "and so does the middle name");
            assertEquals("Suite 407", proposed.getAddressLine2(),
                    "and so does the second address line");
            assertEquals("(501)5551234", proposed.getPhoneNumber1(),
                    "and so does the first telephone number");
            assertEquals("(501)5555678", proposed.getPhoneNumber2(),
                    "and so does the second telephone number");
            assertEquals(STORED_GOVERNMENT_ISSUED_ID, proposed.getGovernmentIssuedId(),
                    "and so does the government-issued identifier");
            assertEquals("1971-08-14", proposed.getDateOfBirth(), "and so does the date of birth");
        }

        /**
         * Asserts a component the body omits and a mandatory edit reads reaches the service absent.
         *
         * <p>This is the whole of the mandatory-field contract. Nine account components and ten
         * customer components carry a mandatory-field edit in {@code 1200-EDIT-MAP-INPUTS} at
         * {@code app/cbl/COACTUPC.cbl:L1470-L1676}, and each one refuses an absent value exactly as it
         * refuses a blank one. That only happens if the merge hands the absent value through. Filling
         * it from the stored row would answer 200 to a caller that dropped a field and tell it
         * nothing, and it would write a value the caller never sent.
         *
         * <p>These tests call the controller directly, so no bean validation ran. What this test pins
         * is the merge; {@code AccountRouteWiringTest} pins the refusal the edit then produces.
         */
        @Test
        void anOmittedMandatoryComponentReachesTheServiceAbsent() {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            controller.updateAccount(ACCOUNT_ID, requestRaising());

            AccountEntity proposed = proposedAccount();
            assertNull(proposed.getCurrentBalance(),
                    "the balance was not submitted, so the edit that owns it reads an absent value");
            assertNull(proposed.getActiveStatus(), "and so does the status edit");
            assertNull(proposed.getOpenDate(), "and so does the open date edit");
            assertNull(proposed.getExpirationDate(), "and so does the expiry date edit");
            assertNull(proposed.getReissueDate(), "and so does the reissue date edit");
            assertNull(proposed.getCashCreditLimit(), "and so does the cash credit limit edit");
            assertNull(proposed.getCurrentCycleCredit(), "and so does the cycle credit edit");
            assertNull(proposed.getCurrentCycleDebit(), "and so does the cycle debit edit");

            CustomerEntity proposedCustomer = proposedCustomer();
            assertNull(proposedCustomer.getFirstName(), "and so does the first name edit");
            assertNull(proposedCustomer.getLastName(), "and so does the last name edit");
            assertNull(proposedCustomer.getAddressLine1(), "and so does the first address line edit");
            assertNull(proposedCustomer.getAddressCity(), "and so does the city edit");
            assertNull(proposedCustomer.getAddressStateCode(), "and so does the state edit");
            assertNull(proposedCustomer.getAddressCountryCode(), "and so does the country edit");
            assertNull(proposedCustomer.getAddressZip(), "and so does the postcode edit");
            assertNull(proposedCustomer.getEftAccountId(), "and so does the transfer account edit");
            assertNull(proposedCustomer.getPrimaryCardHolderIndicator(),
                    "and so does the primary card holder edit");
            assertNull(proposedCustomer.getFicoCreditScore(), "and so does the credit score edit");
        }

        /**
         * Asserts the whole account block being absent leaves every account column as stored.
         *
         * <p>Omitting the block is how a caller updates the customer alone, and it is the one omission
         * the mandatory edits do not refuse. The nine components then reach those edits carrying the
         * stored values, which is what the source screen sent when the operator touched nothing.
         */
        @Test
        void anAbsentAccountBlockKeepsEveryAccountColumn() {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            controller.updateAccount(ACCOUNT_ID, new AccountUpdateRequest(null, customerData()));

            AccountEntity proposed = proposedAccount();
            assertEquals("Y", proposed.getActiveStatus(), "the status stands as stored");
            assertEquals(new BigDecimal("1010.00"), proposed.getCurrentBalance(),
                    "and so does the balance");
            assertEquals(new BigDecimal("10000.00"), proposed.getCreditLimit(),
                    "and so does the credit limit");
            assertEquals(new BigDecimal("5000.00"), proposed.getCashCreditLimit(),
                    "and so does the cash credit limit");
            assertEquals("2015-03-01", proposed.getOpenDate(), "and so does the open date");
            assertEquals("2025-02-28", proposed.getExpirationDate(), "and so does the expiry date");
            assertEquals("2020-03-01", proposed.getReissueDate(), "and so does the reissue date");
            assertEquals(new BigDecimal("0.00"), proposed.getCurrentCycleCredit(),
                    "and so does the cycle credit");
            assertEquals(new BigDecimal("1010.00"), proposed.getCurrentCycleDebit(),
                    "and so does the cycle debit");
            assertEquals("ZEROAPR", proposed.getGroupId(), "and so does the disclosure group");
        }

        /**
         * Asserts a submitted amount is read under the tolerant grammar the source gates on.
         *
         * <p>{@code app/cbl/COACTUPC.cbl:L2201} gates on the currency-aware conversion, so a currency
         * sign and thousands separators are accepted. Constructing a decimal from the string directly
         * would reject both.
         */
        @Test
        void aSubmittedAmountIsReadUnderTheTolerantGrammar() {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            controller.updateAccount(ACCOUNT_ID, new AccountUpdateRequest(
                    new AccountDataRequest(null, null, "$12,000.00", null, null, null, null, null,
                            null, null),
                    customerData()));

            assertEquals(new BigDecimal("12000.00"), proposedAccount().getCreditLimit(),
                    "a currency sign and separators read as one amount");

            buildController();
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            controller.updateAccount(ACCOUNT_ID, new AccountUpdateRequest(
                    new AccountDataRequest(null, null, "$1,234.56", null, null, null, null, null,
                            null, null),
                    customerData()));

            assertEquals(new BigDecimal("1234.56"), proposedAccount().getCreditLimit(),
                    "the contract refuses neither the sign nor the separator");
        }

        /**
         * Asserts a submitted date of eight characters reaches the column as ten.
         *
         * <p>{@code ACUP-NEW-OPEN-DATE PIC X(08)} at {@code app/cbl/COACTUPC.cbl:L772} is the shape a
         * caller submits, and {@code ACCT-UPDATE-RECORD} declares the column {@code PIC X(10)} at
         * {@code app/cbl/COACTUPC.cbl:L427}. A separated ten-character value is the stored shape and
         * not the submitted one, so it is a value the request field cannot hold: it is refused by
         * width before anything is mapped, and the service is not reached. That is a narrower answer
         * than the one this case first asserted, which was that the ten characters were sliced to
         * eight and refused later by the date edit.
         */
        @Test
        void aSubmittedDateOfEightCharactersReachesTheColumnAsTen() {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            controller.updateAccount(ACCOUNT_ID, new AccountUpdateRequest(
                    new AccountDataRequest(null, null, null, null, "20150302", null, null, null,
                            null, null),
                    customerData()));

            assertEquals("2015-03-02", proposedAccount().getOpenDate(),
                    "eight characters reach the ten the column holds");

            buildController();
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            ResponseEntity<?> refused = controller.updateAccount(ACCOUNT_ID,
                    new AccountUpdateRequest(new AccountDataRequest(null, null, null, null,
                            "2015-03-02", null, null, null, null, null), customerData()));

            assertAll(
                    () -> assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, refused.getStatusCode(),
                            "a value the field cannot hold is a refused field"),
                    () -> assertEquals("Open Date must be no longer than 8 characters.",
                            assertInstanceOf(ApiProblem.class, refused.getBody()).detail(),
                            "the message names the field and the eight characters it holds"),
                    () -> verify(accountUpdates, never()).updateAccount(any(), any(), any(), any()));
        }

        /**
         * Asserts the path identifier wins over any identifier the body carries.
         *
         * <p>{@code config/SecurityConfig} scopes a caller against the path variable, so a body naming
         * a different account would let a caller entitled to one account write another.
         */
        @Test
        void thePathIdentifierWinsOverTheBody() {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            controller.updateAccount(ACCOUNT_ID,
                    new AccountUpdateRequest(accountDataRaising(), customerData()));

            assertAll("the path identifier is the only identifier this route reads",
                    () -> assertEquals(ACCOUNT_ID, proposedAccount().getAccountId(),
                            "the account the caller is scoped against is the account written"),
                    () -> assertEquals(java.util.List.of("accountData", "customerData"),
                            java.util.Arrays.stream(AccountUpdateRequest.class
                                            .getRecordComponents())
                                    .map(java.lang.reflect.RecordComponent::getName).toList(),
                            "the body declares no identifier component, so it cannot name one"));
        }

        /**
         * Asserts the fetched pair handed to the service is a copy and not the stored object.
         *
         * <p>Handing over the same object the service later mutates would make the comparison find
         * every field equal however much changed, so the copy is what makes the check mean anything.
         */
        @Test
        void theFetchedPairIsACopyAndNotTheStoredObject() {
            AccountEntity stored = storedAccount();
            CustomerEntity storedCustomer = storedCustomer();
            when(accounts.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(stored));
            when(customers.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(storedCustomer));
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            controller.updateAccount(ACCOUNT_ID, requestRaising());

            ArgumentCaptor<AccountEntity> fetched = ArgumentCaptor.forClass(AccountEntity.class);
            verify(accountUpdates).updateAccount(any(), any(), fetched.capture(), any());
            assertTrue(fetched.getValue() != stored,
                    "the fetched copy is a different object from the one the store returned");
            assertEquals(stored.getCreditLimit(), fetched.getValue().getCreditLimit(),
                    "and it carries the same values");
        }

        /**
         * Asserts the three Social Security parts are recombined only when all three arrive.
         *
         * <p>Writing a number two thirds of which came from the stored row is a value no caller asked
         * for. All three parts are required, so a body carrying one part alone leaves the column absent
         * and the edit at {@code app/cbl/COACTUPC.cbl:L1529-L1531} reads the nine spaces that absence
         * composes and refuses them. Substituting the stored number would let that body through, and
         * the caller would never learn it sent one part of three.
         */
        @Test
        void theSocialSecurityPartsAreRecombinedOnlyTogether() {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            controller.updateAccount(ACCOUNT_ID, new AccountUpdateRequest(null,
                    customerDataWithSocialSecurity("429", null, null)));
            assertNull(proposedCustomer().getSocialSecurityNumber(),
                    "one part alone leaves the column absent, and the stored number is not borrowed");

            buildController();
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            controller.updateAccount(ACCOUNT_ID, new AccountUpdateRequest(null,
                    customerDataWithSocialSecurity("111", "22", "3333")));
            assertEquals("111223333", proposedCustomer().getSocialSecurityNumber(),
                    "all three parts together write the column");
        }

        /**
         * Asserts the stored number is never the source of a submitted one.
         *
         * <p>The guard is the reason the merge cannot hand a partly stored Social Security number to
         * the writer. The number the store holds is one no part of this body named, and it reaches the
         * proposed record on no path.
         */
        @Test
        void anAbsentSocialSecurityNumberNeverBorrowsTheStoredOne() {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            controller.updateAccount(ACCOUNT_ID, requestRaising());

            assertNull(proposedCustomer().getSocialSecurityNumber(),
                    "no part arrived, so the column is absent");
            assertNotEquals(STORED_SOCIAL_SECURITY_NUMBER,
                    proposedCustomer().getSocialSecurityNumber(),
                    "and it is not the number the store holds");
        }
    }

    /** What no answer carries, whatever the outcome. */
    @Nested
    @DisplayName("what no answer carries")
    class WhatNoAnswerCarries {

        /**
         * Asserts neither identity document reaches a read payload or an update payload.
         *
         * <p>{@code CUST-SSN} at {@code app/cpy/CVCUS01Y.cpy:L17} and
         * {@code CUST-GOVT-ISSUED-ID} at {@code app/cpy/CVCUS01Y.cpy:L18} are both stored, and the
         * source moves them to the screen at {@code app/cbl/COACTVWC.cbl:L496-L504} and
         * {@code app/cbl/COACTVWC.cbl:L519}. No answer of this class carries either value.
         */
        @Test
        void neitherIdentityDocumentReachesAnAnswer() throws Exception {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            String read = renderedValuesOf(controller.readAccount(ACCOUNT_ID).getBody());
            String updated = renderedValuesOf(
                    controller.updateAccount(ACCOUNT_ID, requestRaising()).getBody());

            assertAll("neither document travels in either answer",
                    () -> assertFalse(read.contains(STORED_SOCIAL_SECURITY_NUMBER),
                            "the read answer carries no Social Security Number"),
                    () -> assertFalse(read.contains(STORED_GOVERNMENT_ISSUED_ID),
                            "the read answer carries no government-issued identifier"),
                    () -> assertFalse(updated.contains(STORED_SOCIAL_SECURITY_NUMBER),
                            "the update answer carries no Social Security Number"),
                    () -> assertFalse(updated.contains(STORED_GOVERNMENT_ISSUED_ID),
                            "the update answer carries no government-issued identifier"));
        }

        /** Asserts an update reads no row it does not need and writes through no store of its own. */
        @Test
        void anUpdateWritesThroughNoStoreOfItsOwn() {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            controller.updateAccount(ACCOUNT_ID, requestRaising());

            verify(accounts, never()).save(any());
            verify(customers, never()).save(any());
        }
    }

    // Fixtures and helpers.

    /**
     * Renders every value one response body carries, walking into a nested record.
     *
     * @param body the response body, or {@code null}
     * @return every value the body carries, separated
     * @throws Exception when a component accessor cannot be read
     */
    private static String renderedValuesOf(Object body) throws Exception {
        if (body == null) {
            return "";
        }
        if (!body.getClass().isRecord()) {
            return String.valueOf(body);
        }
        StringBuilder rendered = new StringBuilder();
        for (java.lang.reflect.RecordComponent component
                : body.getClass().getRecordComponents()) {
            rendered.append(renderedValuesOf(component.getAccessor().invoke(body))).append('|');
        }
        return rendered.toString();
    }

    /** Stubs both stores to resolve their rows. */
    private void resolveBoth() {
        when(accounts.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(storedAccount()));
        when(customers.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(storedCustomer()));
    }

    /** @return the proposed account the controller handed the service */
    private AccountEntity proposedAccount() {
        ArgumentCaptor<AccountEntity> proposed = ArgumentCaptor.forClass(AccountEntity.class);
        verify(accountUpdates).updateAccount(proposed.capture(), any(), any(), any());
        return proposed.getValue();
    }

    /** @return the proposed customer the controller handed the service */
    private CustomerEntity proposedCustomer() {
        ArgumentCaptor<CustomerEntity> proposed = ArgumentCaptor.forClass(CustomerEntity.class);
        verify(accountUpdates).updateAccount(any(), proposed.capture(), any(), any());
        return proposed.getValue();
    }

    /** @return one request raising the credit limit and leaving every other field as stored */
    private static AccountUpdateRequest requestRaising() {
        return new AccountUpdateRequest(accountDataRaising(), customerData());
    }

    /** @return account values carrying a new credit limit and nothing else */
    private static AccountDataRequest accountDataRaising() {
        return new AccountDataRequest(null, null, "12000.00", null, null, null, null, null, null,
                null);
    }

    /** @return customer values naming the customer and nothing else */
    private static CustomerDataRequest customerData() {
        return customerDataWithSocialSecurity(null, null, null);
    }

    /**
     * Builds customer values naming the customer and the three Social Security parts given.
     *
     * @param part1 the area part, or {@code null}
     * @param part2 the group part, or {@code null}
     * @param part3 the serial part, or {@code null}
     * @return the submitted customer values
     */
    private static CustomerDataRequest customerDataWithSocialSecurity(String part1, String part2,
            String part3) {
        return new CustomerDataRequest(CUSTOMER_ID, null, null, null, null, null, null, null, null,
                null, null, null, part1, part2, part3, null, null, null, null, null);
    }

    /** @return the stored account, from row one of app/data/ASCII/acctdata.txt */
    private static AccountEntity storedAccount() {
        AccountEntity stored = new AccountEntity();
        stored.setAccountId(ACCOUNT_ID);
        stored.setActiveStatus("Y");
        stored.setCurrentBalance(new BigDecimal("1010.00"));
        stored.setCreditLimit(new BigDecimal("10000.00"));
        stored.setCashCreditLimit(new BigDecimal("5000.00"));
        stored.setOpenDate("2015-03-01");
        stored.setExpirationDate("2025-02-28");
        stored.setReissueDate("2020-03-01");
        stored.setCurrentCycleCredit(new BigDecimal("0.00"));
        stored.setCurrentCycleDebit(new BigDecimal("1010.00"));
        stored.setAddressZip(STORED_ADDRESS_ZIP);
        stored.setGroupId("ZEROAPR");
        return stored;
    }

    /** @return the stored customer, from row one of app/data/ASCII/custdata.txt */
    private static CustomerEntity storedCustomer() {
        CustomerEntity stored = new CustomerEntity();
        stored.setCustomerId(CUSTOMER_ID);
        stored.setFirstName("Arlene");
        stored.setMiddleName("Fay");
        stored.setLastName("Abshire");
        stored.setAddressLine1("8829 Ondricka Trail");
        stored.setAddressLine2("Suite 407");
        stored.setAddressCity("North Enoshaven");
        stored.setAddressStateCode("AR");
        stored.setAddressCountryCode("USA");
        stored.setAddressZip("72112");
        stored.setPhoneNumber1("(501)5551234");
        stored.setPhoneNumber2("(501)5555678");
        stored.setSocialSecurityNumber(STORED_SOCIAL_SECURITY_NUMBER);
        stored.setGovernmentIssuedId(STORED_GOVERNMENT_ISSUED_ID);
        stored.setDateOfBirth("1971-08-14");
        stored.setEftAccountId("4829571130");
        stored.setPrimaryCardHolderIndicator("Y");
        stored.setFicoCreditScore(new BigDecimal("688"));
        return stored;
    }
    /**
     * Wraps one verdict as the outcome the service now answers with, carrying the stored account.
     *
     * <p>The controller reads the account out of this outcome rather than reading the row a second
     * time after the transaction committed, so a stubbed passing verdict has to carry the snapshot
     * the real service reads off the row it wrote.
     *
     * @param verdict the verdict to carry
     * @return that verdict beside a snapshot of the stored account
     */
    private static AccountUpdateOutcome outcomeCarrying(EditResult verdict) {
        return new AccountUpdateOutcome(verdict, AccountSnapshot.of(storedAccount()));
    }

}
