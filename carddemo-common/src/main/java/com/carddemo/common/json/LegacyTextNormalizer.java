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
package com.carddemo.common.json;

import java.text.Normalizer;

/**
 * :purpose: Reduce a text value to what a fixed-width ``PIC X(n)`` field can hold and a
 *     3270 device can paint: one canonical composition, and no character that occupies a
 *     position while painting nothing.
 * :output: The named ``normalize`` function.
 * :note: Logic only — no I/O, no framework types, no state.
 */
public final class LegacyTextNormalizer {

    /**
     * :purpose: Non-instantiable helper holder.
     */
    private LegacyTextNormalizer() {
    }

    /**
     * :purpose: Canonicalise a text value and drop every Unicode FORMAT character from it.
     * :param value: the incoming text; may be ``null``.
     * :returns: the value with canonical composition applied and every format character
     *     removed, ``null`` for ``null``, and the SAME instance when there was nothing to
     *     change — which is the common case, so a clean value costs one scan and no
     *     allocation.
     */
    public static String normalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String composed = Normalizer.isNormalized(value, Normalizer.Form.NFC)
                ? value
                : Normalizer.normalize(value, Normalizer.Form.NFC);
        return stripFormatCharacters(composed);
    }

    /**
     * :purpose: Remove every character of Unicode general category ``Cf`` (FORMAT).
     * :param value: the composed text; never ``null`` and never empty.
     * :returns: the text without its format characters, or the same instance when it had
     *     none.
     */
    private static String stripFormatCharacters(String value) {
        int firstOffender = indexOfFormatCharacter(value);
        if (firstOffender < 0) {
            return value;
        }
        StringBuilder kept = new StringBuilder(value.length());
        kept.append(value, 0, firstOffender);
        int index = firstOffender;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            int width = Character.charCount(codePoint);
            if (Character.getType(codePoint) != Character.FORMAT) {
                kept.appendCodePoint(codePoint);
            }
            index += width;
        }
        return kept.toString();
    }

    /**
     * :purpose: Locate the first format character, so an untouched value is returned as it
     *     arrived.
     * :param value: the text to scan.
     * :returns: the index of the first format character, or ``-1`` when there is none.
     */
    private static int indexOfFormatCharacter(String value) {
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            if (Character.getType(codePoint) == Character.FORMAT) {
                return index;
            }
            index += Character.charCount(codePoint);
        }
        return -1;
    }
}
