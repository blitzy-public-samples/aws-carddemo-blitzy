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
package com.carddemo.reporting.batch;

import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Customer;
import com.carddemo.common.domain.Transaction;
import com.carddemo.common.testsupport.MigratedSchemaContainer;
import com.carddemo.reporting.repository.AccountRepository;
import com.carddemo.reporting.repository.CardXrefRepository;
import com.carddemo.reporting.repository.CustomerRepository;
import com.carddemo.reporting.repository.TransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * :purpose: End-to-end integration test for the re-platformed batch statement
 *   engine. It boots the full reporting-service Spring context against a real
 *   PostgreSQL (the shared ``postgres:18`` container carrying the schema the
 *   committed Flyway migrations produce), seeds a deterministic minimal dataset through the
 *   shared ``carddemo-common`` repositories, launches the on-demand
 *   ``statementGenerationJob`` through {@link JobLauncherTestUtils}, and asserts
 *   the produced plain-text (80-column) and HTML (100-column) statement files are
 *   byte-for-byte faithful to the legacy ``CBSTM03A``/``CBSTM03B`` engine
 *   (``CREASTMT.JCL`` DD ``STMTFILE`` LRECL=80 / ``HTMLFILE`` LRECL=100).
 * :output: A Failsafe (``*IT``) integration test whose single scenario confirms
 *   the whole job completes and both statement files preserve exact line widths,
 *   the frozen labels/banners/color literals, and scale-2 ``BigDecimal`` monetary
 *   precision (``000001234.56``, ``300.75``, ``100.50``, ``200.25``).
 */
@SpringBootTest
@SpringBatchTest
@ActiveProfiles("test")
class StatementGenerationJobIT {

    /** :purpose: Synthetic, PII-safe card number shared by the seeded card and transactions. */
    private static final String SYNTHETIC_CARD = "0000000000000000";

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CardXrefRepository cardXrefRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    /** :purpose: Raw JDBC access used only to clear the seeded cards before their parents. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * :purpose: Bind the datasource to the shared, already-migrated ``postgres:18`` container
     *   from :java:class:`com.carddemo.common.testsupport.MigratedSchemaContainer`. Both the
     *   business tables and the Spring Batch metadata schema (``BATCH_*`` tables and
     *   sequences, created by the shared migration ``V5__batch_metadata.sql``) come
     *   from the owning modules' committed migrations, so nothing is generated from the
     *   entities and Hibernate only validates the mapping.
     * :param registry: the dynamic property registry supplied by the test context.
     */
    @DynamicPropertySource
    static void provisionSchema(DynamicPropertyRegistry registry) {
        MigratedSchemaContainer.registerDataSource(registry);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // Every batch path is resolved inside carddemo.batch.output-dir and a path escaping
        // it is refused (CWE-22). The per-test ``@TempDir`` lives under the JVM temporary
        // directory, so that directory is declared as the root: the containment check stays
        // in force and the statement artifacts still land in a JUnit-managed directory.
        registry.add("carddemo.batch.output-dir", () -> System.getProperty("java.io.tmpdir"));
    }

