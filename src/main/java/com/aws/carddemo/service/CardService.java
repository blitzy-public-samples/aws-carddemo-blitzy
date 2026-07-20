/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
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
package com.aws.carddemo.service;

import java.util.List;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aws.carddemo.domain.Card;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.CardRepository;

/**
 * Card management service &mdash; the Java re-platform of the three CardDemo
 * COBOL <em>card</em> online programs, reproduced without any feature expansion:
 * <ul>
 *   <li><strong>Card List</strong> &rarr; {@code legacy/cbl/COCRDLIC.cbl}
 *       (CICS transaction {@code CCLI}). Modeled by
 *       {@link #listCards(Long, String, Pageable)}.</li>
 *   <li><strong>Card View / Select</strong> &rarr; {@code legacy/cbl/COCRDSLC.cbl}
 *       (CICS transaction {@code CCDL}). Modeled by
 *       {@link #viewCard(Long, String)}.</li>
 *   <li><strong>Card Update</strong> &rarr; {@code legacy/cbl/COCRDUPC.cbl}
 *       (CICS transaction {@code CCUP}). Modeled by
 *       {@link #updateCard(String, String, String, String, String)}.</li>
 * </ul>
 * Per the migration plan (AAP&nbsp;&sect;0.5.3), each COBOL paragraph maps to a
 * cohesive service method that <em>preserves the original control flow and
 * evaluation order</em>. The caller-visible message strings below are
 * behavioral-parity contracts copied verbatim from the COBOL working storage and
 * must not be paraphrased.
 *
 * <h2>Pseudo-conversational translation</h2>
 * The legacy programs are pseudo-conversational 3270/CICS transactions: each
 * validates the operator's input, reads {@code CARDDATA} (the VSAM KSDS now
 * re-platformed as the PostgreSQL {@code card} table), and either re-displays the
 * screen with a message or navigates on. There is no terminal here, so:
 * <ul>
 *   <li><strong>List</strong> returns a {@link Page} of {@link Card} entities in
 *       card-number order (the KSDS primary-key browse order), applying the same
 *       optional account/card filters the COBOL {@code 9500-FILTER-RECORDS}
 *       paragraph applied.</li>
 *   <li><strong>View</strong> returns the resolved {@link Card}; an input-edit
 *       failure surfaces as an {@link IllegalArgumentException} carrying the exact
 *       COBOL screen message, and a not-found read surfaces as a
 *       {@link RecordNotFoundException} (COBOL/VSAM {@code FILE STATUS '23'} /
 *       CICS {@code NOTFND}).</li>
 *   <li><strong>Update</strong> returns a {@link CardUpdateResult} that models the
 *       COBOL change-decision states ({@code CCUP-CHANGES-NOT-OK},
 *       {@code NO-CHANGES-DETECTED}, and the applied change), so the field-edit
 *       messages and the "no change detected &rarr; do not write" short-circuit
 *       are preserved for the caller.</li>
 * </ul>
 *
 * <h2>Read-Update-Rewrite concurrency</h2>
 * The COBOL update performs {@code READ ... UPDATE} then {@code REWRITE} and a
 * manual {@code 9300-CHECK-CHANGE-IN-REC} guard against a concurrent change. That
 * integrity is reproduced by JPA optimistic locking: the {@link Card} entity
 * carries a {@code @Version} column, so {@code save()} within this service's
 * {@link Transactional} boundary throws
 * {@link org.springframework.dao.OptimisticLockingFailureException} when another
 * transaction changed the row first (surfaced as HTTP&nbsp;409 by the web layer).
 *
 * <h2>Sensitive data</h2>
 * The card verification value (CVV) is sensitive. It is <em>never</em> logged,
 * <em>never</em> placed in a message or result, and <em>never</em> modified by
 * {@link #updateCard(String, String, String, String, String)} &mdash; the existing
 * value is carried through unchanged, exactly as the COBOL update did (the update
 * screen never accepted a new CVV).
 *
 * <h2>Design constraints</h2>
 * All data access goes through the injected {@link CardRepository} (constructor
 * injection, no field injection); the field-edit rules are reproduced inline so
 * this service is self-contained and its COBOL parity is directly reviewable. No
 * DTOs or mappers are referenced; the service returns entities and small nested
 * result types only. Detailed rationale for the mapping decisions lives in
 * {@code docs/decision-log.md}.
 */
@Service
public class CardService {

    // ------------------------------------------------------------------
    // Caller-visible messages (behavioral-parity contracts, verbatim COBOL)
    // ------------------------------------------------------------------

