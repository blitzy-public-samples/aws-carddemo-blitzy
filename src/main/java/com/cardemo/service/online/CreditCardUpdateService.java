package com.cardemo.service.online;

import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.exception.ValidationException;
import com.cardemo.common.message.MessageConstants;
import com.cardemo.entity.Card;
import com.cardemo.repository.CardRepository;

import jakarta.persistence.OptimisticLockException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Credit Card Update Service — faithfully translates COCRDUPC.cbl.
 *
 * <p>This service handles the credit-card update workflow including:
 * <ul>
 *   <li>Reading card data (9000-READ-DATA / 9100-GETCARD-BYACCTCARD)</li>
 *   <li>Validating card fields (1200-EDIT-MAP-INPUTS and sub-paragraphs 1210–1260)</li>
 *   <li>Detecting field changes (9300-CHECK-CHANGE-IN-REC, case-insensitive)</li>
 *   <li>Updating card records with optimistic locking (9200-WRITE-PROCESSING)</li>
 * </ul>
 *
 * <p>COBOL CICS patterns are mapped as follows:
 * <ul>
 *   <li>{@code EXEC CICS READ} → {@code CardRepository.findById()}</li>
 *   <li>{@code EXEC CICS READ UPDATE} → JPA {@code @Version} optimistic locking</li>
 *   <li>{@code EXEC CICS REWRITE} → {@code CardRepository.save()}</li>
 *   <li>{@code EXEC CICS SYNCPOINT} → {@code @Transactional}</li>
 *   <li>{@code COMMAREA} → {@code CardDemoContext} (request-scoped)</li>
 * </ul>
 *
 * @see Card
 * @see CardRepository
 * @see CardDemoContext
 */
@Service
public class CreditCardUpdateService {

    private static final Logger logger = LoggerFactory.getLogger(CreditCardUpdateService.class);

    /** COBOL program name literal (LIT-THISPGM = 'COCRDUPC'). */
    private static final String PROGRAM_NAME = "COCRDUPC";

    // Validation range constants (from COBOL 1250-EDIT-EXPIRY-MON / 1260-EDIT-EXPIRY-YEAR)
    private static final int MIN_EXPIRY_YEAR = 1950;
    private static final int MAX_EXPIRY_YEAR = 2099;
    private static final int MIN_EXPIRY_MONTH = 1;
    private static final int MAX_EXPIRY_MONTH = 12;
    private static final int ACCOUNT_ID_MAX_LENGTH = 11;
    private static final int CARD_NUM_MAX_LENGTH = 16;

    private final CardRepository cardRepository;
    private final CardDemoContext cardDemoContext;

    /**
     * Constructor injection of dependencies.
     * Replaces COBOL program loading by the CICS transaction manager.
     *
     * @param cardRepository JPA repository for the Card entity (VSAM CARDDAT file)
     * @param cardDemoContext request-scoped session context (COMMAREA equivalent)
     */
    public CreditCardUpdateService(CardRepository cardRepository,
                                   CardDemoContext cardDemoContext) {
        this.cardRepository = cardRepository;
        this.cardDemoContext = cardDemoContext;
    }

    // ========================================================================
    // Inner types
    // ========================================================================

    /**
     * Immutable request record for card update operations.
     * Maps the COCRDUP BMS map input fields (CCRDUPAI) defined in COCRDUP.CPY.
     *
     * <p>Field mapping:
     * <ul>
     *   <li>{@code accountId}    → ACCTSIDI PIC X(11)</li>
     *   <li>{@code cardNum}      → CARDSIDI PIC X(16)</li>
     *   <li>{@code embossedName} → CRDNAMEI PIC X(50)</li>
     *   <li>{@code activeStatus} → CRDSTCDI PIC X(1)  — must be 'Y' or 'N'</li>
     *   <li>{@code expiryMonth}  → EXPMONI  PIC X(2)  — 01–12</li>
     *   <li>{@code expiryYear}   → EXPYEARI PIC X(4)  — 1950–2099</li>
     *   <li>{@code expiryDay}    → EXPDAYI  PIC X(2)  — 01–31</li>
     *   <li>{@code version}      → optional entity version for client-side optimistic
     *       locking; when provided, the server validates it matches the current entity
     *       version before applying updates. Maps to the JPA {@code @Version} field
     *       on the {@link Card} entity, which translates the COBOL CICS READ UPDATE
     *       → REWRITE pattern. If {@code null}, server-side-only optimistic locking
     *       applies.</li>
     * </ul>
     */
    public record CardUpdateRequest(
            String accountId,
            String cardNum,
            String embossedName,
            String activeStatus,
            String expiryMonth,
            String expiryYear,
            String expiryDay,
            Long version
    ) { }

