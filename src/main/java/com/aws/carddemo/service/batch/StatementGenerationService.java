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
package com.aws.carddemo.service.batch;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.service.batch.FileIoService.WorkArea;
import com.aws.carddemo.util.CobolStringUtils;
import com.aws.carddemo.util.NumberFormatter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Account-statement generation batch service — the Java/Spring translation of the COBOL program
 * {@code legacy/app/cbl/CBSTM03A.CBL} (924 lines), preserving 100% behavioral parity with the
 * legacy mainframe implementation (AAP §0.1.1, §0.7.1).
 *
 * <p>For every card cross-reference record, the service produces a fixed-width <em>plain-text</em>
 * statement (legacy {@code STMTFILE}, {@code LRECL=80}) <strong>and</strong> a matching
 * <em>HTML</em> statement (legacy {@code HTMLFILE}, {@code LRECL=100}). Each statement carries the
 * customer name and address, the basic account details (account id, current balance, FICO score), a
 * per-transaction detail list and an expense total. The two outputs are emitted line-by-line to two
 * injected {@link Consumer Consumer&lt;String&gt;} sinks so that the physical writers can live in
 * {@code com.aws.carddemo.batch}; this service only formats lines (mirroring the sink pattern used
 * by the sibling report/reject services).
 *
 * <h2>Architecture and parity rules</h2>
 *
 * <ul>
 *   <li><b>All input file I/O is delegated to {@link FileIoService}</b> (the translation of the
 *       called subroutine {@code CBSTM03B}). The legacy {@code CALL 'CBSTM03B' USING WS-M03B-AREA}
 *       becomes {@link FileIoService#process(WorkArea)} calls; this service never injects JPA
 *       repositories and never performs its own persistence reads (AAP §0.4.2, §0.3.3).
 *   <li><b>z/OS plumbing is dropped.</b> The PSA/TCB/TIOT control-block walk (CBSTM03A lines
 *       262-291) and the {@code ALTER ... GO TO} / {@code WS-FL-DD} state machine ({@code
 *       0000-START}, {@code 8100}-{@code 8599}) are mainframe control hacks whose net effect is
 *       only to (a) open the four input files and (b) read the entire transaction file into an
 *       in-memory table. That net effect is reproduced with clean Java init code.
 *   <li><b>Decimal fidelity (AAP §0.6.1):</b> all money is {@link BigDecimal}; never {@code float}
 *       or {@code double}. The running expense total ({@code WS-TOTAL-AMT}, {@code S9(9)V99}) is a
 *       scale-2 {@link BigDecimal}.
 *   <li><b>Control-flow preservation (AAP §0.6.7):</b> each retained numbered paragraph maps to one
 *       private method invoked in the same order.
 *   <li><b>FILE STATUS to exceptions (AAP §0.6.4):</b> {@code '00'} is success; an open/close also
 *       accepts {@code '04'} (CBSTM03A lines 736, 748); {@code '10'} is end-of-file on the
 *       sequential transaction-table read loop (loop end, not an error); any other status — and a
 *       missing customer or account on the keyed reads (CBSTM03A accepts only {@code '00'} in
 *       {@code 2000-CUSTFILE-GET}/{@code 3000-ACCTFILE-GET}) — triggers a fatal abend modeled as an
 *       {@link IoStatusException} (mirrors {@code 9999-ABEND-PROGRAM}).
 * </ul>
 *
 * <h2>Paragraph-to-method traceability (for {@code docs/traceability-matrix.md})</h2>
 *
 * <table>
 *   <caption>CBSTM03A paragraph mapping</caption>
 *   <tr><th>COBOL (CBSTM03A)</th><th>Java method</th></tr>
 *   <tr><td>PROCEDURE DIVISION + 0000-START/8100-8400 (262-314, 726-816); z/OS ALTER/TIOT
 *       plumbing dropped</td><td>{@link #run(Consumer, Consumer)} init</td></tr>
 *   <tr><td>8100-TRNXFILE-OPEN / 8500-READTRNX-READ / 8599-EXIT (730-853)</td>
 *       <td>{@link #openTrnxfile()} / {@link #readTrnxNext()} / {@link #buildTrnxTable()}</td></tr>
 *   <tr><td>1000-MAINLINE (316)</td><td>{@link #mainline()}</td></tr>
 *   <tr><td>1000-XREFFILE-GET-NEXT (345)</td><td>{@link #xreffileGetNext()}</td></tr>
 *   <tr><td>2000-CUSTFILE-GET (368)</td><td>{@link #custfileGet()}</td></tr>
 *   <tr><td>3000-ACCTFILE-GET (392)</td><td>{@link #acctfileGet()}</td></tr>
 *   <tr><td>5000-CREATE-STATEMENT (458)</td><td>{@link #createStatement()}</td></tr>
 *   <tr><td>5100-WRITE-HTML-HEADER (506)</td><td>{@link #writeHtmlHeader()}</td></tr>
 *   <tr><td>5200-WRITE-HTML-NMADBS (558)</td><td>{@link #writeHtmlNmadbs()}</td></tr>
 *   <tr><td>6000-WRITE-TRANS (675)</td><td>{@link #writeTrans(Transaction)}</td></tr>
 *   <tr><td>4000-TRNXFILE-GET (416)</td><td>{@link #trnxfileGet()}</td></tr>
 *   <tr><td>9100/9200/9300/9400-*-CLOSE (856-919)</td>
 *       <td>{@link #closeFile(String, String)} per ddname</td></tr>
 *   <tr><td>9999-ABEND-PROGRAM (921)</td><td>{@link #abend(String, String, String)}</td></tr>
 *   <tr><td>9999-GOBACK (341)</td><td>normal {@link #run(Consumer, Consumer)} return</td></tr>
 * </table>
 *
 * <h2>Threading</h2>
 *
 * <p>The legacy program is a single-threaded batch step and this service preserves that contract:
 * {@link #run(Consumer, Consumer)} mutates per-run instance state ({@code WORKING-STORAGE}
 * equivalents) and is therefore <strong>not</strong> thread-safe. A single bean instance must be
 * driven by one batch thread at a time, exactly as the JCL step {@code STEP040} of {@code
 * legacy/app/jcl/CREASTMT.JCL} invokes {@code CBSTM03A} once per run.
 */
@Service
public class StatementGenerationService {

  private static final Logger LOG = LoggerFactory.getLogger(StatementGenerationService.class);

  /**
   * Secondary acceptable FILE STATUS on open/close: {@code '04'} (record-length / already-open
   * informational), accepted alongside {@code '00'} by the CBSTM03A open/close paragraphs (lines
   * 736, 748, 862, ...).
   */
  private static final String STATUS_OPEN_WARNING = "04";

  /** Scale-2 zero used to (re)initialize {@code WS-TOTAL-AMT} ({@code MOVE ZERO}). */
  private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");

  /**
   * Replicates {@code CREASTMT.JCL} STEP010 {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} — ascending
   * by card number (positions 263-278) then by transaction id (positions 1-16). Both keys are
   * compared as full 16-byte fixed-width {@code CH} (alphanumeric) fields, matching the byte-level
   * COBOL collation, so the grouping result is independent of the order in which {@link
   * FileIoService} returns rows (AAP §0.6.3).
   */
  private static final Comparator<Transaction> BY_CARD_THEN_TRAN =
      Comparator.comparing((Transaction t) -> CobolStringUtils.padRight(t.getTranCardNum(), 16))
          .thenComparing(t -> CobolStringUtils.padRight(t.getTranId(), 16));

  // ---------------------------------------------------------------------------------------------
  // Text statement literals (verbatim from CBSTM03A STATEMENT-LINES, WS lines 88-146).
  // ---------------------------------------------------------------------------------------------

  private static final String LABEL_ACCT_ID = "Account ID         :";
  private static final String LABEL_CURR_BAL = "Current Balance    :";
  private static final String LABEL_FICO = "FICO Score         :";
  private static final String TEXT_TOTAL_EXP = "Total EXP:";
  private static final String DOLLAR = "$";

  /** {@code ST-LINE5}/{@code ST-LINE10}/{@code ST-LINE12}: 80 dashes. */
  private static final String DASHES_80 = "-".repeat(80);

  /** {@code ST-LINE0}: 31 stars + {@code START OF STATEMENT} + 31 stars (80 chars). */
  private static final String ST_LINE0 = "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31);

  /** {@code ST-LINE6}: 33 spaces + {@code Basic Details} padded to 14 + 33 spaces. */
  private static final String ST_LINE6 = " ".repeat(33) + "Basic Details " + " ".repeat(33);

  /** {@code ST-LINE11}: 30 spaces + {@code TRANSACTION SUMMARY } (20) + 30 spaces. */
  private static final String ST_LINE11 = " ".repeat(30) + "TRANSACTION SUMMARY " + " ".repeat(30);

  /**
   * {@code ST-LINE13}: {@code Tran ID}(16) + {@code Tran Details}(51) + {@code Tran Amount}(13).
   */
  private static final String ST_LINE13 =
      "Tran ID         " + "Tran Details    " + " ".repeat(35) + "  Tran Amount";

  /** {@code ST-LINE15}: 32 stars + {@code END OF STATEMENT} + 32 stars (80 chars). */
  private static final String ST_LINE15 = "*".repeat(32) + "END OF STATEMENT" + "*".repeat(32);

  // ---------------------------------------------------------------------------------------------
  // HTML literals (verbatim from CBSTM03A HTML-LINES 88-level VALUEs, WS lines 148-223, and the
  // STRING fragments in 5100/5200/6000). Spacing differences below are intentional and must be
  // preserved byte-for-byte (e.g. "<table  align" has two spaces; the 5px; vs 5px;background
  // variations differ between the colspan cells and the width cells).
  // ---------------------------------------------------------------------------------------------

  private static final String HTML_L01 = "<!DOCTYPE html>";
  private static final String HTML_L02 = "<html lang=\"en\">";
  private static final String HTML_L03 = "<head>";
  private static final String HTML_L04 = "<meta charset=\"utf-8\">";
  private static final String HTML_L05 = "<title>HTML Table Layout</title>";
  private static final String HTML_L06 = "</head>";
  private static final String HTML_L07 = "<body style=\"margin:0px;\">";
  private static final String HTML_L08 =
      "<table  align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">";
  private static final String HTML_LTRS = "<tr>";
  private static final String HTML_LTRE = "</tr>";
  private static final String HTML_LTDE = "</td>";
  private static final String HTML_L10 =
      "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">";
  private static final String HTML_L15 =
      "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">";
  private static final String HTML_L16 = "<p style=\"font-size:16px\">Bank of XYZ</p>";
  private static final String HTML_L17 = "<p>410 Terry Ave N</p>";
  private static final String HTML_L18 = "<p>Seattle WA 99999</p>";
  private static final String HTML_L22_35 =
      "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">";
  private static final String HTML_L30_42 =
      "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">";
  private static final String HTML_L31 = "<p style=\"font-size:16px\">Basic Details</p>";
  private static final String HTML_L43 = "<p style=\"font-size:16px\">Transaction Summary</p>";
  private static final String HTML_L47 =
      "<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";
  private static final String HTML_L48 = "<p style=\"font-size:16px\">Tran ID</p>";
  private static final String HTML_L50 =
      "<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";
  private static final String HTML_L51 = "<p style=\"font-size:16px\">Tran Details</p>";
  private static final String HTML_L53 =
      "<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;\">";
  private static final String HTML_L54 = "<p style=\"font-size:16px\">Amount</p>";
  private static final String HTML_L58 =
      "<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";
  private static final String HTML_L61 =
      "<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";
  private static final String HTML_L64 =
      "<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;\">";
  private static final String HTML_L75 = "<h3>End of Statement</h3>";
  private static final String HTML_L78 = "</table>";
  private static final String HTML_L79 = "</body>";
  private static final String HTML_L80 = "</html>";

  /** Dynamic-line fragments (CBSTM03A 5100/5200/6000 {@code STRING} statements). */
  private static final String HTML_L11_PREFIX = "<h3>Statement for Account Number: ";

  private static final String HTML_H3_CLOSE = "</h3>";
  private static final String HTML_P_OPEN = "<p>";
  private static final String HTML_P_CLOSE = "</p>";
  private static final String HTML_NAME_PREFIX = "<p style=\"font-size:16px\">";
  private static final String BASIC_ACCT_PREFIX = "<p>Account ID         : ";
  private static final String BASIC_BAL_PREFIX = "<p>Current Balance    : ";
  private static final String BASIC_FICO_PREFIX = "<p>FICO Score         : ";

  /**
   * Two-space delimiter used by the COBOL {@code STRING ... DELIMITED BY ' '} name/address lines.
   */
  private static final String DOUBLE_SPACE = "  ";

  // ---------------------------------------------------------------------------------------------
  // Collaborators.
  // ---------------------------------------------------------------------------------------------

  /** Translation of {@code CBSTM03B}; the sole gateway to the four input files. */
  private final FileIoService fileIoService;

  // ---------------------------------------------------------------------------------------------
  // Per-run state (reset at the top of run(...)). Equivalent to CBSTM03A WORKING-STORAGE; not
  // thread-safe by design (single-threaded batch step).
  // ---------------------------------------------------------------------------------------------

  private Consumer<String> stmtSink;
  private Consumer<String> htmlSink;
  private boolean endOfFile;
  private BigDecimal wsTotalAmt;
  private Map<String, List<Transaction>> trnxTable;
  private CardXref cardXref;
  private Customer customer;
  private Account account;

  // Statement working fields (CBSTM03A ST-NAME / ST-ADD1..3 / ST-ACCT-ID / ST-CURR-BAL /
  // ST-FICO-SCORE), rebuilt fresh for each statement (mirrors INITIALIZE STATEMENT-LINES).
  private String stName;
  private String stAdd1;
  private String stAdd2;
  private String stAdd3;
  private String stAcctId;
  private String stCurrBal;
  private String stFico;

  /**
   * Creates the service with its single collaborator injected (constructor injection; the field is
   * {@code final}).
   *
   * @param fileIoService the {@code CBSTM03B} translation used for all input file I/O
   */
  public StatementGenerationService(FileIoService fileIoService) {
    this.fileIoService = fileIoService;
  }

  /**
   * Generates every account statement, emitting plain-text lines to {@code stmtSink} and HTML lines
   * to {@code htmlSink}. This is the translation of the CBSTM03A PROCEDURE DIVISION ({@code
   * 9999-GOBACK} at line 341 corresponds to this method returning normally).
   *
   * <p>Net order reproduced from the legacy program (the z/OS TIOT "Running JCL"/"DD Names"
   * displays at lines 270-291 and the {@code ALTER}/{@code GO TO} state machine are collapsed):
   *
   * <ol>
   *   <li>reset run-state and log the start banner;
   *   <li>open the transaction file and read it entirely into the in-memory table ({@link
   *       #buildTrnxTable()} — collapses {@code 8100}/{@code 8500}/{@code 8599});
   *   <li>open {@code XREFFILE}, {@code CUSTFILE}, {@code ACCTFILE} ({@code 8200}/{@code
   *       8300}/{@code 8400});
   *   <li>run the main per-cross-reference loop ({@link #mainline()} — {@code 1000-MAINLINE});
   *   <li>close the four input files ({@code 9100}-{@code 9400}). The two output sinks need no
   *       explicit open/close (the COBOL {@code OPEN}/{@code CLOSE STMT-FILE HTML-FILE} at lines
   *       293 and 339 have no sink equivalent).
   * </ol>
   *
   * @param stmtSink receives each fixed-width (80-char) plain-text statement line ({@code WRITE
   *     FD-STMTFILE-REC}); must not be {@code null}
   * @param htmlSink receives each fixed-width (100-char) HTML statement line ({@code WRITE
   *     FD-HTMLFILE-REC}); must not be {@code null}
   * @throws IoStatusException if any input file open/close or read fails, or a required customer or
   *     account record is missing (mirrors {@code 9999-ABEND-PROGRAM})
   */
  public void run(Consumer<String> stmtSink, Consumer<String> htmlSink) {
    this.stmtSink = Objects.requireNonNull(stmtSink, "stmtSink");
    this.htmlSink = Objects.requireNonNull(htmlSink, "htmlSink");
    this.endOfFile = false;
    this.wsTotalAmt = ZERO_AMOUNT;
    this.trnxTable = new LinkedHashMap<>();
    this.cardXref = null;
    this.customer = null;
    this.account = null;

    LOG.info("CBSTM03A statement generation started.");

    // INITIALIZE WS-TRNX-TABLE (line 294) + collapsed ALTER machine (0000-START/8100-8400):
    // open the transaction file and read it all into the in-memory table, then open the three
    // keyed/sequential lookup files.
    buildTrnxTable(); // 8100-TRNXFILE-OPEN + 8500-READTRNX-READ + 8599-EXIT
    openFile(WorkArea.XREFFILE, "XREFFILE"); // 8200-XREFFILE-OPEN
    openFile(WorkArea.CUSTFILE, "CUSTFILE"); // 8300-CUSTFILE-OPEN
    openFile(WorkArea.ACCTFILE, "ACCTFILE"); // 8400-ACCTFILE-OPEN

    mainline(); // 1000-MAINLINE

    closeFile(WorkArea.TRNXFILE, "TRNXFILE"); // 9100-TRNXFILE-CLOSE
    closeFile(WorkArea.XREFFILE, "XREFFILE"); // 9200-XREFFILE-CLOSE
    closeFile(WorkArea.CUSTFILE, "CUSTFILE"); // 9300-CUSTFILE-CLOSE
    closeFile(WorkArea.ACCTFILE, "ACCTFILE"); // 9400-ACCTFILE-CLOSE

    LOG.info("CBSTM03A statement generation completed.");
  }

  // ---------------------------------------------------------------------------------------------
  // Main loop (1000-MAINLINE, lines 316-342).
  // ---------------------------------------------------------------------------------------------

  /**
   * Drives one statement per card cross-reference until end-of-file on {@code XREFFILE} ({@code
   * 1000-MAINLINE}, CBSTM03A lines 316-342). The redundant inner end-of-file guard mirrors the
   * COBOL {@code PERFORM UNTIL END-OF-FILE = 'Y'} wrapping an {@code IF END-OF-FILE = 'N'} block
   * (control-flow parity, AAP §0.6.7).
   */
  private void mainline() {
    while (!endOfFile) { // PERFORM UNTIL END-OF-FILE = 'Y'
      if (!endOfFile) { // IF END-OF-FILE = 'N'
        xreffileGetNext(); // 1000-XREFFILE-GET-NEXT
        if (!endOfFile) { // IF END-OF-FILE = 'N'
          custfileGet(); // 2000-CUSTFILE-GET
          acctfileGet(); // 3000-ACCTFILE-GET
          createStatement(); // 5000-CREATE-STATEMENT
          wsTotalAmt = ZERO_AMOUNT; // MOVE ZERO TO WS-TOTAL-AMT (line 325)
          trnxfileGet(); // 4000-TRNXFILE-GET
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Sequential / keyed reads through FileIoService (the CBSTM03B contract).
  // ---------------------------------------------------------------------------------------------

  /**
   * Reads the next card cross-reference record ({@code 1000-XREFFILE-GET-NEXT}, CBSTM03A lines
   * 345-366). Status {@code '00'} sets the current {@link CardXref} ({@code MOVE WS-M03B-FLDT TO
   * CARD-XREF-RECORD}); {@code '10'} sets end-of-file; any other status abends.
   */
  private void xreffileGetNext() {
    WorkArea wa = new WorkArea(WorkArea.XREFFILE, WorkArea.Operation.READ);
    fileIoService.process(wa);
    String rc = wa.getReturnCode();
    if (FileIoService.STATUS_OK.equals(rc)) {
      this.cardXref = wa.getRecordAs(CardXref.class);
    } else if (FileIoService.STATUS_EOF.equals(rc)) {
      this.endOfFile = true; // MOVE 'Y' TO END-OF-FILE
    } else {
      throw abend("XREFFILE", "READ", rc);
    }
  }

  /**
   * Reads the customer keyed by {@code XREF-CUST-ID} ({@code 2000-CUSTFILE-GET}, CBSTM03A lines
   * 368-390). The key is the 9-digit zero-padded customer id ({@code MOVE XREF-CUST-ID TO
   * WS-M03B-KEY}; {@code WS-M03B-KEY-LN = LENGTH OF XREF-CUST-ID = 9}). Only status {@code '00'} is
   * accepted; a missing customer ({@code '23'} or any other status) is a fatal abend.
   */
  private void custfileGet() {
    String key = CobolStringUtils.padLeftZeros(cardXref.getXrefCustId(), 9);
    WorkArea wa = new WorkArea(WorkArea.CUSTFILE, WorkArea.Operation.READ_KEY, key, 9);
    fileIoService.process(wa);
    String rc = wa.getReturnCode();
    if (FileIoService.STATUS_OK.equals(rc)) {
      this.customer = wa.getRecordAs(Customer.class);
    } else {
      throw abend("CUSTFILE", "READ", rc);
    }
  }

  /**
   * Reads the account keyed by {@code XREF-ACCT-ID} ({@code 3000-ACCTFILE-GET}, CBSTM03A lines
   * 392-414). The key is the 11-digit zero-padded account id ({@code WS-M03B-KEY-LN = LENGTH OF
   * XREF-ACCT-ID = 11}). Only status {@code '00'} is accepted; a missing account is a fatal abend.
   */
  private void acctfileGet() {
    String key = CobolStringUtils.padLeftZeros(cardXref.getXrefAcctId(), 11);
    WorkArea wa = new WorkArea(WorkArea.ACCTFILE, WorkArea.Operation.READ_KEY, key, 11);
    fileIoService.process(wa);
    String rc = wa.getReturnCode();
    if (FileIoService.STATUS_OK.equals(rc)) {
      this.account = wa.getRecordAs(Account.class);
    } else {
      throw abend("ACCTFILE", "READ", rc);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Transaction-table build (8100-TRNXFILE-OPEN / 8500-READTRNX-READ / 8599-EXIT, lines 730-853).
  // ---------------------------------------------------------------------------------------------

  /**
   * Reads the entire transaction file into the in-memory table grouped by card number ({@code
   * 8599-EXIT} plus the {@code CREASTMT.JCL} STEP010 pre-sort). The legacy {@code WS-TRNX-TABLE}
   * caps at 51 cards x 10 transactions; this implementation uses dynamic collections (no cap), a
   * behavior-preserving improvement for in-range data (the sample fixtures fit within the legacy
   * caps).
   *
   * <p>Because the input has been pre-sorted by {@code (cardNum ASC, tranId ASC)} (replicated here
   * via {@link #BY_CARD_THEN_TRAN}, independent of {@link FileIoService} read order), every card's
   * rows are contiguous, so a {@link LinkedHashMap} keyed by card number yields exactly one entry
   * per card while preserving card order — the Java embodiment of {@code WS-TRNX-TABLE} + {@code
   * WS-TRCT}.
   */
  private void buildTrnxTable() {
    Transaction first = openTrnxfile(); // 8100: OPEN + first READ
    List<Transaction> buffer = new ArrayList<>();
    buffer.add(first);
    Transaction tran;
    while ((tran = readTrnxNext()) != null) { // 8500-READTRNX-READ loop
      buffer.add(tran);
    }

    buffer.sort(BY_CARD_THEN_TRAN); // CREASTMT STEP010 SORT FIELDS=(263,16,CH,A,1,16,CH,A)

    this.trnxTable = new LinkedHashMap<>();
    for (Transaction t : buffer) {
      this.trnxTable
          .computeIfAbsent(cardKey(t.getTranCardNum()), unused -> new ArrayList<>())
          .add(t);
    }
  }

  /**
   * Opens {@code TRNXFILE} and reads its first record ({@code 8100-TRNXFILE-OPEN}, CBSTM03A lines
   * 730-764). The open accepts {@code '00'} or {@code '04'}; the first read likewise requires
   * {@code '00'} or {@code '04'} — so an empty transaction file ({@code '10'} on the first read)
   * abends, exactly as the legacy program does (lines 745-758).
   *
   * @return the first transaction record
   */
  private Transaction openTrnxfile() {
    openFile(WorkArea.TRNXFILE, "TRNXFILE"); // OPEN, accept '00'/'04'
    WorkArea wa = new WorkArea(WorkArea.TRNXFILE, WorkArea.Operation.READ);
    fileIoService.process(wa);
    if (!isOpenStatusAcceptable(wa.getReturnCode())) {
      throw abend("TRNXFILE", "READ", wa.getReturnCode());
    }
    return wa.getRecordAs(Transaction.class);
  }

  /**
   * Reads the next sequential transaction record ({@code 8500-READTRNX-READ} read step, CBSTM03A
   * lines 838-850): {@code '00'} returns the record, {@code '10'} returns {@code null} (loop end,
   * {@code 8599-EXIT}), any other status abends.
   *
   * @return the next transaction, or {@code null} at end-of-file
   */
  private Transaction readTrnxNext() {
    WorkArea wa = new WorkArea(WorkArea.TRNXFILE, WorkArea.Operation.READ);
    fileIoService.process(wa);
    String rc = wa.getReturnCode();
    if (FileIoService.STATUS_OK.equals(rc)) {
      return wa.getRecordAs(Transaction.class);
    }
    if (FileIoService.STATUS_EOF.equals(rc)) {
      return null;
    }
    throw abend("TRNXFILE", "READ", rc);
  }

  // ---------------------------------------------------------------------------------------------
  // Statement creation (5000-CREATE-STATEMENT, lines 458-504).
  // ---------------------------------------------------------------------------------------------

  /**
   * Builds and emits one statement's header, customer/account block and summary headings ({@code
   * 5000-CREATE-STATEMENT}, CBSTM03A lines 458-504). The HTML header and name/address/basic rows
   * are interleaved exactly as the COBOL performs {@code 5100-WRITE-HTML-HEADER} and {@code
   * 5200-WRITE-HTML-NMADBS} between the text-line writes.
   */
  private void createStatement() {
    emitStmt(ST_LINE0); // WRITE FD-STMTFILE-REC FROM ST-LINE0
    writeHtmlHeader(); // 5100-WRITE-HTML-HEADER

    // ST-NAME: first/middle/last each DELIMITED BY ' ' (truncated at the first embedded space),
    // joined by single spaces, fitted to 75.
    this.stName =
        CobolStringUtils.fixedWidth(
            delimitedBySpace(customer.getCustFirstName())
                + " "
                + delimitedBySpace(customer.getCustMiddleName())
                + " "
                + delimitedBySpace(customer.getCustLastName())
                + " ",
            75);

    this.stAdd1 = CobolStringUtils.padRight(customer.getCustAddrLine1(), 50); // MOVE -> ST-ADD1
    this.stAdd2 = CobolStringUtils.padRight(customer.getCustAddrLine2(), 50); // MOVE -> ST-ADD2

    // ST-ADD3: line3 + state + country + zip, each DELIMITED BY ' ', joined by single spaces.
    this.stAdd3 =
        CobolStringUtils.fixedWidth(
            delimitedBySpace(customer.getCustAddrLine3())
                + " "
                + delimitedBySpace(customer.getCustAddrStateCd())
                + " "
                + delimitedBySpace(customer.getCustAddrCountryCd())
                + " "
                + delimitedBySpace(customer.getCustAddrZip())
                + " ",
            80);

    // ST-ACCT-ID: MOVE ACCT-ID (9(11)) to X(20) -> 11 zero-padded digits, left-justified.
    this.stAcctId =
        CobolStringUtils.padRight(CobolStringUtils.padLeftZeros(account.getAcctId(), 11), 20);
    // ST-CURR-BAL: MOVE ACCT-CURR-BAL (S9(10)V99) to PIC 9(9).99- (forced zero-pad, trailing sign).
    this.stCurrBal = formatCurrBalance(account.getAcctCurrBal());
    // ST-FICO-SCORE: MOVE CUST-FICO-CREDIT-SCORE (9(03)) to X(20).
    this.stFico =
        CobolStringUtils.padRight(
            CobolStringUtils.padLeftZeros(customer.getCustFicoCreditScore(), 3), 20);

    writeHtmlNmadbs(); // 5200-WRITE-HTML-NMADBS

    emitStmt(stName + CobolStringUtils.spaces(5)); // ST-LINE1
    emitStmt(stAdd1 + CobolStringUtils.spaces(30)); // ST-LINE2
    emitStmt(stAdd2 + CobolStringUtils.spaces(30)); // ST-LINE3
    emitStmt(stAdd3); // ST-LINE4
    emitStmt(DASHES_80); // ST-LINE5
    emitStmt(ST_LINE6); // ST-LINE6
    emitStmt(DASHES_80); // ST-LINE5 (again)
    emitStmt(LABEL_ACCT_ID + stAcctId + CobolStringUtils.spaces(40)); // ST-LINE7
    emitStmt(LABEL_CURR_BAL + stCurrBal + CobolStringUtils.spaces(47)); // ST-LINE8 (7 + 40 fillers)
    emitStmt(LABEL_FICO + stFico + CobolStringUtils.spaces(40)); // ST-LINE9
    emitStmt(DASHES_80); // ST-LINE10
    emitStmt(ST_LINE11); // ST-LINE11
    emitStmt(DASHES_80); // ST-LINE12
    emitStmt(ST_LINE13); // ST-LINE13
    emitStmt(DASHES_80); // ST-LINE12 (again)
  }

  // ---------------------------------------------------------------------------------------------
  // HTML header (5100-WRITE-HTML-HEADER, lines 506-555).
  // ---------------------------------------------------------------------------------------------

  /**
   * Emits the fixed HTML document header and bank-address block ({@code 5100-WRITE-HTML-HEADER},
   * CBSTM03A lines 506-555). The single dynamic line is {@code HTML-L11} ({@code MOVE ACCT-ID TO
   * L11-ACCT}); all other lines are the verbatim {@code HTML-FIXED-LN} 88-level values.
   */
  private void writeHtmlHeader() {
    emitHtml(HTML_L01);
    emitHtml(HTML_L02);
    emitHtml(HTML_L03);
    emitHtml(HTML_L04);
    emitHtml(HTML_L05);
    emitHtml(HTML_L06);
    emitHtml(HTML_L07);
    emitHtml(HTML_L08);
    emitHtml(HTML_LTRS);
    emitHtml(HTML_L10);
    // HTML-L11: '<h3>Statement for Account Number: ' + L11-ACCT (ACCT-ID in X(20)) + '</h3>'.
    String acctId20 =
        CobolStringUtils.padRight(CobolStringUtils.padLeftZeros(account.getAcctId(), 11), 20);
    emitHtml(HTML_L11_PREFIX + acctId20 + HTML_H3_CLOSE);
    emitHtml(HTML_LTDE);
    emitHtml(HTML_LTRE);
    emitHtml(HTML_LTRS);
    emitHtml(HTML_L15);
    emitHtml(HTML_L16);
    emitHtml(HTML_L17);
    emitHtml(HTML_L18);
    emitHtml(HTML_LTDE);
    emitHtml(HTML_LTRE);
    emitHtml(HTML_LTRS);
    emitHtml(HTML_L22_35);
  }

  // ---------------------------------------------------------------------------------------------
  // HTML name / address / basic-details block (5200-WRITE-HTML-NMADBS, lines 558-672).
  // ---------------------------------------------------------------------------------------------

  /**
   * Emits the HTML customer name, address paragraphs and basic-details rows ({@code
   * 5200-WRITE-HTML-NMADBS}, CBSTM03A lines 558-672). The name and address lines use {@code
   * DELIMITED BY ' '} (truncate at the first double space), then append a literal two spaces and
   * {@code </p>}; the basic-details lines use {@code DELIMITED BY '*'} (the whole field, trailing
   * spaces included).
   */
  private void writeHtmlNmadbs() {
    // Name: MOVE ST-NAME (75) TO L23-NAME (50) then STRING with the name-prefix.
    String l23Name = CobolStringUtils.truncate(stName, 50);
    emitHtml(HTML_NAME_PREFIX + delimitedBy(l23Name, DOUBLE_SPACE) + DOUBLE_SPACE + HTML_P_CLOSE);
    // Three address paragraphs.
    emitHtml(HTML_P_OPEN + delimitedBy(stAdd1, DOUBLE_SPACE) + DOUBLE_SPACE + HTML_P_CLOSE);
    emitHtml(HTML_P_OPEN + delimitedBy(stAdd2, DOUBLE_SPACE) + DOUBLE_SPACE + HTML_P_CLOSE);
    emitHtml(HTML_P_OPEN + delimitedBy(stAdd3, DOUBLE_SPACE) + DOUBLE_SPACE + HTML_P_CLOSE);

    emitHtml(HTML_LTDE);
    emitHtml(HTML_LTRE);
    emitHtml(HTML_LTRS);
    emitHtml(HTML_L30_42);
    emitHtml(HTML_L31);
    emitHtml(HTML_LTDE);
    emitHtml(HTML_LTRE);
    emitHtml(HTML_LTRS);
    emitHtml(HTML_L22_35);

    // Basic details (whole-field values).
    emitHtml(BASIC_ACCT_PREFIX + stAcctId + HTML_P_CLOSE);
    emitHtml(BASIC_BAL_PREFIX + stCurrBal + HTML_P_CLOSE);
    emitHtml(BASIC_FICO_PREFIX + stFico + HTML_P_CLOSE);

    emitHtml(HTML_LTDE);
    emitHtml(HTML_LTRE);
    emitHtml(HTML_LTRS);
    emitHtml(HTML_L30_42);
    emitHtml(HTML_L43);
    emitHtml(HTML_LTDE);
    emitHtml(HTML_LTRE);
    emitHtml(HTML_LTRS);
    emitHtml(HTML_L47);
    emitHtml(HTML_L48);
    emitHtml(HTML_LTDE);
    emitHtml(HTML_L50);
    emitHtml(HTML_L51);
    emitHtml(HTML_LTDE);
    emitHtml(HTML_L53);
    emitHtml(HTML_L54);
    emitHtml(HTML_LTDE);
    emitHtml(HTML_LTRE);
  }

  // ---------------------------------------------------------------------------------------------
  // Transaction detail + totals (4000-TRNXFILE-GET 416-456; 6000-WRITE-TRANS 675-723).
  // ---------------------------------------------------------------------------------------------

  /**
   * Writes the transaction detail lines for the current card, accumulates the expense total, then
   * writes the total/closing text lines and the statement-closing HTML ({@code 4000-TRNXFILE-GET},
   * CBSTM03A lines 416-456).
   *
   * <p>The legacy linear table scan with the early-exit {@code WS-CARD-NUM(CR-JMP) > XREF-CARD-NUM}
   * is simply "find this card"; a {@link Map#get(Object)} on the card-keyed table is the faithful
   * equivalent. A card with no transactions in the table produces no detail lines and a zero total.
   */
  private void trnxfileGet() {
    List<Transaction> trans = trnxTable.get(cardKey(cardXref.getXrefCardNum()));
    if (trans != null) {
      for (Transaction tran : trans) {
        writeTrans(tran); // 6000-WRITE-TRANS
        BigDecimal amt = (tran.getTranAmt() == null) ? BigDecimal.ZERO : tran.getTranAmt();
        wsTotalAmt = wsTotalAmt.add(amt); // ADD TRNX-AMT TO WS-TOTAL-AMT (line 429)
      }
    }

    String stTotalTramt = NumberFormatter.formatStatementAmount(wsTotalAmt); // ST-TOTAL-TRAMT
    emitStmt(DASHES_80); // ST-LINE12
    emitStmt(TEXT_TOTAL_EXP + CobolStringUtils.spaces(56) + DOLLAR + stTotalTramt); // ST-LINE14A
    emitStmt(ST_LINE15); // ST-LINE15

    // Statement-closing HTML (lines 439-454).
    emitHtml(HTML_LTRS);
    emitHtml(HTML_L10);
    emitHtml(HTML_L75);
    emitHtml(HTML_LTDE);
    emitHtml(HTML_LTRE);
    emitHtml(HTML_L78);
    emitHtml(HTML_L79);
    emitHtml(HTML_L80);
  }

  /**
   * Writes one transaction's text detail line and the three HTML cells ({@code 6000-WRITE-TRANS},
   * CBSTM03A lines 675-723).
   *
   * @param tran the transaction to render
   */
  private void writeTrans(Transaction tran) {
    String stTranId = CobolStringUtils.padRight(tran.getTranId(), 16); // MOVE TRNX-ID TO ST-TRANID
    String stTranDt =
        CobolStringUtils.padRight(tran.getTranDesc(), 49); // MOVE TRNX-DESC TO ST-TRANDT
    String stTranAmt =
        NumberFormatter.formatStatementAmount(tran.getTranAmt()); // ST-TRANAMT Z(9).99-

    // ST-LINE14: ST-TRANID(16) + ' '(1) + ST-TRANDT(49) + '$'(1) + ST-TRANAMT(13) = 80.
    emitStmt(stTranId + " " + stTranDt + DOLLAR + stTranAmt);

    emitHtml(HTML_LTRS);
    emitHtml(HTML_L58);
    emitHtml(HTML_P_OPEN + stTranId + HTML_P_CLOSE);
    emitHtml(HTML_LTDE);
    emitHtml(HTML_L61);
    emitHtml(HTML_P_OPEN + stTranDt + HTML_P_CLOSE);
    emitHtml(HTML_LTDE);
    emitHtml(HTML_L64);
    emitHtml(HTML_P_OPEN + stTranAmt + HTML_P_CLOSE);
    emitHtml(HTML_LTDE);
    emitHtml(HTML_LTRE);
  }

  // ---------------------------------------------------------------------------------------------
  // FileIoService open/close helpers and abend (8x00 opens, 9x00 closes, 9999-ABEND-PROGRAM).
  // ---------------------------------------------------------------------------------------------

  /**
   * Opens an input file through {@link FileIoService}, accepting status {@code '00'} or {@code
   * '04'} (CBSTM03A {@code 8100}-{@code 8400}); any other status abends.
   *
   * @param ddname the {@link WorkArea} ddname constant
   * @param label the human-readable file label used in diagnostics
   */
  private void openFile(String ddname, String label) {
    WorkArea wa = new WorkArea(ddname, WorkArea.Operation.OPEN);
    fileIoService.process(wa);
    if (!isOpenStatusAcceptable(wa.getReturnCode())) {
      throw abend(label, "OPEN", wa.getReturnCode());
    }
  }

  /**
   * Closes an input file through {@link FileIoService}, accepting status {@code '00'} or {@code
   * '04'} (CBSTM03A {@code 9100}-{@code 9400}); any other status abends.
   *
   * @param ddname the {@link WorkArea} ddname constant
   * @param label the human-readable file label used in diagnostics
   */
  private void closeFile(String ddname, String label) {
    WorkArea wa = new WorkArea(ddname, WorkArea.Operation.CLOSE);
    fileIoService.process(wa);
    if (!isOpenStatusAcceptable(wa.getReturnCode())) {
      throw abend(label, "CLOSE", wa.getReturnCode());
    }
  }

  /**
   * Returns whether {@code rc} is an acceptable open/close status, i.e. {@code '00'} or {@code
   * '04'} (the {@code IF WS-M03B-RC = '00' OR '04'} guard used by the CBSTM03A open/close
   * paragraphs).
   *
   * @param rc the 2-byte FILE STATUS returned by {@link FileIoService}
   * @return {@code true} for {@code '00'} or {@code '04'}
   */
  private static boolean isOpenStatusAcceptable(String rc) {
    return FileIoService.STATUS_OK.equals(rc) || STATUS_OPEN_WARNING.equals(rc);
  }

  /**
   * Builds the fatal {@link IoStatusException} for an unrecoverable I/O status, after logging the
   * diagnostic ({@code 9999-ABEND-PROGRAM}, CBSTM03A line 921, with the {@code DISPLAY 'ABENDING
   * PROGRAM'} and per-file {@code DISPLAY 'ERROR ...'}/{@code 'RETURN CODE: '} messages). Returning
   * the exception lets callers write {@code throw abend(...)} so the compiler sees the control-flow
   * termination.
   *
   * @param fileName the file label (e.g. {@code TRNXFILE})
   * @param operation the operation ({@code OPEN}/{@code READ}/{@code CLOSE})
   * @param fileStatus the offending 2-byte FILE STATUS
   * @return the exception to throw
   */
  private IoStatusException abend(String fileName, String operation, String fileStatus) {
    LOG.error(
        "ABENDING PROGRAM: error during {} on {} (return code '{}')",
        operation,
        fileName,
        fileStatus);
    return new IoStatusException(fileName, operation, fileStatus);
  }

  // ---------------------------------------------------------------------------------------------
  // Output sinks and formatting helpers.
  // ---------------------------------------------------------------------------------------------

  /**
   * Emits one plain-text statement line, fixed to 80 characters ({@code WRITE FD-STMTFILE-REC},
   * {@code FD-STMTFILE-REC PIC X(80)}, {@code LRECL=80}).
   *
   * @param line the formatted line (padded/truncated to 80)
   */
  private void emitStmt(String line) {
    stmtSink.accept(CobolStringUtils.fixedWidth(line, 80));
  }

  /**
   * Emits one HTML statement line, fixed to 100 characters ({@code WRITE FD-HTMLFILE-REC}, {@code
   * FD-HTMLFILE-REC PIC X(100)}, {@code LRECL=100}).
   *
   * @param line the formatted line (padded/truncated to 100)
   */
  private void emitHtml(String line) {
    htmlSink.accept(CobolStringUtils.fixedWidth(line, 100));
  }

  /**
   * Renders {@code ST-CURR-BAL} as COBOL {@code PIC 9(9).99-}: a 9-digit zero-filled (not
   * zero-suppressed) integer part, a literal {@code '.'}, a 2-digit fraction, and a trailing sign
   * ({@code '-'} when negative, a space otherwise) — total width 13 (AAP §0.6.1). The value is
   * truncated to scale 2 with {@link RoundingMode#DOWN} (COBOL has no {@code ROUNDED} here), and an
   * over-long integer part is high-order truncated to 9 digits, matching the COBOL {@code MOVE} of
   * {@code S9(10)V99} into the 9-integer-digit field.
   *
   * @param amount the balance, possibly {@code null} (treated as zero)
   * @return the 13-character edited balance
   */
  private static String formatCurrBalance(BigDecimal amount) {
    BigDecimal value = (amount == null) ? BigDecimal.ZERO : amount;
    BigDecimal scaled = value.setScale(2, RoundingMode.DOWN);
    boolean negative = scaled.signum() < 0;
    String plain = scaled.abs().toPlainString();
    int dot = plain.indexOf('.');
    String intPart = plain.substring(0, dot);
    String fracPart = plain.substring(dot + 1);
    return CobolStringUtils.padLeftZeros(intPart, 9) + "." + fracPart + (negative ? "-" : " ");
  }

  /**
   * Reproduces a COBOL {@code STRING ... DELIMITED BY ' '} term: returns the characters of {@code
   * value} up to (but not including) the first single space, or the whole value when it contains no
   * space.
   *
   * @param value the source text
   * @return the text up to the first space
   */
  private static String delimitedBySpace(String value) {
    return delimitedBy(value, " ");
  }

  /**
   * Reproduces a COBOL {@code STRING ... DELIMITED BY <delimiter>} term: returns the characters of
   * {@code value} up to (but not including) the first occurrence of {@code delimiter}, or the whole
   * value when the delimiter is absent. A {@code null} value yields an empty string.
   *
   * @param value the source text
   * @param delimiter the delimiter literal
   * @return the text preceding the first delimiter occurrence
   */
  private static String delimitedBy(String value, String delimiter) {
    if (value == null) {
      return "";
    }
    int idx = value.indexOf(delimiter);
    return (idx < 0) ? value : value.substring(0, idx);
  }

  /**
   * Normalizes a fixed-width {@code CHAR(16)} card number to a stable map key by trimming trailing
   * spaces, so that the grouping key (from {@code TRNX-CARD-NUM}) and the lookup key (from {@code
   * XREF-CARD-NUM}) compare equal regardless of padding.
   *
   * @param cardNum the raw card number
   * @return the trimmed card-number key
   */
  private static String cardKey(String cardNum) {
    return CobolStringUtils.rtrim(cardNum);
  }
}
