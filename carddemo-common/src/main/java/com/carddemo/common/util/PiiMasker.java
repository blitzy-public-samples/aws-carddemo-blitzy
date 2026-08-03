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
package com.carddemo.common.util;

/**
 * Masking of regulated identifiers for values that leave the service boundary.
 *
 * :purpose: Provide the single masking rule used wherever a sensitive identifier
 *     (social-security number, government-issued id) is rendered outside the
 *     application - REST responses, diagnostics and logs - so the value is
 *     recognizable to its owner without disclosing it.
 * :output: A string of the same length as the input in which every character
 *     except the trailing four is replaced by ``*``; a value of four characters
 *     or fewer is fully asterisked.
 *
 * The rule matches the convention already applied by the entity ``toString()``
 * renderings, so a masked value looks identical wherever it appears, and the
 * preserved length keeps the fixed-width screen semantics of the legacy 3270
 * maps intact (``CUST-SSN`` is ``PIC X(9)``, ``CUST-GOVT-ISSUED-ID`` is
 * ``PIC X(20)``).
 */
public final class PiiMasker {

    /** :purpose: Number of trailing characters left visible for identification. */
    private static final int VISIBLE_SUFFIX_LENGTH = 4;

    /** :purpose: Masking character applied to every concealed position. */
    private static final String MASK_CHARACTER = "*";

    /**
     * :purpose: Prevent instantiation of this static utility.
     */
    private PiiMasker() {
        throw new AssertionError("PiiMasker is a static utility");
    }

    /**
     * Masks a sensitive identifier for disclosure outside the service.
     *
     * :param value: the raw identifier; ``null`` and empty values are returned
     *     unchanged so absent and blank fixed-width fields stay distinguishable.
     * :output: the masked identifier, of the same length as the input.
     */
    public static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        int length = value.length();
        if (length <= VISIBLE_SUFFIX_LENGTH) {
            return MASK_CHARACTER.repeat(length);
        }
        return MASK_CHARACTER.repeat(length - VISIBLE_SUFFIX_LENGTH)
                + value.substring(length - VISIBLE_SUFFIX_LENGTH);
    }

    /**
     * Reports whether a submitted value is merely the mask of a stored value.
     *
     * :param submitted: the value received from a client, possibly ``null``.
     * :param stored: the value currently persisted, possibly ``null``.
     * :output: ``true`` when ``submitted`` equals :java:meth:`mask` of ``stored``
     *     and the stored value was not itself already masked, meaning the client
     *     echoed a masked display value back unchanged and no edit was intended.
     *
     * Screens that display a masked identifier submit the whole record back on
     * update (the legacy maps echo every field), so an update must be able to tell
     * "unchanged, still masked" from a genuine edit and never overwrite a stored
     * identifier with asterisks.
     */
    public static boolean isMaskOf(String submitted, String stored) {
        if (submitted == null || stored == null || stored.isEmpty()) {
            return false;
        }
        return submitted.equals(mask(stored)) && !submitted.equals(stored);
    }
}