    // ========================================================================
    // Public service methods (exported per schema)
    // ========================================================================

    /**
     * Performs an atomic credit-card update with optimistic locking.
     *
     * <p>Maps COBOL paragraphs:
     * <ul>
     *   <li>0000-MAIN (line 367) — orchestration</li>
     *   <li>1200-EDIT-MAP-INPUTS (line 641) — field validation</li>
     *   <li>2000-DECIDE-ACTION (line 948) — change detection and action decision</li>
     *   <li>9200-WRITE-PROCESSING (line 1420) — READ UPDATE then REWRITE</li>
     *   <li>9300-CHECK-CHANGE-IN-REC (line 1498) — concurrent modification check</li>
     * </ul>
     *
     * @param cardNum the card number primary key (16-character string)
     * @param request the update request containing new field values
     * @return the updated {@link Card} entity after successful persist
     * @throws ValidationException      if field validation fails or no changes detected
     * @throws RecordNotFoundException  if the card record does not exist
     */
    @Transactional
    public Card updateCard(String cardNum, CardUpdateRequest request) {
        logger.info("[{}] Credit card update initiated for card ending in: ...{}, user: {}",
                PROGRAM_NAME, maskCardNum(cardNum), cardDemoContext.getUserId());
        logger.debug("[{}] Update request from program: {}", PROGRAM_NAME,
                cardDemoContext.getFromProgram());

        // Guard: card number must be provided
        if (cardNum == null || cardNum.isBlank()) {
            logger.warn("[{}] Null or blank card number — {}", PROGRAM_NAME,
                    MessageConstants.INVALID_KEY_MESSAGE.trim());
            throw new ValidationException("cardNum", "Card number is required");
        }

        // 1200-EDIT-MAP-INPUTS: validate all input fields
        List<String> validationErrors = editMapInputs(request);
        if (!validationErrors.isEmpty()) {
            String combinedErrors = String.join("; ", validationErrors);
            logger.warn("[{}] Card update validation failed: ...{} — {}",
                    PROGRAM_NAME, maskCardNum(cardNum), combinedErrors);
            throw new ValidationException("cardUpdate", combinedErrors);
        }

        // 9200-WRITE-PROCESSING step 1:
        // EXEC CICS READ UPDATE DATASET('CARDDAT') RIDFLD(WS-CARD-RID-CARDNUM)
        Optional<Card> optionalCard = cardRepository.findById(cardNum);
        if (!optionalCard.isPresent()) {
            logger.error("[{}] Card not found for update: ...{}", PROGRAM_NAME,
                    maskCardNum(cardNum));
            throw new RecordNotFoundException("Card", cardNum);
        }
        Card card = optionalCard.orElseThrow(() ->
                new RecordNotFoundException("Card", cardNum));

        logger.debug("[{}] Card record read for update — version: {}, CVV present: {}",
                PROGRAM_NAME, card.getVersion(), card.getCvvCode() != null);

        // Client-side optimistic locking check — translates COBOL READ UPDATE
        // detecting stale data. If the client provides a version number, it must
        // match the current entity version. Stale version → 409 Conflict.
        if (request.version() != null && !request.version().equals(card.getVersion())) {
            logger.warn("[{}] Stale version detected: client={}, current={}",
                    PROGRAM_NAME, request.version(), card.getVersion());
            throw new OptimisticLockException(
                    "Record changed by some one else. Please review");
        }

        // 2000-DECIDE-ACTION: detect if any fields actually changed
        // Maps 9300-CHECK-CHANGE-IN-REC (line 1498) case-insensitive comparison
        boolean hasChanges = detectChanges(card, request);
        if (!hasChanges) {
            // COBOL: 'No change detected with respect to values fetched.'
            logger.info("[{}] No changes detected for card: ...{}", PROGRAM_NAME,
                    maskCardNum(cardNum));
            throw new ValidationException(
                    "No change detected with respect to values fetched.");
        }

        // 9200-WRITE-PROCESSING step 2: prepare and apply updates (lines 1461–1475)
        applyUpdates(card, request);

        // 9200-WRITE-PROCESSING step 3:
        // EXEC CICS REWRITE FILE(LIT-CARDFILENAME) FROM(CARD-UPDATE-RECORD)
        // JPA save() with @Version triggers optimistic locking
        try {
            Card savedCard = cardRepository.save(card);

            // Update COMMAREA context fields
            cardDemoContext.setCardNum(savedCard.getCardNum());
            cardDemoContext.setAcctId(savedCard.getAccountId());

            logger.info("[{}] Card record updated successfully: ...{} — {}",
                    PROGRAM_NAME, maskCardNum(cardNum),
                    MessageConstants.THANK_YOU_MESSAGE.trim());
            return savedCard;

        } catch (OptimisticLockException ex) {
            // Maps COULD-NOT-LOCK-FOR-UPDATE (line 1446) and
            // DATA-WAS-CHANGED-BEFORE-UPDATE (line 1511)
            logger.warn("[{}] Optimistic lock conflict during card update: ...{}",
                    PROGRAM_NAME, maskCardNum(cardNum));
            throw new ValidationException("cardUpdate",
                    "Record changed by some one else. Please review");
        }
    }

