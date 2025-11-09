package com.carddemo.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Transaction report response DTO containing aggregated transaction data for report generation and analytics.
 * 
 * <p>This DTO provides comprehensive transaction reporting capabilities with aggregation, filtering, and summary
 * statistics, replacing COBOL mainframe report generation logic from CORPT00C.cbl (CR00 transaction report program)
 * and batch statement generation programs CBSTM03A.cbl/CBSTM03B.cbl. It transforms VSAM sequential file processing
 * and COBOL arithmetic aggregation to PostgreSQL analytical queries with Spring Boot service layer aggregation.</p>
 * 
 * <h2>COBOL Source Mapping</h2>
 * <p>This report DTO consolidates functionality from multiple COBOL programs:</p>
 * <ul>
 *   <li><b>CORPT00C.cbl (CR00 transaction)</b>: Online transaction report generation with date range filtering,
 *       VSAM STARTBR/READNEXT transaction file browsing, accumulation of transaction totals using WORKING-STORAGE
 *       counters and COBOL COMPUTE statements → replaced by TransactionRepository.findByOriginationTimestampBetween()
 *       with Spring Data JPA and BigDecimal aggregation using SQL SUM(), AVG(), MIN(), MAX(), COUNT() functions</li>
 *   <li><b>CBSTM03A.cbl (CREASTMT job step 1)</b>: Batch statement generation main program reading TRANSACT VSAM file
 *       with SORT utility for account sequencing, computing statement period totals and category breakdowns using
 *       COBOL nested loops and 88-level condition checks → replaced by Spring Batch StatementGenerationJob with
 *       chunk-oriented processing (chunk size=1000) using ItemReader from transaction table with ORDER BY account_id,
 *       ItemProcessor aggregating with Java Stream collectors groupingBy category code</li>
 *   <li><b>CBSTM03B.cbl (CREASTMT job step 2)</b>: Statement detail formatting subprogram called from CBSTM03A,
 *       formatting transaction lines for SYSOUT report with COBOL MOVE statements for field alignment and
 *       decimal editing using PIC edit masks → replaced by StatementDetailProcessor applying formatting logic and
 *       JasperReports or iText PDF generation with template matching mainframe statement layout</li>
 * </ul>
 * 
 * <h2>Report Structure and Nested Classes</h2>
 * <p>The report response contains three nested data structures representing different aggregation levels:</p>
 * <ol>
 *   <li><b>FilterCriteria</b>: Applied filter parameters defining report scope (date range, card number, type, category)</li>
 *   <li><b>TransactionSummary</b>: Aggregate statistics across all transactions in report (count, total, average, min, max)</li>
 *   <li><b>CategoryBreakdown</b>: Per-category aggregation for spending analysis by transaction category</li>
 * </ol>
 * 
 * <h2>Critical Implementation Details</h2>
 * 
 * <h3>BigDecimal Aggregation with COBOL COMP-3 Precision</h3>
 * <p>All monetary aggregation fields (totalAmount, averageAmount, minAmount, maxAmount) use {@link BigDecimal}
 * with scale=2 and RoundingMode.HALF_UP matching COBOL COMP-3 packed decimal arithmetic from CVTRA05Y.cpy
 * TRAN-AMT PIC S9(09)V99. Aggregation logic in ReportGenerationService must:</p>
 * <ul>
 *   <li>Initialize BigDecimal.ZERO for accumulator variables, never use primitive 0.0</li>
 *   <li>Use .add(amount) for summing transaction amounts, never use += operator</li>
 *   <li>Use .divide(count, 2, RoundingMode.HALF_UP) for average calculation with explicit scale and rounding</li>
 *   <li>Use .max(amount) and .min(amount) for range calculation maintaining scale=2</li>
 *   <li>Serialize monetary fields as JSON strings using @JsonFormat(shape = JsonFormat.Shape.STRING) preventing
 *       JavaScript Number precision loss identical to TransactionResponse.amount field handling</li>
 * </ul>
 * 
 * <h3>Date Range Filtering Transformation</h3>
 * <p>COBOL CORPT00C.cbl date range filtering using WORKING-STORAGE fields:</p>
 * <pre>
 * 05 WS-START-DATE.
 *    10 WS-START-DATE-YYYY PIC X(04).
 *    10 FILLER             PIC X(01) VALUE '-'.
 *    10 WS-START-DATE-MM   PIC X(02).
 *    10 FILLER             PIC X(01) VALUE '-'.
 *    10 WS-START-DATE-DD   PIC X(02).
 * 05 WS-END-DATE.
 *    10 WS-END-DATE-YYYY   PIC X(04).
 *    10 FILLER             PIC X(01) VALUE '-'.
 *    10 WS-END-DATE-MM     PIC X(02).
 *    10 FILLER             PIC X(01) VALUE '-'.
 *    10 WS-END-DATE-DD     PIC X(02).
 * </pre>
 * <p>Transforms to FilterCriteria nested class with {@link LocalDate} startDate and endDate fields formatted
 * as ISO 8601 "yyyy-MM-dd" strings in JSON, enabling PostgreSQL date range query with BETWEEN operator on
 * transaction.origination_timestamp column indexed for performance.</p>
 * 
 * <h3>Category Breakdown Aggregation</h3>
 * <p>COBOL nested loop pattern for category accumulation:</p>
 * <pre>
 * PERFORM VARYING WS-CAT-IDX FROM 1 BY 1 UNTIL WS-CAT-IDX > WS-CAT-COUNT
 *    IF TRAN-CAT-CD = WS-CAT-CD(WS-CAT-IDX)
 *       ADD TRAN-AMT TO WS-CAT-TOTAL(WS-CAT-IDX)
 *       ADD 1 TO WS-CAT-COUNT(WS-CAT-IDX)
 *    END-IF
 * END-PERFORM
 * </pre>
 * <p>Transforms to Java Stream API with groupingBy collector:</p>
 * <pre>
 * Map&lt;String, CategoryBreakdown&gt; categoryMap = transactions.stream()
 *     .collect(Collectors.groupingBy(
 *         Transaction::getCategoryCode,
 *         Collectors.collectingAndThen(
 *             Collectors.toList(),
 *             list -&gt; CategoryBreakdown.builder()
 *                 .categoryCode(list.get(0).getCategoryCode())
 *                 .categoryName(categoryRepository.findById(list.get(0).getCategoryCode()).get().getCategoryName())
 *                 .count((long) list.size())
 *                 .totalAmount(list.stream()
 *                     .map(Transaction::getAmount)
 *                     .reduce(BigDecimal.ZERO, BigDecimal::add)
 *                     .setScale(2, RoundingMode.HALF_UP))
 *                 .build()
 *         )
 *     ));
 * </pre>
 * <p>Alternatively, use native PostgreSQL GROUP BY query for better performance with large datasets:</p>
 * <pre>
 * SELECT t.category_code, tc.category_name, COUNT(*), SUM(t.amount)
 * FROM transaction t
 * JOIN transaction_category tc ON t.category_code = tc.category_code
 * WHERE t.origination_timestamp BETWEEN ? AND ?
 * GROUP BY t.category_code, tc.category_name
 * ORDER BY SUM(t.amount) DESC
 * </pre>
 * 
 * <h3>Pagination for Large Transaction Lists</h3>
 * <p>The transactionList field contains individual transaction details matching report filter criteria.
 * To prevent response payload overflow, limit transactionList to maximum 1000 records. For reports with
 * more transactions, TransactionSummary provides aggregate statistics and export functionality generates
 * complete report files. BMS CORPT00M.bms screen pagination with PF7/PF8 keys transforms to HTTP query
 * parameters ?page=0&size=100 for paginated report detail retrieval.</p>
 * 
 * <h3>Export Format Options</h3>
 * <p>The exportFormat field indicates report output format:</p>
 * <ul>
 *   <li><b>"JSON"</b>: Standard REST API response for client-side processing (default)</li>
 *   <li><b>"PDF"</b>: Server-side PDF generation using JasperReports template matching mainframe statement format,
 *       enabling direct replacement of batch SYSOUT report printouts with downloadable PDF files</li>
 *   <li><b>"CSV"</b>: Comma-separated values for spreadsheet import and analysis, replacing mainframe flat file
 *       extract formats for downstream system integration</li>
 * </ul>
 * <p>ReportController endpoints support Content-Type negotiation with Accept header or ?format=pdf query parameter
 * to trigger appropriate export generation maintaining identical data content across formats.</p>
 * 
 * <h2>Database Performance Optimization</h2>
 * <p>Report queries require composite indexes on transaction table matching VSAM alternate index patterns:</p>
 * <ul>
 *   <li><b>idx_transaction_timestamp_card</b>: (origination_timestamp, card_number) for card-specific date range queries</li>
 *   <li><b>idx_transaction_timestamp_type</b>: (origination_timestamp, transaction_type) for type filtering</li>
 *   <li><b>idx_transaction_timestamp_category</b>: (origination_timestamp, category_code) for category breakdown</li>
 * </ul>
 * <p>Flyway migration script V7__create_indexes.sql must include these composite indexes to maintain sub-second
 * report generation matching mainframe response time targets (95th percentile &lt; 200ms for online reports,
 * 4-hour processing window for batch statement generation).</p>
 * 
 * <h2>Batch Processing Integration</h2>
 * <p>This DTO serves dual purposes:</p>
 * <ol>
 *   <li><b>Online Report Generation</b>: ReportController GET /api/reports/transactions endpoint returns JSON
 *       ReportResponse with date range filters, on-demand report generation replacing CICS transaction CR00</li>
 *   <li><b>Batch Statement Generation</b>: StatementGenerationJob Spring Batch job executes nightly producing
 *       PDF statements for all active accounts, scheduled as Kubernetes CronJob replacing mainframe JCL job
 *       scheduler, maintaining 4-hour processing window through parallel processing with thread pool executor</li>
 * </ol>
 * 
 * <h2>BMS Screen Correspondence</h2>
 * <p>This DTO replaces CORPT00M.bms report parameter entry screen with:</p>
 * <ul>
 *   <li><b>Date range input fields</b> (start date, end date) → FilterCriteria.startDate, FilterCriteria.endDate</li>
 *   <li><b>Card number filter field</b> → FilterCriteria.cardNumber</li>
 *   <li><b>Transaction type selection</b> → FilterCriteria.transactionType</li>
 *   <li><b>Category filter dropdown</b> → FilterCriteria.category</li>
 *   <li><b>Report output display</b> → TransactionSummary aggregate statistics, CategoryBreakdown list, transactionList details</li>
 * </ul>
 * <p>React TransactionReportComponent renders this response with Material-UI DataGrid for transaction table,
 * Chart.js for category breakdown visualization, and export buttons triggering PDF/CSV download from server.</p>
 * 
 * <h2>REST API Usage</h2>
 * <pre>
 * GET /api/reports/transactions?startDate=2024-01-01&endDate=2024-01-31&cardNumber=4111111111111234
 * </pre>
 * <p>Returns ReportResponse JSON with:</p>
 * <ul>
 *   <li>reportTitle: "Transaction Report"</li>
 *   <li>reportDate: "2024-02-01" (current date)</li>
 *   <li>filterCriteria: applied filters echoed back for client display</li>
 *   <li>transactionSummary: totalCount=45, totalAmount=12345.67, averageAmount=274.35, etc.</li>
 *   <li>categoryBreakdown: [{categoryCode:"0001", categoryName:"Retail", count:15, totalAmount:5678.90}, ...]</li>
 *   <li>transactionList: [TransactionResponse objects limited to 1000 records]</li>
 *   <li>exportFormat: "JSON"</li>
 * </ul>
 * 
 * @see TransactionResponse Transaction detail DTO used in transactionList field
 * @see com.carddemo.service.reporting.ReportGenerationService Service implementing report aggregation logic
 * @see com.carddemo.controller.ReportController REST controller exposing report endpoints
 * @see com.carddemo.batch.job.StatementGenerationJob Spring Batch job for monthly statement generation
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"reportTitle", "reportDate", "filterCriteria", "transactionSummary", "categoryBreakdown", "typeBreakdown", "transactions", "totalPages", "currentPage", "totalElements", "monthlyAggregates", "exportFormat"})
public class ReportResponse {

