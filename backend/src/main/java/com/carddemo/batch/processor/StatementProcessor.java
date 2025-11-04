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
package com.carddemo.batch.processor;

import com.carddemo.entity.Account;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import com.carddemo.util.DateUtils;
import com.carddemo.util.DecimalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Spring Batch ItemProcessor implementation that transforms statement input data into formatted
 * monthly account statements with both plain text and HTML output formats.
 * 
 * <p>This processor is the Java equivalent of COBOL programs CBSTM03A.CBL and CBSTM03B.CBL which
 * generate account statements from transaction data in two formats: plain text (80-column) and HTML.
 * The processor aggregates transaction records into monthly account statements before writing via
 * StatementItemWriter.</p>
 * 
 * <p><strong>COBOL Source Programs:</strong></p>
 * <ul>
 *   <li>app/cbl/CBSTM03A.CBL - Main statement generation batch program (lines 1-925)</li>
 *   <li>app/cbl/CBSTM03B.CBL - I/O subroutine for VSAM file access (lines 1-231)</li>
 * </ul>
 * 
 * <p><strong>Key Transformation Details:</strong></p>
 * <ul>
 *   <li>CBSTM03A lines 8-9: Generate statements in two formats (plain text and HTML)</li>
 *   <li>CBSTM03A lines 33-34: 2-dimensional array OCCURS structure for transaction aggregation</li>
 *   <li>CBSTM03A line 65: WS-TOTAL-AMT PIC S9(9)V99 COMP-3 → BigDecimal with scale 2, HALF_UP rounding</li>
 *   <li>CBSTM03A lines 91-100: Statement header with customer name and address fields</li>
 *   <li>CBSTM03A lines 30-34: CALL 'CBSTM03B' → Direct JPA repository queries</li>
 *   <li>CBSTM03A lines 71-83: WS-M03B-AREA linkage → Repository method calls</li>
 *   <li>CBSTM03A lines 225-233: WS-TRNX-TABLE 2-D array → Java List<Transaction> with in-memory aggregation</li>
 * </ul>
 * 
 * <p><strong>Critical Numeric Precision Requirements (Section 0.9):</strong></p>
 * <ul>
 *   <li>COBOL WS-TOTAL-AMT PIC S9(9)V99 COMP-3 (line 65) → BigDecimal precision 11, scale 2</li>
 *   <li>ALL transaction amount aggregation uses DecimalUtils.safeAdd() with RoundingMode.HALF_UP</li>
 *   <li>Balance calculations maintain scale 2 precision throughout processing</li>
 *   <li>NO float or double types used for monetary amounts per Section 0.9</li>
 * </ul>
 * 
 * <p><strong>Batch Processing Pattern:</strong></p>
 * <ul>
 *   <li>Accepts StatementInput containing Account, Customer, List&lt;Transaction&gt;, and period dates</li>
 *   <li>Returns Statement entity with formatted plainTextContent and htmlContent</li>
 *   <li>Returns null for accounts with no transactions (enables Spring Batch filtering)</li>
 *   <li>Integrates with StatementGenerationJob chunk-oriented processing</li>
 * </ul>
 * 
 * @see ItemProcessor
 * @see StatementInput
 * @see Statement
 * @see <a href="Section 0.3">Batch Processing Transformation Rules</a>
 * @see <a href="Section 0.5">Batch Job Configuration</a>
 * @see <a href="Section 0.9">Numeric Precision Requirements</a>
 */
@Component
public class StatementProcessor implements ItemProcessor<StatementProcessor.StatementInput, StatementProcessor.Statement> {

    private static final Logger log = LoggerFactory.getLogger(StatementProcessor.class);

    /**
     * Date formatter for transaction dates in statement output.
     * Format: MM/DD/YYYY matching COBOL display date format in statement line items.
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    /**
     * DecimalUtils instance for COMP-3 BigDecimal operations with HALF_UP rounding.
     * Used for transaction amount aggregation and total balance calculations.
     */
    private final DecimalUtils decimalUtils;

    /**
     * DateUtils instance for date formatting operations in statement header and line items.
     * Provides COBOL-compatible date formatting (CCYYMMDD, MM/DD/YYYY, ISO timestamps).
     */
    private final DateUtils dateUtils;

