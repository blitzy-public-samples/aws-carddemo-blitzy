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
package com.carddemo.transaction.mapper;

import com.carddemo.common.domain.Transaction;
import com.carddemo.common.dto.TransactionAddRequestDto;
import com.carddemo.common.dto.TransactionListItemDto;
import com.carddemo.common.dto.TransactionViewResponseDto;
import com.carddemo.common.util.DateUtil;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * :purpose: Hand-written, stateless mapper that converts between the
 *   carddemo-common {@link Transaction} entity and the transaction screen DTOs
 *   (``com.carddemo.common.dto``) for the online COTRN00 list (``CT00``), COTRN01
 *   view (``CT01``) and COTRN02 add (``CT02``) flows. Performs pure, deterministic
 *   field mapping only: monetary amounts are normalized to scale 2 (COBOL
 *   ``S9(09)V99``) and the list short date is derived from the 26-character
 *   origination timestamp via {@link DateUtil}. Carries no persistence, id
 *   generation, validation, logging, or other business logic; those
 *   responsibilities belong to ``TransactionService``.
 */
@Component
public class TransactionMapper {

    /** Output formatter for the COTRN00 list short date (COBOL ``WS-TRAN-DATE`` ``MM/DD/YY``). */
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("MM/dd/yy");

    /**
     * Width of the ``TDESC01``..``TDESC10`` list description fields, declared
     * ``LENGTH=26`` in ``app/bms/COTRN00.bms`` and ``PIC X(26)`` in the symbolic map.
     */
    private static final int LIST_DESC_WIDTH = 26;

    /**
     * :purpose: Build the COTRN01 transaction-view response echoing all thirteen
     *   posted transaction fields; the amount is normalized to scale 2 and the
     *   26-character timestamps are passed through unchanged.
     * :param entity: the posted transaction entity to render; may be ``null``.
     * :returns: a populated {@link TransactionViewResponseDto}, or ``null`` when
     *   ``entity`` is ``null``.
     */
    public TransactionViewResponseDto toViewResponse(Transaction entity) {
        if (entity == null) {
            return null;
        }
        TransactionViewResponseDto dto = new TransactionViewResponseDto();
        dto.setTranId(entity.getTranId());
        dto.setTranCardNum(entity.getTranCardNum());
        dto.setTranTypeCd(entity.getTranTypeCd());
        dto.setTranCatCd(entity.getTranCatCd());
        dto.setTranSource(entity.getTranSource());
        dto.setTranAmt(scale2(entity.getTranAmt()));
        dto.setTranDesc(entity.getTranDesc());
        dto.setTranOrigTs(entity.getTranOrigTs());
        dto.setTranProcTs(entity.getTranProcTs());
        dto.setTranMerchantId(entity.getTranMerchantId());
        dto.setTranMerchantName(entity.getTranMerchantName());
        dto.setTranMerchantCity(entity.getTranMerchantCity());
        dto.setTranMerchantZip(entity.getTranMerchantZip());
        return dto;
    }

    /**
     * :purpose: Map a single transaction entity to a COTRN00 list row: the id, the
     *   ``MM/DD/YY`` short date derived from the origination timestamp, the
     *   description, and the scale-2 amount. Page size and paging flags are decided
     *   by the transaction service, not this mapper.
     * :param entity: the posted transaction entity to render as a row; may be
     *   ``null``.
     * :returns: a populated {@link TransactionListItemDto}, or ``null`` when
     *   ``entity`` is ``null``.
     */
    public TransactionListItemDto toListRow(Transaction entity) {
        if (entity == null) {
            return null;
        }
        TransactionListItemDto row = new TransactionListItemDto();
        row.setTranId(entity.getTranId());
        row.setTranDate(formatShortDate(entity.getTranOrigTs()));
        row.setTranDesc(truncateToListDescWidth(entity.getTranDesc()));
        row.setTranAmt(scale2(entity.getTranAmt()));
        return row;
    }