    /**
     * Descriptive title of the report for display purposes.
     * 
     * <p>Examples:</p>
     * <ul>
     *   <li>"Transaction Report" - for online ad-hoc reports</li>
     *   <li>"Monthly Statement" - for batch-generated account statements</li>
     *   <li>"Account Summary" - for account-level reporting</li>
     *   <li>"Category Analysis Report" - for spending pattern analysis</li>
     * </ul>
     */
    @JsonProperty("reportTitle")
    private String reportTitle;

    /**
     * Date and time when the report was generated for audit trail and version tracking.
     * 
     * <p>Formatted as ISO 8601 date-time string "yyyy-MM-dd'T'HH:mm:ss" in JSON serialization.
     * Always set to current timestamp at report generation time using LocalDateTime.now()
     * in ReportGenerationService, enabling users to identify report freshness and
     * maintain report history with generation timestamps.</p>
     */
    @JsonProperty("reportDate")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime reportDate;

    /**
     * Filter criteria applied to generate this report, enabling client to understand report scope.
     * 
     * <p>Contains all filtering parameters submitted in report request:</p>
     * <ul>
     *   <li>Date range (startDate, endDate) for temporal filtering</li>
     *   <li>Card number for card-specific reports</li>
     *   <li>Transaction type for type-based filtering</li>
     *   <li>Category code for category-specific analysis</li>
     * </ul>
     * 
     * <p>Null if no filters applied (full dataset report). Client UI displays
     * applied filters prominently in report header matching BMS screen filter display.</p>
     */
    @JsonProperty("filterCriteria")
    private FilterCriteria filterCriteria;

