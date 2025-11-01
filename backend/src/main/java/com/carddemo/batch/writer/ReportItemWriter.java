/*
 * ReportItemWriter.java
 * 
 * CardDemo Spring Batch Application
 * 
 * Spring Batch ItemWriter implementation for generating formatted batch processing reports
 * including transaction summaries, account reports, card reports, and operator review files.
 * Transforms COBOL report file WRITE operations from batch programs (CBACT01C, CBTRN02C, CBTRN03C)
 * to Spring Batch FlatFileItemWriter with configurable formatting and CSV/plain-text output options.
 * 
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.carddemo.batch.writer;

import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.transform.DelimitedLineAggregator;
import org.springframework.batch.item.file.transform.LineAggregator;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Spring Batch ItemWriter for generating formatted batch processing reports.
 * 
 * Supports multiple report formats:
 * - CSV for data exports (transaction summary, account data)
 * - Plain-text for operator review (80 or 132 column width)
 * - Formatted tables for summary reports
 * - Reject logs with validation error details
 * 
 * Report structure includes:
 * - Header section with title, generation timestamp, report parameters
 * - Detail lines based on report type with appropriate formatting
 * - Summary footer with record counts, aggregate values, processing statistics
 * 
 * Implements StepExecutionListener for lifecycle management:
 * - beforeStep: Initialize file writer and write report header
 * - write: Process chunks of report data
 * - afterStep: Write summary footer, cleanup, and close file
 * 
 * Maps COBOL file-status codes:
 * - 00 = success
 * - 35 = file not found
 * - 39 = resource conflict
 * to IOException with appropriate error handling and batch job failure propagation.
 */
public class ReportItemWriter implements ItemWriter<Object>, StepExecutionListener {

    private static final Logger logger = LoggerFactory.getLogger(ReportItemWriter.class);

    // Report format constants
    private static final String CSV_DELIMITER = ",";
    private static final int LINE_WIDTH_80 = 80;
    private static final int LINE_WIDTH_132 = 132;
    private static final int DEFAULT_PAGE_SIZE = 20;
    
    // Date/time formatters for report headers matching COBOL timestamp patterns
    private static final DateTimeFormatter REPORT_TIMESTAMP_FORMATTER = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_TIMESTAMP_FORMATTER = 
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    // Configuration properties
    private Resource outputResource;
    private ReportType reportType;
    private ReportFormat reportFormat;
    private int lineWidth;
    private boolean compressionEnabled;
    private int pageSize;
    
    // Runtime state
    private BufferedWriter fileWriter;
    private StepExecution stepExecution;
    private LocalDateTime reportStartTime;
    private LocalDateTime reportEndTime;
    private long lineCount;
    private long pageCount;
    private int linesOnCurrentPage;
    
    // Aggregation statistics for summary footer
    private long totalProcessedCount;
    private long totalAcceptedCount;
    private long totalRejectedCount;
    private BigDecimal totalAmount;
    private BigDecimal totalBalance;
    
    /**
     * Enumeration of supported report types matching COBOL batch programs.
     */
    public enum ReportType {
        TRANSACTION_SUMMARY,    // CBTRN03C aggregation report
        ACCOUNT_REPORT,         // CBACT01C account data display
        CARD_REPORT,           // Card data report with expiration and usage
        REJECT_LOG             // CBTRN02C DALYREJS reject records
    }
    
    /**
     * Enumeration of supported report output formats.
     */
    public enum ReportFormat {
        CSV,              // Comma-separated values for data export
        PLAIN_TEXT,       // Fixed-width plain text for operator review
        FORMATTED_TABLE   // Formatted table with borders and alignment
    }
    
    /**
     * Constructor with default configuration.
     */
    public ReportItemWriter() {
        this.reportFormat = ReportFormat.PLAIN_TEXT;
        this.lineWidth = LINE_WIDTH_132;
        this.compressionEnabled = false;
        this.pageSize = DEFAULT_PAGE_SIZE;
        this.totalAmount = BigDecimal.ZERO;
        this.totalBalance = BigDecimal.ZERO;
    }
    
    /**
     * Constructor with configurable output resource and report type.
     * 
     * @param outputResource Spring Resource for output file path with timestamp naming support
     * @param reportType Type of report to generate
     */
    public ReportItemWriter(Resource outputResource, ReportType reportType) {
        this();
        this.outputResource = outputResource;
        this.reportType = reportType;
    }
    
