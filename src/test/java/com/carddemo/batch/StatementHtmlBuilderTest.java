package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.entity.Account;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StatementHtmlBuilder}, focused on the MAJOR-9 stored-XSS
 * remediation.
 *
 * <p>The remediation requires that every <em>dynamic free-text</em> field rendered into a
 * customer statement &mdash; the customer name, the three address lines, the transaction id,
 * and the transaction description &mdash; is HTML-escaped before being appended, so that a
 * persisted value containing markup (for example {@code <script>...}) cannot become executable
 * HTML/JavaScript when the generated statement is opened in a browser. At the same time, the
 * legacy COBOL byte-for-byte parity (PR-09) must be preserved for the safe CardDemo fixture data:
 * because {@code StatementHtmlBuilder.htmlEscape} takes an identity fast-path when no
 * metacharacter is present, safe values must pass through completely unchanged.</p>
 *
 * <p>This is a pure JUnit&nbsp;5 unit test &mdash; no Spring context is started.
 * {@link StatementHtmlBuilder} is a stateless component with no required collaborators, so it is
 * instantiated directly. Entities are assembled with their Lombok {@code @Builder}s; only the
 * fields actually consumed by the rendered fragments are populated (all reader helpers are
 * null-safe).</p>
 *
 * <p>Test payloads are deliberately kept shorter than each field's fixed width
 * ({@code L23-NAME PIC X(50)}, {@code ST-ADD1 PIC X(50)}, {@code ST-TRANID PIC X(16)},
 * {@code ST-TRANDT PIC X(49)}) and contain no embedded double-space, so they survive the
 * COBOL fixed-width fit / {@code DELIMITED BY '  '} truncation intact and reach the escaper
 * unmodified.</p>
 *
 * @see StatementHtmlBuilder#renderHtmlCustomerAndBasic(Customer, Account)
 * @see StatementHtmlBuilder#renderTransactionRow(Transaction)
 * @see StatementHtmlBuilder#renderHtmlHeader(Account)
 * @see StatementHtmlBuilder#renderFullStatement(Customer, Account, List)
 */
class StatementHtmlBuilderTest {

    /**
     * The component under test. {@code @RequiredArgsConstructor} over zero {@code final}
     * collaborators yields a no-arg constructor, so direct instantiation is valid.
     */
    private final StatementHtmlBuilder builder = new StatementHtmlBuilder();

    // =============================================================================================
    // Fixture factories — safe (parity) data
    // =============================================================================================

    /** A customer whose every dynamic field is free of HTML metacharacters. */
    private static Customer safeCustomer() {
        return Customer.builder()
                .firstName("JOHN")
                .middleName("Q")
                .lastName("PUBLIC")
                .addrLine1("123 MAIN ST")
                .addrLine2("APT 4")
                .addrLine3("SPRINGFIELD")
                .stateCd("IL")
                .countryCd("USA")
                .zipCd("62704")
                .ficoScore(750)
                .build();
    }

    /** An account with a small, fixed id and balance for deterministic rendering. */
    private static Account safeAccount() {
        return Account.builder()
                .acctId(1L)
                .currBal(new BigDecimal("100.00"))
                .build();
    }

    /** A transaction whose id and description are free of HTML metacharacters. */
    private static Transaction safeTransaction() {
        return Transaction.builder()
                .tranId("0000000000000001")
                .description("PURCHASE AT STORE")
                .amount(new BigDecimal("25.00"))
                .build();
    }

    // =============================================================================================
    // Stored-XSS: dynamic free-text MUST be escaped
    // =============================================================================================

    @Nested
    @DisplayName("Stored-XSS defense — dynamic free-text is HTML-escaped")
    class Escaping {

        @Test
        @DisplayName("customer name carrying <script> markup is neutralized")
        void customerNameScriptIsEscaped() {
            // Payload lives entirely in the first name (no spaces) so it is not split by the
            // COBOL DELIMITED BY ' ' tokenization in 5000-CREATE-STATEMENT.
            Customer c = Customer.builder()
                    .firstName("<script>alert(1)</script>")
                    .middleName("")
                    .lastName("")
                    .addrLine1("SAFE ADDR")
                    .addrLine2("")
                    .addrLine3("")
                    .stateCd("IL")
                    .countryCd("USA")
                    .zipCd("62704")
                    .ficoScore(700)
                    .build();

            String html = builder.renderHtmlCustomerAndBasic(c, safeAccount());

            assertThat(html)
                    .as("raw <script> markup must not survive into the statement")
                    .doesNotContain("<script>alert(1)</script>");
            assertThat(html)
                    .as("the script payload must be entity-escaped")
                    .contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        }

