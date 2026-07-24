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
package com.carddemo.transaction.service;

import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Transaction;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.dto.TransactionAddRequestDto;
import com.carddemo.common.dto.TransactionAddResponseDto;
import com.carddemo.common.dto.TransactionListItemDto;
import com.carddemo.common.dto.TransactionListRequestDto;
import com.carddemo.common.dto.TransactionListResponseDto;
import com.carddemo.common.dto.TransactionViewResponseDto;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.RecordNotFoundException;
import com.carddemo.common.util.DateUtil;
import com.carddemo.transaction.mapper.TransactionMapper;
import com.carddemo.transaction.repository.CardXrefRepository;
import com.carddemo.transaction.repository.TransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * :purpose: Online transaction business logic (list, view, add) migrated from the
 *  three CICS transaction programs COTRN00C (list, ``CT00``), COTRN01C (view,
 *  ``CT01``) and COTRN02C (add, ``CT02``). Reproduces the legacy validation order,
 *  verbatim operator messages, ten-row cursor paging, financial precision, and the
 *  cross-reference resolution exactly; the pseudo-conversational COMMAREA state is
 *  supplied as a :java:type:`SessionContext` method parameter and persistence,
 *  cross-reference, and mapping are delegated to the injected collaborators. The
 *  transaction id is drawn from the database sequence rather than the legacy
 *  browse-last-then-increment probe while preserving the 16-character zero-padded
 *  wire format.
 */
@Service
public class TransactionService {

    /** :purpose: Structured logger for flow milestones; never records PII/PCI (card numbers, CVV, SSN). */
    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    // --- Verbatim operator messages (character-for-character from the COBOL sources) ---

