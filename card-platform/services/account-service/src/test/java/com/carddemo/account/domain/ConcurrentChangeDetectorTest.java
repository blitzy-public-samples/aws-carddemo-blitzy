package com.carddemo.account.domain;

import com.carddemo.account.config.AccountProperties;
import com.carddemo.account.config.ObservabilityConfig;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.account.outbox.OutboxWriter;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.AccountCustomerLinkRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.Version;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Tests for {@link ConcurrentChangeDetector}, the re-read comparison of paragraph
 * {@code 9700-CHECK-CHANGE-IN-REC} at app/cbl/COACTUPC.cbl:L4109-L4195.
 *
 * <p>The paragraph runs two conditions. The first reads ten account fields at
 * app/cbl/COACTUPC.cbl:L4115-L4140. The second reads seventeen customer fields at
 * app/cbl/COACTUPC.cbl:L4152-L4186. Either condition failing sets
 * {@code DATA-WAS-CHANGED-BEFORE-UPDATE}, at app/cbl/COACTUPC.cbl:L4143 for the account condition
 * and app/cbl/COACTUPC.cbl:L4189 for the customer one. The caller reads that flag at
 * app/cbl/COACTUPC.cbl:L3950, after the call at app/cbl/COACTUPC.cbl:L3947-L3948.</p>
 *
 * <p>The customer count closes at seventeen. app/cpy/CVCUS01Y.cpy:L5-L22 declares eighteen mapped
 * fields, and the condition reads every one except the {@code CUST-ID} key.</p>
 *
 * <p>The date of birth reads one offset set on the re-read side and a second on the saved side, at
 * app/cbl/COACTUPC.cbl:L4174-L4179. Both failure branches, at app/cbl/COACTUPC.cbl:L4144 and
 * app/cbl/COACTUPC.cbl:L4190, jump to app/cbl/COACTUPC.cbl:L4105, which precedes them.</p>
 *
 * <p>Paragraph {@code 1205-COMPARE-OLD-NEW} sits outside this class. That paragraph asks whether
 * the caller supplied a new value, and {@code 9700-CHECK-CHANGE-IN-REC} asks whether the stored
 * record moved under the caller.</p>
 *
 * <p>No Spring context, no container and no database take part. Every value here is a literal, and
 * no method reads a file.</p>
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("ConcurrentChangeDetector, the re-read comparison at app/cbl/COACTUPC.cbl:L4109-L4195")
class ConcurrentChangeDetectorTest {

    /** The unit under test, a stateless predicate over two entity pairs. */
    private static final ConcurrentChangeDetector DETECTOR = new ConcurrentChangeDetector();

    /**
     * Account identifier, {@value PicClause#ACCT_ID_WIDTH} digits from app/cpy/CVACT01Y.cpy:L5.
     */
    private static final String ACCOUNT_ID = "00000000001";

    /** A second account identifier, one digit away from {@link #ACCOUNT_ID}. */
    private static final String OTHER_ACCOUNT_ID = "00000000002";

    /** Active status, one character from app/cpy/CVACT01Y.cpy:L6. */
    private static final String ACTIVE_STATUS = "Y";

    /** Current balance at the scale app/cpy/CVACT01Y.cpy:L7 declares. */
    private static final BigDecimal CURRENT_BALANCE = new BigDecimal("1940.00");

    /** Credit limit at the scale app/cpy/CVACT01Y.cpy:L8 declares. */
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("20200.00");

    /** Cash credit limit at the scale app/cpy/CVACT01Y.cpy:L9 declares. */
    private static final BigDecimal CASH_CREDIT_LIMIT = new BigDecimal("10200.00");

    /** Open date, ten characters from app/cpy/CVACT01Y.cpy:L10. */
    private static final String OPEN_DATE = "2014-11-20";

    /** Expiry date, ten characters from app/cpy/CVACT01Y.cpy:L11. */
    private static final String EXPIRATION_DATE = "2025-05-20";

    /** Reissue date, ten characters from app/cpy/CVACT01Y.cpy:L12. */
    private static final String REISSUE_DATE = "2025-05-20";

    /** Cycle credit accumulator at the scale app/cpy/CVACT01Y.cpy:L13 declares. */
    private static final BigDecimal CURRENT_CYCLE_CREDIT = new BigDecimal("500.00");

    /** Cycle debit accumulator at the scale app/cpy/CVACT01Y.cpy:L14 declares. */
    private static final BigDecimal CURRENT_CYCLE_DEBIT = new BigDecimal("250.00");

    /** Account postal code, ten characters from app/cpy/CVACT01Y.cpy:L15. */
    private static final String ACCOUNT_ADDRESS_ZIP = "27604     ";

    /**
     * Group identifier from app/cpy/CVACT01Y.cpy:L16, mixed case and at the full declared width of
     * {@value PicClause#ACCT_GROUP_ID_WIDTH}.
     */
    private static final String GROUP_ID = "Premium001";

    /** {@link #GROUP_ID} in upper case, the same characters and a different case. */
    private static final String GROUP_ID_IN_UPPER_CASE = "PREMIUM001";

    /** {@link #GROUP_ID} carrying one trailing space past the declared width. */
    private static final String GROUP_ID_WITH_TRAILING_SPACE = "Premium001 ";

    /** A group identifier holding characters {@link #GROUP_ID} does not. */
    private static final String OTHER_GROUP_ID = "Standard01";

    /**
     * Customer identifier, {@value PicClause#CUST_ID_WIDTH} digits from app/cpy/CVCUS01Y.cpy:L5.
     * Record 1 of app/data/ASCII/custdata.txt carries this value.
     */
    private static final String CUSTOMER_ID = "000000001";

    /** A second customer identifier, one digit away from {@link #CUSTOMER_ID}. */
    private static final String OTHER_CUSTOMER_ID = "000000002";

