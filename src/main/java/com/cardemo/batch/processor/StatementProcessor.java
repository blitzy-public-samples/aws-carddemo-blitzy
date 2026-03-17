package com.cardemo.batch.processor;

import com.cardemo.entity.Account;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.Customer;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring Batch {@link ItemProcessor} that transforms a {@link CardXref} record
 * into a complete {@link StatementData} containing both plain-text and HTML
 * statement output.
 *
 * <p>This processor faithfully translates the statement generation logic from
 * COBOL program CBSTM03A.CBL. For each card cross-reference item, it:</p>
 * <ol>
 *   <li>Retrieves customer data (paragraph 2000-CUSTFILE-GET)</li>
 *   <li>Retrieves account data (paragraph 3000-ACCTFILE-GET)</li>
 *   <li>Retrieves all transactions for the card (paragraph 4000-TRNXFILE-GET)</li>
 *   <li>Builds statement header with customer/account info (paragraph 5000-CREATE-STATEMENT)</li>
 *   <li>Builds transaction detail lines (paragraph 6000-WRITE-TRANS)</li>
 *   <li>Accumulates total amount (WS-TOTAL-AMT PIC S9(9)V99 COMP-3)</li>
 *   <li>Generates plain-text output (STMT-FILE writes)</li>
 *   <li>Generates HTML output (HTML-FILE writes)</li>
 * </ol>
 *
 * <p><strong>Thread Safety:</strong> This processor is stateless. Each
 * {@link #process(CardXref)} call is self-contained with no shared mutable
 * state between invocations. It can safely be used in multi-threaded Spring
 * Batch steps.</p>
 *
 * <p><strong>Monetary Arithmetic:</strong> All monetary computations use
 * {@link BigDecimal} with {@link RoundingMode#HALF_UP} to match COBOL
 * COMP-3 rounding semantics. No floating-point types ({@code float} or
 * {@code double}) are used anywhere in this class.</p>
 *
 * @see CardXref the input item from the batch reader (XREF records)
 * @see StatementData the output item passed to the batch writer
 */
@Component
public class StatementProcessor implements ItemProcessor<CardXref, StatementProcessor.StatementData> {

    private static final Logger log = LoggerFactory.getLogger(StatementProcessor.class);

    /** COBOL statement line width: PIC X(80) per STMT-RECORD. */
    private static final int LINE_WIDTH = 80;

    /** 31 asterisks for ST-LINE0A / ST-LINE0C / ST-LINE15C. */
    private static final String STARS_31 = "*".repeat(31);

    /** 33 asterisks for ST-LINE15A. */
    private static final String STARS_33 = "*".repeat(33);

    /** 80-dash separator for ST-LINE5 / ST-LINE10 / ST-LINE12. */
    private static final String DASHES_80 = "-".repeat(LINE_WIDTH);

    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    /**
     * Constructs the processor with required repository dependencies.
     *
     * @param customerRepository    repository for CUSTDATA VSAM access
     * @param accountRepository     repository for ACCTDATA VSAM access
     * @param transactionRepository repository for TRANSACT VSAM access
     */
    public StatementProcessor(CustomerRepository customerRepository,
                              AccountRepository accountRepository,
                              TransactionRepository transactionRepository) {
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Processes a single {@link CardXref} record into a complete statement.
     * Returns {@code null} when customer or account data cannot be found,
     * which causes Spring Batch to filter out the item (matching COBOL
     * skip semantics for missing data).
     *
     * @param item the card cross-reference record from the batch reader
     * @return fully populated {@link StatementData}, or {@code null} if
     *         customer/account lookup fails
     * @throws Exception if an unrecoverable processing error occurs
     */
    @Override
    public StatementData process(CardXref item) throws Exception {
        String cardNumber = item.getXrefCardNum();
        String custId = item.getCustId();
        String accountId = item.getAccountId();
        String maskedCard = maskCardNumber(cardNumber);

        log.debug("Processing statement for card {}, account {}", maskedCard, accountId);

        // Validate extracted keys — prevent IllegalArgumentException from findById(null)
        if (custId == null || custId.isBlank()) {
            log.warn("CardXref has null/blank customer ID for card {}, skipping", maskedCard);
            return null;
        }
        if (accountId == null || accountId.isBlank()) {
            log.warn("CardXref has null/blank account ID for card {}, skipping", maskedCard);
            return null;
        }

        // Step 1: Get Customer data (← paragraph 2000-CUSTFILE-GET)
        Customer customer = customerRepository.findById(custId).orElse(null);
        if (customer == null) {
            log.warn("Customer not found for ID {}, skipping statement generation", custId);
            return null;
        }

        // Step 2: Get Account data (← paragraph 3000-ACCTFILE-GET)
        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null) {
            log.warn("Account not found for ID {}, skipping statement generation", accountId);
            return null;
        }

        // Step 3: Get transactions for card (← paragraph 4000-TRNXFILE-GET)
        // Replaces COBOL 2D array WS-TRNX-TABLE (OCCURS 51 x 10) with JPA query
        List<Transaction> transactions = transactionRepository
                .findByCardNum(cardNumber, Pageable.unpaged())
                .getContent();

        if (transactions.isEmpty()) {
            log.info("No transactions found for card {}", maskedCard);
        }

        // Step 4: Build StatementData (← paragraph 5000-CREATE-STATEMENT)
        StatementData data = new StatementData();
        data.setCustomerName(buildCustomerName(customer));
        data.setCustomerAddress(buildCustomerAddress(customer));
        data.setAccountId(safeStr(account.getAcctId()));
        data.setCardNumber(safeStr(cardNumber));

        BigDecimal currBal = account.getCurrBal() != null
                ? account.getCurrBal() : BigDecimal.ZERO;
        data.setCurrentBalance(currBal);

        Integer ficoScore = customer.getFicoCreditScore();
        data.setFicoScore(ficoScore != null ? ficoScore : 0);

        // Step 5: Build transaction detail lines and accumulate total
        // WS-TOTAL-AMT PIC S9(9)V99 COMP-3 → BigDecimal scale 2
        List<TransactionLine> lines = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (Transaction txn : transactions) {
            // Defensive card-number match check (mirrors COBOL 2D array filtering)
            if (cardNumber != null && !cardNumber.equals(txn.getCardNum())) {
                log.warn("Transaction {} has unexpected card number, expected {}",
                        txn.getTranId(), maskedCard);
            }

            TransactionLine line = new TransactionLine();
            line.setTransactionId(safeStr(txn.getTranId()));
            line.setDescription(safeStr(txn.getDescription()));

            BigDecimal txnAmount = txn.getAmount() != null
                    ? txn.getAmount() : BigDecimal.ZERO;
            line.setAmount(txnAmount);
            lines.add(line);

            totalAmount = totalAmount.add(txnAmount);
            log.debug("Transaction {}: amount {}", txn.getTranId(), txnAmount);
        }

        data.setTransactionLines(lines);
        data.setTotalAmount(totalAmount.setScale(2, RoundingMode.HALF_UP));

        // Step 6: Generate formatted output (STMT-FILE and HTML-FILE)
        data.setPlainTextStatement(generatePlainTextStatement(data));
        data.setHtmlStatement(generateHtmlStatement(data));

        log.info("Statement generated: account={}, card={}, txns={}, total={}",
                accountId, maskedCard, lines.size(),
                totalAmount.setScale(2, RoundingMode.HALF_UP));

        return data;
    }

    // ---------------------------------------------------------------
    // Helper methods — data extraction
    // ---------------------------------------------------------------

    /**
     * Masks card number for secure logging — shows only last 4 digits.
     *
     * @param cardNumber full card number (up to 16 characters)
     * @return masked card number (e.g., "****1234")
     */
    private String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 4) {
            return "****";
        }
        return "****" + cardNumber.substring(cardNumber.length() - 4);
    }

    /**
     * Builds customer full name matching COBOL
     * {@code STRING ... DELIMITED BY '  '} semantics from paragraph
     * 5000-CREATE-STATEMENT. Concatenates first, middle, and last names
     * with single-space separators after trimming each at the first
     * occurrence of two consecutive spaces.
     *
     * @param customer the customer entity
     * @return formatted customer name string
     */
    private String buildCustomerName(Customer customer) {
        String first = trimAtDoubleSpace(safeStr(customer.getFirstName()));
        String middle = trimAtDoubleSpace(safeStr(customer.getMiddleName()));
        String last = trimAtDoubleSpace(safeStr(customer.getLastName()));
        // Matches COBOL STRING with ' ' DELIMITED BY SIZE separators
        return first + " " + middle + " " + last;
    }

    /**
     * Builds multi-line customer address matching COBOL paragraph
     * 5000-CREATE-STATEMENT layout.
     * <ul>
     *   <li>Line 1: CUST-ADDR-LINE-1 (ST-LINE2)</li>
     *   <li>Line 2: CUST-ADDR-LINE-2 (ST-LINE3)</li>
     *   <li>Line 3: CUST-ADDR-LINE-3 + STATE + COUNTRY + ZIP (ST-LINE4)</li>
     * </ul>
     * Lines are separated by newline characters.
     *
     * @param customer the customer entity
     * @return multi-line address string
     */
    private String buildCustomerAddress(Customer customer) {
        String line1 = safeStr(customer.getAddrLine1());
        String line2 = safeStr(customer.getAddrLine2());
        // ST-LINE4-ADDR3: STRING of remaining address components
        String line3 = trimAtDoubleSpace(safeStr(customer.getAddrLine3()))
                + " " + trimAtDoubleSpace(safeStr(customer.getAddrStateCode()))
                + " " + trimAtDoubleSpace(safeStr(customer.getAddrCountryCode()))
                + " " + trimAtDoubleSpace(safeStr(customer.getAddrZip()));
        return line1 + "\n" + line2 + "\n" + line3;
    }

    // ---------------------------------------------------------------
    // Helper methods — string utilities
    // ---------------------------------------------------------------

    /**
     * Trims a string at the first occurrence of two consecutive spaces,
     * matching COBOL {@code STRING ... DELIMITED BY '  '} semantics.
     * For a PIC X(25) field like "John" followed by 21 spaces, the result
     * is "John" because it stops at the first double-space.
     *
     * @param value the input string
     * @return trimmed string, or empty string if input is null/empty
     */
    private String trimAtDoubleSpace(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int idx = value.indexOf("  ");
        return idx >= 0 ? value.substring(0, idx) : value;
    }

    /**
     * Returns the input string, or empty string if null.
     *
     * @param value nullable input
     * @return non-null string
     */
    private String safeStr(String value) {
        return value != null ? value : "";
    }

    /**
     * Pads or truncates a string to the exact specified length with
     * trailing spaces, matching COBOL MOVE to PIC X(n) semantics.
     *
     * @param text   the input string
     * @param length the desired output length
     * @return fixed-length string
     */
    private String padRight(String text, int length) {
        if (text == null) {
            return " ".repeat(length);
        }
        if (text.length() >= length) {
            return text.substring(0, length);
        }
        return text + " ".repeat(length - text.length());
    }

    // ---------------------------------------------------------------
    // Helper methods — numeric formatting
    // ---------------------------------------------------------------

    /**
     * Formats a {@link BigDecimal} value in COBOL PIC display format.
     * <ul>
     *   <li>{@code zeroSuppress=false}: PIC 9(9).99- (leading zeros preserved)</li>
     *   <li>{@code zeroSuppress=true}: PIC Z(9).99- (leading zeros replaced with spaces)</li>
     * </ul>
     * Output is exactly 13 characters: 9 integer positions + dot + 2 decimal
     * digits + trailing sign (space if positive, minus if negative).
     *
     * @param value        the monetary amount
     * @param zeroSuppress true for Z(9) suppression, false for 9(9) display
     * @return 13-character formatted string
     */
    private String formatCobolPic(BigDecimal value, boolean zeroSuppress) {
        BigDecimal scaled = value.setScale(2, RoundingMode.HALF_UP);
        boolean negative = scaled.compareTo(BigDecimal.ZERO) < 0;
        String plain = scaled.abs().toPlainString();

        int dot = plain.indexOf('.');
        String intPart = dot >= 0 ? plain.substring(0, dot) : plain;
        String decPart = dot >= 0 ? plain.substring(dot + 1) : "00";

        // Ensure exactly 2 decimal digits
        if (decPart.length() < 2) {
            decPart = decPart + "0";
        }
        if (decPart.length() > 2) {
            decPart = decPart.substring(0, 2);
        }

        // Truncate integer part if longer than 9 digits (COBOL overflow behavior)
        if (intPart.length() > 9) {
            intPart = intPart.substring(intPart.length() - 9);
        }

        // Pad integer part: spaces for Z(9), zeros for 9(9)
        if (zeroSuppress) {
            intPart = String.format("%9s", intPart);
        } else {
            while (intPart.length() < 9) {
                intPart = "0" + intPart;
            }
        }

        return intPart + "." + decPart + (negative ? "-" : " ");
    }

    // ---------------------------------------------------------------
    // Plain-text statement generation (← STMT-FILE output)
    // ---------------------------------------------------------------

    /**
     * Generates a plain-text statement matching the COBOL STMT-FILE
     * output layout (80-character lines with STATEMENT-LINES working
     * storage structure ST-LINE0 through ST-LINE15).
     *
     * @param data the fully populated statement data
     * @return plain-text statement as a single string with line separators
     */
    private String generatePlainTextStatement(StatementData data) {
        StringBuilder sb = new StringBuilder();

        // ST-LINE0: ***...***START OF STATEMENT***...***
        sb.append(STARS_31).append("START OF STATEMENT").append(STARS_31).append('\n');

        // ST-LINE1: Customer name (75 chars) + spaces
        sb.append(padRight(data.getCustomerName(), LINE_WIDTH)).append('\n');

        // ST-LINE2, ST-LINE3, ST-LINE4: Address lines
        String[] addrLines = data.getCustomerAddress().split("\n", -1);
        for (int i = 0; i < 3; i++) {
            String addrLine = i < addrLines.length ? addrLines[i] : "";
            sb.append(padRight(addrLine, LINE_WIDTH)).append('\n');
        }

        // ST-LINE5: Dashes separator
        sb.append(DASHES_80).append('\n');

        // ST-LINE6: Centered "Basic Details" header
        sb.append(centerText("Basic Details", LINE_WIDTH)).append('\n');

        // ST-LINE7: Account ID
        sb.append(padRight("Account ID         : " + data.getAccountId(), LINE_WIDTH)).append('\n');

        // ST-LINE8: Current Balance — PIC 9(9).99- (no zero suppression)
        String balanceStr = formatCobolPic(data.getCurrentBalance(), false);
        sb.append(padRight("Current Balance    : " + balanceStr, LINE_WIDTH)).append('\n');

        // ST-LINE9: FICO Score
        sb.append(padRight("FICO Score         : " + data.getFicoScore(), LINE_WIDTH)).append('\n');

        // ST-LINE10: Dashes separator
        sb.append(DASHES_80).append('\n');

        // ST-LINE11: Centered "TRANSACTION SUMMARY" header
        sb.append(centerText("TRANSACTION SUMMARY", LINE_WIDTH)).append('\n');

        // ST-LINE12: Dashes separator
        sb.append(DASHES_80).append('\n');

        // ST-LINE13: Column headers — "Tran ID" (16) + "Tran Details" (51) + "  Tran Amount" (13)
        sb.append(padRight("Tran ID", 16))
                .append(padRight("Tran Details", 51))
                .append(padRight("  Tran Amount", 13))
                .append('\n');

        // ST-LINE14: Each transaction detail line
        // Layout: TRANID (16 chars) + space + TRANDT (49 chars) + "$" + amount PIC Z(9).99-
        for (TransactionLine line : data.getTransactionLines()) {
            sb.append(padRight(line.getTransactionId(), 16));
            sb.append(' ');
            sb.append(padRight(line.getDescription(), 49));
            sb.append('$');
            sb.append(formatCobolPic(line.getAmount(), true));
            sb.append('\n');
        }

        // ST-LINE12 again: Dashes separator before total
        sb.append(DASHES_80).append('\n');

        // ST-LINE14A: "Total EXP:" + spaces(56) + "$" + total PIC Z(9).99-
        sb.append(padRight("Total EXP:", 66));
        sb.append('$');
        sb.append(formatCobolPic(data.getTotalAmount(), true));
        sb.append('\n');

        // ST-LINE15: ***...***END OF STATEMENT***...***
        sb.append(STARS_33).append("END OF STATEMENT").append(STARS_31).append('\n');

        return sb.toString();
    }

    /**
     * Centers text within a line of the given total width.
     *
     * @param text  the text to center
     * @param width the total line width
     * @return centered string padded with spaces
     */
    private String centerText(String text, int width) {
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        int leftPad = (width - text.length()) / 2;
        int rightPad = width - text.length() - leftPad;
        return " ".repeat(leftPad) + text + " ".repeat(rightPad);
    }

    // ---------------------------------------------------------------
    // HTML statement generation (← HTML-FILE output)
    // ---------------------------------------------------------------

    /**
     * Generates an HTML statement matching the COBOL HTML-FILE output
     * structure from paragraphs 5100-WRITE-HTML-HEADER through
     * 6000-WRITE-TRANS. Uses inline CSS with the exact color codes from
     * the COBOL source:
     * <ul>
     *   <li>{@code #1d1d96b3} — header/footer background</li>
     *   <li>{@code #FFAF33} — bank info row</li>
     *   <li>{@code #f2f2f2} — customer info, detail values, and data rows</li>
     *   <li>{@code #33FFD1} — section headers (Basic Details, Transaction Summary)</li>
     *   <li>{@code #33FF5E} — transaction column headers</li>
     * </ul>
     *
     * @param data the fully populated statement data
     * @return complete HTML document string
     */
    private String generateHtmlStatement(StatementData data) {
        StringBuilder h = new StringBuilder();

        // DOCTYPE and opening tags
        h.append("<!DOCTYPE html>\n");
        h.append("<html>\n<head>\n");
        h.append("<meta charset=\"UTF-8\">\n");
        h.append("<title>HTML Table Layout</title>\n");
        h.append("</head>\n");
        h.append("<body style=\"margin:0px;\">\n");
        h.append("<table style=\"width:70%; font-family: 'Segoe UI'; font-size:12px;\">\n");

        // Header row — account statement title (← #1d1d96b3)
        h.append("<tr><td colspan=\"3\" style=\"padding:0px 5px; background-color:#1d1d96b3;\">\n");
        h.append("<h3 style=\"color:white;\">Statement for Account Number: ")
                .append(escapeHtml(data.getAccountId()))
                .append("</h3>\n");
        h.append("</td></tr>\n");

        // Bank info row (← #FFAF33)
        h.append("<tr><td colspan=\"3\" style=\"padding:0px 5px; background-color:#FFAF33;\">\n");
        h.append("<p>Bank of XYZ</p>\n");
        h.append("<p>410 Terry Ave N</p>\n");
        h.append("<p>Seattle, WA 99999</p>\n");
        h.append("</td></tr>\n");

        // Customer info row (← #f2f2f2)
        h.append("<tr><td colspan=\"3\" style=\"padding:0px 5px; background-color:#f2f2f2;\">\n");
        h.append("<p>").append(escapeHtml(data.getCustomerName())).append("</p>\n");

        // Split address lines for HTML display
        String[] addrLines = data.getCustomerAddress().split("\n", -1);
        for (String addrLine : addrLines) {
            h.append("<p>").append(escapeHtml(addrLine)).append("</p>\n");
        }
        h.append("</td></tr>\n");

        // Basic Details section header (← #33FFD1)
        h.append("<tr><td colspan=\"3\" style=\"padding:0px 5px; background-color:#33FFD1; text-align:center;\">\n");
        h.append("<h3>Basic Details</h3>\n");
        h.append("</td></tr>\n");

        // Account ID row (← #f2f2f2)
        h.append("<tr><td colspan=\"3\" style=\"padding:0px 5px; background-color:#f2f2f2;\">\n");
        h.append("<p>Account ID: ").append(escapeHtml(data.getAccountId())).append("</p>\n");
        h.append("</td></tr>\n");

        // Current Balance row (← #f2f2f2)
        h.append("<tr><td colspan=\"3\" style=\"padding:0px 5px; background-color:#f2f2f2;\">\n");
        h.append("<p>Current Balance: ")
                .append(data.getCurrentBalance().setScale(2, RoundingMode.HALF_UP).toPlainString())
                .append("</p>\n");
        h.append("</td></tr>\n");

        // FICO Score row (← #f2f2f2)
        h.append("<tr><td colspan=\"3\" style=\"padding:0px 5px; background-color:#f2f2f2;\">\n");
        h.append("<p>FICO Score: ").append(data.getFicoScore()).append("</p>\n");
        h.append("</td></tr>\n");

        // Transaction Summary section header (← #33FFD1)
        h.append("<tr><td colspan=\"3\" style=\"padding:0px 5px; background-color:#33FFD1; text-align:center;\">\n");
        h.append("<h3>Transaction Summary</h3>\n");
        h.append("</td></tr>\n");

        // Transaction column headers (← #33FF5E)
        h.append("<tr>\n");
        h.append("<td style=\"width:25%; text-align:left; padding:0px 5px; background-color:#33FF5E;\"><p>Tran ID</p></td>\n");
        h.append("<td style=\"width:55%; text-align:left; padding:0px 5px; background-color:#33FF5E;\"><p>Tran Details</p></td>\n");
        h.append("<td style=\"width:20%; text-align:right; padding:0px 5px; background-color:#33FF5E;\"><p>Amount</p></td>\n");
        h.append("</tr>\n");

        // Transaction detail rows (← #f2f2f2)
        for (TransactionLine line : data.getTransactionLines()) {
            h.append("<tr>\n");
            h.append("<td style=\"width:25%; text-align:left; padding:0px 5px; background-color:#f2f2f2;\">");
            h.append("<p>").append(escapeHtml(line.getTransactionId())).append("</p></td>\n");
            h.append("<td style=\"width:55%; text-align:left; padding:0px 5px; background-color:#f2f2f2;\">");
            h.append("<p>").append(escapeHtml(line.getDescription())).append("</p></td>\n");
            h.append("<td style=\"width:20%; text-align:right; padding:0px 5px; background-color:#f2f2f2;\">");
            h.append("<p>").append(line.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString())
                    .append("</p></td>\n");
            h.append("</tr>\n");
        }

        // End of Statement row (← #1d1d96b3)
        h.append("<tr><td colspan=\"3\" style=\"padding:0px 5px; background-color:#1d1d96b3;\">\n");
        h.append("<h3 style=\"color:white;\">End of Statement</h3>\n");
        h.append("</td></tr>\n");

        // Closing tags
        h.append("</table>\n");
        h.append("</body>\n</html>\n");

        return h.toString();
    }

    /**
     * Escapes HTML special characters to prevent XSS and ensure correct
     * rendering of user-provided data in HTML statements.
     *
     * @param text raw text that may contain HTML special characters
     * @return HTML-safe escaped string
     */
    private String escapeHtml(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    // ---------------------------------------------------------------
    // Inner class: StatementData — statement output carrier
    // ---------------------------------------------------------------

    /**
     * Output carrier containing all data for a single account statement,
     * including both the structured data fields and the pre-formatted
     * plain-text and HTML renderings.
     *
     * <p>Corresponds to the combined output of COBOL program CBSTM03A.CBL's
     * STATEMENT-LINES (plain text) and HTML-LINES (HTML) working storage
     * areas written to STMT-FILE and HTML-FILE respectively.</p>
     */
    public static class StatementData {

        private String customerName;
        private String customerAddress;
        private String accountId;
        private String cardNumber;
        private BigDecimal currentBalance;
        private int ficoScore;
        private List<TransactionLine> transactionLines;
        private BigDecimal totalAmount;
        private String plainTextStatement;
        private String htmlStatement;

        /** Default constructor. */
        public StatementData() {
            this.currentBalance = BigDecimal.ZERO;
            this.totalAmount = BigDecimal.ZERO;
            this.transactionLines = new ArrayList<>();
        }

        /**
         * Returns the formatted customer name.
         * @return customer name (first + middle + last)
         */
        public String getCustomerName() {
            return customerName;
        }

        /**
         * Sets the formatted customer name.
         * @param customerName the customer full name
         */
        public void setCustomerName(String customerName) {
            this.customerName = customerName;
        }

        /**
         * Returns the multi-line customer address.
         * @return customer address with newline-separated lines
         */
        public String getCustomerAddress() {
            return customerAddress;
        }

        /**
         * Sets the multi-line customer address.
         * @param customerAddress the address string with newlines
         */
        public void setCustomerAddress(String customerAddress) {
            this.customerAddress = customerAddress;
        }

        /**
         * Returns the 11-character account ID (← ACCT-ID).
         * @return account identifier
         */
        public String getAccountId() {
            return accountId;
        }

        /**
         * Sets the account ID.
         * @param accountId the 11-character account identifier
         */
        public void setAccountId(String accountId) {
            this.accountId = accountId;
        }

        /**
         * Returns the 16-character card number (← XREF-CARD-NUM).
         * @return card number
         */
        public String getCardNumber() {
            return cardNumber;
        }

        /**
         * Sets the card number.
         * @param cardNumber the 16-character card number
         */
        public void setCardNumber(String cardNumber) {
            this.cardNumber = cardNumber;
        }

        /**
         * Returns the current account balance as {@link BigDecimal}
         * (← ACCT-CURR-BAL PIC S9(10)V99 COMP-3).
         * @return current balance, never null
         */
        public BigDecimal getCurrentBalance() {
            return currentBalance;
        }

        /**
         * Sets the current account balance.
         * @param currentBalance the balance as BigDecimal
         */
        public void setCurrentBalance(BigDecimal currentBalance) {
            this.currentBalance = currentBalance;
        }

        /**
         * Returns the FICO credit score (← CUST-FICO-CREDIT-SCORE PIC 9(03)).
         * @return FICO score, defaults to 0 if unavailable
         */
        public int getFicoScore() {
            return ficoScore;
        }

        /**
         * Sets the FICO credit score.
         * @param ficoScore the score value
         */
        public void setFicoScore(int ficoScore) {
            this.ficoScore = ficoScore;
        }

        /**
         * Returns the list of transaction detail lines.
         * @return transaction lines, never null
         */
        public List<TransactionLine> getTransactionLines() {
            return transactionLines;
        }

        /**
         * Sets the list of transaction detail lines.
         * @param transactionLines the detail lines
         */
        public void setTransactionLines(List<TransactionLine> transactionLines) {
            this.transactionLines = transactionLines;
        }

        /**
         * Returns the statement total amount as {@link BigDecimal}
         * (← WS-TOTAL-AMT PIC S9(9)V99 COMP-3).
         * @return total amount with scale 2, never null
         */
        public BigDecimal getTotalAmount() {
            return totalAmount;
        }

        /**
         * Sets the statement total amount.
         * @param totalAmount the total as BigDecimal
         */
        public void setTotalAmount(BigDecimal totalAmount) {
            this.totalAmount = totalAmount;
        }

        /**
         * Returns the pre-formatted plain-text statement (← STMT-FILE).
         * @return plain-text statement string
         */
        public String getPlainTextStatement() {
            return plainTextStatement;
        }

        /**
         * Sets the pre-formatted plain-text statement.
         * @param plainTextStatement the formatted text
         */
        public void setPlainTextStatement(String plainTextStatement) {
            this.plainTextStatement = plainTextStatement;
        }

        /**
         * Returns the pre-formatted HTML statement (← HTML-FILE).
         * @return HTML statement string
         */
        public String getHtmlStatement() {
            return htmlStatement;
        }

        /**
         * Sets the pre-formatted HTML statement.
         * @param htmlStatement the HTML document string
         */
        public void setHtmlStatement(String htmlStatement) {
            this.htmlStatement = htmlStatement;
        }
    }

    // ---------------------------------------------------------------
    // Inner class: TransactionLine — single transaction detail line
    // ---------------------------------------------------------------

    /**
     * Represents a single transaction detail line in a statement,
     * corresponding to COBOL ST-LINE14 layout: transaction ID (16 chars),
     * transaction details (49 chars), and amount (PIC Z(9).99-).
     */
    public static class TransactionLine {

        private String transactionId;
        private String description;
        private BigDecimal amount;

        /** Default constructor. */
        public TransactionLine() {
            this.amount = BigDecimal.ZERO;
        }

        /**
         * Returns the transaction identifier (← TRNX-ID, 16 chars).
         * @return transaction ID
         */
        public String getTransactionId() {
            return transactionId;
        }

        /**
         * Sets the transaction identifier.
         * @param transactionId the transaction ID
         */
        public void setTransactionId(String transactionId) {
            this.transactionId = transactionId;
        }

        /**
         * Returns the transaction description (← TRNX-DESC, up to 100 chars).
         * @return transaction description
         */
        public String getDescription() {
            return description;
        }

        /**
         * Sets the transaction description.
         * @param description the description text
         */
        public void setDescription(String description) {
            this.description = description;
        }

        /**
         * Returns the transaction amount as {@link BigDecimal}
         * (← TRNX-AMT PIC S9(09)V99 COMP-3).
         * @return transaction amount, never null
         */
        public BigDecimal getAmount() {
            return amount;
        }

        /**
         * Sets the transaction amount.
         * @param amount the amount as BigDecimal
         */
        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }
    }
}