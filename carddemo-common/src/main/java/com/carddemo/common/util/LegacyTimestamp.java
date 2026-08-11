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
package com.carddemo.common.util;

/**
 * The single canonical contract for the ``PIC X(26)`` transaction timestamp columns
 *     (``TRAN-ORIG-TS`` / ``TRAN-PROC-TS``, ``CVTRA05Y``).
 * :purpose: Hold the stored-shape rules for the 26-character timestamp fields in ONE
 *     place, so every writer produces and every reader consumes the same contract. The column
 *     contract has exactly two invariants, and they are what every read path relies on: 1. the
 *     stored value is ALWAYS exactly {@link #STORED_WIDTH} characters, and 2. positions one to
 *     ten are ALWAYS the ISO ``YYYY-MM-DD`` date. Everything after position ten is
 *     producer-specific, because the legacy application has four distinct producers and each
 *     one writes its own program's MOVE verbatim: - the online add screen (``COTRN02C``
 *     L464-L465) moves the ten-character map field ``TORIGDTI`` into the 26-character
 *     receiver, which leaves the date followed by sixteen blanks — see {@link
 *     #fromMapDateField(String)}; - bill payment (``COBIL00C`` L255-L266) moves ``CSDAT01Y``
 *     ``WS-TIMESTAMP``, which is ``YYYY-MM-DD HH:MM:SS.mmmmmm`` — one blank between date and
 *     time, colon time separators; - transaction posting (``CBTRN02C`` L438 /
 *     ``Z-GET-DB2-FORMAT-TIMESTAMP``) and interest accrual (``CBACT04C`` L497-L498) move
 *     ``DB2-FORMAT-TS``, which is ``YYYY-MM-DD-HH.MM.SS.hh0000``. The three shapes are a
 *     property of the legacy application, not of this migration: the AAP's 0.3.6 note
 *     describes only the ``DB2-FORMAT-TS`` producer, and the source outranks it for the other
 *     two. Readers therefore never parse past position ten — see {@link
 *     #storedDatePortion(String)} — and no reader may assume a time component is present.
 */
public final class LegacyTimestamp {

    /** :purpose: Stored width of ``TRAN-ORIG-TS`` / ``TRAN-PROC-TS`` (``PIC X(26)``). */
    public static final int STORED_WIDTH = 26;

    /**
     * :purpose: Width of the ``COTRN02`` date map fields ``TORIGDT`` / ``TPROCDT``, declared
     *     ``DFHMDF LENGTH=10`` [app/bms/COTRN02.bms:L187-L190, L200-L203].
     */
    public static final int MAP_DATE_LENGTH = 10;

    private LegacyTimestamp() {
    }

    /**
     * :purpose: Report whether a value is a well-formed ``COTRN02`` date map field: the
     *     ``YYYY-MM-DD`` character shape occupying the WHOLE field.
     * :param value: the candidate value; may be ``null``.
     * :returns: ``true`` when the value is ten characters in ``NNNN-NN-NN`` shape,
     *     disregarding trailing blanks.
     * :note: Ten characters is the whole field the screen can send, so there is no room for
     *     anything after them. Checking only positions one to ten accepts any suffix
     *     whatsoever, and the suffix is then persisted verbatim as a timestamp. Trailing
     *     blanks ARE accepted because they are what the screen itself redisplays:
     *     ``COTRN02C`` L487-L488 moves the 26-character stored value back into the
     *     10-character map field, so a resubmitted screen legitimately carries the date
     *     space-filled.
     */
    public static boolean isMapDateField(String value) {
        if (value == null) {
            return false;
        }
        String field = stripTrailingBlanks(value);
        if (field.length() != MAP_DATE_LENGTH) {
            return false;
        }
        return isAsciiDigit(field, 0) && isAsciiDigit(field, 1) && isAsciiDigit(field, 2)
                && isAsciiDigit(field, 3)
                && field.charAt(4) == '-'
                && isAsciiDigit(field, 5) && isAsciiDigit(field, 6)
                && field.charAt(7) == '-'
                && isAsciiDigit(field, 8) && isAsciiDigit(field, 9);
    }

    /**
     * :purpose: Render a ``COTRN02`` date map field in the stored 26-character form.
     * :param value: the validated map field value: ten characters plus optional trailing
     *     blanks. Callers validate with {@link #isMapDateField(String)} first.
     * :returns: the ten-character date left-justified and space-filled to
     *     {@link #STORED_WIDTH}.
     * :raises IllegalArgumentException: when the value is not the ten-character map field.
     * :note: This is ``MOVE TORIGDTI TO TRAN-ORIG-TS`` (``COTRN02C`` L464-L465) expressed in
     *     Java. Normalizing here is what gives every CT02 row ONE stored shape: the same date
     *     submitted bare or space-filled to the stored width produces byte-identical storage.
     */
    public static String fromMapDateField(String value) {
        if (!isMapDateField(value)) {
            throw new IllegalArgumentException(
                    "not a COTRN02 date map field: " + value);
        }
        String date = stripTrailingBlanks(value);
        return date + " ".repeat(STORED_WIDTH - date.length());
    }

    /**
     * :purpose: Extract the date portion every producer writes to the same positions.
     * :param value: a stored 26-character timestamp; may be ``null`` or short.
     * :returns: positions one to ten as ``YYYY-MM-DD``, or ``null`` when the value is
     *     ``null`` or shorter than the date.
     * :note: This is the ONLY part of the stored value a reader may depend on, because the
     *     three producers differ after position ten. ``CBTRN02C`` L416 compares
     *     ``DALYTRAN-ORIG-TS (1:10)`` for exactly this reason.
     */
    public static String storedDatePortion(String value) {
        if (value == null || value.length() < MAP_DATE_LENGTH) {
            return null;
        }
        return value.substring(0, MAP_DATE_LENGTH);
    }

    /**
     * :purpose: Remove trailing blanks from a fixed-width field value, so a value that
     *     arrives space-filled to the stored width is judged on its content.
     * :param value: the field value; must not be ``null``.
     * :returns: the value without trailing spaces.
     */
    public static String stripTrailingBlanks(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * :purpose: Report whether the character at an index is an ASCII digit.
     * :param value: the string to inspect.
     * :param index: the zero-based index.
     * :returns: ``true`` when the character is ``0``-``9``.
     */
    private static boolean isAsciiDigit(String value, int index) {
        char ch = value.charAt(index);
        return ch >= '0' && ch <= '9';
    }
}