    /** :purpose: COTRN01C L149 empty transaction-id guard. */
    private static final String MSG_TRAN_ID_EMPTY = "Tran ID can NOT be empty...";
    /** :purpose: COTRN01C L285 / COTRN02C L657 keyed transaction not found. */
    private static final String MSG_TRAN_ID_NOT_FOUND = "Transaction ID NOT found...";
    /** :purpose: COTRN00C L214 non-numeric list filter (note the space before the ellipsis). */
    private static final String MSG_TRAN_ID_NUMERIC = "Tran ID must be Numeric ...";
    /** :purpose: COTRN00C L199 invalid row selection flag. */
    private static final String MSG_INVALID_SELECTION = "Invalid selection. Valid value is S";
    /** :purpose: COTRN00C L248 PF7 page-back top boundary. */
    private static final String MSG_ALREADY_TOP = "You are already at the top of the page...";
    /** :purpose: COTRN00C L270 PF8 page-forward bottom boundary. */
    private static final String MSG_ALREADY_BOTTOM = "You are already at the bottom of the page...";
    /** :purpose: COTRN02C L199 non-numeric account id. */
    private static final String MSG_ACCT_NUMERIC = "Account ID must be Numeric...";
    /** :purpose: COTRN02C L213 non-numeric card number. */
    private static final String MSG_CARD_NUMERIC = "Card Number must be Numeric...";
    /** :purpose: COTRN02C L226 neither account nor card entered. */
    private static final String MSG_ACCT_OR_CARD = "Account or Card Number must be entered...";
    /** :purpose: COTRN02C L593 account cross-reference not found. */
    private static final String MSG_ACCT_NOT_FOUND = "Account ID NOT found...";
    /** :purpose: COTRN02C L626 card cross-reference not found. */
    private static final String MSG_CARD_NOT_FOUND = "Card Number NOT found...";
    /** :purpose: COTRN02C L254 empty type code. */
    private static final String MSG_TYPE_EMPTY = "Type CD can NOT be empty...";
    /** :purpose: COTRN02C L260 empty category code. */
    private static final String MSG_CAT_EMPTY = "Category CD can NOT be empty...";
    /** :purpose: COTRN02C L266 empty source. */
    private static final String MSG_SOURCE_EMPTY = "Source can NOT be empty...";
    /** :purpose: COTRN02C L272 empty description. */
    private static final String MSG_DESC_EMPTY = "Description can NOT be empty...";
    /** :purpose: COTRN02C L278 empty amount. */
    private static final String MSG_AMT_EMPTY = "Amount can NOT be empty...";
    /** :purpose: COTRN02C L284 empty origination date. */
    private static final String MSG_ORIG_DATE_EMPTY = "Orig Date can NOT be empty...";
    /** :purpose: COTRN02C L290 empty processing date. */
    private static final String MSG_PROC_DATE_EMPTY = "Proc Date can NOT be empty...";
    /** :purpose: COTRN02C L296 empty merchant id. */
    private static final String MSG_MERCH_ID_EMPTY = "Merchant ID can NOT be empty...";
    /** :purpose: COTRN02C L302 empty merchant name. */
    private static final String MSG_MERCH_NAME_EMPTY = "Merchant Name can NOT be empty...";
    /** :purpose: COTRN02C L308 empty merchant city. */
    private static final String MSG_MERCH_CITY_EMPTY = "Merchant City can NOT be empty...";
    /** :purpose: COTRN02C L314 empty merchant zip. */
    private static final String MSG_MERCH_ZIP_EMPTY = "Merchant Zip can NOT be empty...";
    /** :purpose: COTRN02C L325 non-numeric type code. */
    private static final String MSG_TYPE_NUMERIC = "Type CD must be Numeric...";
    /** :purpose: COTRN02C L331 non-numeric category code. */
    private static final String MSG_CAT_NUMERIC = "Category CD must be Numeric...";
    /** :purpose: COTRN02C L345 malformed amount. */
    private static final String MSG_AMT_FORMAT = "Amount should be in format -99999999.99";
    /** :purpose: COTRN02C L360 malformed origination date. */
    private static final String MSG_ORIG_DATE_FORMAT = "Orig Date should be in format YYYY-MM-DD";
    /** :purpose: COTRN02C L375 malformed processing date. */
    private static final String MSG_PROC_DATE_FORMAT = "Proc Date should be in format YYYY-MM-DD";
    /** :purpose: COTRN02C L401 invalid origination date. */
    private static final String MSG_ORIG_DATE_INVALID = "Orig Date - Not a valid date...";
    /** :purpose: COTRN02C L421 invalid processing date. */
    private static final String MSG_PROC_DATE_INVALID = "Proc Date - Not a valid date...";
    /** :purpose: COTRN02C L432 non-numeric merchant id. */
    private static final String MSG_MERCH_ID_NUMERIC = "Merchant ID must be Numeric...";
    /** :purpose: COTRN02C L178 confirmation required. */
    private static final String MSG_CONFIRM = "Confirm to add this transaction...";
    /** :purpose: COTRN02C L184 invalid confirmation value. */
    private static final String MSG_INVALID_YN = "Invalid value. Valid values are (Y/N)...";
    /** :purpose: COTRN02C L738 duplicate transaction id on write. */
    private static final String MSG_TRAN_ID_EXISTS = "Tran ID already exist...";

    /** :purpose: List page size (COTRN00C ``PERFORM ... UNTIL WS-IDX > 10``). */
    private static final int PAGE_SIZE = 10;
    /** :purpose: CSUTLDTC message number treated as a valid-date carve-out (COTRN02C ``NOT = '2513'``). */
    private static final int CSUTLDTC_OK_MSG_NO = 2513;
    /** :purpose: Width of the zero-padded transaction-id and account/card key form. */
    private static final int TRAN_ID_WIDTH = 16;
    /** :purpose: Low sentinel key opening the forward browse from the first record (COBOL ``LOW-VALUES``). */
    private static final String LOW_SENTINEL = "0000000000000000";
    /** :purpose: Navigation action requesting the previous page (COBOL ``DFHPF7``). */
    private static final String ACTION_PF7 = "PF7";
    /** :purpose: Navigation action requesting the next page (COBOL ``DFHPF8``). */
    private static final String ACTION_PF8 = "PF8";
    /** :purpose: Upper magnitude bound of the ``-99999999.99`` amount screen format (eight integer digits). */
    private static final BigDecimal AMOUNT_MAX = new BigDecimal("99999999.99");

    /** :purpose: Transaction master repository (list browse, keyed read, save, id sequence). */
    private final TransactionRepository transactionRepository;
    /** :purpose: Card cross-reference repository (account/card linkage resolution). */
    private final CardXrefRepository cardXrefRepository;
    /** :purpose: Entity/DTO mapper for the transaction screens. */
    private final TransactionMapper transactionMapper;

