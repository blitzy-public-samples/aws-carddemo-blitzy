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

import com.carddemo.common.constant.Messages;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Transaction;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.dto.TransactionAddRequestDto;
import com.carddemo.common.dto.TransactionAddResponseDto;
import com.carddemo.common.dto.TransactionKeyResponseDto;
import com.carddemo.common.dto.TransactionListItemDto;
import com.carddemo.common.dto.TransactionListRequestDto;
import com.carddemo.common.dto.TransactionListResponseDto;
import com.carddemo.common.dto.TransactionKeyDto;
import com.carddemo.common.dto.TransactionViewResponseDto;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.RecordNotFoundException;
import com.carddemo.common.util.DateUtil;
import com.carddemo.common.util.LegacyTimestamp;
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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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
    /** :purpose: COTRN00C L608 ``STARTBR-TRANSACT-FILE`` NOTFND: nothing at or after the browse position. */
    private static final String MSG_BROWSE_TOP = "You are at the top of the page...";
    /** :purpose: COTRN00C L642 ``READNEXT-TRANSACT-FILE`` ENDFILE: the forward browse ran out of records. */
    private static final String MSG_REACHED_BOTTOM = "You have reached the bottom of the page...";
    /** :purpose: COTRN00C L676 ``READPREV-TRANSACT-FILE`` ENDFILE: the backward browse ran out of records. */
    private static final String MSG_REACHED_TOP = "You have reached the top of the page...";
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

    /**
     * :purpose: Bound on how many sequence values are probed for an unused transaction
     *  id before the add is refused. A small bound suffices because the sequence only
     *  ever has to step past ids the ``DALYTRAN`` feed already posted.
     */
    private static final int MAX_TRAN_ID_PROBES = 1000;

    /** :purpose: List page size (COTRN00C ``PERFORM ... UNTIL WS-IDX > 10``). */
    private static final int PAGE_SIZE = 10;
    /** :purpose: CSUTLDTC message number treated as a valid-date carve-out (COTRN02C ``NOT = '2513'``). */
    private static final int CSUTLDTC_OK_MSG_NO = 2513;
    /** :purpose: Width of the zero-padded transaction-id and account/card key form. */
    private static final int TRAN_ID_WIDTH = 16;



    /** Declared width of ``XREF-ACCT-ID`` / ``ACTIDIN`` (``PIC 9(11)``). */
    private static final int ACCT_ID_WIDTH = 11;

    /**
     * :purpose: Declared width of the card-number key (``CARD-NUM PIC X(16)``, ``COTRN02``
     *  ``CARDNIN LENGTH=16``). The width is exact for the same reason the account width is.
     */
    private static final int CARD_NUM_WIDTH = 16;
    /** :purpose: Low sentinel key opening the forward browse from the first record (COBOL ``LOW-VALUES``). */
    private static final String LOW_SENTINEL = "0000000000000000";
    /** :purpose: Navigation action requesting the previous page (COBOL ``DFHPF7``). */
    /**
     * :purpose: The list screen's default action, matching ``WHEN DFHENTER``
     *     [app/cbl/COTRN00C.cbl:L120]. Accepted explicitly as well as by omission, so a caller may
     *     name the key it pressed rather than having to send nothing.
     */
    private static final String ACTION_ENTER = "ENTER";

    private static final String ACTION_PF7 = "PF7";
    /** :purpose: Navigation action requesting the next page (COBOL ``DFHPF8``). */
    private static final String ACTION_PF8 = "PF8";
    /** :purpose: Upper magnitude bound of the ``-99999999.99`` amount screen format (eight integer digits). */
    private static final BigDecimal AMOUNT_MAX = new BigDecimal("99999999.99");

    /** Largest category code the four-character ``TCATCDI`` field can carry (``PIC 9(04)``). */
    private static final int CAT_CD_MAX = 9999;

    /** Largest merchant id the nine-character ``MIDI`` field can carry (``PIC 9(09)``). */
    private static final long MERCHANT_ID_MAX = 999_999_999L;

    /** SQL state PostgreSQL raises for a unique/primary-key violation. */
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    /** Decimal places the ``TRNAMTI`` picture provides (``PIC S9(09)V99`` -> ``NUMERIC(11,2)``). */
    private static final int MONEY_SCALE_MAX = 2;

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
        // COTRN00C evaluates EIBAID against exactly ENTER, PF3, PF7 and PF8, and its WHEN OTHER
        // branch reports CCDA-MSG-INVALID-KEY [app/cbl/COTRN00C.cbl:L129-L132]. Treating an
        // unrecognized action as ENTER instead silently served the first page: a caller that
        // mistyped a key, or that this service simply does not support, was told nothing and was
        // given a page it never asked for. An ABSENT action is still ENTER -- that is the screen's
        // default, not an unrecognized key.
        if (!action.isEmpty() && !ACTION_ENTER.equalsIgnoreCase(action)) {
            log.debug("listTransactions rejected unrecognized action");
            throw new CardDemoException(Messages.CCDA_MSG_INVALID_KEY);
        }
        return enterList(req);
    }

    /**
     * :purpose: Handle the ENTER path of the list screen: process an optional row
     *  selection first, then validate the numeric filter and read the first forward
     *  page from the filter (inclusive) or from the top of the file.
     * :param req: the normalized list request.
     * :returns: the list response for the selection or the first forward page; an
     *  unmatched filter answers with no rows and the browse-start boundary literal.
     * :raises CardDemoException: on an invalid selection flag or a non-numeric filter.
     */
    private TransactionListResponseDto enterList(TransactionListRequestDto req) {
        String selFlag = trimToNull(req.getSelectionFlag());
        String selId = trimToNull(req.getSelectedTranId());
        if (selFlag != null && selId != null) {
            if ("S".equalsIgnoreCase(selFlag)) {
                // The rows STAY on display. COTRN00C's selection path reaches SEND-TRNLST-SCREEN
                // without erasing the map, so the operator still sees the page they selected from.
                // Echoing an empty row list instead told the caller the page had become empty at
                // the very moment they picked a row out of it.
                TransactionListResponseDto selResp = redisplayCurrentPage(req);
                selResp.setSelectedTranId(selId);
                log.debug("listTransactions selection accepted for view navigation");
                return selResp;
            }
            throw new CardDemoException(MSG_INVALID_SELECTION);
        }

        // L206-217 edits the field as received: EQUAL SPACES OR LOW-VALUES browses from
        // LOW-VALUES, and anything else must be NUMERIC. Trimming first made a tab
        // indistinguishable from an unsupplied filter, so a discarded filter answered with
        // the whole first page and no message at all.
        String filter = req.getTranIdFilter();
        String cursor;
        if (isNotSupplied(filter)) {
            cursor = LOW_SENTINEL;
        } else {
            // COTRN00C L206-L219: blank browses from the top, otherwise
            // `IF TRNIDINI OF COTRN0AI IS NUMERIC` decides. `TRNIDIN` is a sixteen-column
            // field and a class test on an alphanumeric item is true only when EVERY
            // character is a digit, so a shorter entry — which BMS returns blank-padded —
            // fails it and publishes this literal. Zero-padding the entry instead let an
            // unpadded filter position the browse at the record its padded form names. The
            // class test does not trim either, so a value carrying a space fails it here
            // exactly as it does in the source. The width is part of the same edit rather
            // than a second message -- a value wider than the key cannot be keyed on the
            // 3270 at all -- and it is enforced BEFORE the value reaches the key
            // arithmetic, which is what kept an over-wide digit run out of a 500.
            if (!isAllDigits(filter) || filter.length() != TRAN_ID_WIDTH) {
                throw new CardDemoException(MSG_TRAN_ID_NUMERIC);
            }
            cursor = inclusiveLowerCursor(filter);
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
            TransactionListResponseDto resp = redisplayCurrentPage(req);
            resp.setMessage(MSG_ALREADY_BOTTOM);
            return resp;
        }
        String cursor = defaultIfBlank(req.getTranIdLast(), LOW_SENTINEL);
        return forwardPage(cursor, req.getPageNumber());
    }

    /**
     * :purpose: Handle the PF7 page-back path: surface the top-boundary banner when
     *  already on the first page, otherwise read the previous page and re-sort it
     *  into ascending display order.
     * :param req: the normalized list request.
     * :returns: the previous page, or the current state carrying the top-boundary
     *  message; a short previous page carries the backward-exhaustion literal.
     */
    private TransactionListResponseDto pageBackward(TransactionListRequestDto req) {
        if (req.getPageNumber() <= 1) {
            TransactionListResponseDto resp = redisplayCurrentPage(req);
            resp.setMessage(MSG_ALREADY_TOP);
            return resp;
        }
        String cursor = defaultIfBlank(req.getTranIdFirst(), LOW_SENTINEL);
        List<Transaction> rows = new ArrayList<>(
                transactionRepository.findByTranIdLessThanOrderByTranIdDesc(
                        cursor, PageRequest.of(0, PAGE_SIZE)));
        rows.sort(Comparator.comparing(Transaction::getTranId));
        TransactionListResponseDto resp = buildPage(rows);
        resp.setPageNumber(Math.max(1, req.getPageNumber() - 1));
        if (rows.size() < PAGE_SIZE) {
            // PROCESS-PAGE-BACKWARD fills slot ten down to slot one with READPREV; running out
            // takes the ENDFILE arm, which publishes MSG_REACHED_TOP [COTRN00C L671-L679].
            resp.setMessage(MSG_REACHED_TOP);
        }
        return resp;
    }

    /**
     * :purpose: Read a forward page after the cursor id and assemble the response,
     *  computing the next-page flag with an extra existence probe and publishing the
     *  browse-exhaustion literal when the page is empty or short.
     * :param cursor: the exclusive lower-bound cursor id.
     * :param basePageNumber: the page number preceding this page; the returned page
     *  number is one greater when rows are present.
     * :returns: the assembled forward-page response, carrying a boundary message when
     *  the browse ran out of records.
     * :note: The next-page flag comes from an existence probe rather than from a
     *  browse read, so a FULL last page carries no message: on the 3270 the message on
     *  that turn comes from the look-ahead ``READNEXT`` after the fill loop, which is
     *  the probe's analogue and is the same choice ``UserService`` makes for
     *  ``COUSR00C``. The literals here are the ones the fill loop itself raises.
     */
    private TransactionListResponseDto forwardPage(String cursor, int basePageNumber) {
        List<Transaction> rows = transactionRepository.findByTranIdGreaterThanOrderByTranIdAsc(
                cursor, PageRequest.of(0, PAGE_SIZE));
        TransactionListResponseDto resp = buildPage(rows);
        if (rows.isEmpty()) {
            resp.setPageNumber(basePageNumber);
            resp.setNextPage(false);
            // A forward browse that yields nothing is never silent on the 3270. Reading from a
            // fresh position (the ENTER turn, base page zero) is COTRN00C's STARTBR with no key at
            // or after the requested id, whose NOTFND arm publishes MSG_BROWSE_TOP [L603-L611];
            // reading on from a page already on display is the fill loop's READNEXT hitting
            // ENDFILE, whose arm publishes MSG_REACHED_BOTTOM [L637-L645]. Both arms reach
            // SEND-TRNLST-SCREEN, which moves WS-MESSAGE into ERRMSGO [L531], so the operator
            // always learns why the rows stopped. Answering 200-with-no-rows and no message left
            // an unmatched search indistinguishable from a screen that had not been submitted.
            resp.setMessage(basePageNumber == 0 ? MSG_BROWSE_TOP : MSG_REACHED_BOTTOM);
        } else {
            resp.setPageNumber(basePageNumber + 1);
            if (rows.size() < PAGE_SIZE) {
                // A short page means the fill loop itself hit ENDFILE before slot ten, which is
                // the same READNEXT arm and therefore the same literal [COTRN00C L637-L645].
                resp.setMessage(MSG_REACHED_BOTTOM);
            }
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
     * :purpose: Re-read the page currently on display and echo the request's cursor, so a
     *  page-boundary turn answers with the rows still shown rather than with none. The
     *  boundary branches of ``PROCESS-PF7-KEY`` and ``PROCESS-PF8-KEY`` reach
     *  ``SEND-TRNLST-SCREEN`` with ``SEND-ERASE-NO``, which re-sends the map without
     *  erasing it and therefore leaves the displayed rows in place.
     * :param req: the normalized list request, carrying the first id on display.
     * :returns: the current page's rows with the request's page number and next-page flag
     *  preserved; no rows when nothing has been displayed yet.
     */
    private TransactionListResponseDto redisplayCurrentPage(TransactionListRequestDto req) {
        String first = defaultIfBlank(req.getTranIdFirst(), "");
        if (first.isEmpty()) {
            return echoState(req);
        }
        List<Transaction> rows = transactionRepository.findByTranIdGreaterThanOrderByTranIdAsc(
                inclusiveLowerCursor(first), PageRequest.of(0, PAGE_SIZE));
        if (rows.isEmpty()) {
            return echoState(req);
        }
        TransactionListResponseDto resp = buildPage(rows);
        resp.setPageNumber(req.getPageNumber());
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
        // COTRN01C applies NO numeric or width edit to `TRNIDIN` — PROCESS-ENTER-KEY tests
        // only for an empty field and then performs `MOVE TRNIDINI TO TRAN-ID` (both
        // `PIC X(16)`) and reads. The key is therefore whatever the operator typed,
        // blank-padded by BMS, and a short run simply misses the read. Zero-padding it
        // here resolved a record the operator never named.
        String key = tranId.trim();
        log.debug("viewTransaction lookup for tranId key");
        Transaction entity = transactionRepository.findById(key)
                .orElseThrow(() -> new RecordNotFoundException(MSG_TRAN_ID_NOT_FOUND));
        return transactionMapper.toViewResponse(entity);
    }

    /**
     * :purpose: Read the highest-keyed transaction on file, backing the add screen's
     *  ``F5=Copy Last Tran.`` action. ``COPY-LAST-TRAN-DATA`` reaches the same record by
     *  moving ``HIGH-VALUES`` into ``TRAN-ID`` and issuing ``STARTBR`` / ``READPREV`` /
     *  ``ENDBR``; the descending-order read here is that browse.
     * :returns: a {@link TransactionViewResponseDto} for the last transaction on file.
     * :raises RecordNotFoundException: when no transaction exists, the equivalent of the
     *  browse returning end-of-file.
     */
    public TransactionViewResponseDto viewLastTransaction() {
        log.debug("viewLastTransaction reading the highest-keyed transaction");
        Transaction entity = transactionRepository.findFirstByOrderByTranIdDesc()
                .orElseThrow(() -> new RecordNotFoundException(MSG_TRAN_ID_NOT_FOUND));
        return transactionMapper.toViewResponse(entity);
    }

    /**
     * :purpose: Run ``VALIDATE-INPUT-KEY-FIELDS`` on its own and report the pair it
     *  resolves. ``PROCESS-ENTER-KEY`` performs that paragraph before
     *  ``VALIDATE-INPUT-DATA-FIELDS``, so the existence of the account or card is
     *  established before any data field is examined; running the edit here lets the
     *  screen keep that order instead of reporting an empty data field for a key that is
     *  not on file. The paragraph also writes the counterpart key back onto the map, so
     *  the resolved pair is returned rather than just an acknowledgement.
     * :param acctId: the ``ACTIDIN`` entry value; may be ``null`` or blank.
     * :param tranCardNum: the ``CARDNIN`` entry value; may be ``null`` or blank.
     * :returns: the resolved account id and card number.
     * :raises CardDemoException: when a key is non-numeric or neither key is present.
     * :raises RecordNotFoundException: when the account or card cross-reference is
     *  not found.
     */
    @Transactional(readOnly = true)
    public TransactionKeyResponseDto resolveKeys(String acctId, String tranCardNum) {
        CardXref xref = resolveCrossReference(acctId, tranCardNum);
        return new TransactionKeyResponseDto(
                String.format("%011d", xref.getXrefAcctId()), xref.getXrefCardNum());
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
        // The 3270 map field TCATCDI is four characters wide (TRAN-CAT-CD PIC 9(04)), so a
        // five-digit category could never be entered; over the wire it reached the INSERT and
        // tripped chk_transactions_cat_cd, which surfaced as a duplicate-id business error.
        if (request.getTranCatCd() < 0 || request.getTranCatCd() > CAT_CD_MAX) {
            throw new CardDemoException(MSG_CAT_NUMERIC);
        }

        // (D) Amount screen format (sign + eight digits + '.' + two digits).
        if (!matchesAmountFormat(request.getTranAmt())) {
            throw new CardDemoException(MSG_AMT_FORMAT);
        }
        BigDecimal amount = scale2(request.getTranAmt());

        // (E) Origination date character shape, then (F) processing date character shape.
        if (!LegacyTimestamp.isMapDateField(request.getTranOrigTs())) {
            throw new CardDemoException(MSG_ORIG_DATE_FORMAT);
        }
        if (!LegacyTimestamp.isMapDateField(request.getTranProcTs())) {
            throw new CardDemoException(MSG_PROC_DATE_FORMAT);
        }

        // (G) Origination date validity, then (H) processing date validity (CSUTLDTC).
        validateDateOrThrow(LegacyTimestamp.stripTrailingBlanks(request.getTranOrigTs()),
                MSG_ORIG_DATE_INVALID);
        validateDateOrThrow(LegacyTimestamp.stripTrailingBlanks(request.getTranProcTs()),
                MSG_PROC_DATE_INVALID);



        // (I) Merchant id numeric check.
        // MIDI is nine characters wide (TRAN-MERCHANT-ID PIC 9(09)); the same reasoning as the
        // category code applies (chk_transactions_merchant_id).
        if (request.getTranMerchantId() < 0 || request.getTranMerchantId() > MERCHANT_ID_MAX) {
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
        // Stamp the dates in the canonical stored form the same way the amount is stamped at
        // its fixed scale just above: the mapper renders it and this method restates it, both
        // through the ONE helper that owns the contract, so the value that reaches the column
        // is the same whichever path built the entity (COTRN02C L464-L465).
        entity.setTranOrigTs(LegacyTimestamp.fromMapDateField(request.getTranOrigTs()));
        entity.setTranProcTs(LegacyTimestamp.fromMapDateField(request.getTranProcTs()));
        String tranId = nextUnusedTransactionId();
        entity.setTranId(tranId);

        Transaction saved;
        try {
            // saveAndFlush, not save: the INSERT must reach the database INSIDE this
            // try block so a duplicate primary key surfaces here as the verbatim
            // "Tran ID already exist..." rejection (COTRN02C DUPKEY/DUPREC) instead of
            // failing at commit, after the catch. Transaction implements Persistable so
            // an assigned id is INSERTed and never merged over an existing row.
            saved = transactionRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            // ONLY a unique/primary-key violation on the transaction id means "a concurrent
            // writer claimed the id between the probe and the insert". Reporting every
            // integrity failure -- a violated check constraint, a numeric overflow, a missing
            // parent row -- as a duplicate id told the client to retry a request that can
            // never succeed, and hid the real fault. Anything else propagates unchanged so the
            // data-access handler reports it honestly with its correlation id.
            if (!isTransactionIdUniqueViolation(ex)) {
                log.error("addTransaction failed on a data-integrity violation that is not a "
                        + "duplicate transaction id", ex);
                throw ex;
            }
            log.warn("addTransaction rejected duplicate transaction id {}", tranId);
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
     * :purpose: Draw the next transaction id from the database sequence, skipping any
     *  value already present in the transaction master. The sequence starts from one
     *  while the ``DALYTRAN`` feed posts its own sixteen-digit numeric ids, so a raw
     *  sequence value can name a row the posting job already wrote. Persisting under
     *  that id previously overwrote the existing financial row, because JPA ``save``
     *  merges an entity whose identifier already exists instead of failing.
     * :returns: a sixteen-character zero-padded transaction id not currently in use,
     *  preserving the ``TRAN-ID PIC X(16)`` wire format (AAP 0.6.5).
     * :raises CardDemoException: with the frozen ``COTRN02C`` message when no unused id
     *  is found within the probe bound, rather than silently overwriting a row.
     */
    private String nextUnusedTransactionId() {
        for (int probe = 0; probe < MAX_TRAN_ID_PROBES; probe++) {
            String candidate = String.format(
                    "%0" + TRAN_ID_WIDTH + "d", transactionRepository.getNextTransactionId());
            if (!transactionRepository.existsById(candidate)) {
                return candidate;
            }
            log.warn("Generated transaction id {} is already posted; advancing the sequence",
                    candidate);
        }
        throw new CardDemoException(MSG_TRAN_ID_EXISTS);
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
        return resolveCrossReference(request.getAcctId(), request.getTranCardNum());
    }

    /**
     * :purpose: Run ``COTRN02C VALIDATE-INPUT-KEY-FIELDS`` against the two raw key
     *  entries. The paragraph is an ``EVALUATE`` whose first branch wins, so a supplied
     *  account id takes priority over a supplied card number, and the branch that runs
     *  performs the cross-reference read that yields the counterpart key. That read being
     *  INSIDE this paragraph -- which runs before ``VALIDATE-INPUT-DATA-FIELDS`` -- is what
     *  makes a key that is not on file the first thing the screen reports.
     * :param acctIdRaw: the ``ACTIDIN`` entry, or ``null``.
     * :param cardNumRaw: the ``CARDNIN`` entry, or ``null``.
     * :returns: the resolved {@link CardXref} linking card, customer, and account.
     * :raises CardDemoException: when a key is non-numeric or neither key is present.
     * :raises RecordNotFoundException: when the account or card cross-reference is
     *  not found.
     */
    private CardXref resolveCrossReference(String acctIdRaw, String cardNumRaw) {
        String acctIdIn = trimToNull(acctIdRaw);
        String cardIn = trimToNull(cardNumRaw);
        if (acctIdIn != null) {
            // COTRN02C L197-L201: `IF ACTIDINI OF COTRN2AI IS NOT NUMERIC` on an eleven-column
            // field, so the run must be exactly eleven ASCII digits — a class test on an
            // alphanumeric item is true only when every character is a digit, and BMS
            // blank-pads what the operator did not type. The exact width is also what keeps
            // `Long.valueOf` in range: a longer run overflowed and surfaced as a 500.
            if (!isNumeric(acctIdIn) || acctIdIn.length() != ACCT_ID_WIDTH) {
                throw new CardDemoException(MSG_ACCT_NUMERIC);
            }
            Long acctId = Long.valueOf(acctIdIn);
            return cardXrefRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(acctId)
                    .orElseThrow(() -> new RecordNotFoundException(MSG_ACCT_NOT_FOUND));
        }
        if (cardIn != null) {
            // COTRN02C's card edit is the same class test on the sixteen-column `CARDNIN`.
            if (!isNumeric(cardIn) || cardIn.length() != CARD_NUM_WIDTH) {
                throw new CardDemoException(MSG_CARD_NUMERIC);
            }
            return cardXrefRepository.findByXrefCardNum(cardIn)
                    .orElseThrow(() -> new RecordNotFoundException(MSG_CARD_NOT_FOUND));
        }
        throw new CardDemoException(MSG_ACCT_OR_CARD);
    }

    /**
     * :purpose: Resolve the add screen's two key fields from either one, re-expressing
     *  the ``COTRN02C VALIDATE-INPUT-KEY-FIELDS`` paragraph on its own. The legacy
     *  program runs that paragraph BEFORE the eleven data-field blank guards, and its
     *  cross-reference read is what publishes ``Account ID NOT found...`` /
     *  ``Card Number NOT found...``; running it as its own step is what keeps those two
     *  literals reachable when a data field is still empty, and it is also the first
     *  thing ``COPY-LAST-TRAN-DATA`` performs.
     * :param request: the key entry, carrying an account id, a card number, or neither.
     * :returns: both keys, each in its declared width — the account id zero-padded to
     *  eleven digits and the card number at its stored sixteen.
     * :raises CardDemoException: when a key is non-numeric or neither key is present.
     * :raises RecordNotFoundException: when the cross-reference holds no such key.
     */
    public TransactionKeyDto resolveAddKey(TransactionKeyDto request) {
        String acctIdRaw = request == null ? null : request.getAcctId();
        String cardNumRaw = request == null ? null : request.getTranCardNum();
        CardXref xref = resolveCrossReference(acctIdRaw, cardNumRaw);

        TransactionKeyDto resolved = new TransactionKeyDto();
        resolved.setAcctId(String.format("%0" + ACCT_ID_WIDTH + "d", xref.getXrefAcctId()));
        resolved.setTranCardNum(xref.getXrefCardNum());
        log.debug("resolveAddKey matched a cross-reference entry");
        return resolved;
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
     * :purpose: Report whether an amount fits the ``-99999999.99`` screen format exactly:
     *  at most eight integer digits and at most two decimal places (COTRN02C L339-351 tests
     *  the sign, ``(2:8)`` numeric, the ``.`` in position ten and ``(11:2)`` numeric, so a
     *  third decimal digit simply has nowhere to go on the map).
     * :param amount: the amount to test; may be ``null``.
     * :returns: ``true`` when the amount is present, its magnitude does not exceed
     *  ``99999999.99``, and it carries no more than two decimal places.
     */
    private static boolean matchesAmountFormat(BigDecimal amount) {
        if (amount == null) {
            return false;
        }
        if (amount.abs().compareTo(AMOUNT_MAX) > 0) {
            return false;
        }
        // Trailing zeros are only presentation ("1.500" is the same value as "1.50"), so they
        // are stripped before the significant scale is measured; 1.005 keeps scale 3 and is
        // rejected rather than silently rounded to 1.01, which would alter a financial value
        // the caller never authorised (AAP 0.7.6).
        return amount.stripTrailingZeros().scale() <= MONEY_SCALE_MAX;
    }

    /**
     * :purpose: Report whether a data-integrity violation is a unique/primary-key violation
     *  naming the transaction id, i.e. the only integrity failure the legacy ``DUPKEY``
     *  handling covers (``COTRN02C`` "Tran ID already exist...").
     * :param ex: the violation raised by the persistence provider.
     * :returns: ``true`` when a chained {@link SQLException} reports SQL state ``23505`` for
     *  the transaction primary key.
     */
    private static boolean isTransactionIdUniqueViolation(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlCause
                    && SQLSTATE_UNIQUE_VIOLATION.equals(sqlCause.getSQLState())) {
                return namesTransactionId(sqlCause.getMessage());
            }
        }
        // No driver exception is chained (a provider that reports only its own text): the
        // reported wording must then say BOTH that the failure was a duplicate/unique key AND
        // that the transaction id is the key involved, so a not-null or check violation
        // mentioning the column is never mistaken for a duplicate id.
        String reported = chainedMessages(ex);
        return (reported.contains("duplicate key") || reported.contains("unique constraint"))
                && namesTransactionId(reported);
    }

    /**
     * :purpose: Report whether a driver message names the transaction primary key.
     * :param message: the message to inspect; may be ``null``.
     * :returns: ``true`` when the transaction primary key or its column is named.
     */
    private static boolean namesTransactionId(String message) {
        String text = String.valueOf(message).toLowerCase(Locale.ROOT);
        return text.contains("transactions_pkey") || text.contains("tran_id");
    }

    /**
     * :purpose: Concatenate every message in an exception chain, lower-cased, for text
     *  inspection when no SQL state is available.
     * :param throwable: the head of the chain.
     * :returns: the concatenated, lower-cased messages.
     */
    private static String chainedMessages(Throwable throwable) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            text.append(String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT)).append(' ');
        }
        return text.toString();
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

    /** :purpose: Navigation action requesting the previous page (COBOL ``DFHPF7``). */
    /**
     * :purpose: Left-pad a digit string to the stored key width without parsing it. The
     *  key is a fixed-width digit string, so padding is a string operation; parsing it
     *  into an integral type would impose that type's range on a field the caller
     *  controls, which is how a long enough digit string became a 500 instead of a
     *  browse that simply finds nothing.
     * :param digits: a string of ASCII digits.
     * :returns: the value left-padded with zeros to the key width, unchanged when it is
     *  already at least that wide.
     */
    private static String leftPadZeros(String digits) {
        if (digits.length() >= TRAN_ID_WIDTH) {
            return digits;
        }
        StringBuilder padded = new StringBuilder(TRAN_ID_WIDTH);
        for (int i = digits.length(); i < TRAN_ID_WIDTH; i++) {
            padded.append('0');
        }
        return padded.append(digits).toString();
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
            String previous = decrementDigits(paddedId);
            if (previous == null) {
                return LOW_SENTINEL;
            }
            return previous;
        }
        return LOW_SENTINEL;
    }

    /**
     * :purpose: Subtract one from a fixed-width digit string, in the string domain. Keys
     *  are zero-padded to a constant width, so their lexicographic order is their
     *  numeric order and the predecessor of a key is obtained by borrowing from the
     *  right. Working on the digits keeps a key wider than any integral type from
     *  overflowing: such a value is greater than every stored key, and its predecessor
     *  is still greater than every stored key, so the browse correctly finds nothing.
     * :param digits: a string of ASCII digits.
     * :returns: the digit string one less than the input at the same width, or ``null``
     *  when the input is zero and has no predecessor.
     */
    private static String decrementDigits(String digits) {
        char[] borrowed = digits.toCharArray();
        int position = borrowed.length - 1;
        while (position >= 0 && borrowed[position] == '0') {
            borrowed[position] = '9';
            position--;
        }
        if (position < 0) {
            return null;
        }
        borrowed[position] = (char) (borrowed[position] - 1);
        return new String(borrowed);
    }

    /**
     * :purpose: Normalize a monetary value to the COBOL ``S9(09)V99`` fixed scale of
     *  two decimal places using {@link RoundingMode#DOWN}, so excess fraction digits
     *  are truncated toward zero exactly as an unrounded COBOL ``MOVE`` into a
     *  ``V99`` receiver drops them.
     * :param value: the amount to normalize; may be ``null``.
     * :returns: the value at scale two, or ``null`` when the value is ``null``.
     */
    private static BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.DOWN);
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

    /** :purpose: Navigation action requesting the previous page (COBOL ``DFHPF7``). */
    /**
     * :purpose: ``IF <field> EQUAL LOW-VALUES OR EQUAL SPACES`` — the "not supplied" test
     *  a filter edit opens with. An untouched map field arrives as LOW-VALUES, whose wire
     *  analogue is an absent or empty parameter, and a blanked one arrives as SPACES.
     *  Nothing else is "not supplied": a tab is neither, so it falls through to the
     *  numeric edit exactly as it does in the source.
     * :param value: the raw filter value; may be ``null``.
     * :returns: ``true`` when the filter was not supplied.
     */
    private static boolean isNotSupplied(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * :purpose: COBOL ``IS NUMERIC`` against a ``PIC X(n)`` field: every position must
     *  hold one of ``0``..``9``. Unlike {@link #isNumeric(String)} this does not trim, so
     *  a surrounding space fails the class test as it does in the source.
     * :param value: the raw filter value; may be ``null``.
     * :returns: ``true`` when every character is an ASCII digit and there is at least one.
     */
    private static boolean isAllDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!isAsciiDigit(value, i)) {
                return false;
            }
        }
        return true;
    }

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
