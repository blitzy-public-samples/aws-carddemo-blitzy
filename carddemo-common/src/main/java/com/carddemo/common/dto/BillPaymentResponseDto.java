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
package com.carddemo.common.dto;

import java.math.BigDecimal;
import com.carddemo.common.json.CobolNumberSerializers;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * :purpose: Outbound response DTO for the COBIL00 bill-payment screen (CICS
 *  transaction ``CB00``, legacy program ``COBIL00C``). Echoes the paid account and
 *  its balance and, on a confirmed payment, the generated transaction id and the
 *  verbatim ``COBIL00C`` user-facing message, mirroring the ``COBIL00`` output map
 *  ``COBIL0AO`` and the program's confirm/preview/success paths.
 * :output: A mutable carrier with the account id, the balance to display, the
 *  generated transaction id (present only on a posted payment) and the message, on
 *  whichever of the two channels the outcome selects (see the member docstrings).
 */
public class BillPaymentResponseDto {

    /** :purpose: Account id echoed back to the screen (COBIL00 ``ACTIDIN`` / ``ACCT-ID`` PIC X(11)). */
    private String accountId;

    /** :purpose: Account balance to display (COBIL00 ``CURBAL`` bound to ``ACCT-CURR-BAL`` NUMERIC(12,2)). */
    @JsonSerialize(using = CobolNumberSerializers.Money.class)
    private BigDecimal currentBalance;

    /** :purpose: Generated 16-character zero-padded transaction id of a posted payment (``TRAN-ID`` PIC X(16)); null on preview/cancel. */
    private String transactionId;

    /**
     * :purpose: Verbatim user-facing message of a turn that reached the account: the
     *  confirm prompt ``Confirm to make a bill payment...`` (COBIL00C L237-239) or the
     *  payment acknowledgement ``Payment successful.  Your Transaction ID is <id>.``
     *  (L522-533). Blank on cancel.
     * :note: The two are told apart by :attr:`transactionId`, which only the
     *  acknowledgement carries -- never by reading the text. That also settles the
     *  colour: L526 ``MOVE DFHGREEN TO ERRMSGC`` is the ONE site in the program that
     *  overrides the declared ``ERRMSG COLOR=RED``, and it sits on that same
     *  acknowledgement branch, so a populated ``message`` WITH a transaction id is the
     *  GREEN send and WITHOUT one is a RED send.
     */
    private String message;

    /**
     * :purpose: Verbatim user-facing message for an outcome the screen refuses while
     *  still redisplaying its data and returning the cursor to ``ACTIDIN`` -- currently
     *  only ``You have nothing to pay...`` (COBIL00C L200-203).
     * :note: ``COBIL00`` declares ONE message field, ``ERRMSG POS=(23,1) COLOR=RED``,
     *  and ``COBIL00C`` overrides its colour at exactly one site, ``MOVE DFHGREEN TO
     *  ERRMSGC`` on the successful-payment branch (L526). The three in-band outcomes
     *  therefore differ in colour AND in cursor target, and this member is what
     *  separates the one that keeps the cursor on the account id from the two that
     *  reached the account (see :attr:`message`): the refusal cursors ``ACTIDIN``
     *  (L203) whereas the confirm prompt cursors ``CONFIRM`` (L239). Folding the prompt
     *  in here would have forced the client to re-derive which outcome it was from the
     *  balance. At most one of the two members is ever populated.
     */
    private String errorMessage;

    /**
     * :purpose: Create an empty response. Required for JSON (Jackson) serialization.
     */
    public BillPaymentResponseDto() {
    }

    /**
     * :purpose: Create a response carrying the account id, balance, transaction id and message.
     * :param accountId: the account id echoed back to the screen.
     * :param currentBalance: the account balance to display.
     * :param transactionId: the generated transaction id, or null when none was posted.
     * :param message: the verbatim user-facing message.
     */
    public BillPaymentResponseDto(String accountId, BigDecimal currentBalance,
                                  String transactionId, String message) {
        this.accountId = accountId;
        this.currentBalance = currentBalance;
        this.transactionId = transactionId;
        this.message = message;
    }

    /**
     * :purpose: Return the account id echoed back to the screen.
     * :output: the ``accountId`` value.
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * :purpose: Set the account id echoed back to the screen.
     * :param accountId: the ``accountId`` value.
     */
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    /**
     * :purpose: Return the account balance to display.
     * :output: the ``currentBalance`` value as a {@link BigDecimal}.
     */
    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    /**
     * :purpose: Set the account balance to display.
     * :param currentBalance: the ``currentBalance`` value as a {@link BigDecimal}.
     */
    public void setCurrentBalance(BigDecimal currentBalance) {
        this.currentBalance = currentBalance;
    }

    /**
     * :purpose: Return the generated transaction id of a posted payment.
     * :output: the ``transactionId`` value, or null when none was posted.
     */
    public String getTransactionId() {
        return transactionId;
    }

    /**
     * :purpose: Set the generated transaction id of a posted payment.
     * :param transactionId: the ``transactionId`` value.
     */
    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    /**
     * :purpose: Return the verbatim user-facing message.
     * :output: the ``message`` value.
     */
    public String getMessage() {
        return message;
    }

    /**
     * :purpose: Set the verbatim user-facing message.
     * :param message: the ``message`` value.
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * :purpose: Return the verbatim message of an outcome reported as an error.
     * :output: the ``errorMessage`` value.
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * :purpose: Set the verbatim message of an outcome reported as an error.
     * :param errorMessage: the ``errorMessage`` value.
     */
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
