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
package com.aws.carddemo.exception;

import java.util.Locale;

/**
 * Models the legacy COBOL <em>"unexpected {@code FILE STATUS} &rarr; abend"</em> path.
 *
 * <p>In the daily-posting archetype {@code legacy/app/cbl/CBTRN02C.cbl} every file owns its own
 * two-byte {@code FILE STATUS} field (for example {@code ACCTFILE-STATUS}, {@code DALYTRAN-STATUS},
 * {@code TCATBALF-STATUS}) [L103-L129]. After each I/O verb the program inspects that status and,
 * for <strong>any value other than the ones it expects</strong>, it performs {@code DISPLAY 'ERROR
 * ...'}, {@code MOVE <status> TO IO-STATUS}, {@code PERFORM 9910-DISPLAY-IO-STATUS} and finally
 * {@code PERFORM 9999-ABEND-PROGRAM} (which issues {@code MOVE 999 TO ABCODE} then {@code CALL
 * 'CEE3ABD'} to terminate the run unit) [L241-L251, L362-L367, L488-L492, L707-L711]. This
 * exception is the Java equivalent that the migrated services and batch steps throw on that "other
 * status" path (Agent Action Plan &sect;0.6.4 and &sect;0.6.6).
 *
 * <p><strong>When this exception is (and is not) thrown.</strong> It represents <em>only</em> the
 * unexpected-status branch. The statuses the COBOL programs handle as normal control flow must
 * <em>not</em> be mapped to this type:
 *
 * <ul>
 *   <li>{@code '00'} &mdash; successful I/O (normal return).
 *   <li>{@code '10'} &mdash; end-of-file on a sequential read (reader exhaustion / loop end, not an
 *       error).
 *   <li>{@code '23'} &mdash; record not found, which the translated services map to {@link
 *       java.util.Optional#empty()} or a find-or-create (upsert) rather than an abend.
 * </ul>
 *
 * Service and batch authors must therefore branch on those statuses explicitly and reserve this
 * exception for genuinely unexpected I/O conditions.
 *
 * <p><strong>Operator-display parity.</strong> The class reproduces the COBOL {@code
 * 9910-DISPLAY-IO-STATUS} paragraph [L714-L727] exactly so the externally observable status line is
 * byte-faithful (&sect;0.6.6). See {@link #getFormattedStatus()} for the two rendering branches and
 * {@link #getDisplayMessage()} for the operator line.
 *
 * <p><strong>Two distinct abend codes by layer (both preserved).</strong> The batch abend code is
 * {@code 999} (the {@code CEE3ABD} {@code ABCODE} from {@code 9999-ABEND-PROGRAM}), surfaced here
 * as {@link #BATCH_ABEND_CODE}; the online CICS abend code {@code "9999"} is applied separately by
 * {@code GlobalExceptionHandler} and is intentionally <em>not</em> represented on this class. The
 * two codes are not interchangeable.
 *
 * <p>The type extends {@link CardDemoException} (and is therefore an unchecked {@link
 * RuntimeException}), mirroring the COBOL semantics in which an unrecoverable condition transferred
 * control immediately to the abend paragraph rather than being declared on a call signature.
 *
 * @see CardDemoException
 */
public class IoStatusException extends CardDemoException {

  /**
   * Serialization version identifier.
   *
   * <p>{@link CardDemoException} is {@link java.io.Serializable} (through {@link Throwable});
   * pinning an explicit {@code serialVersionUID} fixes the serialized form and suppresses the
   * {@code serial} lint warning that the project's zero-warning build ({@code -Werror -Xlint:all})
   * would otherwise promote to a build-breaking error.
   */
  private static final long serialVersionUID = 1L;

  /**
   * The batch abend code preserved from {@code 9999-ABEND-PROGRAM} in {@code
   * legacy/app/cbl/CBTRN02C.cbl} [L707-L711], where the program executed {@code MOVE 999 TO ABCODE}
   * immediately before {@code CALL 'CEE3ABD'} to terminate the run unit.
   *
   * <p>The batch layer uses this value when translating an {@code IoStatusException} into a failed
   * Spring Batch step exit status, so the externally observable abend code survives the migration.
   * It is distinct from the online CICS abend code {@code "9999"}, which is applied by {@code
   * GlobalExceptionHandler}.
   */
  public static final int BATCH_ABEND_CODE = 999;

  /**
   * The fixed literal text emitted by the COBOL {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04}
   * statement [L720, L725].
   *
   * <p>The trailing {@code NNNN} is part of the COBOL literal itself (it is <em>not</em> a
   * substitution placeholder); the four-character {@link #getFormattedStatus() formatted status} is
   * appended immediately after this prefix with no separator, exactly as the COBOL {@code DISPLAY}
   * concatenates its operands.
   */
  private static final String STATUS_DISPLAY_PREFIX = "FILE STATUS IS: NNNN";

  /**
   * The two-space value substituted for a {@code null} raw status so that rendering can never throw
   * a {@link NullPointerException}. It also doubles as right-padding for statuses shorter than two
   * characters, mirroring the fixed two-byte COBOL {@code IO-STATUS} field.
   */
  private static final String NULL_STATUS = "  ";

