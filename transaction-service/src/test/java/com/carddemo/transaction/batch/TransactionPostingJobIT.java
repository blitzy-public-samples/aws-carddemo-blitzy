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
package com.carddemo.transaction.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.domain.DailyTransaction;
import com.carddemo.common.exception.TransactionRejectException;
import com.carddemo.common.testsupport.MigratedSchemaContainer;
import com.carddemo.transaction.repository.DailyTransactionRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end integration test for the ``CBTRN02C`` transaction-posting job.
 *
 * :purpose: Boot the full transaction-service context against a real PostgreSQL
 *     carrying the schema the committed Flyway migrations produce, stage a
 *     deterministic ``DALYTRAN`` feed in the durable ``daily_transactions`` table,
 *     launch ``transactionPostingJob`` and assert the whole AAP §0.6.4 contract at
 *     runtime: the reject codes and their verbatim descriptions, the 430-byte
 *     ``DALYREJS`` reject record, the ``TRANSACTIONS REJECTED`` tally mapped to
 *     return code 4, and the three posting updates a valid record triggers
 *     (``2700-UPDATE-TCATBAL``, ``2800-UPDATE-ACCOUNT-REC``,
 *     ``2900-WRITE-TRANSACTION-FILE``).
 * :output: A Failsafe (``*IT``) test asserting the job's ``BatchStatus`` and
 *     ``ExitStatus``, the exact reject-file content, and the posted database state.
 *
 * Reject code ``101`` (``ACCOUNT RECORD NOT FOUND``) is deliberately NOT staged
 * here: ``card_xref`` carries foreign keys to both ``accounts`` and ``customers``,
 * so a cross-reference pointing at a non-existent account cannot exist in the
 * migrated schema. That branch is covered by
 * :java:class:`TransactionValidationProcessorTest`, which drives the processor
 * directly.
 */
@SpringBootTest
@SpringBatchTest
@ActiveProfiles("test")
class TransactionPostingJobIT {

    /** :purpose: Feed id of the record that clears every validation rule. */
    private static final String VALID_ID = "9900000000000001";

    /** :purpose: Feed id of the record whose card is absent from the cross-reference. */
    private static final String REJECT_100_ID = "9900000000000100";

    /** :purpose: Feed id of the record that breaches the credit limit. */
    private static final String REJECT_102_ID = "9900000000000102";

    /** :purpose: Feed id of the record that arrives after the account expiration date. */
    private static final String REJECT_103_ID = "9900000000000103";

    /** :purpose: A card number guaranteed to be absent from ``card_xref``. */
    private static final String UNKNOWN_CARD = "9999999999999999";

    /** :purpose: Origination date comfortably before every seeded expiration date. */
    private static final String EARLY_ORIG_TS = "2020-01-01-10.00.00.000000";

    /** :purpose: Origination date comfortably after every seeded expiration date. */
    private static final String LATE_ORIG_TS = "2999-01-01-10.00.00.000000";

    /** :purpose: Account whose cross-referenced card carries the valid and 102 records. */
    private static final long POSTING_ACCT_ID = 1L;

    /** :purpose: Account whose cross-referenced card carries the expiration reject. */
    private static final long EXPIRING_ACCT_ID = 3L;

    /** :purpose: ``DALYTRAN-TYPE-CD`` every staged record carries. */
    private static final String POSTED_TYPE_CD = "01";

    /** :purpose: ``DALYTRAN-CAT-CD`` every staged record carries. */
    private static final int POSTED_CAT_CD = 5001;

    /**
     * :purpose: Per-run directory holding the ``DALYREJS`` reject file. Created in a
     *     static initializer rather than with ``@TempDir`` because
     *     ``carddemo.batch.reject-file`` is a CONFIGURATION property read while the
     *     application context is built, so the path must exist before the context
     *     starts. Removed by :java:meth:`removeRejectDirectory`.
     */
    private static final Path REJECT_DIR = createRejectDirectory();

