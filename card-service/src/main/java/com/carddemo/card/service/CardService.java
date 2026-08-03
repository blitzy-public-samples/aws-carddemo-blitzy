/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.card.service;

import com.carddemo.card.mapper.CardMapper;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.card.repository.CardXrefRepository;
import com.carddemo.common.domain.Card;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.dto.CardDetailResponseDto;
import com.carddemo.common.dto.CardListResponseDto;
import com.carddemo.common.dto.CardUpdateRequestDto;
import com.carddemo.common.dto.CardUpdateResponseDto;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.OptimisticLockConflictException;
import com.carddemo.common.exception.RecordNotFoundException;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * :purpose: Business-logic service for the CardDemo Card feature. Re-expresses the
 *  migrated ``PROCEDURE DIVISION`` logic of three legacy CICS programs:
 *  ``COCRDLIC`` (``CCLI``, card list, seven rows per page), ``COCRDSLC``
 *  (``CCDL``, card detail read by card number) and ``COCRDUPC`` (``CCUP``, card
 *  update with input edits and the read-snapshot-compare-rewrite concurrency
 *  check). Enforces the seven-rows-per-page browse, the ordered card and
 *  cross-reference reads, the fail-fast update validation edits, and the single
 *  atomic update unit of work. Reads run as read-only transactions and the update
 *  runs as one atomic transaction; the session context is supplied by the caller.
 */
@Service
public class CardService {

    /** :purpose: Component logger; never emits the CVV or the full card number. */
    private static final Logger log = LoggerFactory.getLogger(CardService.class);

    /**
     * :purpose: Rows shown per card-list page, from ``COCRDLIC``
     *  ``WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7``.
     */
    public static final int MAX_SCREEN_LINES = 7;

    /** :purpose: Largest value expressible in the eleven-digit ``CARD-ACCT-ID`` filter. */
    private static final long ACCT_ID_MAX = 99_999_999_999L;

    /** :purpose: Exact-match pattern for the sixteen-digit ``CARD-NUM`` filter. */
    private static final String CARD_NUM_PATTERN = "\\d{16}";

    // -- Card list messages (COCRDLIC.cbl), reproduced byte-for-byte. --------------

    /** :purpose: ``COCRDLIC`` ``WS-NO-RECORDS-FOUND`` empty-result message. */
    private static final String MSG_NO_RECORDS_FOUND =
            "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";

    /** :purpose: ``COCRDLIC`` invalid action-code message. */
    private static final String MSG_INVALID_ACTION_CODE = "INVALID ACTION CODE";

    /** :purpose: ``COCRDLIC`` forward-paging boundary message. */
    private static final String MSG_NO_MORE_PAGES = "NO MORE PAGES TO DISPLAY";

    /** :purpose: ``COCRDLIC`` browse boundary message. */
    private static final String MSG_NO_MORE_RECORDS = "NO MORE RECORDS TO SHOW";

    /** :purpose: ``COCRDLIC`` L903 backward-paging boundary message. */
    private static final String MSG_NO_PREVIOUS_PAGES = "NO PREVIOUS PAGES TO DISPLAY";

    /** :purpose: ``COCRDLIC`` ``WS-INFORM-REC-ACTIONS`` (L115-116) informational line. */
    private static final String MSG_INFORM_REC_ACTIONS =
            "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD";

    /** :purpose: ``COCRDLIC`` row action selecting the detail screen (``COCRDSLC``). */
    private static final String ACTION_SELECT = "S";

    /** :purpose: ``COCRDLIC`` row action selecting the update screen (``COCRDUPC``). */
    private static final String ACTION_UPDATE = "U";

    /** :purpose: ``COCRDLIC`` navigation action requesting the previous page (``DFHPF7``). */
    public static final String AID_PF7 = "PF7";

    /** :purpose: ``COCRDLIC`` navigation action requesting the next page (``DFHPF8``). */
    public static final String AID_PF8 = "PF8";

    /** :purpose: ``COCRDLIC`` account-filter edit message. */
    private static final String MSG_ACCT_FILTER_11 =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** :purpose: ``COCRDLIC`` card-number-filter edit message. */
    private static final String MSG_CARD_FILTER_16 =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    // -- Card detail messages (COCRDSLC.cbl), reproduced byte-for-byte. -------------