  /**
   * The raw two-character COBOL {@code FILE STATUS} value that triggered the abend (for example
   * {@code "35"}, {@code "37"} or {@code "92"}).
   *
   * <p>Never {@code null}: if {@code null} is supplied at construction it is stored as {@link
   * #NULL_STATUS} (two spaces) to keep status rendering null-safe.
   */
  private final String fileStatus;

  /**
   * The logical file / DD / dataset name that failed (for example {@code "ACCTFILE"}, {@code
   * "DALYTRAN"} or {@code "TCATBALF"}), mirroring the COBOL {@code ASSIGN TO} name shown in the
   * originating {@code DISPLAY 'ERROR ...'} lines. Stored exactly as supplied.
   */
  private final String fileName;

  /**
   * The I/O verb that failed, expressed with the COBOL verb names: {@code OPEN}, {@code READ},
   * {@code WRITE}, {@code REWRITE}, {@code DELETE}, {@code START} or {@code CLOSE}. Stored exactly
   * as supplied.
   */
  private final String operation;

  /**
   * Creates an exception describing an unexpected {@code FILE STATUS} on a file operation.
   *
   * <p>The detail message is built deterministically in the form {@code "I/O error on file <file>
   * during <operation> - FILE STATUS IS: NNNN<formatted-status>"} (see {@link #getDisplayMessage()}
   * for the trailing portion). It contains no timestamps so it remains stable for logging and
   * assertions.
   *
   * @param fileName the logical file / DD / dataset name that failed; stored as supplied (a {@code
   *     null} value renders as the literal {@code "null"} in the message)
   * @param operation the failing I/O verb (for example {@code "READ"}); stored as supplied
   * @param fileStatus the raw two-character COBOL {@code FILE STATUS}; a {@code null} value is
   *     stored as two spaces to keep rendering null-safe
   */
  public IoStatusException(String fileName, String operation, String fileStatus) {
    super(buildMessage(fileName, operation, fileStatus));
    this.fileName = fileName;
    this.operation = operation;
    this.fileStatus = (fileStatus == null) ? NULL_STATUS : fileStatus;
  }

  /**
   * Creates an exception describing an unexpected {@code FILE STATUS}, chaining the underlying
   * cause.
   *
   * <p>The detail message is identical to {@link #IoStatusException(String, String, String)}.
   *
   * @param fileName the logical file / DD / dataset name that failed; stored as supplied (a {@code
   *     null} value renders as the literal {@code "null"} in the message)
   * @param operation the failing I/O verb (for example {@code "READ"}); stored as supplied
   * @param fileStatus the raw two-character COBOL {@code FILE STATUS}; a {@code null} value is
   *     stored as two spaces to keep rendering null-safe
   * @param cause the underlying failure (for example a JDBC or file I/O exception); later
   *     retrievable via {@link #getCause()}. A {@code null} value indicates the cause is
   *     nonexistent or unknown.
   */
  public IoStatusException(String fileName, String operation, String fileStatus, Throwable cause) {
    super(buildMessage(fileName, operation, fileStatus), cause);
    this.fileName = fileName;
    this.operation = operation;
    this.fileStatus = (fileStatus == null) ? NULL_STATUS : fileStatus;
  }

  /**
   * Returns the raw two-character COBOL {@code FILE STATUS} that triggered this exception.
   *
   * @return the stored status; never {@code null} (two spaces are returned when {@code null} was
   *     supplied at construction)
   */
  public String getFileStatus() {
    return fileStatus;
  }

  /**
   * Returns the logical file / DD / dataset name associated with the failed operation.
   *
   * @return the file name exactly as supplied at construction (may be {@code null})
   */
  public String getFileName() {
    return fileName;
  }

  /**
   * Returns the I/O verb that failed.
   *
   * @return the operation name exactly as supplied at construction (may be {@code null})
   */
  public String getOperation() {
    return operation;
  }

  /**
   * Renders the four-character {@code IO-STATUS-04} value exactly as the COBOL {@code
   * 9910-DISPLAY-IO-STATUS} paragraph [L714-L727].
   *
   * <p>The raw {@link #getFileStatus() file status} is first normalized to exactly two characters
   * (short values are right-padded with spaces, longer values are truncated) so the result is
   * always four characters. With {@code c1} as the first character and {@code c2} as the second:
   *
   * <ul>
   *   <li><strong>Branch A</strong> &mdash; taken when the status is <em>not</em> two ASCII digits
   *       <em>or</em> {@code c1 == '9'} (COBOL {@code IF IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'}).
   *       The result is {@code c1} kept as-is, followed by the three-digit, zero-padded decimal
   *       value of the raw byte {@code c2} ({@code (c2 & 0xFF) % 1000}; the modulo reproduces the
   *       {@code PIC 999} three-digit truncation). This is the VSAM extended-status form in which
   *       the second byte is a binary reason code rather than a character digit &mdash; for example
   *       a {@code '9'} class status whose second byte has value {@code 4} renders as {@code
   *       "9004"}.
   *   <li><strong>Branch B</strong> &mdash; taken otherwise (both bytes are ASCII digits and {@code
   *       c1 != '9'}; COBOL {@code MOVE '0000' TO IO-STATUS-04} then {@code MOVE IO-STATUS TO
   *       IO-STATUS-04(3:2)}). The two-character status is left zero-padded to width four &mdash;
   *       for example {@code "23"} renders as {@code "0023"} and {@code "10"} as {@code "0010"}.
   * </ul>
   *
   * @return the four-character formatted status; never {@code null} and always of length 4
   */
  public String getFormattedStatus() {
    return formatStatus(fileStatus);
  }

