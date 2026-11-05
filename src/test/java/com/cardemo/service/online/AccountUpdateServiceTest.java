package com.cardemo.service.online;

import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.exception.ValidationException;
import com.cardemo.common.validation.FieldValidator;
import com.cardemo.entity.Account;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.Customer;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.service.online.AccountUpdateService.AccountUpdateRequest;

import jakarta.persistence.OptimisticLockException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 unit tests for {@link AccountUpdateService} — the most complex
 * online service translating COACTUPC.cbl. Tests cover:
 * <ul>
 *   <li>Optimistic locking (JPA @Version → CICS READ UPDATE/REWRITE)</li>
 *   <li>25-field validation (editMapInputs → paragraphs 1200–1280)</li>
 *   <li>Cross-file lookups (XREF → Account → Customer)</li>
 * </ul>
 *
 * <p>Uses Mockito for repository and context mocking. Static utility methods
 * (DateConversionUtil, LookupCodeUtil, FieldValidator statics) execute with
 * real implementations since they are pure functions with no side effects.
 *
 * @see AccountUpdateService
 * @see <a href="app/cbl/COACTUPC.cbl">COACTUPC.cbl — Account Update CICS program</a>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountUpdateService — COACTUPC.cbl Translation Tests")
class AccountUpdateServiceTest {

    // ========================================================================
    // Test Constants — matching COACTUPC.cbl WORKING-STORAGE values
    // ========================================================================

    /** Standard test account ID (11 characters per ACCT-ID PIC X(11)). */
    private static final String TEST_ACCT_ID = "00000000001";

    /** Standard test customer ID (9 characters per CUST-ID PIC X(09)). */
    private static final String TEST_CUST_ID = "000000001";

    /** Standard test card number (16 characters per CARD-NUM PIC X(16)). */
    private static final String TEST_CARD_NUM = "4111111111111111";

    /** Standard test user ID for session context. */
    private static final String TEST_USER_ID = "USER0001";

    /** Valid US state code confirmed in LookupCodeUtil.VALID_US_STATE_CODES. */
    private static final String VALID_STATE = "NY";

    /** Valid 5-digit ZIP for state/zip combo testing. */
    private static final String VALID_ZIP = "10001";

    /** Valid general-purpose area code confirmed in LookupCodeUtil. */
    private static final String VALID_AREA_CODE = "212";

    /** Valid phone prefix (3 numeric digits, not 000). */
    private static final String VALID_PHONE_PREFIX = "555";

    /** Valid phone line number (4 numeric digits, not 0000). */
    private static final String VALID_PHONE_LINE = "1234";

    /** Valid SSN (9 digits, part1 ≠ 000/666/≥900, part2 ≠ 00, part3 ≠ 0000). */
    private static final String VALID_SSN = "123456789";

    /** Valid FICO score within 300–850 range. */
    private static final String VALID_FICO = "750";

    /** Valid CCYYMMDD date string for account dates. */
    private static final String VALID_DATE_CCYYMMDD = "20250115";

    /** Valid YYYY-MM-DD stored date format (for entity fields). */
    private static final String VALID_DATE_STORED = "2025-01-15";

    /** Valid BigDecimal balance string. */
    private static final String VALID_BALANCE = "1000.00";

    /** Discount group ID for test data. */
    private static final String TEST_GROUP_ID = "GROUP001";

    // ========================================================================
    // Mocked Dependencies
    // ========================================================================

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CardDemoContext cardDemoContext;

    @Mock
    private FieldValidator fieldValidator;

    /**
     * System under test — injected with the 5 mocked dependencies matching
     * the constructor (AccountRepository, CardXrefRepository,
     * CustomerRepository, CardDemoContext, FieldValidator).
     * Note: DateConversionUtil is a final utility class with static methods
     * and is NOT injected.
     */
    @InjectMocks
    private AccountUpdateService accountUpdateService;

    // ========================================================================
    // Shared Test Fixtures
    // ========================================================================

    private Account testAccount;
    private Customer testCustomer;
    private CardXref testCardXref;
    private AccountUpdateRequest validRequest;

    // ========================================================================
    // Reusable FieldValidator.ValidationResult instances
    // ========================================================================