    /** First name from app/cpy/CVCUS01Y.cpy:L6, as record 1 of the customer fixture carries it. */
    private static final String FIRST_NAME = "Immanuel";

    /** Middle name from app/cpy/CVCUS01Y.cpy:L7. */
    private static final String MIDDLE_NAME = "Madeline";

    /** Last name from app/cpy/CVCUS01Y.cpy:L8. */
    private static final String LAST_NAME = "Kessler";

    /** Address line 1 from app/cpy/CVCUS01Y.cpy:L9. */
    private static final String ADDRESS_LINE_1 = "618 Deshaun Route";

    /** Address line 2 from app/cpy/CVCUS01Y.cpy:L10. */
    private static final String ADDRESS_LINE_2 = "Apt. 802";

    /** Address line 3 from app/cpy/CVCUS01Y.cpy:L11. The target names the field the city. */
    private static final String ADDRESS_CITY = "Altenwerthshire";

    /** State code, two characters from app/cpy/CVCUS01Y.cpy:L12. */
    private static final String ADDRESS_STATE_CODE = "NC";

    /** Country code, three characters from app/cpy/CVCUS01Y.cpy:L13. */
    private static final String ADDRESS_COUNTRY_CODE = "USA";

    /**
     * Customer postal code from app/cpy/CVCUS01Y.cpy:L14, padded to the declared ten characters as
     * offsets 240 through 249 of the fixture record carry it.
     */
    private static final String CUSTOMER_ADDRESS_ZIP = "12546     ";

    /**
     * Telephone number 1 from app/cpy/CVCUS01Y.cpy:L15. Offsets 250 through 264 of record 1 of
     * app/data/ASCII/custdata.txt carry these fifteen characters, the last two of them spaces.
     */
    private static final String PHONE_NUMBER_1 = "(908)119-8310  ";

    /** Telephone number 2 from app/cpy/CVCUS01Y.cpy:L16, fifteen characters. */
    private static final String PHONE_NUMBER_2 = "(373)693-8684  ";

    /**
     * Social Security Number, {@value PicClause#CUST_SSN_WIDTH} digits from
     * app/cpy/CVCUS01Y.cpy:L17.
     */
    private static final String SOCIAL_SECURITY_NUMBER = syntheticSocialSecurityNumber(1);

    /** Government-issued identifier, twenty characters from app/cpy/CVCUS01Y.cpy:L18. */
    private static final String GOVERNMENT_ISSUED_ID = syntheticGovernmentIssuedId("TEST", 1);

    /**
     * A government-issued identifier opening with two letters, within the twenty characters
     * app/cpy/CVCUS01Y.cpy:L18 declares. A case fold reaches the two letters and leaves the
     * digits.
     */
    private static final String LETTER_BEARING_GOVERNMENT_ISSUED_ID =
            syntheticGovernmentIssuedId("AB", 2);

    /**
     * Date of birth from app/cpy/CVCUS01Y.cpy:L19, {@value PicClause#CUST_DOB_WIDTH} characters
     * holding two separators. Offsets 309 through 318 of record 1 of app/data/ASCII/custdata.txt
     * carry this value.
     */
    private static final String DATE_OF_BIRTH = "1961-06-08";

    /**
     * {@link #DATE_OF_BIRTH} in the eight-character form of
     * {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD PIC X(08)} at app/cbl/COACTUPC.cbl:L746, whose year,
     * month and day parts sit at app/cbl/COACTUPC.cbl:L749-L751 and carry no separator.
     */
    private static final String UNSEPARATED_DATE_OF_BIRTH = "19610608";

    /**
     * Declared width of {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD PIC X(08)} at
     * app/cbl/COACTUPC.cbl:L746.
     */
    private static final int SAVED_DATE_OF_BIRTH_WIDTH = 8;

    /** Electronic funds transfer account identifier from app/cpy/CVCUS01Y.cpy:L20. */
    private static final String EFT_ACCOUNT_ID = "0053581756";

    /** Primary card holder indicator, one character from app/cpy/CVCUS01Y.cpy:L21. */
    private static final String PRIMARY_CARD_HOLDER_INDICATOR = "Y";

    /**
     * {@link #PRIMARY_CARD_HOLDER_INDICATOR} in lower case, the same character and a different
     * case.
     */
    private static final String PRIMARY_CARD_HOLDER_INDICATOR_IN_LOWER_CASE = "y";

    /** Credit score from app/cpy/CVCUS01Y.cpy:L22. FICO names a credit score. */
    private static final BigDecimal FICO_CREDIT_SCORE = new BigDecimal("274");

    /** The separator the ten-character dates carry at index 4 and index 7. */
    private static final char DATE_SEPARATOR = '-';

    /** A separator character the ten-character dates do not carry. */
    private static final char OTHER_DATE_SEPARATOR = '/';

    /**
     * Half of the smallest unit the two-place account amounts hold. Truncation toward zero drops
     * this addend and half-up rounding carries it to the next unit.
     */
    private static final BigDecimal HALF_OF_ONE_CENT = new BigDecimal("0.005");

    /** One cent, the value half-up rounding produces from {@link #HALF_OF_ONE_CENT}. */
    private static final BigDecimal ONE_CENT = new BigDecimal("0.01");

    /** Sixteen leading zeros ahead of an amount. A leading zero changes no value and no scale. */
    private static final String LEADING_ZERO_PADDING = "0000000000000000";

    /** Two zeros past the two-place scale. Each raises the scale and changes no value. */
    private static final String TRAILING_ZERO_PADDING = "00";

    /** The message text of app/cbl/COACTUPC.cbl:L522, carried character for character. */
    private static final String SOURCE_MESSAGE_TEXT =
            "Record changed by some one else. Please review";

    /** Character count of the text at app/cbl/COACTUPC.cbl:L522. */
    private static final int SOURCE_MESSAGE_LENGTH = 46;

