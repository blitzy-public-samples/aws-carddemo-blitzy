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
package com.aws.carddemo.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * Stateless date-validation utility that reproduces the behavior of the legacy AWS CardDemo COBOL
 * date routines in pure Java.
 *
 * <p>This class is the Java translation of three legacy z/OS artifacts:
 *
 * <ul>
 *   <li>{@code app/cbl/CSUTLDTC.cbl} — the CICS {@code CDV1} transaction program that wraps the IBM
 *       Language Environment callable service {@code CEEDAYS} to validate a date string against a
 *       picture mask and return an 80-byte result (severity, message number, and a 15-character
 *       result text). Reproduced by {@link #validateDate(String, String)}.
 *   <li>{@code app/cpy/CSUTLDPY.cpy} — the reusable {@code PROCEDURE DIVISION} paragraphs that
 *       field-edit a {@code CCYYMMDD} date ({@code EDIT-YEAR-CCYY}, {@code EDIT-MONTH}, {@code
 *       EDIT-DAY}, {@code EDIT-DAY-MONTH-YEAR}, {@code EDIT-DATE-LE}) and the date-of-birth
 *       reasonableness check ({@code EDIT-DATE-OF-BIRTH}). Reproduced by {@link
 *       #editDateCcyymmdd(String, String)} and {@link #editDateOfBirth(String, String, LocalDate)}.
 *   <li>{@code app/cpy/CSUTLDWY.cpy} — the {@code WORKING-STORAGE} backing {@code CSUTLDPY} (the
 *       field validity flags and the result layout). Its {@code FLG-*} flags are modeled by {@link
 *       DateEditResult.FieldStatus} and its 80-byte result layout by {@link
 *       DateValidationResult#formattedMessage()}.
 * </ul>
 *
 * <h2>Parity contract</h2>
 *
 * <ul>
 *   <li><b>No exceptions for bad dates.</b> An invalid date is a normal outcome and is always
 *       reported through a returned result object — never an exception. Exceptions would only ever
 *       surface for genuinely unexpected programming errors.
 *   <li><b>Severity semantics.</b> COBOL severity {@code '0000'} (numeric zero) means the date is
 *       valid; any non-zero severity means invalid, mirroring {@code MOVE WS-SEVERITY-N TO
 *       RETURN-CODE} in {@code CSUTLDTC}.
 *   <li><b>Tolerated message 2513.</b> {@code CEEDAYS} message number {@code 2513} ({@code
 *       FC-UNSUPP-RANGE}, "Unsupp. Range") is tolerated by some callers (for example {@code
 *       COACTUPC}) even when severity is non-zero. The tolerance is intentionally <em>not</em>
 *       baked into the {@code valid} flag; callers inspect {@link #MSG_UNSUPPORTED_RANGE} via
 *       {@link DateValidationResult#isToleratedUnsupportedRange()} and decide for themselves.
 *   <li><b>Exact COBOL leap-year rule.</b> {@link #isLeapYearCobol(int)} reproduces the branch in
 *       {@code EDIT-DAY-MONTH-YEAR} (divisor 400 when the two-digit year-within-century is zero,
 *       otherwise 4) rather than delegating to {@link java.time.LocalDate#isLeapYear()}.
 *   <li><b>Century rule.</b> Only centuries {@code 19} ({@code LAST-CENTURY}) and {@code 20}
 *       ({@code THIS-CENTURY}) are accepted, exactly as coded in {@code CSUTLDWY}/{@code CSUTLDPY}.
 *   <li><b>Field-edit order.</b> The edit sequence year/century &rarr; month &rarr; day &rarr;
 *       day-month-year combinations &rarr; Language-Environment backstop is preserved.
 * </ul>
 *
 * <h2>{@code CEEDAYS} feedback emulation</h2>
 *
 * <p>The mainframe {@code CEEDAYS} service is unavailable off-host, so {@link #validateDate(String,
 * String)} reproduces the externally observable subset of its feedback codes that migrated callers
 * branch on, using strict {@link java.time} parsing ({@link ResolverStyle#STRICT}). The COBOL
 * picture mask is translated to a {@link DateTimeFormatter} pattern by a small private translator
 * (see {@link #toJavaPattern(String)}). After a successful strict parse the resulting calendar date
 * is classified against the {@code CEEDAYS} Lilian-day supported range (15&nbsp;October&nbsp;1582,
 * Lilian day {@code 1}, through 31&nbsp;December&nbsp;9999 &mdash; the range over which {@code
 * CEEDAYS} can return a Lilian day number):
 *
 * <ul>
 *   <li><b>Valid &amp; in range</b> &mdash; severity {@link #SEVERITY_OK} ({@code "0000"}), message
 *       {@code "0000"}, result {@link #RESULT_VALID} ({@code FC-INVALID-DATE}, the all-zero "ok"
 *       feedback token).
 *   <li><b>Year-within-era zero</b> (proleptic year {@code 0}) &mdash; severity {@code "0003"},
 *       message {@code "2521"}, result {@code "YearInEra is 0"} ({@code FC-YEAR-IN-ERA-ZERO}). The
 *       AD/CE era {@code CEEDAYS} uses has no year zero, so this is an error callers do
 *       <em>not</em> tolerate.
 *   <li><b>Before the Lilian epoch</b> (a real calendar date earlier than
 *       15&nbsp;October&nbsp;1582) &mdash; severity {@code "0003"}, message {@link
 *       #MSG_UNSUPPORTED_RANGE} ({@code "2513"}), result {@link #RESULT_UNSUPPORTED_RANGE} ({@code
 *       "Unsupp. Range"}, {@code FC-UNSUPP-RANGE}). This is the feedback that {@code
 *       COTRN02C}/{@code CORPT00C} (and their migrated equivalents {@code TranAddService}/{@code
 *       ReportService}) explicitly tolerate; see {@link
 *       DateValidationResult#isToleratedUnsupportedRange()}.
 * </ul>
 *
 * <p>A parse failure (an impossible calendar date, a non-numeric field, or a mask that yields an
 * unusable pattern) maps to severity {@code "0003"} &mdash; the severity carried by <em>every</em>
 * {@code CEEDAYS} error feedback token (the first halfword {@code X'0003'} of {@code
 * FC-BAD-DATE-VALUE}, {@code FC-INVALID-MONTH}, {@code FC-NON-NUMERIC-DATA}, and the rest) &mdash;
 * with message {@code "0000"} and result {@link #RESULT_INVALID}. The exact {@code CEEDAYS}
 * sub-code among those parse-failure tokens ("Insufficient", "Datevalue error", "Invalid Era",
 * "Invalid month", "Bad Pic String", "Nonnumeric data") is not reconstructed: it requires the real
 * {@code CEEDAYS} internals and &mdash; critically for parity &mdash; no migrated caller branches
 * on it. Every one of those tokens is a non-{@code 2513} error and therefore drives the identical
 * reject decision in every caller. Only the {@code 2513} (tolerated) versus non-{@code 2513}
 * (rejected) distinction is behaviorally observable, and that distinction is reproduced exactly.
 *
 * <p>The class is stateless, JDK-only (no Spring dependency, keeping the {@code util} package
 * dependency-free), uses no floating-point arithmetic, and is therefore thread-safe.
 */
public final class DateValidationService {

  /** COBOL severity value (numeric zero) that denotes a valid date. */
  public static final String SEVERITY_OK = "0000";

  /**
   * {@code CEEDAYS} message number that maps to {@code FC-UNSUPP-RANGE} ("Unsupp. Range"). Callers
   * may tolerate this message even when severity is non-zero; see {@link
   * DateValidationResult#isToleratedUnsupportedRange()}.
   */
  public static final String MSG_UNSUPPORTED_RANGE = "2513";

  /** 15-character {@code WS-RESULT} text used by {@code CSUTLDTC} for a valid date. */
  public static final String RESULT_VALID = "Date is valid";

  /** 15-character {@code WS-RESULT} text used by {@code CSUTLDTC} for an invalid date. */
  public static final String RESULT_INVALID = "Date is invalid";

  /**
   * 15-character {@code WS-RESULT} text {@code CSUTLDTC} emits for {@code FC-UNSUPP-RANGE} &mdash;
   * a real calendar date that falls outside the {@code CEEDAYS} Lilian supported range. Pairs with
   * {@link #MSG_UNSUPPORTED_RANGE} and is the tolerated feedback (see {@link
   * DateValidationResult#isToleratedUnsupportedRange()}). Stored unpadded; {@link #buildResultLine}
   * right-pads it to the 15-character {@code WS-RESULT} field, reproducing the COBOL literal {@code
   * 'Unsupp. Range '} byte-for-byte.
   */
  public static final String RESULT_UNSUPPORTED_RANGE = "Unsupp. Range";

  /** Default COBOL picture mask ({@code WS-DATE-FORMAT VALUE 'YYYYMMDD'} in {@code CSUTLDWY}). */
  public static final String DEFAULT_FORMAT = "YYYYMMDD";

  /**
   * Severity emitted for every {@code CEEDAYS} error feedback code. The {@code CSUTLDTC} feedback
   * tokens all carry {@code X'0003'} in their severity halfword (for example {@code
   * FC-UNSUPP-RANGE} = {@code X'000309D1...'}), so {@code MOVE SEVERITY OF FEEDBACK-CODE TO
   * WS-SEVERITY-N} yields {@code 3} &rarr; {@code "0003"}. Callers branch only on {@link
   * #SEVERITY_OK} versus non-zero, so the precise value is observable only in the rendered {@code
   * WS-MESSAGE} line, where {@code "0003"} is byte-faithful to the mainframe.
   */
  private static final String SEVERITY_ERROR = "0003";

  /** Message number for an error with no specific reconstructable {@code CEEDAYS} sub-code. */
  private static final String MSG_NONE = "0000";

  /**
   * {@code CEEDAYS} message number for {@code FC-YEAR-IN-ERA-ZERO} (feedback token {@code
   * X'000309D9...'} &rarr; {@code 0x09D9} = {@code 2521}): a proleptic year of {@code 0}, which has
   * no representation in the AD/CE era {@code CEEDAYS} uses. This is a non-tolerated error.
   */
  private static final String MSG_YEAR_IN_ERA_ZERO = "2521";

  /** 15-character {@code WS-RESULT} text {@code CSUTLDTC} emits for {@code FC-YEAR-IN-ERA-ZERO}. */
  private static final String RESULT_YEAR_IN_ERA_ZERO = "YearInEra is 0";

  /**
   * First date {@code CEEDAYS} can represent as a Lilian day number (Lilian day {@code 1} =
   * 15&nbsp;October&nbsp;1582). A strictly earlier real calendar date is reported as {@code
   * FC-UNSUPP-RANGE} (message {@link #MSG_UNSUPPORTED_RANGE}), the feedback migrated callers
   * tolerate.
   */
  private static final LocalDate LILIAN_RANGE_START = LocalDate.of(1582, 10, 15);

  /** Number of characters in a {@code CCYYMMDD} date ({@code WS-EDIT-DATE-CCYYMMDD} is 8 bytes). */
  private static final int CCYYMMDD_LENGTH = 8;

  /**
   * Strict formatter for the canonical {@code CCYYMMDD} mask. {@code uuuu} (year-of-era with a
   * sign) combined with {@link ResolverStyle#STRICT} rejects impossible dates such as February 30.
   */
  private static final DateTimeFormatter CCYYMMDD_FORMATTER =
      DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

  // Private aliases for the nested field-status flags keep the edit logic readable while the public
  // enum remains nested in DateEditResult (mirroring how CSUTLDWY scopes the FLG-* flags).
  private static final DateEditResult.FieldStatus VALID = DateEditResult.FieldStatus.VALID;
  private static final DateEditResult.FieldStatus NOT_OK = DateEditResult.FieldStatus.NOT_OK;
  private static final DateEditResult.FieldStatus BLANK = DateEditResult.FieldStatus.BLANK;

  private DateValidationService() {
    throw new AssertionError("DateValidationService is a non-instantiable utility class");
  }

  /**
   * Immutable result of {@link DateValidationService#validateDate(String, String)}, mirroring the
   * 80-byte {@code WS-MESSAGE} / {@code WS-DATE-VALIDATION-RESULT} layout returned by {@code
   * CSUTLDTC}.
   *
   * @param severity the 4-character COBOL severity ({@code "0000"} means valid)
   * @param messageNumber the 4-character {@code CEEDAYS} message number
   * @param resultText the 15-character {@code WS-RESULT} text ({@link #RESULT_VALID} or {@link
   *     #RESULT_INVALID})
   * @param formattedMessage the full 80-character result line, byte-faithful to {@code WS-MESSAGE}
   * @param valid {@code true} iff {@code severity} is {@link DateValidationService#SEVERITY_OK}
   */
  public record DateValidationResult(
      String severity,
      String messageNumber,
      String resultText,
      String formattedMessage,
      boolean valid) {

    /**
     * Readable alias for the auto-generated {@code valid()} accessor.
     *
     * @return {@code true} iff the date validated successfully (severity {@code "0000"})
     */
    public boolean isValid() {
      return valid;
    }

    /**
     * Indicates whether this result carries the tolerated {@code CEEDAYS} "Unsupp. Range" message
     * ({@link DateValidationService#MSG_UNSUPPORTED_RANGE}). The date-entry transactions {@code
     * COTRN02C} and {@code CORPT00C} (migrated to {@code TranAddService} and {@code ReportService})
     * accept a {@code 2513} result &mdash; {@code IF CSUTLDTC-RESULT-SEV-CD = '0000' ... ELSE IF
     * CSUTLDTC-RESULT-MSG-NUM NOT = '2513'} &mdash; treating it as acceptable even though {@link
     * #valid()} is {@code false}.
     *
     * @return {@code true} iff {@link #messageNumber()} equals {@code "2513"}
     */
    public boolean isToleratedUnsupportedRange() {
      return MSG_UNSUPPORTED_RANGE.equals(messageNumber);
    }
  }

  /**
   * Immutable result of the {@code CCYYMMDD} field edits ({@link
   * DateValidationService#editDateCcyymmdd(String, String)} and {@link
   * DateValidationService#editDateOfBirth(String, String, LocalDate)}).
   *
   * <p>The per-field {@link FieldStatus} values reproduce the {@code FLG-YEAR-*}, {@code
   * FLG-MONTH-*}, and {@code FLG-DAY-*} flags from {@code CSUTLDWY}. The {@code message} reproduces
   * the COBOL {@code WS-RETURN-MSG}: because COBOL only builds the message {@code IF
   * WS-RETURN-MSG-OFF}, the text reflects the <em>first</em> failing field (this choice is
   * preserved here for parity with the common caller behavior).
   *
   * @param valid {@code true} iff no field failed and the Language-Environment backstop reported
   *     severity zero (equivalent to COBOL {@code INPUT-ERROR} being off)
   * @param year the year/century field status
   * @param month the month field status
   * @param day the day field status
   * @param message the first failing field's error text, or an empty string when {@code valid}
   */
  public record DateEditResult(
      boolean valid, FieldStatus year, FieldStatus month, FieldStatus day, String message) {

    /**
     * Field validity flag mirroring the {@code CSUTLDWY} {@code FLG-*} 88-levels: {@code
     * FLG-*-ISVALID} ({@code LOW-VALUES}) &rarr; {@link #VALID}; {@code '0'} &rarr; {@link
     * #NOT_OK}; {@code 'B'} &rarr; {@link #BLANK}.
     */
    public enum FieldStatus {
      /** Field passed all edits ({@code FLG-*-ISVALID}, {@code LOW-VALUES}). */
      VALID,
      /** Field is present but failed an edit ({@code FLG-*-NOT-OK}, {@code '0'}). */
      NOT_OK,
      /** Field was not supplied ({@code FLG-*-BLANK}, {@code 'B'}). */
      BLANK
    }

    /**
     * Readable alias for the auto-generated {@code valid()} accessor.
     *
     * @return {@code true} iff every field edit and the backstop passed
     */
    public boolean isValid() {
      return valid;
    }
  }

  /**
   * Validates a date string against a COBOL picture mask, reproducing {@code CSUTLDTC} (the {@code
   * CDV1} transaction's call to {@code CEEDAYS}).
   *
   * <p>Because {@code CEEDAYS} is unavailable off-host, validation uses strict {@link java.time}
   * parsing and then classifies the parsed date against the {@code CEEDAYS} Lilian supported range
   * (see the class Javadoc). A valid in-range date yields severity {@link #SEVERITY_OK}, message
   * {@code "0000"}, and result text {@link #RESULT_VALID}. A real calendar date before the Lilian
   * epoch (15&nbsp;October&nbsp;1582) yields severity {@code "0003"}, message {@link
   * #MSG_UNSUPPORTED_RANGE} ({@code "2513"}), and result {@link #RESULT_UNSUPPORTED_RANGE} &mdash;
   * the feedback migrated callers tolerate. A proleptic year of {@code 0} yields the non-tolerated
   * {@code "2521"} ({@code FC-YEAR-IN-ERA-ZERO}). Any parse failure yields severity {@code "0003"},
   * message {@code "0000"}, and result text {@link #RESULT_INVALID}. This method never throws for
   * an invalid date.
   *
   * @param date the date text (COBOL {@code LS-DATE}, {@code PIC X(10)}); {@code null} is treated
   *     as spaces
   * @param formatMask the COBOL picture mask (COBOL {@code LS-DATE-FORMAT}, {@code PIC X(10)});
   *     blank or {@code null} defaults to {@link #DEFAULT_FORMAT}
   * @return the validation result, including the byte-faithful 80-character {@code WS-MESSAGE} line
   */
  public static DateValidationResult validateDate(String date, String formatMask) {
    String dateText = (date == null) ? "" : date;
    String mask =
        (formatMask == null || CobolStringUtils.trim(formatMask).isEmpty())
            ? DEFAULT_FORMAT
            : formatMask;

    String severity;
    String messageNumber;
    String resultText;
    boolean valid;
    try {
      DateTimeFormatter formatter =
          DateTimeFormatter.ofPattern(toJavaPattern(mask)).withResolverStyle(ResolverStyle.STRICT);
      LocalDate parsed = LocalDate.parse(CobolStringUtils.trim(dateText), formatter);
      // The date is a real calendar date; now reproduce the CEEDAYS Lilian-range feedback that
      // migrated callers branch on. CEEDAYS can only return a Lilian day number for dates from
      // 15 October 1582 (Lilian day 1) onward, and the AD/CE era it uses has no year zero.
      if (parsed.getYear() == 0) {
        // FC-YEAR-IN-ERA-ZERO (message 2521): non-tolerated error. The COBOL caller branches only
        // on severity == '0000' OR message == '2513', so this rejects exactly like the mainframe.
        severity = SEVERITY_ERROR;
        messageNumber = MSG_YEAR_IN_ERA_ZERO;
        resultText = RESULT_YEAR_IN_ERA_ZERO;
        valid = false;
      } else if (parsed.isBefore(LILIAN_RANGE_START)) {
        // FC-UNSUPP-RANGE (message 2513): a structurally valid date earlier than the Lilian epoch.
        // This is the feedback COTRN02C/CORPT00C (and TranAddService/ReportService) tolerate, so
        // emitting it here makes DateValidationResult#isToleratedUnsupportedRange() observable
        // instead of dead — closing the behavioral-parity gap against the mainframe.
        severity = SEVERITY_ERROR;
        messageNumber = MSG_UNSUPPORTED_RANGE;
        resultText = RESULT_UNSUPPORTED_RANGE;
        valid = false;
      } else {
        // FC-INVALID-DATE (the all-zero "ok" feedback token): valid date within the supported
        // range.
        severity = SEVERITY_OK;
        messageNumber = MSG_NONE;
        resultText = RESULT_VALID;
        valid = true;
      }
    } catch (DateTimeParseException | IllegalArgumentException ex) {
      // Strict parsing rejected the date (or the mask produced an unusable pattern). Mirror the
      // CEEDAYS error outcome (severity 0003) rather than propagating the exception. The specific
      // CEEDAYS sub-code is not reconstructable off-host, but every parse-failure token is a
      // non-2513 error, so all callers reject identically.
      severity = SEVERITY_ERROR;
      messageNumber = MSG_NONE;
      resultText = RESULT_INVALID;
      valid = false;
    }

    String formattedMessage = buildResultLine(severity, messageNumber, resultText, dateText, mask);
    return new DateValidationResult(severity, messageNumber, resultText, formattedMessage, valid);
  }

  /**
   * Field-edits an 8-character {@code CCYYMMDD} date, reproducing the {@code CSUTLDPY} {@code
   * EDIT-DATE-CCYYMMDD} fall-through ({@code EDIT-YEAR-CCYY} &rarr; {@code EDIT-MONTH} &rarr;
   * {@code EDIT-DAY} &rarr; {@code EDIT-DAY-MONTH-YEAR} &rarr; {@code EDIT-DATE-LE}).
   *
   * <p>Each individual field edit runs independently (a failure does not abort the remaining field
   * edits), exactly as the COBOL paragraphs fall through to one another. The returned {@code
   * message} reflects the first failing field, mirroring the COBOL {@code IF WS-RETURN-MSG-OFF}
   * guard. The day-month-year combination checks and the Language-Environment backstop ({@code
   * EDIT-DATE-LE}, delegating to {@link #validateDate(String, String)}) are applied last, only when
   * the individual edits all passed. This method never throws for an invalid date.
   *
   * @param ccyymmdd the date to edit (COBOL {@code WS-EDIT-DATE-CCYYMMDD}); normalized to 8
   *     characters (right-padded / right-truncated) before editing; {@code null} is treated as
   *     spaces
   * @param fieldName the caller's field label (COBOL {@code WS-EDIT-VARIABLE-NAME}) used to prefix
   *     error messages; trimmed via {@link CobolStringUtils#trim(String)}
   * @return the field-edit result
   */
  public static DateEditResult editDateCcyymmdd(String ccyymmdd, String fieldName) {
    String name = CobolStringUtils.trim(fieldName);
    String d8 = CobolStringUtils.padRight(ccyymmdd, CCYYMMDD_LENGTH);
    String ccyyStr = d8.substring(0, 4);
    String mmStr = d8.substring(4, 6);
    String ddStr = d8.substring(6, 8);

    FieldStatusAccumulator acc = new FieldStatusAccumulator();

    // ---- EDIT-YEAR-CCYY: year present, 4-digit numeric, century 19 or 20 ----
    if (isBlank(ccyyStr)) {
      acc.year = BLANK;
      acc.fail(name + " : Year must be supplied.");
    } else if (!isAllDigits(ccyyStr)) {
      acc.year = NOT_OK;
      acc.fail(name + " must be 4 digit number.");
    } else {
      int century = Integer.parseInt(ccyyStr.substring(0, 2));
      if (century == 20 || century == 19) {
        acc.year = VALID;
      } else {
        acc.year = NOT_OK;
        acc.fail(name + " : Century is not valid.");
      }
    }

    // ---- EDIT-MONTH: month present, numeric, 1-12 ----
    boolean monthNumeric = false;
    int month = 0;
    if (isBlank(mmStr)) {
      acc.month = BLANK;
      acc.fail(name + " : Month must be supplied.");
    } else if (!isAllDigits(mmStr)) {
      acc.month = NOT_OK;
      acc.fail(name + ": Month must be a number between 1 and 12.");
    } else {
      month = Integer.parseInt(mmStr);
      if (month >= 1 && month <= 12) {
        acc.month = VALID;
        monthNumeric = true;
      } else {
        acc.month = NOT_OK;
        acc.fail(name + ": Month must be a number between 1 and 12.");
      }
    }

    // ---- EDIT-DAY: day present, numeric, 1-31 ----
    boolean dayNumeric = false;
    int day = 0;
    if (isBlank(ddStr)) {
      acc.day = BLANK;
      acc.fail(name + " : Day must be supplied.");
    } else if (!isAllDigits(ddStr)) {
      acc.day = NOT_OK;
      acc.fail(name + ":day must be a number between 1 and 31.");
    } else {
      day = Integer.parseInt(ddStr);
      if (day >= 1 && day <= 31) {
        acc.day = VALID;
        dayNumeric = true;
      } else {
        acc.day = NOT_OK;
        acc.fail(name + ":day must be a number between 1 and 31.");
      }
    }

    // ---- EDIT-DAY-MONTH-YEAR: cross-field combinations (only meaningful for numeric month/day)
    // ----
    if (monthNumeric && dayNumeric) {
      boolean is31DayMonth =
          month == 1
              || month == 3
              || month == 5
              || month == 7
              || month == 8
              || month == 10
              || month == 12;
      boolean isFebruary = month == 2;

      if (!is31DayMonth && day == 31) {
        acc.month = NOT_OK;
        acc.day = NOT_OK;
        acc.fail(name + ":Cannot have 31 days in this month.");
        return acc.toResult();
      }
      if (isFebruary && day == 30) {
        acc.month = NOT_OK;
        acc.day = NOT_OK;
        acc.fail(name + ":Cannot have 30 days in this month.");
        return acc.toResult();
      }
      if (isFebruary && day == 29 && isAllDigits(ccyyStr)) {
        int ccyy = Integer.parseInt(ccyyStr);
        if (!isLeapYearCobol(ccyy)) {
          acc.year = NOT_OK;
          acc.month = NOT_OK;
          acc.day = NOT_OK;
          acc.fail(name + ":Not a leap year.Cannot have 29 days in this month.");
          return acc.toResult();
        }
      }
    }

    // COBOL L274: if any field is not valid, skip the Language-Environment backstop.
    if (acc.inputError) {
      return acc.toResult();
    }

    // ---- EDIT-DATE-LE: Language-Environment backstop via CSUTLDTC ----
    DateValidationResult le = validateDate(d8, DEFAULT_FORMAT);
    if (!SEVERITY_OK.equals(le.severity())) {
      acc.year = NOT_OK;
      acc.month = NOT_OK;
      acc.day = NOT_OK;
      acc.fail(
          name
              + " validation error Sev code: "
              + le.severity()
              + " Message code: "
              + le.messageNumber());
      return acc.toResult();
    }

    return acc.toResult();
  }

  /**
   * Field-edits a date of birth using {@link LocalDate#now()} as the current date. Equivalent to
   * {@link #editDateOfBirth(String, String, LocalDate)} with today's date.
   *
   * @param ccyymmdd the date of birth (COBOL {@code WS-EDIT-DATE-CCYYMMDD})
   * @param fieldName the caller's field label used to prefix error messages
   * @return the field-edit result, additionally rejecting dates that are today or in the future
   */
  public static DateEditResult editDateOfBirth(String ccyymmdd, String fieldName) {
    return editDateOfBirth(ccyymmdd, fieldName, LocalDate.now());
  }

  /**
   * Field-edits a date of birth and applies the {@code CSUTLDPY} {@code EDIT-DATE-OF-BIRTH}
   * reasonableness check: the current date must be <em>strictly</em> greater than the supplied
   * date, so a date that is today or in the future is rejected.
   *
   * <p>The standard {@code CCYYMMDD} field edits are applied first via {@link
   * #editDateCcyymmdd(String, String)}. The future check is then evaluated independently using
   * strict {@link java.time} parsing: when the supplied date is parseable and {@code today} is not
   * strictly after it, the "cannot be in the future" outcome is returned and <em>takes
   * precedence</em>. This precedence is required so that a syntactically parseable future date
   * whose century is outside the accepted 19/20 range (for example {@code 29991231}) is reported as
   * a future date rather than as a century error, matching the documented caller expectation; the
   * standalone COBOL {@code EDIT-DATE-OF-BIRTH} paragraph likewise performs only the future
   * comparison. When the date is not in the future, the underlying field-edit result is returned
   * unchanged. This method never throws for an invalid date.
   *
   * @param ccyymmdd the date of birth (COBOL {@code WS-EDIT-DATE-CCYYMMDD})
   * @param fieldName the caller's field label used to prefix error messages
   * @param today the date treated as "now"; {@code null} defaults to {@link LocalDate#now()}
   * @return the field-edit result, additionally rejecting dates that are today or in the future
   */
  public static DateEditResult editDateOfBirth(String ccyymmdd, String fieldName, LocalDate today) {
    String name = CobolStringUtils.trim(fieldName);
    LocalDate effectiveToday = (today == null) ? LocalDate.now() : today;
    DateEditResult fieldResult = editDateCcyymmdd(ccyymmdd, fieldName);

    LocalDate dob = tryParseCcyymmdd(ccyymmdd);
    if (dob != null && !effectiveToday.isAfter(dob)) {
      // COBOL: IF WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY ... ELSE reject. today == dob or
      // today < dob both fail the strict-greater test and are rejected as "in the future".
      return new DateEditResult(false, NOT_OK, NOT_OK, NOT_OK, name + ":cannot be in the future ");
    }
    return fieldResult;
  }

  /**
   * Reproduces the exact COBOL leap-year decision from {@code CSUTLDPY} {@code
   * EDIT-DAY-MONTH-YEAR}: when the two-digit year-within-century is zero (for example 1900 or 2000)
   * the divisor is 400, otherwise the divisor is 4, and the year is a leap year when {@code CCYY}
   * is evenly divisible by that divisor.
   *
   * <p>This intentionally differs from {@link java.time.LocalDate#isLeapYear()} so that the
   * original branch (and its observable results, such as 1900 being treated as non-leap and 2000 as
   * leap) is preserved.
   *
   * @param ccyy the 4-digit Gregorian year
   * @return {@code true} iff {@code ccyy} is a leap year under the COBOL rule
   */
  public static boolean isLeapYearCobol(int ccyy) {
    int yy = ccyy % 100;
    return (yy == 0) ? (ccyy % 400 == 0) : (ccyy % 4 == 0);
  }

  /**
   * Translates a COBOL picture mask to a {@link DateTimeFormatter} pattern.
   *
   * <p>The COBOL mask vocabulary ({@code CCYY}/{@code YYYY} &rarr; {@code uuuu}, {@code YY} &rarr;
   * {@code uu}, {@code MM} stays {@code MM}, {@code DD} &rarr; {@code dd}) is mapped to {@link
   * java.time} pattern letters. Literal separators (for example {@code '/'} or {@code '-'}) pass
   * through unchanged. A blank mask or the default {@code "YYYYMMDD"} maps to {@code "uuuuMMdd"}.
   *
   * @param cobolMask the COBOL picture mask (trailing spaces are ignored)
   * @return the equivalent {@link DateTimeFormatter} pattern
   */
  private static String toJavaPattern(String cobolMask) {
    String mask = CobolStringUtils.rtrim(cobolMask);
    if (mask.isEmpty() || DEFAULT_FORMAT.equals(mask)) {
      return "uuuuMMdd";
    }
    String pattern = mask;
    pattern = pattern.replace("CCYY", "uuuu");
    pattern = pattern.replace("YYYY", "uuuu");
    pattern = pattern.replace("YY", "uu");
    pattern = pattern.replace("DD", "dd");
    return pattern;
  }

  /**
   * Builds the 80-character {@code WS-MESSAGE} result line exactly as laid out in {@code CSUTLDTC}
   * / {@code CSUTLDWY}: {@code WS-SEVERITY(4)} + {@code "Mesg Code:"} padded to 11 + {@code
   * WS-MSG-NO(4)} + one space + {@code WS-RESULT(15)} + one space + {@code "TstDate:"} padded to 9
   * + {@code WS-DATE(10)} + one space + {@code "Mask used:"} (10) + {@code WS-DATE-FMT(10)} + one
   * space + three trailing spaces.
   *
   * @param severity the 4-character severity
   * @param messageNumber the 4-character message number
   * @param resultText the 15-character result text
   * @param date the test date as supplied by the caller
   * @param mask the picture mask as supplied by the caller
   * @return the assembled 80-character line
   */
  private static String buildResultLine(
      String severity, String messageNumber, String resultText, String date, String mask) {
    StringBuilder line = new StringBuilder(80);
    line.append(CobolStringUtils.padRight(severity, 4)); // WS-SEVERITY  PIC X(04)
    line.append(CobolStringUtils.padRight("Mesg Code:", 11)); // FILLER       PIC X(11)
    line.append(CobolStringUtils.padRight(messageNumber, 4)); // WS-MSG-NO    PIC X(04)
    line.append(' '); // FILLER       PIC X(01)
    line.append(CobolStringUtils.padRight(resultText, 15)); // WS-RESULT    PIC X(15)
    line.append(' '); // FILLER       PIC X(01)
    line.append(CobolStringUtils.padRight("TstDate:", 9)); // FILLER       PIC X(09)
    line.append(CobolStringUtils.padRight(date, 10)); // WS-DATE      PIC X(10)
    line.append(' '); // FILLER       PIC X(01)
    line.append(CobolStringUtils.padRight("Mask used:", 10)); // FILLER       PIC X(10)
    line.append(CobolStringUtils.padRight(mask, 10)); // WS-DATE-FMT  PIC X(10)
    line.append(' '); // FILLER       PIC X(01)
    line.append(CobolStringUtils.spaces(3)); // FILLER       PIC X(03)
    return line.toString();
  }

  /**
   * Attempts to parse an 8-character {@code CCYYMMDD} date with the strict canonical formatter.
   *
   * @param ccyymmdd the date to parse; {@code null} or unparseable input yields {@code null}
   * @return the parsed {@link LocalDate}, or {@code null} when the input is not a valid date
   */
  private static LocalDate tryParseCcyymmdd(String ccyymmdd) {
    if (ccyymmdd == null) {
      return null;
    }
    String d8 = CobolStringUtils.padRight(ccyymmdd, CCYYMMDD_LENGTH);
    try {
      return LocalDate.parse(CobolStringUtils.trim(d8), CCYYMMDD_FORMATTER);
    } catch (DateTimeParseException ex) {
      return null;
    }
  }

  /**
   * Tests whether a fixed-width field is blank, matching the COBOL {@code EQUAL SPACES OR EQUAL
   * LOW-VALUES} guard (both ASCII spaces and {@code NUL} bytes are treated as blank).
   *
   * @param value the field contents
   * @return {@code true} iff every character is a space or {@code NUL} (or the value is {@code
   *     null})
   */
  private static boolean isBlank(String value) {
    if (value == null) {
      return true;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c != ' ' && c != '\0') {
        return false;
      }
    }
    return true;
  }

  /**
   * Tests whether a field consists solely of ASCII digits, matching the COBOL {@code IS NUMERIC}
   * test for an unsigned numeric-display field.
   *
   * @param value the field contents
   * @return {@code true} iff {@code value} is non-empty and every character is {@code '0'}-{@code
   *     '9'}
   */
  private static boolean isAllDigits(String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  /**
   * Mutable accumulator for the three field-status flags and the first-failure message during a
   * {@code CCYYMMDD} edit. It models the COBOL {@code FLG-*} flags plus the {@code INPUT-ERROR}
   * flag and the {@code IF WS-RETURN-MSG-OFF} (first-message-wins) behavior in one place.
   */
  private static final class FieldStatusAccumulator {
    private DateEditResult.FieldStatus year = VALID;
    private DateEditResult.FieldStatus month = VALID;
    private DateEditResult.FieldStatus day = VALID;
    private boolean inputError = false;
    private String message = null;

    /**
     * Records a field failure: sets {@code INPUT-ERROR} and retains the first failure message only
     * (subsequent calls keep the earliest message), mirroring {@code IF WS-RETURN-MSG-OFF}.
     *
     * @param failureMessage the COBOL {@code WS-RETURN-MSG} text for this failure
     */
    void fail(String failureMessage) {
      inputError = true;
      if (message == null) {
        message = failureMessage;
      }
    }

    /**
     * Builds the immutable result. {@code valid} is the negation of {@code INPUT-ERROR}; the
     * message is normalized to an empty string when no failure was recorded.
     *
     * @return the assembled {@link DateEditResult}
     */
    DateEditResult toResult() {
      return new DateEditResult(!inputError, year, month, day, (message == null) ? "" : message);
    }
  }
}
