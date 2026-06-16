package com.carddemo.batch;

import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.CardDemoConstants;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.mockito.ArgumentCaptor;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Keystone parity test proving the Spring Batch {@code transactionPostingJob} reproduces the legacy
 * COBOL daily-transaction-posting program {@code CBTRN02C} EXACTLY.
 *
 * <p><b>Behavioral contract reproduced (authority: AAP §0.6.1, §0.6.2, §0.6.5, §0.7.1):</b></p>
 * <ul>
 *   <li>The full reject-code superset <b>100 / 101 / 102 / 103 / 109</b> — including the codes the
 *       prompt's simplified summary omits (101 and 109).</li>
 *   <li>The reject gauntlet order: card lookup (100) → account lookup (101) → cycle-based overlimit
 *       (102) → account-expiry-vs-origination-date (103). Steps 102 and 103 are <b>sequential
 *       {@code if}s</b> (not {@code else-if}); when both fail, the final reason is <b>103</b>
 *       (overwrites 102).</li>
 *   <li>The <b>430-byte DALYREJS</b> reject record: a 350-byte verbatim feed image, a 4-byte
 *       zero-padded reason code, and a 76-byte description trailer (350 + 4 + 76 = 430).</li>
 *   <li><b>Verbatim carry-through of DALYTRAN-ID</b> — the batch poster does NOT regenerate ids
 *       (unlike the online add path); the incoming id is persisted as the transaction id.</li>
 *   <li><b>RETURN-CODE=4 → {@link ExitStatus} {@code WARNING}</b> when any record is rejected; the
 *       job itself still completes ({@link BatchStatus#COMPLETED}).</li>
 * </ul>
 *
 * <p><b>HYBRID strategy.</b> Two reject codes are UNREACHABLE through a full-job integration run
 * because the H2 schema (V1) enforces the foreign key {@code card_xref.xref_acct_id → accounts(acct_id)}:
 * <ul>
 *   <li><b>101</b> (ACCOUNT RECORD NOT FOUND) — every insertable xref necessarily references an
 *       existing account, so the processor's "xref found but account missing" branch cannot be
 *       reached with FK-backed data.</li>
 *   <li><b>109</b> (account update / REWRITE failure) — only occurs when the account disappears or
 *       its save fails between read and write; not reproducible with FK-backed seed data.</li>
 * </ul>
 * Therefore this single class uses BOTH integration tests (real {@code transactionPostingJob}
 * launches over crafted and production fixtures for 100/102/103, valid posting, WARNING exit, the
 * 430-byte reject file, and verbatim id) AND pure-Mockito component tests (instantiating the
 * production {@link TransactionPostingProcessor} and {@link TransactionPostingWriter} directly with
 * mocked repositories) for 101, 109, and unit-level reinforcement of the gauntlet and the exact
 * {@code buildRejectLine} format.</p>
 *
 * <p><b>Isolation.</b> Integration methods COMMIT posted transactions plus account / tcatbal
 * mutations, so each is annotated {@link DirtiesContext} {@code AFTER_METHOD}. The Mockito component
 * methods touch no database and carry no {@code @DirtiesContext}. No method is {@code @Transactional}.</p>
 */
@SpringBatchTest
@SpringBootTest
@ActiveProfiles("test")
class TransactionPostingJobTest {

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    @Qualifier("transactionPostingJob")
    private Job transactionPostingJob;

    @Autowired
    private CardXrefRepository cardXrefRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TransactionCategoryBalanceRepository tcbRepository;

    @TempDir
    Path tempDir;

    /**
     * Multiple {@code Job} beans exist in the application context, so {@link JobLauncherTestUtils}
     * cannot auto-wire a single job; bind the job-under-test explicitly and clear any prior batch
     * metadata before every method.
     */
    @BeforeEach
    void setUp() {
        jobLauncherTestUtils.setJob(transactionPostingJob);
        jobRepositoryTestUtils.removeJobExecutions();
    }

    // ------------------------------------------------------------------------------------------------
    // Static helpers — DALYTRAN record crafting (byte-exact 350-char layout) and S9(09)V99 overpunch.
    // ------------------------------------------------------------------------------------------------

    /** Overpunch table for non-negative values: index = trailing digit 0..9 → {@code {ABCDEFGHI}. */
    private static final String POS_OVERPUNCH = "{ABCDEFGHI";

    /** Overpunch table for negative values: index = trailing digit 0..9 → {@code }JKLMNOPQR}. */
    private static final String NEG_OVERPUNCH = "}JKLMNOPQR";

    /**
     * Encodes a {@link BigDecimal} as the 11-character {@code S9(09)V99} overpunched field that the
     * DALYTRAN feed places at {@code amt[132:143]}. The two implied decimals are folded into an
     * 11-digit zero-padded cents string whose final digit is overpunched with the sign.
     *
     * <p>Anchors (must hold): {@code encodeAmount(504.77) == "0000005047G"} and
     * {@code encodeAmount(-919.00) == "0000009190}"}.</p>
     */
    private static String encodeAmount(BigDecimal amt) {
        boolean neg = amt.signum() < 0;
        long cents = amt.abs().movePointRight(2).longValueExact(); // 1.00 -> 100 ; 504.77 -> 50477
        String digits = String.format("%011d", cents);            // exactly 11 digits
        String head = digits.substring(0, 10);                     // first 10 digits
        int last = digits.charAt(10) - '0';                        // 11th (last) digit, 0..9
        char oc = (neg ? NEG_OVERPUNCH : POS_OVERPUNCH).charAt(last);
        return head + oc;                                          // 11 characters
    }

    /** Overlays {@code val} into {@code buf} starting at {@code start} without writing past the buffer. */
    private static void put(char[] buf, int start, String val) {
        for (int i = 0; i < val.length() && start + i < buf.length; i++) {
            buf[start + i] = val.charAt(i);
        }
    }

    /**
     * Builds a 350-character DALYTRAN line with the parity-critical fields populated and benign
     * defaults elsewhere. Field offsets mirror {@link DailyTransactionRecord}'s 0-based layout:
     * id[0:16], typeCd[16:18], catCd[18:22], source[22:32], desc[32:132], amt[132:143],
     * merchId[143:152], merchName[152:202], merchCity[202:252], merchZip[252:262], cardNum[262:278],
     * origTs[278:304], procTs[304:330], filler[330:350].
     */
    private static String buildRecord(String id, String typeCd, int catCd, BigDecimal amt,
                                      String cardNum, String origTs26) {
        char[] buf = new char[350];
        Arrays.fill(buf, ' ');
        put(buf, 0, id);                            // [0:16]
        put(buf, 16, typeCd);                       // [16:18]
        put(buf, 18, String.format("%04d", catCd)); // [18:22]
        put(buf, 22, "POS");                        // source [22:32]
        put(buf, 32, "TEST TRANSACTION");           // desc [32:132]
        put(buf, 132, encodeAmount(amt));           // amt [132:143] (11 chars)
        put(buf, 143, "000000000");                 // merchant id [143:152] (9 chars)
        put(buf, 152, "TEST MERCHANT");             // merch name [152:202]
        put(buf, 202, "TEST CITY");                 // merch city [202:252]
        put(buf, 252, "00000");                     // merch zip [252:262]
        put(buf, 262, cardNum);                     // cardNum [262:278] (16 chars)
        put(buf, 278, origTs26);                    // orig ts [278:304] (26 chars)
        put(buf, 304, origTs26);                    // proc ts [304:330] (26 chars)
        return new String(buf);                     // exactly 350 characters
    }

    /** Returns a 26-character {@code yyyy-MM-dd HH:mm:ss.SSSSSS} timestamp at midnight for the date. */
    private static String tsForDate(LocalDate d) {
        return d + " 00:00:00.000000"; // 10 + 16 = 26 characters
    }

    // ------------------------------------------------------------------------------------------------
    // Static helpers — minimal entity builders for the pure-Mockito component tests.
    // ------------------------------------------------------------------------------------------------

    /** Builds a detached {@link CardXref} mapping a card number to an account id (cust id is a stub). */
    private static CardXref newXref(String card, Long acctId) {
        CardXref xref = new CardXref();
        xref.setXrefCardNum(card);
        xref.setXrefCustId(1L); // column is non-null; value is irrelevant to the posting gauntlet
        xref.setXrefAcctId(acctId);
        return xref;
    }

    /**
     * Builds a detached {@link Account} with the fields the posting gauntlet reads: credit limit,
     * both cycle totals (set to {@code cyc}), expiration date, and a zero current balance.
     */
    private static Account newAccount(Long id, String creditLimit, String cyc, LocalDate exp) {
        Account a = new Account();
        a.setAcctId(id);
        a.setCreditLimit(new BigDecimal(creditLimit));
        a.setCurrCycCredit(new BigDecimal(cyc));
        a.setCurrCycDebit(new BigDecimal(cyc));
        a.setExpirationDate(exp);
        a.setCurrBal(new BigDecimal("0.00"));
        return a;
    }

    // ================================================================================================
    // INTEGRATION TESTS — launch the real transactionPostingJob (each @DirtiesContext AFTER_METHOD).
    // ================================================================================================

    /**
     * THE keystone parity test. A single five-record fixture exercises, in one chunk: a valid
     * posting, reject 100 (unknown card), reject 102 (overlimit), reject 103 (transaction received
     * after account expiration), and a combined overlimit+expired record that must resolve to 103
     * (proving the 102→103 sequential-{@code if} overwrite). It then asserts the job completes with a
     * {@code WARNING} exit, the valid record posts under its VERBATIM id with the cross-referenced
     * account balance incremented, and the DALYREJS file holds exactly four 430-byte records whose
     * reason codes appear in input order.
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void postingJob_postsValidRecord_andRejects_100_102_103_withWarning_and430ByteRejects() throws Exception {
        // Resolve a REAL seeded card→account pair (FK-backed) so the valid/102/103 records reference
        // an existing account; the bogus-card record references a card absent from the xref.
        CardXref xref = cardXrefRepository.findAll().get(0);
        Long acctId = xref.getXrefAcctId();
        String realCard = xref.getXrefCardNum();
        Account acct = accountRepository.findById(acctId).orElseThrow();

        BigDecimal balBefore = acct.getCurrBal();
        LocalDate expiry = acct.getExpirationDate();

        // Capture the (acct, type '01', cat 1) category balance BEFORE the run so the post-job
        // assertion measures the delta and stays robust to any prior committed state (treat an absent
        // row as 0.00 — the writer upserts it on first post).
        TransactionCategoryBalance.TransactionCategoryBalanceId tcbId =
                new TransactionCategoryBalance.TransactionCategoryBalanceId(acctId, "01", 1);
        BigDecimal tcbBalBefore = tcbRepository.findById(tcbId)
                .map(TransactionCategoryBalance::getTranCatBal)
                .orElse(new BigDecimal("0.00"));

        // cycCredit − cycDebit + overlimitAmt == creditLimit + 1.00 > creditLimit  →  reject 102.
        BigDecimal overlimitAmt = acct.getCreditLimit()
                .subtract(acct.getCurrCycCredit())
                .add(acct.getCurrCycDebit())
                .add(new BigDecimal("1.00"));

        // Record order matters: record 1 posts (no reject line); records 2–5 each emit one reject.
        String validRec = buildRecord("9000000000000001", "01", 1, new BigDecimal("1.00"),
                realCard, tsForDate(LocalDate.of(2020, 1, 1)));
        String badCardRec = buildRecord("9000000000000002", "01", 1, new BigDecimal("1.00"),
                "9999999999999999", tsForDate(LocalDate.of(2020, 1, 1)));
        String overlimitRec = buildRecord("9000000000000003", "01", 1, overlimitAmt,
                realCard, tsForDate(LocalDate.of(2020, 1, 1)));
        String expiredRec = buildRecord("9000000000000004", "01", 1, new BigDecimal("1.00"),
                realCard, tsForDate(expiry.plusDays(1)));
        String combinedRec = buildRecord("9000000000000005", "01", 1, overlimitAmt,
                realCard, tsForDate(expiry.plusDays(1)));

        Path fixture = tempDir.resolve("dailytran-crafted.txt");
        Files.write(fixture, List.of(validRec, badCardRec, overlimitRec, expiredRec, combinedRec));
        Path rejectFile = tempDir.resolve("rejects.txt");

        JobParameters params = new JobParametersBuilder(jobLauncherTestUtils.getUniqueJobParameters())
                .addString("input.file.path", fixture.toAbsolutePath().toString())
                .addString("outputRejectPath", rejectFile.toAbsolutePath().toString())
                .toJobParameters();

        JobExecution execution = jobLauncherTestUtils.launchJob(params);

        // Rejected records do NOT fail the job — it completes, but with a WARNING exit (RETURN-CODE=4).
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        StepExecution step = execution.getStepExecutions().iterator().next();
        assertThat(step.getStepName()).isEqualTo("transactionPostingStep");
        assertThat(step.getReadCount()).isEqualTo(5);
        assertThat(step.getExitStatus().getExitCode()).isEqualTo("WARNING");

        // Valid posting carries the DALYTRAN-ID through VERBATIM (no TranIdGenerator on the batch path).
        Transaction posted = transactionRepository.findById("9000000000000001").orElseThrow();
        assertThat(posted.getTranId()).isEqualTo("9000000000000001");
        assertThat(posted.getAcctId()).isEqualTo(acctId);
        assertThat(posted.getAmt()).isEqualByComparingTo("1.00");

        // The single valid post adds its amount to the cross-referenced account's current balance.
        Account after = accountRepository.findById(acctId).orElseThrow();
        assertThat(after.getCurrBal()).isEqualByComparingTo(balBefore.add(new BigDecimal("1.00")));

        // The transaction-category balance for (acct, type '01', cat 1) is incremented by exactly the
        // one valid post (CBTRN02C TCATBAL update, §0.6.1); rejected records never reach postValid.
        assertThat(tcbRepository.findById(tcbId).orElseThrow().getTranCatBal())
                .isEqualByComparingTo(tcbBalBefore.add(new BigDecimal("1.00")));

        // DALYREJS: exactly four 430-byte reject records (filter any trailing blank line defensively).
        List<String> rejectLines = Files.readAllLines(rejectFile).stream()
                .filter(line -> !line.isEmpty())
                .toList();
        assertThat(rejectLines).hasSize(4);
        for (String line : rejectLines) {
            assertThat(line).hasSize(CardDemoConstants.DALYREJS_RECORD_WIDTH); // 350 + 4 + 76 == 430
        }

        // Reason codes (substring [350:354]) in the crafted input order.
        assertThat(rejectLines.get(0).substring(350, 354)).isEqualTo("0100");
        assertThat(rejectLines.get(1).substring(350, 354)).isEqualTo("0102");
        assertThat(rejectLines.get(2).substring(350, 354)).isEqualTo("0103");
        assertThat(rejectLines.get(3).substring(350, 354)).isEqualTo("0103"); // combined → 103 overwrites 102

        // 100 description trailer (substring [354:430]).
        assertThat(rejectLines.get(0).substring(354, 430).trim())
                .isEqualTo(CardDemoConstants.REJECT_DESC_INVALID_CARD);

        // The 350-byte feed image is preserved verbatim, including the bogus card number it carried.
        assertThat(rejectLines.get(0).substring(262, 278)).isEqualTo("9999999999999999");
        assertThat(rejectLines.get(0).substring(0, 350)).isEqualTo(badCardRec);
    }

    /**
     * Smoke test over the byte-exact production fixture {@code fixtures/dailytran.txt}. Asserts the
     * job completes, reads <strong>every</strong> record the fixture contains &mdash; with the expected
     * read count <em>derived dynamically from the fixture file itself</em> rather than hardcoded, so the
     * test is robust to feed churn (the daily-transaction fixture has varied between 311 and 300 records;
     * this test must assume neither count) &mdash; and, regardless of how many records the real feed
     * matches against the seed, emits only structurally valid 430-byte reject records (AAP §0.6.2).
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void postingJob_overRealDailytranFixture_completes_readsAllRecords_rejectsAre430Bytes() throws Exception {
        String path = new ClassPathResource("fixtures/dailytran.txt").getFile().getAbsolutePath();

        // Count-agnostic expected read count: derive it from the fixture itself (count the non-empty
        // 350-byte records, mirroring how the FlatFileItemReader reads each line as one record) rather
        // than asserting a magic number. This satisfies the checkpoint's count-agnostic requirement and
        // will not break if the committed fixture's record count changes (e.g. 300 <-> 311).
        long expectedReadCount = Files.readAllLines(Path.of(path)).stream()
                .filter(line -> !line.isEmpty())
                .count();
        assertThat(expectedReadCount).as("fixture must contain at least one record").isGreaterThan(0L);

        Path rejectFile = tempDir.resolve("rejects-real.txt");

        JobParameters params = new JobParametersBuilder(jobLauncherTestUtils.getUniqueJobParameters())
                .addString("input.file.path", path)
                .addString("outputRejectPath", rejectFile.toAbsolutePath().toString())
                .toJobParameters();

        JobExecution execution = jobLauncherTestUtils.launchJob(params);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        StepExecution step = execution.getStepExecutions().iterator().next();
        // The job must read EVERY record present in the fixture (no read-time skipping/filtering),
        // whatever that count is — proven against the dynamically-derived count, never a hardcoded 300/311.
        assertThat(step.getReadCount()).isEqualTo(expectedReadCount);

        // Structural-only assertion: do not hardcode a reject count (the feed's match rate against the
        // seed is not asserted); only require that every reject record is a well-formed 430-byte line.
        if (Files.exists(rejectFile)) {
            List<String> rejectLines = Files.readAllLines(rejectFile).stream()
                    .filter(line -> !line.isEmpty())
                    .toList();
            for (String line : rejectLines) {
                assertThat(line).hasSize(CardDemoConstants.DALYREJS_RECORD_WIDTH);
            }
        }
    }

    // ================================================================================================
    // MOCKITO COMPONENT TESTS — instantiate production components directly with mocked repositories.
    // No Spring context, no database, no @DirtiesContext, no @Transactional.
    // ================================================================================================

    /** Processor gauntlet step 1: card absent from the xref → reject 100 (INVALID CARD NUMBER FOUND). */
    @Test
    void processor_rejects100_whenCardNotInXref() {
        CardXrefRepository xrefRepo = mock(CardXrefRepository.class);
        AccountRepository acctRepo = mock(AccountRepository.class);
        when(xrefRepo.findByXrefCardNum(any())).thenReturn(Optional.empty());

        TransactionPostingProcessor processor = new TransactionPostingProcessor(xrefRepo, acctRepo);
        ProcessedTransaction pt = processor.process(DailyTransactionRecord.parse(buildRecord(
                "0000000000000001", "01", 1, new BigDecimal("1.00"),
                "9999999999999999", tsForDate(LocalDate.of(2020, 1, 1)))));

        assertThat(pt.isRejected()).isTrue();
        assertThat(pt.getRejectCode()).isEqualTo(CardDemoConstants.REJECT_CODE_INVALID_CARD);
        assertThat(pt.getRejectDesc()).isEqualTo(CardDemoConstants.REJECT_DESC_INVALID_CARD);
    }

    /**
     * Processor gauntlet step 2: xref found but account missing → reject 101 (ACCOUNT RECORD NOT
     * FOUND). UNREACHABLE through a full-job integration run (the FK guarantees the account exists),
     * so it is proven here at the component level.
     */
    @Test
    void processor_rejects101_whenAccountMissing() {
        CardXrefRepository xrefRepo = mock(CardXrefRepository.class);
        AccountRepository acctRepo = mock(AccountRepository.class);
        when(xrefRepo.findByXrefCardNum(any())).thenReturn(Optional.of(newXref("4859452612877065", 12345678901L)));
        when(acctRepo.findById(any())).thenReturn(Optional.empty());

        TransactionPostingProcessor processor = new TransactionPostingProcessor(xrefRepo, acctRepo);
        ProcessedTransaction pt = processor.process(DailyTransactionRecord.parse(buildRecord(
                "0000000000000001", "01", 1, new BigDecimal("1.00"),
                "4859452612877065", tsForDate(LocalDate.of(2020, 1, 1)))));

        assertThat(pt.isRejected()).isTrue();
        assertThat(pt.getRejectCode()).isEqualTo(CardDemoConstants.REJECT_CODE_ACCOUNT_NOT_FOUND);
        assertThat(pt.getRejectDesc()).isEqualTo(CardDemoConstants.REJECT_DESC_ACCOUNT_NOT_FOUND);
    }

    /**
     * Processor gauntlet step 3: cycle-based overlimit → reject 102. With cycCredit=cycDebit=0.00 and
     * creditLimit=100.00, an amount of 200.00 yields tempBal=200.00 > 100.00. The far-future expiry
     * keeps step 4 from firing, so the final reason remains 102.
     */
    @Test
    void processor_rejects102_whenOverlimit() {
        CardXrefRepository xrefRepo = mock(CardXrefRepository.class);
        AccountRepository acctRepo = mock(AccountRepository.class);
        when(xrefRepo.findByXrefCardNum(any())).thenReturn(Optional.of(newXref("4859452612877065", 777L)));
        when(acctRepo.findById(any())).thenReturn(
                Optional.of(newAccount(777L, "100.00", "0.00", LocalDate.of(2099, 12, 31))));

        TransactionPostingProcessor processor = new TransactionPostingProcessor(xrefRepo, acctRepo);
        ProcessedTransaction pt = processor.process(DailyTransactionRecord.parse(buildRecord(
                "0000000000000001", "01", 1, new BigDecimal("200.00"),
                "4859452612877065", tsForDate(LocalDate.of(2020, 1, 1)))));

        assertThat(pt.isRejected()).isTrue();
        assertThat(pt.getRejectCode()).isEqualTo(CardDemoConstants.REJECT_CODE_OVERLIMIT);
    }

    /**
     * Processor gauntlet step 4: account expiry strictly before the transaction origination date →
     * reject 103. A large credit limit keeps step 3 from firing, isolating the expiry rule.
     */
    @Test
    void processor_rejects103_whenExpired() {
        CardXrefRepository xrefRepo = mock(CardXrefRepository.class);
        AccountRepository acctRepo = mock(AccountRepository.class);
        when(xrefRepo.findByXrefCardNum(any())).thenReturn(Optional.of(newXref("4859452612877065", 777L)));
        when(acctRepo.findById(any())).thenReturn(
                Optional.of(newAccount(777L, "100000.00", "0.00", LocalDate.of(2020, 1, 1))));

        TransactionPostingProcessor processor = new TransactionPostingProcessor(xrefRepo, acctRepo);
        ProcessedTransaction pt = processor.process(DailyTransactionRecord.parse(buildRecord(
                "0000000000000001", "01", 1, new BigDecimal("1.00"),
                "4859452612877065", tsForDate(LocalDate.of(2025, 1, 1)))));

        assertThat(pt.isRejected()).isTrue();
        assertThat(pt.getRejectCode()).isEqualTo(CardDemoConstants.REJECT_CODE_TRANSACTION_EXPIRED);
    }

    /**
     * Both overlimit AND expired: because steps 3 and 4 are sequential {@code if}s (not {@code
     * else-if}), the 103 assignment overwrites the 102, so the final reason is 103. This is the
     * unit-level proof of the overwrite asserted in the integration test's combined record.
     */
    @Test
    void processor_combinedOverlimitAndExpired_finalReasonIs103() {
        CardXrefRepository xrefRepo = mock(CardXrefRepository.class);
        AccountRepository acctRepo = mock(AccountRepository.class);
        when(xrefRepo.findByXrefCardNum(any())).thenReturn(Optional.of(newXref("4859452612877065", 777L)));
        when(acctRepo.findById(any())).thenReturn(
                Optional.of(newAccount(777L, "100.00", "0.00", LocalDate.of(2020, 1, 1))));

        TransactionPostingProcessor processor = new TransactionPostingProcessor(xrefRepo, acctRepo);
        ProcessedTransaction pt = processor.process(DailyTransactionRecord.parse(buildRecord(
                "0000000000000001", "01", 1, new BigDecimal("200.00"),
                "4859452612877065", tsForDate(LocalDate.of(2025, 1, 1)))));

        assertThat(pt.isRejected()).isTrue();
        assertThat(pt.getRejectCode()).isEqualTo(CardDemoConstants.REJECT_CODE_TRANSACTION_EXPIRED);
    }

    /**
     * Valid path: the processor builds a {@link Transaction} whose id is the incoming DALYTRAN-ID
     * VERBATIM (no id regeneration), whose account id comes from the xref, and whose amount is copied.
     */
    @Test
    void processor_validRecord_buildsTransaction_withVerbatimId() {
        CardXrefRepository xrefRepo = mock(CardXrefRepository.class);
        AccountRepository acctRepo = mock(AccountRepository.class);
        when(xrefRepo.findByXrefCardNum(any())).thenReturn(Optional.of(newXref("4859452612877065", 777L)));
        when(acctRepo.findById(any())).thenReturn(
                Optional.of(newAccount(777L, "100000.00", "0.00", LocalDate.of(2099, 12, 31))));

        TransactionPostingProcessor processor = new TransactionPostingProcessor(xrefRepo, acctRepo);
        ProcessedTransaction pt = processor.process(DailyTransactionRecord.parse(buildRecord(
                "0000000000000042", "01", 1, new BigDecimal("1.00"),
                "4859452612877065", tsForDate(LocalDate.of(2020, 1, 1)))));

        assertThat(pt.isValid()).isTrue();
        Transaction tx = pt.getTransaction();
        assertThat(tx).isNotNull();
        assertThat(tx.getTranId()).isEqualTo("0000000000000042"); // VERBATIM carry-through
        assertThat(tx.getAcctId()).isEqualTo(777L);
        assertThat(tx.getAmt()).isEqualByComparingTo("1.00");
    }

    /**
     * Writer reject 109: a valid item whose account read fails during {@code postValid} is caught and
     * re-marked rejected with code 109 (account update failed) and emitted to the reject writer.
     * UNREACHABLE through a full-job integration run, so it is proven here at the component level. The
     * 109 description intentionally shares 101's text in the legacy source, so only the {@code 0109}
     * reason code is asserted (not the description).
     */
    @Test
    void writer_rejects109_whenAccountUpdateFails() throws Exception {
        AccountRepository acctRepo = mock(AccountRepository.class);
        TransactionCategoryBalanceRepository tcbRepo = mock(TransactionCategoryBalanceRepository.class);
        TransactionRepository tranRepo = mock(TransactionRepository.class);
        @SuppressWarnings("unchecked")
        FlatFileItemWriter<String> rejectWriter = mock(FlatFileItemWriter.class);

        TransactionPostingWriter writer =
                new TransactionPostingWriter(acctRepo, tcbRepo, tranRepo, rejectWriter);
        writer.beforeStep(MetaDataInstanceFactory.createStepExecution());

        DailyTransactionRecord rec = DailyTransactionRecord.parse(buildRecord(
                "0000000000000001", "01", 1, new BigDecimal("10.00"),
                "4859452612877065", tsForDate(LocalDate.of(2020, 1, 1))));
        Transaction tx = new Transaction();
        tx.setTranId("0000000000000001");
        tx.setAcctId(777L);
        tx.setTypeCd("01");
        tx.setCatCd(1);
        tx.setAmt(new BigDecimal("10.00"));
        tx.setCardNum("4859452612877065");
        ProcessedTransaction pt = ProcessedTransaction.valid(rec, tx);

        when(tcbRepo.findById(any())).thenReturn(Optional.empty());
        when(acctRepo.findById(any())).thenReturn(Optional.empty()); // orElseThrow → caught → 109

        writer.write(new Chunk<>(List.of(pt)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Chunk<String>> captor = ArgumentCaptor.forClass(Chunk.class);
        verify(rejectWriter, atLeastOnce()).write(captor.capture());
        List<String> rejectLines = captor.getAllValues().stream()
                .flatMap(c -> c.getItems().stream())
                .toList();

        assertThat(rejectLines).hasSize(1);
        assertThat(rejectLines.get(0)).hasSize(CardDemoConstants.DALYREJS_RECORD_WIDTH);
        assertThat(rejectLines.get(0).substring(350, 354)).isEqualTo("0109"); // reason only; desc shares 101's text
        assertThat(pt.isRejected()).isTrue();
        assertThat(pt.getRejectCode()).isEqualTo(CardDemoConstants.REJECT_CODE_ACCOUNT_UPDATE_FAILED);

        ExitStatus exit = writer.afterStep(MetaDataInstanceFactory.createStepExecution());
        assertThat(exit.getExitCode()).isEqualTo("WARNING");
    }

    /**
     * Writer valid path: the TCATBAL is upserted, the account current balance and (since the amount is
     * non-negative) current-cycle credit are incremented, and the account and transaction are saved.
     * No reject line is produced and the step exits {@code COMPLETED}.
     */
    @Test
    void writer_postsValid_updatesAccountCycleAndPersists() throws Exception {
        AccountRepository acctRepo = mock(AccountRepository.class);
        TransactionCategoryBalanceRepository tcbRepo = mock(TransactionCategoryBalanceRepository.class);
        TransactionRepository tranRepo = mock(TransactionRepository.class);
        @SuppressWarnings("unchecked")
        FlatFileItemWriter<String> rejectWriter = mock(FlatFileItemWriter.class);

        TransactionPostingWriter writer =
                new TransactionPostingWriter(acctRepo, tcbRepo, tranRepo, rejectWriter);
        writer.beforeStep(MetaDataInstanceFactory.createStepExecution());

        Account a = newAccount(777L, "100000.00", "0.00", LocalDate.of(2099, 12, 31));
        a.setCurrBal(new BigDecimal("100.00"));

        DailyTransactionRecord rec = DailyTransactionRecord.parse(buildRecord(
                "0000000000000050", "01", 1, new BigDecimal("10.00"),
                "4859452612877065", tsForDate(LocalDate.of(2020, 1, 1))));
        Transaction tx = new Transaction();
        tx.setTranId("0000000000000050");
        tx.setAcctId(777L);
        tx.setTypeCd("01");
        tx.setCatCd(1);
        tx.setAmt(new BigDecimal("10.00"));
        tx.setCardNum("4859452612877065");
        ProcessedTransaction pt = ProcessedTransaction.valid(rec, tx);

        when(tcbRepo.findById(any())).thenReturn(Optional.empty());
        when(acctRepo.findById(777L)).thenReturn(Optional.of(a));

        writer.write(new Chunk<>(List.of(pt)));

        verify(tcbRepo).save(any());
        verify(acctRepo).save(a);
        verify(tranRepo).save(tx);
        assertThat(a.getCurrBal()).isEqualByComparingTo("110.00");      // 100.00 + 10.00
        assertThat(a.getCurrCycCredit()).isEqualByComparingTo("10.00"); // amount ≥ 0 → cycle credit
        verify(rejectWriter, never()).write(any());

        ExitStatus exit = writer.afterStep(MetaDataInstanceFactory.createStepExecution());
        assertThat(exit.getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    /**
     * Precise §0.6.2 layout proof: a rejected item's {@code buildRejectLine} output is exactly 430
     * characters — the 350-byte feed image verbatim, the 4-byte zero-padded reason code, then the
     * 76-byte description trailer.
     */
    @Test
    void writer_buildRejectLine_is430Bytes_withReasonAndDescTrailer() throws Exception {
        AccountRepository acctRepo = mock(AccountRepository.class);
        TransactionCategoryBalanceRepository tcbRepo = mock(TransactionCategoryBalanceRepository.class);
        TransactionRepository tranRepo = mock(TransactionRepository.class);
        @SuppressWarnings("unchecked")
        FlatFileItemWriter<String> rejectWriter = mock(FlatFileItemWriter.class);

        TransactionPostingWriter writer =
                new TransactionPostingWriter(acctRepo, tcbRepo, tranRepo, rejectWriter);
        writer.beforeStep(MetaDataInstanceFactory.createStepExecution());

        DailyTransactionRecord rec = DailyTransactionRecord.parse(buildRecord(
                "0000000000000001", "01", 1, new BigDecimal("1.00"),
                "9999999999999999", tsForDate(LocalDate.of(2020, 1, 1))));
        ProcessedTransaction pt = ProcessedTransaction.rejected(
                rec, CardDemoConstants.REJECT_CODE_INVALID_CARD, CardDemoConstants.REJECT_DESC_INVALID_CARD);

        writer.write(new Chunk<>(List.of(pt)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Chunk<String>> captor = ArgumentCaptor.forClass(Chunk.class);
        verify(rejectWriter, atLeastOnce()).write(captor.capture());
        List<String> rejectLines = captor.getAllValues().stream()
                .flatMap(c -> c.getItems().stream())
                .toList();

        assertThat(rejectLines).hasSize(1);
        String line = rejectLines.get(0);
        assertThat(line).hasSize(CardDemoConstants.DALYREJS_RECORD_WIDTH);                 // 430
        assertThat(line.substring(0, 350)).isEqualTo(rec.getRawRecord());                 // 350-byte feed image verbatim
        assertThat(line.substring(350, 354)).isEqualTo("0100");                           // 4-byte reason code
        assertThat(line.substring(354, 430).trim())
                .isEqualTo(CardDemoConstants.REJECT_DESC_INVALID_CARD);                    // 76-byte description trailer
    }
}