    /**
     * :purpose: The CONFIGURED ``DALYREJS`` name. It is the base of the deliverable, not the
     *     file the run writes: ``POSTTRAN.jcl`` allocated ``DALYREJS`` as
     *     ``DSN=AWS.M2.CARDDEMO.DALYREJS(+1)``, a NEW generation per run, so the writer
     *     qualifies this name with the execution's generation and this exact path must stay
     *     untouched [app/jcl/POSTTRAN.jcl].
     */
    private static final Path REJECT_FILE = REJECT_DIR.resolve("dalyrejs.txt");

    /** :purpose: Glob matching the generation-qualified reject files of the base name. */
    private static final String REJECT_GENERATION_GLOB = "dalyrejs.G*.txt";

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Feed rows present before this test staged its own, restored afterwards. */
    private List<DailyTransaction> originalFeed = new ArrayList<>();

    /** Account rows this test mutates, captured for restoration. */
    private final List<Map<String, Object>> originalAccounts = new ArrayList<>();

    /** Card number cross-referenced to :data:`POSTING_ACCT_ID`. */
    private String postingCardNum;

    /** Card number cross-referenced to :data:`EXPIRING_ACCT_ID`. */
    private String expiringCardNum;

    /** Credit limit of the posting account, used to build the over-limit amount. */
    private BigDecimal postingCreditLimit;

    /**
     * Category balance held for the posted key before this run, empty when the key
     * carried no row; captured so the assertion is relative and the row is restored
     * (or removed) exactly as found.
     */
    private Optional<BigDecimal> originalCategoryBalance = Optional.empty();

    /**
     * :purpose: Bind the datasource to the shared, already-migrated ``postgres:18``
     *     container so both the business tables and the Spring Batch metadata schema
     *     come from the owning modules' committed migrations, and point the reject
     *     writer at a per-run temporary file. The per-run directory is ALSO declared as
     *     ``carddemo.batch.output-dir``: every batch path is resolved inside that root and
     *     a path escaping it is refused (CWE-22), so a run writing outside the configured
     *     root — which is what a bare temporary file would be — is rejected by design.
     * :param registry: the dynamic property registry supplied by the test context.
     */
    @DynamicPropertySource
    static void provisionSchema(DynamicPropertyRegistry registry) {
        MigratedSchemaContainer.registerDataSource(registry);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("carddemo.batch.output-dir", REJECT_DIR::toString);
        registry.add("carddemo.batch.reject-file", REJECT_FILE::toString);
    }

    /**
     * :purpose: Capture the seeded feed and the accounts this run mutates, then stage
     *     a four-record deterministic feed: one valid record, one whose card is not
     *     cross-referenced (100), one that breaches the credit limit (102) and one
     *     that arrives after the account expiration date (103).
     */
    @BeforeEach
    void stageDeterministicFeed() {
        postingCardNum = jdbcTemplate.queryForObject(
                "SELECT xref_card_num FROM card_xref WHERE xref_acct_id = ?",
                String.class, POSTING_ACCT_ID);
        expiringCardNum = jdbcTemplate.queryForObject(
                "SELECT xref_card_num FROM card_xref WHERE xref_acct_id = ?",
                String.class, EXPIRING_ACCT_ID);
        postingCreditLimit = jdbcTemplate.queryForObject(
                "SELECT acct_credit_limit FROM accounts WHERE acct_id = ?",
                BigDecimal.class, POSTING_ACCT_ID);

        for (long acctId : new long[] {POSTING_ACCT_ID, EXPIRING_ACCT_ID}) {
            originalAccounts.add(jdbcTemplate.queryForMap(
                    "SELECT acct_id, acct_curr_bal, acct_curr_cyc_credit, acct_curr_cyc_debit, version "
                            + "FROM accounts WHERE acct_id = ?", acctId));
        }

        originalCategoryBalance = jdbcTemplate.query(
                        "SELECT tran_cat_bal FROM tran_cat_bal WHERE trancat_acct_id = ? "
                                + "AND trancat_type_cd = ? AND trancat_cd = ?",
                        (rs, rowNum) -> rs.getBigDecimal("tran_cat_bal"),
                        POSTING_ACCT_ID, POSTED_TYPE_CD, POSTED_CAT_CD)
                .stream().findFirst();

        originalFeed = dailyTransactionRepository.findAll();
        dailyTransactionRepository.deleteAll();
        dailyTransactionRepository.saveAll(List.of(
                feedRecord(VALID_ID, postingCardNum, new BigDecimal("25.50"), EARLY_ORIG_TS),
                feedRecord(REJECT_100_ID, UNKNOWN_CARD, new BigDecimal("10.00"), EARLY_ORIG_TS),
                feedRecord(REJECT_102_ID, postingCardNum,
                        postingCreditLimit.add(new BigDecimal("1000.00")), EARLY_ORIG_TS),
                feedRecord(REJECT_103_ID, expiringCardNum, new BigDecimal("5.00"), LATE_ORIG_TS)));
        assertThat(REJECT_FILE.getParent()).exists();
    }

