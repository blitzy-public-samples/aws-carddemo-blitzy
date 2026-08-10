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

import java.text.Normalizer;
import java.util.Locale;

/**
 * :purpose: Canonical form of a CardDemo security user id, shared by every module that reads or
 *  writes one, so a user id written by one service is always found by another.
 * :output: The NFKC-canonicalized, trimmed, upper-cased id. This reproduces the legacy 3270 behaviour, where
 *  the ``UCTRAN`` attribute folded terminal input to upper case before the program saw it and
 *  ``COSGN00C`` additionally applied ``FUNCTION UPPER-CASE`` to the entered id
 *  [app/cbl/COSGN00C.cbl:L132]. A lower-case user id was therefore not representable on the
 *  mainframe, and the migrated system must not be able to create one either.
 * :note: This type exists because the rule was previously re-implemented independently in each
 *  module and the copies had drifted: the sign-on path folded case, the administrative WRITE path
 *  did not, and two of the read paths folded case without trimming. The consequence was an
 *  administrator being able to create a user whose id no sign-on could ever match. Every caller
 *  MUST delegate here rather than fold case locally, so the write path and the authentication path
 *  cannot diverge again.
 * :note: ``Locale.ROOT`` is mandatory, not decorative. ``toUpperCase()`` without a locale uses the
 *  JVM default, and in a Turkish locale ``"admin001"`` folds to ``"ADMİN001"`` -- a dotted capital
 *  I that no keyed read would match. The result is a service whose authentication depends on the
 *  host's locale.
 */
public final class UserIdNormalizer {

    /**
     * :purpose: Prevent instantiation of this stateless helper.
     */
    private UserIdNormalizer() {
        throw new AssertionError("UserIdNormalizer is a static utility and must not be instantiated");
    }

    /**
     * :purpose: Fold a user id to its canonical stored form.
     * :param userId: the raw user id as supplied by a caller or read from a request; may be
     *  ``null``.
     * :returns: the NFKC-canonicalized, trimmed, upper-cased id, or ``null`` when ``userId`` is
     *  ``null``. A ``null`` input yields ``null`` rather than an empty string so an absent value
     *  stays distinguishable from a blank one and the legacy presence edits still report their
     *  own messages.
     * :note: Unicode NFKC runs FIRST, so a compatibility form folds to the character it stands
     *  for -- fullwidth ``Ａ`` (U+FF21) to ``A``, the ligature ``ﬁ`` to ``fi`` -- before the id is
     *  compared or stored. Without it two ids that a reviewer reads as the same string are two
     *  different rows and two different principals. NFKC does not equate characters from
     *  DIFFERENT scripts (Cyrillic ``А`` U+0410 is not Latin ``A``), which is why the character
     *  set is additionally restricted where a user id is accepted -- on the add-user request DTO
     *  and by the ``chk_sec_usr_id_charset`` constraint -- rather than being left to
     *  canonicalization alone.
     */
    public static String normalize(String userId) {
        if (userId == null) {
            return null;
        }
        return Normalizer.normalize(userId, Normalizer.Form.NFKC).trim().toUpperCase(Locale.ROOT);
    }

    /**
     * :purpose: Fold a user id for use as a lookup or index key, where an absent value must still
     *  produce a usable string rather than propagate a ``null`` into a key expression.
     * :param userId: the raw user id; may be ``null``.
     * :returns: the trimmed, upper-cased id, or the empty string when ``userId`` is ``null``.
     */
    public static String normalizeToKey(String userId) {
        String normalized = normalize(userId);
        return normalized == null ? "" : normalized;
    }
}
