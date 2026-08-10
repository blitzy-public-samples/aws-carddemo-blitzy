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

import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * :purpose: Decide what a batch job execution may publish as its exit description, so a
 *  caller polling a run learns its OUTCOME without being handed the service's internals.
 *  Spring Batch stores the exit description of a failed job as the full stack trace of the
 *  cause: a single ``FAILED`` run wrote 2,500 characters naming the exception type, the
 *  generated SQL statement with its column list, the entity class and identifier, and
 *  nineteen framework frames with file names and line numbers. That is the batch tier's
 *  equivalent of an unsanitized error body (CWE-209, CWE-497), and it was being serialized
 *  verbatim by every execution-status endpoint.
 * :output: A sanitizing function used by the batch launch and execution-status endpoints of
 *  the batch, reporting and transaction services, so all three publish the same contract.
 * :note: The decision is an ALLOW list, not a blocklist of known-bad markers: a description
 *  is published only when it looks like the single-line, legacy-visible return-code text the
 *  application's own listeners produce -- ``Return code 4: 38 transaction(s) rejected``, the
 *  ``CBTRN02C`` tally -- and anything else is replaced. A blocklist would publish the next
 *  diagnostic shape nobody thought to exclude.
 * :note: The withheld text is NOT re-logged here. Spring Batch has already written the
 *  cause, with its stack trace, to the service log under the job's own correlation id, which
 *  is where an operator reads it; repeating it would only duplicate the record.
 */
public final class BatchExitMessageSanitizer {

    /**
     * :purpose: Logger for the fact that a description was withheld. Deliberately DEBUG: a
     *  client polling a failed execution reaches this on every poll, and raising it higher
     *  would trade an information leak for log flooding.
     */
    private static final Logger log = LoggerFactory.getLogger(BatchExitMessageSanitizer.class);

    /**
     * :purpose: The one caller-facing text published in place of a description that is not
     *  legacy-visible. It names no type, no statement, no file and no identifier; the
     *  execution's ``status`` and ``exitCode`` already carry the outcome, and the diagnostic
     *  detail stays in the service log.
     */
    public static final String WITHHELD_MESSAGE =
            "Job failed. The diagnostic detail is recorded in the service log.";

    /**
     * :purpose: Longest description that can still be the application's own single-line
     *  return-code text. The longest one this application produces is 41 characters
     *  (``Return code 4: 38 transaction(s) rejected``); the failed-run stack trace was 2,500.
     */
    static final int MAX_PUBLISHABLE_LENGTH = 200;

    /**
     * :purpose: A dotted identifier -- two name segments joined by a period with no space.
     *  One rule covers every diagnostic shape at once: a fully-qualified exception type
     *  (``org.springframework.orm.ObjectOptimisticLockingFailureException``), a stack frame's
     *  source reference (``JpaTransactionManager.java:556``), a method reference and a host
     *  name. Prose is unaffected, because a sentence's period is followed by a space or ends
     *  the text.
     */
    private static final Pattern DOTTED_IDENTIFIER = Pattern.compile("[A-Za-z0-9_$]+\\.[A-Za-z0-9_$]+");

    /**
     * :purpose: The shape of an echoed SQL statement or a bound parameter list, for the case
     *  where a statement is reported on one line and carries no dotted identifier.
     */
    private static final Pattern SQL_MARKER = Pattern.compile(
            "(?i)(\\bselect\\b|\\binsert\\s+into\\b|\\bupdate\\b|\\bdelete\\s+from\\b|=\\s*\\?)");

    /**
     * :purpose: Prevent instantiation of this stateless helper.
     */
    private BatchExitMessageSanitizer() {
        throw new AssertionError("BatchExitMessageSanitizer is a static utility and must not be instantiated");
    }

    /**
     * :purpose: Sanitize one exit description for publication on the wire.
     * :param exitDescription: the description Spring Batch recorded for the execution; may be
     *  ``null`` or empty.
     * :returns: the description unchanged when it is absent, empty or the legacy-visible
     *  return-code text; otherwise {@link #WITHHELD_MESSAGE}.
     */
    public static String sanitize(String exitDescription) {
        if (exitDescription == null || exitDescription.isEmpty()) {
            // A run that completed normally records no description at all. Publishing the
            // absent value as-is keeps "nothing to report" distinguishable from "withheld".
            return exitDescription;
        }
        if (isLegacyVisible(exitDescription)) {
            return exitDescription;
        }
        log.debug("Withheld a batch exit description of {} characters from the response; "
                + "the cause is in the service log", exitDescription.length());
        return WITHHELD_MESSAGE;
    }

    /**
     * :purpose: Report whether a description is the single-line, legacy-visible outcome text
     *  that may be published.
     * :param exitDescription: a non-empty description.
     * :returns: ``true`` when the text is short, single-line, and free of the dotted
     *  identifiers and SQL fragments that only a framework diagnostic contains.
     */
    private static boolean isLegacyVisible(String exitDescription) {
        if (exitDescription.length() > MAX_PUBLISHABLE_LENGTH) {
            return false;
        }
        if (exitDescription.indexOf('\n') >= 0 || exitDescription.indexOf('\r') >= 0) {
            // Every stack trace is multi-line; no outcome text this application writes is.
            return false;
        }
        if (DOTTED_IDENTIFIER.matcher(exitDescription).find()) {
            return false;
        }
        return !SQL_MARKER.matcher(exitDescription).find();
    }
}
