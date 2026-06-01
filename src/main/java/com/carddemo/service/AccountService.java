package com.carddemo.service;

import com.carddemo.dto.account.AccountDto;
import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.mapper.AccountMapper;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.util.BigDecimalUtil;
import com.carddemo.util.DateConversionUtil;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account business service replacing the two CICS online programs
 * {@code app/cbl/COACTVWC.cbl} (account view, TRANID {@code 'CAVW'}) and
 * {@code app/cbl/COACTUPC.cbl} (account update, TRANID {@code 'CAUP'}).
 *
 * <p>Backs the {@code GET /api/accounts/{acctId}} (view) and
 * {@code PUT /api/accounts/{acctId}} (update) REST endpoints exposed by
 * {@code com.carddemo.controller.AccountController}. The COBOL record layout is the
 * 300-byte {@code ACCOUNT-RECORD} from {@code app/cpy/CVACT01Y.cpy}; account existence
 * is verified through the 50-byte {@code CARD-XREF-RECORD} of {@code app/cpy/CVACT03Y.cpy}.</p>
 *
 * <h2>View flow (COACTVWC)</h2>
 * <p>{@link #getAccount(Long)} reproduces the COACTVWC two-step lookup: it first verifies
 * the account exists in the card cross-reference ({@code CXACAIX} alternate-index path,
 * here {@link CardXrefRepository#findByAccountId(Long)}), then performs the keyed
 * {@code ACCTDAT} read ({@link AccountRepository#findById(Object)}). The exact COACTVWC
 * diagnostic messages are preserved verbatim
 * ({@code app/cbl/COACTVWC.cbl} L122/L126/L130/L132); the customer-master read is handled
 * separately by {@code CustomerService}.</p>
 *
 * <h2>Update flow (COACTUPC)</h2>
 * <p>{@link #updateAccount(Long, AccountDto)} reproduces the COACTUPC
 * {@code WS-DATACHANGED-FLAG} semantics: each supplied field is compared against the
 * persisted value and applied only when it differs, tracked by a {@code modified} flag. If
 * no field changed, the exact COACTUPC message
 * {@code "No change detected with respect to values fetched."}
 * ({@code app/cbl/COACTUPC.cbl} L492) is raised. The active-status field is restricted to
 * {@code Y}/{@code N} ({@code app/cbl/COACTUPC.cbl} L504).</p>
 *
 * <h2>Preservation rules satisfied</h2>
 * <ul>
 *   <li><b>PR-14</b> &mdash; the COBOL field {@code ACCT-EXPIRAION-DATE} [sic] is the
 *       correctly spelled {@code expirationDate} in Java; no typo propagates.</li>
 *   <li><b>PR-16</b> &mdash; all five monetary fields are {@link BigDecimal}; change
 *       detection uses {@link BigDecimalUtil#isEqual(BigDecimal, BigDecimal)}
 *       ({@code compareTo}, never {@code equals}, so {@code 100.0} and {@code 100.00}
 *       are treated as unchanged), and every stored value is normalized to scale 2 with
 *       {@link java.math.RoundingMode#HALF_UP} via
 *       {@link BigDecimalUtil#ensureScaleTwo(BigDecimal)}. {@code float}/{@code double} are
 *       never used for money.</li>
 *   <li><b>PR-22</b> &mdash; the {@link Account} entity carries {@code @Version}; a
 *       concurrent modification surfaces as {@link ObjectOptimisticLockingFailureException},
 *       rethrown here with the exact COACTUPC message
 *       {@code "Record changed by some one else. Please review"}
 *       ({@code app/cbl/COACTUPC.cbl} L522) and mapped to HTTP 409 by
 *       {@code GlobalExceptionHandler}. {@link AccountRepository#saveAndFlush(Object)} is
 *       used so the version check executes synchronously inside the {@code try} block.</li>
 *   <li><b>PR-23</b> &mdash; lock ordering {@code CUSTOMER -> ACCOUNT -> CARD ->
 *       TRANSACTION}: the writable {@link Account} is the first (and only) locked entity;
 *       the cross-reference read is a non-locking existence lookup.</li>
 *   <li><b>PR-24</b> &mdash; {@link #getAccount(Long)} runs
 *       {@code @Transactional(readOnly = true)}; {@link #updateAccount(Long, AccountDto)}
 *       runs in a read-write {@code @Transactional} scope bracketing the read-then-rewrite
 *       as a single unit of work (the CICS {@code SYNCPOINT} equivalent).</li>
 *   <li><b>PR-28 / PR-29</b> &mdash; Spring stack only (no {@code javax.*}); dependencies
 *       are injected by constructor over {@code final} fields via Lombok
 *       {@link RequiredArgsConstructor} (no field injection).</li>
 * </ul>
 *
 * <h2>Field-level validation</h2>
 * <p>COACTUPC performs intensive field-by-field editing. The checks relevant to the
 * account record are performed inline here (active-status {@code Y}/{@code N}; date
 * external-shape and validity via {@link DateConversionUtil}). The committed
 * {@code com.carddemo.validation.AccountValidator} validates a {@code DailyTransaction}
 * against an account (CBTRN02C credit-limit/expiration codes 102/103) and is therefore not
 * applicable to the account view/update flow, which carries no transaction; hence the
 * orchestration validation is performed inline as permitted by the file specification.</p>
 *
 * @see com.carddemo.controller.AccountController
 * @see com.carddemo.mapper.AccountMapper
 * @see com.carddemo.entity.Account
 * @since 1.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    /**
     * Largest valid account id &mdash; eleven {@code 9}s, mirroring the unsigned COBOL
     * {@code ACCT-ID PIC 9(11)} domain ({@code app/cpy/CVACT01Y.cpy}).
     */
    private static final long MAX_ACCT_ID = 99_999_999_999L;

    /**
     * {@link java.time.format.DateTimeFormatter}-compatible pattern string used for the
     * {@link DateConversionUtil#isValidDate(String, String)} validity check. Note this is
     * the lowercase Java pattern {@code yyyy-MM-dd} (NOT the COBOL mask {@code YYYY-MM-DD},
     * in which {@code Y} would denote week-based-year and {@code D} day-of-year).
     */
    private static final String ISO_DATE_FORMAT = "yyyy-MM-dd";

    /**
     * Exact 10-character ISO date external shape ({@code yyyy-MM-dd}). Used as the
     * first-line edit check for the three account date fields, mirroring the COACTUPC
     * component-by-component date editing of {@code PIC X(10)} date fields before the
     * semantic validity check delegated to {@link DateConversionUtil}.
     */
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    // ----- Exact COBOL message literals (preserved verbatim; see class JavaDoc) -----

    /** {@code app/cbl/COACTVWC.cbl} L122 / {@code COACTUPC.cbl} L484. */
    private static final String MSG_ACCT_NOT_PROVIDED = "Account number not provided";

    /** {@code app/cbl/COACTVWC.cbl} L126/L128 / {@code COACTUPC.cbl} L494/L496. */
    private static final String MSG_ACCT_INVALID = "Account number must be a non zero 11 digit number";

    /** {@code app/cbl/COACTVWC.cbl} L130. */
    private static final String MSG_NOT_IN_XREF = "Did not find this account in account card xref file";

    /** {@code app/cbl/COACTVWC.cbl} L132. */
    private static final String MSG_NOT_IN_MASTER = "Did not find this account in account master file";

    /** {@code app/cbl/COACTUPC.cbl} L504. */
    private static final String MSG_STATUS_YN = "Account Active Status must be Y or N";

    /** {@code app/cbl/COACTUPC.cbl} L492 (note the trailing period). */
    private static final String MSG_NO_CHANGE = "No change detected with respect to values fetched.";

    /** {@code app/cbl/COACTUPC.cbl} L522. */
    private static final String MSG_OPTIMISTIC_LOCK = "Record changed by some one else. Please review";

    /**
     * Spring Data JPA repository for the account master ({@code ACCTDAT}). Injected by
     * constructor (PR-29). Supplies the keyed {@link AccountRepository#findById(Object)}
     * read (COBOL {@code EXEC CICS READ DATASET('ACCTDAT')}) and the
     * {@link AccountRepository#saveAndFlush(Object)} rewrite (COBOL {@code REWRITE} with
     * {@code @Version} optimistic locking).
     */
    private final AccountRepository accountRepository;

    /**
     * Spring Data JPA repository for the card cross-reference ({@code CXACAIX} path).
     * Injected by constructor (PR-29). Supplies the
     * {@link CardXrefRepository#findByAccountId(Long)} alternate-index lookup used by the
     * COACTVWC account-existence verification step in {@link #getAccount(Long)}.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Entity&harr;DTO mapper. Injected by constructor (PR-29).
     * {@link AccountMapper#toDto(Account)} converts the managed {@link Account} to the
     * outbound {@link AccountDto}, normalizing every monetary field to scale 2 and
     * rendering {@link LocalDate} fields to ISO {@code yyyy-MM-dd} strings.
     */
    private final AccountMapper accountMapper;

    /**
     * Retrieves a single account by id for {@code GET /api/accounts/{acctId}}, reproducing
     * the COACTVWC account-view flow.
     *
     * <p>Validation and lookup order (faithful to {@code app/cbl/COACTVWC.cbl}):</p>
     * <ol>
     *   <li>A {@code null} id raises {@link IllegalArgumentException} with the exact message
     *       {@code "Account number not provided"} (COACTVWC L122).</li>
     *   <li>A zero, negative, or greater-than-11-digit id raises
     *       {@link IllegalArgumentException} with the exact message
     *       {@code "Account number must be a non zero 11 digit number"} (COACTVWC L126).</li>
     *   <li>If no cross-reference row links the id (the {@code CXACAIX} step is empty),
     *       {@link AccountNotFoundException} carries the exact message
     *       {@code "Did not find this account in account card xref file"} (COACTVWC L130).</li>
     *   <li>If the {@code ACCTDAT} keyed read finds no master record,
     *       {@link AccountNotFoundException} carries the exact message
     *       {@code "Did not find this account in account master file"} (COACTVWC L132,
     *       COBOL validation code 101).</li>
     * </ol>
     *
     * <p>Runs read-only and transactional (PR-24); acquires no write locks (PR-23). The
     * verbatim messages are surfaced via {@link AccountNotFoundException#withMessage(String)}
     * (the {@code String} constructor would otherwise prepend a diagnostic prefix) and
     * copied unchanged onto the REST error payload by {@code GlobalExceptionHandler}
     * (HTTP 404 for not-found, HTTP 400 for the argument checks).</p>
     *
     * @param acctId the 11-digit account id (COBOL {@code ACCT-ID PIC 9(11)})
     * @return the account as an {@link AccountDto} (monetary fields scale 2, dates ISO
     *         {@code yyyy-MM-dd})
     * @throws IllegalArgumentException  if {@code acctId} is null, zero, negative, or
     *                                   exceeds 11 digits
     * @throws AccountNotFoundException  if the account is absent from the cross-reference or
     *                                   the master file
     */
    @Transactional(readOnly = true)
    public AccountDto getAccount(Long acctId) {
        log.debug("Looking up account {}", acctId);

        // COACTVWC account-id edit (1000-SEND-MAP / 1200-EDIT-MAP-INPUTS):
        // distinguish "not provided" (L122) from "non zero 11 digit number" (L126).
        if (acctId == null) {
            throw new IllegalArgumentException(MSG_ACCT_NOT_PROVIDED);
        }
        if (acctId <= 0L || acctId > MAX_ACCT_ID) {
            throw new IllegalArgumentException(MSG_ACCT_INVALID);
        }

        // Step 1 (COACTVWC 9200-GETCARDXREF-BYACCT): verify the account exists in the card
        // cross-reference via the CXACAIX alternate-index path before the master read.
        List<CardXref> xrefs = cardXrefRepository.findByAccountId(acctId);
        if (xrefs.isEmpty()) {
            throw AccountNotFoundException.withMessage(MSG_NOT_IN_XREF);
        }

        // Step 2 (COACTVWC 9300-GETACCTDATA-BYACCT): keyed ACCTDAT read; NOTFND -> code 101.
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> AccountNotFoundException.withMessage(MSG_NOT_IN_MASTER));

        return accountMapper.toDto(account);
    }

    /**
     * Updates an existing account for {@code PUT /api/accounts/{acctId}}, reproducing the
     * COACTUPC update flow including its {@code WS-DATACHANGED-FLAG} change-detection and
     * optimistic-locking (race-condition) semantics.
     *
     * <p>The account is loaded first (PR-23 lock ordering; the read-then-rewrite is bracketed
     * by the surrounding {@code @Transactional} scope, the CICS {@code SYNCPOINT} equivalent).
     * Each non-null field on {@code dto} is compared against the persisted value and applied
     * only when it differs, exactly as COACTUPC sets {@code WS-DATACHANGED-FLAG} to {@code 1}
     * on the first real change:</p>
     * <ul>
     *   <li><b>Active status</b> &mdash; must be {@code Y} or {@code N} (COACTUPC L504),
     *       otherwise {@link IllegalArgumentException}
     *       {@code "Account Active Status must be Y or N"}.</li>
     *   <li><b>Monetary fields</b> ({@code currBal}, {@code creditLimit},
     *       {@code cashCreditLimit}, {@code currCycCredit}, {@code currCycDebit}) &mdash;
     *       compared with {@link BigDecimalUtil#isEqual(BigDecimal, BigDecimal)}
     *       ({@code compareTo}, PR-16) and stored normalized to scale 2 via
     *       {@link BigDecimalUtil#ensureScaleTwo(BigDecimal)}.</li>
     *   <li><b>Date fields</b> ({@code openDate}, {@code expirationDate}, {@code reissueDate})
     *       &mdash; the inbound ISO {@code yyyy-MM-dd} string is shape-checked and validated
     *       (PR-14 corrects the COBOL {@code ACCT-EXPIRAION-DATE} typo) then parsed to
     *       {@link LocalDate} for comparison and storage.</li>
     *   <li><b>ZIP and group id</b> &mdash; plain string comparison.</li>
     * </ul>
     *
     * <p>If no field changed, {@link IllegalStateException}
     * {@code "No change detected with respect to values fetched."} is raised (COACTUPC L492,
     * mapped to HTTP 422). Otherwise the managed entity is persisted with
     * {@link AccountRepository#saveAndFlush(Object)}; a {@code @Version} conflict
     * (concurrent modification) is rethrown as {@link ObjectOptimisticLockingFailureException}
     * carrying the exact message {@code "Record changed by some one else. Please review"}
     * (COACTUPC L522, mapped to HTTP 409 by {@code GlobalExceptionHandler}).</p>
     *
     * <p>The {@code acctId} path variable is authoritative; any {@code acctId} on the
     * {@code dto} body is ignored (the primary key is immutable).</p>
     *
     * @param acctId the id of the account to update (COBOL {@code ACCT-ID PIC 9(11)})
     * @param dto    the desired field values; {@code null} fields are left unchanged
     * @return the updated account as an {@link AccountDto}
     * @throws AccountNotFoundException             if no account exists for {@code acctId}
     *                                              (COACTUPC master read NOTFND)
     * @throws IllegalArgumentException             if active status is not {@code Y}/{@code N}
     *                                              or a supplied date is malformed
     * @throws IllegalStateException                if no field differs from the persisted
     *                                              record
     * @throws ObjectOptimisticLockingFailureException if the record was changed concurrently
     */
    @Transactional
    public AccountDto updateAccount(Long acctId, AccountDto dto) {
        log.info("Updating account {}", acctId);

        // PR-23: acquire the writable ACCOUNT entity first (read-for-update equivalent).
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> AccountNotFoundException.withMessage(MSG_NOT_IN_MASTER));

        boolean modified = false;

        // --- Active status: Y or N only (COACTUPC L504) ---
        if (dto.getActiveStatus() != null) {
            String status = dto.getActiveStatus();
            if (!"Y".equals(status) && !"N".equals(status)) {
                throw new IllegalArgumentException(MSG_STATUS_YN);
            }
            if (!status.equals(account.getActiveStatus())) {
                account.setActiveStatus(status);
                modified = true;
            }
        }

        // --- Monetary fields (PR-16): compareTo via BigDecimalUtil.isEqual; store scale 2 ---
        if (dto.getCurrBal() != null) {
            BigDecimal normalized = BigDecimalUtil.ensureScaleTwo(dto.getCurrBal());
            if (!BigDecimalUtil.isEqual(normalized, account.getCurrBal())) {
                account.setCurrBal(normalized);
                modified = true;
            }
        }
        if (dto.getCreditLimit() != null) {
            BigDecimal normalized = BigDecimalUtil.ensureScaleTwo(dto.getCreditLimit());
            if (!BigDecimalUtil.isEqual(normalized, account.getCreditLimit())) {
                account.setCreditLimit(normalized);
                modified = true;
            }
        }
        if (dto.getCashCreditLimit() != null) {
            BigDecimal normalized = BigDecimalUtil.ensureScaleTwo(dto.getCashCreditLimit());
            if (!BigDecimalUtil.isEqual(normalized, account.getCashCreditLimit())) {
                account.setCashCreditLimit(normalized);
                modified = true;
            }
        }
        if (dto.getCurrCycCredit() != null) {
            BigDecimal normalized = BigDecimalUtil.ensureScaleTwo(dto.getCurrCycCredit());
            if (!BigDecimalUtil.isEqual(normalized, account.getCurrCycCredit())) {
                account.setCurrCycCredit(normalized);
                modified = true;
            }
        }
        if (dto.getCurrCycDebit() != null) {
            BigDecimal normalized = BigDecimalUtil.ensureScaleTwo(dto.getCurrCycDebit());
            if (!BigDecimalUtil.isEqual(normalized, account.getCurrCycDebit())) {
                account.setCurrCycDebit(normalized);
                modified = true;
            }
        }

        // --- Date fields (ISO yyyy-MM-dd; PR-14 expirationDate naming) ---
        if (dto.getOpenDate() != null) {
            LocalDate parsed = parseAccountDate(dto.getOpenDate(), "Open date");
            if (!parsed.equals(account.getOpenDate())) {
                account.setOpenDate(parsed);
                modified = true;
            }
        }
        if (dto.getExpirationDate() != null) {
            LocalDate parsed = parseAccountDate(dto.getExpirationDate(), "Expiration date");
            if (!parsed.equals(account.getExpirationDate())) {
                account.setExpirationDate(parsed);
                modified = true;
            }
        }
        if (dto.getReissueDate() != null) {
            LocalDate parsed = parseAccountDate(dto.getReissueDate(), "Reissue date");
            if (!parsed.equals(account.getReissueDate())) {
                account.setReissueDate(parsed);
                modified = true;
            }
        }

        // --- Address ZIP and disclosure group id ---
        if (dto.getAddrZip() != null && !dto.getAddrZip().equals(account.getAddrZip())) {
            account.setAddrZip(dto.getAddrZip());
            modified = true;
        }
        if (dto.getGroupId() != null && !dto.getGroupId().equals(account.getGroupId())) {
            account.setGroupId(dto.getGroupId());
            modified = true;
        }

        // COACTUPC WS-DATACHANGED-FLAG = 0 -> "No change detected..." (L492).
        if (!modified) {
            throw new IllegalStateException(MSG_NO_CHANGE);
        }

        try {
            // saveAndFlush forces the @Version check to run synchronously here (rather than
            // deferring to transaction commit), so a concurrent-modification conflict is
            // caught inside this block (PR-22; COACTUPC REWRITE race-condition check).
            Account saved = accountRepository.saveAndFlush(account);
            log.info("Account {} updated successfully", acctId);
            return accountMapper.toDto(saved);
        } catch (ObjectOptimisticLockingFailureException ex) {
            // Preserve the exact COACTUPC message; GlobalExceptionHandler maps to HTTP 409.
            throw new ObjectOptimisticLockingFailureException(MSG_OPTIMISTIC_LOCK, ex);
        }
    }

    /**
     * Validates and parses an inbound ISO {@code yyyy-MM-dd} account date, mirroring the
     * COACTUPC date editing of a {@code PIC X(10)} field: the exact 10-character external
     * shape is enforced by {@link #ISO_DATE_PATTERN}, then semantic validity is confirmed via
     * {@link DateConversionUtil#isValidDate(String, String)} (the {@code CSUTLDTC} equivalent,
     * which rejects impossible dates such as {@code 2024-02-30}). The value is then parsed to
     * a {@link LocalDate} using the shared {@link DateConversionUtil#ISO_DATE_FORMATTER}.
     *
     * @param value      the inbound date string (guaranteed non-null by the caller)
     * @param fieldLabel a human-readable field label used in the failure message
     * @return the parsed {@link LocalDate}
     * @throws IllegalArgumentException if the value is not a valid {@code yyyy-MM-dd} date
     *                                  (mapped to HTTP 400 by {@code GlobalExceptionHandler})
     */
    private LocalDate parseAccountDate(String value, String fieldLabel) {
        if (value == null
                || !ISO_DATE_PATTERN.matcher(value).matches()
                || !DateConversionUtil.isValidDate(value, ISO_DATE_FORMAT)) {
            throw new IllegalArgumentException(
                    fieldLabel + " must be a valid date in " + ISO_DATE_FORMAT + " format");
        }
        return LocalDate.parse(value, DateConversionUtil.ISO_DATE_FORMATTER);
    }
}