    /**
     * Aggregate statistics summarizing all transactions in the report.
     * 
     * <p>Provides high-level metrics computed using BigDecimal aggregation:</p>
     * <ul>
     *   <li>totalCount: Total number of transactions matching filters</li>
     *   <li>totalAmount: Sum of all transaction amounts</li>
     *   <li>averageAmount: Mean transaction value</li>
     *   <li>minAmount: Smallest transaction amount</li>
     *   <li>maxAmount: Largest transaction amount</li>
     * </ul>
     * 
     * <p>Replaces COBOL WORKING-STORAGE counters and accumulators:</p>
     * <pre>
     * 05 WS-TRAN-COUNT     PIC 9(05) VALUE ZEROS.
     * 05 WS-TRAN-TOTAL     PIC S9(11)V99 COMP-3 VALUE ZEROS.
     * 05 WS-TRAN-AVG       PIC S9(09)V99 COMP-3 VALUE ZEROS.
     * </pre>
     */
    @JsonProperty("transactionSummary")
    private TransactionSummary transactionSummary;

    /**
     * Per-category transaction breakdown for spending analysis.
     * 
     * <p>List of CategoryBreakdown objects, one per transaction category found in report dataset.
     * Each entry contains:</p>
     * <ul>
     *   <li>categoryCode: Transaction category identifier (e.g., "0001" for Retail)</li>
     *   <li>categoryName: Human-readable category name from transaction_category table</li>
     *   <li>count: Number of transactions in this category</li>
     *   <li>totalAmount: Sum of transaction amounts for this category</li>
     * </ul>
     * 
     * <p>Ordered by totalAmount descending to show highest spending categories first,
     * enabling category-based spending pattern analysis matching COBOL report sections
     * for category subtotals in CBSTM03A.cbl statement generation logic.</p>
     * 
     * <p>Null if report does not include category breakdown (e.g., single-category reports).</p>
     */
    @JsonProperty("categoryBreakdown")
    private List<CategoryBreakdown> categoryBreakdown;