    /** The two-word spelling app/cbl/COACTUPC.cbl:L522 carries. */
    private static final String TWO_WORD_SPELLING = "some one";

    /** The one-word spelling app/cbl/COACTUPC.cbl:L522 does not carry. */
    private static final String ONE_WORD_SPELLING = "someone";

    /**
     * Builds a fully populated account record holding every field of
     * {@code ACCOUNT-RECORD} at app/cpy/CVACT01Y.cpy:L4 that the target maps. Each call returns a
     * fresh instance, and a caller mutates one side of a pair and leaves the other untouched.
     *
     * @return an account record carrying the constants of this class
     */
    private static AccountEntity account() {
        AccountEntity account = new AccountEntity();
        account.setAccountId(ACCOUNT_ID);
        account.setActiveStatus(ACTIVE_STATUS);
        account.setCurrentBalance(CURRENT_BALANCE);
        account.setCreditLimit(CREDIT_LIMIT);
        account.setCashCreditLimit(CASH_CREDIT_LIMIT);
        account.setOpenDate(OPEN_DATE);
        account.setExpirationDate(EXPIRATION_DATE);
        account.setReissueDate(REISSUE_DATE);
        account.setCurrentCycleCredit(CURRENT_CYCLE_CREDIT);
        account.setCurrentCycleDebit(CURRENT_CYCLE_DEBIT);
        account.setAddressZip(ACCOUNT_ADDRESS_ZIP);
        account.setGroupId(GROUP_ID);
        return account;
    }

    /**
     * Builds a fully populated customer record holding every field of
     * {@code CUSTOMER-RECORD} at app/cpy/CVCUS01Y.cpy:L4 that the target maps. Each call returns a
     * fresh instance.
     *
     * @return a customer record carrying the constants of this class
     */
    private static CustomerEntity customer() {
        CustomerEntity customer = new CustomerEntity();
        customer.setCustomerId(CUSTOMER_ID);
        customer.setFirstName(FIRST_NAME);
        customer.setMiddleName(MIDDLE_NAME);
        customer.setLastName(LAST_NAME);
        customer.setAddressLine1(ADDRESS_LINE_1);
        customer.setAddressLine2(ADDRESS_LINE_2);
        customer.setAddressCity(ADDRESS_CITY);
        customer.setAddressStateCode(ADDRESS_STATE_CODE);
        customer.setAddressCountryCode(ADDRESS_COUNTRY_CODE);
        customer.setAddressZip(CUSTOMER_ADDRESS_ZIP);
        customer.setPhoneNumber1(PHONE_NUMBER_1);
        customer.setPhoneNumber2(PHONE_NUMBER_2);
        customer.setSocialSecurityNumber(SOCIAL_SECURITY_NUMBER);
        customer.setGovernmentIssuedId(GOVERNMENT_ISSUED_ID);
        customer.setDateOfBirth(DATE_OF_BIRTH);
        customer.setEftAccountId(EFT_ACCOUNT_ID);
        customer.setPrimaryCardHolderIndicator(PRIMARY_CARD_HOLDER_INDICATOR);
        customer.setFicoCreditScore(FICO_CREDIT_SCORE);
        return customer;
    }

    /**
     * Applies one mutation to the re-read account and leaves the earlier-read account and both
     * customer records identical. The verdict then follows the account condition at
     * app/cbl/COACTUPC.cbl:L4115-L4140 alone.
     *
     * @param mutation the single field change the re-read record carries
     * @return the verdict {@link ConcurrentChangeDetector#storedRecordChanged} returns
     */
    private static boolean accountChanged(Consumer<AccountEntity> mutation) {
        AccountEntity reRead = account();
        mutation.accept(reRead);
        return DETECTOR.storedRecordChanged(reRead, customer(), account(), customer());
    }

    /**
     * Applies one mutation to each side of the account pair and leaves both customer records
     * identical.
     *
     * @param reReadMutation the field change the re-read record carries
     * @param fetchedMutation the field change the earlier-read record carries
     * @return the verdict {@link ConcurrentChangeDetector#storedRecordChanged} returns
     */
    private static boolean accountChanged(Consumer<AccountEntity> reReadMutation,
            Consumer<AccountEntity> fetchedMutation) {
        AccountEntity reRead = account();
        AccountEntity fetched = account();
        reReadMutation.accept(reRead);
        fetchedMutation.accept(fetched);
        return DETECTOR.storedRecordChanged(reRead, customer(), fetched, customer());
    }

    /**
     * Applies one mutation to the re-read customer and leaves the earlier-read customer and both
     * account records identical. The verdict then follows the customer condition at
     * app/cbl/COACTUPC.cbl:L4152-L4186 alone.
     *
     * @param mutation the single field change the re-read record carries
     * @return the verdict {@link ConcurrentChangeDetector#storedRecordChanged} returns
     */
    private static boolean customerChanged(Consumer<CustomerEntity> mutation) {
        CustomerEntity reRead = customer();
        mutation.accept(reRead);
        return DETECTOR.storedRecordChanged(account(), reRead, account(), customer());
    }

    /**
     * Applies one mutation to each side of the customer pair and leaves both account records
     * identical.
     *
     * @param reReadMutation the field change the re-read record carries
     * @param fetchedMutation the field change the earlier-read record carries
     * @return the verdict {@link ConcurrentChangeDetector#storedRecordChanged} returns
     */
    private static boolean customerChanged(Consumer<CustomerEntity> reReadMutation,
            Consumer<CustomerEntity> fetchedMutation) {
        CustomerEntity reRead = customer();
        CustomerEntity fetched = customer();
        reReadMutation.accept(reRead);
        fetchedMutation.accept(fetched);
        return DETECTOR.storedRecordChanged(account(), reRead, account(), fetched);
    }