    /**
     * Constructor-based dependency injection for utility classes.
     * 
     * @param decimalUtils BigDecimal utility for COMP-3 precision preservation
     * @param dateUtils Date utility for COBOL-compatible date formatting
     */
    @Autowired
    public StatementProcessor(DecimalUtils decimalUtils, DateUtils dateUtils) {
        this.decimalUtils = decimalUtils;
        this.dateUtils = dateUtils;
    }

    /**
     * Processes StatementInput item and generates formatted Statement output.
     * 
     * <p>This method implements the core statement generation logic from CBSTM03A.CBL mainline
     * processing (lines 316-339). It performs the following operations:</p>
     * <ol>
     *   <li>Validates input data (account, customer, transactions)</li>
     *   <li>Filters accounts with no transactions (returns null for Spring Batch skip logic)</li>
     *   <li>Sorts transactions chronologically by origination timestamp</li>
     *   <li>Generates statement header with customer information (CBSTM03A lines 458-504)</li>
     *   <li>Aggregates transaction line items with running total (CBSTM03A lines 416-456)</li>
     *   <li>Computes total transaction amount with COMP-3 precision (CBSTM03A lines 429-434)</li>
     *   <li>Formats plain text output (80-column COBOL layout)</li>
     *   <li>Formats HTML output with embedded CSS styling (CBSTM03A lines 506-673)</li>
     *   <li>Returns Statement entity with both formatted outputs</li>
     * </ol>
     * 
     * @param item StatementInput containing account, customer, transactions, and period dates
     * @return Statement entity with formatted plainTextContent and htmlContent, or null if no transactions
     * @throws Exception if error occurs during statement generation processing
     */
    @Override
    public Statement process(StatementInput item) throws Exception {
        if (item == null) {
            log.warn("Received null StatementInput, skipping processing");
            return null;
        }

        Account account = item.getAccount();
        Customer customer = item.getCustomer();
        List<Transaction> transactions = item.getTransactions();

        // Validate required data
        if (account == null || customer == null) {
            log.warn("Account or Customer is null for statement processing, skipping");
            return null;
        }

        // Filter accounts with no transactions - return null to enable Spring Batch skip logic
        if (transactions == null || transactions.isEmpty()) {
            log.info("No transactions found for account ID: {}, skipping statement generation", 
                    account.getAccountId());
            return null;
        }

        log.info("Processing statement for account ID: {}, customer ID: {}, transaction count: {}",
                account.getAccountId(), customer.getCustomerId(), transactions.size());

        // Sort transactions chronologically by origination timestamp
        // Matches COBOL logic where transactions are aggregated in date order
        List<Transaction> sortedTransactions = new ArrayList<>(transactions);
        sortedTransactions.sort(Comparator.comparing(Transaction::getOriginationTimestamp));

        // Generate plain text statement content (80-column format)
        String plainTextContent = generatePlainTextStatement(account, customer, sortedTransactions, item);

        // Generate HTML statement content with embedded CSS
        String htmlContent = generateHtmlStatement(account, customer, sortedTransactions, item);

        // Create and return Statement entity
        Statement statement = new Statement();
        statement.setAccountId(account.getAccountId());
        statement.setCustomerId(customer.getCustomerId());
        statement.setStatementDate(item.getStatementDate());
        statement.setPeriodStartDate(item.getPeriodStartDate());
        statement.setPeriodEndDate(item.getPeriodEndDate());
        statement.setPlainTextContent(plainTextContent);
        statement.setHtmlContent(htmlContent);
        statement.setTransactionCount(sortedTransactions.size());

        // Calculate total transaction amount using DecimalUtils for COMP-3 precision
        BigDecimal totalAmount = calculateTotalAmount(sortedTransactions);
        statement.setTotalAmount(totalAmount);

        log.info("Successfully generated statement for account ID: {}, total amount: {}",
                account.getAccountId(), DecimalUtils.toFormattedCurrency(totalAmount));

        return statement;
    }

