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
package com.aws.carddemo.account.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.aws.carddemo.account.dto.AccountUpdateRequest;
import com.aws.carddemo.account.exception.ValidationException;

/**
 * Business-rule validator for the account-update slice, reproducing the legacy
 * CardDemo COBOL field edits <em>behaviorally, not verbatim</em>.
 *
 * <p>This component is the validation heart of the account migration. Every
 * accept/reject decision here is designed to match the outcome of the legacy
 * program so that clients observe identical semantics. The rules originate from:</p>
 * <ul>
 *   <li>{@code COACTUPC.cbl} paragraph {@code 1210-EDIT-ACCOUNT}
 *       (11-digit, non-zero numeric account key) &mdash; see
 *       {@link #validateAccountId(String)}.</li>
 *   <li>{@code COACTUPC.cbl} paragraph {@code 1220-EDIT-YESNO}
 *       (active status must be exactly {@code Y} or {@code N}) &mdash; see
 *       {@link #validateActiveStatus(String)}.</li>
 *   <li>{@code COACTUPC.cbl} paragraph {@code 1250-EDIT-SIGNED-9V2}
 *       (signed {@code PIC S9(10)V99} monetary range and scale) &mdash; see
 *       {@link #validateAmount(BigDecimal, String)}.</li>
 *   <li>{@code CSUTLDPY.cpy} date-edit chain
 *       {@code EDIT-DATE-CCYYMMDD} &rarr; {@code EDIT-YEAR-CCYY} /
 *       {@code EDIT-MONTH} / {@code EDIT-DAY} / {@code EDIT-DAY-MONTH-YEAR}
 *       (calendar validity plus the 1900&ndash;2099 century window) &mdash; see
 *       {@link #validateDate(String, String)}.</li>
 * </ul>
 *
 * <p><strong>Native date reimplementation.</strong> The legacy final safety-net
 * paragraph {@code EDIT-DATE-LE} delegated to the Language Environment routine
 * {@code CSUTLDTC.cbl}. That call is <em>reimplemented natively</em> here using
 * strict {@link java.time} parsing rather than invoked; the strict parser enforces
 * month, day-of-month, and the exact Gregorian leap-year rules, which subsumes the
 * legacy LE check. (See AAP &sect;0.6.3 and &sect;0.7.2.)</p>
 *
 * <p><strong>Fail-fast, single-message semantics.</strong> {@link #validate} stops
 * at the first rule violation and throws immediately, mirroring the legacy program's
 * one-message-at-a-time behavior (COBOL {@code WS-RETURN-MSG-OFF}, which captured only
 * the first error message). Each rule is also exposed as an independently invokable
 * public method so the validation matrix can be exercised rule-by-rule.</p>
 *
 * <p><strong>Precision.</strong> Monetary values are handled exclusively with
 * {@link java.math.BigDecimal}; {@code float} and {@code double} are never used
 * anywhere in this class, preserving exact decimal precision (AAP &sect;0.6.2).</p>
 *
 * <p><strong>Security.</strong> This class performs no logging, and every exception
 * message references the offending <em>field name</em> only &mdash; never the raw
 * value. Account numbers and monetary amounts are therefore never emitted in
 * plaintext, satisfying the migration's security requirements (AAP &sect;0.6.6) and
 * matching the legacy edits, which build their text from the field name via
 * {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)}.</p>
 *
 * <p>On any violation this validator throws {@link ValidationException}, which the
 * central {@code GlobalExceptionHandler} maps to <strong>HTTP 400 (Bad Request)</strong>.
 * The validator holds no state and has no injected collaborators, so it is inherently
 * thread-safe and can be shared as a singleton Spring bean.</p>
 */
@Component
public class AccountValidator {

