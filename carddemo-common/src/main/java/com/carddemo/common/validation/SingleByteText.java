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
package com.carddemo.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * :purpose: Assert that every text property of a request carries only characters the
 *     downstream fixed-width record interface can represent - one ISO-8859-1 byte each.
 * :output: A validation failure naming the offending property when any ``String`` property
 *     holds a code point outside ISO-8859-1.
 * :note: Declared on the TYPE so it covers every text property of the request, including
 *     ones added later. The alternative - annotating each field - leaves a new field silently
 *     unguarded, and the guarantee this constraint exists for is a property of the whole
 *     record, not of one field.
 * :note: This is a BOUNDARY constraint, and the boundary is where it has to be. The
 *     business tables are UTF-8 and hold such text happily, but the batch deliverables those
 *     rows feed - the 350-byte transaction record, the 500-byte customer record, the statement
 *     files, the combined transaction file - are BYTE-width contracts a downstream consumer
 *     parses by offset (AAP 0.7.6 forbids changing them). Text that cannot be represented in
 *     one byte per character therefore has exactly two possible outcomes downstream: a lost
 *     character, or a record of the wrong length. Refusing it at the point of entry is the
 *     only outcome that keeps both the record and the operator's intent intact.
 */
@Documented
@Constraint(validatedBy = SingleByteTextValidator.class)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface SingleByteText {

    /**
     * :purpose: Validation message used when a character is unrepresentable.
     * :returns: the message template reported when a property carries unrepresentable
     *  text. The offending property name is appended by the validator.
     */
    String message() default "must contain only characters the downstream fixed-width "
            + "record interface can represent (ISO-8859-1)";

    /**
     * :purpose: Validation groups this constraint belongs to.
     * :returns: the validation groups this constraint belongs to.
     */
    Class<?>[] groups() default {};

    /**
     * :purpose: Metadata payload carried by this constraint.
     * :returns: the payload types associated with this constraint.
     */
    Class<? extends Payload>[] payload() default {};
}
