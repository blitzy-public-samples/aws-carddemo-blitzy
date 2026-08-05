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
package com.carddemo.common.batch;

/**
 * :purpose: Keep the fixed-width batch artefacts BYTE-exact for any text the relational
 *  store can hold. Every legacy record layout CardDemo still has to produce is declared
 *  in bytes — ``DALYREJS`` is ``RECFM=F LRECL=430`` [app/jcl/POSTTRAN.jcl],
 *  ``FD-STMTFILE-REC`` is ``PIC X(80)``, ``HTML-FIXED-LN`` is ``PIC X(100)`` and
 *  ``FD-REPTFILE-REC`` is ``PIC X(133)`` — because each field held single-byte EBCDIC
 *  characters. A modern ``VARCHAR`` column holds arbitrary Unicode, so a character count
 *  is no longer a byte count and every downstream reader that parses by offset breaks.
 * :output: Text in which one character is guaranteed to encode to exactly one byte in
 *  ISO-8859-1, the encoding all fixed-width CardDemo writers pin.
 * :note: Two distinct effects are corrected here. Under UTF-8 an accented
 *  character occupies two bytes, so a record grew past its declared length and every
 *  field after it was byte-shifted. Pinning ISO-8859-1 fixes that but not the converse:
 *  a code point outside Latin-1 is unmappable, and a SUPPLEMENTARY code point such as an
 *  emoji is a surrogate PAIR — two Java characters — that the encoder replaces with a
 *  SINGLE byte, so the record came out SHORTER than its declared length. Substituting one
 *  ``?`` per unmappable code point before the value is padded restores the invariant in
 *  both directions.
 * :note: For pure ASCII or Latin-1 content — every legacy fixture and all normal
 *  production data — this is the identity transformation, so no byte of existing output
 *  changes.
 */
public final class FixedWidthText {

    /**
     * :purpose: Substitute used for a code point that ISO-8859-1 cannot represent. It is
     *  the same character the JDK's own encoder emits for an unmappable code point, so the
     *  substitution is visible rather than silent.
     */
    public static final char UNMAPPABLE_SUBSTITUTE = '?';

    /**
     * :purpose: Highest code point ISO-8859-1 can represent as a single byte.
     */
    private static final int MAX_SINGLE_BYTE_CODE_POINT = 0xFF;

    /**
     * :purpose: Prevent instantiation of this stateless helper.
     */
    private FixedWidthText() {
    }

    /**
     * :purpose: Reduce a value to text whose character count equals its ISO-8859-1 byte
     *  count, so a subsequent fixed-width pad or truncate yields exactly the declared
     *  number of BYTES.
     * :param value: the source value; ``null`` is returned unchanged so callers keep their
     *  own null handling (a COBOL ``MOVE`` of an absent value produces spaces or zeros,
     *  which the callers already reproduce).
     * :returns: the value with every code point outside ISO-8859-1 replaced by a single
     *  {@link #UNMAPPABLE_SUBSTITUTE}, or ``null`` when the value was ``null``.
     */
    public static String toSingleByteText(String value) {
        if (value == null) {
            return null;
        }
        boolean needsSubstitution = false;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > MAX_SINGLE_BYTE_CODE_POINT) {
                needsSubstitution = true;
                break;
            }
        }
        if (!needsSubstitution) {
            // Overwhelmingly the common case: ASCII / Latin-1 content is returned as is,
            // so no allocation and no byte of existing output changes.
            return value;
        }
        StringBuilder sanitized = new StringBuilder(value.length());
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            // One output character per CODE POINT: a surrogate pair must not contribute
            // two characters, because ISO-8859-1 encodes the pair as a single byte.
            sanitized.append(codePoint <= MAX_SINGLE_BYTE_CODE_POINT
                    ? (char) codePoint
                    : UNMAPPABLE_SUBSTITUTE);
            index += Character.charCount(codePoint);
        }
        return sanitized.toString();
    }
}