    /**
     * Generates plain text statement content in 80-column format matching COBOL layout.
     * 
     * <p>This method implements COBOL statement formatting from CBSTM03A.CBL lines 458-504
     * (statement header generation) and lines 675-723 (transaction line formatting).
     * The output matches COBOL STATEMENT-LINES structure (lines 85-146) with exact column
     * positioning and field alignment.</p>
     * 
     * @param account Account entity with account ID, balance, and FICO score
     * @param customer Customer entity with name and address information
     * @param transactions Sorted list of transactions for the statement period
     * @param input StatementInput with period dates
     * @return Formatted plain text statement string (80-column layout)
     */
    private String generatePlainTextStatement(Account account, Customer customer, 
                                             List<Transaction> transactions, StatementInput input) {
        StringBuilder sb = new StringBuilder();

        // ST-LINE0: Statement start marker (CBSTM03A lines 86-89)
        sb.append(repeatChar('*', 31));
        sb.append("START OF STATEMENT");
        sb.append(repeatChar('*', 31));
        sb.append("\n");

        // ST-LINE1: Customer name (CBSTM03A lines 90-92, formatted lines 462-469)
        String customerName = formatCustomerName(customer);
        sb.append(padRight(customerName, 75));
        sb.append(repeatChar(' ', 5));
        sb.append("\n");

        // ST-LINE2: Address line 1 (CBSTM03A lines 93-95, 470)
        String addressLine1 = customer.getAddressLine1() != null ? customer.getAddressLine1() : "";
        sb.append(padRight(addressLine1, 50));
        sb.append(repeatChar(' ', 30));
        sb.append("\n");

        // ST-LINE3: Address line 2 (CBSTM03A lines 96-98, 471)
        String addressLine2 = customer.getAddressLine2() != null ? customer.getAddressLine2() : "";
        sb.append(padRight(addressLine2, 50));
        sb.append(repeatChar(' ', 30));
        sb.append("\n");

        // ST-LINE4: Address line 3 with city, state, country, zip (CBSTM03A lines 99-100, 472-481)
        String addressLine3 = formatAddressLine3(customer);
        sb.append(padRight(addressLine3, 80));
        sb.append("\n");

        // ST-LINE5: Separator line (CBSTM03A lines 101-102, 492)
        sb.append(repeatChar('-', 80));
        sb.append("\n");

        // ST-LINE6: "Basic Details" header (CBSTM03A lines 103-106, 493)
        sb.append(repeatChar(' ', 33));
        sb.append("Basic Details");
        sb.append(repeatChar(' ', 34));
        sb.append("\n");

        // ST-LINE5: Separator line (CBSTM03A line 494)
        sb.append(repeatChar('-', 80));
        sb.append("\n");

        // ST-LINE7: Account ID (CBSTM03A lines 107-110, 483, 495)
        sb.append("Account ID         :");
        sb.append(padRight(String.valueOf(account.getAccountId()), 20));
        sb.append(repeatChar(' ', 40));
        sb.append("\n");

        // ST-LINE8: Current Balance (CBSTM03A lines 111-115, 484, 496)
        sb.append("Current Balance    :");
        String balanceStr = formatMoney(account.getCurrentBalance());
        sb.append(padLeft(balanceStr, 13));
        sb.append(repeatChar(' ', 47));
        sb.append("\n");

        // ST-LINE9: FICO Score (CBSTM03A lines 116-119, 485, 497)
        sb.append("FICO Score         :");
        String ficoScore = customer.getFicoCreditScore() != null ? 
                String.valueOf(customer.getFicoCreditScore()) : "";
        sb.append(padRight(ficoScore, 20));
        sb.append(repeatChar(' ', 40));
        sb.append("\n");

        // ST-LINE10: Separator line (CBSTM03A lines 120-121, 498)
        sb.append(repeatChar('-', 80));
        sb.append("\n");

        // ST-LINE11: "TRANSACTION SUMMARY" header (CBSTM03A lines 122-125, 499)
        sb.append(repeatChar(' ', 30));
        sb.append("TRANSACTION SUMMARY ");
        sb.append(repeatChar(' ', 30));
        sb.append("\n");

        // ST-LINE12: Separator line (CBSTM03A lines 126-127, 500)
        sb.append(repeatChar('-', 80));
        sb.append("\n");

        // ST-LINE13: Transaction column headers (CBSTM03A lines 128-131, 501)
        sb.append("Tran ID         ");
        sb.append("Tran Details    ");
        sb.append(repeatChar(' ', 35));
        sb.append("  Tran Amount");
        sb.append("\n");

        // ST-LINE12: Separator line (CBSTM03A line 502)
        sb.append(repeatChar('-', 80));
        sb.append("\n");

        // Transaction detail lines (ST-LINE14 for each transaction, CBSTM03A lines 132-137, 675-679)
        for (Transaction transaction : transactions) {
            // ST-TRANID: Transaction ID (16 chars)
            sb.append(padRight(transaction.getTransactionId(), 16));
            sb.append(" ");

            // ST-TRANDT: Transaction description (49 chars)
            String description = transaction.getTransactionDescription() != null ?
                    transaction.getTransactionDescription() : "";
            sb.append(padRight(description, 49));

            // ST-TRANAMT: Transaction amount with $ sign and formatting
            sb.append("$");
            String amountStr = formatMoney(transaction.getTransactionAmount());
            sb.append(padLeft(amountStr, 13));
            sb.append("\n");
        }

        // ST-LINE12: Separator line before total (CBSTM03A line 435)
        sb.append(repeatChar('-', 80));
        sb.append("\n");

        // ST-LINE14A: Total transaction amount (CBSTM03A lines 138-142, 434-436)
        BigDecimal totalAmount = calculateTotalAmount(transactions);
        sb.append("Total EXP:");
        sb.append(repeatChar(' ', 56));
        sb.append("$");
        String totalStr = formatMoney(totalAmount);
        sb.append(padLeft(totalStr, 13));
        sb.append("\n");

        // ST-LINE15: Statement end marker (CBSTM03A lines 143-146, 437)
        sb.append(repeatChar('*', 32));
        sb.append("END OF STATEMENT");
        sb.append(repeatChar('*', 32));
        sb.append("\n");

        return sb.toString();
    }