    /**
     * Reads a card record by card number.
     *
     * <p>Maps COBOL paragraphs:
     * <ul>
     *   <li>9000-READ-DATA (line 1343) — initialise old-details and delegate to read</li>
     *   <li>9100-GETCARD-BYACCTCARD (line 1376) — EXEC CICS READ FILE('CARDDAT')</li>
     * </ul>
     *
     * <p>After a successful read the {@link CardDemoContext} is updated with the
     * card number and account ID so that subsequent operations (update, navigation)
     * carry the correct context.</p>
     *
     * @param cardNum the card number primary key (16-character string)
     * @return the {@link Card} entity
     * @throws RecordNotFoundException if the card record is not found
     *         (maps DFHRESP(NOTFND) at line 1395)
     */
    public Card readData(String cardNum) {
        logger.debug("[{}] Reading card data for card: ...{}", PROGRAM_NAME,
                maskCardNum(cardNum));

        // Log current context state (uses getCardNum / getAcctId on context)
        logger.debug("[{}] Current context — card: {}, acct: {}",
                PROGRAM_NAME,
                maskCardNum(cardDemoContext.getCardNum()),
                cardDemoContext.getAcctId());

        // 9100-GETCARD-BYACCTCARD:
        // EXEC CICS READ FILE(LIT-CARDFILENAME) RIDFLD(WS-CARD-RID-CARDNUM)
        Card card = cardRepository.findById(cardNum)
                .orElseThrow(() -> {
                    // DFHRESP(NOTFND) → DID-NOT-FIND-ACCTCARD-COMBO (line 1400)
                    logger.warn("[{}] Card record not found: ...{}", PROGRAM_NAME,
                            maskCardNum(cardNum));
                    return new RecordNotFoundException("Card", cardNum);
                });

        // 9000-READ-DATA: populate old details for change detection
        // Access embossed name in uppercase per COBOL INSPECT CONVERTING
        // LIT-LOWER TO LIT-UPPER (lines 1356–1358)
        String embossedNameUpper = card.getEmbossedName() != null
                ? card.getEmbossedName().toUpperCase()
                : "";
        logger.debug("[{}] Card read — name='{}', status='{}', expiry='{}', acctId='{}'",
                PROGRAM_NAME, embossedNameUpper, card.getActiveStatus(),
                card.getExpirationDate(), card.getAccountId());

        // Access CVV code for completeness check
        // (maps MOVE CARD-CVV-CD TO CCUP-OLD-CVV-CD at line 1354)
        String cvvCode = card.getCvvCode();
        logger.debug("[{}] Card CVV present: {}", PROGRAM_NAME,
                cvvCode != null && !cvvCode.isBlank());

        // Update COMMAREA context (maps COBOL MOVE statements into COMMAREA)
        cardDemoContext.setCardNum(card.getCardNum());
        cardDemoContext.setAcctId(card.getAccountId());

        return card;
    }