    /**
     * Returns a ten-character date with {@link #DATE_SEPARATOR} swapped for
     * {@link #OTHER_DATE_SEPARATOR} at both separator positions.
     *
     * @param tenCharacterDate the date to alter
     * @return the date carrying the other separator character
     */
    private static String withOtherSeparators(String tenCharacterDate) {
        return tenCharacterDate.replace(DATE_SEPARATOR, OTHER_DATE_SEPARATOR);
    }

    @Test
    @DisplayName("Two identical pairs report no change, the CONTINUE path of "
            + "app/cbl/COACTUPC.cbl:L4141 and app/cbl/COACTUPC.cbl:L4187")
    void identicalPairsReportNoChange() {
        assertThat(DETECTOR.storedRecordChanged(account(), customer(), account(), customer()))
                .isFalse();
    }

    @Test
    @DisplayName("the source comparator omits the account identifier; the update boundary owns it")
    void sourceComparatorOmitsTheAccountIdentifier() {
        assertThat(accountChanged(account -> account.setAccountId(OTHER_ACCOUNT_ID))).isFalse();
    }

    @Test
    @DisplayName("the source comparator omits the customer identifier; the update boundary owns it")
    void sourceComparatorOmitsTheCustomerIdentifier() {
        assertThat(customerChanged(customer -> customer.setCustomerId(OTHER_CUSTOMER_ID)))
                .isFalse();
    }

    @Nested
    @DisplayName("Identifier ownership at the update boundary")
    class IdentifierOwnership {

        @Test
        @DisplayName("a submitted customer identifier outside the fetched pair is refused")
        void aMismatchedCustomerIdentifierIsRefusedBeforeAnyWrite() {
            AccountRepository accounts = mock(AccountRepository.class);
            CustomerRepository customers = mock(CustomerRepository.class);
            OutboxWriter outbox = mock(OutboxWriter.class);
            AccountUpdateService updateService = updateService(accounts, customers, outbox);
            AccountEntity proposedAccount = account();
            CustomerEntity proposedCustomer = customer();
            AccountEntity fetchedAccount = account();
            CustomerEntity fetchedCustomer = customer();
            proposedCustomer.setCustomerId(OTHER_CUSTOMER_ID);

            var result = updateService.updateAccount(
                    proposedAccount, proposedCustomer, fetchedAccount, fetchedCustomer);

            assertThat(result.valid()).isFalse();
            assertThat(result.message())
                    .isEqualTo(AccountUpdateService.IDENTIFIER_OWNERSHIP_MISMATCH);
            verifyNoInteractions(accounts, customers, outbox);
        }

        @Test
        @DisplayName("a submitted account identifier outside the fetched pair is refused")
        void aMismatchedAccountIdentifierIsRefusedBeforeAnyWrite() {
            AccountRepository accounts = mock(AccountRepository.class);
            CustomerRepository customers = mock(CustomerRepository.class);
            OutboxWriter outbox = mock(OutboxWriter.class);
            AccountUpdateService updateService = updateService(accounts, customers, outbox);
            AccountEntity proposedAccount = account();
            CustomerEntity proposedCustomer = customer();
            AccountEntity fetchedAccount = account();
            CustomerEntity fetchedCustomer = customer();
            proposedAccount.setAccountId(OTHER_ACCOUNT_ID);

            var result = updateService.updateAccount(
                    proposedAccount, proposedCustomer, fetchedAccount, fetchedCustomer);

            assertThat(result.valid()).isFalse();
            assertThat(result.message())
                    .isEqualTo(AccountUpdateService.IDENTIFIER_OWNERSHIP_MISMATCH);
            verifyNoInteractions(accounts, customers, outbox);
        }

        private AccountUpdateService updateService(AccountRepository accounts,
                CustomerRepository customers, OutboxWriter outbox) {
            return new AccountUpdateService(accounts, customers,
                    mock(AccountCustomerLinkRepository.class), DETECTOR, outbox,
                    immediateTransactions(), accountMeters(), accountProperties());
        }
    }

    /**
     * The ten comparisons of the first condition, one test each, in the order
     * app/cbl/COACTUPC.cbl:L4115-L4140 tests them. Each test changes one field of the re-read
     * record and leaves the other nine identical.
     *
     * <p>The three dates read slices of the re-read record against separately declared year, month
     * and day fields of the saved copy, at app/cbl/COACTUPC.cbl:L684-L701. Only the date of birth
     * slices both sides.</p>
     */
    @Nested
    @DisplayName("Account condition, the ten comparisons at app/cbl/COACTUPC.cbl:L4115-L4140")
    class AccountCondition {

        @Test
        @DisplayName("1. Active status, app/cbl/COACTUPC.cbl:L4115")
        void activeStatusChangeIsReported() {
            assertThat(accountChanged(account -> account.setActiveStatus("N"))).isTrue();
        }

        @Test
        @DisplayName("2. Current balance, app/cbl/COACTUPC.cbl:L4117")
        void currentBalanceChangeIsReported() {
            assertThat(accountChanged(
                    account -> account.setCurrentBalance(new BigDecimal("1940.01")))).isTrue();
        }

        @Test
        @DisplayName("3. Credit limit, app/cbl/COACTUPC.cbl:L4119")
        void creditLimitChangeIsReported() {
            assertThat(accountChanged(
                    account -> account.setCreditLimit(new BigDecimal("20200.01")))).isTrue();
        }

        @Test
        @DisplayName("4. Cash credit limit, app/cbl/COACTUPC.cbl:L4121")
        void cashCreditLimitChangeIsReported() {
            assertThat(accountChanged(
                    account -> account.setCashCreditLimit(new BigDecimal("10200.01")))).isTrue();
        }

        @Test
        @DisplayName("5. Cycle credit accumulator, app/cbl/COACTUPC.cbl:L4123")
        void currentCycleCreditChangeIsReported() {
            assertThat(accountChanged(
                    account -> account.setCurrentCycleCredit(new BigDecimal("500.01")))).isTrue();
        }

