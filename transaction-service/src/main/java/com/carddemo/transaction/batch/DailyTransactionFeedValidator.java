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
package com.carddemo.transaction.batch;

import com.carddemo.common.domain.DailyTransaction;
import com.carddemo.common.exception.CardDemoException;

/**
 * :purpose: Assert that a staged ``daily_transactions`` row really is a ``CVTRA06Y
 *     DALYTRAN-RECORD`` before the posting pipeline validates it, so a structurally unusable
 *     row is reported as an actionable feed fault naming the record and the offending field
 *     instead of aborting the step with a raw ``NullPointerException`` or
 *     ``StringIndexOutOfBoundsException``.
 * :output: Nothing on success; a {@link CardDemoException} carrying the ``DALYTRAN-ID``
 *     and the offending field otherwise.
 * :note: ``DALYTRAN`` is a fixed-width 350-byte sequential data set, so ``CBTRN02C`` can
 *     never see a record without ``DALYTRAN-AMT`` or with a truncated ``DALYTRAN-ORIG-TS``;
 *     the relational staging table is where such a row can appear at all. The schema refuses
 *     it at ingestion (``V1__create_schema.sql`` declares the mandatory feed columns ``NOT
 *     NULL`` with a minimum ``dalytran_orig_ts`` length), and this validator is the matching
 *     in-process guard for a record assembled in memory: without it the over-limit computation
 *     dereferenced a null amount and the expiry check substringed a short timestamp, which
 *     aborted the step on the same row at every rerun and blocked every later record in the
 *     feed.
 */
final class DailyTransactionFeedValidator {

    /**
     * :purpose: Number of leading ``DALYTRAN-ORIG-TS`` characters the expiry check
     *     compares against ``ACCT-EXPIRAION-DATE`` (``YYYY-MM-DD``).
     */
    static final int ORIG_TS_DATE_LENGTH = 10;

    /**
     * :purpose: Prevent instantiation of this stateless validator.
     */
    private DailyTransactionFeedValidator() {
    }

    /**
     * :purpose: Verify that every ``DALYTRAN`` field the posting pipeline
     *     dereferences is present and usable.
     * :param record: the staged feed record about to be validated and posted.
     * :raises CardDemoException: when the record is absent, or when a mandatory
     *     field is missing or too short to satisfy the legacy fixed-width layout.
     */
    static void requireUsableRecord(DailyTransaction record) {
        if (record == null) {
            throw new CardDemoException(
                    "DALYTRAN feed record is missing: the posting job received no record to validate");
        }
        String dalytranId = record.getDalytranId();
        if (dalytranId == null || dalytranId.isBlank()) {
            throw new CardDemoException(feedFault("(unknown)", "DALYTRAN-ID (dalytran_id)", "is absent"));
        }
        if (record.getDalytranCardNum() == null) {
            throw new CardDemoException(
                    feedFault(dalytranId, "DALYTRAN-CARD-NUM (dalytran_card_num)", "is absent"));
        }
        if (record.getDalytranAmt() == null) {
            throw new CardDemoException(
                    feedFault(dalytranId, "DALYTRAN-AMT (dalytran_amt)", "is absent"));
        }
        String origTs = record.getDalytranOrigTs();
        if (origTs == null) {
            throw new CardDemoException(
                    feedFault(dalytranId, "DALYTRAN-ORIG-TS (dalytran_orig_ts)", "is absent"));
        }
        if (origTs.length() < ORIG_TS_DATE_LENGTH) {
            throw new CardDemoException(feedFault(dalytranId, "DALYTRAN-ORIG-TS (dalytran_orig_ts)",
                    "carries " + origTs.length() + " characters, fewer than the "
                            + ORIG_TS_DATE_LENGTH + " date characters (YYYY-MM-DD) the"
                            + " expiration check compares against ACCT-EXPIRAION-DATE"));
        }
        if (record.getDalytranTypeCd() == null) {
            throw new CardDemoException(
                    feedFault(dalytranId, "DALYTRAN-TYPE-CD (dalytran_type_cd)", "is absent"));
        }
        if (record.getDalytranCatCd() == null) {
            throw new CardDemoException(
                    feedFault(dalytranId, "DALYTRAN-CAT-CD (dalytran_cat_cd)", "is absent"));
        }
    }

    /**
     * :purpose: Compose the operator-facing message for an unusable feed record.
     * :param dalytranId: the record's ``DALYTRAN-ID``, or a placeholder when absent.
     * :param field: the offending field in both its COBOL and column spellings.
     * :param detail: what is wrong with that field.
     * :returns: the message carried by the raised {@link CardDemoException}.
     */
    private static String feedFault(String dalytranId, String field, String detail) {
        return "DALYTRAN feed record " + dalytranId
                + " is not a usable CVTRA06Y record: " + field + " " + detail
                + ". Correct the staged daily_transactions row and rerun the posting job.";
    }
}
