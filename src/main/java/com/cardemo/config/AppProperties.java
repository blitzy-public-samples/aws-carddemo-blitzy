/*
 * ============================================================================
 * AppProperties.java — Custom Application Configuration Properties
 * ============================================================================
 * Migrated from: JCL DD names (DALYTRAN, DALYREJS, STMTFILE) and
 *                COBOL WORKING-STORAGE hardcoded values
 *
 * This class is the single source of truth for custom application
 * configuration in the migrated CardDemo application. Spring Boot binds
 * YAML properties (under the "cardemo" prefix) to this POJO automatically
 * via @ConfigurationProperties.
 *
 * SECURITY NOTE: This class does NOT contain any credentials, passwords,
 * or secrets. Database credentials are externalized via environment variables:
 *   - ${DB_URL}       → spring.datasource.url       (in application.yml)
 *   - ${DB_USERNAME}  → spring.datasource.username   (in application.yml)
 *   - ${DB_PASSWORD}  → spring.datasource.password   (in application.yml)
 *
 * YAML usage pattern:
 *   cardemo:
 *     application-name: CardDemo
 *     application-version: 2.0.0
 *     batch:
 *       input-directory: ${BATCH_INPUT_DIR:./data/input}
 *       output-directory: ${BATCH_OUTPUT_DIR:./data/output}
 *       reject-file-path: ${BATCH_REJECT_FILE:dalyrejs.txt}
 *       statement-output-path: ${STATEMENT_OUTPUT_DIR:./data/statements}
 *       chunk-size: ${BATCH_CHUNK_SIZE:100}
 *       max-retries: ${BATCH_MAX_RETRIES:3}
 *
 * Environment variable overrides work seamlessly. For example:
 *   CARDEMO_BATCH_CHUNK_SIZE=200 overrides the default chunk size.
 *
 * Source references:
 *   - CBTRN02C.cbl: DALYTRAN (daily transaction input), DALYREJS (reject output)
 *   - CBSTM03A.CBL: STMTFILE (plain text statements), HTMLFILE (HTML statements)
 *   - COTTL01Y.cpy: Application title "CardDemo"
 * ============================================================================
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 * ============================================================================
 */
package com.cardemo.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe configuration properties for the CardDemo application.
 *
 * <p>Binds all properties under the {@code cardemo} prefix in
 * {@code application.yml} to strongly-typed Java fields. This replaces
 * hardcoded JCL DD names and COBOL WORKING-STORAGE configuration values
 * with externalized, environment-variable-overridable settings.</p>
 *
 * <p>All batch job configuration classes in {@code com.cardemo.batch.job}
 * inject this bean to read batch settings such as file paths, chunk sizes,
 * and retry limits.</p>
 */
@Component
@Validated
@ConfigurationProperties(prefix = "cardemo")
public class AppProperties {

    // ========================================================================
    // Application-Level Properties
    // ========================================================================

    /**
     * Application identifier. Matches the COBOL application title defined
     * in COTTL01Y.cpy. Used for logging, actuator info endpoint, and
     * structured log metadata.
     */
    private String applicationName = "CardDemo";

    /**
     * Version of the migrated Java application. Distinguishes the Java
     * migration (2.0.0) from the original COBOL application (1.0.0).
     */
    private String applicationVersion = "2.0.0";

    // ========================================================================
    // Batch Configuration (Nested)
    // ========================================================================

    /**
     * Batch processing configuration group. Contains all settings for
     * Spring Batch jobs that replace JCL batch orchestration.
     */
    private Batch batch = new Batch();

    // ========================================================================
    // Application-Level Getters and Setters
    // ========================================================================

    /**
     * Returns the application name identifier.
     *
     * @return the application name, defaults to "CardDemo"
     */
    public String getApplicationName() {
        return applicationName;
    }

    /**
     * Sets the application name identifier.
     *
     * @param applicationName the application name to set
     */
    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    /**
     * Returns the application version string.
     *
     * @return the application version, defaults to "2.0.0"
     */
    public String getApplicationVersion() {
        return applicationVersion;
    }

    /**
     * Sets the application version string.
     *
     * @param applicationVersion the application version to set
     */
    public void setApplicationVersion(String applicationVersion) {
        this.applicationVersion = applicationVersion;
    }

    /**
     * Returns the batch processing configuration.
     *
     * @return the batch configuration object
     */
    public Batch getBatch() {
        return batch;
    }

    /**
     * Sets the batch processing configuration.
     *
     * @param batch the batch configuration object to set
     */
    public void setBatch(Batch batch) {
        this.batch = batch;
    }

    // ========================================================================
    // Nested Batch Configuration Class
    // ========================================================================