        @Test
        @DisplayName("address line with all five metacharacters is fully escaped")
        void addressWithAllMetacharactersIsEscaped() {
            // Address line 1 is a direct MOVE in COBOL; contains <, ", &, ', > and no double-space.
            String unsafe = "<a\"&'>";
            Customer c = Customer.builder()
                    .firstName("JANE")
                    .middleName("")
                    .lastName("DOE")
                    .addrLine1(unsafe)
                    .addrLine2("")
                    .addrLine3("")
                    .stateCd("IL")
                    .countryCd("USA")
                    .zipCd("62704")
                    .ficoScore(700)
                    .build();

            String html = builder.renderHtmlCustomerAndBasic(c, safeAccount());

            assertThat(html)
                    .as("raw unsafe address must not survive into the statement")
                    .doesNotContain(unsafe);
            assertThat(html)
                    .as("every HTML metacharacter must be escaped to its entity")
                    .contains("&lt;")
                    .contains("&gt;")
                    .contains("&quot;")
                    .contains("&#39;")
                    .contains("&amp;");
        }

        @Test
        @DisplayName("transaction id and description are escaped; numeric amount is untouched")
        void transactionIdAndDescriptionAreEscaped() {
            Transaction tx = Transaction.builder()
                    .tranId("<b>x</b>")
                    .description("<script>alert(1)</script>")
                    .amount(new BigDecimal("12.34"))
                    .build();

            String html = builder.renderTransactionRow(tx);

            assertThat(html)
                    .as("raw transaction id markup must not survive")
                    .doesNotContain("<b>x</b>");
            assertThat(html)
                    .as("transaction id markup must be entity-escaped")
                    .contains("&lt;b&gt;x&lt;/b&gt;");

            assertThat(html)
                    .as("raw transaction description markup must not survive")
                    .doesNotContain("<script>alert(1)</script>");
            assertThat(html)
                    .as("transaction description markup must be entity-escaped")
                    .contains("&lt;script&gt;alert(1)&lt;/script&gt;");

            assertThat(html)
                    .as("the numeric amount is not free-text and renders verbatim")
                    .contains("12.34");
        }
    }

    // =============================================================================================
    // PR-09 parity: safe legacy data is rendered unchanged (escaping is a no-op)
    // =============================================================================================

    @Nested
    @DisplayName("PR-09 parity — safe fixture data is rendered byte-for-byte unchanged")
    class Parity {

        @Test
        @DisplayName("customer/basic fragment: safe values verbatim, no entities introduced")
        void safeCustomerFragmentIsNotEscaped() {
            String html = builder.renderHtmlCustomerAndBasic(safeCustomer(), safeAccount());

            // Safe dynamic values appear verbatim — the escaper did not transform them.
            assertThat(html).contains("JOHN Q PUBLIC");
            assertThat(html).contains("123 MAIN ST");

            // No HTML entity is introduced for safe data (no literal constant contains '&'),
            // proving the identity fast-path preserved byte-for-byte parity.
            assertNoHtmlEntities(html);
        }

        @Test
        @DisplayName("header fragment is deterministic, unescaped, and preserves the preamble")
        void headerIsDeterministicAndUnescaped() {
            Account acct = safeAccount();

            String first = builder.renderHtmlHeader(acct);
            String second = builder.renderHtmlHeader(acct);

            assertThat(first)
                    .as("header rendering is deterministic / byte-identical")
                    .isEqualTo(second);
            assertThat(first)
                    .as("DOCTYPE preamble preserved exactly")
                    .startsWith("<!DOCTYPE html>\n");
            assertThat(first).contains("Statement for Account Number: ");
            assertThat(first)
                    .as("PIC 9(11) account id, leading zeros, moved into PIC X(20)")
                    .contains("00000000001");
            assertNoHtmlEntities(first);
        }

        @Test
        @DisplayName("transaction row: safe values verbatim, no entities introduced")
        void safeTransactionRowIsNotEscaped() {
            String html = builder.renderTransactionRow(safeTransaction());

            assertThat(html).contains("0000000000000001");
            assertThat(html).contains("PURCHASE AT STORE");
            assertNoHtmlEntities(html);
        }

        @Test
        @DisplayName("full statement with safe data introduces no entities and is deterministic")
        void fullStatementWithSafeDataIsNotEscaped() {
            Customer c = safeCustomer();
            Account a = safeAccount();
            List<Transaction> txns = List.of(safeTransaction());

            String first = builder.renderFullStatement(c, a, txns);
            String second = builder.renderFullStatement(c, a, txns);

            assertThat(first)
                    .as("full statement rendering is deterministic / byte-identical")
                    .isEqualTo(second);
            assertThat(first).contains("JOHN Q PUBLIC");
            assertThat(first).contains("PURCHASE AT STORE");
            assertNoHtmlEntities(first);
        }
    }

    /**
     * Asserts that {@code html} contains none of the five HTML entity sequences produced by the
     * escaper. Because no fixed HTML literal constant in {@link StatementHtmlBuilder} contains an
     * ampersand, the presence of any of these sequences would necessarily come from escaping a
     * dynamic value &mdash; so their absence proves the escaper was a no-op for safe data,
     * preserving PR-09 byte-for-byte parity.
     */
    private static void assertNoHtmlEntities(String html) {
        assertThat(html)
                .as("safe data must not be escaped (PR-09 parity)")
                .doesNotContain("&lt;")
                .doesNotContain("&gt;")
                .doesNotContain("&quot;")
                .doesNotContain("&#39;")
                .doesNotContain("&amp;");
    }
}