    /**
     * Strict ISO date parser used to validate the three date fields.
     *
     * <p><strong>Why {@code uuuu} and not {@code yyyy} &mdash; do not "fix" this.</strong>
     * Under {@link ResolverStyle#STRICT}, the pattern letter {@code y} means
     * <em>year-of-era</em> and requires an explicit era (AD/BC) token to resolve a date.
     * An era-less ISO string such as {@code "2014-11-20"} parsed with {@code "yyyy-MM-dd"}
     * in STRICT mode throws {@link DateTimeParseException} for <em>every</em> value,
     * valid or not, because month, day-of-month, and year-of-era are insufficient to
     * construct a date when the era is unknown. The correct pattern letter is
     * {@code u} (<em>proleptic year</em>), which parses purely numeric years while still
     * enforcing all strict calendar rules. This realizes the AAP intent ("preserve
     * behavior, replace mechanism") rather than the literal-but-broken {@code yyyy}
     * snippet shown in AAP &sect;0.6.3.</p>
     *
     * <p>With {@code uuuu} + STRICT this parser correctly rejects, for example,
     * {@code 2014-13-01} (month &gt; 12), {@code 2014-11-31} (31 in a 30-day month),
     * {@code 2023-02-29} (non-leap February 29), {@code 1900-02-29} (century year not
     * divisible by 400), and {@code 2014-1-5} (month/day not zero-padded to two digits);
     * it accepts {@code 2000-02-29} (leap year divisible by 400). This exactly reproduces
     * {@code EDIT-MONTH} + {@code EDIT-DAY} + {@code EDIT-DAY-MONTH-YEAR}
     * [app/cpy/CSUTLDPY.cpy:L91-L282] including the Gregorian leap rule
     * (&divide;400 for century years, otherwise &divide;4)
     * [app/cpy/CSUTLDPY.cpy:L243-L272].</p>
     */
    private static final DateTimeFormatter STRICT_ISO_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    /**
     * Inclusive upper magnitude bound for a {@code PIC S9(10)V99} monetary value:
     * ten integer digits plus two fraction digits, i.e. &plusmn;9,999,999,999.99.
     * The boundary value itself is accepted.
     */
    private static final BigDecimal MAX_MONEY = new BigDecimal("9999999999.99");

    /**
     * Maximum permitted fraction-digit scale for monetary values. A
     * {@code PIC S9(10)V99} field carries at most two decimal places.
     */
    private static final int MONEY_MAX_SCALE = 2;

    /**
     * Minimum accepted calendar year. The legacy {@code EDIT-YEAR-CCYY} check permits
     * only {@code LAST-CENTURY} (century {@code 19}), so 1900 is the floor
     * [app/cpy/CSUTLDPY.cpy:L66-L84, app/cpy/CSUTLDWY.cpy century 88-levels].
     */
    private static final int MIN_YEAR = 1900;

    /**
     * Maximum accepted calendar year. The legacy {@code EDIT-YEAR-CCYY} check permits
     * only {@code THIS-CENTURY} (century {@code 20}), so 2099 is the ceiling
     * [app/cpy/CSUTLDPY.cpy:L66-L84, app/cpy/CSUTLDWY.cpy century 88-levels].
     */
    private static final int MAX_YEAR = 2099;

    /**
     * Precompiled pattern for an 11-digit numeric account key, reproducing the
     * {@code PIC 9(11)} shape enforced by {@code 1210-EDIT-ACCOUNT}
     * [app/cbl/COACTUPC.cbl:L1783-L1822].
     */
    private static final Pattern ACCOUNT_ID_PATTERN = Pattern.compile("^[0-9]{11}$");

    /**
     * The single all-zeros 11-digit value, rejected by the legacy non-zero check
     * ({@code CC-ACCT-ID-N EQUAL ZEROS}) [app/cbl/COACTUPC.cbl:L1803].
     */
    private static final String ZERO_ACCOUNT_ID = "00000000000";

