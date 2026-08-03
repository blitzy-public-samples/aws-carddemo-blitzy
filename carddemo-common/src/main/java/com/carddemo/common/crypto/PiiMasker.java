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
package com.carddemo.common.crypto;

/**
 * :purpose: Produce the masked renderings of the sensitive customer identifiers that
 *     leave the service boundary, so that the social security number, the government
 *     issued identifier and the electronic funds transfer account identifier are
 *     never emitted in clear text while the field itself remains present in the
 *     response contract derived from the BMS symbolic maps.
 * :output: Masked strings that retain only the trailing digits needed to let an
 *     operator recognise a record, with every other character replaced by ``*``.
 * :note: Masking is applied at the response-assembly boundary rather than by
 *     omitting the property, so the wire contract keeps the field the 3270 screen
 *     displayed while the value satisfies the at-rest/in-transit protection
 *     requirement. The value stored in the database remains AES-GCM ciphertext
 *     produced by {@link CryptoConverter}; masking never alters persisted state.
 */
public final class PiiMasker {

    /** :purpose: Character substituted for every concealed position. */
    private static final char MASK_CHARACTER = '*';

    /** :purpose: Number of trailing characters left visible on a masked value. */
    private static final int VISIBLE_TAIL_LENGTH = 4;

    /** :purpose: Fixed prefix of a masked nine-digit social security number. */
    private static final String SSN_MASK_PREFIX = "***-**-";

    /**
     * :purpose: Prevent instantiation of this stateless helper.
     */
    private PiiMasker() {
        throw new AssertionError("PiiMasker is a static utility and must not be instantiated");
    }

    /**
     * :purpose: Mask a nine-character social security number into the conventional
     *     ``***-**-NNNN`` rendering, retaining only the final four characters.
     * :param ssn: the clear-text social security number; may be ``null`` or blank.
     * :returns: the masked rendering, or the original value when it is ``null`` or blank.
     */
    public static String maskSsn(String ssn) {
        if (ssn == null || ssn.isBlank()) {
            return ssn;
        }
        String trimmed = ssn.trim();
        if (trimmed.length() <= VISIBLE_TAIL_LENGTH) {
            return SSN_MASK_PREFIX + trimmed;
        }
        return SSN_MASK_PREFIX + trimmed.substring(trimmed.length() - VISIBLE_TAIL_LENGTH);
    }

    /**
     * :purpose: Mask an arbitrary sensitive identifier by replacing every character
     *     except the final four with the mask character, preserving the original
     *     length so the field width of the legacy screen is still recognisable.
     * :param value: the clear-text identifier; may be ``null`` or blank.
     * :returns: the masked rendering, or the original value when it is ``null`` or blank.
     */
    public static String maskIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= VISIBLE_TAIL_LENGTH) {
            return repeat(trimmed.length());
        }
        int concealed = trimmed.length() - VISIBLE_TAIL_LENGTH;
        return repeat(concealed) + trimmed.substring(concealed);
    }

    /**
     * :purpose: Report whether a submitted value is merely the MASK of the stored value
     *     echoed back by the client, rather than a genuine new value. A view response
     *     masks the regulated identifiers, so a screen that re-submits every field would
     *     otherwise overwrite the real stored identifier with its own mask and destroy the
     *     data. Both mask shapes this class produces are recognised.
     * :param submitted: the value carried by the update request.
     * :param stored: the value currently persisted for that field.
     * :returns: ``true`` when the submitted value equals a mask of the stored value and is
     *     not the stored value itself.
     */
    /**
     * :purpose: Report whether a value carries the mask character at all, so a caller can
     *     recognise an echoed mask WITHOUT holding the stored value to compare against. A
     *     regulated identifier is all digits (optionally grouped), so a mask character can
     *     only have come from this class: such a value therefore carries no new data and the
     *     stored value stands, whichever record's mask it happens to be.
     * :param value: the value carried by the request, possibly ``null``.
     * :returns: ``true`` when the value contains the mask character.
     */
    public static boolean isMaskShaped(String value) {
        return value != null && value.indexOf(MASK_CHARACTER) >= 0;
    }

    public static boolean isMaskOf(String submitted, String stored) {
        if (submitted == null || stored == null || stored.isEmpty()) {
            return false;
        }
        if (submitted.equals(stored)) {
            return false;
        }
        return submitted.equals(maskSsn(stored)) || submitted.equals(maskIdentifier(stored));
    }

    /**
     * :purpose: Build a run of mask characters of the requested length.
     * :param length: number of mask characters to produce.
     * :returns: a string of ``length`` mask characters.
     */
    private static String repeat(int length) {
        char[] masked = new char[length];
        for (int i = 0; i < length; i++) {
            masked[i] = MASK_CHARACTER;
        }
        return new String(masked);
    }
}