    /**
     * Per-type transaction breakdown for transaction type analysis.
     * 
     * <p>List of TypeBreakdown objects, one per transaction type found in report dataset.
     * Each entry contains:</p>
     * <ul>
     *   <li>typeCode: Transaction type identifier (e.g., "01" for Purchase, "02" for Payment)</li>
     *   <li>typeName: Human-readable type name from transaction_type table</li>
     *   <li>transactionCount: Number of transactions of this type</li>
     *   <li>totalAmount: Sum of transaction amounts for this type</li>
     * </ul>
     * 
     * <p>Ordered by totalAmount descending to show highest transaction type volumes first,
     * enabling type-based analysis matching COBOL report sections for type subtotals.</p>
     * 
     * <p>Null if report does not include type breakdown.</p>
     */
    @JsonProperty("typeBreakdown")
    private List<TypeBreakdown> typeBreakdown;

    /**
     * Detailed transaction records included in the report.
     * 
     * <p>List of TransactionDetail DTOs containing full transaction details for each
     * transaction matching report filter criteria. Supports pagination with totalPages,
     * currentPage, and totalElements fields for efficient large dataset handling.</p>
     * 
     * <p>Each TransactionDetail includes transaction ID, card number, amount, merchant
     * details, timestamps, and transaction metadata.</p>
     * 
     * <p>Ordered by originationTimestamp descending (most recent first) matching BMS
     * transaction list screen display order.</p>
     * 
     * <p>Null if report is summary-only without transaction detail list.</p>
     */
    @JsonProperty("transactions")
    private List<TransactionDetail> transactions;

    /**
     * Total number of pages available for pagination.
     * 
     * <p>Calculated as Math.ceil(totalElements / pageSize) by Spring Data Pageable.
     * Enables client pagination controls to display page range and navigation.</p>
     */
    @JsonProperty("totalPages")
    private int totalPages;

    /**
     * Current page number (zero-based) for pagination.
     * 
     * <p>Matches Spring Data Pageable page number parameter, enabling client to
     * track current position in paginated result set.</p>
     */
    @JsonProperty("currentPage")
    private int currentPage;

    /**
     * Total number of transaction records matching filter criteria.
     * 
     * <p>Total count across all pages, enabling client to display "Showing X of Y results"
     * information and calculate pagination boundaries.</p>
     */
    @JsonProperty("totalElements")
    private long totalElements;

    /**
     * Monthly aggregation of transactions for trend analysis.
     * 
     * <p>List of MonthlyAggregate objects containing transaction statistics grouped
     * by month, enabling time-series analysis and trend visualization.</p>
     * 
     * <p>Each entry contains month identifier, transaction count, total amount, and
     * average amount for that month period.</p>
     * 
     * <p>Null if monthly aggregation not requested or date range spans less than one month.</p>
     */
    @JsonProperty("monthlyAggregates")
    private List<MonthlyAggregate> monthlyAggregates;

    /**
     * Report output format indicator for export functionality.
     * 
     * <p>Possible values:</p>
     * <ul>
     *   <li><b>"JSON"</b>: Standard REST API JSON response (default)</li>
     *   <li><b>"PDF"</b>: Server-side generated PDF document matching mainframe statement format</li>
     *   <li><b>"CSV"</b>: Comma-separated values file for spreadsheet import</li>
     * </ul>
     * 
     * <p>When "PDF" or "CSV", ReportController returns binary response with appropriate
     * Content-Type header (application/pdf or text/csv) and Content-Disposition: attachment
     * header triggering browser download. JSON format returns standard JSON response body
     * for client-side rendering in React TransactionReportComponent.</p>
     */
    @JsonProperty("exportFormat")
    private String exportFormat;

