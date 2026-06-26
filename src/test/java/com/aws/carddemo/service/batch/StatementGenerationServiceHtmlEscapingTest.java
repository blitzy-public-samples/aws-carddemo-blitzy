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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.service.batch.FileIoService.WorkArea;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Security and parity tests for {@link StatementGenerationService} focused on the statement HTML
 * output boundary (the {@code CBSTM03A} HTML statement, {@code legacy/app/cbl/CBSTM03A.CBL} lines
 * 506-721).
 *
 * <p><strong>What this guards (CWE-79).</strong> The service renders two statements per card: a
 * fixed-width plain-text statement and an HTML statement. Customer name/address and per-transaction
 * fields are dynamic, data-derived values; the transaction <em>description</em> in particular is
 * operator-controlled (it can be entered through the online transaction-add screen, persisted, and
 * later surfaced on a statement). These tests assert that every dynamic value is HTML-escaped
 * before it is concatenated into HTML markup, so stored markup cannot break out of the surrounding
 * {@code <p>}/{@code <h3>} element and execute in a browser.
 *
 * <p><strong>What this preserves (parity).</strong> The same dynamic values flow <em>unescaped</em>
 * into the plain-text statement, which is a fixed-width {@code LRECL=80} file (not browser
 * rendered) and must stay byte-faithful to the COBOL output (AAP §0.6.7). The tests therefore also
 * assert that the text statement still contains the raw characters, and that for clean
 * (metacharacter-free) data the HTML escaping is a no-op so existing golden-file HTML parity is
 * unaffected.
 *
 * <p><strong>Test harness.</strong> {@link FileIoService} (the {@code CBSTM03B} translation) is a
 * Mockito mock driven by a small stateful {@code Answer} that reproduces the exact {@code
 * process(WorkArea)} call sequence of one statement run: open/seq-read the transaction file (one
 * record then EOF), open the three lookup files, then for the single card cross-reference return
 * the keyed customer/account and the matching transaction, then EOF on the next cross-reference
 * read, then close. No Spring context, JPA, or database is required.
 */
class StatementGenerationServiceHtmlEscapingTest {

  /** Card number shared by the cross-reference and the transaction so the table lookup matches. */
  private static final String CARD_NUM = "0010203040506070";

  /**
   * Drives one full statement run through {@link StatementGenerationService#run} against a mocked
   * {@link FileIoService}, returning the emitted plain-text and HTML lines.
   *
   * @param customer the customer record returned by the keyed {@code CUSTFILE} read
   * @param account the account record returned by the keyed {@code ACCTFILE} read
   * @param xref the card cross-reference returned by the sequential {@code XREFFILE} read
   * @param transaction the single transaction returned by the sequential {@code TRNXFILE} read
   * @return a two-element holder: {@code [0]} = plain-text lines joined, {@code [1]} = HTML lines
   *     joined
   */
  private static String[] runOnce(
      Customer customer, Account account, CardXref xref, Transaction transaction) {
    FileIoService fileIoService = mock(FileIoService.class);

    // Stateful sequencing: TRNXFILE and XREFFILE each yield one record then end-of-file ("10").
    int[] trnxReads = {0};
    int[] xrefReads = {0};

    doAnswer(
            invocation -> {
              WorkArea wa = invocation.getArgument(0);
              String dd = wa.getDdname();
              WorkArea.Operation op = wa.getOperation();
              if (WorkArea.TRNXFILE.equals(dd)) {
                if (op == WorkArea.Operation.READ) {
                  if (trnxReads[0]++ == 0) {
                    wa.setRecord(transaction);
                    wa.setReturnCode(FileIoService.STATUS_OK);
                  } else {
                    wa.setRecord(null);
                    wa.setReturnCode(FileIoService.STATUS_EOF);
                  }
                } else {
                  wa.setReturnCode(FileIoService.STATUS_OK); // OPEN / CLOSE
                }
              } else if (WorkArea.XREFFILE.equals(dd)) {
                if (op == WorkArea.Operation.READ) {
                  if (xrefReads[0]++ == 0) {
                    wa.setRecord(xref);
                    wa.setReturnCode(FileIoService.STATUS_OK);
                  } else {
                    wa.setRecord(null);
                    wa.setReturnCode(FileIoService.STATUS_EOF);
                  }
                } else {
                  wa.setReturnCode(FileIoService.STATUS_OK); // OPEN / CLOSE
                }
              } else if (WorkArea.CUSTFILE.equals(dd)) {
                if (op == WorkArea.Operation.READ_KEY) {
                  wa.setRecord(customer);
                }
                wa.setReturnCode(FileIoService.STATUS_OK);
              } else if (WorkArea.ACCTFILE.equals(dd)) {
                if (op == WorkArea.Operation.READ_KEY) {
                  wa.setRecord(account);
                }
                wa.setReturnCode(FileIoService.STATUS_OK);
              } else {
                wa.setReturnCode(FileIoService.STATUS_OK);
              }
              return null;
            })
        .when(fileIoService)
        .process(any(WorkArea.class));

    StatementGenerationService service = new StatementGenerationService(fileIoService);
    List<String> stmtLines = new ArrayList<>();
    List<String> htmlLines = new ArrayList<>();
    service.run(stmtLines::add, htmlLines::add);

    return new String[] {String.join("\n", stmtLines), String.join("\n", htmlLines)};
  }