    private FieldValidator.ValidationResult validDateResult;
    private FieldValidator.ValidationResult invalidDateResult;

    // ========================================================================
    // @BeforeEach Setup — Initializes shared fixtures for each test
    // ========================================================================

    @BeforeEach
    void setUp() {
        // Build standard test entities
        testAccount = buildTestAccount();
        testCustomer = buildTestCustomer();
        testCardXref = new CardXref(TEST_CARD_NUM, TEST_CUST_ID, TEST_ACCT_ID);

        // Build a fully valid request that passes all 25 validations
        validRequest = buildValidRequest();

        // Construct valid/invalid date validation results for mock responses.
        // FieldValidator.ValidationResult(valid, inputError, variableName, returnMessage, flags)
        // FieldValidationFlags(yearFlag, monthFlag, dayFlag): '\0' = VALID_FLAG
        FieldValidator.FieldValidationFlags validFlags =
                new FieldValidator.FieldValidationFlags('\0', '\0', '\0');
        validDateResult = new FieldValidator.ValidationResult(
                true, false, "", "", validFlags);

        FieldValidator.FieldValidationFlags invalidFlags =
                new FieldValidator.FieldValidationFlags('0', '0', '0');
        invalidDateResult = new FieldValidator.ValidationResult(
                false, true, "Date", "Date : Month is invalid.", invalidFlags);
    }

    // ========================================================================
    // Helper: Build Test Account Entity
    // ========================================================================

    /**
     * Creates a standard Account entity with valid business fields.
     * Matches CVACT01Y.cpy 300-byte ACCTDATA VSAM record layout.
     * Uses BigDecimal for all COMP-3 monetary fields per AAP mandate.
     */
    private Account buildTestAccount() {
        Account acct = new Account(
                TEST_ACCT_ID,
                "Y",                                          // activeStatus
                new BigDecimal("1000.00"),                    // currBal
                new BigDecimal("5000.00"),                    // creditLimit
                new BigDecimal("2000.00"),                    // cashCreditLimit
                VALID_DATE_STORED,                            // openDate (YYYY-MM-DD)
                "2026-12-31",                                 // expirationDate
                "2026-01-01",                                 // reissueDate
                new BigDecimal("500.00"),                     // currCycCredit
                new BigDecimal("200.00"),                     // currCycDebit
                VALID_ZIP,                                    // addrZip
                TEST_GROUP_ID                                 // groupId
        );
        acct.setVersion(1L);
        return acct;
    }

    // ========================================================================
    // Helper: Build Test Customer Entity
    // ========================================================================

    /**
     * Creates a standard Customer entity with valid personal data.
     * Matches CVCUS01Y.cpy 500-byte CUSTDATA VSAM record layout.
     * 18-parameter constructor per Customer entity definition.
     */
    private Customer buildTestCustomer() {
        return new Customer(
                TEST_CUST_ID,           // custId
                "John",                 // firstName
                "Paul",                 // middleName
                "Smith",                // lastName
                "Main Street",          // addrLine1
                "Suite A",              // addrLine2
                "New York",             // addrLine3 (city)
                VALID_STATE,            // addrStateCode
                "USA",                  // addrCountryCode
                VALID_ZIP,              // addrZip
                "(212)555-1234",        // phoneNum1
                null,                   // phoneNum2
                VALID_SSN,              // ssn
                "A12345678",            // govtIssuedId
                "1990-05-15",           // dateOfBirth (YYYY-MM-DD)
                "1234567890",           // eftAccountId
                "Y",                    // priCardHolderInd
                750                     // ficoCreditScore
        );
    }

    // ========================================================================
    // Helper: Build Fully Valid AccountUpdateRequest
    // ========================================================================

