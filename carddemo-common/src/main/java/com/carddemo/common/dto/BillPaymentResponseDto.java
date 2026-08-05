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
 *  generated transaction id (present only on a posted payment) and the message.
 */
public class BillPaymentResponseDto {

    /** :purpose: Account id echoed back to the screen (COBIL00 ``ACTIDIN`` / ``ACCT-ID`` PIC X(11)). */
    private String accountId;

    /** :purpose: Account balance to display (COBIL00 ``CURBAL`` bound to ``ACCT-CURR-BAL`` NUMERIC(12,2)). */
    @JsonSerialize(using = CobolNumberSerializers.Money.class)
    private BigDecimal currentBalance;

    /** :purpose: Generated 16-character zero-padded transaction id of a posted payment (``TRAN-ID`` PIC X(16)); null on preview/cancel. */
    private String transactionId;

    /** :purpose: Verbatim user-facing message (COBIL00C confirm prompt, success banner, or blank on cancel). */
    private String message;

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
}