        @Test
        @DisplayName("6. Cycle debit accumulator, app/cbl/COACTUPC.cbl:L4125")
        void currentCycleDebitChangeIsReported() {
            assertThat(accountChanged(
                    account -> account.setCurrentCycleDebit(new BigDecimal("250.01")))).isTrue();
        }

        @Test
        @DisplayName("7. Open date, the three slices at app/cbl/COACTUPC.cbl:L4127-L4129")
        void openDateChangeIsReported() {
            assertThat(accountChanged(account -> account.setOpenDate("2014-11-21"))).isTrue();
        }

        @Test
        @DisplayName("8. Expiry date, the three slices at app/cbl/COACTUPC.cbl:L4131-L4133")
        void expirationDateChangeIsReported() {
            assertThat(accountChanged(account -> account.setExpirationDate("2025-05-21"))).isTrue();
        }

        @Test
        @DisplayName("9. Reissue date, the three slices at app/cbl/COACTUPC.cbl:L4135-L4137")
        void reissueDateChangeIsReported() {
            assertThat(accountChanged(account -> account.setReissueDate("2025-05-21"))).isTrue();
        }

        @Test
        @DisplayName("10. Group identifier folded to lower case, "
                + "app/cbl/COACTUPC.cbl:L4139-L4140")
        void groupIdentifierChangeIsReported() {
            assertThat(accountChanged(account -> account.setGroupId(OTHER_GROUP_ID))).isTrue();
        }
    }

    /**
     * The seventeen comparisons of the second condition, one test each, in the order
     * app/cbl/COACTUPC.cbl:L4152-L4186 tests them. Each test changes one field of the re-read
     * record and leaves the other sixteen identical.
     *
     * <p>The order holds the government-issued identifier at position thirteen, after the postal
     * code, the two telephone numbers and the Social Security Number.</p>
     */
    @Nested
    @DisplayName("Customer condition, the seventeen comparisons at "
            + "app/cbl/COACTUPC.cbl:L4152-L4186")
    class CustomerCondition {

        @Test
        @DisplayName("1. First name folded to upper case, app/cbl/COACTUPC.cbl:L4152-L4153")
        void firstNameChangeIsReported() {
            assertThat(customerChanged(customer -> customer.setFirstName("Immanuela"))).isTrue();
        }

        @Test
        @DisplayName("2. Middle name folded to upper case, app/cbl/COACTUPC.cbl:L4154-L4155")
        void middleNameChangeIsReported() {
            assertThat(customerChanged(customer -> customer.setMiddleName("Madelines"))).isTrue();
        }

        @Test
        @DisplayName("3. Last name folded to upper case, app/cbl/COACTUPC.cbl:L4156-L4157")
        void lastNameChangeIsReported() {
            assertThat(customerChanged(customer -> customer.setLastName("Kesslers"))).isTrue();
        }

        @Test
        @DisplayName("4. Address line 1 folded to upper case, app/cbl/COACTUPC.cbl:L4158-L4159")
        void addressLine1ChangeIsReported() {
            assertThat(customerChanged(
                    customer -> customer.setAddressLine1("619 Deshaun Route"))).isTrue();
        }

        @Test
        @DisplayName("5. Address line 2 folded to upper case, app/cbl/COACTUPC.cbl:L4160-L4161")
        void addressLine2ChangeIsReported() {
            assertThat(customerChanged(customer -> customer.setAddressLine2("Apt. 803"))).isTrue();
        }

        @Test
        @DisplayName("6. Address line 3 folded to upper case, app/cbl/COACTUPC.cbl:L4162-L4163")
        void addressCityChangeIsReported() {
            assertThat(customerChanged(
                    customer -> customer.setAddressCity("Altenwerthville"))).isTrue();
        }

        @Test
        @DisplayName("7. State code folded to upper case, app/cbl/COACTUPC.cbl:L4164-L4165")
        void addressStateCodeChangeIsReported() {
            assertThat(customerChanged(customer -> customer.setAddressStateCode("SC"))).isTrue();
        }

        @Test
        @DisplayName("8. Country code folded to upper case, app/cbl/COACTUPC.cbl:L4166-L4167")
        void addressCountryCodeChangeIsReported() {
            assertThat(customerChanged(
                    customer -> customer.setAddressCountryCode("CAN"))).isTrue();
        }

        @Test
        @DisplayName("9. Postal code, app/cbl/COACTUPC.cbl:L4168")
        void addressZipChangeIsReported() {
            assertThat(customerChanged(customer -> customer.setAddressZip("12547     "))).isTrue();
        }

        @Test
        @DisplayName("10. Telephone number 1, app/cbl/COACTUPC.cbl:L4169")
        void phoneNumber1ChangeIsReported() {
            assertThat(customerChanged(
                    customer -> customer.setPhoneNumber1("(908)119-8311  "))).isTrue();
        }

        @Test
        @DisplayName("11. Telephone number 2, app/cbl/COACTUPC.cbl:L4170")
        void phoneNumber2ChangeIsReported() {
            assertThat(customerChanged(
                    customer -> customer.setPhoneNumber2("(373)693-8685  "))).isTrue();
        }

        @Test
        @DisplayName("12. Social Security Number, app/cbl/COACTUPC.cbl:L4171")
        void socialSecurityNumberChangeIsReported() {
            assertThat(customerChanged(
                    customer -> customer.setSocialSecurityNumber("020973889"))).isTrue();
        }

        @Test
        @DisplayName("13. Government-issued identifier folded to upper case, "
                + "app/cbl/COACTUPC.cbl:L4172-L4173")
        void governmentIssuedIdChangeIsReported() {
            assertThat(customerChanged(
                    customer -> customer.setGovernmentIssuedId("00000000000049368438"))).isTrue();
        }