    /**
     * Validates every editable business field of an account-update request, in the
     * same order the legacy program applies its edits, and throws on the first
     * violation encountered (fail-fast, single-message semantics).
     *
     * <p>The account identifier and group id are intentionally not validated here:
     * {@link AccountUpdateRequest} structurally omits both (read-only tightening,
     * AAP &sect;0.7.2), so there is no getter for them. The path account id is
     * validated separately via {@link #validateAccountId(String)}, which
     * {@code AccountService} invokes with the URL path variable. The {@code addressZip}
     * field carries no legacy edit (the DTO's {@code @Size(max = 10)} constraint is
     * sufficient), and {@code version} is handled by the service's concurrency check;
     * neither is validated here (Minimal Change Clause, AAP &sect;0.7.1).</p>
     *
     * @param request the account-update request to validate; must not be {@code null}
     * @throws ValidationException on the first field that violates a business rule
     */
    public void validate(AccountUpdateRequest request) {
        if (request == null) {
            throw new ValidationException("request must be supplied.");
        }

        // Active status: 1220-EDIT-YESNO.
        validateActiveStatus(request.getActiveStatus());

        // Monetary fields: 1250-EDIT-SIGNED-9V2 (all five PIC S9(10)V99 amounts).
        validateAmount(request.getCurrentBalance(), "currentBalance");
        validateAmount(request.getCreditLimit(), "creditLimit");
        validateAmount(request.getCashCreditLimit(), "cashCreditLimit");
        validateAmount(request.getCurrentCycleCredit(), "currentCycleCredit");
        validateAmount(request.getCurrentCycleDebit(), "currentCycleDebit");

        // Date fields: EDIT-DATE-CCYYMMDD chain.
        validateDate(request.getOpenDate(), "openDate");
        validateDate(request.getExpirationDate(), "expirationDate");
        validateDate(request.getReissueDate(), "reissueDate");
    }

