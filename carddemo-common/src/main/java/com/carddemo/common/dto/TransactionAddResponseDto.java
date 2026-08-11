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
 * :purpose: Outbound response DTO for the COTRN02 add-transaction screen (CICS
 *  transaction ``CT02``, legacy program ``COTRN02C``). Returns the identity of the
 *  newly posted transaction and the verbatim confirmation banner the legacy program
 *  built with its ``STRING`` statement, so the client can display the exact message
 *  and route by the generated id.
 * :output: A mutable carrier with the generated 16-character transaction id and the
 *  confirmation message.
 */
public class TransactionAddResponseDto {

    /** :purpose: The generated 16-character zero-padded transaction id (COTRN02 ``TRAN-ID`` PIC X(16)). */
    private String tranId;

    /** :purpose: The confirmation message (COTRN02 ``WS-MESSAGE`` success ``STRING``). */
    private String message;

    /**
     * :purpose: Create an empty response. Required for JSON (Jackson) serialization.
     */
    public TransactionAddResponseDto() {
    }

    /**
     * :purpose: Create a response carrying the generated id and confirmation message.
     * :param tranId: the generated 16-character transaction id.
     * :param message: the confirmation message.
     */
    public TransactionAddResponseDto(String tranId, String message) {
        this.tranId = tranId;
        this.message = message;
    }

    /**
     * :purpose: Return the generated transaction id.
     * :output: the ``tranId`` value.
     */
    public String getTranId() {
        return tranId;
    }

    /**
     * :purpose: Set the generated transaction id.
     * :param tranId: the ``tranId`` value.
     */
    public void setTranId(String tranId) {
        this.tranId = tranId;
    }

    /**
     * :purpose: Return the confirmation message.
     * :output: the ``message`` value.
     */
    public String getMessage() {
        return message;
    }

    /**
     * :purpose: Set the confirmation message.
     * :param message: the ``message`` value.
     */
    public void setMessage(String message) {
        this.message = message;
    }
}
