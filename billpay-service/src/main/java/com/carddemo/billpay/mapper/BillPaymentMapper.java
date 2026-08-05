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
package com.carddemo.billpay.mapper;

import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Transaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * :purpose: Stateless, hand-written helper that assembles the fixed-value
 *   bill-payment {@link Transaction} defined by the legacy CICS program
 *   ``COBIL00C`` (transaction ``CB00``). It reproduces the record-assembly
 *   ``MOVE`` statements of ``COBIL00C PROCESS-ENTER-KEY`` verbatim and stamps the
 *   26-character origination and processing timestamps in the ``CSDAT01Y``
 *   ``WS-TIMESTAMP`` wire format.
 * :output: New, unpersisted {@link Transaction} instances populated with the
 *   frozen bill-payment field values, the account balance being paid in full and
 *   the current timestamp. Assembly only: this component performs no persistence,
 *   no repository access, no transaction demarcation and no balance mutation;
 *   writing the transaction, decrementing the balance and updating the account
 *   remain ``com.carddemo.billpay.service`` responsibilities.
 */
@Component
public class BillPaymentMapper {

    /** ``MOVE '02' TO TRAN-TYPE-CD`` — bill-payment transaction type code. */
    private static final String TRAN_TYPE_CD = "02";

    /** ``MOVE 2 TO TRAN-CAT-CD`` — bill-payment transaction category code. */
    private static final Integer TRAN_CAT_CD = 2;

    /** ``MOVE 'POS TERM' TO TRAN-SOURCE`` — transaction origination source. */
    private static final String TRAN_SOURCE = "POS TERM";

    /** ``MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC`` — transaction description. */
    private static final String TRAN_DESC = "BILL PAYMENT - ONLINE";

    /** ``MOVE 999999999 TO TRAN-MERCHANT-ID`` — fixed bill-payment merchant id. */
    private static final Long MERCHANT_ID = 999999999L;

    /** ``MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME`` — fixed merchant name. */
    private static final String MERCHANT_NAME = "BILL PAYMENT";

    /** ``MOVE 'N/A' TO TRAN-MERCHANT-CITY`` — fixed merchant city. */
    private static final String MERCHANT_CITY = "N/A";

    /** ``MOVE 'N/A' TO TRAN-MERCHANT-ZIP`` — fixed merchant zip. */
    private static final String MERCHANT_ZIP = "N/A";

    /** Monetary scale for ``TRAN-AMT`` (``S9(09)V99`` -> ``NUMERIC(11,2)``). */
    private static final int AMOUNT_SCALE = 2;

    /**
     * Date-and-time portion of the ``CSDAT01Y WS-TIMESTAMP`` layout: ``YYYY-MM-DD``
     * then a single space then ``HH:MM:SS`` with a colon time separator.
     */
    private static final DateTimeFormatter BILLPAY_TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Fixed microsecond suffix of ``WS-TIMESTAMP`` — ``COBIL00C
     * GET-CURRENT-TIMESTAMP`` moves zeros into ``WS-TIMESTAMP-TM-MS6``.
     */
    private static final String TS_MICROS_SUFFIX = ".000000";

    /** Clock supplying the current instant; injected so callers can freeze time. */
    private final Clock clock;

    /**
     * Create a mapper backed by the system default-zone clock.
     *
     * :purpose: Default constructor used by the Spring container to register the
     *   mapper as a singleton bean.
     */
    public BillPaymentMapper() {
        this(Clock.systemDefaultZone());
    }

    /**
     * Create a mapper backed by the supplied clock.
     *
     * :param clock: the clock supplying the current instant for timestamp
     *   generation; must be non-null.
     */
    public BillPaymentMapper(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Assemble the bill-payment transaction record from the account being paid and its
     * card cross-reference, using the fixed field values defined by COBIL00C
     * PROCESS-ENTER-KEY. Assembly only: this method neither persists the transaction
     * nor mutates the account balance.
     *
     * :param account: the account whose balance is being paid in full (must be non-null,
     *                 fully populated; supplies the transaction amount).
     * :param cardXref: the card cross-reference (CXACAIX) for the account (must be non-null;
     *                  supplies the card number).
     * :return: a new, unpersisted Transaction populated with the fixed bill-payment values
     *          and the current timestamp; the transaction id is left unset for the
     *          persistence layer's sequence strategy to assign.
     */
    public Transaction toBillPaymentTransaction(Account account, CardXref cardXref) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(cardXref, "cardXref");

        Transaction transaction = new Transaction();
        transaction.setTranTypeCd(TRAN_TYPE_CD);
        transaction.setTranCatCd(TRAN_CAT_CD);
        transaction.setTranSource(TRAN_SOURCE);
        transaction.setTranDesc(TRAN_DESC);
        transaction.setTranAmt(billPaymentAmount(account.getAcctCurrBal()));
        transaction.setTranCardNum(cardXref.getXrefCardNum());
        transaction.setTranMerchantId(MERCHANT_ID);
        transaction.setTranMerchantName(MERCHANT_NAME);
        transaction.setTranMerchantCity(MERCHANT_CITY);
        transaction.setTranMerchantZip(MERCHANT_ZIP);

        String timestamp = currentBillPaymentTimestamp();
        transaction.setTranOrigTs(timestamp);
        transaction.setTranProcTs(timestamp);

        return transaction;
    }

    /**
     * Assemble the bill-payment transaction and assign a pre-generated transaction id.
     * Convenience overload for services that obtain the id from the persistence sequence
     * before assembly; delegates to {@link #toBillPaymentTransaction(Account, CardXref)}
     * and then sets the id.
     *
     * :param account: the account whose balance is being paid in full (must be non-null,
     *                 fully populated).
     * :param cardXref: the card cross-reference (CXACAIX) for the account (must be non-null).
     * :param tranId: the 16-character, zero-padded transaction id to assign; when ``null``
     *                the returned transaction's id is left unset.
     * :return: a new, unpersisted Transaction populated with the fixed bill-payment values,
     *          the current timestamp and the supplied transaction id.
     */
    public Transaction toBillPaymentTransaction(Account account, CardXref cardXref, String tranId) {
        Transaction transaction = toBillPaymentTransaction(account, cardXref);
        transaction.setTranId(tranId);
        return transaction;
    }

    /**
     * Normalize the account balance to the transaction-amount scale.
     *
     * :param acctCurrBal: the current account balance being paid in full (must be non-null).
     * :return: the balance at scale 2, truncated toward zero; a value-preserving
     *          normalization of the already-``NUMERIC(12,2)`` balance onto the
     *          ``NUMERIC(11,2)`` transaction amount.
     */
    private BigDecimal billPaymentAmount(BigDecimal acctCurrBal) {
        Objects.requireNonNull(acctCurrBal, "acctCurrBal");
        return acctCurrBal.setScale(AMOUNT_SCALE, RoundingMode.DOWN);
    }

    /**
     * Build the current timestamp string in the ``CSDAT01Y WS-TIMESTAMP`` wire format.
     *
     * :return: a 26-character timestamp of the form ``yyyy-MM-dd HH:mm:ss.000000`` — a
     *          single space between the date and time, colon time separators and the fixed
     *          zeroed microsecond suffix — matching ``COBIL00C GET-CURRENT-TIMESTAMP``.
     */
    private String currentBillPaymentTimestamp() {
        return LocalDateTime.now(clock).format(BILLPAY_TS_FORMATTER) + TS_MICROS_SUFFIX;
    }
}