    /**
     * :purpose: Construct the service with its collaborators via constructor injection.
     * :param transactionRepository: the transaction master repository.
     * :param cardXrefRepository: the card cross-reference repository.
     * :param transactionMapper: the entity/DTO mapper.
     */
    public TransactionService(TransactionRepository transactionRepository,
                              CardXrefRepository cardXrefRepository,
                              TransactionMapper transactionMapper) {
        this.transactionRepository = transactionRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.transactionMapper = transactionMapper;
    }

    /**
     * :purpose: List posted transactions ten per page with forward/backward cursor
     *  paging and an optional transaction-id filter, and honor row selection that
     *  drives navigation to the view screen (COTRN00C, ``CT00``).
     * :param request: the list request carrying the navigation action, tran-id
     *  filter, paging cursor, and row-selection inputs.
     * :param sessionContext: the pseudo-conversational session context; may be
     *  ``null``.
     * :returns: a {@link TransactionListResponseDto} with the page rows, paging
     *  cursor, next-page flag, and any selected id or informational message.
     * :raises CardDemoException: when the filter is non-numeric or a row selection
     *  flag is invalid.
     */
    @Transactional(readOnly = true)
    public TransactionListResponseDto listTransactions(TransactionListRequestDto request,
                                                       SessionContext sessionContext) {
        TransactionListRequestDto req = request == null ? new TransactionListRequestDto() : request;
        String action = req.getAction() == null ? "" : req.getAction().trim();
        log.debug("listTransactions action='{}' pageNumber={}", action, req.getPageNumber());

        if (ACTION_PF7.equalsIgnoreCase(action)) {
            return pageBackward(req);
        }
        if (ACTION_PF8.equalsIgnoreCase(action)) {
            return pageForward(req);
        }
        return enterList(req);
    }

    /**
     * :purpose: Handle the ENTER path of the list screen: process an optional row
     *  selection first, then validate the numeric filter and read the first forward
     *  page from the filter (inclusive) or from the top of the file.
     * :param req: the normalized list request.
     * :returns: the list response for the selection or the first forward page.
     * :raises CardDemoException: on an invalid selection flag or a non-numeric filter.
     */
    private TransactionListResponseDto enterList(TransactionListRequestDto req) {
        String selFlag = trimToNull(req.getSelectionFlag());
        String selId = trimToNull(req.getSelectedTranId());
        if (selFlag != null && selId != null) {
            if ("S".equalsIgnoreCase(selFlag)) {
                TransactionListResponseDto selResp = echoState(req);
                selResp.setSelectedTranId(selId);
                log.debug("listTransactions selection accepted for view navigation");
                return selResp;
            }
            throw new CardDemoException(MSG_INVALID_SELECTION);
        }

        String filter = trimToNull(req.getTranIdFilter());
        String cursor;
        if (filter == null) {
            cursor = LOW_SENTINEL;
        } else {
            if (!isNumeric(filter)) {
                throw new CardDemoException(MSG_TRAN_ID_NUMERIC);
            }
            cursor = inclusiveLowerCursor(pad16(filter));
        }
        return forwardPage(cursor, 0);
    }

    /**
     * :purpose: Handle the PF8 page-forward path: surface the bottom-boundary banner
     *  when no further page exists, otherwise read the next forward page from the
     *  last id of the current page.
     * :param req: the normalized list request.
     * :returns: the next forward page, or the current state carrying the
     *  bottom-boundary message.
     */
    private TransactionListResponseDto pageForward(TransactionListRequestDto req) {
        if (!req.isNextPage()) {
            TransactionListResponseDto resp = echoState(req);
            resp.setMessage(MSG_ALREADY_BOTTOM);
            return resp;
        }
        String cursor = pad16(defaultIfBlank(req.getTranIdLast(), LOW_SENTINEL));
        return forwardPage(cursor, req.getPageNumber());
    }