    /**
     * Validates the account identifier, reproducing {@code 1210-EDIT-ACCOUNT}
     * [app/cbl/COACTUPC.cbl:L1783-L1822].
     *
     * <p>The value must be supplied, must be exactly eleven numeric digits
     * ({@code PIC 9(11)}), and must not be all zeros. The all-zeros rejection
     * reproduces the legacy {@code CC-ACCT-ID-N EQUAL ZEROS} guard
     * [app/cbl/COACTUPC.cbl:L1803]; the error text is preserved from the legacy
     * literal [app/cbl/COACTUPC.cbl:L1806-L1808].</p>
     *
     * <p>This method is {@code public} so that it can be exercised in isolation by the
     * validation test suite and invoked by {@code AccountService} for the URL path id.</p>
     *
     * @param accountId the candidate account identifier (typically the URL path variable)
     * @throws ValidationException if the identifier is missing, not 11 numeric digits,
     *                             or all zeros
     */
    public void validateAccountId(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new ValidationException("accountId must be supplied.");
        }
        if (!ACCOUNT_ID_PATTERN.matcher(accountId).matches() || ZERO_ACCOUNT_ID.equals(accountId)) {
            throw new ValidationException(
                    "Account Number if supplied must be a 11 digit Non-Zero Number");
        }
    }

    /**
     * Validates the account active status, reproducing {@code 1220-EDIT-YESNO}
     * [app/cbl/COACTUPC.cbl:L1856-L1896].
     *
     * <p>The value must be supplied and must be exactly {@code "Y"} or {@code "N"}.
     * The comparison is case-sensitive and matches the legacy {@code FLG-YES-NO-ISVALID}
     * flag, which recognizes only the uppercase literals {@code 'Y'}/{@code 'N'}; lowercase
     * {@code "y"}/{@code "n"} are therefore rejected. A {@code null}, empty, or
     * whitespace-only value maps to the legacy blank branch
     * [app/cbl/COACTUPC.cbl:L1861-L1873].</p>
     *
     * @param status the candidate active-status value
     * @throws ValidationException if the status is missing or is not exactly {@code Y}/{@code N}
     */
    public void validateActiveStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new ValidationException("activeStatus must be supplied.");
        }
        if (!("Y".equals(status) || "N".equals(status))) {
            throw new ValidationException("activeStatus must be Y or N.");
        }
    }

    /**
     * Validates a signed monetary amount, reproducing {@code 1250-EDIT-SIGNED-9V2}
     * [app/cbl/COACTUPC.cbl:L2180-L2223].
     *
     * <p>The value must be supplied, must carry at most {@value #MONEY_MAX_SCALE} fraction
     * digits, and its magnitude must not exceed the {@code PIC S9(10)V99} bound of
     * &plusmn;9,999,999,999.99 (inclusive). The scale and range checks together reproduce
     * the legacy numeric-shape edit ({@code FUNCTION TEST-NUMVAL-C} against a
     * {@code S9(10)V99} picture), while a {@code null} maps to the legacy blank branch
     * [app/cbl/COACTUPC.cbl:L2184-L2195].</p>
     *
     * <p>Comparison uses {@link BigDecimal#compareTo(BigDecimal)} (never
     * {@link BigDecimal#equals(Object)}) so that magnitude &mdash; not scale &mdash; drives
     * the range decision. {@code float}/{@code double} are never used and the value is never
     * compared with {@code ==} (AAP &sect;0.6.2).</p>
     *
     * @param value     the candidate monetary value
     * @param fieldName the logical field name, used only to build the (sanitized) error
     *                  message; the offending value is never included
     * @throws ValidationException if the value is missing, has more than two fraction
     *                             digits, or is out of range
     */
    public void validateAmount(BigDecimal value, String fieldName) {
        if (value == null) {
            throw new ValidationException(fieldName + " must be supplied.");
        }
        if (value.scale() > MONEY_MAX_SCALE) {
            throw new ValidationException(fieldName + " must have at most 2 decimal places.");
        }
        if (value.abs().compareTo(MAX_MONEY) > 0) {
            throw new ValidationException(fieldName + " must be within +/-9,999,999,999.99.");
        }
    }

    /**
     * Validates a date string, reproducing the {@code EDIT-DATE-CCYYMMDD} chain
     * [app/cpy/CSUTLDPY.cpy:L25-L282].
     *
     * <p>The value must be supplied and must be a strictly valid ISO {@code yyyy-MM-dd}
     * date. Strict parsing (see {@link #STRICT_ISO_DATE}) enforces month 01&ndash;12, a
     * day valid for the month, rejection of 31 in a 30-day month, rejection of 30 February,
     * and the exact Gregorian leap-year rule for 29 February &mdash; collectively
     * reproducing {@code EDIT-MONTH}, {@code EDIT-DAY}, and {@code EDIT-DAY-MONTH-YEAR}.</p>
     *
     * <p>Because strict parsing alone accepts any four-digit year, an explicit century
     * guard reproduces {@code EDIT-YEAR-CCYY} [app/cpy/CSUTLDPY.cpy:L66-L84]: the year must
     * fall within {@value #MIN_YEAR}&ndash;{@value #MAX_YEAR} (the legacy
     * {@code LAST-CENTURY}/{@code THIS-CENTURY} window).</p>
     *
     * <p>The caught {@link DateTimeParseException} is deliberately <em>not</em> chained into
     * the thrown message, since its text can echo the raw input; the message references the
     * field name only (AAP &sect;0.6.6). The wire/JSON format remains {@code yyyy-MM-dd};
     * only this validator's internal formatter uses the {@code uuuu} proleptic-year pattern.</p>
     *
     * @param value     the candidate date string (expected ISO {@code yyyy-MM-dd})
     * @param fieldName the logical field name, used only to build the (sanitized) error message
     * @throws ValidationException if the value is missing, not a strictly valid date, or has a
     *                             year outside 1900&ndash;2099
     */
    public void validateDate(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(fieldName + " must be supplied.");
        }

        LocalDate parsed;
        try {
            parsed = LocalDate.parse(value, STRICT_ISO_DATE);
        } catch (DateTimeParseException ex) {
            // Do not chain ex.getMessage(): it can echo the raw input. Keep it field-name-based.
            throw new ValidationException(fieldName + " must be a valid date in yyyy-MM-dd format.");
        }

        int year = parsed.getYear();
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw new ValidationException(fieldName + " year must be between 1900 and 2099.");
        }
    }
}