    /**
     * :purpose: Reduce a stored description to the width the list map field holds,
     *   reproducing ``MOVE TRAN-DESC TO TDESC0nI`` where the source is ``PIC X(100)`` and
     *   the target ``PIC X(26)``. The 3270 field is a fixed 26-cell window that cannot
     *   wrap, so the leftmost 26 characters are all the screen ever shows and the row
     *   always occupies exactly one line.
     * :param value: the stored description; may be ``null``.
     * :returns: the leftmost 26 characters, or ``null`` when ``value`` is ``null``.
     */
    private static String truncateToListDescWidth(String value) {
        if (value == null || value.length() <= LIST_DESC_WIDTH) {
            return value;
        }
        return value.substring(0, LIST_DESC_WIDTH);
    }

    /**
     * :purpose: Map a list of transaction entities to COTRN00 list rows, preserving
     *   the input order.
     * :param entities: the transaction entities to render; may be ``null``.
     * :returns: a list of {@link TransactionListItemDto} rows, or an empty list when
     *   ``entities`` is ``null``.
     */
    public List<TransactionListItemDto> toListRows(List<Transaction> entities) {
        if (entities == null) {
            return List.of();
        }
        return entities.stream()
                .map(this::toListRow)
                .collect(Collectors.toList());
    }

    /**
     * :purpose: Build a new {@link Transaction} entity from the COTRN02
     *   add-transaction request, copying the editable fields and normalizing the
     *   amount to scale 2. The transaction id is deliberately left unset: it is
     *   assigned by the persistence layer (identity/sequence), not by this mapper.
     * :param request: the add-transaction request carrying the editable screen
     *   fields; may be ``null``.
     * :returns: a new {@link Transaction} with the copied fields, or ``null`` when
     *   ``request`` is ``null``.
     */
    public Transaction toEntity(TransactionAddRequestDto request) {
        if (request == null) {
            return null;
        }
        Transaction entity = new Transaction();
        // tranId is assigned by the persistence layer (identity/sequence); never set here.
        entity.setTranTypeCd(request.getTranTypeCd());
        entity.setTranCatCd(request.getTranCatCd());
        entity.setTranSource(request.getTranSource());
        entity.setTranDesc(request.getTranDesc());
        entity.setTranAmt(scale2(request.getTranAmt()));
        entity.setTranCardNum(request.getTranCardNum());
        entity.setTranMerchantId(request.getTranMerchantId());
        entity.setTranMerchantName(request.getTranMerchantName());
        entity.setTranMerchantCity(request.getTranMerchantCity());
        entity.setTranMerchantZip(request.getTranMerchantZip());
        entity.setTranOrigTs(request.getTranOrigTs());
        entity.setTranProcTs(request.getTranProcTs());
        return entity;
    }

    /**
     * :purpose: Normalize a monetary value to the COBOL ``S9(09)V99`` fixed scale of
     *   two decimal places using {@link RoundingMode#DOWN}, so excess fraction
     *   digits are truncated toward zero exactly as an unrounded COBOL ``MOVE``
     *   into a ``V99`` receiver drops them.
     * :param value: the amount to normalize; may be ``null``.
     * :returns: the value at scale 2, or ``null`` when ``value`` is ``null``.
     */
    private static BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.DOWN);
    }

    /**
     * :purpose: Render the COTRN00 ``MM/DD/YY`` short date from the first ten
     *   characters (the ``YYYY-MM-DD`` portion) of a 26-character origination
     *   timestamp. Parsing is delegated to {@link DateUtil}; unparseable, null, or
     *   too-short input yields an empty string rather than an error.
     * :param tranOrigTs: the 26-character origination timestamp; may be ``null``.
     * :returns: the ``MM/DD/YY`` date, or an empty string when the timestamp is
     *   null, too short, or not a valid ISO date.
     */
    private static String formatShortDate(String tranOrigTs) {
        if (tranOrigTs == null || tranOrigTs.length() < 10) {
            return "";
        }
        return DateUtil.parse(tranOrigTs.substring(0, 10), DateUtil.MASK_ISO)
                .map(SHORT_DATE::format)
                .orElse("");
    }
}