    /**
     * :purpose: Handle the PF7 page-back path: surface the top-boundary banner when
     *  already on the first page, otherwise read the previous page and re-sort it
     *  into ascending display order.
     * :param req: the normalized list request.
     * :returns: the previous page, or the current state carrying the top-boundary
     *  message.
     */
    private TransactionListResponseDto pageBackward(TransactionListRequestDto req) {
        if (req.getPageNumber() <= 1) {
            TransactionListResponseDto resp = echoState(req);
            resp.setMessage(MSG_ALREADY_TOP);
            return resp;
        }
        String cursor = pad16(defaultIfBlank(req.getTranIdFirst(), LOW_SENTINEL));
        List<Transaction> rows = new ArrayList<>(
                transactionRepository.findByTranIdLessThanOrderByTranIdDesc(
                        cursor, PageRequest.of(0, PAGE_SIZE)));
        rows.sort(Comparator.comparing(Transaction::getTranId));
        TransactionListResponseDto resp = buildPage(rows);
        resp.setPageNumber(Math.max(1, req.getPageNumber() - 1));
        return resp;
    }

    /**
     * :purpose: Read a forward page after the cursor id and assemble the response,
     *  computing the next-page flag with an extra existence probe.
     * :param cursor: the exclusive lower-bound cursor id.
     * :param basePageNumber: the page number preceding this page; the returned page
     *  number is one greater when rows are present.
     * :returns: the assembled forward-page response.
     */
    private TransactionListResponseDto forwardPage(String cursor, int basePageNumber) {
        List<Transaction> rows = transactionRepository.findByTranIdGreaterThanOrderByTranIdAsc(
                cursor, PageRequest.of(0, PAGE_SIZE));
        TransactionListResponseDto resp = buildPage(rows);
        if (rows.isEmpty()) {
            resp.setPageNumber(basePageNumber);
            resp.setNextPage(false);
        } else {
            resp.setPageNumber(basePageNumber + 1);
        }
        return resp;
    }

    /**
     * :purpose: Assemble a list response from an ascending-ordered page of
     *  transactions: map the rows and set the first/last cursor and the next-page
     *  flag from the last row.
     * :param rows: the page of transactions in ascending id order.
     * :returns: the assembled response (page number is set by the caller).
     */
    private TransactionListResponseDto buildPage(List<Transaction> rows) {
        TransactionListResponseDto resp = new TransactionListResponseDto();
        List<TransactionListItemDto> mapped = transactionMapper.toListRows(rows);
        resp.setTransactions(mapped == null ? new ArrayList<>() : mapped);
        if (!rows.isEmpty()) {
            String firstId = rows.get(0).getTranId();
            String lastId = rows.get(rows.size() - 1).getTranId();
            resp.setTranIdFirst(firstId);
            resp.setTranIdLast(lastId);
            resp.setNextPage(transactionRepository.existsByTranIdGreaterThan(lastId));
        } else {
            resp.setNextPage(false);
        }
        return resp;
    }

    /**
     * :purpose: Build a response that echoes the request's paging cursor unchanged,
     *  used for the page-boundary and row-selection turns that do not advance the
     *  page.
     * :param req: the normalized list request.
     * :returns: a response echoing the current cursor with no rows.
     */
    private TransactionListResponseDto echoState(TransactionListRequestDto req) {
        TransactionListResponseDto resp = new TransactionListResponseDto();
        resp.setTransactions(new ArrayList<>());
        resp.setPageNumber(req.getPageNumber());
        resp.setTranIdFirst(req.getTranIdFirst());
        resp.setTranIdLast(req.getTranIdLast());
        resp.setNextPage(req.isNextPage());
        return resp;
    }

    /**
     * :purpose: Retrieve a single posted transaction by its sixteen-character id and
     *  map every field to the view response (COTRN01C, ``CT01``).
     * :param tranId: the transaction id entered on the view screen.
     * :param sessionContext: the pseudo-conversational session context; may be
     *  ``null``.
     * :returns: the {@link TransactionViewResponseDto} for the located transaction.
     * :raises CardDemoException: when the id is null, empty, or blank.
     * :raises RecordNotFoundException: when no transaction exists for the id.
     */
    @Transactional(readOnly = true)
    public TransactionViewResponseDto viewTransaction(String tranId, SessionContext sessionContext) {
        if (tranId == null || tranId.trim().isEmpty()) {
            throw new CardDemoException(MSG_TRAN_ID_EMPTY);
        }
        String key = pad16(tranId.trim());
        log.debug("viewTransaction lookup for tranId key");
        Transaction entity = transactionRepository.findById(key)
                .orElseThrow(() -> new RecordNotFoundException(MSG_TRAN_ID_NOT_FOUND));
        return transactionMapper.toViewResponse(entity);
    }

