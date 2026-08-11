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
package com.carddemo.common.json;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * :purpose: Render COBOL numeric field values in their declared display form so the JSON
 *  wire representation is byte-identical to what the legacy 3270 field carried.
 *  A ``PIC 9(n)`` identifier keeps its full width and leading zeros, and a
 *  ``PIC S9(p)V99`` amount keeps its exact scale.
 * :output: The static ``fixedWidth`` and ``scaled`` formatting helpers plus the
 *  ``PIC`` width constants the DTO serializers bind to.
 */
public final class CobolWireFormat {

    /** :purpose: Width of ``ACCT-ID`` / ``CARD-ACCT-ID`` / ``XREF-ACCT-ID`` (``PIC 9(11)``). */
    public static final int ACCOUNT_ID_WIDTH = 11;

    /** :purpose: Width of ``CUST-ID`` / ``XREF-CUST-ID`` (``PIC 9(09)``). */
    public static final int CUSTOMER_ID_WIDTH = 9;

    /** :purpose: Width of ``TRAN-MERCHANT-ID`` (``PIC 9(09)``). */
    public static final int MERCHANT_ID_WIDTH = 9;

    /** :purpose: Width of ``TRAN-CAT-CD`` (``PIC 9(04)``). */
    public static final int TRAN_CATEGORY_WIDTH = 4;

    /** :purpose: Width of ``CUST-FICO-CREDIT-SCORE`` (``PIC 9(03)``). */
    public static final int FICO_SCORE_WIDTH = 3;

    /** :purpose: Scale of every CardDemo monetary field (``V99``). */
    public static final int MONEY_SCALE = 2;

    /**
     * :purpose: Prevent instantiation of this utility class.
     */
    private CobolWireFormat() {
    }

    /**
     * :purpose: Format an unsigned COBOL identifier as a zero-padded decimal string of
     *  its declared ``PIC 9(n)`` width. A value already at or above the declared width
     *  is emitted unpadded rather than truncated, so no digit is ever lost.
     * :param value: the identifier value.
     * :param width: the declared ``PIC 9(n)`` width.
     * :returns: the zero-padded decimal string.
     */
    public static String fixedWidth(long value, int width) {
        String digits = Long.toString(value);
        if (digits.length() >= width) {
            return digits;
        }
        StringBuilder padded = new StringBuilder(width);
        padded.repeat('0', width - digits.length());
        return padded.append(digits).toString();
    }

    /**
     * :purpose: Format a monetary value at the exact COBOL scale, in plain (never
     *  exponential) notation, so the transmitted digits match the packed-decimal field.
     *  The value is only ever extended to the declared scale; a stored value that
     *  already carries more precision is preserved rather than rounded away, which keeps
     *  the transfer non-lossy.
     * :param value: the monetary value.
     * :returns: the plain-string form at no less than the declared scale.
     */
    public static String scaled(BigDecimal value) {
        BigDecimal atScale = value.scale() < MONEY_SCALE
                ? value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY)
                : value;
        return atScale.toPlainString();
    }
}
