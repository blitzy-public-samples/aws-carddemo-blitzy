package com.cardemo.service.online;

import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.entity.Card;
import com.cardemo.entity.CardXref;
import com.cardemo.repository.CardRepository;
import com.cardemo.repository.CardXrefRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CreditCardDetailService} — validates card detail/selection
 * logic ported from COCRDSLC.cbl. Tests cover primary key card reads (CARDDAT),
 * AIX-based card reads by account (CARDAIX), input validation, and cross-reference
 * resolution via CARDXREF.
 *
 * <p>COBOL Paragraph Mapping:
 * <ul>
 *   <li>0000-MAIN → viewCardDetail() navigation and dispatch</li>
 *   <li>2000-PROCESS-INPUTS → processInputs() validate and read</li>
 *   <li>2200-EDIT-MAP-INPUTS → editMapInputs() field validation</li>
 *   <li>9100-GETCARD-BYACCTCARD → getCardByAcctCard() primary key read</li>
 *   <li>9150-GETCARD-BYACCT → getCardByAcct() AIX-based read</li>
 * </ul>
 *
 * @see CreditCardDetailService
 */
@ExtendWith(MockitoExtension.class)
class CreditCardDetailServiceTest {

    /** Card number matching CARD-NUM PIC X(16) from CVACT02Y.cpy */
    private static final String TEST_CARD_NUM = "4111111111111111";

    /** Account ID matching CARD-ACCT-ID PIC 9(11) from CVACT02Y.cpy */
    private static final String TEST_ACCOUNT_ID = "00000000001";

    /** CVV code for test card fixture — CARD-CVV-CD PIC 9(03) */
    private static final String TEST_CVV = "123";

    /** Embossed name for test card — CARD-EMBOSSED-NAME PIC X(50) */
    private static final String TEST_EMBOSSED_NAME = "JOHN DOE";

    /** Expiration date for test card — CARD-EXPIRAION-DATE PIC X(10) */
    private static final String TEST_EXPIRATION_DATE = "2025-12-31";

    /** Active status for test card — 'Y' = active per CARD-ACTIVE-STATUS PIC X(01) */
    private static final String TEST_ACTIVE_STATUS = "Y";

    /** Customer ID for cross-reference — XREF-CUST-ID PIC 9(09) from CVACT03Y.cpy */
    private static final String TEST_CUST_ID = "000000001";

    /** Non-existent card number used for NOTFND (RESP=13) simulation */
    private static final String NONEXISTENT_CARD_NUM = "9999999999999999";

    /** Non-existent account ID used for NOTFND (RESP=13) simulation */
    private static final String NONEXISTENT_ACCOUNT_ID = "99999999999";

    /** COCRDLIC program name — matches CC_LIST_PROGRAM constant in service */
    private static final String CC_LIST_PROGRAM = "COCRDLIC";

    @Mock
    private CardRepository cardRepository;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private CardDemoContext cardDemoContext;

    @InjectMocks
    private CreditCardDetailService creditCardDetailService;

    /** Test Card entity populated from CVACT02Y.cpy CARD-RECORD fixture data */
    private Card testCard;

    /** Test CardXref entity from CVACT03Y.cpy CARD-XREF-RECORD fixture data */
    private CardXref testXref;

    /**
     * Initialize test fixtures before each test method. Creates Card and CardXref
     * entities using parameterized constructors matching VSAM record layouts.
     */
    @BeforeEach
    void setUp() {
        testCard = new Card(TEST_CARD_NUM, TEST_ACCOUNT_ID, TEST_CVV,
                TEST_EMBOSSED_NAME, TEST_EXPIRATION_DATE, TEST_ACTIVE_STATUS);
        testXref = new CardXref(TEST_CARD_NUM, TEST_CUST_ID, TEST_ACCOUNT_ID);
    }

