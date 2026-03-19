/*
 * CreditCardUpdateServiceTest.java — JUnit 5 Unit Test (← COCRDUPC.cbl)
 *
 * Mockito-based unit tests for CreditCardUpdateService verifying credit card
 * update logic with optimistic locking and field validation. Tests map to
 * COBOL paragraphs: 0000-MAIN, 1200-EDIT-MAP-INPUTS (and sub-paragraphs
 * 1230-EDIT-NAME, 1240-EDIT-CARDSTATUS, 1250-EDIT-EXPIRY-MON,
 * 1260-EDIT-EXPIRY-YEAR), 9000-READ-DATA, 9200-WRITE-PROCESSING,
 * and 9300-CHECK-CHANGE-IN-REC.
 *
 * COBOL source: app/cbl/COCRDUPC.cbl
 * VSAM dataset: CARDDATA (copybook CVACT02Y.cpy, 150-byte KSDS)
 * COMMAREA: COCOM01Y.cpy (1024-byte session context)
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cardemo.service.online;

// Internal imports (from depends_on_files)
import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.exception.ValidationException;
import com.cardemo.entity.Card;
import com.cardemo.repository.CardRepository;
import com.cardemo.service.online.CreditCardUpdateService.CardUpdateRequest;

// External imports — Jakarta Persistence (for OptimisticLockException simulation)
import jakarta.persistence.OptimisticLockException;

// External imports — JUnit 5 Jupiter (provided by spring-boot-starter-test)
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// External imports — Mockito (provided by spring-boot-starter-test)
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// Java standard library
import java.util.List;
import java.util.Optional;

// Static imports — AssertJ fluent assertions
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Static imports — Mockito verification and stubbing
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CreditCardUpdateService} — Mockito-based, no Spring context.
 *
 * <p>Tests the credit card update logic faithfully translated from COCRDUPC.cbl
 * (Credit Card Update for the CardDemo Application). Each test method maps to a
 * specific execution path through the COBOL paragraphs:</p>
 * <ul>
 *   <li><strong>0000-MAIN</strong> — Orchestration: read data → validate →
 *       detect changes → save</li>
 *   <li><strong>1200-EDIT-MAP-INPUTS</strong> — Combined field validation</li>
 *   <li><strong>1230-EDIT-NAME</strong> — Embossed name: non-blank, alpha + spaces</li>
 *   <li><strong>1240-EDIT-CARDSTATUS</strong> — Active status: 'Y' or 'N' only</li>
 *   <li><strong>1250-EDIT-EXPIRY-MON</strong> — Expiry month: numeric 1–12</li>
 *   <li><strong>1260-EDIT-EXPIRY-YEAR</strong> — Expiry year: numeric 1950–2099</li>
 *   <li><strong>9000-READ-DATA</strong> — Card record lookup by primary key</li>
 *   <li><strong>9200-WRITE-PROCESSING</strong> — Optimistic lock card update</li>
 *   <li><strong>9300-CHECK-CHANGE-IN-REC</strong> — Concurrent modification detection</li>
 * </ul>
 *
 * <h2>Test Fixture Design</h2>
 * <p>A baseline {@link Card} entity is created in {@link #setUp()} with valid fields:
 * card number "4111111111111111", account ID "00000000050", CVV "123", embossed name
 * "JOHN DOE", expiration "2026-06-15", active status "Y", version 1. This fixture
 * mirrors the CARDDATA VSAM 150-byte record layout from CVACT02Y.cpy.</p>
 *
 * <h2>Optimistic Locking Strategy</h2>
 * <p>The COBOL CICS {@code READ UPDATE → REWRITE} pattern is replaced by JPA
 * {@code @Version}-based optimistic locking. Tests verify both the success path
 * (save completes) and the failure path ({@link OptimisticLockException} thrown,
 * mapped to DATA-WAS-CHANGED-BEFORE-UPDATE error state).</p>
 *
 * @see CreditCardUpdateService
 * @see Card
 * @see CardRepository
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreditCardUpdateService — COCRDUPC.cbl Unit Tests")
class CreditCardUpdateServiceTest {

    // ========================================================================
    // Test fixture constants — matching CVACT02Y.cpy CARD-RECORD layout
    // ========================================================================

    /** Test card number — CARD-NUM PIC X(16), 16-character format. */
    private static final String TEST_CARD_NUM = "4111111111111111";

    /** Test account ID — CARD-ACCT-ID PIC 9(11), with leading zeros. */
    private static final String TEST_ACCT_ID = "00000000050";

    /** Test CVV code — CARD-CVV-CD PIC 9(03). */
    private static final String TEST_CVV = "123";

    /** Test embossed name — CARD-EMBOSSED-NAME PIC X(50), alphabetic. */
    private static final String TEST_NAME = "JOHN DOE";

    /** Test expiration date — CARD-EXPIRAION-DATE PIC X(10), YYYY-MM-DD. */
    private static final String TEST_EXP_DATE = "2026-06-15";

    /** Test active status — CARD-ACTIVE-STATUS PIC X(01), 'Y' = active. */
    private static final String TEST_STATUS = "Y";

    // ========================================================================
    // Mocked dependencies and system under test
    // ========================================================================

    /** Mocked CardRepository — replaces VSAM CARDDAT file I/O operations. */
    @Mock
    private CardRepository cardRepository;

    /** Mocked CardDemoContext — replaces COMMAREA session context (COCOM01Y). */
    @Mock
    private CardDemoContext cardDemoContext;

    /** System under test — CreditCardUpdateService with mocked dependencies. */
    @InjectMocks
    private CreditCardUpdateService creditCardUpdateService;

    /** Reusable test card fixture initialized before each test method. */
    private Card testCard;

    // ========================================================================
    // Setup — initialises CARDDATA 150-byte record fixture
    // ========================================================================

    /**
     * Initialises the test card fixture before each test.
     * Uses the parameterised Card constructor matching CVACT02Y.cpy layout.
     * Sets JPA @Version to 1L for optimistic locking tests (maps
     * CICS READ UPDATE versioning).
     */
    @BeforeEach
    void setUp() {
        testCard = new Card(TEST_CARD_NUM, TEST_ACCT_ID, TEST_CVV,
                TEST_NAME, TEST_EXP_DATE, TEST_STATUS);
        testCard.setVersion(1L);
    }

    // ========================================================================
    // Optimistic Locking Tests — 0000-MAIN / 9200-WRITE-PROCESSING
    // ========================================================================

    /**
     * Test 1: Successful card update with valid changes.
     *
     * <p>Maps COBOL paragraphs: 0000-MAIN → 1200-EDIT-MAP-INPUTS →
     * 9200-WRITE-PROCESSING (findById → detectChanges → save).</p>
     *
     * <p>Verifies the complete update flow: findById reads the card, validation
     * passes, change detection finds a difference in embossed name (CCUP-NEW-CRDNAME
     * differs from CCUP-OLD-CRDNAME), and save() persists the updated record
     * (analogous to EXEC CICS REWRITE FILE(LIT-CARDFILENAME) FROM(CARD-UPDATE-RECORD)).</p>
     */
    @Test
    @DisplayName("Test 1: updateCard — success with valid name change (0000-MAIN → save)")
    void testUpdateCard_SuccessWithValidChanges() {
        // Arrange: card exists in repository with @Version=1
        when(cardRepository.findById(TEST_CARD_NUM)).thenReturn(Optional.of(testCard));
        when(cardRepository.save(any(Card.class))).thenReturn(testCard);

        // Create update request with different embossed name to trigger change detection
        // (maps CCUP-NEW-CRDNAME != CCUP-OLD-CRDNAME → CCUP-CHANGES-MADE)
        CardUpdateRequest request = new CardUpdateRequest(
                TEST_ACCT_ID, TEST_CARD_NUM, "JANE DOE", TEST_STATUS,
                "06", "2026", "15", null);

        // Act: invoke updateCard (maps 0000-MAIN orchestration)
        Card result = creditCardUpdateService.updateCard(TEST_CARD_NUM, request);

        // Assert: save was called (maps EXEC CICS REWRITE)
        verify(cardRepository).save(any(Card.class));
        assertThat(result).isNotNull();
        assertThat(result.getCardNum()).isEqualTo(TEST_CARD_NUM);
    }

    /**
     * Test 2: Card update fails with OptimisticLockException (concurrent modification).
     *
     * <p>Maps COBOL pattern: DATA-WAS-CHANGED-BEFORE-UPDATE at line 207
     * ('Record changed by some one else. Please review') and
     * COULD-NOT-LOCK-FOR-UPDATE at line 205 — these 88-level conditions
     * correspond to RESP codes from EXEC CICS READ UPDATE / REWRITE failures
     * when another terminal has modified the same card between read and write.</p>
     *
     * <p>In Java, JPA {@code @Version} detects this condition and throws
     * {@link OptimisticLockException}, which the service wraps in a
     * {@link ValidationException} preserving the original COBOL error message.</p>
     */
    @Test
    @DisplayName("Test 2: updateCard — OptimisticLockException (concurrent modification)")
    void testUpdateCard_OptimisticLockException() {
        // Arrange: card exists, but save throws concurrent modification error
        when(cardRepository.findById(TEST_CARD_NUM)).thenReturn(Optional.of(testCard));
        when(cardRepository.save(any(Card.class)))
                .thenThrow(new OptimisticLockException("Concurrent modification detected"));

        CardUpdateRequest request = new CardUpdateRequest(
                TEST_ACCT_ID, TEST_CARD_NUM, "JANE DOE", TEST_STATUS,
                "06", "2026", "15", null);

        // Act & Assert: should wrap OptimisticLockException in ValidationException
        // with the exact COBOL error message from DATA-WAS-CHANGED-BEFORE-UPDATE
        assertThatThrownBy(() -> creditCardUpdateService.updateCard(TEST_CARD_NUM, request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Record changed by some one else");
    }

    // ========================================================================
    // Field Validation Tests — 1200-EDIT-MAP-INPUTS sub-paragraphs
    // Each test verifies a specific COBOL validation paragraph through the
    // public editMapInputs() method. "Returns true" in the spec maps to
    // the absence of that field's error in the returned list; "Returns false"
    // maps to the presence of the error message.
    // ========================================================================

    /**
     * Test 3: Valid embossed name passes validation (editName returns true).
     *
     * <p>Maps 1230-EDIT-NAME: CARD-EMBOSSED-NAME PIC X(50) — validates that
     * the embossed name contains only alphabetic characters and spaces.
     * COBOL logic: INSPECT CONVERTING LIT-ALL-ALPHA-FROM TO SPACES, then
     * checks if result TRIM LENGTH = 0 (i.e., all chars were letters/spaces).</p>
     */
    @Test
    @DisplayName("Test 3: editName valid — 1230-EDIT-NAME accepts 'JOHN DOE'")
    void testEditName_Valid() {
        // Arrange: request with valid name "JOHN DOE" (alpha + spaces)
        CardUpdateRequest request = createValidRequest();

        // Act: validate all fields via editMapInputs
        List<String> errors = creditCardUpdateService.editMapInputs(request);

        // Assert: no name-related error (editName returns true internally)
        assertThat(errors).noneMatch(e -> e.contains("Card name"));
    }

    /**
     * Test 4: Blank embossed name fails validation (editName returns false).
     *
     * <p>Maps 1230-EDIT-NAME: when CARD-EMBOSSED-NAME is spaces/low-values,
     * COBOL sets FLG-CARDNAME-NOT-OK and moves WS-PROMPT-FOR-NAME
     * ('Card name not provided') to WS-RETURN-MSG.</p>
     */
    @Test
    @DisplayName("Test 4: editName blank — 1230-EDIT-NAME rejects empty name")
    void testEditName_Blank() {
        // Arrange: request with blank embossed name (maps LOW-VALUES / SPACES)
        CardUpdateRequest request = new CardUpdateRequest(
                TEST_ACCT_ID, TEST_CARD_NUM, "", TEST_STATUS,
                "06", "2026", "15", null);

        // Act
        List<String> errors = creditCardUpdateService.editMapInputs(request);

        // Assert: name validation error present (editName returns false)
        assertThat(errors).anyMatch(e -> e.contains("Card name"));
    }

    /**
     * Test 5: Active status 'Y' passes validation (editCardStatus returns true).
     *
     * <p>Maps 1240-EDIT-CARDSTATUS: CARD-ACTIVE-STATUS = 'Y' satisfies
     * FLG-YES-NO-VALID (88 VALUES 'Y', 'N') at line 91.</p>
     */
    @Test
    @DisplayName("Test 5: editCardStatus valid 'Y' — 1240-EDIT-CARDSTATUS accepts active")
    void testEditCardStatus_ValidY() {
        // Arrange: request with status "Y" (active card)
        CardUpdateRequest request = new CardUpdateRequest(
                TEST_ACCT_ID, TEST_CARD_NUM, TEST_NAME, "Y",
                "06", "2026", "15", null);

        // Act
        List<String> errors = creditCardUpdateService.editMapInputs(request);

        // Assert: no status-related error (editCardStatus returns true)
        assertThat(errors).noneMatch(e -> e.contains("Status must be Y or N"));
    }

    /**
     * Test 6: Active status 'N' passes validation (editCardStatus returns true).
     *
     * <p>Maps 1240-EDIT-CARDSTATUS: 'N' is also a valid value per
     * FLG-YES-NO-VALID (88 VALUES 'Y', 'N').</p>
     */
    @Test
    @DisplayName("Test 6: editCardStatus valid 'N' — 1240-EDIT-CARDSTATUS accepts inactive")
    void testEditCardStatus_ValidN() {
        // Arrange: request with status "N" (inactive card)
        CardUpdateRequest request = new CardUpdateRequest(
                TEST_ACCT_ID, TEST_CARD_NUM, TEST_NAME, "N",
                "06", "2026", "15", null);

        // Act
        List<String> errors = creditCardUpdateService.editMapInputs(request);

        // Assert: no status-related error (editCardStatus returns true)
        assertThat(errors).noneMatch(e -> e.contains("Status must be Y or N"));
    }

    /**
     * Test 7: Invalid active status fails validation (editCardStatus returns false).
     *
     * <p>Maps 1240-EDIT-CARDSTATUS: any value other than 'Y' or 'N' sets
     * FLG-CARDSTATUS-NOT-OK and moves CARD-STATUS-MUST-BE-YES-NO
     * ('Card Active Status must be Y or N') to WS-RETURN-MSG.</p>
     */
    @Test
    @DisplayName("Test 7: editCardStatus invalid 'X' — 1240-EDIT-CARDSTATUS rejects")
    void testEditCardStatus_Invalid() {
        // Arrange: request with invalid status "X" (not Y or N)
        CardUpdateRequest request = new CardUpdateRequest(
                TEST_ACCT_ID, TEST_CARD_NUM, TEST_NAME, "X",
                "06", "2026", "15", null);

        // Act
        List<String> errors = creditCardUpdateService.editMapInputs(request);

        // Assert: status validation error present (editCardStatus returns false)
        assertThat(errors).anyMatch(e -> e.contains("Status must be Y or N"));
    }

    /**
     * Test 8: Valid expiry month passes validation (editExpiryMon returns true).
     *
     * <p>Maps 1250-EDIT-EXPIRY-MON: VALID-MONTH (88 VALUES 1 THRU 12).
     * Tests month "06" (June) as a representative valid month within the
     * COBOL-defined range.</p>
     */
    @Test
    @DisplayName("Test 8: editExpiryMon valid '06' — 1250-EDIT-EXPIRY-MON accepts 1-12")
    void testEditExpiryMon_Valid() {
        // Arrange: request with valid month "06" (June, within 1-12)
        CardUpdateRequest request = createValidRequest();

        // Act
        List<String> errors = creditCardUpdateService.editMapInputs(request);

        // Assert: no month-related error (editExpiryMon returns true for 06)
        assertThat(errors).noneMatch(e -> e.contains("expiry month"));
    }

    /**
     * Test 9: Invalid expiry month fails validation (editExpiryMon returns false).
     *
     * <p>Maps 1250-EDIT-EXPIRY-MON: values outside 1–12 set
     * FLG-CARDEXPMON-NOT-OK and move CARD-EXPIRY-MONTH-NOT-VALID
     * ('Card expiry month must be between 1 and 12') to WS-RETURN-MSG.</p>
     */
    @Test
    @DisplayName("Test 9: editExpiryMon invalid '13' — 1250-EDIT-EXPIRY-MON rejects")
    void testEditExpiryMon_Invalid() {
        // Arrange: request with invalid month "13" (exceeds 12)
        CardUpdateRequest request = new CardUpdateRequest(
                TEST_ACCT_ID, TEST_CARD_NUM, TEST_NAME, TEST_STATUS,
                "13", "2026", "15", null);

        // Act
        List<String> errors = creditCardUpdateService.editMapInputs(request);

        // Assert: month validation error present (editExpiryMon returns false)
        assertThat(errors).anyMatch(e -> e.contains("expiry month"));
    }

    /**
     * Test 10: Valid expiry year passes validation (editExpiryYear returns true).
     *
     * <p>Maps 1260-EDIT-EXPIRY-YEAR: VALID-YEAR (88 VALUES 1950 THRU 2099).
     * Tests "2026" as a representative valid year within the COBOL-defined
     * range. The COBOL validation is a pure range check (1950–2099), not an
     * expiration check against current date.</p>
     */
    @Test
    @DisplayName("Test 10: editExpiryYear valid '2026' — 1260-EDIT-EXPIRY-YEAR accepts 1950-2099")
    void testEditExpiryYear_Valid() {
        // Arrange: request with valid year "2026" (within 1950-2099)
        CardUpdateRequest request = createValidRequest();

        // Act
        List<String> errors = creditCardUpdateService.editMapInputs(request);

        // Assert: no year-related error (editExpiryYear returns true for 2026)
        assertThat(errors).noneMatch(e -> e.contains("expiry year"));
    }

    /**
     * Test 11: Invalid expiry year fails validation (editExpiryYear returns false).
     *
     * <p>Maps 1260-EDIT-EXPIRY-YEAR: values outside 1950–2099 set
     * FLG-CARDEXPYEAR-NOT-OK and move CARD-EXPIRY-YEAR-NOT-VALID
     * ('Invalid card expiry year') to WS-RETURN-MSG.
     * Tests "0000" (below minimum 1950) as an invalid year.</p>
     */
    @Test
    @DisplayName("Test 11: editExpiryYear invalid '0000' — 1260-EDIT-EXPIRY-YEAR rejects")
    void testEditExpiryYear_Invalid() {
        // Arrange: request with invalid year "0000" (below minimum 1950)
        CardUpdateRequest request = new CardUpdateRequest(
                TEST_ACCT_ID, TEST_CARD_NUM, TEST_NAME, TEST_STATUS,
                "06", "0000", "15", null);

        // Act
        List<String> errors = creditCardUpdateService.editMapInputs(request);

        // Assert: year validation error present (editExpiryYear returns false)
        assertThat(errors).anyMatch(e -> e.contains("expiry year"));
    }

    // ========================================================================
    // Record Not Found Test — 9000-READ-DATA / 9100-GETCARD-BYACCTCARD
    // ========================================================================

    /**
     * Test 12: Read card data returns not found (DFHRESP(NOTFND)).
     *
     * <p>Maps 9000-READ-DATA → 9100-GETCARD-BYACCTCARD: when EXEC CICS READ
     * DATASET('CARDDAT') RIDFLD(WS-CARD-RID-CARDNUM) returns RESP=13
     * (DFHRESP(NOTFND)), COBOL sets DID-NOT-FIND-ACCTCARD-COMBO
     * ('Did not find cards for this search condition').</p>
     *
     * <p>In Java, this maps to {@code Optional.empty()} from
     * {@code CardRepository.findById()}, which triggers
     * {@link RecordNotFoundException} (VSAM file status '23').</p>
     */
    @Test
    @DisplayName("Test 12: readData — card not found (DFHRESP(NOTFND) / status '23')")
    void testReadData_CardNotFound() {
        // Arrange: card does not exist in repository (VSAM NOTFND)
        when(cardRepository.findById(TEST_CARD_NUM)).thenReturn(Optional.empty());

        // Act & Assert: should throw RecordNotFoundException (file status '23')
        assertThatThrownBy(() -> creditCardUpdateService.readData(TEST_CARD_NUM))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("Card");
    }

    // ========================================================================
    // Combined Validation Test — 1200-EDIT-MAP-INPUTS
    // ========================================================================

    /**
     * Test 13: Multiple validation errors returned simultaneously.
     *
     * <p>Maps 1200-EDIT-MAP-INPUTS performing all sub-paragraph validations
     * (1210 through 1260). When multiple fields are invalid, the service
     * collects all errors in a list — matching the COBOL pattern where each
     * field flag is checked independently and multiple WS-RETURN-MSG values
     * can accumulate across the PERFORM sequence.</p>
     *
     * <p>Test input: blank name (violates 1230), invalid status "X"
     * (violates 1240), and invalid month "13" (violates 1250).</p>
     */
    @Test
    @DisplayName("Test 13: editMapInputs — multiple validation errors collected")
    void testEditMapInputs_MultipleValidationErrors() {
        // Arrange: request with blank name, invalid status "X", invalid month "13"
        // Account ID and card number are valid; year is valid (2026)
        CardUpdateRequest request = new CardUpdateRequest(
                TEST_ACCT_ID, TEST_CARD_NUM, "", "X",
                "13", "2026", "15", null);

        // Act: validate all fields
        List<String> errors = creditCardUpdateService.editMapInputs(request);

        // Assert: at least 3 validation errors (name, status, month)
        assertThat(errors).hasSizeGreaterThanOrEqualTo(3);

        // Verify each specific error message is present
        assertThat(errors).anyMatch(e -> e.contains("Card name"));
        assertThat(errors).anyMatch(e -> e.contains("Status must be Y or N"));
        assertThat(errors).anyMatch(e -> e.contains("expiry month"));
    }

    // ========================================================================
    // Private helper methods
    // ========================================================================

    /**
     * Creates a fully valid {@link CardUpdateRequest} where all fields pass
     * validation. Used as a baseline for individual field validation tests
     * that verify one field at a time.
     *
     * <p>Field values match the COBOL validation rules:</p>
     * <ul>
     *   <li>accountId: "00000000050" — 11-digit numeric, non-zero</li>
     *   <li>cardNum: "4111111111111111" — 16-digit numeric</li>
     *   <li>embossedName: "JOHN DOE" — alphabetic + spaces</li>
     *   <li>activeStatus: "Y" — matches FLG-YES-NO-VALID</li>
     *   <li>expiryMonth: "06" — within VALID-MONTH (1–12)</li>
     *   <li>expiryYear: "2026" — within VALID-YEAR (1950–2099)</li>
     *   <li>expiryDay: "15" — valid day</li>
     * </ul>
     *
     * @return a valid CardUpdateRequest passing all 1200-EDIT-MAP-INPUTS checks
     */
    private CardUpdateRequest createValidRequest() {
        return new CardUpdateRequest(
                TEST_ACCT_ID,      // valid: 11-digit numeric, non-zero
                TEST_CARD_NUM,     // valid: 16-digit numeric
                TEST_NAME,         // valid: alphabetic + spaces
                TEST_STATUS,       // valid: "Y"
                "06",              // valid: month 1-12
                "2026",            // valid: year 1950-2099
                "15",              // valid: day
                null               // version: null = server-side only locking
        );
    }
}
