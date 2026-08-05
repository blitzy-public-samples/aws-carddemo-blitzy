package com.carddemo.account.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
@DisplayName("the account read and update surface")
class AccountControllerTest {

    /** Row one of {@code app/data/ASCII/acctdata.txt}, its account identifier. */
    private static final String ACCOUNT_ID = "00000000050";

    /** The customer the update names, nine digits. */
    private static final String CUSTOMER_ID = "000000050";

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
         * Asserts a read that missed answers 404 with a problem document naming no identifier.
         *
         * <p>The detail names nothing, for the reason {@code config/SecurityConfig} gives for its own
         * 403: a caller probing for another subject's rows should learn nothing from the answer.
         */
        @Test
        void aReadThatMissedAnswersNotFoundAndNamesNothing() {
            when(accounts.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.readAccount(ACCOUNT_ID);

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(), "no row answers 404");
            ApiProblem problem = assertInstanceOf(ApiProblem.class, response.getBody());
            assertEquals(ApiProblem.NOT_FOUND, problem.title(), "one fixed title");
            assertFalse(problem.detail().contains(ACCOUNT_ID),
                    "the detail repeats no identifier back to its sender");
            assertNull(problem.messages(), "no field failed, so no field text is carried");
        }

        /** Asserts a read writes nothing at all. */
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
                    .thenReturn(EditResult.ok());

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
                    .thenReturn(new EditResult(true, noChange));

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
            when(accountUpdates.updateAccount(any(), any(), any(), any())).thenReturn(
                    EditResult.failure(ConcurrentChangeDetector.RECORD_CHANGED_MESSAGE));

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
                        .thenReturn(EditResult.failure(lockFailure));

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
                    .thenReturn(EditResult.failure(ficoMessage));

            ResponseEntity<?> response = controller.updateAccount(ACCOUNT_ID, requestRaising());

            assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, response.getStatusCode(),
                    "a field a caller can correct answers 422");
            ApiProblem problem = assertInstanceOf(ApiProblem.class, response.getBody());
            assertEquals(ApiProblem.VALIDATION_FAILED, problem.title(), "one fixed title");
            assertEquals(java.util.List.of(ficoMessage), problem.messages(),
                    "the edit text reaches the caller character for character");
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
            assertEquals(java.util.List.of(AccountController.CUSTOMER_ID_REQUIRED_MESSAGE),
                    problem.messages(), "the answer says which component was missing");
            verify(accounts, never()).findByAccountId(any());
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
                    .thenReturn(EditResult.ok());

            controller.updateAccount(ACCOUNT_ID, requestRaising());

            assertEquals(new BigDecimal("12000.00"), proposedAccount().getCreditLimit(),
                    "the submitted credit limit reached the service at the column's scale");
        }

        /**
         * Asserts a component the body omits keeps the stored value.
         *
         * <p>These tests call the controller directly, so bean validation has already run or, here, has
         * not. Over the Hypertext Transfer Protocol most of these components carry a mandatory-field
         * edit and a body omitting one is refused before this merge is reached, which
         * {@code AccountRouteWiringTest} asserts. What this test pins is the merge itself: an absent
         * value never overwrites a stored one. The property matters for the components that may be
         * omitted, and it has to hold for all of them, because a merge that guards one field and not its
         * neighbour is a merge a later change quietly breaks.
         */
        @Test
        void anOmittedComponentKeepsTheStoredValue() {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(EditResult.ok());

            controller.updateAccount(ACCOUNT_ID, requestRaising());

            AccountEntity proposed = proposedAccount();
            assertEquals(new BigDecimal("1010.00"), proposed.getCurrentBalance(),
                    "the balance was not submitted, so it stands as stored");
            assertEquals("2015-03-01", proposed.getOpenDate(), "and so does the open date");
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
                    .thenReturn(EditResult.ok());

            controller.updateAccount(ACCOUNT_ID, new AccountUpdateRequest(
                    new AccountDataRequest(null, null, "$12,000.00", null, null, null, null, null,
                            null, null),
                    customerData()));

            assertEquals(new BigDecimal("12000.00"), proposedAccount().getCreditLimit(),
                    "a currency sign and separators read as one amount");
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
                    .thenReturn(EditResult.ok());

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
                    .thenReturn(EditResult.ok());

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
         * for, so a body carrying one part alone leaves the column as stored. Over the Hypertext Transfer
         * Protocol a cross-field check on the block refuses that body outright, and this is the second
         * guard behind it.
         */
        @Test
        void theSocialSecurityPartsAreRecombinedOnlyTogether() {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(EditResult.ok());

            controller.updateAccount(ACCOUNT_ID, new AccountUpdateRequest(null,
                    customerDataWithSocialSecurity("429", null, null)));
            assertEquals("429541163", proposedCustomer().getSocialSecurityNumber(),
                    "one part alone leaves the stored number as it stands");

            buildController();
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(EditResult.ok());

            controller.updateAccount(ACCOUNT_ID, new AccountUpdateRequest(null,
                    customerDataWithSocialSecurity("111", "22", "3333")));
            assertEquals("111223333", proposedCustomer().getSocialSecurityNumber(),
                    "all three parts together write the column");
        }
    }

    // Fixtures and helpers.

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
        stored.setAddressZip("72112");
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
        stored.setSocialSecurityNumber("429541163");
        stored.setGovernmentIssuedId("AR8829114");
        stored.setDateOfBirth("1971-08-14");
        stored.setEftAccountId("4829571130");
        stored.setPrimaryCardHolderIndicator("Y");
        stored.setFicoCreditScore(new BigDecimal("688"));
        return stored;
    }
}
