package com.aws.carddemo.dto.report;

/**
 * Report title / date-range header for the daily transaction report.
 *
 * <p>Origin: legacy/cpy/CVTRA07Y.cpy (REPORT-NAME-HEADER).
 *
 * <p>This is a 1:1 translation of the {@code REPORT-NAME-HEADER} {@code 01}-level
 * group in copybook {@code CVTRA07Y}, a fixed-width, 115-byte record printed as the
 * top-of-report header line by the daily transaction report writer
 * ({@code batch/TransactionReportJobConfig}, migrated from COBOL program
 * {@code CBTRN03C}). The original COBOL layout is:
 *
 * <pre>
 *   REPT-SHORT-NAME    PIC X(38) VALUE 'DALYREPT'                  (bytes  1-38)
 *   REPT-LONG-NAME     PIC X(41) VALUE 'Daily Transaction Report'  (bytes 39-79)
 *   REPT-DATE-HEADER   PIC X(12) VALUE 'Date Range: '              (bytes 80-91)
 *   REPT-START-DATE    PIC X(10) VALUE SPACES                      (bytes 92-101)
 *   FILLER             PIC X(04) VALUE ' to '                      (bytes 102-105)
 *   REPT-END-DATE      PIC X(10) VALUE SPACES                      (bytes 106-115)
 *   ---------------------------------------------------------------------------
 *   total                                                          115 bytes
 * </pre>
 *
 * <p>Modeling notes:
 * <ul>
 *   <li>Only {@link #reptStartDate} and {@link #reptEndDate} are runtime-variable
 *       (the reporting date window, sourced from {@code WS-START-DATE} /
 *       {@code WS-END-DATE} in {@code CBTRN03C}); every other field is a fixed COBOL
 *       {@code VALUE} literal and is therefore modeled as an immutable
 *       {@code public static final String} constant. Modeling the literals as
 *       constants matches the COBOL {@code VALUE} semantics and prevents accidental
 *       mutation of the fixed report title, subtitle, label, and separator.</li>
 *   <li>The date fields are kept as 10-character {@code String}s (not
 *       {@code java.time.LocalDate}). This is a fixed-width print-layout field and
 *       byte fidelity matters for the flat-file report; the batch layer formats each
 *       date to its 10-character representation (for example {@code MM/DD/YYYY} or
 *       {@code YYYY-MM-DD}) before setting it here.</li>
 *   <li>This class is a pure typed holder. It deliberately does not assemble the
 *       fixed-width byte line itself: padding each field to its X(38)/X(41)/X(12)/
 *       X(10)/X(04)/X(10) width and composing the final line is the responsibility
 *       of the report writer and the {@code FixedWidthRecordMapper} at print time.</li>
 * </ul>
 */
public class ReportNameHeader {

    /**
     * {@code REPT-SHORT-NAME PIC X(38) VALUE 'DALYREPT'}.
     *
     * <p>Fixed short report name. The natural (unpadded) literal is stored here; the
     * report writer right-pads it with spaces to the full X(38) field width at print
     * time.
     */
    public static final String SHORT_NAME = "DALYREPT";

    /**
     * {@code REPT-LONG-NAME PIC X(41) VALUE 'Daily Transaction Report'}.
     *
     * <p>Fixed long report title. The natural (unpadded) literal is stored here; the
     * report writer right-pads it with spaces to the full X(41) field width at print
     * time.
     */
    public static final String LONG_NAME = "Daily Transaction Report";

    /**
     * {@code REPT-DATE-HEADER PIC X(12) VALUE 'Date Range: '}.
     *
     * <p>Fixed label that precedes the reporting date window. The COBOL literal is
     * exactly 12 characters wide, including the single trailing space, so this
     * constant already fills the entire X(12) field.
     */
    public static final String DATE_HEADER = "Date Range: ";

    /**
     * {@code FILLER PIC X(04) VALUE ' to '}.
     *
     * <p>Fixed separator printed between the start and end dates. The COBOL literal
     * is exactly 4 characters wide (leading space, {@code to}, trailing space), so
     * this constant already fills the entire X(04) field.
     */
    public static final String DATE_SEPARATOR = " to ";

    /**
     * {@code REPT-START-DATE PIC X(10)}: start of the reporting window.
     *
     * <p>Runtime-variable 10-character date string (COBOL default {@code SPACES}).
     */
    private String reptStartDate;

    /**
     * {@code REPT-END-DATE PIC X(10)}: end of the reporting window.
     *
     * <p>Runtime-variable 10-character date string (COBOL default {@code SPACES}).
     */
    private String reptEndDate;

    /**
     * Creates an empty header. Mirrors the COBOL {@code VALUE SPACES} default for the
     * two variable date fields, leaving {@link #reptStartDate} and
     * {@link #reptEndDate} {@code null} until populated by the batch writer.
     */
    public ReportNameHeader() {
    }

    /**
     * Creates a header for the supplied reporting date window.
     *
     * @param reptStartDate start of the reporting window as a 10-character date
     *                      string ({@code REPT-START-DATE}); may be {@code null}
     * @param reptEndDate   end of the reporting window as a 10-character date string
     *                      ({@code REPT-END-DATE}); may be {@code null}
     */
    public ReportNameHeader(String reptStartDate, String reptEndDate) {
        this.reptStartDate = reptStartDate;
        this.reptEndDate = reptEndDate;
    }

    /**
     * Returns the start of the reporting window ({@code REPT-START-DATE}).
     *
     * @return the 10-character start-date string, or {@code null} if not set
     */
    public String getReptStartDate() {
        return reptStartDate;
    }

    /**
     * Sets the start of the reporting window ({@code REPT-START-DATE}).
     *
     * @param reptStartDate the 10-character start-date string; may be {@code null}
     */
    public void setReptStartDate(String reptStartDate) {
        this.reptStartDate = reptStartDate;
    }

    /**
     * Returns the end of the reporting window ({@code REPT-END-DATE}).
     *
     * @return the 10-character end-date string, or {@code null} if not set
     */
    public String getReptEndDate() {
        return reptEndDate;
    }

    /**
     * Sets the end of the reporting window ({@code REPT-END-DATE}).
     *
     * @param reptEndDate the 10-character end-date string; may be {@code null}
     */
    public void setReptEndDate(String reptEndDate) {
        this.reptEndDate = reptEndDate;
    }
}