    /**
     * Builds an {@link AccountUpdateRequest} that passes all 25 validations
     * in editMapInputs. Every field uses known-valid values that satisfy
     * both mocked (date) and real (static) validators.
     */
    private AccountUpdateRequest buildValidRequest() {
        AccountUpdateRequest req = new AccountUpdateRequest();

        // Account fields (10)
        req.setActiveStatus("Y");
        req.setOpenDate(VALID_DATE_CCYYMMDD);
        req.setCreditLimit("5000.00");
        req.setExpirationDate("20261231");
        req.setCashCreditLimit("2000.00");
        req.setReissueDate("20260101");
        req.setCurrBal(VALID_BALANCE);
        req.setCurrCycCredit("500.00");
        req.setCurrCycDebit("200.00");
        req.setGroupId(TEST_GROUP_ID);

        // Customer fields (21)
        req.setFirstName("John");
        req.setMiddleName("Paul");
        req.setLastName("Smith");
        req.setAddrLine1("Main Street");
        req.setAddrLine2("Suite A");
        req.setAddrLine3("New York");
        req.setAddrStateCode(VALID_STATE);
        req.setAddrCountryCode("USA");
        req.setAddrZip(VALID_ZIP);
        req.setPhone1AreaCode(VALID_AREA_CODE);
        req.setPhone1Prefix(VALID_PHONE_PREFIX);
        req.setPhone1LineNum(VALID_PHONE_LINE);
        req.setPhone2AreaCode("");
        req.setPhone2Prefix("");
        req.setPhone2LineNum("");
        req.setSsn(VALID_SSN);
        req.setGovtIssuedId("A12345678");
        req.setDateOfBirth(VALID_DATE_CCYYMMDD);
        req.setEftAccountId("1234567890");
        req.setPriCardHolderInd("Y");
        req.setFicoCreditScore(VALID_FICO);

        return req;
    }

    // ========================================================================
    // Helper: Mock the readAcct chain (getCardXrefByAcct→getAcctDataByAcct→getCustDataByCust)
    // ========================================================================

    /**
     * Configures repository mocks for the full readAcct call chain:
     * <ol>
     *   <li>CardXrefRepository.findByAccountId → test XREF</li>
     *   <li>AccountRepository.findById → test Account</li>
     *   <li>CustomerRepository.findById → test Customer</li>
     * </ol>
     * Also configures CardDemoContext getters used by storeFetchedData
     * and writeProcessing (getCustId, getAcctId).
     */
    private void mockReadAcctChain() {
        when(cardXrefRepository.findByAccountId(TEST_ACCT_ID))
                .thenReturn(List.of(testCardXref));
        when(accountRepository.findById(TEST_ACCT_ID))
                .thenReturn(Optional.of(testAccount));
        when(customerRepository.findById(TEST_CUST_ID))
                .thenReturn(Optional.of(testCustomer));
        // storeFetchedData calls setters on mock context (no-ops),
        // but writeProcessing needs getCustId() to return valid value
        lenient().when(cardDemoContext.getCustId()).thenReturn(TEST_CUST_ID);
        lenient().when(cardDemoContext.getAcctId()).thenReturn(TEST_ACCT_ID);
    }

    /**
     * Configures FieldValidator mock to return valid results for all
     * editDateCcyymmdd and editDateOfBirth calls in editMapInputs.
     * Uses lenient() because not all date validators may be reached
     * if a prior field validation causes early return.
     */
    private void mockValidDateValidation() {
        lenient().when(fieldValidator.editDateCcyymmdd(anyString(), anyString()))
                .thenReturn(validDateResult);
        lenient().when(fieldValidator.editDateOfBirth(anyString(), anyString()))
                .thenReturn(validDateResult);
    }

    // ========================================================================
    // Test 1: updateAccount — Success with Changes
    // Maps: 0000-MAIN → 9000-READ-ACCT → 1200-EDIT-MAP-INPUTS → 9600-WRITE-PROCESSING
    // ========================================================================

    @Test
    @DisplayName("updateAccount: read, validate, write with changes (REST stateless)")
    void testUpdateAccount_SuccessWithChanges() {
        // Arrange: REST API always proceeds directly to validate + write
        // (pgmContext ENTER/REENTER branching removed for stateless REST)
        mockReadAcctChain();
        mockValidDateValidation();

        // Modify currBal in request to trigger a change
        validRequest.setCurrBal("2000.00");

        // Mock save to return the saved account
        when(accountRepository.save(any(Account.class))).thenReturn(testAccount);
        lenient().when(customerRepository.save(any(Customer.class))).thenReturn(testCustomer);

        // Act
        Account result = accountUpdateService.updateAccount(TEST_ACCT_ID, validRequest);

        // Assert: save was called for both account and customer
        verify(accountRepository, times(1)).save(any(Account.class));
        verify(customerRepository, times(1)).save(any(Customer.class));
        assertThat(result).isNotNull();
    }