    /**
     * Test 1: viewCardDetail with card number — Maps 9100-GETCARD-BYACCTCARD.
     * EXEC CICS READ DATASET('CARDDAT') RIDFLD(WS-CARD-RID-CARDNUM).
     *
     * <p>Verifies: When entering from credit card list screen (COCRDLIC) with a
     * card number, the service performs a primary key read on CARDDAT and returns
     * the matching card. The AIX path must NOT be invoked.
     */
    @Test
    @DisplayName("viewCardDetail: primary key read by cardNum from CC list screen")
    void testViewCardDetail_ByCardNum() {
        // Arrange — Mock COMMAREA context for PGM-ENTER from COCRDLIC
        when(cardDemoContext.getFromProgram()).thenReturn(CC_LIST_PROGRAM);
        when(cardDemoContext.getPgmContext()).thenReturn(CardDemoContext.PGM_ENTER);
        when(cardDemoContext.isReenterContext()).thenReturn(false);
        // resolveAccountId(null) falls through to context lookup
        when(cardDemoContext.getAcctId()).thenReturn(TEST_ACCOUNT_ID);

        // Mock primary key read — EXEC CICS READ DATASET('CARDDAT')
        when(cardRepository.findById(TEST_CARD_NUM)).thenReturn(Optional.of(testCard));

        // Act — enter with cardNum provided, accountId null
        Card result = creditCardDetailService.viewCardDetail(TEST_CARD_NUM, null);

        // Assert — card returned with all CVACT02Y.cpy fields intact
        assertThat(result).isNotNull();
        assertThat(result.getCardNum()).isEqualTo(TEST_CARD_NUM);
        assertThat(result.getAccountId()).isEqualTo(TEST_ACCOUNT_ID);
        assertThat(result.getActiveStatus()).isEqualTo(TEST_ACTIVE_STATUS);
        assertThat(result.getEmbossedName()).isEqualTo(TEST_EMBOSSED_NAME);
        assertThat(result.getExpirationDate()).isEqualTo(TEST_EXPIRATION_DATE);
        assertThat(result.getCvvCode()).isEqualTo(TEST_CVV);

        // Verify primary key path was used, AIX path was NOT used
        verify(cardRepository, times(1)).findById(TEST_CARD_NUM);
        verify(cardXrefRepository, never()).findByAccountId(any());
        verify(cardRepository, never()).findByAccountId(any());
    }

    /**
     * Test 2: viewCardDetail with account ID only — Maps 9150-GETCARD-BYACCT.
     * EXEC CICS READ DATASET('CARDAIX') RIDFLD(WS-CARD-RID-ACCTID) — AIX read.
     *
     * <p>Verifies: When entering from CC list with only an account ID (no card number),
     * the service uses the AIX-based lookup via findByAccountId and returns the
     * first matching card.
     */
    @Test
    @DisplayName("viewCardDetail: AIX read by accountId from CC list screen")
    void testViewCardDetail_ByAccountId() {
        // Arrange — Mock COMMAREA context for PGM-ENTER from COCRDLIC
        when(cardDemoContext.getFromProgram()).thenReturn(CC_LIST_PROGRAM);
        when(cardDemoContext.getPgmContext()).thenReturn(CardDemoContext.PGM_ENTER);
        when(cardDemoContext.isReenterContext()).thenReturn(false);
        // resolveCardNum(null) falls through to context lookup which returns null
        when(cardDemoContext.getCardNum()).thenReturn(null);

        // Mock AIX read path — cross-reference lookup then account-based card read
        List<CardXref> xrefList = new ArrayList<>();
        xrefList.add(testXref);
        when(cardXrefRepository.findByAccountId(TEST_ACCOUNT_ID)).thenReturn(xrefList);

        List<Card> cardList = new ArrayList<>();
        cardList.add(testCard);
        when(cardRepository.findByAccountId(TEST_ACCOUNT_ID)).thenReturn(cardList);

        // Act — enter with cardNum null, accountId provided
        Card result = creditCardDetailService.viewCardDetail(null, TEST_ACCOUNT_ID);

        // Assert — first card from AIX path returned
        assertThat(result).isNotNull();
        assertThat(result.getCardNum()).isEqualTo(TEST_CARD_NUM);
        assertThat(result.getAccountId()).isEqualTo(TEST_ACCOUNT_ID);

        // Verify AIX path was used, primary key read was NOT used
        verify(cardXrefRepository, times(1)).findByAccountId(TEST_ACCOUNT_ID);
        verify(cardRepository, times(1)).findByAccountId(TEST_ACCOUNT_ID);
        verify(cardRepository, never()).findById(any());
    }