        /**
         * The three slices app/cbl/COACTUPC.cbl:L4174-L4179 reads. The re-read side is read at
         * {@code (1:4)}, {@code (6:2)} and {@code (9:2)}, and the saved side at {@code (1:4)},
         * {@code (5:2)} and {@code (7:2)}. A change in the year, in the month, or in the day
         * reaches a slice on both sides.
         */
        @Test
        @DisplayName("14. Date of birth, the three slices at app/cbl/COACTUPC.cbl:L4174-L4179")
        void dateOfBirthChangeIsReported() {
            assertThat(customerChanged(customer -> customer.setDateOfBirth("1962-06-08"))).isTrue();
            assertThat(customerChanged(customer -> customer.setDateOfBirth("1961-07-08"))).isTrue();
            assertThat(customerChanged(customer -> customer.setDateOfBirth("1961-06-09"))).isTrue();
        }

        @Test
        @DisplayName("15. Electronic funds transfer identifier, "
                + "app/cbl/COACTUPC.cbl:L4181-L4182")
        void eftAccountIdChangeIsReported() {
            assertThat(customerChanged(
                    customer -> customer.setEftAccountId("0053581757"))).isTrue();
        }

        @Test
        @DisplayName("16. Primary card holder indicator, app/cbl/COACTUPC.cbl:L4183-L4185")
        void primaryCardHolderIndicatorChangeIsReported() {
            assertThat(customerChanged(
                    customer -> customer.setPrimaryCardHolderIndicator("N"))).isTrue();
        }

        @Test
        @DisplayName("17. Credit score, app/cbl/COACTUPC.cbl:L4186")
        void ficoCreditScoreChangeIsReported() {
            assertThat(customerChanged(
                    customer -> customer.setFicoCreditScore(new BigDecimal("275")))).isTrue();
        }
    }

    /**
     * The nine customer fields app/cbl/COACTUPC.cbl:L4152-L4173 folds to upper case on both sides,
     * each with a setter and a value carrying letters.
     *
     * @return one argument set per folded field
     */
    private static Stream<Arguments> foldedCustomerFields() {
        return Stream.of(
                Arguments.of("first name",
                        (BiConsumer<CustomerEntity, String>) CustomerEntity::setFirstName,
                        FIRST_NAME),
                Arguments.of("middle name",
                        (BiConsumer<CustomerEntity, String>) CustomerEntity::setMiddleName,
                        MIDDLE_NAME),
                Arguments.of("last name",
                        (BiConsumer<CustomerEntity, String>) CustomerEntity::setLastName,
                        LAST_NAME),
                Arguments.of("address line 1",
                        (BiConsumer<CustomerEntity, String>) CustomerEntity::setAddressLine1,
                        ADDRESS_LINE_1),
                Arguments.of("address line 2",
                        (BiConsumer<CustomerEntity, String>) CustomerEntity::setAddressLine2,
                        ADDRESS_LINE_2),
                Arguments.of("address line 3",
                        (BiConsumer<CustomerEntity, String>) CustomerEntity::setAddressCity,
                        ADDRESS_CITY),
                Arguments.of("state code",
                        (BiConsumer<CustomerEntity, String>) CustomerEntity::setAddressStateCode,
                        ADDRESS_STATE_CODE),
                Arguments.of("country code",
                        (BiConsumer<CustomerEntity, String>) CustomerEntity::setAddressCountryCode,
                        ADDRESS_COUNTRY_CODE),
                Arguments.of("government-issued identifier",
                        (BiConsumer<CustomerEntity, String>) CustomerEntity::setGovernmentIssuedId,
                        LETTER_BEARING_GOVERNMENT_ISSUED_ID));
    }

    /**
     * The five account amounts app/cbl/COACTUPC.cbl:L4117-L4125 reads through the numeric
     * redefinitions at app/cbl/COACTUPC.cbl:L676-L707, each with its value, its
     * {@link PicClause} scale and its setter.
     *
     * @return one argument set per account amount
     */
    private static Stream<Arguments> accountAmounts() {
        return Stream.of(
                Arguments.of("current balance", CURRENT_BALANCE, PicClause.ACCT_CURR_BAL_SCALE,
                        (BiConsumer<AccountEntity, BigDecimal>) AccountEntity::setCurrentBalance),
                Arguments.of("credit limit", CREDIT_LIMIT, PicClause.ACCT_CREDIT_LIMIT_SCALE,
                        (BiConsumer<AccountEntity, BigDecimal>) AccountEntity::setCreditLimit),
                Arguments.of("cash credit limit", CASH_CREDIT_LIMIT,
                        PicClause.ACCT_CASH_CREDIT_LIMIT_SCALE,
                        (BiConsumer<AccountEntity, BigDecimal>) AccountEntity::setCashCreditLimit),
                Arguments.of("cycle credit accumulator", CURRENT_CYCLE_CREDIT,
                        PicClause.ACCT_CURR_CYC_CREDIT_SCALE,
                        (BiConsumer<AccountEntity, BigDecimal>)
                                AccountEntity::setCurrentCycleCredit),
                Arguments.of("cycle debit accumulator", CURRENT_CYCLE_DEBIT,
                        PicClause.ACCT_CURR_CYC_DEBIT_SCALE,
                        (BiConsumer<AccountEntity, BigDecimal>)
                                AccountEntity::setCurrentCycleDebit));
    }

    /**
     * The four fields the conditions read as year, month and day slices: the three account dates of
     * app/cbl/COACTUPC.cbl:L4127-L4137 and the date of birth of
     * app/cbl/COACTUPC.cbl:L4174-L4179. Each argument set carries the field value and a function
     * returning the verdict for an altered value of that field.
     *
     * @return one argument set per sliced field
     */
    private static Stream<Arguments> slicedDateFields() {
        return Stream.of(
                Arguments.of("open date", OPEN_DATE, (Function<String, Boolean>) date ->
                        accountChanged(account -> account.setOpenDate(date))),
                Arguments.of("expiry date", EXPIRATION_DATE, (Function<String, Boolean>) date ->
                        accountChanged(account -> account.setExpirationDate(date))),
                Arguments.of("reissue date", REISSUE_DATE, (Function<String, Boolean>) date ->
                        accountChanged(account -> account.setReissueDate(date))),
                Arguments.of("date of birth", DATE_OF_BIRTH, (Function<String, Boolean>) date ->
                        customerChanged(customer -> customer.setDateOfBirth(date))));
    }