    /**
     * Validates all card update request fields.
     *
     * <p>Maps COBOL paragraph 1200-EDIT-MAP-INPUTS (line 641) with
     * sub-paragraphs:
     * <ul>
     *   <li>1210-EDIT-ACCOUNT (line 721) — account ID: non-blank, numeric, max 11 digits</li>
     *   <li>1220-EDIT-CARD    (line 762) — card number: non-blank, numeric, max 16 digits</li>
     *   <li>1230-EDIT-NAME    (line 806) — embossed name: non-blank, alphabetic + spaces</li>
     *   <li>1240-EDIT-CARDSTATUS (line 845) — active status: 'Y' or 'N'</li>
     *   <li>1250-EDIT-EXPIRY-MON (line 877) — expiry month: numeric 1–12</li>
     *   <li>1260-EDIT-EXPIRY-YEAR (line 913) — expiry year: numeric 1950–2099</li>
     * </ul>
     *
     * @param request the card update request to validate
     * @return list of validation error messages; empty if all fields are valid
     */
    public List<String> editMapInputs(CardUpdateRequest request) {
        List<String> errors = new ArrayList<>();

        // 1210-EDIT-ACCOUNT: validate account ID
        if (!editAccount(request.accountId())) {
            errors.add("Account number not provided or invalid");
        }

        // 1220-EDIT-CARD: validate card number
        if (!editCard(request.cardNum())) {
            errors.add("Card number not provided or invalid");
        }

        // 1230-EDIT-NAME: validate embossed name (alpha + spaces only)
        if (!editName(request.embossedName())) {
            errors.add("Card name not provided or contains invalid characters");
        }

        // 1240-EDIT-CARDSTATUS: validate active status
        if (!editCardStatus(request.activeStatus())) {
            errors.add("Card Active Status must be Y or N");
        }

        // 1250-EDIT-EXPIRY-MON: validate expiry month
        if (!editExpiryMon(request.expiryMonth())) {
            errors.add("Card expiry month must be between 1 and 12");
        }

        // 1260-EDIT-EXPIRY-YEAR: validate expiry year
        if (!editExpiryYear(request.expiryYear())) {
            errors.add("Invalid card expiry year");
        }

        if (!errors.isEmpty()) {
            logger.debug("[{}] Validation errors for card update: {}", PROGRAM_NAME, errors);
        }

        return errors;
    }

    // ========================================================================
    // Private validation methods — COBOL paragraphs 1210–1260
    // ========================================================================