  /**
   * Returns the operator display line emitted by the COBOL {@code DISPLAY 'FILE STATUS IS: NNNN'
   * IO-STATUS-04} statement, byte-faithfully [L720, L725].
   *
   * <p>The result is the fixed prefix {@code "FILE STATUS IS: NNNN"} immediately followed by the
   * four-character {@link #getFormattedStatus() formatted status} (no separator) &mdash; for
   * example a status of {@code "23"} yields {@code "FILE STATUS IS: NNNN0023"}. This string is
   * externally observable and is preserved verbatim (&sect;0.6.6).
   *
   * @return the operator display line; never {@code null}
   */
  public String getDisplayMessage() {
    return STATUS_DISPLAY_PREFIX + getFormattedStatus();
  }

  /**
   * Builds the deterministic detail message used by the constructors.
   *
   * <p>Implemented as a {@code static} helper so it can be evaluated within the mandatory
   * first-statement {@code super(...)} call, before the instance fields are assigned.
   *
   * @param fileName the file name (may be {@code null})
   * @param operation the failing I/O verb (may be {@code null})
   * @param fileStatus the raw two-character status (may be {@code null})
   * @return the composed detail message
   */
  private static String buildMessage(String fileName, String operation, String fileStatus) {
    return "I/O error on file "
        + fileName
        + " during "
        + operation
        + " - "
        + STATUS_DISPLAY_PREFIX
        + formatStatus(fileStatus);
  }

  /**
   * Reproduces the four-character {@code IO-STATUS-04} rendering of {@code 9910-DISPLAY-IO-STATUS}.
   *
   * <p>Shared by {@link #getFormattedStatus()} and {@link #buildMessage(String, String, String)} so
   * the constructor-time message and the post-construction accessor always agree.
   *
   * @param rawStatus the raw status (may be {@code null} or any length)
   * @return the four-character formatted status
   */
  private static String formatStatus(String rawStatus) {
    String status = normalizeToTwoChars(rawStatus);
    char firstChar = status.charAt(0);
    char secondChar = status.charAt(1);
    if (!isTwoAsciiDigits(status) || firstChar == '9') {
      // Branch A: VSAM extended status — the second byte is a binary reason code, formatted as a
      // three-digit decimal (PIC 999) and prefixed by the first character kept verbatim.
      int reasonByte = (secondChar & 0xFF) % 1000;
      return String.valueOf(firstChar) + String.format(Locale.ROOT, "%03d", reasonByte);
    }
    // Branch B: ordinary numeric status, left zero-padded to width four (the COBOL (3:2) overlay).
    return "00" + status;
  }

  /**
   * Normalizes a raw status to exactly two characters, mirroring the fixed two-byte COBOL {@code
   * IO-STATUS} field.
   *
   * <p>A {@code null} value becomes two spaces; a value shorter than two characters is right-padded
   * with spaces; a value longer than two characters is truncated to its first two characters. The
   * result is therefore always exactly two characters, which guarantees the four-character width of
   * {@link #formatStatus(String)} and prevents any {@link NullPointerException} or {@link
   * IndexOutOfBoundsException} during rendering.
   *
   * @param rawStatus the raw status (may be {@code null} or any length)
   * @return a string of exactly two characters
   */
  private static String normalizeToTwoChars(String rawStatus) {
    String status = (rawStatus == null) ? NULL_STATUS : rawStatus;
    int length = status.length();
    if (length == 2) {
      return status;
    }
    if (length < 2) {
      return (status + NULL_STATUS).substring(0, 2);
    }
    return status.substring(0, 2);
  }

  /**
   * Tests whether a two-character status consists solely of ASCII digits {@code '0'}-{@code '9'},
   * reproducing the COBOL {@code IO-STATUS NUMERIC} class test.
   *
   * <p>Only ASCII digits qualify (unlike {@link Character#isDigit(char)}, which also accepts
   * non-ASCII decimal digits), matching the COBOL semantics exactly.
   *
   * @param status a string of exactly two characters
   * @return {@code true} if both characters are ASCII digits, otherwise {@code false}
   */
  private static boolean isTwoAsciiDigits(String status) {
    char c0 = status.charAt(0);
    char c1 = status.charAt(1);
    return c0 >= '0' && c0 <= '9' && c1 >= '0' && c1 <= '9';
  }
}