    /**
     * :purpose: Validate every add-transaction input in the exact legacy order,
     *  resolve the account/card cross-reference, generate the transaction id from the
     *  database sequence, persist the new transaction, and return the confirmation
     *  response (COTRN02C, ``CT02``).
     * :param request: the add-transaction request carrying the key and editable
     *  fields plus the confirmation flag.
     * :param sessionContext: the pseudo-conversational session context; may be
     *  ``null``. When present the resolved account and card are propagated for
     *  continuity.
     * :returns: a {@link TransactionAddResponseDto} with the generated id and the
     *  verbatim confirmation message.
     * :raises CardDemoException: on any validation failure or a duplicate id.
     * :raises RecordNotFoundException: when the account or card cross-reference is
     *  not found.
     */
    @Transactional
    public TransactionAddResponseDto addTransaction(TransactionAddRequestDto request,
                                                    SessionContext sessionContext) {
        if (request == null) {
            throw new CardDemoException(MSG_ACCT_OR_CARD);
        }

        // (A) Key fields: account id has priority over card number (COTRN02C VALIDATE-INPUT-KEY-FIELDS).
        CardXref xref = resolveCrossReference(request);
        String resolvedCardNum = xref.getXrefCardNum();
        Long resolvedAcctId = xref.getXrefAcctId();

        // (B) Empty guards, in the exact legacy order (COTRN02C VALIDATE-INPUT-DATA-FIELDS).
        if (isBlank(request.getTranTypeCd())) {
            throw new CardDemoException(MSG_TYPE_EMPTY);
        }
        if (request.getTranCatCd() == null) {
            throw new CardDemoException(MSG_CAT_EMPTY);
        }
        if (isBlank(request.getTranSource())) {
            throw new CardDemoException(MSG_SOURCE_EMPTY);
        }
        if (isBlank(request.getTranDesc())) {
            throw new CardDemoException(MSG_DESC_EMPTY);
        }
        if (request.getTranAmt() == null) {
            throw new CardDemoException(MSG_AMT_EMPTY);
        }
        if (isBlank(request.getTranOrigTs())) {
            throw new CardDemoException(MSG_ORIG_DATE_EMPTY);
        }
        if (isBlank(request.getTranProcTs())) {
            throw new CardDemoException(MSG_PROC_DATE_EMPTY);
        }
        if (request.getTranMerchantId() == null) {
            throw new CardDemoException(MSG_MERCH_ID_EMPTY);
        }
        if (isBlank(request.getTranMerchantName())) {
            throw new CardDemoException(MSG_MERCH_NAME_EMPTY);
        }
        if (isBlank(request.getTranMerchantCity())) {
            throw new CardDemoException(MSG_MERCH_CITY_EMPTY);
        }
        if (isBlank(request.getTranMerchantZip())) {
            throw new CardDemoException(MSG_MERCH_ZIP_EMPTY);
        }

        // (C) Numeric checks for the type and category codes.
        if (!isNumeric(request.getTranTypeCd())) {
            throw new CardDemoException(MSG_TYPE_NUMERIC);
        }
        if (request.getTranCatCd() < 0) {
            throw new CardDemoException(MSG_CAT_NUMERIC);
        }

        // (D) Amount screen format (sign + eight digits + '.' + two digits).
        if (!matchesAmountFormat(request.getTranAmt())) {
            throw new CardDemoException(MSG_AMT_FORMAT);
        }
        BigDecimal amount = scale2(request.getTranAmt());

        // (E) Origination date character shape, then (F) processing date character shape.
        if (!matchesIsoDateShape(request.getTranOrigTs())) {
            throw new CardDemoException(MSG_ORIG_DATE_FORMAT);
        }
        if (!matchesIsoDateShape(request.getTranProcTs())) {
            throw new CardDemoException(MSG_PROC_DATE_FORMAT);
        }

        // (G) Origination date validity, then (H) processing date validity (CSUTLDTC).
        validateDateOrThrow(request.getTranOrigTs().substring(0, 10), MSG_ORIG_DATE_INVALID);
        validateDateOrThrow(request.getTranProcTs().substring(0, 10), MSG_PROC_DATE_INVALID);

        // (I) Merchant id numeric check.
        if (request.getTranMerchantId() < 0) {
            throw new CardDemoException(MSG_MERCH_ID_NUMERIC);
        }

        // Confirmation flag, evaluated after all field validation (COTRN02C PROCESS-ENTER-KEY).
        String confirm = request.getConfirm();
        if (!"Y".equalsIgnoreCase(defaultIfBlank(confirm, ""))) {
            if (isBlank(confirm) || "N".equalsIgnoreCase(confirm.trim())) {
                throw new CardDemoException(MSG_CONFIRM);
            }
            throw new CardDemoException(MSG_INVALID_YN);
        }

        // Build, stamp, and persist the new transaction.
        Transaction entity = transactionMapper.toEntity(request);
        entity.setTranCardNum(resolvedCardNum);
        entity.setTranAmt(amount);
        String tranId = String.format("%0" + TRAN_ID_WIDTH + "d", transactionRepository.getNextTransactionId());
        entity.setTranId(tranId);

        Transaction saved;
        try {
            saved = transactionRepository.save(entity);
        } catch (DataIntegrityViolationException ex) {
            throw new CardDemoException(MSG_TRAN_ID_EXISTS, ex);
        }

        if (sessionContext != null) {
            sessionContext.setAcctId(resolvedAcctId);
            sessionContext.setCardNum(resolvedCardNum);
        }

        String message = String.format("Transaction added successfully.  Your Tran ID is %s.", saved.getTranId());
        log.info("addTransaction persisted new transaction id {}", saved.getTranId());
        return new TransactionAddResponseDto(saved.getTranId(), message);
    }