    /**
     * Test 3: getCardByAcctCard success — Maps 9100-GETCARD-BYACCTCARD.
     * Direct primary key card read combining both account and card identifiers.
     *
     * <p>Note: Parameter order is (accountId, cardNum) matching COBOL
     * WS-CARD-RID-ACCTID / WS-CARD-RID-CARDNUM working storage fields.
     */
    @Test
    @DisplayName("getCardByAcctCard: successful primary key card read")
    void testGetCardByAcctCard_Success() {
        // Arrange — Mock findById for primary key lookup on CARDDAT
        when(cardRepository.findById(TEST_CARD_NUM)).thenReturn(Optional.of(testCard));

        // Act — parameter order: (accountId, cardNum)
        Card result = creditCardDetailService.getCardByAcctCard(TEST_ACCOUNT_ID, TEST_CARD_NUM);

        // Assert — card returned with full CVACT02Y record fields
        assertThat(result).isNotNull();
        assertThat(result.getCardNum()).isEqualTo(TEST_CARD_NUM);
        assertThat(result.getAccountId()).isEqualTo(TEST_ACCOUNT_ID);
        assertThat(result.getActiveStatus()).isEqualTo(TEST_ACTIVE_STATUS);
        assertThat(result.getEmbossedName()).isEqualTo(TEST_EMBOSSED_NAME);
        assertThat(result.getExpirationDate()).isEqualTo(TEST_EXPIRATION_DATE);

        // Verify exactly one primary key lookup occurred
        verify(cardRepository, times(1)).findById(TEST_CARD_NUM);
    }

    /**
     * Test 4: getCardByAcctCard not found — Maps 9100-GETCARD-BYACCTCARD RESP=13.
     * Simulates DFHRESP(NOTFND) when card does not exist in CARDDAT VSAM dataset.
     * Service must throw RecordNotFoundException equivalent to VSAM status '23'.
     */
    @Test
    @DisplayName("getCardByAcctCard: throws RecordNotFoundException for VSAM NOTFND")
    void testGetCardByAcctCard_NotFound() {
        // Arrange — Mock findById returning empty (VSAM status '23' / NOTFND)
        when(cardRepository.findById(NONEXISTENT_CARD_NUM)).thenReturn(Optional.empty());

        // Act & Assert — RecordNotFoundException maps DFHRESP(NOTFND)
        assertThatThrownBy(() ->
                creditCardDetailService.getCardByAcctCard(TEST_ACCOUNT_ID, NONEXISTENT_CARD_NUM))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("not found");

        // Verify lookup was attempted with the non-existent key
        verify(cardRepository, times(1)).findById(NONEXISTENT_CARD_NUM);
    }

    /**
     * Test 5: getCardByAcct success — Maps 9150-GETCARD-BYACCT.
     * AIX-based lookup returns list of cards associated with the account
     * via alternate index on CARD-ACCT-ID (position 16, length 11).
     */
    @Test
    @DisplayName("getCardByAcct: successful AIX-based card lookup by account")
    void testGetCardByAcct_Success() {
        // Arrange — Mock AIX lookup returning one card for the account
        List<Card> cardList = new ArrayList<>();
        cardList.add(testCard);
        when(cardRepository.findByAccountId(TEST_ACCOUNT_ID)).thenReturn(cardList);

        // Act
        List<Card> result = creditCardDetailService.getCardByAcct(TEST_ACCOUNT_ID);

        // Assert — non-empty result list with the correct card
        assertThat(result).isNotEmpty();
        assertThat(result.size()).isEqualTo(1);
        assertThat(result.isEmpty()).isFalse();
        assertThat(result.get(0).getCardNum()).isEqualTo(TEST_CARD_NUM);
        assertThat(result.get(0).getAccountId()).isEqualTo(TEST_ACCOUNT_ID);

        // Verify exactly one AIX lookup occurred
        verify(cardRepository, times(1)).findByAccountId(TEST_ACCOUNT_ID);
    }