    /** :purpose: ``COCRDSLC`` ``DID-NOT-FIND-ACCTCARD-COMBO`` (card read miss). */
    private static final String MSG_DETAIL_NOT_FOUND =
            "Did not find cards for this search condition";

    /** :purpose: ``COCRDSLC`` ``DID-NOT-FIND-ACCT-IN-CARDXREF`` (account read miss). */
    private static final String MSG_ACCT_NOT_IN_CARDS =
            "Did not find this account in cards database";

    /** :purpose: ``COCRDSLC`` account-key edit message. */
    private static final String MSG_ACCT_NON_ZERO_11 =
            "Account number must be a non zero 11 digit number";

    /** :purpose: ``COCRDSLC`` card-key edit message. */
    private static final String MSG_CARD_IF_SUPPLIED_16 =
            "Card number if supplied must be a 16 digit number";

    // -- Card update validation-edit messages (COCRDUPC.cbl), byte-for-byte. --------

    /** :purpose: ``COCRDUPC`` ``WS-NAME-MUST-BE-ALPHA`` name edit message. */
    private static final String MSG_NAME_ALPHA =
            "Card name can only contain alphabets and spaces";

    /** :purpose: ``COCRDUPC`` ``CARD-STATUS-MUST-BE-YES-NO`` status edit message. */
    private static final String MSG_STATUS_YN = "Card Active Status must be Y or N";

    /** :purpose: ``COCRDUPC`` ``CARD-EXPIRY-MONTH-NOT-VALID`` month edit message. */
    private static final String MSG_EXPIRY_MONTH =
            "Card expiry month must be between 1 and 12";

    /** :purpose: ``COCRDUPC`` ``CARD-EXPIRY-YEAR-NOT-VALID`` year edit message. */
    private static final String MSG_EXPIRY_YEAR = "Invalid card expiry year";

    // -- Card update lock/rewrite outcome messages (COCRDUPC.cbl); retained for -----
    // -- traceability even though the pseudo-conversational lock mechanism collapses.

    /** :purpose: ``COCRDUPC`` ``COULD-NOT-LOCK-FOR-UPDATE`` outcome message. */
    private static final String MSG_COULD_NOT_LOCK = "Could not lock record for update";

    /**
     * :purpose: ``COCRDUPC`` ``DATA-WAS-CHANGED-BEFORE-UPDATE`` outcome message; byte-identical
     *  to ``OptimisticLockConflictException.MESSAGE`` (the shared HTTP 409 conflict message).
     */
    private static final String MSG_DATA_WAS_CHANGED =
            "Record changed by some one else. Please review";

    /** :purpose: ``COCRDUPC`` ``LOCKED-BUT-UPDATE-FAILED`` outcome message. */
    private static final String MSG_UPDATE_FAILED = "Update of record failed";

    /** :purpose: Repository for the ``cards`` store (legacy ``CARDDAT`` / ``CARDAIX``). */
    private final CardRepository cardRepository;

    /** :purpose: Repository for the ``card_xref`` store (legacy ``XREFFILE`` / ``CXACAIX``). */
    private final CardXrefRepository cardXrefRepository;

    /** :purpose: Entity-to-DTO mapper for card list, detail and update responses. */
    private final CardMapper cardMapper;

    /**
     * :purpose: Construct the card service with its three collaborators.
     * :param cardRepository: repository for the ``cards`` store.
     * :param cardXrefRepository: repository for the ``card_xref`` store.
     * :param cardMapper: entity-to-DTO mapper for card responses.
     */
    public CardService(CardRepository cardRepository,
                       CardXrefRepository cardXrefRepository,
                       CardMapper cardMapper) {
        this.cardRepository = cardRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.cardMapper = cardMapper;
    }

    /**
     * :purpose: List cards for the card-list screen (``COCRDLIC``, CICS ``CCLI``),
     *  reproducing the seven-rows-per-page browse over the card master with the
     *  legacy account and card-number filter edits and admin-versus-user account
     *  scoping. The list projection shows only the owning account id, the card
     *  number and the active status; the CVV and embossed name are never listed.
     * :param acctIdFilter: optional owning-account filter; when supplied it must be
     *  a positive number of at most eleven digits.
     * :param cardNumFilter: optional exact card-number filter; when supplied it must
     *  be sixteen digits.
     * :param pageNumber: the one-based page number to return.
     * :param sessionContext: the caller session; a non-admin user is scoped to the
     *  account it carries. May be ``null``.
     * :returns: the card-list response holding at most ``MAX_SCREEN_LINES`` rows.
     * :raises CardDemoException: when a supplied account or card-number filter is invalid.
     */
    @Transactional(readOnly = true)
    public CardListResponseDto listCards(Long acctIdFilter,
                                         String cardNumFilter,
                                         int pageNumber,
                                         SessionContext sessionContext) {
        return listCards(acctIdFilter, cardNumFilter, pageNumber, null, null, null, sessionContext);
    }

