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
     * :purpose: Matches a whole path segment that is nothing but a candidate PAN, used by
     *     {@link #maskPath} to redact the ``/cards/{cardNumber}`` variable while leaving a
     *     segment that merely contains digits alone.
     */
    private static final Pattern PAN_SEGMENT_PATTERN = Pattern.compile("\\d{12,19}");

    /**
     * :purpose: The path segment whose successor is a card number, i.e. the ``/cards``
     *     prefix of the card detail and card update endpoints.
     */
    private static final String CARDS_SEGMENT = "cards/";

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
     * :purpose: Mask a request path, redacting a PAN only where a card number can actually
     *     appear: the path segment that follows a ``cards`` segment, which is the
     *     ``/cards/{cardNumber}`` variable of the card detail and card update endpoints. Every
     *     other segment is returned verbatim.
     * :param path: the request URI (query string excluded), possibly ``null``.
     * :returns: the path with a card-number segment reduced to its last four digits and all
     *     other segments unchanged.
     * :note: Applying {@link #maskPan} to a whole request path over-redacted: the 16-character
     *     transaction id of ``/transactions/{id}`` matches the PAN shape, so a ``404`` reported
     *     ``"/transactions/QS**********0001"`` - a path that never existed - while
     *     ``/accounts/{id}`` (11 digits) came back intact. A support engineer could not match the
     *     reported path to the request. Only the card-number position carries a PAN, so only it is
     *     masked.
     * :note: {@link #maskPan} remains the right helper for free text (an exception message, a
     *     driver message, an audited field value), where a PAN can appear anywhere and no
     *     positional rule applies.
     */
    public static String maskPath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        int cardsAt = indexOfCardsSegment(path);
        if (cardsAt < 0) {
            return path;
        }
        int start = cardsAt + CARDS_SEGMENT.length();
        int end = path.indexOf('/', start);
        if (end < 0) {
            end = path.length();
        }
        String segment = path.substring(start, end);
        if (!PAN_SEGMENT_PATTERN.matcher(segment).matches()) {
            return path;
        }
        return path.substring(0, start) + mask(segment) + path.substring(end);
    }

    /**
     * :purpose: Locate the ``/cards/`` segment boundary that precedes a card-number path
     *     variable, matching it as a whole segment so a path such as ``/discardsx/...``
     *     is never treated as the card endpoint.
     * :param path: the request URI.
     * :returns: the index at which the ``/cards/`` segment starts, or ``-1`` when the path
     *     addresses no card by number.
     */
    private static int indexOfCardsSegment(String path) {
        int from = 0;
        while (true) {
            int at = path.indexOf(CARDS_SEGMENT, from);
            if (at < 0) {
                return -1;
            }
            // A whole segment: preceded by the path root or by another segment boundary.
            if (at == 0 || path.charAt(at - 1) == '/' || path.charAt(at - 1) == '=') {
                return at;
            }
            from = at + 1;
        }
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
