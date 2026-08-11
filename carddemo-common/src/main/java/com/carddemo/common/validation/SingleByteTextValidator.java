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

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * :purpose: Validate {@link SingleByteText} by inspecting every ``String`` field of the
 *  annotated request object.
 * :output: ``true`` when every text field is representable in ISO-8859-1; otherwise
 *  ``false`` with one violation bound to the offending field, so the caller is told
 *  WHICH field it must correct.
 * :note: Reflection over declared fields, walking the class hierarchy, rather than a
 *  hand-written list: the constraint has to hold for every text property the request
 *  carries, and a list is a thing that goes out of date silently. Static and synthetic
 *  fields are skipped, and a field is read through ``setAccessible`` because the DTOs
 *  keep their state private behind accessors.
 * :note: Only the FIRST offending field is reported. The legacy screens edited one
 *  field at a time and reported one message, and every other validator in this codebase
 *  reproduces that; reporting the whole set here would be the only place that did not.
 */
public class SingleByteTextValidator implements ConstraintValidator<SingleByteText, Object> {

    /** Highest code point ISO-8859-1 represents as a single byte. */
    private static final int MAX_SINGLE_BYTE_CODE_POINT = 0xFF;

    /**
     * :purpose: Check every text field of the value under validation.
     * :param value: the request object; ``null`` is valid (absence is another
     *  constraint's concern).
     * :param context: the validation context, used to bind the violation to the
     *  offending property.
     * :returns: ``true`` when no text field carries an unrepresentable character.
     */
    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        for (Class<?> type = value.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != String.class
                        || Modifier.isStatic(field.getModifiers())
                        || field.isSynthetic()) {
                    continue;
                }
                String text = readField(field, value);
                int offendingIndex = firstUnrepresentableIndex(text);
                if (offendingIndex >= 0) {
                    context.disableDefaultConstraintViolation();
                    context.buildConstraintViolationWithTemplate(
                                    context.getDefaultConstraintMessageTemplate())
                            .addPropertyNode(field.getName())
                            .addConstraintViolation();
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * :purpose: Read a private field's value without imposing an accessor convention on
     *  the annotated request types.
     * :param field: the declared field.
     * :param target: the object under validation.
     * :returns: the field's value, or ``null`` when it cannot be read.
     */
    private static String readField(Field field, Object target) {
        try {
            field.setAccessible(true);
            return (String) field.get(target);
        } catch (IllegalAccessException | RuntimeException e) {
            // An unreadable field cannot be judged, and refusing the whole request for
            // that reason would turn a reflection restriction into a business rejection.
            return null;
        }
    }

    /**
     * :purpose: Find the first character of a value that ISO-8859-1 cannot represent.
     * :param text: the value to inspect; ``null`` and empty are representable.
     * :returns: the index of the first offending character, or ``-1`` when there is none.
     */
    private static int firstUnrepresentableIndex(String text) {
        if (text == null) {
            return -1;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > MAX_SINGLE_BYTE_CODE_POINT) {
                return i;
            }
        }
        return -1;
    }
}