    /**
     * :purpose: List cards for the card-list screen (``COCRDLIC``, CICS ``CCLI``) including the
     *  navigation action and the row selection the screen carries, and reproduce
     *  ``1400-SETUP-MESSAGE`` (L895-925) so the operator sees the same banner the 3270 screen
     *  would have shown. ``WS-ERROR-MSG`` holds at most one message, and the legacy
     *  ``IF WS-ERROR-MSG-OFF`` guards mean the FIRST condition to fire wins.
     * :param acctIdFilter: optional owning-account filter; at most eleven digits when supplied.
     * :param cardNumFilter: optional exact card-number filter; sixteen digits when supplied.
     * :param pageNumber: the one-based page number to return (``WS-CA-SCREEN-NUM``).
     * :param aid: the navigation action, ``"PF7"`` (page back) or ``"PF8"`` (page forward);
     *  ``null`` or blank means plain entry.
     * :param action: the row-selection flag, ``"S"`` for detail or ``"U"`` for update.
     * :param selectedCardNumber: the card number of the selected row.
     * :param sessionContext: the caller session; a non-admin user is scoped to its account.
     * :returns: the card-list response holding at most ``MAX_SCREEN_LINES`` rows, the paging
     *  state, the resolved selection and the two message lines.
     * :raises CardDemoException: when a supplied filter is invalid, or the row-selection flag
     *  is neither ``S`` nor ``U``.
     */
    @Transactional(readOnly = true)
    public CardListResponseDto listCards(Long acctIdFilter,
                                         String cardNumFilter,
                                         int pageNumber,
                                         String aid,
                                         String action,
                                         String selectedCardNumber,
                                         SessionContext sessionContext) {
        // Filter edits (COCRDLIC 1210/1220), applied only when a filter is supplied.
        if (acctIdFilter != null && (acctIdFilter <= 0L || acctIdFilter > ACCT_ID_MAX)) {
            throw new CardDemoException(MSG_ACCT_FILTER_11);
        }
        boolean cardFilterSupplied = cardNumFilter != null && !cardNumFilter.isBlank();
        if (cardFilterSupplied && !cardNumFilter.matches(CARD_NUM_PATTERN)) {
            throw new CardDemoException(MSG_CARD_FILTER_16);
        }

        // Authorization scoping (AAP 0.6.3): a non-admin sees only its own account's cards.
        Long effectiveAcctId = acctIdFilter;
        if (sessionContext != null
                && sessionContext.getUserType() == SessionContext.UserType.CDEMO_USRTYP_USER) {
            effectiveAcctId = sessionContext.getAcctId();
        }

        // Fetch candidates, reproducing the CARDDAT keyed read / CARDAIX account browse.
        List<Card> candidates;
        if (cardFilterSupplied) {
            Card single = cardRepository.findById(cardNumFilter).orElse(null);
            boolean inScope = single != null
                    && (effectiveAcctId == null || effectiveAcctId.equals(single.getCardAcctId()));
            candidates = inScope ? List.of(single) : List.of();
        } else if (effectiveAcctId != null) {
            candidates = cardRepository.findByCardAcctId(effectiveAcctId);
        } else {
            candidates = cardRepository.findAll();
        }

        // Order by card number to match the VSAM primary-key browse order.
        List<Card> ordered = candidates.stream()
                .sorted(Comparator.comparing(Card::getCardNum,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        // Paginate to at most MAX_SCREEN_LINES rows (page is one-based like WS-CA-SCREEN-NUM).
        int page = Math.max(pageNumber, 1);
        int offset = (page - 1) * MAX_SCREEN_LINES;
        List<Card> pageRows = offset >= ordered.size()
                ? List.of()
                : ordered.subList(offset, Math.min(offset + MAX_SCREEN_LINES, ordered.size()));
        // COBOL reads one extra record (WS-MAX-SCREEN-LINES + 1) to know a further page exists.
        boolean morePagesExist = ordered.size() > offset + MAX_SCREEN_LINES;

        CardListResponseDto response = cardMapper.toListResponse(pageRows);
        response.setPageNumber(page);
        response.setNextPage(morePagesExist);

        // COCRDLIC 1300 row-action edit: the only valid flags are 'S' and 'U'. Performed
        // before 1400-SETUP-MESSAGE so an invalid flag wins over any paging banner, exactly
        // as WS-INVALID-ACTION-CODE does.
        if (action != null && !action.isBlank()) {
            String canonical = action.trim().toUpperCase(java.util.Locale.ROOT);
            if (!ACTION_SELECT.equals(canonical) && !ACTION_UPDATE.equals(canonical)) {
                throw new CardDemoException(MSG_INVALID_ACTION_CODE);
            }
            response.setSelectedAction(canonical);
            response.setSelectedCardNumber(selectedCardNumber == null ? null : selectedCardNumber.trim());
        }

        response.setMessage(resolveListMessage(page, pageRows.isEmpty(), morePagesExist, aid));
        if (!pageRows.isEmpty()) {
            response.setInfoMessage(MSG_INFORM_REC_ACTIONS);
        }

        if (response.getMessage() != null) {
            log.debug("card list page {} banner: {}", page, response.getMessage());
        } else {
            log.debug("card list page {} returned {} row(s); morePagesExist={}",
                    page, pageRows.size(), morePagesExist);
        }
        return response;
    }

    /**
     * :purpose: Reproduce ``COCRDLIC 1400-SETUP-MESSAGE`` (L895-925) plus the ``ENDFILE``
     *  branches of the browse loop (L1219/L1239), in the legacy evaluation order and honouring
     *  the ``IF WS-ERROR-MSG-OFF`` first-message-wins guard.
     * :param page: the one-based page number returned.
     * :param empty: whether the returned page holds no rows.
     * :param morePagesExist: whether a further forward page exists (``CA-NEXT-PAGE-EXISTS``).
     * :param aid: the navigation action, ``"PF7"``, ``"PF8"``, or ``null``.
     * :returns: the single ``WS-ERROR-MSG`` line, or ``null`` when no condition applies.
     */
    private String resolveListMessage(int page, boolean empty, boolean morePagesExist, String aid) {
        boolean pf7 = AID_PF7.equalsIgnoreCase(aid);
        boolean pf8 = AID_PF8.equalsIgnoreCase(aid);

        // WHEN CCARD-AID-PFK07 AND CA-FIRST-PAGE (L902-904).
        if (pf7 && page <= 1) {
            return MSG_NO_PREVIOUS_PAGES;
        }
        // WHEN CCARD-AID-PFK08 AND CA-NEXT-PAGE-NOT-EXISTS AND CA-LAST-PAGE-SHOWN (L905-909):
        // asking to advance past a page that is already the last one shown.
        if (pf8 && !morePagesExist && empty) {
            return MSG_NO_MORE_PAGES;
        }
        // Browse ENDFILE on the first screen with nothing read at all sets
        // WS-NO-RECORDS-FOUND (L1240-1244).
        if (empty && page <= 1) {
            return MSG_NO_RECORDS_FOUND;
        }
        // Browse ENDFILE reached (L1219/L1239): either the page came up short or it is empty
        // beyond the first screen.
        if (empty || !morePagesExist) {
            return MSG_NO_MORE_RECORDS;
        }
        return null;
    }

    /**
     * :purpose: Read a single card for the card-detail screen (``COCRDSLC``, CICS
     *  ``CCDL``) by its card number (``9100-GETCARD-BYACCTCARD``), joining the
     *  cross-reference for the owning customer linkage (AAP 0.6.4).
     * :param cardNumber: the sixteen-character card number to read.
     * :param sessionContext: the caller session; a non-admin user may view only a
     *  card owned by the account it carries. May be ``null``.
     * :returns: the card-detail response for the resolved card.
     * :raises RecordNotFoundException: when no card matches the card number, or the
     *  card is outside a non-admin caller's account.
     */
    @Transactional(readOnly = true)
    public CardDetailResponseDto getCardDetail(String cardNumber, SessionContext sessionContext) {
        // Keyed read of the card master by card number (COBOL 9100 RIDFLD(card-number)).
        Card card = cardRepository.findById(cardNumber)
                .orElseThrow(() -> new RecordNotFoundException(MSG_DETAIL_NOT_FOUND));

        // Non-admin scoping (AAP 0.6.3): a user cannot view another account's card.
        if (sessionContext != null
                && sessionContext.getUserType() == SessionContext.UserType.CDEMO_USRTYP_USER
                && sessionContext.getAcctId() != null
                && !sessionContext.getAcctId().equals(card.getCardAcctId())) {
            throw new RecordNotFoundException(MSG_DETAIL_NOT_FOUND);
        }

        // Cross-reference join for the owning customer id; the detail still renders
        // when the cross-reference is absent (the mapper leaves the customer id unset).
        CardXref xref = cardXrefRepository.findById(cardNumber).orElse(null);
        log.debug("card detail resolved for account {}", card.getCardAcctId());
        return cardMapper.toDetailResponse(card, xref);
    }

    /**
     * :purpose: Update a card for the card-update screen (``COCRDUPC``, CICS ``CCUP``)
     *  in one atomic transaction, reproducing the fail-fast input edits
     *  (``1230``-``1260``), the no-change short-circuit (``1200``), and the
     *  re-read-then-rewrite sequence (``9200``).
     * :param cardNumber: the sixteen-character card number to update.
     * :param request: the editable card fields (embossed name, active status, expiry
     *  date and CVV).
     * :param sessionContext: the caller session; the resolved card number and account
     *  id are propagated into it. May be ``null``.
     * :returns: the card-update response reflecting the persisted card.
     * :raises CardDemoException: when a validation edit fails (name, active status,
     *  expiry month or expiry year).
     * :raises RecordNotFoundException: when no card matches the card number.
     * :raises OptimisticLockConflictException: when the request carries a display-time
     *  snapshot (``CCUP-OLD-*``) that no longer matches the re-read card, signalling a
     *  concurrent modification (``9300-CHECK-CHANGE-IN-REC``).
     * :note: ``Card`` has no version column, so the legacy read-snapshot-compare-rewrite
     *  concurrency check (``9300-CHECK-CHANGE-IN-REC``) is reproduced at the service layer:
     *  when the request carries the display-time snapshot (``CCUP-OLD-*``) the re-read card
     *  is compared field-by-field against it and a mismatch throws
     *  ``OptimisticLockConflictException``; when no snapshot is supplied the check is skipped
     *  and the update is guarded only by the in-transaction re-read and no-change
     *  short-circuit. See ``docs/decision-log.md``.
     */
    @Transactional
    public CardUpdateResponseDto updateCard(String cardNumber,
                                            CardUpdateRequestDto request,
                                            SessionContext sessionContext) {
        if (request == null) {
            // No editable fields supplied: reproduce the first (name) edit failure.
            throw new CardDemoException(MSG_NAME_ALPHA);
        }

        // Step A -- validation edits, fail-fast in COBOL PERFORM order 1230->1240->1250->1260.
        validateEmbossedName(request.getCardEmbossedName());
        validateActiveStatus(request.getCardActiveStatus());
        validateExpiryMonth(request.getCardExpiraionDate());
        validateExpiryYear(request.getCardExpiraionDate());

        // Step C -- re-read the current card inside this transaction (COBOL 9200 READ ... UPDATE).
        Card card = cardRepository.findById(cardNumber)
                .orElseThrow(() -> new RecordNotFoundException(MSG_DETAIL_NOT_FOUND));
        CardXref xref = cardXrefRepository.findById(cardNumber).orElse(null);

        // Step D -- read-snapshot-compare-rewrite concurrency check (COBOL 9300-CHECK-CHANGE-IN-REC).
        // Reproduced at the service layer because Card has no version column: when the request
        // carries the display-time snapshot (CCUP-OLD-*), the re-read card is compared field-by-field
        // against it, and any difference signals a concurrent modification. When no snapshot is
        // supplied the check is skipped, preserving the behaviour of callers that omit it.
        if (snapshotPresent(request) && hasDataChangedSinceSnapshot(request, card)) {
            log.debug("card update conflict for account {}: {}",
                    card.getCardAcctId(), MSG_DATA_WAS_CHANGED);
            throw new OptimisticLockConflictException();
        }

        // Step B -- NO-CHANGES-DETECTED short-circuit (COBOL 1200): nothing to rewrite.
        if (isUnchanged(request, card)) {
            log.debug("card update no-op for account {}", card.getCardAcctId());
            return cardMapper.toUpdateResponse(card, xref);
        }

        // Step E -- apply the edits onto the managed entity (COBOL CARD-UPDATE-RECORD assembly).
        cardMapper.applyUpdate(request, card);

        // Step F -- persist within the single transaction (COBOL REWRITE; failure rolls back).
        Card saved = cardRepository.save(card);

        // Step G -- propagate the resolved identifiers into the session (COBOL COMMAREA moves).
        if (sessionContext != null) {
            sessionContext.setCardNum(saved.getCardNum());
            sessionContext.setAcctId(saved.getCardAcctId());
        }

        log.debug("card updated for account {}", saved.getCardAcctId());
        return cardMapper.toUpdateResponse(saved, xref);
    }

    /**
     * :purpose: Validate the embossed-name edit (``COCRDUPC 1230-EDIT-NAME``): the name
     *  is required and may contain only ASCII letters and spaces.
     * :param name: the submitted embossed name.
     * :raises CardDemoException: when the name is blank or contains a non-letter,
     *  non-space character.
     */
    private void validateEmbossedName(String name) {
        if (name == null || name.isBlank()) {
            throw new CardDemoException(MSG_NAME_ALPHA);
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            boolean alpha = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
            if (!alpha && ch != ' ') {
                throw new CardDemoException(MSG_NAME_ALPHA);
            }
        }
    }

    /**
     * :purpose: Validate the active-status edit (``COCRDUPC 1240-EDIT-CARDSTATUS``): the
     *  status is required and must be exactly ``"Y"`` or ``"N"``.
     * :param status: the submitted active-status flag.
     * :raises CardDemoException: when the status is not ``"Y"`` or ``"N"``.
     */
    private void validateActiveStatus(String status) {
        if (!"Y".equals(status) && !"N".equals(status)) {
            throw new CardDemoException(MSG_STATUS_YN);
        }
    }

    /**
     * :purpose: Validate the expiry-month edit (``COCRDUPC 1250-EDIT-EXPIRY-MON``): the
     *  month, taken from characters 6-7 of the ``YYYY-MM-DD`` expiry date, must be
     *  numeric and between 1 and 12.
     * :param expiraionDate: the submitted expiry date in ``YYYY-MM-DD`` form.
     * :raises CardDemoException: when the month is missing, non-numeric or out of range.
     */
    private void validateExpiryMonth(String expiraionDate) {
        Integer month = extractInt(expiraionDate, 5, 7);
        if (month == null || month < 1 || month > 12) {
            throw new CardDemoException(MSG_EXPIRY_MONTH);
        }
    }

    /**
     * :purpose: Validate the expiry-year edit (``COCRDUPC 1260-EDIT-EXPIRY-YEAR``): the
     *  year, taken from characters 1-4 of the ``YYYY-MM-DD`` expiry date, must be
     *  numeric and between 1950 and 2099.
     * :param expiraionDate: the submitted expiry date in ``YYYY-MM-DD`` form.
     * :raises CardDemoException: when the year is missing, non-numeric or out of range.
     */
    private void validateExpiryYear(String expiraionDate) {
        Integer year = extractInt(expiraionDate, 0, 4);
        if (year == null || year < 1950 || year > 2099) {
            throw new CardDemoException(MSG_EXPIRY_YEAR);
        }
    }

    /**
     * :purpose: Parse the ASCII-numeric substring ``[beginIndex, endIndex)`` of a value.
     * :param value: the source string, or ``null``.
     * :param beginIndex: the inclusive start index of the slice.
     * :param endIndex: the exclusive end index of the slice.
     * :returns: the parsed integer, or ``null`` when the value is too short or the slice
     *  contains a non-digit character.
     */
    private static Integer extractInt(String value, int beginIndex, int endIndex) {
        if (value == null || value.length() < endIndex) {
            return null;
        }
        String slice = value.substring(beginIndex, endIndex);
        for (int i = 0; i < slice.length(); i++) {
            char ch = slice.charAt(i);
            if (ch < '0' || ch > '9') {
                return null;
            }
        }
        return Integer.valueOf(slice);
    }

    /**
     * :purpose: Determine whether the submitted card data matches the current card,
     *  reproducing the ``COCRDUPC`` no-change short-circuit (the embossed name is
     *  compared case-insensitively, mirroring the COBOL upper-case compare).
     * :param request: the submitted editable card fields.
     * :param card: the current managed card.
     * :returns: ``true`` when the embossed name, active status, expiry date and CVV are
     *  all unchanged.
     */
    private boolean isUnchanged(CardUpdateRequestDto request, Card card) {
        return equalsIgnoreCase(request.getCardEmbossedName(), card.getCardEmbossedName())
                && equalsExact(request.getCardActiveStatus(), card.getCardActiveStatus())
                && equalsExact(request.getCardExpiraionDate(), card.getCardExpiraionDate())
                && equalsExact(request.getCardCvvCd(), card.getCardCvvCd());
    }

    /**
     * :purpose: Determine whether the request carries the display-time snapshot
     *  (``CCUP-OLD-*``), enabling the ``9300`` concurrent-change check. The snapshot is
     *  considered present when at least one snapshot field is non-null.
     * :param request: the submitted editable card fields.
     * :returns: ``true`` when at least one ``CCUP-OLD-*`` snapshot field is present.
     */
    private static boolean snapshotPresent(CardUpdateRequestDto request) {
        return request.getOldCardEmbossedName() != null
                || request.getOldCardActiveStatus() != null
                || request.getOldCardExpiraionDate() != null
                || request.getOldCardCvvCd() != null;
    }

    /**
     * :purpose: Determine whether the re-read card differs from the display-time snapshot
     *  carried in the request (``COCRDUPC 9300-CHECK-CHANGE-IN-REC``), signalling that the
     *  record was changed by someone else since it was displayed. Six fields are compared:
     *  CVV (``CCUP-OLD-CVV-CD``), embossed name (``CCUP-OLD-CRDNAME``, case-insensitive to
     *  mirror the COBOL upper-case ``INSPECT ... CONVERTING`` compare), expiry year
     *  (``CARD-EXPIRAION-DATE(1:4)``), expiry month (``(6:2)``), expiry day (``(9:2)``) and
     *  active status (``CCUP-OLD-CRDSTCD``).
     * :param request: the submitted card fields carrying the ``CCUP-OLD-*`` snapshot.
     * :param card: the current managed card re-read inside the update transaction.
     * :returns: ``true`` when any compared field differs (the record changed since display).
     */
    private boolean hasDataChangedSinceSnapshot(CardUpdateRequestDto request, Card card) {
        String snapshotExpiry = request.getOldCardExpiraionDate();
        String currentExpiry = card.getCardExpiraionDate();
        boolean matches =
                equalsExact(request.getOldCardCvvCd(), card.getCardCvvCd())
                        && equalsIgnoreCase(request.getOldCardEmbossedName(),
                                card.getCardEmbossedName())
                        && equalsExact(slice(snapshotExpiry, 0, 4), slice(currentExpiry, 0, 4))
                        && equalsExact(slice(snapshotExpiry, 5, 7), slice(currentExpiry, 5, 7))
                        && equalsExact(slice(snapshotExpiry, 8, 10), slice(currentExpiry, 8, 10))
                        && equalsExact(request.getOldCardActiveStatus(),
                                card.getCardActiveStatus());
        return !matches;
    }

    /**
     * :purpose: Return the substring ``[beginIndex, endIndex)`` of a value, or ``null`` when
     *  the value is ``null`` or too short; used by the ``9300`` expiry year/month/day slice
     *  compare (``CARD-EXPIRAION-DATE(1:4)/(6:2)/(9:2)``).
     * :param value: the source string, or ``null``.
     * :param beginIndex: the inclusive start index of the slice.
     * :param endIndex: the exclusive end index of the slice.
     * :returns: the requested slice, or ``null`` when the value is too short.
     */
    private static String slice(String value, int beginIndex, int endIndex) {
        if (value == null || value.length() < endIndex) {
            return null;
        }
        return value.substring(beginIndex, endIndex);
    }

    /**
     * :purpose: Null-safe case-insensitive string comparison.
     * :param a: the first value, or ``null``.
     * :param b: the second value, or ``null``.
     * :returns: ``true`` when both are ``null`` or equal ignoring case.
     */
    private static boolean equalsIgnoreCase(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    /**
     * :purpose: Null-safe exact string comparison.
     * :param a: the first value, or ``null``.
     * :param b: the second value, or ``null``.
     * :returns: ``true`` when both are ``null`` or equal.
     */
    private static boolean equalsExact(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

}