    /**
     * Account-filter edit message for a supplied-but-non-numeric account.
     * Exact COBOL literal from {@code legacy/cbl/COCRDSLC.cbl} (2210-EDIT-ACCOUNT),
     * {@code legacy/cbl/COCRDLIC.cbl} (2210-EDIT-ACCOUNT), and
     * {@code legacy/cbl/COCRDUPC.cbl} (1210-EDIT-ACCOUNT):
     * {@code 'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER'}.
     */
    public static final String MSG_ACCOUNT_FILTER_NOT_NUMERIC =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /**
     * Card-filter edit message for a supplied-but-non-numeric card number.
     * Exact COBOL literal from {@code legacy/cbl/COCRDSLC.cbl} (2220-EDIT-CARD),
     * {@code legacy/cbl/COCRDLIC.cbl} (2220-EDIT-CARD), and
     * {@code legacy/cbl/COCRDUPC.cbl} (1220-EDIT-CARD):
     * {@code 'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER'}.
     */
    public static final String MSG_CARD_FILTER_NOT_NUMERIC =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /**
     * View edit message when neither an account nor a card filter was supplied.
     * Exact COBOL literal {@code NO-SEARCH-CRITERIA-RECEIVED} in
     * {@code legacy/cbl/COCRDSLC.cbl}: {@code 'No input received'}.
     */
    public static final String MSG_NO_INPUT_RECEIVED = "No input received";

    /**
     * View not-found message for a keyed read by card number that finds no row.
     * Exact COBOL literal {@code DID-NOT-FIND-ACCTCARD-COMBO} in
     * {@code legacy/cbl/COCRDSLC.cbl} (9100-GETCARD-BYACCTCARD, {@code NOTFND}):
     * {@code 'Did not find cards for this search condition'}.
     */
    public static final String MSG_DID_NOT_FIND_ACCTCARD_COMBO =
            "Did not find cards for this search condition";

    /**
     * View not-found message for an account that owns no card (alternate-index
     * read). Exact COBOL literal {@code DID-NOT-FIND-ACCT-IN-CARDXREF} in
     * {@code legacy/cbl/COCRDSLC.cbl} (9150-GETCARD-BYACCT, {@code NOTFND}):
     * {@code 'Did not find this account in cards database'}.
     */
    public static final String MSG_DID_NOT_FIND_ACCT_IN_CARDXREF =
            "Did not find this account in cards database";

    /**
     * Update edit message when the embossed name is not supplied. Exact COBOL
     * literal {@code WS-PROMPT-FOR-NAME} in {@code legacy/cbl/COCRDUPC.cbl}
     * (1230-EDIT-NAME): {@code 'Card name not provided'}.
     */
    public static final String MSG_CARD_NAME_NOT_PROVIDED = "Card name not provided";

    /**
     * Update edit message when the embossed name contains a non-alphabetic,
     * non-space character. Exact COBOL literal {@code WS-NAME-MUST-BE-ALPHA} in
     * {@code legacy/cbl/COCRDUPC.cbl} (1230-EDIT-NAME):
     * {@code 'Card name can only contain alphabets and spaces'}.
     */
    public static final String MSG_CARD_NAME_MUST_BE_ALPHA =
            "Card name can only contain alphabets and spaces";

    /**
     * Update edit message when the active-status flag is missing or not Y/N.
     * Exact COBOL literal {@code CARD-STATUS-MUST-BE-YES-NO} in
     * {@code legacy/cbl/COCRDUPC.cbl} (1240-EDIT-CARDSTATUS):
     * {@code 'Card Active Status must be Y or N'}.
     */
    public static final String MSG_CARD_STATUS_MUST_BE_YES_NO =
            "Card Active Status must be Y or N";

    /**
     * Update edit message when the expiry month is missing or outside 1..12.
     * Exact COBOL literal {@code CARD-EXPIRY-MONTH-NOT-VALID} in
     * {@code legacy/cbl/COCRDUPC.cbl} (1250-EDIT-EXPIRY-MON):
     * {@code 'Card expiry month must be between 1 and 12'}.
     */
    public static final String MSG_CARD_EXPIRY_MONTH_NOT_VALID =
            "Card expiry month must be between 1 and 12";

    /**
     * Update edit message when the expiry year is missing or invalid. Exact COBOL
     * literal {@code CARD-EXPIRY-YEAR-NOT-VALID} in
     * {@code legacy/cbl/COCRDUPC.cbl} (1260-EDIT-EXPIRY-YEAR):
     * {@code 'Invalid card expiry year'}.
     */
    public static final String MSG_CARD_EXPIRY_YEAR_NOT_VALID = "Invalid card expiry year";

    /**
     * Update message when the submitted values match the stored values, so no
     * write is performed. Exact COBOL literal {@code NO-CHANGES-DETECTED} in
     * {@code legacy/cbl/COCRDUPC.cbl}:
     * {@code 'No change detected with respect to values fetched.'}.
     */
    public static final String MSG_NO_CHANGE_DETECTED =
            "No change detected with respect to values fetched.";

