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
package com.carddemo.common.security;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * :purpose: Mask primary account numbers (PANs) and other long digit runs before they
 *     reach a log stream, so a card number that travels in a request path or an
 *     exception message is never written to disk in full (CWE-532). Business
 *     responses are unaffected: this helper is only applied on the logging and
 *     audit path.
 * :output: The input text with every 12-to-19-digit run replaced by asterisks plus
 *     its last four digits (for example ``************5740``).
 * :note: The 12-digit lower bound is the shortest PAN length in the ISO/IEC 7812
 *     range, so shorter identifiers such as an 11-digit account id are left intact
 *     and remain useful for support and correlation.
 */
public final class SensitiveDataMasker {

    /**
     * :purpose: Matches an unbroken run of 12 to 19 digits that is not part of a
     *     longer digit run, i.e. a candidate PAN.
     */
    private static final Pattern PAN_PATTERN = Pattern.compile("(?<!\\d)\\d{12,19}(?!\\d)");

    /**
     * :purpose: Number of trailing digits retained when masking, matching the
     *     industry-standard "last four" display convention.
     */
    private static final int VISIBLE_SUFFIX_LENGTH = 4;

    /**
     * :purpose: Prevent instantiation of this stateless helper.
     */
    private SensitiveDataMasker() {
    }

    /**
     * :purpose: Mask every PAN-shaped digit run in the supplied text.
     * :param text: arbitrary text destined for a log record, possibly ``null``.
     * :returns: the masked text, or the original value when it is ``null`` or blank.
     */
    public static String maskPan(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = PAN_PATTERN.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder masked = new StringBuilder(text.length());
        int cursor = 0;
        do {
            masked.append(text, cursor, matcher.start());
            masked.append(mask(matcher.group()));
            cursor = matcher.end();
        } while (matcher.find());
        masked.append(text, cursor, text.length());
        return masked.toString();
    }

    /**
     * :purpose: Replace all but the last four digits of a single digit run.
     * :param digits: the matched digit run.
     * :returns: the asterisk-padded value retaining only the last four digits.
     */
    private static String mask(String digits) {
        int hidden = digits.length() - VISIBLE_SUFFIX_LENGTH;
        StringBuilder builder = new StringBuilder(digits.length());
        builder.append("*".repeat(hidden));
        builder.append(digits, hidden, digits.length());
        return builder.toString();
    }
}