    /**
     * Maps 1210-EDIT-ACCOUNT (line 721).
     * Account ID must not be blank, must be numeric, and at most 11 digits.
     * COBOL uses {@code FUNCTION TEST-NUMVAL} to detect non-numeric input.
     *
     * @param accountId the account ID string
     * @return {@code true} if valid, {@code false} otherwise
     */
    private boolean editAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return false;
        }
        String trimmed = accountId.trim();
        if (trimmed.length() > ACCOUNT_ID_MAX_LENGTH) {
            return false;
        }
        // COBOL: FUNCTION TEST-NUMVAL(CARD-ACCT-ID-X) > 0 → not numeric
        if (!trimmed.chars().allMatch(Character::isDigit)) {
            return false;
        }
        // Account must not be all zeros
        return !trimmed.chars().allMatch(c -> c == '0');
    }

    /**
     * Maps 1220-EDIT-CARD (line 762).
     * Card number must not be blank, must be numeric, and at most 16 digits.
     * COBOL uses {@code FUNCTION TEST-NUMVAL} to detect non-numeric input.
     *
     * @param cardNum the card number string
     * @return {@code true} if valid, {@code false} otherwise
     */
    private boolean editCard(String cardNum) {
        if (cardNum == null || cardNum.isBlank()) {
            return false;
        }
        String trimmed = cardNum.trim();
        if (trimmed.length() > CARD_NUM_MAX_LENGTH) {
            return false;
        }
        return trimmed.chars().allMatch(Character::isDigit);
    }

    /**
     * Maps 1230-EDIT-NAME (line 806).
     * Embossed name must not be blank and must contain only letters and spaces.
     *
     * <p>COBOL logic:
     * {@code INSPECT CONVERTING LIT-ALL-ALPHA-FROM TO spaces}, then checks
     * whether {@code FUNCTION TRIM(result, TRAILING) LENGTH = 0} — i.e. the
     * original consisted solely of alphabetic characters and spaces.</p>
     *
     * @param embossedName the card embossed name
     * @return {@code true} if valid, {@code false} otherwise
     */
    private boolean editName(String embossedName) {
        if (embossedName == null || embossedName.isBlank()) {
            return false;
        }
        // Only letters and spaces are allowed (mirrors COBOL INSPECT CONVERTING)
        return embossedName.chars().allMatch(c -> Character.isLetter(c) || c == ' ');
    }

    /**
     * Maps 1240-EDIT-CARDSTATUS (line 845).
     * Active status must be {@code 'Y'} or {@code 'N'}.
     * COBOL: FLG-YES-NO-VALID check.
     *
     * @param status the active status string
     * @return {@code true} if valid ('Y' or 'N'), {@code false} otherwise
     */
    private boolean editCardStatus(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String upper = status.trim().toUpperCase();
        return "Y".equals(upper) || "N".equals(upper);
    }

    /**
     * Maps 1250-EDIT-EXPIRY-MON (line 877).
     * Expiry month must be numeric and in the range 1–12.
     * COBOL: VALID-MONTH range check.
     *
     * @param month the expiry month string
     * @return {@code true} if valid (1–12), {@code false} otherwise
     */
    private boolean editExpiryMon(String month) {
        if (month == null || month.isBlank()) {
            return false;
        }
        try {
            int monthVal = Integer.parseInt(month.trim());
            return monthVal >= MIN_EXPIRY_MONTH && monthVal <= MAX_EXPIRY_MONTH;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /**
     * Maps 1260-EDIT-EXPIRY-YEAR (line 913).
     * Expiry year must be numeric and in the range 1950–2099.
     * COBOL: VALID-YEAR range check.
     *
     * @param year the expiry year string (4 digits)
     * @return {@code true} if valid (1950–2099), {@code false} otherwise
     */
    private boolean editExpiryYear(String year) {
        if (year == null || year.isBlank()) {
            return false;
        }
        try {
            int yearVal = Integer.parseInt(year.trim());
            return yearVal >= MIN_EXPIRY_YEAR && yearVal <= MAX_EXPIRY_YEAR;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    // ========================================================================
    // Private helper methods
    // ========================================================================

    /**
     * Detects whether any card fields were changed between the current record
     * and the update request, using case-insensitive comparison.
     *
     * <p>Maps 9300-CHECK-CHANGE-IN-REC (line 1498) and the change detection
     * logic in 1200-EDIT-MAP-INPUTS where {@code FUNCTION UPPER-CASE}
     * comparisons determine whether {@code CCUP-CHANGE-DETECTED} should be set.</p>
     *
     * @param card    the current card entity from the database
     * @param request the update request with potentially new values
     * @return {@code true} if at least one field differs, {@code false} if all match
     */
    private boolean detectChanges(Card card, CardUpdateRequest request) {
        // Compare embossed name (case-insensitive per COBOL FUNCTION UPPER-CASE)
        String oldName = normalizeForComparison(card.getEmbossedName());
        String newName = normalizeForComparison(request.embossedName());
        if (!oldName.equals(newName)) {
            return true;
        }

        // Compare active status (case-insensitive)
        String oldStatus = normalizeForComparison(card.getActiveStatus());
        String newStatus = normalizeForComparison(request.activeStatus());
        if (!oldStatus.equals(newStatus)) {
            return true;
        }

        // Compare expiration date components
        // Card entity stores date in "YYYY-MM-DD" format (10 characters)
        String oldExpDate = card.getExpirationDate() != null
                ? card.getExpirationDate() : "";
        String oldExpYear = oldExpDate.length() >= 4
                ? oldExpDate.substring(0, 4) : "";
        String oldExpMon = oldExpDate.length() >= 7
                ? oldExpDate.substring(5, 7) : "";
        String oldExpDay = oldExpDate.length() >= 10
                ? oldExpDate.substring(8, 10) : "";

        String newExpYear = padLeft(request.expiryYear(), 4, '0');
        String newExpMon = padLeft(request.expiryMonth(), 2, '0');
        String newExpDay = padLeft(request.expiryDay(), 2, '0');

        if (!oldExpYear.equals(newExpYear)) {
            return true;
        }
        if (!oldExpMon.equals(newExpMon)) {
            return true;
        }
        return !oldExpDay.equals(newExpDay);
    }

    /**
     * Applies the update request field values to the card entity.
     *
     * <p>Maps the 9200-WRITE-PROCESSING "Prepare the update" section (lines 1461–1475)
     * where COBOL {@code MOVE} statements copy new field values into
     * {@code CARD-UPDATE-RECORD} and {@code STRING} assembles the expiration date.</p>
     *
     * @param card    the card entity to update
     * @param request the update request with new field values
     */
    private void applyUpdates(Card card, CardUpdateRequest request) {
        // MOVE CCUP-NEW-CRDNAME TO CARD-UPDATE-EMBOSSED-NAME
        card.setEmbossedName(request.embossedName());

        // MOVE CCUP-NEW-CRDSTCD TO CARD-UPDATE-ACTIVE-STATUS
        card.setActiveStatus(request.activeStatus());

        // STRING CCUP-NEW-EXPYEAR '-' CCUP-NEW-EXPMON '-' CCUP-NEW-EXPDAY
        //        DELIMITED BY SIZE INTO CARD-UPDATE-EXPIRAION-DATE
        String expYear = padLeft(request.expiryYear(), 4, '0');
        String expMonth = padLeft(request.expiryMonth(), 2, '0');
        String expDay = padLeft(request.expiryDay(), 2, '0');
        String expirationDate = expYear + "-" + expMonth + "-" + expDay;
        card.setExpirationDate(expirationDate);
    }

    /**
     * Normalises a string value for case-insensitive comparison.
     * Handles {@code null} safely by returning an empty string.
     * Mirrors the COBOL {@code FUNCTION UPPER-CASE} and {@code TRIM} pattern.
     *
     * @param value the string to normalise
     * @return upper-cased trimmed value, or empty string if {@code null}
     */
    private String normalizeForComparison(String value) {
        if (value == null) {
            return "";
        }
        return value.toUpperCase().trim();
    }

    /**
     * Left-pads a string value to the specified length with the given character.
     * Used to normalise month/day/year values for comparison and storage.
     *
     * @param value   the string to pad (may be {@code null})
     * @param length  the target length
     * @param padChar the character to pad with
     * @return the padded string, truncated to {@code length} if longer
     */
    private String padLeft(String value, int length, char padChar) {
        if (value == null) {
            return String.valueOf(padChar).repeat(length);
        }
        String trimmed = value.trim();
        if (trimmed.length() >= length) {
            return trimmed.substring(0, length);
        }
        return String.valueOf(padChar).repeat(length - trimmed.length()) + trimmed;
    }

    /**
     * Masks a card number for logging purposes (PII protection).
     * Shows only the last 4 digits.
     *
     * @param cardNum the full card number (may be {@code null})
     * @return masked representation showing the last 4 digits only
     */
    private String maskCardNum(String cardNum) {
        if (cardNum == null || cardNum.length() <= 4) {
            return "****";
        }
        return cardNum.substring(cardNum.length() - 4);
    }
}