    // ========================================================================
    // Test 2: updateAccount — OptimisticLockException
    // Maps: 9600-WRITE-PROCESSING → REWRITE fails with concurrent modification
    // ========================================================================

    @Test
    @DisplayName("updateAccount: save throws OptimisticLockException → ValidationException")
    void testUpdateAccount_OptimisticLockException() {
        // Arrange: REST API proceeds directly to validate + write
        mockReadAcctChain();
        mockValidDateValidation();

        // Configure save to throw OptimisticLockException (concurrent modification)
        when(accountRepository.save(any(Account.class)))
                .thenThrow(new OptimisticLockException("concurrent modification"));

        // Act & Assert: service wraps OptimisticLockException into ValidationException
        assertThatThrownBy(() -> accountUpdateService.updateAccount(TEST_ACCT_ID, validRequest))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    ValidationException ve = (ValidationException) ex;
                    assertThat(ve.getFieldName()).isEqualTo("account");
                    assertThat(ve.getValidationMessage())
                            .isEqualTo("Could not lock account record for update.");
                });
    }

    // ========================================================================
    // Test 3: updateAccount — No Changes (ENTER path)
    // Maps: 0000-MAIN → isEnterContext() → readAcct → setPgmContext → return
    // ========================================================================

    @Test
    @DisplayName("updateAccount: valid request with identical values still saves successfully")
    void testUpdateAccount_NoChanges() {
        // Arrange: REST API always proceeds to validate + write, even if values are unchanged.
        // The old COBOL ENTER path (read-only display) is handled by GET /api/accounts/{id}
        // (AccountViewService). PUT always validates and saves.
        mockReadAcctChain();
        mockValidDateValidation();

        // Mock save to return the existing account (no field changes)
        when(accountRepository.save(any(Account.class))).thenReturn(testAccount);
        lenient().when(customerRepository.save(any(Customer.class))).thenReturn(testCustomer);

        // Act
        Account result = accountUpdateService.updateAccount(TEST_ACCT_ID, validRequest);

        // Assert: save was still called (REST PUT always writes)
        verify(accountRepository, times(1)).save(any(Account.class));
        assertThat(result).isNotNull();
        assertThat(result.getAcctId()).isEqualTo(TEST_ACCT_ID);
    }

    // ========================================================================
    // Test 4: editMapInputs — Valid Date Fields
    // Maps: 1200-EDIT-MAP-INPUTS → date validation via editDateCcyymmdd
    // ========================================================================

    @Test
    @DisplayName("editMapInputs: all date fields valid → no date-related errors")
    void testEditMapInputs_ValidDateFields() {
        // Arrange: mock all date validators to return valid
        mockValidDateValidation();

        // Act: call editMapInputs with fully valid request
        List<String> errors = accountUpdateService.editMapInputs(validRequest);

        // Assert: no errors at all (all 25 fields pass)
        assertThat(errors).isEmpty();

        // Verify: all 4 date field validations were invoked
        verify(fieldValidator, times(1)).editDateCcyymmdd("Open Date", VALID_DATE_CCYYMMDD);
        verify(fieldValidator, times(1)).editDateCcyymmdd("Expiry Date", "20261231");
        verify(fieldValidator, times(1)).editDateCcyymmdd("Reissue Date", "20260101");
        verify(fieldValidator, times(1)).editDateCcyymmdd("Date of Birth", VALID_DATE_CCYYMMDD);
        verify(fieldValidator, times(1)).editDateOfBirth("Date of Birth", VALID_DATE_CCYYMMDD);
    }

    // ========================================================================
    // Test 5: editMapInputs — Invalid Date Format
    // Maps: 1200-EDIT-MAP-INPUTS → editDateCcyymmdd returns error
    // ========================================================================

    @Test
    @DisplayName("editMapInputs: invalid open date → error in validation list")
    void testEditMapInputs_InvalidDateFormat() {
        // Arrange: open date validator returns error; others return valid
        String invalidDate = "99991301"; // month 13 is invalid
        validRequest.setOpenDate(invalidDate);

        FieldValidator.FieldValidationFlags badFlags =
                new FieldValidator.FieldValidationFlags('0', '0', '0');
        FieldValidator.ValidationResult openDateError = new FieldValidator.ValidationResult(
                false, true, "Open Date",
                "Open Date : Month is invalid.", badFlags);

        when(fieldValidator.editDateCcyymmdd("Open Date", invalidDate))
                .thenReturn(openDateError);
        // Other dates still return valid
        lenient().when(fieldValidator.editDateCcyymmdd("Expiry Date", "20261231"))
                .thenReturn(validDateResult);
        lenient().when(fieldValidator.editDateCcyymmdd("Reissue Date", "20260101"))
                .thenReturn(validDateResult);
        lenient().when(fieldValidator.editDateCcyymmdd("Date of Birth", VALID_DATE_CCYYMMDD))
                .thenReturn(validDateResult);
        lenient().when(fieldValidator.editDateOfBirth("Date of Birth", VALID_DATE_CCYYMMDD))
                .thenReturn(validDateResult);

        // Act
        List<String> errors = accountUpdateService.editMapInputs(validRequest);

        // Assert: error list contains the date validation error
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(e -> e.contains("Open Date"));
        assertThat(errors).anyMatch(e -> e.contains("Month is invalid"));
    }

    // ========================================================================
    // Test 6: editSigned9V2 — Valid Currency Values
    // Maps: 1250-EDIT-SIGNED-9V2 → PIC S9(10)V99 range validation
    // ========================================================================

    @Test
    @DisplayName("editMapInputs: valid currency values (BigDecimal) → no monetary errors")
    void testEditSigned9V2_ValidCurrency() {
        // Arrange: set all 5 monetary fields to various valid BigDecimal values
        mockValidDateValidation();
        validRequest.setCurrBal("1000.00");
        validRequest.setCreditLimit("-500.50");
        validRequest.setCashCreditLimit("0.00");
        validRequest.setCurrCycCredit("9999999999.99");
        validRequest.setCurrCycDebit("-9999999999.99");

        // Act
        List<String> errors = accountUpdateService.editMapInputs(validRequest);

        // Assert: no errors — all monetary values within PIC S9(10)V99 range
        assertThat(errors).isEmpty();
    }

    // ========================================================================
    // Test 7: editSigned9V2 — Invalid Currency Values
    // Maps: 1250-EDIT-SIGNED-9V2 → NumberFormatException / out-of-range
    // ========================================================================

    @Test
    @DisplayName("editMapInputs: invalid currency 'abc' → error for Current Balance")
    void testEditSigned9V2_InvalidCurrency() {
        // Arrange: set currBal to a non-numeric value
        mockValidDateValidation();
        validRequest.setCurrBal("abc");

        // Act
        List<String> errors = accountUpdateService.editMapInputs(validRequest);

        // Assert: error list contains currency validation error for Current Balance
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(e ->
                e.contains("Current Balance") && e.contains("is not valid"));
    }

    // ========================================================================
    // Test 8: editUsPhoneNum — Valid Phone Number
    // Maps: 1260-EDIT-US-PHONE-NUM → area code + prefix + line number
    // ========================================================================

    @Test
    @DisplayName("editMapInputs: valid US phone '212-555-1234' → no phone errors")
    void testEditUsPhoneNum_Valid() {
        // Arrange: phone 1 set to valid components; phone 2 all blank (optional)
        mockValidDateValidation();
        validRequest.setPhone1AreaCode(VALID_AREA_CODE);
        validRequest.setPhone1Prefix(VALID_PHONE_PREFIX);
        validRequest.setPhone1LineNum(VALID_PHONE_LINE);
        validRequest.setPhone2AreaCode("");
        validRequest.setPhone2Prefix("");
        validRequest.setPhone2LineNum("");

        // Act
        List<String> errors = accountUpdateService.editMapInputs(validRequest);

        // Assert: no phone-related errors
        assertThat(errors).isEmpty();
    }

    // ========================================================================
    // Test 9: editUsPhoneNum — Invalid Phone Number (area code 000)
    // Maps: 1260-EDIT-US-PHONE-NUM → area code 000 not valid
    // ========================================================================

    @Test
    @DisplayName("editMapInputs: invalid phone area code '000' → phone error")
    void testEditUsPhoneNum_Invalid() {
        // Arrange: set phone1 area code to invalid "000"
        mockValidDateValidation();
        validRequest.setPhone1AreaCode("000");
        validRequest.setPhone1Prefix(VALID_PHONE_PREFIX);
        validRequest.setPhone1LineNum(VALID_PHONE_LINE);

        // Act
        List<String> errors = accountUpdateService.editMapInputs(validRequest);

        // Assert: error list contains phone validation error
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(e ->
                e.toLowerCase().contains("phone") || e.contains("area code"));
    }

    // ========================================================================
    // Test 10: editUsSsn — Valid SSN
    // Maps: 1265-EDIT-US-SSN → 9 digits, 3-part validation
    // ========================================================================

    @Test
    @DisplayName("editMapInputs: valid SSN '123456789' → no SSN errors")
    void testEditUsSsn_Valid() {
        // Arrange: valid SSN (part1=123, part2=45, part3=6789)
        mockValidDateValidation();
        validRequest.setSsn(VALID_SSN);

        // Act
        List<String> errors = accountUpdateService.editMapInputs(validRequest);

        // Assert: no SSN-related errors
        assertThat(errors).isEmpty();
    }

    // ========================================================================
    // Test 11: editUsSsn — Invalid SSN (000 prefix)
    // Maps: 1265-EDIT-US-SSN → part1 "000" is invalid
    // ========================================================================

    @Test
    @DisplayName("editMapInputs: invalid SSN '000456789' → SSN error")
    void testEditUsSsn_Invalid() {
        // Arrange: SSN with invalid part1 = "000"
        mockValidDateValidation();
        validRequest.setSsn("000456789");

        // Act
        List<String> errors = accountUpdateService.editMapInputs(validRequest);

        // Assert: error list contains SSN validation error
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(e -> e.contains("SSN"));
    }

    // ========================================================================
    // Test 12: editFicoScore — Valid FICO Score
    // Maps: 1275-EDIT-FICO-SCORE → range 300–850
    // ========================================================================

    @Test
    @DisplayName("editMapInputs: valid FICO score '750' → no FICO errors")
    void testEditFicoScore_Valid() {
        // Arrange: FICO score within valid range
        mockValidDateValidation();
        validRequest.setFicoCreditScore(VALID_FICO);

        // Act
        List<String> errors = accountUpdateService.editMapInputs(validRequest);

        // Assert: no FICO-related errors
        assertThat(errors).isEmpty();
    }

    // ========================================================================
    // Test 13: editFicoScore — Invalid FICO Score (out of range)
    // Maps: 1275-EDIT-FICO-SCORE → 851 exceeds maximum 850
    // ========================================================================

    @Test
    @DisplayName("editMapInputs: invalid FICO score '851' → FICO out-of-range error")
    void testEditFicoScore_Invalid() {
        // Arrange: FICO score above maximum 850
        mockValidDateValidation();
        validRequest.setFicoCreditScore("851");

        // Act
        List<String> errors = accountUpdateService.editMapInputs(validRequest);

        // Assert: error list contains FICO range error
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(e ->
                e.contains("FICO") && e.contains("300") && e.contains("850"));
    }

    // ========================================================================
    // Test 14: editUsStateCd — Valid State Code
    // Maps: 1270-EDIT-US-STATE-CD → LookupCodeUtil.isValidUsStateCode
    // ========================================================================

    @Test
    @DisplayName("editMapInputs: valid state code 'NY' → no state errors")
    void testEditUsStateCd_Valid() {
        // Arrange: valid state code confirmed in LookupCodeUtil
        mockValidDateValidation();
        validRequest.setAddrStateCode(VALID_STATE);

        // Act
        List<String> errors = accountUpdateService.editMapInputs(validRequest);

        // Assert: no state-related errors
        assertThat(errors).isEmpty();
    }

    // ========================================================================
    // Test 15: editUsStateCd — Invalid State Code
    // Maps: 1270-EDIT-US-STATE-CD → "XX" not in valid codes set
    // ========================================================================

    @Test
    @DisplayName("editMapInputs: invalid state code 'XX' → state code error")
    void testEditUsStateCd_Invalid() {
        // Arrange: state code "XX" is not a valid US state
        mockValidDateValidation();
        validRequest.setAddrStateCode("XX");

        // Act
        List<String> errors = accountUpdateService.editMapInputs(validRequest);

        // Assert: error list contains state code validation error
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(e ->
                e.contains("State") && e.contains("not a valid"));
    }

    // ========================================================================
    // Test 16: editUsStateZipCd — Valid State+ZIP Combo
    // Maps: 1280-EDIT-US-STATE-ZIP-CD → LookupCodeUtil.isValidStateZipCombo
    // ========================================================================

    @Test
    @DisplayName("editMapInputs: valid state 'NY' + ZIP '10001' → no combo error")
    void testEditUsStateZipCd_Valid() {
        // Arrange: NY + zip prefix "10" is a valid state/zip combination
        mockValidDateValidation();
        validRequest.setAddrStateCode(VALID_STATE);
        validRequest.setAddrZip(VALID_ZIP);

        // Act
        List<String> errors = accountUpdateService.editMapInputs(validRequest);

        // Assert: no state/zip combo errors
        assertThat(errors).isEmpty();
    }

    // ========================================================================
    // Test 17: getCardXrefByAcct — Not Found
    // Maps: 9200-GETCARDXREF-BYACCT → RESP=13 → RecordNotFoundException
    // ========================================================================

    @Test
    @DisplayName("getCardXrefByAcct: empty XREF list → RecordNotFoundException")
    void testGetCardXrefByAcct_NotFound() {
        // Arrange: repository returns empty list (VSAM status 23 / RESP=13)
        when(cardXrefRepository.findByAccountId("99999999999"))
                .thenReturn(List.of());

        // Act & Assert: RecordNotFoundException thrown for missing XREF
        assertThatThrownBy(() -> accountUpdateService.getCardXrefByAcct("99999999999"))
                .isInstanceOf(RecordNotFoundException.class);
    }

    // ========================================================================
    // Test 18: checkChangeInRec — Detects Field-Level Changes
    // Maps: 1205-COMPARE-OLD-NEW → compares 10 fields
    // ========================================================================

    @Test
    @DisplayName("checkChangeInRec: different currBal → returns true (changes detected)")
    void testCompareOldNew_DetectsChanges() {
        // Arrange: original account with currBal=1000.00
        Account original = buildTestAccount();

        // Modified account with different currBal (2000.00)
        Account modified = new Account(
                TEST_ACCT_ID,
                "Y",
                new BigDecimal("2000.00"),      // Changed from 1000.00
                new BigDecimal("5000.00"),
                new BigDecimal("2000.00"),
                VALID_DATE_STORED,
                "2026-12-31",
                "2026-01-01",
                new BigDecimal("500.00"),
                new BigDecimal("200.00"),
                VALID_ZIP,
                TEST_GROUP_ID
        );

        // Act
        boolean hasChanges = accountUpdateService.checkChangeInRec(original, modified);

        // Assert: changes detected because currBal differs
        assertThat(hasChanges).isTrue();
    }

    // ========================================================================
    // Test 19: writeProcessing — Successful Write
    // Maps: 9600-WRITE-PROCESSING → REWRITE both ACCTDAT and CUSTDAT
    // ========================================================================

    @Test
    @DisplayName("writeProcessing: valid changes → both account and customer saved")
    void testWriteProcessing_Success() {
        // Arrange: mock repository reads for fresh data re-read
        when(accountRepository.findById(TEST_ACCT_ID))
                .thenReturn(Optional.of(testAccount));
        when(cardDemoContext.getCustId()).thenReturn(TEST_CUST_ID);
        when(customerRepository.findById(TEST_CUST_ID))
                .thenReturn(Optional.of(testCustomer));

        // Mock saves
        when(accountRepository.save(any(Account.class))).thenReturn(testAccount);
        when(customerRepository.save(any(Customer.class))).thenReturn(testCustomer);

        // Act: call writeProcessing directly with the current account and a valid request
        assertThatCode(() ->
                accountUpdateService.writeProcessing(testAccount, validRequest))
                .doesNotThrowAnyException();

        // Assert: both repositories had save() called
        verify(accountRepository, times(1)).save(any(Account.class));
        verify(customerRepository, times(1)).save(any(Customer.class));
    }
}