    /**
     * :purpose: Remove everything this run posted and restore the captured feed and
     *     account state so the shared container is left exactly as it was found.
     */
    @AfterEach
    void restoreState() {
        jdbcTemplate.update("DELETE FROM transactions WHERE tran_id = ?", VALID_ID);
        if (originalCategoryBalance.isPresent()) {
            jdbcTemplate.update("UPDATE tran_cat_bal SET tran_cat_bal = ? WHERE trancat_acct_id = ? "
                            + "AND trancat_type_cd = ? AND trancat_cd = ?",
                    originalCategoryBalance.get(), POSTING_ACCT_ID, POSTED_TYPE_CD, POSTED_CAT_CD);
        } else {
            jdbcTemplate.update("DELETE FROM tran_cat_bal WHERE trancat_acct_id = ? "
                    + "AND trancat_type_cd = ? AND trancat_cd = ?",
                    POSTING_ACCT_ID, POSTED_TYPE_CD, POSTED_CAT_CD);
        }
        originalCategoryBalance = Optional.empty();
        for (Map<String, Object> account : originalAccounts) {
            jdbcTemplate.update("UPDATE accounts SET acct_curr_bal = ?, acct_curr_cyc_credit = ?, "
                            + "acct_curr_cyc_debit = ?, version = ? WHERE acct_id = ?",
                    account.get("acct_curr_bal"), account.get("acct_curr_cyc_credit"),
                    account.get("acct_curr_cyc_debit"), account.get("version"),
                    account.get("acct_id"));
        }
        originalAccounts.clear();
        dailyTransactionRepository.deleteAll();
        dailyTransactionRepository.saveAll(originalFeed);
    }

    /**
     * Builds one feed record.
     *
     * :param id: the ``DALYTRAN-ID``.
     * :param cardNum: the ``DALYTRAN-CARD-NUM`` driving the cross-reference lookup.
     * :param amount: the ``DALYTRAN-AMT``.
     * :param origTs: the 26-character ``DALYTRAN-ORIG-TS``.
     * :output: the staged :class:`DailyTransaction`.
     */
    private static DailyTransaction feedRecord(String id, String cardNum, BigDecimal amount,
                                               String origTs) {
        DailyTransaction record = new DailyTransaction();
        record.setDalytranId(id);
        record.setDalytranTypeCd("01");
        record.setDalytranCatCd(5001);
        record.setDalytranSource("POS TERM");
        record.setDalytranDesc("Posting job integration record " + id);
        record.setDalytranAmt(amount);
        record.setDalytranMerchantId(123456789L);
        record.setDalytranMerchantName("Mercado Central");
        record.setDalytranMerchantCity("Springfield");
        record.setDalytranMerchantZip("22770");
        record.setDalytranCardNum(cardNum);
        record.setDalytranOrigTs(origTs);
        record.setDalytranProcTs("2024-06-16-01.00.00.000000");
        return record;
    }