    @Test
    @DisplayName("A group identifier differing in case alone reports no change, "
            + "app/cbl/COACTUPC.cbl:L4139-L4140")
    void groupIdentifierCaseOnlyDifferenceReportsNoChange() {
        assertThat(accountChanged(account -> account.setGroupId(GROUP_ID_IN_UPPER_CASE)))
                .isFalse();
    }

    /**
     * The fold at app/cbl/COACTUPC.cbl:L4139-L4140 applies {@code FUNCTION LOWER-CASE} to both
     * sides and trims neither. One trailing space past the declared width of
     * {@value PicClause#ACCT_GROUP_ID_WIDTH} survives the fold and reaches the comparison.
     */
    @Test
    @DisplayName("A group identifier carrying a trailing space past the declared width reports a "
            + "change, app/cbl/COACTUPC.cbl:L4139-L4140")
    void groupIdentifierTrailingSpaceReportsChange() {
        assertThat(accountChanged(account -> account.setGroupId(GROUP_ID_WITH_TRAILING_SPACE)))
                .isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("A folded customer field differing in case alone reports no change, "
            + "app/cbl/COACTUPC.cbl:L4152-L4173")
    @MethodSource("foldedCustomerFields")
    void caseOnlyDifferenceInAFoldedCustomerFieldReportsNoChange(String field,
            BiConsumer<CustomerEntity, String> setter, String value) {
        assertThat(customerChanged(
                reRead -> setter.accept(reRead, value.toLowerCase(Locale.ROOT)),
                fetched -> setter.accept(fetched, value.toUpperCase(Locale.ROOT))))
                .isFalse();
    }

    @Test
    @DisplayName("A primary card holder indicator differing in case alone reports a change, "
            + "app/cbl/COACTUPC.cbl:L4183-L4185")
    void caseOnlyDifferenceInAPlainCustomerFieldReportsChange() {
        assertThat(customerChanged(customer -> customer.setPrimaryCardHolderIndicator(
                PRIMARY_CARD_HOLDER_INDICATOR_IN_LOWER_CASE))).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("An account amount carrying leading zeros reports no change, "
            + "app/cbl/COACTUPC.cbl:L4117-L4125")
    @MethodSource("accountAmounts")
    void leadingZerosInAnAccountAmountReportNoChange(String field, BigDecimal value, int scale,
            BiConsumer<AccountEntity, BigDecimal> setter) {
        BigDecimal padded = new BigDecimal(LEADING_ZERO_PADDING + value.toPlainString());

        assertThat(padded).isEqualByComparingTo(value);
        assertThat(padded.scale()).isEqualTo(scale);
        assertThat(accountChanged(account -> setter.accept(account, padded))).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("An account amount carrying two zeros past the declared scale reports no change, "
            + "app/cbl/COACTUPC.cbl:L4117-L4125")
    @MethodSource("accountAmounts")
    void trailingZeroScaleInAnAccountAmountReportsNoChange(String field, BigDecimal value,
            int scale, BiConsumer<AccountEntity, BigDecimal> setter) {
        BigDecimal wider = new BigDecimal(value.toPlainString() + TRAILING_ZERO_PADDING);

        assertThat(wider.scale()).isEqualTo(scale + TRAILING_ZERO_PADDING.length());
        assertThat(accountChanged(account -> setter.accept(account, wider))).isFalse();
    }

    /**
     * Truncation toward zero decides the verdict for the five account amounts. The
     * {@code ROUNDED} phrase appears in none of the twenty-eight programs of app/cbl, and a store
     * drops the digits past the declared scale.
     *
     * @param field the amount under test, named by the argument source
     * @param value the amount the two records carry
     * @param scale the declared scale of the amount
     * @param setter the accessor that writes the amount
     */
    @ParameterizedTest(name = "{0}")
    @DisplayName("Truncation toward zero decides the verdict for an account amount, and half-up "
            + "rounding yields a different value, app/cbl/COACTUPC.cbl:L4117-L4125")
    @MethodSource("accountAmounts")
    void truncationTowardZeroDecidesTheAccountAmountVerdict(String field, BigDecimal value,
            int scale, BiConsumer<AccountEntity, BigDecimal> setter) {
        BigDecimal withHalfOfOneCent = value.add(HALF_OF_ONE_CENT);

        assertThat(CobolDecimal.truncateToScale(withHalfOfOneCent, scale))
                .isEqualByComparingTo(value);
        assertThat(withHalfOfOneCent.setScale(scale, RoundingMode.HALF_UP))
                .isEqualByComparingTo(value.add(ONE_CENT))
                .isNotEqualByComparingTo(value);

        assertThat(accountChanged(account -> setter.accept(account, withHalfOfOneCent))).isFalse();
        assertThat(accountChanged(account -> setter.accept(account, withHalfOfOneCent),
                account -> setter.accept(account, value.add(ONE_CENT)))).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("The separator positions of a sliced field reach no slice, "
            + "app/cbl/COACTUPC.cbl:L4127-L4137 and app/cbl/COACTUPC.cbl:L4174-L4179")
    @MethodSource("slicedDateFields")
    void separatorPositionsOfASlicedFieldReachNoSlice(String field, String value,
            Function<String, Boolean> verdictForValue) {
        String alteredValue = withOtherSeparators(value);

        assertThat(alteredValue).isNotEqualTo(value);
        assertThat(verdictForValue.apply(alteredValue)).isFalse();
    }

    /**
     * The saved date of birth of app/cbl/COACTUPC.cbl:L746 is eight characters wide, two narrower
     * than the {@value PicClause#CUST_DOB_WIDTH} the re-read side carries. The comparison at
     * app/cbl/COACTUPC.cbl:L4174-L4179 returns a verdict for that form and reads no slice past the
     * supplied characters.
     */
    @Test
    @DisplayName("The eight-character saved date of birth yields a reported change, "
            + "app/cbl/COACTUPC.cbl:L4174-L4179")
    void eightCharacterSavedDateOfBirthYieldsAVerdict() {
        assertThat(DATE_OF_BIRTH).hasSize(PicClause.CUST_DOB_WIDTH);
        assertThat(UNSEPARATED_DATE_OF_BIRTH).hasSize(SAVED_DATE_OF_BIRTH_WIDTH);

        assertThatCode(() -> customerChanged(
                customer -> customer.setDateOfBirth(DATE_OF_BIRTH),
                customer -> customer.setDateOfBirth(UNSEPARATED_DATE_OF_BIRTH)))
                .doesNotThrowAnyException();

        assertThat(customerChanged(
                customer -> customer.setDateOfBirth(DATE_OF_BIRTH),
                customer -> customer.setDateOfBirth(UNSEPARATED_DATE_OF_BIRTH))).isTrue();
    }

    @Test
    @DisplayName("The message text matches app/cbl/COACTUPC.cbl:L522 character for character")
    void messageMatchesTheSourceText() {
        assertThat(ConcurrentChangeDetector.RECORD_CHANGED_MESSAGE)
                .isEqualTo(SOURCE_MESSAGE_TEXT)
                .hasSize(SOURCE_MESSAGE_LENGTH)
                .contains(TWO_WORD_SPELLING)
                .doesNotContain(ONE_WORD_SPELLING);
    }

    /**
     * The two conditions at app/cbl/COACTUPC.cbl:L4115-L4140 and app/cbl/COACTUPC.cbl:L4152-L4186
     * read field values. No field of app/cpy/CVACT01Y.cpy:L4-L17 and none of
     * app/cpy/CVCUS01Y.cpy:L4-L23 holds a record version, and this test reads annotation metadata
     * alone: no table, no column and no migration takes part.
     *
     * @param mappedEntity the entity class under test, supplied by the value source
     */
    @ParameterizedTest(name = "{0}")
    @DisplayName("No declared field of a mapped entity carries the Jakarta Persistence Version "
            + "annotation")
    @ValueSource(classes = {AccountEntity.class, CustomerEntity.class})
    void noDeclaredFieldCarriesTheVersionAnnotation(Class<?> mappedEntity) {
        List<String> versioned = Stream.of(mappedEntity.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(Version.class))
                .map(Field::getName)
                .toList();

        assertThat(versioned).isEmpty();
    }

    @Test
    @DisplayName("An absent account on one side reports a change")
    void absentAccountOnOneSideReportsChange() {
        assertThat(DETECTOR.storedRecordChanged(null, customer(), account(), customer())).isTrue();
        assertThat(DETECTOR.storedRecordChanged(account(), customer(), null, customer())).isTrue();
    }

    @Test
    @DisplayName("An absent customer on one side reports a change")
    void absentCustomerOnOneSideReportsChange() {
        assertThat(DETECTOR.storedRecordChanged(account(), null, account(), customer())).isTrue();
        assertThat(DETECTOR.storedRecordChanged(account(), customer(), account(), null)).isTrue();
    }

    @Test
    @DisplayName("Absent records on both sides report no change")
    void absentRecordsOnBothSidesReportNoChange() {
        assertThat(DETECTOR.storedRecordChanged(null, null, null, null)).isFalse();
    }

    /** Builds a clearly synthetic nine-digit Social Security Number. */
    private static String syntheticSocialSecurityNumber(long serial) {
        return "999" + String.format(Locale.ROOT, "%06d", serial);
    }

    /** Builds a clearly synthetic twenty-character government identifier. */
    private static String syntheticGovernmentIssuedId(String prefix, long serial) {
        return prefix + String.format("%0" + (20 - prefix.length()) + "d", serial);
    }

    /** Runs a transaction callback directly, so no transaction manager takes part. */
    private static TransactionTemplate immediateTransactions() {
        return new TransactionTemplate() {

            private static final long serialVersionUID = 1L;

            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    /** The shared meter holder this service records through. */
    private static ObservabilityConfig.AccountMeters accountMeters() {
        return new ObservabilityConfig().accountMeters(new SimpleMeterRegistry());
    }


    /**
     * Builds the bound configuration this service reads, with the shipped lock-wait bound.
     *
     * <p>{@code AccountUpdateService} reads one value from it, {@code carddemo.write.lock-wait-ms},
     * which it renders once into the PostgreSQL interval string its locked reads are bounded by. The
     * value below is the one {@code src/main/resources/application.yml} ships.
     *
     * @return the configuration record
     */
    private static AccountProperties accountProperties() {
        return new AccountProperties(
                new AccountProperties.Api(65_536L),
                new AccountProperties.Kafka(new AccountProperties.Kafka.Topics(
                        "account.state-changed", "customer.context-changed",
                        "transaction.posted", "carddemo.dead-letter"),
                        new AccountProperties.Kafka.Groups("account-posted")),
                new AccountProperties.Consumer(new AccountProperties.Consumer.Retry(3, 1_000L)),
                new AccountProperties.Outbox(new AccountProperties.Outbox.Relay(500L, 100,
                        "account-relay", java.time.Duration.ofMinutes(2L), 1_000L,
                        java.time.Duration.ofSeconds(10L)), 168L),
                new AccountProperties.ProcessedEvent(720L, 168L),
                new AccountProperties.Retention(3_600_000L),
                new AccountProperties.Write(3_000L));
    }
}