    /**
     * Batch processing configuration properties.
     *
     * <p>Groups all Spring Batch related settings that replace JCL DD
     * name allocations and COBOL WORKING-STORAGE batch parameters.</p>
     *
     * <p>JCL-to-property mapping:</p>
     * <ul>
     *   <li>{@code inputDirectory} — replaces JCL DD DALYTRAN file allocation
     *       (CBTRN02C.cbl: {@code SELECT DALYTRAN-FILE ASSIGN TO DALYTRAN})</li>
     *   <li>{@code outputDirectory} — replaces JCL DD DALYREJS and STMTFILE
     *       output allocations</li>
     *   <li>{@code rejectFilePath} — replaces JCL DD DALYREJS
     *       (CBTRN02C.cbl: {@code SELECT DALYREJS-FILE ASSIGN TO DALYREJS})</li>
     *   <li>{@code statementOutputPath} — replaces JCL DD STMTFILE/HTMLFILE
     *       (CBSTM03A.CBL: {@code SELECT STMT-FILE ASSIGN TO STMTFILE})</li>
     *   <li>{@code chunkSize} — chunk size for Spring Batch chunk-oriented
     *       steps, typical for COBOL batch block processing</li>
     *   <li>{@code maxRetries} — maximum retries for failed batch steps</li>
     * </ul>
     */
    public static class Batch {

        /**
         * Path to batch input files directory. The daily transaction feed
         * (DALYTRAN) is read from this directory.
         *
         * <p>Replaces JCL DD DALYTRAN file allocation from CBTRN02C.cbl.</p>
         */
        private String inputDirectory = ".";

        /**
         * Path to batch output files directory. Reject files (DALYREJS)
         * and statement files are written to this directory.
         *
         * <p>Replaces JCL DD DALYREJS and STMTFILE output allocations.</p>
         */
        private String outputDirectory = ".";

        /**
         * Reject file output path relative to the output directory.
         * Contains rejected daily transaction records with validation
         * trailer indicating the reject reason code (100-103).
         *
         * <p>Replaces CBTRN02C.cbl DALYREJS file:
         * {@code SELECT DALYREJS-FILE ASSIGN TO DALYREJS}.</p>
         *
         * <p>Reject codes (from CBTRN02C.cbl validation logic):</p>
         * <ul>
         *   <li>100 — Card cross-reference (XREF) not found</li>
         *   <li>101 — Account not found</li>
         *   <li>102 — Credit limit exceeded</li>
         *   <li>103 — Account expired</li>
         * </ul>
         */
        private String rejectFilePath = "dalyrejs.txt";

        /**
         * Statement output directory path. Statement generation produces
         * both plain text and HTML formatted account statements.
         *
         * <p>Replaces CBSTM03A.CBL output files:
         * {@code SELECT STMT-FILE ASSIGN TO STMTFILE} and
         * {@code SELECT HTML-FILE ASSIGN TO HTMLFILE}.</p>
         */
        private String statementOutputPath = "statements/";

        /**
         * Chunk size for Spring Batch chunk-oriented processing steps.
         * Determines how many records are processed per transaction commit.
         * Must be at least 1.
         *
         * <p>Typical value of 100 matches COBOL batch block processing
         * patterns where records are processed in groups for efficiency.</p>
         */
        @Min(1)
        private int chunkSize = 100;

        /**
         * Maximum number of retries for failed batch step executions.
         * Controls fault tolerance in Spring Batch retry policies.
         */
        @Min(0)
        private int maxRetries = 3;

        // ====================================================================
        // Batch Getters and Setters
        // ====================================================================

        /**
         * Returns the batch input directory path.
         *
         * @return the input directory, defaults to "."
         */
        public String getInputDirectory() {
            return inputDirectory;
        }

        /**
         * Sets the batch input directory path.
         *
         * @param inputDirectory the input directory path to set
         */
        public void setInputDirectory(String inputDirectory) {
            this.inputDirectory = inputDirectory;
        }

        /**
         * Returns the batch output directory path.
         *
         * @return the output directory, defaults to "."
         */
        public String getOutputDirectory() {
            return outputDirectory;
        }

        /**
         * Sets the batch output directory path.
         *
         * @param outputDirectory the output directory path to set
         */
        public void setOutputDirectory(String outputDirectory) {
            this.outputDirectory = outputDirectory;
        }

        /**
         * Returns the reject file output path.
         *
         * @return the reject file path, defaults to "dalyrejs.txt"
         */
        public String getRejectFilePath() {
            return rejectFilePath;
        }

        /**
         * Sets the reject file output path.
         *
         * @param rejectFilePath the reject file path to set
         */
        public void setRejectFilePath(String rejectFilePath) {
            this.rejectFilePath = rejectFilePath;
        }

        /**
         * Returns the statement output directory path.
         *
         * @return the statement output path, defaults to "statements/"
         */
        public String getStatementOutputPath() {
            return statementOutputPath;
        }

        /**
         * Sets the statement output directory path.
         *
         * @param statementOutputPath the statement output path to set
         */
        public void setStatementOutputPath(String statementOutputPath) {
            this.statementOutputPath = statementOutputPath;
        }

        /**
         * Returns the chunk size for batch processing.
         *
         * @return the chunk size, defaults to 100
         */
        public int getChunkSize() {
            return chunkSize;
        }

        /**
         * Sets the chunk size for batch processing.
         *
         * @param chunkSize the chunk size to set, must be at least 1
         */
        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        /**
         * Returns the maximum retries for failed batch steps.
         *
         * @return the maximum retries, defaults to 3
         */
        public int getMaxRetries() {
            return maxRetries;
        }

        /**
         * Sets the maximum retries for failed batch steps.
         *
         * @param maxRetries the maximum retries to set
         */
        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }
    }
}
