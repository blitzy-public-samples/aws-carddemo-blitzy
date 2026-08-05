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
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * :purpose: Hold the Jackson serializers that emit CardDemo numeric DTO fields as JSON
 *  strings in their declared COBOL display form. A JSON number would silently drop the
 *  leading zeros of a ``PIC 9(n)`` identifier and, once parsed by an IEEE-754 client,
 *  the trailing scale digits of a ``PIC S9(p)V99`` amount; a string preserves both.
 * :output: The nested ``AccountId``, ``CustomerId``, ``MerchantId``, ``TranCategoryCode``,
 *  ``FicoScore`` and ``Money`` serializers referenced from ``@JsonSerialize(using = ...)``.
 */
public final class CobolNumberSerializers {

    /**
     * :purpose: Prevent instantiation of this container class.
     */
    private CobolNumberSerializers() {
    }

    /**
     * :purpose: Base serializer for an unsigned COBOL identifier of a fixed ``PIC 9(n)``
     *  width.
     */
    private abstract static class FixedWidthIdSerializer extends ValueSerializer<Number> {

        private final int width;

        /**
         * :purpose: Bind the serializer to a declared identifier width.
         * :param width: the declared ``PIC 9(n)`` width.
         */
        protected FixedWidthIdSerializer(int width) {
            this.width = width;
        }

        /**
         * :purpose: Write the identifier as a zero-padded decimal string.
         * :param value: the identifier value.
         * :param gen: the JSON generator.
         * :param ctxt: the active serialization context.
         */
        @Override
        public void serialize(Number value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString(CobolWireFormat.fixedWidth(value.longValue(), width));
        }
    }

    /**
     * :purpose: Serialize ``ACCT-ID`` / ``CARD-ACCT-ID`` / ``XREF-ACCT-ID`` as an
     *  eleven-digit zero-padded string (``PIC 9(11)``).
     */
    public static final class AccountId extends FixedWidthIdSerializer {

        /**
         * :purpose: Create the account-id serializer.
         */
        public AccountId() {
            super(CobolWireFormat.ACCOUNT_ID_WIDTH);
        }
    }

    /**
     * :purpose: Serialize ``CUST-ID`` / ``XREF-CUST-ID`` as a nine-digit zero-padded
     *  string (``PIC 9(09)``).
     */
    public static final class CustomerId extends FixedWidthIdSerializer {

        /**
         * :purpose: Create the customer-id serializer.
         */
        public CustomerId() {
            super(CobolWireFormat.CUSTOMER_ID_WIDTH);
        }
    }

    /**
     * :purpose: Serialize ``TRAN-MERCHANT-ID`` as a nine-digit zero-padded string
     *  (``PIC 9(09)``).
     */
    public static final class MerchantId extends FixedWidthIdSerializer {

        /**
         * :purpose: Create the merchant-id serializer.
         */
        public MerchantId() {
            super(CobolWireFormat.MERCHANT_ID_WIDTH);
        }
    }

    /**
     * :purpose: Serialize ``TRAN-CAT-CD`` as a four-digit zero-padded string
     *  (``PIC 9(04)``).
     */
    public static final class TranCategoryCode extends FixedWidthIdSerializer {

        /**
         * :purpose: Create the transaction-category-code serializer.
         */
        public TranCategoryCode() {
            super(CobolWireFormat.TRAN_CATEGORY_WIDTH);
        }
    }

    /**
     * :purpose: Serialize ``CUST-FICO-CREDIT-SCORE`` as a three-digit zero-padded string
     *  (``PIC 9(03)``).
     */
    public static final class FicoScore extends FixedWidthIdSerializer {

        /**
         * :purpose: Create the FICO-score serializer.
         */
        public FicoScore() {
            super(CobolWireFormat.FICO_SCORE_WIDTH);
        }
    }

    /**
     * :purpose: Serialize a monetary ``PIC S9(p)V99`` field as a plain string carrying the
     *  exact scale, so a client never re-derives the value through a binary float.
     */
    public static final class Money extends ValueSerializer<BigDecimal> {

        /**
         * :purpose: Write the amount as a plain decimal string at the declared scale.
         * :param value: the monetary value.
         * :param gen: the JSON generator.
         * :param ctxt: the active serialization context.
         */
        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString(CobolWireFormat.scaled(value));
        }
    }
}