    // Setters for Spring configuration
    
    public void setOutputResource(Resource outputResource) {
        this.outputResource = outputResource;
    }
    
    public void setReportType(ReportType reportType) {
        this.reportType = reportType;
    }
    
    public void setReportFormat(ReportFormat reportFormat) {
        this.reportFormat = reportFormat;
    }
    
    public void setLineWidth(int lineWidth) {
        this.lineWidth = lineWidth;
    }
    
    public void setCompressionEnabled(boolean compressionEnabled) {
        this.compressionEnabled = compressionEnabled;
    }
    
    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }
    
    /**
     * Initialize file writer before step execution.
     * Creates output file with timestamp-based naming for audit trail.
     * Writes report header section.
     * 
     * @param stepExecution Spring Batch step execution context
     */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        this.stepExecution = stepExecution;
        this.reportStartTime = LocalDateTime.now();
        this.lineCount = 0;
        this.pageCount = 1;
        this.linesOnCurrentPage = 0;
        this.totalProcessedCount = 0;
        this.totalAcceptedCount = 0;
        this.totalRejectedCount = 0;
        
        try {
            // Create output file with timestamp-based naming
            File outputFile = createOutputFile();
            logger.info("Initializing report writer for file: {}", outputFile.getAbsolutePath());
            
            // Initialize file writer with optional GZIP compression
            if (compressionEnabled) {
                fileWriter = new BufferedWriter(
                    new java.io.OutputStreamWriter(
                        new GZIPOutputStream(new java.io.FileOutputStream(outputFile)),
                        "UTF-8"
                    )
                );
                logger.info("GZIP compression enabled for report output");
            } else {
                fileWriter = new BufferedWriter(new FileWriter(outputFile));
            }
            
            // Write report header
            writeReportHeader();
            
            logger.info("Report writer initialized successfully for {} report", reportType);
            
        } catch (IOException e) {
            logger.error("Error initializing report writer: {}", e.getMessage(), e);
            throw new ReportWriteException("Failed to initialize report writer - File Status: 35 (File Not Found)", e);
        }
    }
    
    /**
     * Write report header section with title, generation timestamp, and column headers.
     * Format varies based on report type and format.
     * 
     * @throws IOException if file write operation fails
     */
    private void writeReportHeader() throws IOException {
        String reportTitle = getReportTitle();
        String timestamp = reportStartTime.format(REPORT_TIMESTAMP_FORMATTER);
        
        if (reportFormat == ReportFormat.CSV) {
            // CSV header with column names
            writeLine(getCSVHeader());
        } else {
            // Plain text or formatted table header
            writeLine(createHeaderLine());
            writeLine(centerText(reportTitle, lineWidth));
            writeLine(centerText("Generated: " + timestamp, lineWidth));
            writeLine(createSeparatorLine());
            writeLine(getColumnHeader());
            writeLine(createSeparatorLine());
        }
        
        linesOnCurrentPage += 5;
    }
    
    /**
     * Write chunks of report data.
     * Implements ItemWriter interface for Spring Batch chunk processing.
     * 
     * @param chunk Chunk of report line items to write
     * @throws Exception if write operation fails
     */
    @Override
    public void write(Chunk<?> chunk) throws Exception {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        
        logger.debug("Writing chunk of {} items to report", chunk.size());
        
        for (Object item : chunk) {
            try {
                // Check for page break
                if (linesOnCurrentPage >= pageSize && reportFormat != ReportFormat.CSV) {
                    writePageFooter();
                    writePageHeader();
                }
                
                // Format and write detail line based on report type
                String formattedLine = formatDetailLine(item);
                writeLine(formattedLine);
                
                // Update statistics
                updateStatistics(item);
                
            } catch (IOException e) {
                logger.error("Error writing report line: {}", e.getMessage(), e);
                throw new ReportWriteException("Failed to write report line - File Status: 39 (Resource Conflict)", e);
            }
        }
        
        logger.debug("Successfully wrote {} items to report", chunk.size());
    }
    
    /**
     * Cleanup and finalize report after step execution.
     * Writes summary footer with processing statistics.
     * Closes file writer and logs report generation metrics.
     * 
     * @param stepExecution Spring Batch step execution context
     * @return ExitStatus.COMPLETED on success, ExitStatus.FAILED on error
     */
    @Override
    public org.springframework.batch.core.ExitStatus afterStep(StepExecution stepExecution) {
        this.reportEndTime = LocalDateTime.now();
        
        try {
            // Write summary footer
            writeSummaryFooter();
            
            // Flush and close file writer
            if (fileWriter != null) {
                fileWriter.flush();
                fileWriter.close();
                logger.info("Report writer closed successfully");
            }
            
            // Log report generation metrics
            logReportMetrics();
            
            // Return COMPLETED status for successful execution
            return org.springframework.batch.core.ExitStatus.COMPLETED;
            
        } catch (IOException e) {
            logger.error("Error closing report writer: {}", e.getMessage(), e);
            throw new ReportWriteException("Failed to close report writer - File Status: 39 (Resource Conflict)", e);
        }
    }
    
    /**
     * Write summary footer with record counts, aggregate values, and processing statistics.
     * 
     * @throws IOException if file write operation fails
     */
    private void writeSummaryFooter() throws IOException {
        // Get execution statistics from step execution context
        long readCount = stepExecution.getReadCount();
        long writeCount = stepExecution.getWriteCount();
        long skipCount = stepExecution.getSkipCount();
        
        Duration processingDuration = Duration.between(reportStartTime, reportEndTime);
        
        if (reportFormat == ReportFormat.CSV) {
            // CSV summary section
            writeLine("");
            writeLine("Summary Statistics");
            writeLine("Total Processed," + readCount);
            writeLine("Total Written," + writeCount);
            writeLine("Total Skipped," + skipCount);
            writeLine("Processing Duration (seconds)," + processingDuration.getSeconds());
            
            if (reportType == ReportType.TRANSACTION_SUMMARY || reportType == ReportType.ACCOUNT_REPORT) {
                writeLine("Total Amount," + formatAmount(totalAmount));
                writeLine("Average Balance," + formatAmount(calculateAverageBalance()));
            }
            
        } else {
            // Plain text or formatted table summary
            writeLine(createSeparatorLine());
            writeLine("");
            writeLine(centerText("SUMMARY STATISTICS", lineWidth));
            writeLine("");
            writeLine(String.format("%-40s: %,15d", "Total Records Processed", readCount));
            writeLine(String.format("%-40s: %,15d", "Total Records Written", writeCount));
            writeLine(String.format("%-40s: %,15d", "Total Records Rejected", skipCount));
            writeLine("");
            
            if (reportType == ReportType.TRANSACTION_SUMMARY || reportType == ReportType.ACCOUNT_REPORT) {
                writeLine(String.format("%-40s: %,15.2f", "Total Amount", totalAmount));
                writeLine(String.format("%-40s: %,15.2f", "Average Balance", calculateAverageBalance()));
                writeLine("");
            }
            
            writeLine(String.format("%-40s: %s", "Report Start Time", 
                reportStartTime.format(REPORT_TIMESTAMP_FORMATTER)));
            writeLine(String.format("%-40s: %s", "Report End Time", 
                reportEndTime.format(REPORT_TIMESTAMP_FORMATTER)));
            writeLine(String.format("%-40s: %d seconds", "Processing Duration", 
                processingDuration.getSeconds()));
            writeLine("");
            writeLine(createSeparatorLine());
            writeLine(centerText("END OF REPORT", lineWidth));
            writeLine(createSeparatorLine());
        }
    }
    
    /**
     * Format detail line based on report type and format.
     * 
     * @param item Report data item
     * @return Formatted string for output
     */
    private String formatDetailLine(Object item) {
        if (reportFormat == ReportFormat.CSV) {
            return formatCSVLine(item);
        } else {
            return formatPlainTextLine(item);
        }
    }
    
    /**
     * Format CSV line with delimiter-separated values.
     * 
     * @param item Report data item
     * @return CSV formatted string
     */
    private String formatCSVLine(Object item) {
        StringBuilder sb = new StringBuilder();
        
        switch (reportType) {
            case TRANSACTION_SUMMARY:
                sb.append(getProperty(item, "accountId")).append(CSV_DELIMITER);
                sb.append(getProperty(item, "transactionTypeCode")).append(CSV_DELIMITER);
                sb.append(getProperty(item, "categoryCode")).append(CSV_DELIMITER);
                sb.append(getProperty(item, "transactionAmount")).append(CSV_DELIMITER);
                sb.append(getProperty(item, "transactionDate"));
                break;
                
            case ACCOUNT_REPORT:
                sb.append(getProperty(item, "accountId")).append(CSV_DELIMITER);
                sb.append(getProperty(item, "accountStatus")).append(CSV_DELIMITER);
                sb.append(getProperty(item, "currentBalance")).append(CSV_DELIMITER);
                sb.append(getProperty(item, "creditLimit")).append(CSV_DELIMITER);
                sb.append(getProperty(item, "openDate"));
                break;
                
            case CARD_REPORT:
                sb.append(getProperty(item, "cardNumber")).append(CSV_DELIMITER);
                sb.append(getProperty(item, "accountId")).append(CSV_DELIMITER);
                sb.append(getProperty(item, "expirationDate")).append(CSV_DELIMITER);
                sb.append(getProperty(item, "cardStatus")).append(CSV_DELIMITER);
                sb.append(getProperty(item, "usageCount"));
                break;
                
            case REJECT_LOG:
                sb.append(getProperty(item, "transactionId")).append(CSV_DELIMITER);
                sb.append(getProperty(item, "rejectionCode")).append(CSV_DELIMITER);
                sb.append(getProperty(item, "rejectionDescription")).append(CSV_DELIMITER);
                sb.append(getProperty(item, "transactionData"));
                break;
        }
        
        return sb.toString();
    }
    
    /**
     * Format plain text line with fixed-width columns.
     * 
     * @param item Report data item
     * @return Plain text formatted string
     */
    private String formatPlainTextLine(Object item) {
        StringBuilder sb = new StringBuilder();
        
        switch (reportType) {
            case TRANSACTION_SUMMARY:
                sb.append(String.format("%-11s ", getProperty(item, "accountId")));
                sb.append(String.format("%-2s ", getProperty(item, "transactionTypeCode")));
                sb.append(String.format("%-4s ", getProperty(item, "categoryCode")));
                sb.append(String.format("%15.2f ", parseAmount(getProperty(item, "transactionAmount"))));
                sb.append(String.format("%-10s", getProperty(item, "transactionDate")));
                break;
                
            case ACCOUNT_REPORT:
                sb.append(String.format("%-11s ", getProperty(item, "accountId")));
                sb.append(String.format("%-1s ", getProperty(item, "accountStatus")));
                sb.append(String.format("%15.2f ", parseAmount(getProperty(item, "currentBalance"))));
                sb.append(String.format("%15.2f ", parseAmount(getProperty(item, "creditLimit"))));
                sb.append(String.format("%-10s", getProperty(item, "openDate")));
                break;
                
            case CARD_REPORT:
                sb.append(String.format("%-16s ", getProperty(item, "cardNumber")));
                sb.append(String.format("%-11s ", getProperty(item, "accountId")));
                sb.append(String.format("%-10s ", getProperty(item, "expirationDate")));
                sb.append(String.format("%-1s ", getProperty(item, "cardStatus")));
                sb.append(String.format("%10s", getProperty(item, "usageCount")));
                break;
                
            case REJECT_LOG:
                // Reject log format: transaction ID, error code, description
                sb.append(String.format("%-16s ", getProperty(item, "transactionId")));
                sb.append(String.format("%-4s ", getProperty(item, "rejectionCode")));
                sb.append(String.format("%-50s", getProperty(item, "rejectionDescription")));
                break;
        }
        
        return sb.toString();
    }
    
    /**
     * Update aggregation statistics from report item.
     * 
     * @param item Report data item
     */
    private void updateStatistics(Object item) {
        totalProcessedCount++;
        
        // Update type-specific statistics
        if (reportType == ReportType.TRANSACTION_SUMMARY) {
            String amountStr = getProperty(item, "transactionAmount");
            if (amountStr != null && !amountStr.isEmpty()) {
                totalAmount = totalAmount.add(parseAmount(amountStr));
            }
        } else if (reportType == ReportType.ACCOUNT_REPORT) {
            String balanceStr = getProperty(item, "currentBalance");
            if (balanceStr != null && !balanceStr.isEmpty()) {
                totalBalance = totalBalance.add(parseAmount(balanceStr));
            }
        }
    }
    
    /**
     * Get CSV header line with column names.
     * 
     * @return CSV header string
     */
    private String getCSVHeader() {
        switch (reportType) {
            case TRANSACTION_SUMMARY:
                return "Account ID,Type Code,Category Code,Amount,Transaction Date";
            case ACCOUNT_REPORT:
                return "Account ID,Status,Current Balance,Credit Limit,Open Date";
            case CARD_REPORT:
                return "Card Number,Account ID,Expiration Date,Status,Usage Count";
            case REJECT_LOG:
                return "Transaction ID,Rejection Code,Rejection Description,Transaction Data";
            default:
                return "";
        }
    }
    
    /**
     * Get column header for plain text format.
     * 
     * @return Column header string
     */
    private String getColumnHeader() {
        switch (reportType) {
            case TRANSACTION_SUMMARY:
                return String.format("%-11s %-2s %-4s %15s %-10s", 
                    "Account ID", "Ty", "Cat", "Amount", "Date");
            case ACCOUNT_REPORT:
                return String.format("%-11s %-1s %15s %15s %-10s",
                    "Account ID", "S", "Current Balance", "Credit Limit", "Open Date");
            case CARD_REPORT:
                return String.format("%-16s %-11s %-10s %-1s %10s",
                    "Card Number", "Account ID", "Exp Date", "S", "Usage");
            case REJECT_LOG:
                return String.format("%-16s %-4s %-50s",
                    "Transaction ID", "Code", "Rejection Description");
            default:
                return "";
        }
    }
    
    /**
     * Get report title based on report type.
     * 
     * @return Report title string
     */
    private String getReportTitle() {
        switch (reportType) {
            case TRANSACTION_SUMMARY:
                return "TRANSACTION SUMMARY REPORT";
            case ACCOUNT_REPORT:
                return "ACCOUNT LISTING REPORT";
            case CARD_REPORT:
                return "CARD STATUS REPORT";
            case REJECT_LOG:
                return "TRANSACTION REJECT LOG";
            default:
                return "BATCH PROCESSING REPORT";
        }
    }
    
    /**
     * Create output file with timestamp-based naming.
     * 
     * @return File object for output
     * @throws IOException if file creation fails
     */
    private File createOutputFile() throws IOException {
        String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP_FORMATTER);
        String baseName = getBaseFileName();
        String extension = compressionEnabled ? ".txt.gz" : ".txt";
        
        File outputFile;
        if (outputResource != null) {
            // Use provided resource path
            outputFile = outputResource.getFile();
            
            // Ensure parent directory exists
            File parentDir = outputFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
        } else {
            // Create file with timestamp in filename
            String filename = baseName + "_" + timestamp + extension;
            outputFile = new File(filename);
            
            // Ensure parent directory exists
            File parentDir = outputFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
        }
        
        return outputFile;
    }
    
    /**
     * Get base filename for report type.
     * 
     * @return Base filename string
     */
    private String getBaseFileName() {
        switch (reportType) {
            case TRANSACTION_SUMMARY:
                return "transaction_summary";
            case ACCOUNT_REPORT:
                return "account_report";
            case CARD_REPORT:
                return "card_report";
            case REJECT_LOG:
                return "reject_log";
            default:
                return "batch_report";
        }
    }
    
    /**
     * Write page header for paginated reports.
     * 
     * @throws IOException if write operation fails
     */
    private void writePageHeader() throws IOException {
        pageCount++;
        linesOnCurrentPage = 0;
        
        writeLine("");
        writeLine(createHeaderLine());
        writeLine(rightAlignText("Page " + pageCount, lineWidth));
        writeLine(createSeparatorLine());
        writeLine(getColumnHeader());
        writeLine(createSeparatorLine());
        
        linesOnCurrentPage += 6;
    }
    
    /**
     * Write page footer for paginated reports.
     * 
     * @throws IOException if write operation fails
     */
    private void writePageFooter() throws IOException {
        writeLine(createSeparatorLine());
        writeLine("");
    }
    
    /**
     * Write a line to the output file.
     * 
     * @param line Line to write
     * @throws IOException if write operation fails
     */
    private void writeLine(String line) throws IOException {
        fileWriter.write(line);
        fileWriter.newLine();
        lineCount++;
        linesOnCurrentPage++;
    }
    
    /**
     * Create header line (top border).
     * 
     * @return Header line string
     */
    private String createHeaderLine() {
        return repeatChar('=', lineWidth);
    }
    
    /**
     * Create separator line.
     * 
     * @return Separator line string
     */
    private String createSeparatorLine() {
        return repeatChar('-', lineWidth);
    }
    
    /**
     * Center text within specified width.
     * 
     * @param text Text to center
     * @param width Total width
     * @return Centered text string
     */
    private String centerText(String text, int width) {
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        int padding = (width - text.length()) / 2;
        return repeatChar(' ', padding) + text + repeatChar(' ', width - padding - text.length());
    }
    
    /**
     * Right-align text within specified width.
     * 
     * @param text Text to right-align
     * @param width Total width
     * @return Right-aligned text string
     */
    private String rightAlignText(String text, int width) {
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        return repeatChar(' ', width - text.length()) + text;
    }
    
    /**
     * Repeat character n times.
     * 
     * @param ch Character to repeat
     * @param count Number of repetitions
     * @return String with repeated character
     */
    private String repeatChar(char ch, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(ch);
        }
        return sb.toString();
    }
    
    /**
     * Get property value from object using reflection or map access.
     * 
     * @param item Object to extract property from
     * @param propertyName Property name
     * @return Property value as string
     */
    private String getProperty(Object item, String propertyName) {
        if (item == null) {
            return "";
        }
        
        try {
            // Try map access first
            if (item instanceof java.util.Map) {
                Object value = ((java.util.Map<?, ?>) item).get(propertyName);
                return value != null ? value.toString() : "";
            }
            
            // Try bean property access via reflection
            String getterName = "get" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
            java.lang.reflect.Method getter = item.getClass().getMethod(getterName);
            Object value = getter.invoke(item);
            return value != null ? value.toString() : "";
            
        } catch (Exception e) {
            logger.warn("Could not extract property '{}' from item: {}", propertyName, e.getMessage());
            return "";
        }
    }
    
    /**
     * Parse amount string to BigDecimal with COBOL COMP-3 precision preservation.
     * Scale set to 2 with HALF_UP rounding per Section 0.9 requirements.
     * 
     * @param amountStr Amount as string
     * @return BigDecimal with scale 2
     */
    private BigDecimal parseAmount(String amountStr) {
        if (amountStr == null || amountStr.trim().isEmpty()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        
        try {
            BigDecimal amount = new BigDecimal(amountStr.trim());
            return amount.setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            logger.warn("Invalid amount format: {}", amountStr);
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
    }
    
    /**
     * Format amount for display with 2 decimal places.
     * 
     * @param amount BigDecimal amount
     * @return Formatted amount string
     */
    private String formatAmount(BigDecimal amount) {
        if (amount == null) {
            return "0.00";
        }
        return String.format("%.2f", amount);
    }
    
    /**
     * Calculate average balance from total balance and count.
     * 
     * @return Average balance as BigDecimal
     */
    private BigDecimal calculateAverageBalance() {
        if (totalProcessedCount == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return totalBalance.divide(
            new BigDecimal(totalProcessedCount), 
            2, 
            RoundingMode.HALF_UP
        );
    }
    
    /**
     * Log report generation metrics for monitoring.
     */
    private void logReportMetrics() {
        Duration duration = Duration.between(reportStartTime, reportEndTime);
        
        logger.info("Report Generation Metrics:");
        logger.info("  Report Type: {}", reportType);
        logger.info("  Report Format: {}", reportFormat);
        logger.info("  Total Lines Written: {}", lineCount);
        logger.info("  Total Pages: {}", pageCount);
        logger.info("  Records Processed: {}", totalProcessedCount);
        logger.info("  Processing Duration: {} seconds", duration.getSeconds());
        
        if (outputResource != null) {
            try {
                File file = outputResource.getFile();
                long fileSize = file.length();
                logger.info("  Output File: {}", file.getAbsolutePath());
                logger.info("  File Size: {} bytes", fileSize);
            } catch (IOException e) {
                logger.warn("Could not determine output file size: {}", e.getMessage());
            }
        }
    }
    
    /**
     * Custom exception for report write errors.
     * Maps COBOL file-status codes to Java exceptions.
     */
    public static class ReportWriteException extends RuntimeException {
        public ReportWriteException(String message) {
            super(message);
        }
        
        public ReportWriteException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