    @Test
    @DisplayName("the posting job reads the durable DALYTRAN feed, posts the valid record and rejects 100/102/103")
    void postingJobPostsAndRejectsAccordingToTheLegacyRules() throws Exception {
        assertThat(dailyTransactionRepository.count()).isEqualTo(4L);

        JobExecution execution = jobOperatorTestUtils.startJob(new JobParametersBuilder()
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters());

        // ---- job outcome: COMPLETED with the legacy return-code-4 exit status ----
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED_WITH_REJECTS");
        assertThat(execution.getExitStatus().getExitDescription())
                .isEqualTo("Return code 4: 3 transaction(s) rejected");
        assertThat(execution.getStepExecutions()).hasSize(1);
        assertThat(execution.getStepExecutions().iterator().next().getReadCount()).isEqualTo(4L);

        // ---- 2500-WRITE-REJECT-REC: three 430-byte reject records ----
        List<String> rejectLines = Files.readAllLines(rejectGeneration(), StandardCharsets.UTF_8);
        assertThat(rejectLines).hasSize(3);
        assertThat(rejectLines).allSatisfy(line -> assertThat(line).hasSize(430));

        String reject100 = rejectFor(rejectLines, REJECT_100_ID);
        assertThat(reject100.substring(350, 354)).isEqualTo("0100");
        assertThat(reject100.substring(354).trim())
                .isEqualTo(TransactionRejectException.MSG_INVALID_CARD_NUMBER);

        String reject102 = rejectFor(rejectLines, REJECT_102_ID);
        assertThat(reject102.substring(350, 354)).isEqualTo("0102");
        assertThat(reject102.substring(354).trim())
                .isEqualTo(TransactionRejectException.MSG_OVER_LIMIT);

        String reject103 = rejectFor(rejectLines, REJECT_103_ID);
        assertThat(reject103.substring(350, 354)).isEqualTo("0103");
        assertThat(reject103.substring(354).trim())
                .isEqualTo(TransactionRejectException.MSG_ACCOUNT_EXPIRED);

        // ---- 2900-WRITE-TRANSACTION-FILE: the valid record became a transaction ----
        Map<String, Object> posted = jdbcTemplate.queryForMap(
                "SELECT tran_id, tran_card_num, tran_amt, tran_orig_ts, tran_proc_ts, tran_type_cd, "
                        + "tran_cat_cd FROM transactions WHERE tran_id = ?", VALID_ID);
        assertThat(posted.get("tran_card_num")).isEqualTo(postingCardNum);
        assertThat((BigDecimal) posted.get("tran_amt")).isEqualByComparingTo(new BigDecimal("25.50"));
        assertThat(posted.get("tran_orig_ts")).isEqualTo(EARLY_ORIG_TS);
        // Z-GET-DB2-FORMAT-TIMESTAMP: yyyy-MM-dd-HH.mm.ss.SSSS00 (26 chars, dash/dot form).
        assertThat((String) posted.get("tran_proc_ts"))
                .matches("^\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{6}$");

        // ---- the rejected records were NOT posted ----
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM transactions WHERE tran_id IN (?, ?, ?)", Long.class,
                REJECT_100_ID, REJECT_102_ID, REJECT_103_ID)).isZero();

        // ---- 2800-UPDATE-ACCOUNT-REC: balance and cycle credit advanced by the amount ----
        Map<String, Object> account = jdbcTemplate.queryForMap(
                "SELECT acct_curr_bal, acct_curr_cyc_credit, acct_curr_cyc_debit FROM accounts "
                        + "WHERE acct_id = ?", POSTING_ACCT_ID);
        BigDecimal originalBal = (BigDecimal) originalAccounts.get(0).get("acct_curr_bal");
        BigDecimal originalCycCredit =
                (BigDecimal) originalAccounts.get(0).get("acct_curr_cyc_credit");
        assertThat((BigDecimal) account.get("acct_curr_bal"))
                .isEqualByComparingTo(originalBal.add(new BigDecimal("25.50")));
        assertThat((BigDecimal) account.get("acct_curr_cyc_credit"))
                .isEqualByComparingTo(originalCycCredit.add(new BigDecimal("25.50")));

        // ---- 2700-UPDATE-TCATBAL: the running category balance absorbed the amount ----
        BigDecimal expectedCategoryBalance = originalCategoryBalance
                .orElse(BigDecimal.ZERO).add(new BigDecimal("25.50"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT tran_cat_bal FROM tran_cat_bal WHERE trancat_acct_id = ? "
                        + "AND trancat_type_cd = ? AND trancat_cd = ?",
                BigDecimal.class, POSTING_ACCT_ID, POSTED_TYPE_CD, POSTED_CAT_CD))
                .isEqualByComparingTo(expectedCategoryBalance);
    }

    @Test
    @DisplayName("the feed staged through the repository is durable: it survives a fresh read")
    void feedIsReadFromTheDurableTable() {
        // The reader consumes DailyTransactionRepository.findAll(); the records must come
        // from the daily_transactions table the committed migrations own, not from a
        // process-local buffer, or the job posts nothing when it runs in a deployment.
        Long rowsInTable = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM daily_transactions", Long.class);
        assertThat(rowsInTable).isEqualTo(4L);

        assertThat(dailyTransactionRepository.findAll())
                .extracting(DailyTransaction::getDalytranId)
                .containsExactly(VALID_ID, REJECT_100_ID, REJECT_102_ID, REJECT_103_ID);
        assertThat(dailyTransactionRepository.existsById(VALID_ID)).isTrue();
        assertThat(dailyTransactionRepository.existsById(UNKNOWN_CARD)).isFalse();
        assertThat(dailyTransactionRepository.findById(REJECT_102_ID))
                .get()
                .extracting(DailyTransaction::getDalytranAmt)
                .isEqualTo(postingCreditLimit.add(new BigDecimal("1000.00")));
    }

    /**
     * :purpose: Locate the single generation-qualified ``DALYREJS`` file this run produced.
     * :returns: the path of the run's reject generation.
     * :raises IOException: when the reject directory cannot be listed.
     * :note: The run writes a NEW generation rather than a fixed name, so the assertions
     *     read the generation and additionally prove the configured base name was never
     *     written — a fixed name would have destroyed the previous run's deliverable
     *     [app/jcl/POSTTRAN.jcl].
     */
    private static Path rejectGeneration() throws IOException {
        List<Path> generations = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(REJECT_DIR, REJECT_GENERATION_GLOB)) {
            stream.forEach(generations::add);
        }
        assertThat(generations)
                .as("generation-qualified DALYREJS files in %s", REJECT_DIR)
                .hasSize(1);
        assertThat(REJECT_FILE)
                .as("the configured base name must never be written over")
                .doesNotExist();
        return generations.get(0);
    }

    /**
     * Creates the per-run reject directory.
     *
     * :output: the created directory.
     * :raises UncheckedIOException: when the directory cannot be created.
     */
    private static Path createRejectDirectory() {
        try {
            return Files.createTempDirectory("carddemo-posting-reject-");
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to create the reject-file directory", ex);
        }
    }

    /**
     * :purpose: Delete the per-run reject directory and its contents so the test
     *     leaves no files behind.
     * :raises IOException: when the directory cannot be removed.
     */
    @AfterAll
    static void removeRejectDirectory() throws IOException {
        if (!Files.exists(REJECT_DIR)) {
            return;
        }
        try (var paths = Files.walk(REJECT_DIR)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new UncheckedIOException("Unable to delete " + path, ex);
                }
            });
        }
    }

    /**
     * Finds the reject record written for a feed id.
     *
     * :param rejectLines: every line of the reject file.
     * :param dalytranId: the feed id whose reject record is wanted.
     * :output: the 430-character reject record.
     */
    private static String rejectFor(List<String> rejectLines, String dalytranId) {
        return rejectLines.stream()
                .filter(line -> line.startsWith(dalytranId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no reject record written for feed id " + dalytranId));
    }
}