    /**
     * Filter criteria nested class containing report filtering parameters.
     * 
     * <p>This static nested class encapsulates all filter parameters applied to generate
     * the report, transforming BMS CORPT00M.bms input fields to structured DTO:</p>
     * <ul>
     *   <li>Date range fields (startDate, endDate) enable temporal filtering</li>
     *   <li>Card number filter enables card-specific reporting</li>
     *   <li>Transaction type filter enables type-based analysis</li>
     *   <li>Category filter enables category-specific reporting</li>
     * </ul>
     * 
     * <p>All fields are optional (null if not applied). Service layer constructs dynamic
     * query with WHERE clause predicates only for non-null filter values, enabling flexible
     * filter combination matching COBOL conditional logic with 88-level checks.</p>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FilterCriteria {

        /**
         * Start date for date range filtering (inclusive).
         * 
         * <p>Filters transactions with originationTimestamp >= startDate at 00:00:00 local time.
         * Formatted as ISO 8601 date string "yyyy-MM-dd" in JSON. Null if no start date filter applied.</p>
         * 
         * <p>Transforms COBOL WS-START-DATE PIC X(10) field from CORPT00C.cbl to LocalDate type
         * with automatic ISO format parsing and serialization.</p>
         */
        @JsonProperty("startDate")
        @JsonFormat(pattern = "yyyy-MM-dd")
        private LocalDate startDate;

        /**
         * End date for date range filtering (inclusive).
         * 
         * <p>Filters transactions with originationTimestamp <= endDate at 23:59:59 local time.
         * Formatted as ISO 8601 date string "yyyy-MM-dd" in JSON. Null if no end date filter applied.</p>
         * 
         * <p>Transforms COBOL WS-END-DATE PIC X(10) field from CORPT00C.cbl to LocalDate type
         * with automatic ISO format parsing and serialization.</p>
         */
        @JsonProperty("endDate")
        @JsonFormat(pattern = "yyyy-MM-dd")
        private LocalDate endDate;

        /**
         * Card number filter for card-specific reports.
         * 
         * <p>16-digit card PAN (Primary Account Number) for filtering transactions to specific card.
         * Should match full 16-digit PAN in database, not masked version. Service layer validates
         * card number format and existence before executing query. Null if no card filter applied.</p>
         * 
         * <p>Enables account-specific and card-specific transaction reporting for customer statements
         * and card activity monitoring.</p>
         */
        @JsonProperty("cardNumber")
        private String cardNumber;

        /**
         * Customer ID filter for customer-specific reports.
         * 
         * <p>Long integer customer identifier from customer table enabling targeted
         * customer reports across all accounts and cards belonging to this customer.
         * Null if no customer filter applied.</p>
         */
        @JsonProperty("customerId")
        private Long customerId;

        /**
         * Account ID filter for account-specific reports.
         * 
         * <p>Long integer account identifier from account table enabling targeted
         * account reports for specific account and all cards under that account.
         * Null if no account filter applied.</p>
         */
        @JsonProperty("accountId")
        private Long accountId;

        /**
         * Transaction type filter for type-based reporting.
         * 
         * <p>2-character transaction type code matching transaction_type table:</p>
         * <ul>
         *   <li>"01" - Purchase transaction</li>
         *   <li>"02" - Cash advance</li>
         *   <li>"03" - Payment/credit</li>
         *   <li>"04" - Balance transfer</li>
         * </ul>
         * 
         * <p>Null if no type filter applied (all transaction types included).</p>
         */
        @JsonProperty("typeCode")
        private String typeCode;

        /**
         * Merchant name filter for merchant-specific reporting.
         * 
         * <p>Merchant name substring for partial name matching using SQL LIKE wildcard
         * search. Service layer constructs query with '%' + merchantName + '%' pattern
         * enabling flexible merchant search. Null if no merchant filter applied.</p>
         */
        @JsonProperty("merchantName")
        private String merchantName;

        /**
         * Minimum transaction amount filter.
         * 
         * <p>BigDecimal with scale=2 representing minimum transaction amount for range filtering.
         * Filters transactions with amount >= minAmount. Null if no minimum amount filter applied.</p>
         */
        @JsonProperty("minAmount")
        private BigDecimal minAmount;

        /**
         * Maximum transaction amount filter.
         * 
         * <p>BigDecimal with scale=2 representing maximum transaction amount for range filtering.
         * Filters transactions with amount <= maxAmount. Null if no maximum amount filter applied.</p>
         */
        @JsonProperty("maxAmount")
        private BigDecimal maxAmount;