  private static CardXref xref() {
    CardXref xref = new CardXref();
    xref.setXrefCardNum(CARD_NUM);
    xref.setXrefCustId(50L);
    xref.setXrefAcctId(50L);
    return xref;
  }

  private static Account account() {
    Account account = new Account();
    account.setAcctId(50L);
    account.setAcctCurrBal(new BigDecimal("492.00"));
    return account;
  }

  private static Transaction transaction(String description) {
    Transaction tran = new Transaction();
    tran.setTranId(CARD_NUM);
    tran.setTranCardNum(CARD_NUM);
    tran.setTranDesc(description);
    tran.setTranAmt(new BigDecimal("123.45"));
    return tran;
  }

  private static Customer customer(
      String first, String middle, String last, String addr1, String addr2) {
    Customer customer = new Customer();
    customer.setCustId(50L);
    customer.setCustFirstName(first);
    customer.setCustMiddleName(middle);
    customer.setCustLastName(last);
    customer.setCustAddrLine1(addr1);
    customer.setCustAddrLine2(addr2);
    customer.setCustAddrLine3("City");
    customer.setCustAddrStateCd("OR");
    customer.setCustAddrCountryCd("USA");
    customer.setCustAddrZip("04257");
    customer.setCustFicoCreditScore(623L);
    return customer;
  }

  /**
   * Malicious markup in the customer name, an address line, and the (operator-controlled)
   * transaction description must be HTML-escaped in the HTML statement so it cannot execute in a
   * browser, while the plain-text statement keeps the raw characters for fixed-width parity.
   */
  @Test
  void htmlOutputEscapesMaliciousMarkup_textStatementStaysRaw() {
    String evilName = "<script>x</script>";
    String evilAddr1 = "<img src=x onerror=alert(1)>";
    String evilAddr2 = "Apt<b>1</b>";
    String evilDesc = "<script>alert(1)</script>";

    String[] out =
        runOnce(
            customer(evilName, "", "Doe", evilAddr1, evilAddr2),
            account(),
            xref(),
            transaction(evilDesc));
    String text = out[0];
    String html = out[1];

    // HTML statement: no raw injectable markup survives; only escaped entity forms appear.
    assertThat(html).doesNotContain("<script>alert(1)</script>");
    assertThat(html).doesNotContain("<script>x</script>");
    assertThat(html).doesNotContain("<img src=x onerror=alert(1)>");
    assertThat(html).doesNotContain("<b>1</b>");
    assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    assertThat(html).contains("&lt;script&gt;x&lt;/script&gt;");
    assertThat(html).contains("&lt;img src=x onerror=alert(1)&gt;");
    assertThat(html).contains("&lt;b&gt;1&lt;/b&gt;");

    // Plain-text statement (LRECL=80, not browser rendered): raw characters preserved for parity.
    assertThat(text).contains("<script>alert(1)</script>");
    assertThat(text).contains("<script>x</script>");

    // Sanity: a full statement was rendered (HTML closing structure + text banners present).
    assertThat(html).contains("</table>");
    assertThat(text).contains("START OF STATEMENT");
    assertThat(text).contains("END OF STATEMENT");
  }

  /**
   * For clean (metacharacter-free) data the HTML escaping is a no-op: the dynamic values appear
   * verbatim in both the HTML and the text statement, so existing golden-file HTML parity is
   * unaffected by the escaping change.
   */
  @Test
  void cleanData_htmlContainsVerbatimValues_escapeIsNoOp() {
    String desc = "POS PURCHASE - GROCERY MART";

    String[] out =
        runOnce(
            customer("Aniya", "Alba", "Von", "1588 Nienow Cape", "Suite 187"),
            account(),
            xref(),
            transaction(desc));
    String text = out[0];
    String html = out[1];

    // Clean values are not altered by escaping (no entity references introduced).
    assertThat(html).contains("Aniya");
    assertThat(html).contains("1588 Nienow Cape");
    assertThat(html).contains(desc);
    assertThat(html).doesNotContain("&lt;");
    assertThat(html).doesNotContain("&amp;");

    // The same clean values appear in the plain-text statement.
    assertThat(text).contains("Aniya");
    assertThat(text).contains(desc);
  }
}