    /**
     * Concurrency-conflict message for the CCUP update flow (F-P4-D). The {@code card} row carries a
     * JPA {@code @Version} column; the online update carries the version observed when the change was
     * previewed and rejects a confirm whose observed version is absent or no longer matches the
     * persisted row (another request committed a change to the same card after the preview). This
     * reproduces, for the card aggregate, the intent of the COACTUPC
     * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} guard and matches the wording used by
     * {@code AccountService}; the web layer maps the resulting
     * {@link org.springframework.dao.OptimisticLockingFailureException} to HTTP {@code 409}.
     */
    public static final String MSG_DATA_CHANGED =
            "Record changed by some one else. Please review";

    // ------------------------------------------------------------------
    // Field-format constants (COBOL picture / value ranges)
    // ------------------------------------------------------------------

    /** Largest 11-digit account id, i.e. {@code PIC 9(11)} maximum (99,999,999,999). */
    private static final long ACCOUNT_ID_MAX = 99_999_999_999L;

    /** Card-number field width &mdash; {@code CARD-NUM PIC X(16)} / filter {@code PIC 9(16)}. */
    private static final int CARD_NUMBER_LENGTH = 16;

    /** Lowest valid expiry month ({@code VALID-MONTH VALUES 1 THRU 12}). */
    private static final int MONTH_MIN = 1;

    /** Highest valid expiry month ({@code VALID-MONTH VALUES 1 THRU 12}). */
    private static final int MONTH_MAX = 12;

    /** Maximum digit width of the expiry-month field ({@code CARD-MONTH-CHECK PIC X(2)}). */
    private static final int MONTH_MAX_DIGITS = 2;

    /** Lowest valid expiry year ({@code VALID-YEAR VALUES 1950 THRU 2099}). */
    private static final int YEAR_MIN = 1950;

    /** Highest valid expiry year ({@code VALID-YEAR VALUES 1950 THRU 2099}). */
    private static final int YEAR_MAX = 2099;

    /** Digit width of the expiry-year field ({@code CARD-YEAR-CHECK PIC X(4)}). */
    private static final int YEAR_DIGITS = 4;

    /** Length of the {@code YYYY-MM-DD} expiration date ({@code CARD-EXPIRAION-DATE PIC X(10)}). */
    private static final int EXPIRATION_DATE_LENGTH = 10;

    /** Start index (inclusive) of the day within {@code YYYY-MM-DD} (COBOL {@code (9:2)}). */
    private static final int EXPIRATION_DAY_START = 8;

    /** JPA property used to order card browses by primary key (card number ascending). */
    private static final String SORT_PROPERTY_CARD_NUM = "cardNum";

    /**
     * Data-access gateway to the {@code card} table (the re-platformed
     * {@code CARDDATA.VSAM.KSDS} and its account alternate index
     * {@code CARDDATA.VSAM.AIX}). Sole collaborator; injected via the constructor.
     */
    private final CardRepository cardRepository;

