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

import jakarta.validation.constraints.Size;

/**
 * :purpose: Inbound request DTO for the COBIL00 bill-payment screen (CICS
 *  transaction ``CB00``, legacy program ``COBIL00C``). Carries the account whose
 *  outstanding balance is to be paid in full together with the operator's Y/N
 *  confirmation, mirroring the two operator-entered fields of the ``COBIL00``
 *  input map ``COBIL0AI``.
 * :output: A mutable carrier with the entered account id and confirmation flag.
 */
public class BillPaymentRequestDto {

    /** :purpose: Account id being paid (COBIL00 ``ACTIDIN`` / ``ACCT-ID`` PIC X(11)). */
    @Size(max = 11)
    private String accountId;

    /**
     * :purpose: Payment confirmation flag (COBIL00 ``CONFIRM`` PIC X(01)). ``Y``/``y``
     *  executes the payment; ``N``/``n``/blank/absent declines it; any other value is
     *  invalid.
     */
    @Size(max = 1)
    private String confirm;

    /**
     * :purpose: Create an empty request. Required for JSON (Jackson) deserialization.
     */
    public BillPaymentRequestDto() {
    }

    /**
     * :purpose: Create a request carrying the account id and confirmation flag.
     * :param accountId: the account id being paid.
     * :param confirm: the Y/N payment confirmation flag.
     */
    public BillPaymentRequestDto(String accountId, String confirm) {
        this.accountId = accountId;
        this.confirm = confirm;
    }

    /**
     * :purpose: Return the account id being paid.
     * :output: the ``accountId`` value.
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * :purpose: Set the account id being paid.
     * :param accountId: the ``accountId`` value.
     */
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    /**
     * :purpose: Return the payment confirmation flag.
     * :output: the ``confirm`` value.
     */
    public String getConfirm() {
        return confirm;
    }

    /**
     * :purpose: Set the payment confirmation flag.
     * :param confirm: the ``confirm`` value.
     */
    public void setConfirm(String confirm) {
        this.confirm = confirm;
    }
}