    /**
     * :purpose: Seed a deterministic, PII-safe dataset through the shared
     *   repositories in dependency order (customer, then account, then the card
     *   cross-reference, then two transactions) so the launched job reads exactly
     *   one card with a known balance and two known transaction amounts. Parents
     *   are saved before children so the ``card_xref`` foreign keys are satisfied,
     *   and every NOT-NULL column of each entity is populated with synthetic
     *   values. The two transactions sum to ``300.75``.
     */
    @BeforeEach
    void seedData() {
        transactionRepository.deleteAll();
        cardXrefRepository.deleteAll();
        // The migration seed carries 50 cards that foreign-key into accounts, so they are
        // removed before their parents; this job reads whole tables, so the test owns their
        // content.
        jdbcTemplate.update("DELETE FROM cards");
        // tran_cat_bal foreign-keys into accounts (fk_tran_cat_bal_acct), so the seeded
        // category balances go before their parent accounts.
        jdbcTemplate.update("DELETE FROM tran_cat_bal");
        accountRepository.deleteAll();
        customerRepository.deleteAll();

        Customer customer = new Customer();
        customer.setCustId(1L);
        customer.setCustFirstName("JOHN");
        customer.setCustMiddleName("A");
        customer.setCustLastName("DOE");
        customer.setCustAddrLine1("123 MAIN ST");
        customer.setCustAddrLine2("SUITE 100");
        customer.setCustAddrLine3("ANYTOWN");
        customer.setCustAddrStateCd("WA");
        customer.setCustAddrCountryCd("USA");
        customer.setCustAddrZip("99999");
        customer.setCustPhoneNum1("(000)000-0000");
        customer.setCustPhoneNum2("(000)000-0000");
        customer.setCustDobYyyyMmDd("1970-01-01");
        customer.setCustPriCardHolderInd("Y");
        customer.setCustFicoCreditScore(750);
        // The migrated NOT NULL PII columns carry blank values: no PII is seeded (AAP 0.6.7)
        // and the CryptoConverter passes empty strings through unchanged.
        customer.setCustSsn("");
        customer.setCustGovtIssuedId("");
        customer.setCustEftAccountId("");
        customerRepository.save(customer);

        Account account = new Account();
        account.setAcctId(1L);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(new BigDecimal("1234.56"));
        account.setAcctCreditLimit(new BigDecimal("5000.00"));
        account.setAcctCashCreditLimit(new BigDecimal("1000.00"));
        account.setAcctOpenDate("2020-01-01");
        // The legacy copybook misspelling ACCT-EXPIRAION-DATE is preserved verbatim.
        account.setAcctExpiraionDate("2099-12-31");
        account.setAcctReissueDate("2020-01-01");
        account.setAcctCurrCycCredit(new BigDecimal("0.00"));
        account.setAcctCurrCycDebit(new BigDecimal("0.00"));
        account.setAcctAddrZip("99999");
        accountRepository.save(account);

        // The card master row backs the cross-reference: a transaction's tran_card_num
        // foreign-keys into cards (fk_transactions_card, V8__transactions_card_fk.sql), so a
        // fixture carrying only the xref would describe a state the database does not permit.
        jdbcTemplate.update(
                "INSERT INTO cards (card_num, card_acct_id, card_cvv_cd, card_embossed_name, "
                        // The legacy copybook misspelling CARD-EXPIRAION-DATE is preserved.
                        + "card_expiraion_date, card_active_status, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                SYNTHETIC_CARD, 1L, null, "Statement Holder", "2099-12-31", "Y", 0L);

        CardXref cardXref = new CardXref();
        cardXref.setXrefCardNum(SYNTHETIC_CARD);
        cardXref.setXrefCustId(1L);
        cardXref.setXrefAcctId(1L);
        cardXrefRepository.save(cardXref);

        Transaction transactionOne = new Transaction();
        transactionOne.setTranId("0000000000000001");
        transactionOne.setTranCardNum(SYNTHETIC_CARD);
        transactionOne.setTranAmt(new BigDecimal("100.50"));
        transactionOne.setTranDesc("PURCHASE ONE");
        transactionOne.setTranTypeCd("01");
        transactionOne.setTranCatCd(1);
        transactionOne.setTranSource("POS");
        transactionOne.setTranOrigTs("2024-01-15-10.30.00.123456");
        transactionOne.setTranProcTs("2024-01-15-10.30.00.123456");

        Transaction transactionTwo = new Transaction();
        transactionTwo.setTranId("0000000000000002");
        transactionTwo.setTranCardNum(SYNTHETIC_CARD);
        transactionTwo.setTranAmt(new BigDecimal("200.25"));
        transactionTwo.setTranDesc("PURCHASE TWO");
        transactionTwo.setTranTypeCd("01");
        transactionTwo.setTranCatCd(1);
        transactionTwo.setTranSource("POS");
        transactionTwo.setTranOrigTs("2024-01-16-11.45.00.654321");
        transactionTwo.setTranProcTs("2024-01-16-11.45.00.654321");

        transactionRepository.saveAll(List.of(transactionOne, transactionTwo));
    }

    /**
     * :purpose: Launch the whole ``statementGenerationJob`` against the seeded data
     *   with the text and HTML output paths redirected under a temporary directory,
     *   then assert the job completes and both produced files are byte-for-byte
     *   faithful to the legacy engine: every text line is exactly 80 characters and
     *   every HTML line exactly 100, the frozen labels/banners/color literals are
     *   present verbatim, and the balance and transaction amounts render with exact
     *   scale-2 precision and no drift.
     * :param tempDir: the JUnit-managed temporary directory for the output files.
     */
    @Test
    void statementJobGeneratesByteExactTextAndHtmlStatements(@TempDir Path tempDir) throws Exception {
        File textFile = new File(tempDir.toFile(), "statements.txt");
        File htmlFile = new File(tempDir.toFile(), "statements.html");

        JobParameters params = new JobParametersBuilder()
                // The two output file names are the whole parameter set: CREASTMT
                // carries no PARM and the job reads no date window.
                .addString("stmtFile", textFile.getAbsolutePath())
                .addString("htmlFile", htmlFile.getAbsolutePath())
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters();

        JobExecution jobExecution = jobLauncherTestUtils.launchJob(params);

        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jobExecution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        assertThat(textFile).exists();
        assertThat(htmlFile).exists();

        List<String> textLines = Files.readAllLines(textFile.toPath());
        List<String> htmlLines = Files.readAllLines(htmlFile.toPath());

        // Frozen text labels/banners reconstructed exactly as the engine emits them
        // (the 20-character basic-detail labels, the fixed asterisk banners).
        String accountIdLabel = "Account ID" + " ".repeat(9) + ":";
        String currentBalanceLabel = "Current Balance" + " ".repeat(4) + ":";
        String ficoScoreLabel = "FICO Score" + " ".repeat(9) + ":";
        String startBanner = "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31);
        String endBanner = "*".repeat(32) + "END OF STATEMENT" + "*".repeat(32);

        assertAll("statement text + HTML byte-exact fidelity",
                // ----- TEXT statement (STMTFILE, LRECL=80) -----
                () -> assertThat(textLines).isNotEmpty(),
                () -> assertThat(textLines).allSatisfy(line -> assertThat(line).hasSize(80)),
                () -> assertThat(textLines).contains(startBanner),
                () -> assertThat(textLines).contains(endBanner),
                () -> assertThat(textLines).anyMatch(line -> line.contains("Basic Details ")),
                () -> assertThat(textLines).anyMatch(line -> line.startsWith(accountIdLabel)),
                () -> assertThat(textLines).anyMatch(line -> line.startsWith(currentBalanceLabel)),
                () -> assertThat(textLines).anyMatch(line -> line.startsWith(ficoScoreLabel)),
                () -> assertThat(textLines).anyMatch(line -> line.contains("TRANSACTION SUMMARY ")),
                () -> assertThat(textLines)
                        .anyMatch(line -> line.contains("Tran ID         ") && line.contains("  Tran Amount")),
                () -> assertThat(textLines).anyMatch(line -> line.startsWith("Total EXP:")),
                // TEXT precision (no drift): balance 9(9).99- edit and the Z(9).99- amounts.
                () -> assertThat(textLines)
                        .anyMatch(line -> line.startsWith(currentBalanceLabel) && line.contains("000001234.56 ")),
                () -> assertThat(textLines)
                        .anyMatch(line -> line.startsWith(ficoScoreLabel) && line.contains("750")),
                () -> assertThat(textLines)
                        .anyMatch(line -> line.startsWith("Total EXP:") && line.contains("300.75")),
                () -> assertThat(textLines).anyMatch(line -> line.contains("100.50")),
                () -> assertThat(textLines).anyMatch(line -> line.contains("200.25")),

                // ----- HTML statement (HTMLFILE, LRECL=100) -----
                () -> assertThat(htmlLines).isNotEmpty(),
                () -> assertThat(htmlLines).allSatisfy(line -> assertThat(line).hasSize(100)),
                () -> assertThat(htmlLines).anyMatch(line -> line.stripTrailing().equals("<!DOCTYPE html>")),
                () -> assertThat(htmlLines).anyMatch(line -> line.stripTrailing().equals("<html lang=\"en\">")),
                () -> assertThat(htmlLines).anyMatch(line -> line.stripTrailing().equals("<body style=\"margin:0px;\">")),
                () -> assertThat(htmlLines).anyMatch(line -> line.contains("<table  align=")),
                () -> assertThat(htmlLines).anyMatch(line -> line.contains("Bank of XYZ")),
                () -> assertThat(htmlLines).anyMatch(line -> line.contains("410 Terry Ave N")),
                () -> assertThat(htmlLines).anyMatch(line -> line.contains("Seattle WA 99999")),
                () -> assertThat(htmlLines).anyMatch(line -> line.contains("Basic Details")),
                () -> assertThat(htmlLines).anyMatch(line -> line.contains("Transaction Summary")),
                () -> assertThat(htmlLines).anyMatch(line -> line.contains("<h3>Statement for Account Number: ")),
                () -> assertThat(htmlLines).anyMatch(line -> line.stripTrailing().equals("<h3>End of Statement</h3>")),
                () -> assertThat(htmlLines).anyMatch(line -> line.contains("#1d1d96b3")),
                () -> assertThat(htmlLines).anyMatch(line -> line.contains("#FFAF33")),
                () -> assertThat(htmlLines).anyMatch(line -> line.contains("#f2f2f2")),
                () -> assertThat(htmlLines).anyMatch(line -> line.contains("#33FFD1")),
                () -> assertThat(htmlLines).anyMatch(line -> line.contains("#33FF5E")),
                // HTML precision (no drift): balance and the first transaction amount.
                () -> assertThat(htmlLines).anyMatch(line -> line.contains("000001234.56 ")),
                () -> assertThat(htmlLines).anyMatch(line -> line.contains("100.50"))
        );
    }
}