    /**
     * Creates the service with its required repository collaborator.
     *
     * <p>Constructor injection is used (never field injection) so the dependency
     * is explicit and the instance is fully initialized and immutable after
     * construction. The body only assigns the field &mdash; it invokes no
     * overridable method &mdash; so no partially constructed instance can escape.
     *
     * @param cardRepository the {@link Card} repository; must not be {@code null}
     */
    public CardService(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    // ==================================================================
    // Nested result types (validation outcomes; never DTOs)
    // ==================================================================

    /**
     * The three change-decision outcomes of {@link CardService#updateCard}, the
     * Java analog of the COBOL {@code CCUP-CHANGE-ACTION} condition names in
     * {@code legacy/cbl/COCRDUPC.cbl}.
     */
    public enum CardUpdateStatus {

        /**
         * The field edits passed and the change was applied and persisted &mdash;
         * the COBOL "changes okay" / rewritten state.
         */
        CHANGES_OK,

        /**
         * At least one field edit failed, so no write was performed &mdash; the
         * COBOL {@code CCUP-CHANGES-NOT-OK} state. The accompanying message is the
         * first failing field's exact COBOL screen message (first-message latching).
         */
        CHANGES_NOT_OK,

        /**
         * The submitted values matched the stored values, so no write was
         * performed &mdash; the COBOL {@code NO-CHANGES-DETECTED} short-circuit.
         */
        NO_CHANGES_DETECTED
    }

    /**
     * Immutable outcome of {@link CardService#updateCard}. Models every COBOL
     * change-decision result so the caller (a controller re-showing the screen)
     * can reproduce the legacy behavior without any DTO coupling.
     *
     * <p>The {@link #card} is the current entity: the persisted result for
     * {@link CardUpdateStatus#CHANGES_OK}, or the unmodified loaded entity for
     * {@link CardUpdateStatus#CHANGES_NOT_OK} and
     * {@link CardUpdateStatus#NO_CHANGES_DETECTED}. The {@link #message} is the
     * exact COBOL screen message for the non-applied outcomes and the empty
     * string for {@link CardUpdateStatus#CHANGES_OK}. The card's CVV is never
     * echoed in {@link #message}.</p>
     *
     * @param status  the change-decision outcome; never {@code null}
     * @param card    the current card entity; never {@code null}
     * @param message the caller-visible message (empty when the change was applied);
     *                never {@code null}
     */
    public record CardUpdateResult(CardUpdateStatus status, Card card, String message) {
    }

    // ==================================================================
    // Card List (COCRDLIC, transaction CCLI)
    // ==================================================================

    /**
     * Lists cards in card-number order, applying the optional account and card
     * filters exactly as COBOL {@code 9500-FILTER-RECORDS}
     * ({@code legacy/cbl/COCRDLIC.cbl}) did during the {@code 9000-READ-FORWARD} /
     * {@code 9100-READ-BACKWARDS} browse.
     *
     * <p>Filter semantics (reproducing {@code 9500-FILTER-RECORDS} verbatim): a
     * candidate row is included if and only if
     * <em>(the account filter is not valid, or the row's account id equals it)</em>
     * <strong>and</strong>
     * <em>(the card filter is not valid, or the row's card number equals it)</em>.
     * An absent or invalid filter does not constrain the result &mdash; the COBOL
     * screen surfaces any edit message separately; this browse simply does not
     * filter on it.</p>
     *
     * <p>The legacy program browses {@code CARDDATA} by its primary key
     * ({@code STARTBR}/{@code READNEXT} on the card number) and filters each record
     * in memory, so results are always ordered by card number ascending. This
     * method preserves that order and, because the card number is the unique
     * primary key, resolves a valid card filter with a single keyed lookup (the
     * behavioral equivalent of the browse-and-match).</p>
     *
     * @param accountFilter the optional 11-digit account id filter; {@code null},
     *                      zero, or out-of-range means "no account filter"
     * @param cardFilter    the optional 16-digit card-number filter; {@code null},
     *                      blank, zero, or non-16-digit means "no card filter"
     * @param pageable      the pagination request; the sort is forced to card
     *                      number ascending to match the KSDS key order
     * @return a page of matching {@link Card} entities in card-number order
     */
    @Transactional(readOnly = true)
    public Page<Card> listCards(Long accountFilter, String cardFilter, Pageable pageable) {
        boolean accountFilterValid = isAccountFilterValid(accountFilter);
        String card = trimToNull(cardFilter);
        boolean cardFilterValid = isCardFilterValid(card);

        // COBOL 9500-FILTER-RECORDS: a valid card filter targets the unique primary
        // key, so at most one row can match; resolve it with a keyed read and apply
        // the account filter as the second guard clause.
        if (cardFilterValid) {
            Card match = cardRepository.findById(card).orElse(null);
            boolean keep = match != null
                    && (!accountFilterValid || accountFilter.equals(match.getAcctId()));
            List<Card> content = keep ? List.of(match) : List.of();
            return pageOf(content, pageable);
        }

        // Only an account filter (or none) constrains the browse. Both paths are
        // ordered by card number ascending to preserve the VSAM key order.
        Pageable byCardNumber = byCardNumberAscending(pageable);
        if (accountFilterValid) {
            return cardRepository.findByAcctId(accountFilter, byCardNumber);
        }
        return cardRepository.findAll(byCardNumber);
    }

    // ==================================================================
    // Card View / Select (COCRDSLC, transaction CCDL)
    // ==================================================================

    /**
     * Resolves a single card for the view/select screen, reproducing the
     * {@code 2210-EDIT-ACCOUNT} / {@code 2220-EDIT-CARD} edits and the
     * {@code 9000-READ-DATA} reads of {@code legacy/cbl/COCRDSLC.cbl}.
     *
     * <p>Edit order and first-message latching are preserved: the account filter is
     * edited before the card filter, so when both are supplied and invalid the
     * account message wins. A supplied-but-non-numeric account raises
     * {@link #MSG_ACCOUNT_FILTER_NOT_NUMERIC}; a supplied-but-non-numeric card
     * raises {@link #MSG_CARD_FILTER_NOT_NUMERIC}; and when neither filter is
     * supplied the message is {@link #MSG_NO_INPUT_RECEIVED}. These input-edit
     * failures are thrown as {@link IllegalArgumentException} (the COBOL re-showed
     * the screen with the message).</p>
     *
     * <p>Read precedence follows {@code 9000-READ-DATA}: when a card number is
     * supplied it is read by key ({@code 9100-GETCARD-BYACCTCARD}); otherwise the
     * account's first card is read via the account index
     * ({@code 9150-GETCARD-BYACCT}). A read that finds nothing raises
     * {@link RecordNotFoundException} carrying the matching COBOL message
     * ({@link #MSG_DID_NOT_FIND_ACCTCARD_COMBO} for the keyed read,
     * {@link #MSG_DID_NOT_FIND_ACCT_IN_CARDXREF} for the account read).</p>
     *
     * @param accountId  the optional 11-digit account id; may be {@code null} or zero
     * @param cardNumber the optional 16-digit card number; may be {@code null} or blank
     * @return the resolved {@link Card} entity
     * @throws IllegalArgumentException if the supplied filters fail the COBOL edits
     * @throws RecordNotFoundException  if no matching card exists
     */
    @Transactional(readOnly = true)
    public Card viewCard(Long accountId, String cardNumber) {
        String card = trimToNull(cardNumber);
        boolean accountSupplied = accountId != null && accountId != 0L;
        boolean cardSupplied = card != null && !isAllZeros(card);

        // 2210-EDIT-ACCOUNT is edited first, so it latches the message on a conflict.
        if (accountSupplied && !isAccountFilterValid(accountId)) {
            throw new IllegalArgumentException(MSG_ACCOUNT_FILTER_NOT_NUMERIC);
        }
        // 2220-EDIT-CARD.
        if (cardSupplied && !isCardFilterValid(card)) {
            throw new IllegalArgumentException(MSG_CARD_FILTER_NOT_NUMERIC);
        }
        // Cross-field edit: no search criteria supplied (NO-SEARCH-CRITERIA-RECEIVED).
        if (!accountSupplied && !cardSupplied) {
            throw new IllegalArgumentException(MSG_NO_INPUT_RECEIVED);
        }

        // 9000-READ-DATA: the card number takes read precedence over the account.
        if (cardSupplied) {
            return cardRepository.findById(card)
                    .orElseThrow(() -> new RecordNotFoundException(MSG_DID_NOT_FIND_ACCTCARD_COMBO));
        }
        return cardRepository.findByAcctIdOrderByCardNumAsc(accountId).stream()
                .findFirst()
                .orElseThrow(() -> new RecordNotFoundException(MSG_DID_NOT_FIND_ACCT_IN_CARDXREF));
    }

    // ==================================================================
    // Card Update (COCRDUPC, transaction CCUP)
    // ==================================================================

    /**
     * Applies a card update, reproducing the field edits, the change-decision
     * logic, and the read-check-change-rewrite cycle of
     * {@code legacy/cbl/COCRDUPC.cbl}.
     *
     * <p><strong>Load.</strong> The card is read by its number
     * ({@code 9200-WRITE-PROCESSING} {@code READ ... UPDATE}); if it does not exist
     * a {@link RecordNotFoundException} is raised
     * ({@link #MSG_DID_NOT_FIND_ACCTCARD_COMBO}).</p>
     *
     * <p><strong>Edits (exact order, first-message latching).</strong> Reproduces
     * {@code 1200-EDIT-MAP-INPUTS}:
     * <ol>
     *   <li>{@code 1230-EDIT-NAME} &mdash; embossed name: mandatory
     *       ({@link #MSG_CARD_NAME_NOT_PROVIDED}) then alphabetic-and-spaces only
     *       ({@link #MSG_CARD_NAME_MUST_BE_ALPHA});</li>
     *   <li>{@code 1240-EDIT-CARDSTATUS} &mdash; active status must be {@code Y} or
     *       {@code N} ({@link #MSG_CARD_STATUS_MUST_BE_YES_NO});</li>
     *   <li>{@code 1250-EDIT-EXPIRY-MON} &mdash; month 1..12
     *       ({@link #MSG_CARD_EXPIRY_MONTH_NOT_VALID});</li>
     *   <li>{@code 1260-EDIT-EXPIRY-YEAR} &mdash; year 1950..2099
     *       ({@link #MSG_CARD_EXPIRY_YEAR_NOT_VALID}).</li>
     * </ol>
     * The first failing edit's message is returned with
     * {@link CardUpdateStatus#CHANGES_NOT_OK} and no write occurs.</p>
     *
     * <p><strong>No-change short-circuit.</strong> If the submitted embossed name
     * (compared case-insensitively), active status, and composed expiration date
     * match the stored values, {@link CardUpdateStatus#NO_CHANGES_DETECTED} is
     * returned with {@link #MSG_NO_CHANGE_DETECTED} and no write occurs (COBOL
     * {@code NO-CHANGES-DETECTED}).</p>
     *
     * <p><strong>Apply.</strong> Otherwise the embossed name, active status, and
     * expiration date are updated and the entity is saved. The expiration date is
     * composed as {@code YYYY-MM-DD} from the new year and month while
     * <em>preserving the existing day</em> (the COBOL update edits only month and
     * year: {@code STRING CCUP-NEW-EXPYEAR '-' CCUP-NEW-EXPMON '-' CCUP-NEW-EXPDAY}).
     * The CVV is deliberately <em>not</em> modified &mdash; it is carried through
     * unchanged. A concurrent modification of the row causes {@code save()} to throw
     * {@link org.springframework.dao.OptimisticLockingFailureException} (the
     * {@code @Version} guard reproducing {@code 9300-CHECK-CHANGE-IN-REC}).</p>
     *
     * @param cardNumber   the card number identifying the row to update
     * @param embossedName the new embossed name (edited: mandatory, alpha + spaces)
     * @param activeStatus the new active status (edited: {@code Y} or {@code N})
     * @param expiryMonth  the new expiry month (edited: 1..12)
     * @param expiryYear   the new expiry year (edited: 1950..2099)
     * @param expectedVersion the caller's observed {@code @Version} value for the
     *                        row, carried through the preview/confirm flow. A valid
     *                        observed version is <em>mandatory</em> on the
     *                        state-changing confirm: a {@code null} or non-matching
     *                        value causes an immediate
     *                        {@link org.springframework.dao.OptimisticLockingFailureException}
     *                        with {@link #MSG_DATA_CHANGED} before any edit or write
     *                        (F-P4-D &mdash; reproduces the COBOL
     *                        {@code 9300-CHECK-CHANGE-IN-REC} re-read-and-compare so a
     *                        stale form cannot silently overwrite a concurrent update).
     * @return the update outcome; never {@code null}
     * @throws RecordNotFoundException if no card exists for {@code cardNumber}
     * @throws org.springframework.dao.OptimisticLockingFailureException if the
     *                                 observed version is {@code null} or does not
     *                                 match the stored row, or the row was modified
     *                                 concurrently at save time
     */
    @Transactional
    public CardUpdateResult updateCard(String cardNumber,
                                       String embossedName,
                                       String activeStatus,
                                       String expiryMonth,
                                       String expiryYear,
                                       Long expectedVersion) {
        String key = trimToNull(cardNumber);
        Card card = cardRepository.findById(key == null ? "" : key)
                .orElseThrow(() -> new RecordNotFoundException(MSG_DID_NOT_FIND_ACCTCARD_COMBO));

        // Stale-preview guard (F-P4-D): the CCUP change is previewed against a card the operator read
        // earlier; the observed @Version is carried from the preview to this confirm. A valid observed
        // version is MANDATORY -- an absent (null) version means the client never carried the
        // X-CardDemo-Card-Version header (or carried an unparseable value) and cannot prove the
        // confirm matches the previewed card, while a mismatch means another request committed a change
        // to the same card after the preview. In either case the write is rejected as a 409 conflict
        // rather than silently overwriting the newer row. This runs before the field edits so a stale
        // confirm is rejected regardless of the values it carries, reproducing at the card-aggregate
        // level the intent of COACTUPC's DATA-WAS-CHANGED-BEFORE-UPDATE guard.
        if (expectedVersion == null || !expectedVersion.equals(card.getVersion())) {
            throw new OptimisticLockingFailureException(MSG_DATA_CHANGED);
        }

        // Field edits in COBOL order, keeping the first failing message.
        String message = validateEditableFields(embossedName, activeStatus, expiryMonth, expiryYear);
        if (message != null) {
            return new CardUpdateResult(CardUpdateStatus.CHANGES_NOT_OK, card, message);
        }

        // Validated, normalized new values.
        String newName = embossedName.strip();
        String newStatus = activeStatus.strip();
        String newMonth = twoDigitMonth(expiryMonth);
        String newYear = expiryYear.strip();
        String preservedDay = expirationDay(card.getCardExpirationDate());
        String newExpiration = newYear + "-" + newMonth + "-" + preservedDay;

        // COBOL NO-CHANGES-DETECTED short-circuit (name compared case-insensitively).
        boolean nameSame = equalsIgnoreCaseStripped(card.getCardEmbossedName(), newName);
        boolean statusSame = equalsStripped(card.getCardActiveStatus(), newStatus);
        boolean expirationSame = equalsStripped(card.getCardExpirationDate(), newExpiration);
        if (nameSame && statusSame && expirationSame) {
            return new CardUpdateResult(CardUpdateStatus.NO_CHANGES_DETECTED, card, MSG_NO_CHANGE_DETECTED);
        }

        // Apply the validated changes. The CVV is intentionally left untouched.
        card.setCardEmbossedName(newName);
        card.setCardActiveStatus(newStatus);
        card.setCardExpirationDate(newExpiration);
        Card saved = cardRepository.save(card);
        return new CardUpdateResult(CardUpdateStatus.CHANGES_OK, saved, "");
    }

    // ==================================================================
    // Field edits (COCRDUPC 1230/1240/1250/1260) — return the exact COBOL
    // message on failure, or {@code null} when the value passes.
    // ==================================================================

    /**
     * Runs the four Card Update field edits in the exact COBOL evaluation order
     * ({@code 1230-EDIT-NAME} &rarr; {@code 1240-EDIT-CARDSTATUS} &rarr;
     * {@code 1250-EDIT-EXPIRY-MON} &rarr; {@code 1260-EDIT-EXPIRY-YEAR}) and
     * returns the <em>first</em> failing field's exact COBOL screen message, or
     * {@code null} when every field passes. This reproduces the COBOL
     * first-message latching (each subsequent edit runs only while no earlier
     * edit has failed).
     *
     * <p>This is the single validation entry point shared by
     * {@link #updateCard(String, String, String, String, String)} (which runs it
     * before applying the read-check-change-rewrite) and by the Card Update web
     * controller's ENTER (validate-only) path, so the on-screen edits fire on both
     * the validate and the confirm submits &mdash; exactly as the COBOL program
     * re-edited the fields on every {@code ENTER} before allowing the update to be
     * confirmed. Because the outcome depends solely on the submitted values, the
     * method is {@code static} and performs no data access; the CVV is neither read
     * nor referenced here.</p>
     *
     * @param embossedName the submitted embossed name (edited: mandatory, alpha + spaces)
     * @param activeStatus the submitted active status (edited: {@code Y} or {@code N})
     * @param expiryMonth  the submitted expiry month (edited: 1..12)
     * @param expiryYear   the submitted expiry year (edited: 1950..2099)
     * @return the first failing field's exact COBOL message, or {@code null} when
     *         all four fields are valid
     */
    public static String validateEditableFields(String embossedName,
                                                 String activeStatus,
                                                 String expiryMonth,
                                                 String expiryYear) {
        String message = editName(embossedName);
        if (message == null) {
            message = editCardStatus(activeStatus);
        }
        if (message == null) {
            message = editExpiryMonth(expiryMonth);
        }
        if (message == null) {
            message = editExpiryYear(expiryYear);
        }
        return message;
    }

    /**
     * {@code 1230-EDIT-NAME}: the embossed name is mandatory and may contain only
     * alphabetic characters and spaces (COBOL {@code INSPECT ... CONVERTING} the
     * alphabet to spaces and asserting an empty remainder).
     *
     * @param value the submitted embossed name
     * @return {@link #MSG_CARD_NAME_NOT_PROVIDED} when blank,
     *         {@link #MSG_CARD_NAME_MUST_BE_ALPHA} when it contains non-alphabetic,
     *         non-space characters, or {@code null} when valid
     */
    private static String editName(String value) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isEmpty() || isAllZeros(stripped)) {
            return MSG_CARD_NAME_NOT_PROVIDED;
        }
        if (!isAlphaSpaces(stripped)) {
            return MSG_CARD_NAME_MUST_BE_ALPHA;
        }
        return null;
    }

    /**
     * {@code 1240-EDIT-CARDSTATUS}: the active status is mandatory and must be
     * exactly {@code Y} or {@code N} (COBOL {@code FLG-YES-NO-VALID}).
     *
     * @param value the submitted active status
     * @return {@link #MSG_CARD_STATUS_MUST_BE_YES_NO} when blank or not {@code Y}/{@code N},
     *         or {@code null} when valid
     */
    private static String editCardStatus(String value) {
        String stripped = value == null ? "" : value.strip();
        if (!"Y".equals(stripped) && !"N".equals(stripped)) {
            return MSG_CARD_STATUS_MUST_BE_YES_NO;
        }
        return null;
    }

    /**
     * {@code 1250-EDIT-EXPIRY-MON}: the expiry month is mandatory, numeric, and in
     * the range 1..12 (COBOL {@code VALID-MONTH}, a {@code PIC X(2)} field).
     *
     * @param value the submitted expiry month
     * @return {@link #MSG_CARD_EXPIRY_MONTH_NOT_VALID} when blank, non-numeric, or
     *         out of range, or {@code null} when valid
     */
    private static String editExpiryMonth(String value) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isEmpty() || stripped.length() > MONTH_MAX_DIGITS || !isAllDigits(stripped)) {
            return MSG_CARD_EXPIRY_MONTH_NOT_VALID;
        }
        int month = Integer.parseInt(stripped);
        if (month < MONTH_MIN || month > MONTH_MAX) {
            return MSG_CARD_EXPIRY_MONTH_NOT_VALID;
        }
        return null;
    }

    /**
     * {@code 1260-EDIT-EXPIRY-YEAR}: the expiry year is mandatory, numeric, and in
     * the range 1950..2099 (COBOL {@code VALID-YEAR}, a {@code PIC X(4)} field).
     *
     * @param value the submitted expiry year
     * @return {@link #MSG_CARD_EXPIRY_YEAR_NOT_VALID} when blank, non-numeric, or
     *         out of range, or {@code null} when valid
     */
    private static String editExpiryYear(String value) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isEmpty() || stripped.length() > YEAR_DIGITS || !isAllDigits(stripped)) {
            return MSG_CARD_EXPIRY_YEAR_NOT_VALID;
        }
        int year = Integer.parseInt(stripped);
        if (year < YEAR_MIN || year > YEAR_MAX) {
            return MSG_CARD_EXPIRY_YEAR_NOT_VALID;
        }
        return null;
    }

    // ==================================================================
    // Value helpers
    // ==================================================================

    /**
     * Normalizes a validated expiry month to two digits ({@code "01".."12"}).
     *
     * @param value the validated month (guaranteed numeric and in range)
     * @return the zero-padded two-digit month
     */
    private static String twoDigitMonth(String value) {
        int month = Integer.parseInt(value.strip());
        return month < 10 ? "0" + month : Integer.toString(month);
    }

    /**
     * Extracts the day component ({@code DD}) from a stored {@code YYYY-MM-DD}
     * expiration date so it can be preserved across an update (the COBOL update
     * edits only month and year). Falls back to {@code "01"} when the stored value
     * is absent or malformed.
     *
     * @param existingExpiration the stored expiration date, expected as {@code YYYY-MM-DD}
     * @return the two-digit day component, or {@code "01"} when unavailable
     */
    private static String expirationDay(String existingExpiration) {
        if (existingExpiration != null && existingExpiration.length() >= EXPIRATION_DATE_LENGTH) {
            String day = existingExpiration.substring(EXPIRATION_DAY_START, EXPIRATION_DATE_LENGTH);
            if (isAllDigits(day)) {
                return day;
            }
        }
        return "01";
    }

    /**
     * Compares two values for equality after stripping surrounding whitespace,
     * treating {@code null} as an empty string.
     */
    private static boolean equalsStripped(String left, String right) {
        String a = left == null ? "" : left.strip();
        String b = right == null ? "" : right.strip();
        return a.equals(b);
    }

    /**
     * Compares two values for case-insensitive equality after stripping surrounding
     * whitespace, treating {@code null} as an empty string (the COBOL change
     * decision uppercases the name before comparing).
     */
    private static boolean equalsIgnoreCaseStripped(String left, String right) {
        String a = left == null ? "" : left.strip();
        String b = right == null ? "" : right.strip();
        return a.equalsIgnoreCase(b);
    }

    // ==================================================================
    // Filter-validity helpers (COCRDLIC / COCRDSLC edits)
    // ==================================================================

    /**
     * Determines whether an account filter is a valid, non-zero 11-digit number
     * (COBOL {@code 2210-EDIT-ACCOUNT}: "must be a non-zero 11 digit number").
     */
    private static boolean isAccountFilterValid(Long accountFilter) {
        return accountFilter != null && accountFilter > 0L && accountFilter <= ACCOUNT_ID_MAX;
    }

    /**
     * Determines whether a card filter is a valid, non-zero 16-digit number
     * (COBOL {@code 2220-EDIT-CARD}).
     */
    private static boolean isCardFilterValid(String cardFilter) {
        return cardFilter != null
                && cardFilter.length() == CARD_NUMBER_LENGTH
                && isAllDigits(cardFilter)
                && !isAllZeros(cardFilter);
    }

    /**
     * Forces the pagination request to sort by card number ascending, preserving
     * the VSAM KSDS primary-key order regardless of any caller-supplied sort.
     */
    private static Pageable byCardNumberAscending(Pageable pageable) {
        Sort sort = Sort.by(Sort.Direction.ASC, SORT_PROPERTY_CARD_NUM);
        if (pageable == null || pageable.isUnpaged()) {
            return Pageable.unpaged(sort);
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    /**
     * Wraps an already-resolved, card-number-ordered list into a {@link Page},
     * honouring the requested pagination window and clamping to the available
     * content (used for the unique-card-filter path).
     */
    private static Page<Card> pageOf(List<Card> content, Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return new PageImpl<>(content);
        }
        int offset = (int) Math.min(pageable.getOffset(), content.size());
        int end = Math.min(offset + pageable.getPageSize(), content.size());
        return new PageImpl<>(content.subList(offset, end), pageable, content.size());
    }

    // ==================================================================
    // Character-class helpers
    // ==================================================================

    /**
     * Returns {@code true} when every character is an ASCII letter or a space
     * (reproducing the COBOL alphabetic-and-spaces {@code INSPECT} check).
     */
    private static boolean isAlphaSpaces(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean alpha = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
            if (!alpha && ch != ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} when the value is non-empty and every character is an
     * ASCII digit.
     */
    private static boolean isAllDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} when the value is non-empty and every character is the
     * digit {@code '0'} (COBOL treats an all-zero field as "not provided").
     */
    private static boolean isAllZeros(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Strips surrounding whitespace and returns {@code null} when the result is
     * empty, so absent optional filters and blank submissions are handled uniformly.
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

}
