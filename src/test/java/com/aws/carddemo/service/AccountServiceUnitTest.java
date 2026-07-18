/*
 * CardDemo — AWS mainframe (COBOL/CICS/VSAM) to Java 25 + Spring Boot re-platform.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.aws.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.CustomerRepository;
import com.aws.carddemo.service.AccountService.AccountDetail;
import com.aws.carddemo.service.AccountService.AccountUpdateCommand;
import com.aws.carddemo.service.AccountService.AccountUpdateResult;
import com.aws.carddemo.service.AccountService.DateParts;
import com.aws.carddemo.service.AccountService.PhoneParts;
import com.aws.carddemo.service.AccountService.SsnParts;
import com.aws.carddemo.service.AccountService.Status;
import com.aws.carddemo.service.rule.NumericRequiredRule;
import com.aws.carddemo.service.rule.UsSsnRule;

/**
 * Fast, dependency-isolated unit tests for {@link AccountService} — the re-platform
 * of the {@code COACTVWC} account inquiry ({@code CAVW}) and the {@code COACTUPC}
 * account-update pseudo-conversation ({@code CAUP}).
 *
 * <p><strong>Why this test exists (QA MAJOR-1 &amp; MINOR-3).</strong> The pre-existing
 * {@link AccountServiceTest} is a full {@code @SpringBootTest} + Testcontainers class that
 * only exercises the confirmed-write concurrency path; a large majority of the service —
 * the account-filter edit, the {@code 9000-READ-ACCT} not-found chain, the
 * {@code 2000-DECIDE-ACTION} re-entry state machine, and the twenty-four field edits of
 * {@code 1200-EDIT-MAP-INPUTS} — was uncovered, which the QA report proved by mutating
 * {@code viewAccount} to {@code return null} without any account test failing. These
 * Mockito unit tests close that gap by driving the real business logic against mocked
 * repositories, so the bundle-plus-per-package JaCoCo gate reflects genuine behavioral
 * coverage rather than incidental wiring coverage.</p>
 *
 * <p><strong>Deterministic clock (QA MINOR-3).</strong> The {@code EDIT-DATE-OF-BIRTH}
 * "must be in the past" rejection compared the entered date against the wall clock, so its
 * future-date branch could not be pinned. {@link AccountService} now takes an injected
 * {@link Clock}; every test here supplies a fixed clock at {@link #TODAY 2026-07-16} so the
 * future-date-of-birth branch is exercised deterministically against a known reference
 * date. This is a pure unit test: no Spring context and no database are started.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountService — COACTVWC inquiry & COACTUPC update (unit, mocked repositories)")
class AccountServiceUnitTest {

    /** Account identifier used by every fixture (matches the seeded row used elsewhere). */
    private static final long ACCT_ID = 1L;

    /** Owning customer identifier, linked to {@link #ACCT_ID} through the cross-reference. */
    private static final long CUST_ID = 1L;

    /** Card number of the cross-reference row that links the customer to the account. */
    private static final String CARD_NUM = "4000000000000001";

    /** The fixed "today" the injected {@link Clock} reports for the date-of-birth boundary. */
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 16);

    /** A valid, strictly-past date of birth (accepted by {@code EDIT-DATE-OF-BIRTH}). */
    private static final DateParts DOB_PAST = new DateParts("1980", "05", "20");

    /** The DOB value that {@link #customer} is seeded with (equal to {@link #DOB_PAST}). */
    private static final String CUST_DOB = "1980-05-20";

    /**
     * A valid calendar date that is in the future relative to {@link #TODAY}; it passes the
     * calendar-validity edit but must be rejected by the "must be in the past" DOB edit,
     * yielding the exact latched message {@link #MSG_DOB_FUTURE}.
     */
    private static final DateParts DOB_FUTURE = new DateParts("2027", "01", "01");

    /**
     * The exact caller-visible message produced when the date of birth is not in the past
     * ({@code FLD_DOB + SFX_DOB_FUTURE} in {@link AccountService}; both are private, so the
     * literal is reproduced here to lock the observable contract).
     */
    private static final String MSG_DOB_FUTURE = "Date of Birth:cannot be in the future ";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CardXrefRepository cardXrefRepository;

    /** The service under test, built with a fixed clock and the real (dependency-free) rules. */
    private AccountService service;

    /**
     * Builds the service with the mocked repositories, the real {@link DateValidationService}
     * and {@link UsSsnRule} (both dependency-light and deterministic), and a {@link Clock}
     * pinned to {@link #TODAY} so the DOB future-date branch is testable.
     */
    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        service = new AccountService(
                accountRepository,
                customerRepository,
                cardXrefRepository,
                new DateValidationService(),
                new UsSsnRule(new NumericRequiredRule()),
                fixedClock);
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    /** A seeded account whose columns equal the "no changes" command values. */
    private static Account account(Long version) {
        Account a = new Account(
                ACCT_ID,
                "Y",
                new BigDecimal("1234.56"),
                new BigDecimal("5000.00"),
                new BigDecimal("1000.00"),
                "2020-01-15",
                "2030-12-31",
                "2020-06-01",
                new BigDecimal("250.00"),
                new BigDecimal("75.25"),
                "20374",
                "A000000000");
        a.setVersion(version);
        return a;
    }

    /** A seeded customer whose columns equal the "no changes" command values. */
    private static Customer customer(String lastName) {
        return new Customer(
                CUST_ID,
                "Grace",
                "Brewster",
                lastName,
                "1 Navy Yard",
                null,
                "Washington",
                "DC",
                "USA",
                "20374",
                null,
                null,
                "123456789",
                "GID1",
                CUST_DOB,
                "1234567890",
                "Y",
                700);
    }

    /** The cross-reference row linking {@link #CUST_ID} to {@link #ACCT_ID}. */
    private static CardXref xref() {
        return new CardXref(CARD_NUM, CUST_ID, ACCT_ID);
    }

    /**
     * Stubs the full {@code 9000-READ-ACCT} chain (xref &rarr; account &rarr; customer) so a
     * read succeeds. {@code accountVersion} controls the version carried on the returned
     * account; {@code lastName} controls the customer surname (used to make a submitted
     * command differ or match).
     */
    private void stubReadChain(Long accountVersion, String lastName) {
        when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(ACCT_ID))
                .thenReturn(Optional.of(xref()));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account(accountVersion)));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer(lastName)));
    }

    /**
     * Builds a fully-valid account-update command; every field except those named is a value
     * that passes its {@code 1200-EDIT-MAP-INPUTS} edit and equals the seeded record, so only
     * the varied fields ({@code dob}, {@code lastName}, and the flow flags) can drive a
     * different outcome.
     */
    private static AccountUpdateCommand command(String accountId,
                                                Long expectedVersion,
                                                DateParts dob,
                                                String lastName,
                                                Status priorStatus,
                                                boolean confirmSave,
                                                boolean cancel) {
        return new AccountUpdateCommand(
                accountId,
                expectedVersion,
                "Y",
                new DateParts("2020", "01", "15"),
                "5000.00",
                new DateParts("2030", "12", "31"),
                "1000.00",
                new DateParts("2020", "06", "01"),
                "1234.56",
                "250.00",
                "75.25",
                "A000000000",
                new SsnParts("123", "45", "6789"),
                dob,
                "700",
                "Grace",
                "Brewster",
                lastName,
                "1 Navy Yard",
                "",
                "Washington",
                "DC",
                "20374",
                "USA",
                new PhoneParts("", "", ""),
                new PhoneParts("", "", ""),
                "GID1",
                "1234567890",
                "Y",
                priorStatus,
                confirmSave,
                cancel);
    }

    /** A blank (absent) phone number. */
    private static final PhoneParts NO_PHONE = new PhoneParts("", "", "");

    /**
     * A valid, {@code SHOW_DETAILS} re-entry command that differs from the seeded record only in
     * the supplied phone numbers, so the edit pass runs {@code 1260-EDIT-US-PHONE-NUM} on the
     * given values while every other edit passes.
     */
    private static AccountUpdateCommand phoneCommand(PhoneParts phone1, PhoneParts phone2) {
        return new AccountUpdateCommand(
                "1", null, "Y",
                new DateParts("2020", "01", "15"), "5000.00", new DateParts("2030", "12", "31"),
                "1000.00", new DateParts("2020", "06", "01"), "1234.56", "250.00", "75.25",
                "A000000000", new SsnParts("123", "45", "6789"), DOB_PAST, "700",
                "Grace", "Brewster", "Changed", "1 Navy Yard", "", "Washington", "DC", "20374", "USA",
                phone1, phone2, "GID1", "1234567890", "Y", Status.SHOW_DETAILS, false, false);
    }

    /**
     * A valid, {@code SHOW_DETAILS} re-entry command that differs from the seeded record only in
     * the four date fields, so {@code EDIT-DATE-CCYYMMDD} runs on each supplied value while every
     * other edit passes.
     */
    private static AccountUpdateCommand dateCommand(DateParts open, DateParts expiry,
                                                    DateParts reissue, DateParts dob) {
        return new AccountUpdateCommand(
                "1", null, "Y",
                open, "5000.00", expiry, "1000.00", reissue, "1234.56", "250.00", "75.25",
                "A000000000", new SsnParts("123", "45", "6789"), dob, "700",
                "Grace", "Brewster", "Changed", "1 Navy Yard", "", "Washington", "DC", "20374", "USA",
                NO_PHONE, NO_PHONE, "GID1", "1234567890", "Y", Status.SHOW_DETAILS, false, false);
    }

    /**
     * A valid, {@code SHOW_DETAILS} re-entry command that differs from the seeded record only in
     * the FICO score, so {@code 1275-EDIT-FICO-SCORE} runs on the given value while every other
     * edit passes.
     */
    private static AccountUpdateCommand ficoCommand(String fico) {
        return new AccountUpdateCommand(
                "1", null, "Y",
                new DateParts("2020", "01", "15"), "5000.00", new DateParts("2030", "12", "31"),
                "1000.00", new DateParts("2020", "06", "01"), "1234.56", "250.00", "75.25",
                "A000000000", new SsnParts("123", "45", "6789"), DOB_PAST, fico,
                "Grace", "Brewster", "Changed", "1 Navy Yard", "", "Washington", "DC", "20374", "USA",
                NO_PHONE, NO_PHONE, "GID1", "1234567890", "Y", Status.SHOW_DETAILS, false, false);
    }

    /**
     * A valid, {@code SHOW_DETAILS} re-entry command that differs from the seeded record only in
     * the state code and ZIP, so the cross-field {@code 1280-EDIT-US-STATE-ZIP-CD} check runs once
     * both individual edits have passed.
     */
    private static AccountUpdateCommand stateZipCommand(String state, String zip) {
        return new AccountUpdateCommand(
                "1", null, "Y",
                new DateParts("2020", "01", "15"), "5000.00", new DateParts("2030", "12", "31"),
                "1000.00", new DateParts("2020", "06", "01"), "1234.56", "250.00", "75.25",
                "A000000000", new SsnParts("123", "45", "6789"), DOB_PAST, "700",
                "Grace", "Brewster", "Brewster", "1 Navy Yard", "", "Washington", state, zip, "USA",
                NO_PHONE, NO_PHONE, "GID1", "1234567890", "Y", Status.SHOW_DETAILS, false, false);
    }

    // ========================================================================
    // COACTVWC — account inquiry (viewAccount)
    // ========================================================================

    @Nested
    @DisplayName("viewAccount (COACTVWC / CAVW)")
    class ViewAccount {

        @Test
        @DisplayName("returns the merged account+customer detail for a valid id (9000-READ-ACCT)")
        void returnsMergedDetail() {
            stubReadChain(0L, "Brewster");

            AccountDetail detail = service.viewAccount(ACCT_ID);

            assertThat(detail).isNotNull();
            assertThat(detail.account().getAcctId()).isEqualTo(ACCT_ID);
            assertThat(detail.customer().getCustId()).isEqualTo(CUST_ID);
            assertThat(detail.customer().getCustLastName()).isEqualTo("Brewster");
        }

        @Test
        @DisplayName("rejects a zero account id with the filter message (2210-EDIT-ACCOUNT)")
        void rejectsZeroId() {
            assertThatThrownBy(() -> service.viewAccount(0L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(AccountService.MSG_ACCT_FILTER_INVALID);
        }

        @Test
        @DisplayName("rejects a negative account id with the filter message")
        void rejectsNegativeId() {
            assertThatThrownBy(() -> service.viewAccount(-1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(AccountService.MSG_ACCT_FILTER_INVALID);
        }

        @Test
        @DisplayName("rejects an id wider than 11 digits with the filter message")
        void rejectsTooWideId() {
            assertThatThrownBy(() -> service.viewAccount(100_000_000_000L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(AccountService.MSG_ACCT_FILTER_INVALID);
        }

        @Test
        @DisplayName("throws NOTFND when the cross-reference is missing (9200-GETCARDXREF-BYACCT)")
        void throwsWhenXrefMissing() {
            when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(ACCT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.viewAccount(ACCT_ID))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessage("Account:00000000001 not found in Cross ref file.");
        }

        @Test
        @DisplayName("throws NOTFND when the account master is missing (9300-GETACCTDATA-BYACCT)")
        void throwsWhenAccountMissing() {
            when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(ACCT_ID))
                    .thenReturn(Optional.of(xref()));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.viewAccount(ACCT_ID))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessage("Account:00000000001 not found in Acct Master file.");
        }

        @Test
        @DisplayName("throws NOTFND when the customer master is missing (9400-GETCUSTDATA-BYCUST)")
        void throwsWhenCustomerMissing() {
            when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(ACCT_ID))
                    .thenReturn(Optional.of(xref()));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account(0L)));
            when(customerRepository.findById(CUST_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.viewAccount(ACCT_ID))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessage("CustId:000000001 not found in customer master.");
        }
    }

    // ========================================================================
    // COACTUPC — first entry (reentry == false): fetch-and-show
    // ========================================================================

    @Nested
    @DisplayName("updateAccount first entry (COACTUPC 0000-MAIN, reentry=false)")
    class FirstEntry {

        @Test
        @DisplayName("fetches and shows the details on first entry (ACUP-DETAILS-NOT-FETCHED)")
        void showsDetails() {
            stubReadChain(0L, "Brewster");

            AccountUpdateResult result =
                    service.updateAccount(command("1", null, DOB_PAST, "Brewster", null, false, false), false);

            assertThat(result.status()).isEqualTo(Status.SHOW_DETAILS);
            assertThat(result.message()).isEqualTo(AccountService.MSG_PROMPT_CHANGES);
            assertThat(result.detail()).isNotNull();
        }

        @Test
        @DisplayName("blank account id -> 'Account number not provided' (1210-EDIT-ACCOUNT)")
        void blankAccountId() {
            AccountUpdateResult result =
                    service.updateAccount(command("", null, DOB_PAST, "Brewster", null, false, false), false);

            assertThat(result.status()).isEqualTo(Status.CHANGES_NOT_OK);
            assertThat(result.message()).isEqualTo(AccountService.MSG_ACCT_NOT_PROVIDED);
            assertThat(result.detail()).isNull();
        }

        @Test
        @DisplayName("non-numeric account id -> 'non-zero 11 digit' message")
        void nonNumericAccountId() {
            AccountUpdateResult result =
                    service.updateAccount(command("12A", null, DOB_PAST, "Brewster", null, false, false), false);

            assertThat(result.status()).isEqualTo(Status.CHANGES_NOT_OK);
            assertThat(result.message()).isEqualTo(AccountService.MSG_ACCT_NON_ZERO_11);
            assertThat(result.detail()).isNull();
        }

        @Test
        @DisplayName("account id wider than 11 digits -> 'non-zero 11 digit' message")
        void tooWideAccountId() {
            AccountUpdateResult result =
                    service.updateAccount(command("123456789012", null, DOB_PAST, "Brewster", null, false, false), false);

            assertThat(result.status()).isEqualTo(Status.CHANGES_NOT_OK);
            assertThat(result.message()).isEqualTo(AccountService.MSG_ACCT_NON_ZERO_11);
        }

        @Test
        @DisplayName("zero account id -> 'non-zero 11 digit' message")
        void zeroAccountId() {
            AccountUpdateResult result =
                    service.updateAccount(command("0", null, DOB_PAST, "Brewster", null, false, false), false);

            assertThat(result.status()).isEqualTo(Status.CHANGES_NOT_OK);
            assertThat(result.message()).isEqualTo(AccountService.MSG_ACCT_NON_ZERO_11);
        }
    }

    // ========================================================================
    // COACTUPC — re-entry routing (2000-DECIDE-ACTION)
    // ========================================================================

    @Nested
    @DisplayName("updateAccount re-entry routing (COACTUPC 2000-DECIDE-ACTION, reentry=true)")
    class ReentryRouting {

        @Test
        @DisplayName("null prior status routes to a fresh fetch (defensive DETAILS-NOT-FETCHED)")
        void nullPriorRoutesToFetch() {
            AccountUpdateResult result =
                    service.updateAccount(command("", null, DOB_PAST, "Brewster", null, false, false), true);

            // A blank filter proves control reached fetchForUpdate rather than the state machine.
            assertThat(result.status()).isEqualTo(Status.CHANGES_NOT_OK);
            assertThat(result.message()).isEqualTo(AccountService.MSG_ACCT_NOT_PROVIDED);
        }

        @Test
        @DisplayName("PF12 cancel re-reads and re-shows, discarding edits (WHEN CCARD-AID-PFK12)")
        void cancelRoutesToFetch() {
            stubReadChain(0L, "Brewster");

            AccountUpdateResult result =
                    service.updateAccount(command("1", null, DOB_PAST, "Changed", Status.SHOW_DETAILS, false, true), true);

            assertThat(result.status()).isEqualTo(Status.SHOW_DETAILS);
            assertThat(result.message()).isEqualTo(AccountService.MSG_PROMPT_CHANGES);
        }

        @Test
        @DisplayName("a completed (DONE) screen resets to a fresh fetch on the next request")
        void donePriorRoutesToFetch() {
            stubReadChain(0L, "Brewster");

            AccountUpdateResult result =
                    service.updateAccount(command("1", null, DOB_PAST, "Brewster", Status.DONE, false, false), true);

            assertThat(result.status()).isEqualTo(Status.SHOW_DETAILS);
            assertThat(result.message()).isEqualTo(AccountService.MSG_PROMPT_CHANGES);
        }

        @Test
        @DisplayName("an unexpected (transient) prior state abends with 'UNEXPECTED DATA SCENARIO' (WHEN OTHER)")
        void unexpectedStateThrows() {
            assertThatThrownBy(() ->
                    service.updateAccount(command("1", null, DOB_PAST, "Brewster", Status.LOCK_ERROR, false, false), true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("UNEXPECTED DATA SCENARIO");
        }
    }

    // ========================================================================
    // COACTUPC — decide from SHOW-DETAILS / CHANGES-NOT-OK (1205 + 1200 edits)
    // ========================================================================

    @Nested
    @DisplayName("updateAccount edit decision (COACTUPC 1205-COMPARE-OLD-NEW + 1200-EDIT-MAP-INPUTS)")
    class DecideFromShowDetails {

        @Test
        @DisplayName("no functional change re-shows with the 'no changes' message (NO-CHANGES-DETECTED)")
        void unchangedShowsNoChanges() {
            stubReadChain(0L, "Brewster");

            AccountUpdateResult result =
                    service.updateAccount(command("1", null, DOB_PAST, "Brewster", Status.SHOW_DETAILS, false, false), true);

            assertThat(result.status()).isEqualTo(Status.SHOW_DETAILS);
            assertThat(result.message()).isEqualTo(AccountService.MSG_NO_CHANGES);
        }

        @Test
        @DisplayName("a valid change advances to the confirmation prompt (CHANGES-OK-NOT-CONFIRMED)")
        void validChangeAsksConfirmation() {
            stubReadChain(0L, "Brewster");

            AccountUpdateResult result =
                    service.updateAccount(command("1", null, DOB_PAST, "Changed", Status.SHOW_DETAILS, false, false), true);

            assertThat(result.status()).isEqualTo(Status.CHANGES_OK_NOT_CONFIRMED);
            assertThat(result.message()).isEqualTo(AccountService.MSG_PROMPT_CONFIRMATION);
        }

        @Test
        @DisplayName("a future date of birth is rejected in the past (EDIT-DATE-OF-BIRTH; QA MINOR-3)")
        void futureDateOfBirthRejected() {
            stubReadChain(0L, "Brewster");

            AccountUpdateResult result =
                    service.updateAccount(command("1", null, DOB_FUTURE, "Brewster", Status.SHOW_DETAILS, false, false), true);

            assertThat(result.status()).isEqualTo(Status.CHANGES_NOT_OK);
            assertThat(result.message()).isEqualTo(MSG_DOB_FUTURE);
        }

        @Test
        @DisplayName("a CHANGES-NOT-OK re-submit re-runs the edits (same branch as SHOW-DETAILS)")
        void changesNotOkReEdits() {
            stubReadChain(0L, "Brewster");

            AccountUpdateResult result =
                    service.updateAccount(command("1", null, DOB_PAST, "Changed", Status.CHANGES_NOT_OK, false, false), true);

            assertThat(result.status()).isEqualTo(Status.CHANGES_OK_NOT_CONFIRMED);
            assertThat(result.message()).isEqualTo(AccountService.MSG_PROMPT_CONFIRMATION);
        }
    }

    // ========================================================================
    // COACTUPC — confirmation & write (2000 WHEN CHANGES-OK-NOT-CONFIRMED)
    // ========================================================================

    @Nested
    @DisplayName("updateAccount confirmation & write (COACTUPC 9600-WRITE-PROCESSING)")
    class ConfirmationAndWrite {

        @Test
        @DisplayName("awaiting confirmation without PF05 re-shows the confirmation prompt")
        void reshowConfirmationWhenNotConfirmed() {
            stubReadChain(0L, "Brewster");

            AccountUpdateResult result =
                    service.updateAccount(command("1", null, DOB_PAST, "Changed", Status.CHANGES_OK_NOT_CONFIRMED, false, false), true);

            assertThat(result.status()).isEqualTo(Status.CHANGES_OK_NOT_CONFIRMED);
            assertThat(result.message()).isEqualTo(AccountService.MSG_PROMPT_CONFIRMATION);
        }

        @Test
        @DisplayName("PF05 confirm with the matching version commits and reports DONE")
        void confirmWritesDone() {
            when(accountRepository.findByIdForVersionedUpdate(ACCT_ID))
                    .thenReturn(Optional.of(account(0L)));
            when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(ACCT_ID))
                    .thenReturn(Optional.of(xref()));
            when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer("Brewster")));
            when(accountRepository.save(ArgumentMatchers.any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(customerRepository.save(ArgumentMatchers.any(Customer.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            AccountUpdateResult result =
                    service.updateAccount(command("1", 0L, DOB_PAST, "Changed", Status.CHANGES_OK_NOT_CONFIRMED, true, false), true);

            assertThat(result.status()).isEqualTo(Status.DONE);
            assertThat(result.message()).isEqualTo(AccountService.MSG_CONFIRM_SUCCESS);
            assertThat(result.detail().customer().getCustLastName()).isEqualTo("Changed");
        }

        @Test
        @DisplayName("PF05 confirm with a stale version is rejected (9700-CHECK-CHANGE-IN-REC -> 409)")
        void confirmStaleVersionRejected() {
            when(accountRepository.findByIdForVersionedUpdate(ACCT_ID))
                    .thenReturn(Optional.of(account(5L)));

            assertThatThrownBy(() ->
                    service.updateAccount(command("1", 3L, DOB_PAST, "Changed", Status.CHANGES_OK_NOT_CONFIRMED, true, false), true))
                    .isInstanceOf(OptimisticLockingFailureException.class)
                    .hasMessage(AccountService.MSG_DATA_CHANGED);
        }

        @Test
        @DisplayName("PF05 confirm re-runs the field edits defensively; a bad DOB re-shows CHANGES-NOT-OK")
        void confirmDefensiveEditFails() {
            when(accountRepository.findByIdForVersionedUpdate(ACCT_ID))
                    .thenReturn(Optional.of(account(0L)));
            when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(ACCT_ID))
                    .thenReturn(Optional.of(xref()));
            when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer("Brewster")));

            AccountUpdateResult result =
                    service.updateAccount(command("1", 0L, DOB_FUTURE, "Changed", Status.CHANGES_OK_NOT_CONFIRMED, true, false), true);

            assertThat(result.status()).isEqualTo(Status.CHANGES_NOT_OK);
            assertThat(result.message()).isEqualTo(MSG_DOB_FUTURE);
        }

        @Test
        @DisplayName("PF05 confirm throws NOTFND when the account row cannot be re-read for update")
        void confirmAccountMissing() {
            when(accountRepository.findByIdForVersionedUpdate(ACCT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.updateAccount(command("1", 0L, DOB_PAST, "Changed", Status.CHANGES_OK_NOT_CONFIRMED, true, false), true))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessage("Account:00000000001 not found in Acct Master file.");
        }
    }

    // ========================================================================
    // COACTUPC — field-edit failure branches (1200-EDIT-MAP-INPUTS)
    // ========================================================================

    /**
     * These tests drive {@link AccountService}'s field-edit pass ({@code 1200-EDIT-MAP-INPUTS})
     * through its failure branches — the parity-critical COBOL edit logic (yes/no, alphabetic,
     * mandatory, numeric-required, signed-decimal, date component + combination, FICO range,
     * phone area/prefix/line, state code, and the cross-field state+ZIP check). Every edit runs
     * on every submit (only the message is first-message-wins), so a single command carrying
     * several invalid fields exercises many branches at once. Each returns
     * {@link Status#CHANGES_NOT_OK} with the first latched message, exactly as the COBOL re-shows
     * the map with the first error.
     */
    @Nested
    @DisplayName("updateAccount field-edit failures (COACTUPC 1200-EDIT-MAP-INPUTS)")
    class EditRuleFailures {

        @Test
        @DisplayName("every scalar field invalid: first error is Account Status, all edits run")
        void allScalarEditsFail() {
            stubReadChain(0L, "Brewster");

            AccountUpdateCommand invalid = new AccountUpdateCommand(
                    "1", null,
                    "Q",                                    // accountStatus -> not Y/N
                    new DateParts("2020", "01", "15"),      // openDate valid
                    "ABC",                                  // creditLimit -> not a number
                    new DateParts("2030", "12", "31"),      // expiryDate valid
                    "1.2.3",                                // cashCreditLimit -> two decimal points
                    new DateParts("2020", "06", "01"),      // reissueDate valid
                    "+",                                    // currentBalance -> sign only, no digit
                    "",                                     // currentCycleCredit -> blank
                    "9X",                                   // currentCycleDebit -> non-numeric
                    "A000000000",                           // groupId (not edited)
                    new SsnParts("000", "12", "3456"),      // ssn -> reserved area '000'
                    DOB_PAST,                               // dob valid
                    "900",                                  // ficoScore -> above 850
                    "123",                                  // firstName -> not alphabetic
                    "1$",                                   // middleName -> not alphabetic
                    "9",                                    // lastName -> not alphabetic (also a change)
                    "",                                     // addressLine1 -> mandatory, blank
                    "",                                     // addressLine2 (optional)
                    "1",                                    // city -> not alphabetic
                    "ZZ",                                   // stateCode -> alphabetic but not a real state
                    "9X9",                                  // zipCode -> non-numeric
                    "2",                                    // countryCode -> not alphabetic
                    new PhoneParts("1X3", "", ""),          // phone1 -> area non-digit, prefix+line missing
                    new PhoneParts("000", "9X9", "12X4"),   // phone2 -> area zero, prefix+line non-digit
                    "GID1",
                    "000",                                  // eftAccountId -> numeric but zero
                    "Q",                                    // primaryHolderFlag -> not Y/N
                    Status.SHOW_DETAILS, false, false);

            AccountUpdateResult result = service.updateAccount(invalid, true);

            assertThat(result.status()).isEqualTo(Status.CHANGES_NOT_OK);
            assertThat(result.message()).startsWith("Account Status");
        }

        @Test
        @DisplayName("phone edits: area-supplied, NANPA area, zero prefix, and zero line all latch")
        void phoneEditsFail() {
            stubReadChain(0L, "Brewster");

            AccountUpdateResult result = service.updateAccount(
                    phoneCommand(
                            new PhoneParts("", "123", "4567"),     // phone1 -> area missing (prefix present)
                            new PhoneParts("211", "000", "0000")), // phone2 -> N11 area, zero prefix, zero line
                    true);

            assertThat(result.status()).isEqualTo(Status.CHANGES_NOT_OK);
            assertThat(result.message()).contains("Area code must be supplied");
        }

        @ParameterizedTest(name = "FICO \"{0}\" is rejected")
        @ValueSource(strings = {"9X0", "000", ""})
        @DisplayName("FICO edits: non-numeric, zero, and blank are each rejected (1245 + 1275)")
        void ficoEditsFail(String fico) {
            stubReadChain(0L, "Brewster");

            AccountUpdateResult result = service.updateAccount(ficoCommand(fico), true);

            assertThat(result.status()).isEqualTo(Status.CHANGES_NOT_OK);
            assertThat(result.message()).startsWith("FICO Score");
        }

        @Test
        @DisplayName("date edits A: empty parts, non-4-digit year, out-of-range month/day, bad century, 31-day month")
        void dateEditsFailGroupA() {
            stubReadChain(0L, "Brewster");

            AccountUpdateResult result = service.updateAccount(
                    dateCommand(
                            new DateParts("", "", ""),          // open -> year/month/day all missing
                            new DateParts("123", "13", "40"),   // expiry -> 3-digit year, month 13, day 40
                            new DateParts("1820", "06", "15"),  // reissue -> century 18 invalid
                            new DateParts("2020", "04", "31")), // dob -> 31st of a 30-day month
                    true);

            assertThat(result.status()).isEqualTo(Status.CHANGES_NOT_OK);
            assertThat(result.message()).startsWith("Open Date");
        }

        @Test
        @DisplayName("date edits B: Feb 29 non-leap, Feb 30, and Feb 29 leap-year (valid) combination checks")
        void dateEditsFailGroupB() {
            stubReadChain(0L, "Brewster");

            AccountUpdateResult result = service.updateAccount(
                    dateCommand(
                            new DateParts("2019", "02", "29"),  // open -> Feb 29 in a non-leap year
                            new DateParts("2020", "02", "30"),  // expiry -> Feb 30 never exists
                            new DateParts("2020", "02", "29"),  // reissue -> Feb 29 in a leap year (valid)
                            new DateParts("2020", "06", "15")), // dob -> valid past date
                    true);

            assertThat(result.status()).isEqualTo(Status.CHANGES_NOT_OK);
            assertThat(result.message()).startsWith("Open Date");
        }

        @Test
        @DisplayName("cross-field state+ZIP check rejects a valid state with an inconsistent ZIP (1280)")
        void stateZipComboFails() {
            stubReadChain(0L, "Brewster");

            // "CA" is a valid state and "5" is a valid non-zero numeric ZIP individually, but the
            // ZIP has no two-digit prefix, so the state/ZIP combination edit rejects it.
            AccountUpdateResult result = service.updateAccount(stateZipCommand("CA", "5"), true);

            assertThat(result.status()).isEqualTo(Status.CHANGES_NOT_OK);
            assertThat(result.message()).isEqualTo("Invalid zip code for state");
        }
    }

    // ========================================================================
    // Null-argument contract
    // ========================================================================

    @Test
    @DisplayName("a null command is rejected (Objects.requireNonNull contract)")
    void nullCommandRejected() {
        assertThatThrownBy(() -> service.updateAccount(null, false))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("command");
    }
}