    /**
     * Test 6: getCardByAcct not found — Maps 9150-GETCARD-BYACCT RESP=13.
     * Simulates DFHRESP(NOTFND) when no cards exist for the account via AIX.
     * Service must throw RecordNotFoundException for empty AIX result.
     */
    @Test
    @DisplayName("getCardByAcct: throws RecordNotFoundException for empty AIX result")
    void testGetCardByAcct_NotFound() {
        // Arrange — Mock AIX lookup returning empty list (VSAM NOTFND via AIX)
        when(cardRepository.findByAccountId(NONEXISTENT_ACCOUNT_ID)).thenReturn(List.of());

        // Act & Assert — RecordNotFoundException for missing account in cross-reference
        assertThatThrownBy(() ->
                creditCardDetailService.getCardByAcct(NONEXISTENT_ACCOUNT_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("Did not find");

        // Verify lookup was attempted for the non-existent account
        verify(cardRepository, times(1)).findByAccountId(NONEXISTENT_ACCOUNT_ID);
    }

    /**
     * Test 7: editMapInputs with valid inputs — Maps 2200-EDIT-MAP-INPUTS.
     * 2210-EDIT-ACCOUNT validates 11-digit numeric account ID.
     * 2220-EDIT-CARD validates 16-digit numeric card number.
     *
     * <p>Both inputs are valid: zero validation errors expected.
     */
    @Test
    @DisplayName("editMapInputs: returns empty errors for valid account and card inputs")
    void testEditMapInputs_ValidInputs() {
        // Act — editMapInputs(cardNum, accountId): both valid
        List<String> errors = creditCardDetailService.editMapInputs(TEST_CARD_NUM, TEST_ACCOUNT_ID);

        // Assert — no validation errors for valid 16-digit card + 11-digit account
        assertThat(errors).isNotNull();
        assertThat(errors.isEmpty()).isTrue();
        assertThat(errors.size()).isEqualTo(0);
    }

    /**
     * Test 8: editMapInputs with invalid card number — Maps 2220-EDIT-CARD.
     * Card number that is too short (not 16 digits) triggers the CARD ID FILTER
     * validation error from the COBOL 2220-EDIT-CARD paragraph.
     */
    @Test
    @DisplayName("editMapInputs: returns validation error for invalid card number")
    void testEditMapInputs_InvalidCardNum() {
        // Arrange — card number too short (5 digits instead of required 16)
        String invalidCardNum = "12345";

        // Act — editMapInputs(cardNum, accountId): valid account, invalid card
        List<String> errors = creditCardDetailService.editMapInputs(invalidCardNum, TEST_ACCOUNT_ID);

        // Assert — validation error list should contain card filter error
        assertThat(errors).isNotEmpty();
        assertThat(errors.size()).isGreaterThanOrEqualTo(1);
        // Error message matches COBOL MSG_CARD_FILTER_ERROR pattern
        assertThat(errors.get(0)).contains("CARD");
    }

    /**
     * Test 9: viewCardDetail with cross-reference lookup — Tests that CardXrefRepository
     * is used for cross-reference resolution when looking up cards by account ID
     * through the AIX path in readData.
     *
     * <p>Maps the CARDXREF VSAM dataset (CVACT03Y.cpy) access in readData: when
     * only an account ID is available, the service first queries CardXrefRepository
     * to resolve cross-references, then reads cards via findByAccountId.
     *
     * <p>This test explicitly verifies cross-reference interaction patterns,
     * including CardXref entity field access (xrefCardNum, accountId, custId).
     */
    @Test
    @DisplayName("viewCardDetail: cross-reference lookup via CardXrefRepository")
    void testViewCardDetail_CrossRefLookup() {
        // Arrange — Mock COMMAREA context for PGM-ENTER from COCRDLIC
        when(cardDemoContext.getFromProgram()).thenReturn(CC_LIST_PROGRAM);
        when(cardDemoContext.getPgmContext()).thenReturn(CardDemoContext.PGM_ENTER);
        when(cardDemoContext.isReenterContext()).thenReturn(false);
        // No card number in context — forces AIX path through cross-reference
        when(cardDemoContext.getCardNum()).thenReturn(null);

        // Mock cross-reference lookup — CARDXREF (CVACT03Y.cpy CARD-XREF-RECORD)
        CardXref xref = new CardXref(TEST_CARD_NUM, TEST_CUST_ID, TEST_ACCOUNT_ID);
        xref.setCustId(TEST_CUST_ID);
        List<CardXref> xrefList = new ArrayList<>();
        xrefList.add(xref);
        when(cardXrefRepository.findByAccountId(TEST_ACCOUNT_ID)).thenReturn(xrefList);

        // Mock account-based card read via AIX
        List<Card> cardList = new ArrayList<>();
        cardList.add(testCard);
        when(cardRepository.findByAccountId(TEST_ACCOUNT_ID)).thenReturn(cardList);

        // Act — enter with accountId only, no cardNum, forcing cross-reference path
        Card result = creditCardDetailService.viewCardDetail(null, TEST_ACCOUNT_ID);

        // Assert — card returned successfully from cross-reference resolution
        assertThat(result).isNotNull();
        assertThat(result.getCardNum()).isEqualTo(TEST_CARD_NUM);
        assertThat(result.getAccountId()).isEqualTo(TEST_ACCOUNT_ID);

        // Verify cross-reference resolution was performed via CardXrefRepository
        verify(cardXrefRepository, times(1)).findByAccountId(TEST_ACCOUNT_ID);

        // Verify CardXref entity fields — CVACT03Y.cpy record layout integrity
        assertThat(xref.getXrefCardNum()).isEqualTo(TEST_CARD_NUM);
        assertThat(xref.getAccountId()).isEqualTo(TEST_ACCOUNT_ID);
    }
}
