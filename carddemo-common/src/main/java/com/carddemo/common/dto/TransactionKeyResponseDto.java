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

/**
 * :purpose: Outbound response DTO for the key-field edit of the COTRN02 add-transaction
 *  screen (CICS transaction ``CT02``, legacy program ``COTRN02C``).
 *  ``VALIDATE-INPUT-KEY-FIELDS`` reads the card cross-reference before any data field is
 *  examined, and it writes the counterpart key back onto the map — ``MOVE XREF-CARD-NUM
 *  TO CARDNINI`` after a read by account, ``MOVE XREF-ACCT-ID TO ACTIDINI`` after a read
 *  by card. This carries that resolved pair back to the screen so the same edit runs in
 *  the same order, and so the key the operator did not supply appears as the source
 *  fills it in.
 * :output: A mutable carrier with the resolved account id and card number.
 */
public class TransactionKeyResponseDto {

    /** :purpose: The resolved account id (``XREF-ACCT-ID`` PIC 9(11)) as an 11-digit string. */
    private String acctId;

    /** :purpose: The resolved card number (``XREF-CARD-NUM`` PIC X(16)). */
    private String tranCardNum;

    /**
     * :purpose: Create an empty response. Required for JSON (Jackson) serialization.
     */
    public TransactionKeyResponseDto() {
    }

    /**
     * :purpose: Create a response carrying the resolved key pair.
     * :param acctId: the resolved 11-digit account id.
     * :param tranCardNum: the resolved 16-character card number.
     */
    public TransactionKeyResponseDto(String acctId, String tranCardNum) {
        this.acctId = acctId;
        this.tranCardNum = tranCardNum;
    }

    /**
     * :purpose: Return the resolved account id.
     * :output: the ``acctId`` value.
     */
    public String getAcctId() {
        return acctId;
    }

    /**
     * :purpose: Set the resolved account id.
     * :param acctId: the 11-digit account id.
     */
    public void setAcctId(String acctId) {
        this.acctId = acctId;
    }

    /**
     * :purpose: Return the resolved card number.
     * :output: the ``tranCardNum`` value.
     */
    public String getTranCardNum() {
        return tranCardNum;
    }

    /**
     * :purpose: Set the resolved card number.
     * :param tranCardNum: the 16-character card number.
     */
    public void setTranCardNum(String tranCardNum) {
        this.tranCardNum = tranCardNum;
    }
}