        /**
         * Category filter for category-specific reporting.
         * 
         * <p>4-digit category code matching transaction_category table (e.g., "0001" for Retail,
         * "0002" for Grocery, "0003" for Gas/Fuel, "0004" for Dining). Enables spending analysis
         * by category for merchant category code (MCC) based reporting.</p>
         * 
         * <p>Null if no category filter applied (all categories included).</p>
         */
        @JsonProperty("category")
        private String category;
    }

    /**
     * Transaction summary nested class containing aggregate statistics.
     * 
     * <p>This static nested class provides high-level metrics computed from all transactions
     * matching report filter criteria, replacing COBOL WORKING-STORAGE accumulator fields
     * with BigDecimal-based aggregation for exact decimal precision.</p>
     * 
     * <p>All monetary fields use BigDecimal with scale=2 and JSON string serialization to
     * prevent precision loss. Aggregation logic in ReportGenerationService must use BigDecimal
     * arithmetic methods (add, divide, max, min) with explicit RoundingMode.HALF_UP for division
     * operations matching COBOL COMPUTE statement rounding behavior.</p>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TransactionSummary {

        /**
         * Total count of transactions in the report.
         * 
         * <p>Long integer representing number of transaction records matching filter criteria.
         * Computed using SQL COUNT(*) function or Java collection.size() method. Never negative.</p>
         * 
         * <p>Replaces COBOL WS-TRAN-COUNT PIC 9(05) accumulator incremented in loop:</p>
         * <pre>
         * ADD 1 TO WS-TRAN-COUNT
         * </pre>
         */
        @JsonProperty("transactionCount")
        private Long transactionCount;

        /**
         * Total sum of all transaction amounts in the report.
         * 
         * <p>BigDecimal with scale=2 and RoundingMode.HALF_UP representing sum of all transaction
         * amounts. Computed using SQL SUM(amount) function or Java Stream reduce with BigDecimal.add():</p>
         * <pre>
         * BigDecimal totalAmount = transactions.stream()
         *     .map(Transaction::getAmount)
         *     .reduce(BigDecimal.ZERO, BigDecimal::add)
         *     .setScale(2, RoundingMode.HALF_UP);
         * </pre>
         * 
         * <p>Serialized as JSON string "12345.67" preventing JavaScript Number precision loss.
         * Replaces COBOL WS-TRAN-TOTAL PIC S9(11)V99 COMP-3 accumulator:</p>
         * <pre>
         * ADD TRAN-AMT TO WS-TRAN-TOTAL
         * </pre>
         */
        @JsonProperty("totalAmount")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private BigDecimal totalAmount;

        /**
         * Average (mean) transaction amount.
         * 
         * <p>BigDecimal with scale=2 and RoundingMode.HALF_UP representing arithmetic mean of
         * transaction amounts. Computed as totalAmount.divide(totalCount, 2, RoundingMode.HALF_UP)
         * with explicit scale and rounding mode preventing ArithmeticException for non-terminating
         * decimal expansion.</p>
         * 
         * <p>Serialized as JSON string preventing precision loss. Null if totalCount is zero
         * (no transactions in report). Replaces COBOL average calculation:</p>
         * <pre>
         * COMPUTE WS-TRAN-AVG = WS-TRAN-TOTAL / WS-TRAN-COUNT
         * </pre>
         */
        @JsonProperty("averageAmount")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private BigDecimal averageAmount;

        /**
         * Minimum transaction amount in the report.
         * 
         * <p>BigDecimal with scale=2 representing smallest transaction amount found. Computed using
         * SQL MIN(amount) function or Java Stream min with Comparator. Null if totalCount is zero.</p>
         * 
         * <p>Serialized as JSON string maintaining precision. Enables range analysis showing
         * minimum and maximum transaction values for outlier detection and spending pattern analysis.</p>
         */
        @JsonProperty("minAmount")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private BigDecimal minAmount;

        /**
         * Maximum transaction amount in the report.
         * 
         * <p>BigDecimal with scale=2 representing largest transaction amount found. Computed using
         * SQL MAX(amount) function or Java Stream max with Comparator. Null if totalCount is zero.</p>
         * 
         * <p>Serialized as JSON string maintaining precision. Enables range analysis showing
         * minimum and maximum transaction values for outlier detection and spending pattern analysis.</p>
         */
        @JsonProperty("maxAmount")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private BigDecimal maxAmount;
    }

    /**
     * Category breakdown nested class for per-category aggregation.
     * 
     * <p>This static nested class represents aggregated transaction statistics for a single
     * transaction category, enabling spending pattern analysis by merchant category code (MCC).
     * Report contains list of CategoryBreakdown objects, one per category found in dataset,
     * ordered by totalAmount descending to highlight highest spending categories.</p>
     * 
     * <p>Replaces COBOL nested loop category accumulation logic in CBSTM03A.cbl with SQL
     * GROUP BY aggregation or Java Stream groupingBy collector for efficient category-based
     * summarization.</p>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CategoryBreakdown {

        /**
         * Transaction category code identifier.
         * 
         * <p>4-digit category code from transaction_category table (e.g., "0001" for Retail).
         * References TransactionCategory.categoryCode primary key enabling join for category
         * name lookup. Matches TRAN-CAT-CD PIC 9(04) field from CVTRA05Y.cpy TRAN-RECORD.</p>
         */
        @JsonProperty("categoryCode")
        private String categoryCode;

        /**
         * Human-readable category name for display.
         * 
         * <p>Descriptive category name from transaction_category table joined by categoryCode.
         * Examples: "Retail", "Grocery", "Gas/Fuel", "Dining & Entertainment", "Travel",
         * "Healthcare", "Utilities". Enables user-friendly report display without requiring
         * client-side category code lookup.</p>
         */
        @JsonProperty("categoryName")
        private String categoryName;

        /**
         * Count of transactions in this category.
         * 
         * <p>Long integer representing number of transactions with this categoryCode in the
         * report dataset. Computed using SQL COUNT(*) with GROUP BY category_code or Java
         * Stream groupingBy with counting collector. Never negative.</p>
         */
        @JsonProperty("transactionCount")
        private Long transactionCount;

        /**
         * Total sum of transaction amounts for this category.
         * 
         * <p>BigDecimal with scale=2 and RoundingMode.HALF_UP representing sum of all transaction
         * amounts for this category. Computed using SQL SUM(amount) with GROUP BY or Java Stream
         * groupingBy with summingDouble collector converted to BigDecimal.</p>
         * 
         * <p>Serialized as JSON string preventing precision loss. Enables category-wise spending
         * analysis showing which categories account for largest portion of total spending, matching
         * COBOL category subtotal logic in statement generation programs.</p>
         */
        @JsonProperty("totalAmount")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private BigDecimal totalAmount;
    }

    /**
     * Transaction type breakdown nested class for type-level aggregation.
     * 
     * <p>Represents aggregated transaction statistics grouped by transaction type code, enabling
     * analysis of spending patterns by transaction type (purchases, payments, refunds, fees). This
     * breakdown complements CategoryBreakdown by providing a different aggregation dimension focused
     * on transaction type rather than merchant category.</p>
     * 
     * <p>Replaces COBOL nested loop type accumulation logic in statement generation programs with
     * SQL GROUP BY type_code aggregation or Java Stream groupingBy collector for efficient type-based
     * summarization matching mainframe report type subtotals.</p>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TypeBreakdown {

        /**
         * Transaction type code identifier.
         * 
         * <p>2-digit type code from transaction_type table (e.g., "01" for Purchase, "02" for Payment).
         * References TransactionType.typeCode primary key enabling join for type description lookup.
         * Matches TRAN-TYPE-CD field from CVTRA03Y.cpy transaction type reference data.</p>
         */
        @JsonProperty("typeCode")
        private String typeCode;

        /**
         * Human-readable type description for display.
         * 
         * <p>Descriptive type name from transaction_type table joined by typeCode. Examples: "Purchase",
         * "Payment", "Refund", "Fee", "Interest Charge", "Cash Advance". Enables user-friendly report
         * display without requiring client-side type code lookup.</p>
         */
        @JsonProperty("typeName")
        private String typeName;

        /**
         * Count of transactions of this type.
         * 
         * <p>Long integer representing number of transactions with this typeCode in the report dataset.
         * Computed using SQL COUNT(*) with GROUP BY type_code or Java Stream groupingBy with counting
         * collector. Never negative. Enables analysis of transaction type frequency.</p>
         */
        @JsonProperty("transactionCount")
        private Long transactionCount;

        /**
         * Total sum of transaction amounts for this type.
         * 
         * <p>BigDecimal with scale=2 and RoundingMode.HALF_UP representing sum of all transaction
         * amounts for this type. Computed using SQL SUM(amount) with GROUP BY or Java Stream groupingBy
         * with reduce(BigDecimal.ZERO, BigDecimal::add) collector.</p>
         * 
         * <p>Serialized as JSON string preventing precision loss. Enables type-wise spending analysis
         * showing breakdown between purchases, payments, and other transaction types, matching COBOL
         * type subtotal logic in mainframe statement generation.</p>
         */
        @JsonProperty("totalAmount")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private BigDecimal totalAmount;
    }

    /**
     * Individual transaction detail nested class for transaction-level reporting.
     * 
     * <p>Represents a single transaction record with all relevant fields for detailed transaction
     * listing in reports. This class enables generation of detailed transaction reports showing
     * individual transaction records with full details including card number, merchant information,
     * amounts, and categorization.</p>
     * 
     * <p>Replaces COBOL transaction record layout from CVTRA05Y.cpy TRAN-RECORD structure with
     * Java DTO pattern, transforming fixed-length COBOL fields to flexible JSON representation
     * for REST API responses and report generation.</p>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TransactionDetail {

        /**
         * Unique transaction identifier.
         * 
         * <p>Primary key from transaction table, typically 16-character alphanumeric identifier
         * matching TRAN-ID field from CVTRA05Y.cpy. Enables transaction lookup and cross-reference
         * with other transaction records.</p>
         */
        @JsonProperty("transactionId")
        private String transactionId;

        /**
         * Transaction date (without time component).
         * 
         * <p>Date portion of transaction origination timestamp, formatted as ISO 8601 "yyyy-MM-dd"
         * in JSON. Matches TRAN-PROC-DT field from COBOL transaction record. Used for date-based
         * sorting and filtering in detailed transaction reports.</p>
         */
        @JsonProperty("transactionDate")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        private LocalDate transactionDate;

        /**
         * Card number associated with transaction.
         * 
         * <p>16-digit card number from card table via transaction.card relationship. Matches
         * TRAN-CARD-NUM PIC X(16) field from CVTRA05Y.cpy. May be masked or partially displayed
         * based on security requirements (e.g., showing only last 4 digits).</p>
         */
        @JsonProperty("cardNumber")
        private String cardNumber;

        /**
         * Merchant name where transaction occurred.
         * 
         * <p>Merchant business name from transaction record, matching TRAN-MERCHANT-NAME field.
         * Used for merchant-based filtering and reporting. Essential field for transaction
         * identification and categorization.</p>
         */
        @JsonProperty("merchantName")
        private String merchantName;

        /**
         * Merchant city location.
         * 
         * <p>City where merchant is located, matching TRAN-MERCHANT-CITY field from COBOL record.
         * Provides geographic context for transaction analysis and fraud detection patterns.</p>
         */
        @JsonProperty("merchantCity")
        private String merchantCity;

        /**
         * Transaction amount with exact decimal precision.
         * 
         * <p>BigDecimal with scale=2 matching TRAN-AMT PIC S9(09)V99 COMP-3 field from CVTRA05Y.cpy.
         * Positive amounts represent charges/purchases, negative amounts represent credits/refunds.
         * Serialized as JSON string to prevent JavaScript Number precision loss.</p>
         */
        @JsonProperty("amount")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private BigDecimal amount;

        /**
         * Transaction type code.
         * 
         * <p>2-digit type code referencing transaction_type table, matching TRAN-TYPE-CD field.
         * Examples: "01" (Purchase), "02" (Payment), "03" (Refund), "04" (Fee). Used for
         * type-based filtering and breakdown aggregation.</p>
         */
        @JsonProperty("typeCode")
        private String typeCode;

        /**
         * Transaction category code.
         * 
         * <p>4-digit category code referencing transaction_category table, matching TRAN-CAT-CD
         * field from CVTRA04Y.cpy. Examples: "0001" (Retail), "0002" (Grocery), "0003" (Gas/Fuel).
         * Used for category-based filtering and breakdown aggregation.</p>
         */
        @JsonProperty("categoryCode")
        private String categoryCode;

        /**
         * Transaction description or notes.
         * 
         * <p>Free-text description field providing additional transaction details, matching
         * TRAN-DESC field from COBOL record. May contain merchant-provided description, authorization
         * notes, or other contextual information.</p>
         */
        @JsonProperty("description")
        private String description;
    }

    /**
     * Monthly aggregate nested class for time-series transaction analysis.
     * 
     * <p>Represents aggregated transaction statistics grouped by month, enabling trend analysis
     * and time-series visualization of transaction patterns. Each MonthlyAggregate object contains
     * statistics for a single month period within the report date range.</p>
     * 
     * <p>Replaces COBOL monthly report option with SQL date truncation functions (DATE_TRUNC('month'))
     * for efficient time-based grouping and aggregation, enabling flexible time-period analysis
     * without rigid monthly/yearly report distinctions from mainframe batch programs.</p>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MonthlyAggregate {

        /**
         * Month identifier in YYYY-MM format.
         * 
         * <p>String representation of the month in ISO format (e.g., "2024-01", "2024-02").
         * Enables chronological sorting and grouping of monthly statistics for trend analysis.</p>
         */
        @JsonProperty("month")
        private String month;

        /**
         * Count of transactions in this month.
         * 
         * <p>Long integer representing number of transactions that occurred during this month.
         * Computed using SQL COUNT(*) with GROUP BY DATE_TRUNC('month', transaction_date).</p>
         */
        @JsonProperty("transactionCount")
        private long transactionCount;

        /**
         * Total sum of transaction amounts for this month.
         * 
         * <p>BigDecimal with scale=2 representing sum of all transaction amounts during this month.
         * Serialized as JSON string to preserve exact decimal precision.</p>
         */
        @JsonProperty("totalAmount")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private BigDecimal totalAmount;

        /**
         * Average transaction amount for this month.
         * 
         * <p>BigDecimal with scale=2 calculated as totalAmount / transactionCount with
         * RoundingMode.HALF_UP. Serialized as JSON string to preserve precision.</p>
         */
        @JsonProperty("averageAmount")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private BigDecimal averageAmount;
    }
}