    /**
     * :purpose: Resolve the card cross-reference from the request key fields with
     *  account-id priority, reproducing the COTRN02C ``EVALUATE TRUE`` key
     *  validation.
     * :param request: the add-transaction request.
     * :returns: the resolved {@link CardXref} linking card, customer, and account.
     * :raises CardDemoException: when a key is non-numeric or neither key is present.
     * :raises RecordNotFoundException: when the account or card cross-reference is
     *  not found.
     */
    private CardXref resolveCrossReference(TransactionAddRequestDto request) {
        String acctIdIn = trimToNull(request.getAcctId());
        String cardIn = trimToNull(request.getTranCardNum());
        if (acctIdIn != null) {
            if (!isNumeric(acctIdIn)) {
                throw new CardDemoException(MSG_ACCT_NUMERIC);
            }
            Long acctId = Long.valueOf(acctIdIn);
            return cardXrefRepository.findByXrefAcctId(acctId)
                    .orElseThrow(() -> new RecordNotFoundException(MSG_ACCT_NOT_FOUND));
        }
        if (cardIn != null) {
            if (!isNumeric(cardIn)) {
                throw new CardDemoException(MSG_CARD_NUMERIC);
            }
            return cardXrefRepository.findByXrefCardNum(cardIn)
                    .orElseThrow(() -> new RecordNotFoundException(MSG_CARD_NOT_FOUND));
        }
        throw new CardDemoException(MSG_ACCT_OR_CARD);
    }

    /**
     * :purpose: Validate a date against the ISO mask, applying the CSUTLDTC
     *  ``2513`` carve-out that the legacy program treats as valid.
     * :param date: the ten-character ``YYYY-MM-DD`` date to validate.
     * :param message: the verbatim message to raise when the date is invalid.
     * :raises CardDemoException: when the date is invalid and the CSUTLDTC message
     *  number is not the ``2513`` carve-out.
     */
    private void validateDateOrThrow(String date, String message) {
        DateUtil.DateValidationResult result = DateUtil.validateDate(date, DateUtil.MASK_ISO);
        if (result.severity() == 0) {
            return;
        }
        if (result.msgNo() != CSUTLDTC_OK_MSG_NO) {
            throw new CardDemoException(message);
        }
    }