    /**
     * Generates HTML statement content with embedded CSS styling.
     * 
     * <p>This method implements COBOL HTML statement generation from CBSTM03A.CBL lines 506-673
     * (HTML header and body generation with inline styles). The HTML output uses embedded CSS
     * to match the visual styling defined in COBOL HTML-LINES structure (lines 148-224).</p>
     * 
     * @param account Account entity with account ID, balance, and FICO score
     * @param customer Customer entity with name and address information
     * @param transactions Sorted list of transactions for the statement period
     * @param input StatementInput with period dates
     * @return Formatted HTML statement string with embedded CSS styling
     */
    private String generateHtmlStatement(Account account, Customer customer,
                                        List<Transaction> transactions, StatementInput input) {
        StringBuilder sb = new StringBuilder();

        // HTML document structure (CBSTM03A lines 508-523)
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html lang=\"en\">\n");
        sb.append("<head>\n");
        sb.append("<meta charset=\"utf-8\">\n");
        sb.append("<title>HTML Table Layout</title>\n");
        sb.append("</head>\n");
        sb.append("<body style=\"margin:0px;\">\n");
        sb.append("<table align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">\n");

        // Statement title row with account number (CBSTM03A lines 524-534)
        sb.append("<tr>\n");
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px; background-color:#1d1d96b3;\">\n");
        sb.append("<h3>Statement for Account Number: ");
        sb.append(account.getAccountId());
        sb.append("</h3>\n");
        sb.append("</td>\n");
        sb.append("</tr>\n");

        // Bank header information (CBSTM03A lines 535-548)
        sb.append("<tr>\n");
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px; background-color:#FFAF33;\">\n");
        sb.append("<p style=\"font-size:16px\">Bank of XYZ</p>\n");
        sb.append("<p>410 Terry Ave N</p>\n");
        sb.append("<p>Seattle WA 99999</p>\n");
        sb.append("</td>\n");
        sb.append("</tr>\n");

        // Customer name and address section (CBSTM03A lines 549-593)
        sb.append("<tr>\n");
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px; background-color:#f2f2f2;\">\n");
        String customerName = formatCustomerName(customer);
        sb.append("<p style=\"font-size:16px\">");
        sb.append(escapeHtml(customerName));
        sb.append("</p>\n");

        if (customer.getAddressLine1() != null && !customer.getAddressLine1().trim().isEmpty()) {
            sb.append("<p>");
            sb.append(escapeHtml(customer.getAddressLine1()));
            sb.append("</p>\n");
        }

        if (customer.getAddressLine2() != null && !customer.getAddressLine2().trim().isEmpty()) {
            sb.append("<p>");
            sb.append(escapeHtml(customer.getAddressLine2()));
            sb.append("</p>\n");
        }

        String addressLine3 = formatAddressLine3(customer);
        if (!addressLine3.trim().isEmpty()) {
            sb.append("<p>");
            sb.append(escapeHtml(addressLine3));
            sb.append("</p>\n");
        }

        sb.append("</td>\n");
        sb.append("</tr>\n");

        // Basic Details section header (CBSTM03A lines 598-607)
        sb.append("<tr>\n");
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px; background-color:#33FFD1; text-align:center;\">\n");
        sb.append("<p style=\"font-size:16px\">Basic Details</p>\n");
        sb.append("</td>\n");
        sb.append("</tr>\n");

        // Account ID row (CBSTM03A lines 613-619)
        sb.append("<tr>\n");
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px; background-color:#f2f2f2;\">\n");
        sb.append("<p>Account ID         : ");
        sb.append(account.getAccountId());
        sb.append("</p>\n");
        sb.append("</td>\n");
        sb.append("</tr>\n");

        // Current Balance row (CBSTM03A lines 620-626)
        sb.append("<tr>\n");
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px; background-color:#f2f2f2;\">\n");
        sb.append("<p>Current Balance    : ");
        sb.append(formatMoney(account.getCurrentBalance()));
        sb.append("</p>\n");
        sb.append("</td>\n");
        sb.append("</tr>\n");

        // FICO Score row (CBSTM03A lines 627-633)
        sb.append("<tr>\n");
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px; background-color:#f2f2f2;\">\n");
        sb.append("<p>FICO Score         : ");
        String ficoScore = customer.getFicoCreditScore() != null ?
                String.valueOf(customer.getFicoCreditScore()) : "";
        sb.append(ficoScore);
        sb.append("</p>\n");
        sb.append("</td>\n");
        sb.append("</tr>\n");

        // Transaction Summary section header (CBSTM03A lines 638-647)
        sb.append("<tr>\n");
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px; background-color:#33FFD1; text-align:center;\">\n");
        sb.append("<p style=\"font-size:16px\">Transaction Summary</p>\n");
        sb.append("</td>\n");
        sb.append("</tr>\n");

        // Transaction table headers (CBSTM03A lines 648-669)
        sb.append("<tr>\n");
        sb.append("<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">\n");
        sb.append("<p style=\"font-size:16px\">Tran ID</p>\n");
        sb.append("</td>\n");
        sb.append("<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">\n");
        sb.append("<p style=\"font-size:16px\">Tran Details</p>\n");
        sb.append("</td>\n");
        sb.append("<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;\">\n");
        sb.append("<p style=\"font-size:16px\">Amount</p>\n");
        sb.append("</td>\n");
        sb.append("</tr>\n");

        // Transaction detail rows (CBSTM03A lines 681-721)
        for (Transaction transaction : transactions) {
            sb.append("<tr>\n");

            // Transaction ID cell
            sb.append("<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">\n");
            sb.append("<p>");
            sb.append(escapeHtml(transaction.getTransactionId()));
            sb.append("</p>\n");
            sb.append("</td>\n");

            // Transaction description cell
            sb.append("<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">\n");
            sb.append("<p>");
            String description = transaction.getTransactionDescription() != null ?
                    transaction.getTransactionDescription() : "";
            sb.append(escapeHtml(description));
            sb.append("</p>\n");
            sb.append("</td>\n");

            // Transaction amount cell
            sb.append("<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;\">\n");
            sb.append("<p>");
            sb.append(formatMoney(transaction.getTransactionAmount()));
            sb.append("</p>\n");
            sb.append("</td>\n");

            sb.append("</tr>\n");
        }

        // End of Statement row (CBSTM03A lines 439-453)
        sb.append("<tr>\n");
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px; background-color:#1d1d96b3;\">\n");
        sb.append("<h3>End of Statement</h3>\n");
        sb.append("</td>\n");
        sb.append("</tr>\n");

        // Close table, body, and html tags (CBSTM03A lines 449-454)
        sb.append("</table>\n");
        sb.append("</body>\n");
        sb.append("</html>\n");

        return sb.toString();
    }

    /**
     * Calculates total transaction amount using DecimalUtils for COMP-3 precision preservation.
     * 
     * <p>This method implements COBOL WS-TOTAL-AMT accumulation logic from CBSTM03A.CBL line 429:
     * "ADD TRNX-AMT TO WS-TOTAL-AMT". The COBOL field WS-TOTAL-AMT is defined as PIC S9(9)V99 COMP-3
     * (line 65), requiring BigDecimal with scale 2 and RoundingMode.HALF_UP per Section 0.9.</p>
     * 
     * @param transactions List of transactions to sum
     * @return Total transaction amount with scale 2 and HALF_UP rounding
     */
    private BigDecimal calculateTotalAmount(List<Transaction> transactions) {
        BigDecimal total = DecimalUtils.createMoneyAmount("0.00");

        for (Transaction transaction : transactions) {
            if (transaction.getTransactionAmount() != null) {
                // Use DecimalUtils.safeAdd() to maintain COMP-3 precision with HALF_UP rounding
                total = DecimalUtils.safeAdd(total, transaction.getTransactionAmount());
            }
        }

        // Ensure final total has scale 2 with HALF_UP rounding matching COBOL COMP-3
        return DecimalUtils.setScaleWithRounding(total, DecimalUtils.MONEY_SCALE);
    }

    /**
     * Formats customer name from name components matching COBOL STRING statement.
     * 
     * <p>This method implements COBOL logic from CBSTM03A.CBL lines 462-469:
     * STRING CUST-FIRST-NAME DELIMITED BY ' '
     *        ' ' DELIMITED BY SIZE
     *        CUST-MIDDLE-NAME DELIMITED BY ' '
     *        ' ' DELIMITED BY SIZE
     *        CUST-LAST-NAME DELIMITED BY ' '
     *        INTO ST-NAME</p>
     * 
     * @param customer Customer entity with name components
     * @return Formatted customer name string
     */
    private String formatCustomerName(Customer customer) {
        StringBuilder name = new StringBuilder();

        if (customer.getFirstName() != null) {
            name.append(customer.getFirstName().trim());
        }

        if (customer.getMiddleName() != null && !customer.getMiddleName().trim().isEmpty()) {
            if (name.length() > 0) {
                name.append(" ");
            }
            name.append(customer.getMiddleName().trim());
        }

        if (customer.getLastName() != null) {
            if (name.length() > 0) {
                name.append(" ");
            }
            name.append(customer.getLastName().trim());
        }

        return name.toString();
    }

    /**
     * Formats address line 3 with city, state, country, and ZIP code.
     * 
     * <p>This method implements COBOL logic from CBSTM03A.CBL lines 472-481:
     * STRING CUST-ADDR-LINE-3 DELIMITED BY ' '
     *        ' ' DELIMITED BY SIZE
     *        CUST-ADDR-STATE-CD DELIMITED BY ' '
     *        ' ' DELIMITED BY SIZE
     *        CUST-ADDR-COUNTRY-CD DELIMITED BY ' '
     *        ' ' DELIMITED BY SIZE
     *        CUST-ADDR-ZIP DELIMITED BY ' '
     *        INTO ST-ADD3</p>
     * 
     * @param customer Customer entity with address components
     * @return Formatted address line 3 string
     */
    private String formatAddressLine3(Customer customer) {
        StringBuilder address = new StringBuilder();

        if (customer.getAddressLine3() != null && !customer.getAddressLine3().trim().isEmpty()) {
            address.append(customer.getAddressLine3().trim());
        }

        if (customer.getStateCode() != null && !customer.getStateCode().trim().isEmpty()) {
            if (address.length() > 0) {
                address.append(" ");
            }
            address.append(customer.getStateCode().trim());
        }

        if (customer.getCountryCode() != null && !customer.getCountryCode().trim().isEmpty()) {
            if (address.length() > 0) {
                address.append(" ");
            }
            address.append(customer.getCountryCode().trim());
        }

        if (customer.getZipCode() != null && !customer.getZipCode().trim().isEmpty()) {
            if (address.length() > 0) {
                address.append(" ");
            }
            address.append(customer.getZipCode().trim());
        }

        return address.toString();
    }

    /**
     * Formats BigDecimal money amount for display with 2 decimal places.
     * Matches COBOL PIC Z(9).99- display format from CBSTM03A.CBL ST-TRANAMT (line 137).
     * 
     * @param amount BigDecimal amount to format
     * @return Formatted string with 2 decimal places (e.g., "1234.56")
     */
    private String formatMoney(BigDecimal amount) {
        if (amount == null) {
            return "0.00";
        }
        // Ensure scale 2 for display
        BigDecimal scaledAmount = DecimalUtils.setScaleWithRounding(amount, DecimalUtils.MONEY_SCALE);
        return DecimalUtils.toPlainString(scaledAmount);
    }

    /**
     * Pads string to the right with spaces to specified length.
     * Truncates if string exceeds specified length.
     * 
     * @param str String to pad
     * @param length Target length
     * @return Padded or truncated string
     */
    private String padRight(String str, int length) {
        if (str == null) {
            str = "";
        }
        if (str.length() >= length) {
            return str.substring(0, length);
        }
        return String.format("%-" + length + "s", str);
    }

    /**
     * Pads string to the left with spaces to specified length.
     * Truncates if string exceeds specified length.
     * 
     * @param str String to pad
     * @param length Target length
     * @return Padded or truncated string
     */
    private String padLeft(String str, int length) {
        if (str == null) {
            str = "";
        }
        if (str.length() >= length) {
            return str.substring(0, length);
        }
        return String.format("%" + length + "s", str);
    }

    /**
     * Repeats a character n times.
     * Used for creating separator lines and padding in statement formatting.
     * 
     * @param ch Character to repeat
     * @param count Number of times to repeat
     * @return String containing repeated character
     */
    private String repeatChar(char ch, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(ch);
        }
        return sb.toString();
    }

    /**
     * Escapes HTML special characters to prevent injection and rendering issues.
     * Converts &, <, >, ", and ' to their HTML entity equivalents.
     * 
     * @param text Text to escape
     * @return HTML-escaped text
     */
    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    /**
     * Input DTO for StatementProcessor containing all data required for statement generation.
     * 
     * <p>This class represents the input data structure passed to the processor, containing
     * account information, customer details, transaction list, and statement period dates.
     * It replaces COBOL CBSTM03B subroutine linkage area WS-M03B-AREA (CBSTM03A lines 71-83)
     * with direct data access via JPA entities per Section 0.3 transformation rules.</p>
     * 
     * <p><strong>COBOL Source Mapping:</strong></p>
     * <ul>
     *   <li>Account data: CBSTM03A lines 392-414 (ACCTFILE read via CBSTM03B)</li>
     *   <li>Customer data: CBSTM03A lines 368-390 (CUSTFILE read via CBSTM03B)</li>
     *   <li>Transaction data: CBSTM03A lines 416-456 (TRNXFILE sequential read)</li>
     *   <li>Period dates: Determined by batch job scheduling (monthly statement cycle)</li>
     * </ul>
     */
    public static class StatementInput {
        private Account account;
        private Customer customer;
        private List<Transaction> transactions;
        private LocalDate statementDate;
        private LocalDate periodStartDate;
        private LocalDate periodEndDate;

        /**
         * Default constructor for StatementInput.
         */
        public StatementInput() {
        }

        /**
         * Full constructor for StatementInput.
         * 
         * @param account Account entity with account details
         * @param customer Customer entity with customer information
         * @param transactions List of transactions for the statement period
         * @param statementDate Date the statement is generated
         * @param periodStartDate Start date of the statement period
         * @param periodEndDate End date of the statement period
         */
        public StatementInput(Account account, Customer customer, List<Transaction> transactions,
                            LocalDate statementDate, LocalDate periodStartDate, LocalDate periodEndDate) {
            this.account = account;
            this.customer = customer;
            this.transactions = transactions;
            this.statementDate = statementDate;
            this.periodStartDate = periodStartDate;
            this.periodEndDate = periodEndDate;
        }

        public Account getAccount() {
            return account;
        }

        public void setAccount(Account account) {
            this.account = account;
        }

        public Customer getCustomer() {
            return customer;
        }

        public void setCustomer(Customer customer) {
            this.customer = customer;
        }

        public List<Transaction> getTransactions() {
            return transactions;
        }

        public void setTransactions(List<Transaction> transactions) {
            this.transactions = transactions;
        }

        public LocalDate getStatementDate() {
            return statementDate;
        }

        public void setStatementDate(LocalDate statementDate) {
            this.statementDate = statementDate;
        }

        public LocalDate getPeriodStartDate() {
            return periodStartDate;
        }

        public void setPeriodStartDate(LocalDate periodStartDate) {
            this.periodStartDate = periodStartDate;
        }

        public LocalDate getPeriodEndDate() {
            return periodEndDate;
        }

        public void setPeriodEndDate(LocalDate periodEndDate) {
            this.periodEndDate = periodEndDate;
        }
    }

    /**
     * Output DTO for StatementProcessor containing formatted statement content.
     * 
     * <p>This class represents the output data structure returned by the processor, containing
     * both plain text and HTML formatted statement content. It replaces COBOL output files
     * STMTFILE (80-column plain text) and HTMLFILE (100-column HTML) from CBSTM03A lines 39-40
     * with a single entity containing both formats for flexible output processing.</p>
     * 
     * <p><strong>COBOL Source Mapping:</strong></p>
     * <ul>
     *   <li>Plain text content: CBSTM03A FD-STMTFILE-REC (80-byte records)</li>
     *   <li>HTML content: CBSTM03A FD-HTMLFILE-REC (100-byte records)</li>
     *   <li>Statement metadata: Account ID, customer ID, period dates, totals</li>
     * </ul>
     * 
     * <p>The Statement entity will be written to database or file system by StatementItemWriter
     * component in the Spring Batch job configuration per Section 0.5 batch processing pattern.</p>
     */
    public static class Statement {
        private Long accountId;
        private Long customerId;
        private LocalDate statementDate;
        private LocalDate periodStartDate;
        private LocalDate periodEndDate;
        private String plainTextContent;
        private String htmlContent;
        private Integer transactionCount;
        private BigDecimal totalAmount;

        /**
         * Default constructor for Statement.
         */
        public Statement() {
        }

        /**
         * Full constructor for Statement.
         * 
         * @param accountId Account ID for the statement
         * @param customerId Customer ID for the statement
         * @param statementDate Date the statement is generated
         * @param periodStartDate Start date of the statement period
         * @param periodEndDate End date of the statement period
         * @param plainTextContent Formatted plain text statement (80-column)
         * @param htmlContent Formatted HTML statement with embedded CSS
         * @param transactionCount Number of transactions included in statement
         * @param totalAmount Total transaction amount with COMP-3 precision
         */
        public Statement(Long accountId, Long customerId, LocalDate statementDate,
                       LocalDate periodStartDate, LocalDate periodEndDate,
                       String plainTextContent, String htmlContent,
                       Integer transactionCount, BigDecimal totalAmount) {
            this.accountId = accountId;
            this.customerId = customerId;
            this.statementDate = statementDate;
            this.periodStartDate = periodStartDate;
            this.periodEndDate = periodEndDate;
            this.plainTextContent = plainTextContent;
            this.htmlContent = htmlContent;
            this.transactionCount = transactionCount;
            this.totalAmount = totalAmount;
        }

        public Long getAccountId() {
            return accountId;
        }

        public void setAccountId(Long accountId) {
            this.accountId = accountId;
        }

        public Long getCustomerId() {
            return customerId;
        }

        public void setCustomerId(Long customerId) {
            this.customerId = customerId;
        }

        public LocalDate getStatementDate() {
            return statementDate;
        }

        public void setStatementDate(LocalDate statementDate) {
            this.statementDate = statementDate;
        }

        public LocalDate getPeriodStartDate() {
            return periodStartDate;
        }

        public void setPeriodStartDate(LocalDate periodStartDate) {
            this.periodStartDate = periodStartDate;
        }

        public LocalDate getPeriodEndDate() {
            return periodEndDate;
        }

        public void setPeriodEndDate(LocalDate periodEndDate) {
            this.periodEndDate = periodEndDate;
        }

        public String getPlainTextContent() {
            return plainTextContent;
        }

        public void setPlainTextContent(String plainTextContent) {
            this.plainTextContent = plainTextContent;
        }

        public String getHtmlContent() {
            return htmlContent;
        }

        public void setHtmlContent(String htmlContent) {
            this.htmlContent = htmlContent;
        }

        public Integer getTransactionCount() {
            return transactionCount;
        }

        public void setTransactionCount(Integer transactionCount) {
            this.transactionCount = transactionCount;
        }

        public BigDecimal getTotalAmount() {
            return totalAmount;
        }

        public void setTotalAmount(BigDecimal totalAmount) {
            this.totalAmount = totalAmount;
        }

        @Override
        public String toString() {
            return "Statement{" +
                    "accountId=" + accountId +
                    ", customerId=" + customerId +
                    ", statementDate=" + statementDate +
                    ", periodStartDate=" + periodStartDate +
                    ", periodEndDate=" + periodEndDate +
                    ", transactionCount=" + transactionCount +
                    ", totalAmount=" + (totalAmount != null ? DecimalUtils.toFormattedCurrency(totalAmount) : "null") +
                    '}';
        }
    }
}