    /**
     * :purpose: Report whether a string is a non-empty run of ASCII digits,
     *  reproducing the COBOL ``IS NUMERIC`` test on a display field.
     * :param value: the candidate string; may be ``null``.
     * :returns: ``true`` when the trimmed value is non-empty and all ASCII digits.
     */
    private static boolean isNumeric(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * :purpose: Report whether an amount fits the ``-99999999.99`` screen format,
     *  i.e. at most eight integer digits at scale two (COTRN02C amount check).
     * :param amount: the amount to test; may be ``null``.
     * :returns: ``true`` when the amount is present and its magnitude does not exceed
     *  ``99999999.99``.
     */
    private static boolean matchesAmountFormat(BigDecimal amount) {
        if (amount == null) {
            return false;
        }
        return amount.abs().compareTo(AMOUNT_MAX) <= 0;
    }

    /**
     * :purpose: Report whether the first ten characters of a value form the
     *  ``YYYY-MM-DD`` character shape (COTRN02C date character-position check).
     * :param value: the candidate string; may be ``null``.
     * :returns: ``true`` when positions one to ten match ``NNNN-NN-NN``.
     */
    private static boolean matchesIsoDateShape(String value) {
        if (value == null || value.length() < 10) {
            return false;
        }
        return isAsciiDigit(value, 0) && isAsciiDigit(value, 1) && isAsciiDigit(value, 2)
                && isAsciiDigit(value, 3)
                && value.charAt(4) == '-'
                && isAsciiDigit(value, 5) && isAsciiDigit(value, 6)
                && value.charAt(7) == '-'
                && isAsciiDigit(value, 8) && isAsciiDigit(value, 9);
    }

    /**
     * :purpose: Report whether the character at an index is an ASCII digit.
     * :param value: the string to inspect.
     * :param index: the character index.
     * :returns: ``true`` when the character is ``0``..``9``.
     */
    private static boolean isAsciiDigit(String value, int index) {
        char c = value.charAt(index);
        return c >= '0' && c <= '9';
    }

    /**
     * :purpose: Normalize a key to the sixteen-character zero-padded stored form; a
     *  numeric value is left-padded with zeros, otherwise the trimmed value is used.
     * :param value: the raw key; may be ``null``.
     * :returns: the sixteen-character zero-padded key, or the trimmed value when it
     *  is not numeric.
     */
    private static String pad16(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (isNumeric(trimmed)) {
            return String.format("%0" + TRAN_ID_WIDTH + "d", Long.valueOf(trimmed));
        }
        return trimmed;
    }

    /**
     * :purpose: Compute the exclusive lower-bound cursor that makes a filter id an
     *  inclusive start of the forward browse (COBOL ``STARTBR`` GTEQ semantics
     *  against the strict ``GreaterThan`` query).
     * :param paddedId: the sixteen-character zero-padded filter id.
     * :returns: the largest id strictly less than the filter, or the low sentinel
     *  when the filter is at or below the first key.
     */
    private static String inclusiveLowerCursor(String paddedId) {
        if (isNumeric(paddedId)) {
            long value = Long.parseLong(paddedId);
            if (value <= 0L) {
                return LOW_SENTINEL;
            }
            return String.format("%0" + TRAN_ID_WIDTH + "d", value - 1L);
        }
        return LOW_SENTINEL;
    }

    /**
     * :purpose: Normalize a monetary value to the COBOL ``S9(09)V99`` fixed scale of
     *  two decimal places using {@link RoundingMode#HALF_UP}.
     * :param value: the amount to normalize; may be ``null``.
     * :returns: the value at scale two, or ``null`` when the value is ``null``.
     */
    private static BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * :purpose: Report whether a string is ``null`` or blank after trimming (COBOL
     *  ``= SPACES OR LOW-VALUES``).
     * :param value: the candidate string; may be ``null``.
     * :returns: ``true`` when the value is ``null`` or trims to empty.
     */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * :purpose: Trim a string and collapse blank values to ``null``.
     * :param value: the candidate string; may be ``null``.
     * :returns: the trimmed value, or ``null`` when blank.
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * :purpose: Return the value when non-blank, otherwise a fallback.
     * :param value: the candidate string; may be ``null``.
     * :param fallback: the value to use when the candidate is blank.
     * :returns: the trimmed candidate when non-blank, otherwise the fallback.
     */
    private static String defaultIfBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }
}
