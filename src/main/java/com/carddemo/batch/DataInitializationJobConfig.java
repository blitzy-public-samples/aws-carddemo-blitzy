package com.carddemo.batch;

import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.DisclosureGroupId;
import com.carddemo.entity.TransactionCategory;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
import com.carddemo.entity.TransactionCategoryId;
import com.carddemo.entity.TransactionType;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.DisclosureGroupRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionCategoryRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.repository.TransactionTypeRepository;
import com.carddemo.util.FixedWidthRecordParser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring Batch configuration for the <strong>{@code dataInitializationJob}</strong> &mdash; the
 * Java/PostgreSQL replacement for the composite mainframe seed-loading sequence of nine
 * file-initialization JCL jobs.
 *
 * <h2>Legacy mainframe behavior (nine IDCAMS DELETE/DEFINE/REPRO jobs)</h2>
 * Each source JCL job deleted, re-defined, and {@code REPRO}-loaded a single VSAM dataset from a
 * corresponding fixed-width ASCII fixture under {@code app/data/ASCII/}:
 * <table border="1">
 *   <caption>JCL job &rarr; fixture &rarr; entity mapping</caption>
 *   <tr><th>Source JCL</th><th>Fixture</th><th>Records</th><th>Entity / repository</th></tr>
 *   <tr><td>{@code app/jcl/TRANTYPE.jcl}</td><td>{@code trantype.txt}</td><td>7</td><td>{@link TransactionType}</td></tr>
 *   <tr><td>{@code app/jcl/TRANCATG.jcl}</td><td>{@code trancatg.txt}</td><td>18</td><td>{@link TransactionCategory}</td></tr>
 *   <tr><td>{@code app/jcl/DISCGRP.jcl}</td><td>{@code discgrp.txt}</td><td>51</td><td>{@link DisclosureGroup}</td></tr>
 *   <tr><td>{@code app/jcl/CUSTFILE.jcl}</td><td>{@code custdata.txt}</td><td>50</td><td>{@link Customer}</td></tr>
 *   <tr><td>{@code app/jcl/ACCTFILE.jcl}</td><td>{@code acctdata.txt}</td><td>50</td><td>{@link Account}</td></tr>
 *   <tr><td>{@code app/jcl/CARDFILE.jcl}</td><td>{@code carddata.txt}</td><td>50</td><td>{@link Card}</td></tr>
 *   <tr><td>{@code app/jcl/XREFFILE.jcl}</td><td>{@code cardxref.txt}</td><td>50</td><td>{@link CardXref}</td></tr>
 *   <tr><td>{@code app/jcl/TCATBALF.jcl}</td><td>{@code tcatbal.txt}</td><td>50</td><td>{@link TransactionCategoryBalance}</td></tr>
 *   <tr><td>{@code app/jcl/TRANFILE.jcl}</td><td>(none in seed)</td><td>0</td><td>{@code Transaction} (no-op; see below)</td></tr>
 * </table>
 *
 * <p>The tenth job in the original sequence, {@code app/jcl/CBADMCDJ.jcl}, defined CICS resources
 * (mapsets/programs/transactions). It has no Java equivalent &mdash; CICS routing is replaced by the
 * Spring REST controllers (AAP &sect;0.4.1.3) &mdash; and is therefore intentionally omitted here.</p>
 *
 * <h2>Modernized Spring Batch behavior</h2>
 * The relational schema is created by the committed Flyway migrations
 * ({@code V1__schema.sql} + {@code V2__indexes.sql}); the {@code @Index} declarations on
 * {@link Card}, {@link CardXref}, and {@code Transaction} replace the VSAM alternate indexes, so the
 * VSAM {@code DEFINE}/{@code BLDINDEX} mechanics of the original jobs require no Java equivalent. This
 * job reuses the <em>same</em> {@code app/data/ASCII/*.txt} fixtures (preserved unchanged per PR-27)
 * as a programmatic seed/re-load path: each of the nine {@code @Bean Step}s reads one fixture,
 * parses every fixed-width line into the matching JPA entity, and batch-persists the result through
 * the corresponding repository.
 *
 * <h2>Dependency-aware step ordering (PR-23)</h2>
 * The {@link #dataInitializationJob()} chains the steps so that every foreign-key target is present
 * before its referrers, mirroring the documented VSAM lock/load order
 * {@code CUSTOMER &rarr; ACCOUNT &rarr; CARD &rarr; TRANSACTION}:
 * <ol>
 *   <li>Reference data first (no foreign keys): {@link TransactionType}, {@link TransactionCategory},
 *       {@link DisclosureGroup}.</li>
 *   <li>Master data in FK order: {@link Customer} &rarr; {@link Account} &rarr; {@link Card} &rarr;
 *       {@link CardXref}.</li>
 *   <li>{@link TransactionCategoryBalance} (logically keyed by account + transaction category).</li>
 *   <li>{@code Transaction} &mdash; a deliberate no-op: there is no transaction seed fixture; the
 *       {@code transactions} table is populated by the {@code POSTTRAN} job
 *       ({@code TransactionPostingJobConfig}). The step is retained to preserve the one-to-one
 *       correspondence with {@code TRANFILE.jcl}.</li>
 * </ol>
 *
 * <h2>Idempotency</h2>
 * Because the same master/reference data is also seeded declaratively by Flyway
 * ({@code V3__seed_reference_data.sql} + {@code V5__seed_master_data.sql}), the tables are normally
 * already populated by the time this operational job is launched (for example via
 * {@code POST /api/admin/jobs/dataInitializationJob/launch}). To remain safely re-runnable and avoid
 * primary-key violations on an already-seeded database, each loading step is guarded: if its target
 * table already contains rows the step logs and returns {@link RepeatStatus#FINISHED} without
 * loading. On a genuinely empty database (Flyway seeding disabled) every fixture is loaded in full.
 *
 * <h2>Rule enforcement</h2>
 * <ul>
 *   <li><b>PR-13</b> &mdash; byte offsets/lengths in every step mirror the COBOL {@code PIC} clauses
 *       of the originating copybook exactly.</li>
 *   <li><b>PR-16</b> &mdash; zoned-decimal money fields are decoded to {@link BigDecimal} with scale
 *       {@code 2} and {@link RoundingMode#HALF_UP}; no {@code float}/{@code double} is used.</li>
 *   <li><b>PR-22</b> &mdash; freshly constructed entities leave {@code @Version} at its default
 *       ({@code 0}) for optimistic locking.</li>
 *   <li><b>PR-23</b> &mdash; steps execute in dependency-aware order (see above).</li>
 *   <li><b>PR-24</b> &mdash; every step runs in its own transaction via the injected
 *       {@link PlatformTransactionManager}, replicating the implicit CICS {@code SYNCPOINT}
 *       unit-of-work boundary.</li>
 *   <li><b>PR-27</b> &mdash; the ASCII fixtures are read only; never modified.</li>
 *   <li><b>PR-28 / PR-29</b> &mdash; Spring/Jakarta annotations and constructor injection (Lombok
 *       {@code @RequiredArgsConstructor} over {@code final} fields).</li>
 * </ul>
 *
 * <p><strong>Implementation note &mdash; {@link FixedWidthRecordParser} usage.</strong> The parser is
 * a stateless utility exposing only {@code public static} methods (its constructor is private), so it
 * is invoked statically rather than injected as a bean. Its {@code parseBigDecimal} method assumes
 * already-normalized numeric text; the CardDemo money fixtures instead carry the COBOL zoned-decimal
 * sign as an overpunch on the final character (for example the trailing {@code '{'} in
 * {@code "00000001940{"}). That overpunch is decoded here by {@link #decodeZonedDecimalSign(String)}
 * (standard EBCDIC convention) before the scaled {@link BigDecimal} is built by
 * {@link #parseMoney(String, int, int, int)}, keeping this loader self-contained within its declared
 * dependencies.</p>
 *
 * <p><strong>Spring Batch wiring.</strong> This class is a plain {@code @Configuration}; it does
 * <em>not</em> declare {@code @EnableBatchProcessing} (which would disable Spring Boot's batch
 * auto-configuration on Boot 3.x). The {@code JobRegistryBeanPostProcessor} declared in
 * {@code BatchConfig} auto-registers the {@link #dataInitializationJob()} bean under {@link #JOB_NAME}
 * so the {@code BatchAdminController} can launch it by name.</p>
 *
 * @see com.carddemo.util.FixedWidthRecordParser
 * @see com.carddemo.batch.reader.AsciiFixedWidthItemReader
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class DataInitializationJobConfig {

    /** Registry name of the composite seed-loading job; used by {@code BatchAdminController}. */
    public static final String JOB_NAME = "dataInitializationJob";

    /** Classpath location of the fixtures (used first; e.g. test resources under {@code fixtures/}). */
    private static final String CLASSPATH_FIXTURE_PREFIX = "fixtures/";

    /** Filesystem location of the canonical fixtures at the repository root (PR-27, read-only). */
    private static final String FILESYSTEM_FIXTURE_PREFIX = "app/data/ASCII/";

    /** ISO layout used by every {@code PIC X(10)} date field in the CardDemo fixtures. */
    private static final String ISO_DATE_PATTERN = "yyyy-MM-dd";

    /** Implied decimal scale for all COBOL {@code S9(n)V99} money/rate fields (PR-16). */
    private static final int MONEY_SCALE = 2;

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final TransactionTypeRepository transactionTypeRepository;
    private final TransactionCategoryRepository transactionCategoryRepository;
    private final DisclosureGroupRepository disclosureGroupRepository;
    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final CardRepository cardRepository;
    private final CardXrefRepository cardXrefRepository;
    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;
    private final TransactionRepository transactionRepository;

    // ------------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------------

    /**
     * Builds a single-transaction tasklet {@link Step} bound to the shared {@link #jobRepository}
     * and {@link #transactionManager} (PR-24: each step is its own unit of work).
     *
     * @param stepName the unique Spring Batch step name
     * @param tasklet  the tasklet body to execute
     * @return a fully built {@link Step}
     */
    private Step buildStep(String stepName, Tasklet tasklet) {
        return new StepBuilder(stepName, jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    /**
     * Reads every non-empty line of a fixed-width fixture, trying the classpath first
     * ({@code fixtures/<fileName>}) and then the repository-root filesystem path
     * ({@code app/data/ASCII/<fileName>}). If the fixture is found in neither location the method
     * logs a warning and returns an empty list, allowing the owning step to complete as a no-op
     * rather than fail.
     *
     * @param fileName the bare fixture file name (e.g. {@code "acctdata.txt"})
     * @return the fixture's non-empty lines, in file order; never {@code null}
     * @throws IOException if the resolved resource exists but cannot be read
     */
    private List<String> readFixtureLines(String fileName) throws IOException {
        Resource resource = new ClassPathResource(CLASSPATH_FIXTURE_PREFIX + fileName);
        if (!resource.exists()) {
            resource = new FileSystemResource(FILESYSTEM_FIXTURE_PREFIX + fileName);
        }
        if (!resource.exists()) {
            log.warn("Fixture '{}' not found on classpath ('{}{}') or filesystem ('{}{}'); "
                            + "skipping load (0 records)",
                    fileName, CLASSPATH_FIXTURE_PREFIX, fileName, FILESYSTEM_FIXTURE_PREFIX, fileName);
            return new ArrayList<>();
        }
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    /**
     * Decodes a COBOL zoned-decimal numeric string whose final character carries the sign as an
     * EBCDIC overpunch, returning a plain (optionally signed) digit string.
     *
     * <p>Standard convention: {@code '{'} = {@code +0}, {@code 'A'..'I'} = {@code +1..+9},
     * {@code '}'} = {@code -0}, {@code 'J'..'R'} = {@code -1..-9}. A trailing plain digit indicates
     * no overpunch and the input is returned unchanged (treated as a positive unsigned value). This
     * mirrors the canonical decoder in {@code AsciiFixedWidthItemReader#decodeZonedDecimalSign} so
     * the seed loader and the streaming reader agree byte-for-byte.</p>
     *
     * @param overpunched the raw fixed-width numeric field (digits with an optional trailing
     *                    overpunch character); may be {@code null} or empty
     * @return the decoded plain digit string (prefixed with {@code '-'} when negative), or the input
     *         unchanged when {@code null}/empty/already plain
     * @throws IllegalArgumentException if the trailing character is not a recognized overpunch
     */
    private static String decodeZonedDecimalSign(String overpunched) {
        if (overpunched == null || overpunched.isEmpty()) {
            return overpunched;
        }
        char last = overpunched.charAt(overpunched.length() - 1);
        if (last >= '0' && last <= '9') {
            return overpunched;
        }
        char digit;
        boolean negative;
        switch (last) {
            case '{': digit = '0'; negative = false; break;
            case 'A': digit = '1'; negative = false; break;
            case 'B': digit = '2'; negative = false; break;
            case 'C': digit = '3'; negative = false; break;
            case 'D': digit = '4'; negative = false; break;
            case 'E': digit = '5'; negative = false; break;
            case 'F': digit = '6'; negative = false; break;
            case 'G': digit = '7'; negative = false; break;
            case 'H': digit = '8'; negative = false; break;
            case 'I': digit = '9'; negative = false; break;
            case '}': digit = '0'; negative = true; break;
            case 'J': digit = '1'; negative = true; break;
            case 'K': digit = '2'; negative = true; break;
            case 'L': digit = '3'; negative = true; break;
            case 'M': digit = '4'; negative = true; break;
            case 'N': digit = '5'; negative = true; break;
            case 'O': digit = '6'; negative = true; break;
            case 'P': digit = '7'; negative = true; break;
            case 'Q': digit = '8'; negative = true; break;
            case 'R': digit = '9'; negative = true; break;
            default:
                throw new IllegalArgumentException(
                        "Invalid zoned-decimal overpunch character '" + last + "' at end of '"
                                + overpunched + "'");
        }
        String digits = overpunched.substring(0, overpunched.length() - 1) + digit;
        return negative ? "-" + digits : digits;
    }

    /**
     * Extracts a COBOL {@code S9(n)V99} zoned-decimal money/rate field and returns it as a scaled
     * {@link BigDecimal} (PR-16).
     *
     * <p>The field is extracted (and trimmed) via {@link FixedWidthRecordParser#parseString}, its
     * trailing sign overpunch is decoded by {@link #decodeZonedDecimalSign(String)}, the implied
     * decimal point is positioned with {@link BigDecimal#movePointLeft(int)}, and the target scale is
     * enforced with {@link RoundingMode#HALF_UP} (matching the COBOL {@code ROUNDED} clause). The
     * exact-decimal {@link BigDecimal#BigDecimal(String)} constructor is used &mdash; never a
     * {@code double}-based factory.</p>
     *
     * <p>Examples: {@code "00000001940{"} &rarr; {@code 194.00}; {@code "00150{"} &rarr; {@code 15.00}.</p>
     *
     * @param line   the full fixed-width record line
     * @param offset zero-based start of the field
     * @param length field length including the trailing overpunch character
     * @param scale  implied decimal scale (typically {@link #MONEY_SCALE})
     * @return the parsed {@link BigDecimal}, or {@code null} if the field is blank/out of bounds
     */
    private static BigDecimal parseMoney(String line, int offset, int length, int scale) {
        String raw = FixedWidthRecordParser.parseString(line, offset, length);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String decoded = decodeZonedDecimalSign(raw);
        return new BigDecimal(decoded).movePointLeft(scale).setScale(scale, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------------
    // Step beans (one per ASCII fixture; PR-23 dependency-aware order)
    // ------------------------------------------------------------------------

    /**
     * Step 1 &mdash; loads {@link TransactionType} reference data from {@code trantype.txt}
     * (7 records, 60-byte {@code CVTRA03Y} layout: {@code TRAN-TYPE PIC X(02)} +
     * {@code TRAN-TYPE-DESC PIC X(50)}). Replaces {@code app/jcl/TRANTYPE.jcl}.
     *
     * @return the configured {@link Step}
     */
    @Bean
    public Step loadTransactionTypesStep() {
        return buildStep("loadTransactionTypesStep",
                (StepContribution contribution, ChunkContext chunkContext) -> {
            long existing = transactionTypeRepository.count();
            if (existing > 0) {
                log.info("loadTransactionTypesStep: transaction_types already populated (count={}); "
                        + "skipping seed load", existing);
                return RepeatStatus.FINISHED;
            }
            List<String> lines = readFixtureLines("trantype.txt");
            List<TransactionType> entities = new ArrayList<>(lines.size());
            for (String line : lines) {
                if (line.length() < 52) {
                    log.warn("Skipping short trantype.txt record (len={} < 52): '{}'", line.length(), line);
                    continue;
                }
                TransactionType e = new TransactionType();
                e.setTypeCd(FixedWidthRecordParser.parseString(line, 0, 2));
                e.setTypeDesc(FixedWidthRecordParser.parseString(line, 2, 50));
                entities.add(e);
            }
            transactionTypeRepository.saveAll(entities);
            contribution.incrementWriteCount(entities.size());
            log.info("loadTransactionTypesStep: loaded {} TransactionType record(s) from trantype.txt",
                    entities.size());
            return RepeatStatus.FINISHED;
        });
    }

    /**
     * Step 2 &mdash; loads {@link TransactionCategory} reference data from {@code trancatg.txt}
     * (18 records, 60-byte {@code CVTRA04Y} layout: {@code TRAN-TYPE-CD PIC X(02)} +
     * {@code TRAN-CAT-CD PIC 9(04)} + {@code TRAN-CAT-TYPE-DESC PIC X(50)}). The four-character
     * category code is preserved as a zero-padded {@link String} (composite-key fidelity, PR-15).
     * Replaces {@code app/jcl/TRANCATG.jcl}.
     *
     * @return the configured {@link Step}
     */
    @Bean
    public Step loadTransactionCategoriesStep() {
        return buildStep("loadTransactionCategoriesStep",
                (StepContribution contribution, ChunkContext chunkContext) -> {
            long existing = transactionCategoryRepository.count();
            if (existing > 0) {
                log.info("loadTransactionCategoriesStep: transaction_categories already populated "
                        + "(count={}); skipping seed load", existing);
                return RepeatStatus.FINISHED;
            }
            List<String> lines = readFixtureLines("trancatg.txt");
            List<TransactionCategory> entities = new ArrayList<>(lines.size());
            for (String line : lines) {
                if (line.length() < 56) {
                    log.warn("Skipping short trancatg.txt record (len={} < 56): '{}'", line.length(), line);
                    continue;
                }
                TransactionCategoryId id = new TransactionCategoryId();
                id.setTypeCd(FixedWidthRecordParser.parseString(line, 0, 2));
                id.setCategoryCd(FixedWidthRecordParser.parseString(line, 2, 4));
                TransactionCategory e = new TransactionCategory();
                e.setId(id);
                e.setCategoryDesc(FixedWidthRecordParser.parseString(line, 6, 50));
                entities.add(e);
            }
            transactionCategoryRepository.saveAll(entities);
            contribution.incrementWriteCount(entities.size());
            log.info("loadTransactionCategoriesStep: loaded {} TransactionCategory record(s) "
                    + "from trancatg.txt", entities.size());
            return RepeatStatus.FINISHED;
        });
    }

    /**
     * Step 3 &mdash; loads {@link DisclosureGroup} reference data from {@code discgrp.txt}
     * (51 records, 50-byte {@code CVTRA02Y} layout: {@code DIS-ACCT-GROUP-ID PIC X(10)} +
     * {@code DIS-TRAN-TYPE-CD PIC X(02)} + {@code DIS-TRAN-CAT-CD PIC 9(04)} +
     * {@code DIS-INT-RATE PIC S9(04)V99}). The interest rate is a zoned-decimal money field decoded
     * to {@link BigDecimal} scale 2 (PR-16). The fixture contains the {@code A000000000}, the
     * {@code DEFAULT} and the zero-APR groups; the {@code DEFAULT} group is the fallback target used
     * by the interest-calculation lookup (PR-02). Replaces {@code app/jcl/DISCGRP.jcl}.
     *
     * @return the configured {@link Step}
     */
    @Bean
    public Step loadDisclosureGroupsStep() {
        return buildStep("loadDisclosureGroupsStep",
                (StepContribution contribution, ChunkContext chunkContext) -> {
            long existing = disclosureGroupRepository.count();
            if (existing > 0) {
                log.info("loadDisclosureGroupsStep: disclosure_groups already populated (count={}); "
                        + "skipping seed load", existing);
                return RepeatStatus.FINISHED;
            }
            List<String> lines = readFixtureLines("discgrp.txt");
            List<DisclosureGroup> entities = new ArrayList<>(lines.size());
            for (String line : lines) {
                if (line.length() < 22) {
                    log.warn("Skipping short discgrp.txt record (len={} < 22): '{}'", line.length(), line);
                    continue;
                }
                DisclosureGroupId id = new DisclosureGroupId();
                id.setAccountGroupId(FixedWidthRecordParser.parseString(line, 0, 10));
                id.setTranTypeCd(FixedWidthRecordParser.parseString(line, 10, 2));
                id.setTranCatCd(FixedWidthRecordParser.parseString(line, 12, 4));
                DisclosureGroup e = new DisclosureGroup();
                e.setId(id);
                e.setDisIntRate(parseMoney(line, 16, 6, MONEY_SCALE));
                entities.add(e);
            }
            disclosureGroupRepository.saveAll(entities);
            contribution.incrementWriteCount(entities.size());
            log.info("loadDisclosureGroupsStep: loaded {} DisclosureGroup record(s) from discgrp.txt",
                    entities.size());
            return RepeatStatus.FINISHED;
        });
    }

    /**
     * Step 4 &mdash; loads {@link Customer} master data from {@code custdata.txt} (50 records,
     * 500-byte {@code CVCUS01Y} layout). The date of birth ({@code CUST-DOB-YYYY-MM-DD PIC X(10)}) is
     * parsed to {@link java.time.LocalDate}; all other fields are alphanumeric or unsigned numeric.
     * Replaces {@code app/jcl/CUSTFILE.jcl}. First link in the FK chain (PR-23).
     *
     * @return the configured {@link Step}
     */
    @Bean
    public Step loadCustomersStep() {
        return buildStep("loadCustomersStep",
                (StepContribution contribution, ChunkContext chunkContext) -> {
            long existing = customerRepository.count();
            if (existing > 0) {
                log.info("loadCustomersStep: customers already populated (count={}); skipping seed load",
                        existing);
                return RepeatStatus.FINISHED;
            }
            List<String> lines = readFixtureLines("custdata.txt");
            List<Customer> entities = new ArrayList<>(lines.size());
            for (String line : lines) {
                if (line.length() < 332) {
                    log.warn("Skipping short custdata.txt record (len={} < 332): '{}'", line.length(), line);
                    continue;
                }
                Customer e = new Customer();
                e.setCustId(FixedWidthRecordParser.parseLong(line, 0, 9));
                e.setFirstName(FixedWidthRecordParser.parseString(line, 9, 25));
                e.setMiddleName(FixedWidthRecordParser.parseString(line, 34, 25));
                e.setLastName(FixedWidthRecordParser.parseString(line, 59, 25));
                e.setAddrLine1(FixedWidthRecordParser.parseString(line, 84, 50));
                e.setAddrLine2(FixedWidthRecordParser.parseString(line, 134, 50));
                e.setAddrLine3(FixedWidthRecordParser.parseString(line, 184, 50));
                e.setStateCd(FixedWidthRecordParser.parseString(line, 234, 2));
                e.setCountryCd(FixedWidthRecordParser.parseString(line, 236, 3));
                e.setZipCd(FixedWidthRecordParser.parseString(line, 239, 10));
                e.setPhoneNum1(FixedWidthRecordParser.parseString(line, 249, 15));
                e.setPhoneNum2(FixedWidthRecordParser.parseString(line, 264, 15));
                e.setSsn(FixedWidthRecordParser.parseString(line, 279, 9));
                e.setGovtIssuedId(FixedWidthRecordParser.parseString(line, 288, 20));
                e.setDob(FixedWidthRecordParser.parseDate(line, 308, 10, ISO_DATE_PATTERN));
                e.setEftAccountId(FixedWidthRecordParser.parseString(line, 318, 10));
                e.setPrimaryCardHolderInd(FixedWidthRecordParser.parseString(line, 328, 1));
                e.setFicoScore(FixedWidthRecordParser.parseInteger(line, 329, 3));
                entities.add(e);
            }
            customerRepository.saveAll(entities);
            contribution.incrementWriteCount(entities.size());
            log.info("loadCustomersStep: loaded {} Customer record(s) from custdata.txt", entities.size());
            return RepeatStatus.FINISHED;
        });
    }

    /**
     * Step 5 &mdash; loads {@link Account} master data from {@code acctdata.txt} (50 records,
     * 300-byte {@code CVACT01Y} layout). The five {@code PIC S9(10)V99} money fields ({@code currBal},
     * {@code creditLimit}, {@code cashCreditLimit}, {@code currCycCredit}, {@code currCycDebit}) are
     * zoned-decimal and decoded to {@link BigDecimal} scale 2 (PR-16); the three {@code PIC X(10)}
     * dates are parsed to {@link java.time.LocalDate}. The blank {@code ACCT-GROUP-ID} of the sample
     * accounts maps to an empty group reference (interest lookup then uses the {@code DEFAULT}
     * fallback, PR-02). Replaces {@code app/jcl/ACCTFILE.jcl}.
     *
     * @return the configured {@link Step}
     */
    @Bean
    public Step loadAccountsStep() {
        return buildStep("loadAccountsStep",
                (StepContribution contribution, ChunkContext chunkContext) -> {
            long existing = accountRepository.count();
            if (existing > 0) {
                log.info("loadAccountsStep: accounts already populated (count={}); skipping seed load",
                        existing);
                return RepeatStatus.FINISHED;
            }
            List<String> lines = readFixtureLines("acctdata.txt");
            List<Account> entities = new ArrayList<>(lines.size());
            for (String line : lines) {
                if (line.length() < 122) {
                    log.warn("Skipping short acctdata.txt record (len={} < 122): '{}'", line.length(), line);
                    continue;
                }
                Account e = new Account();
                e.setAcctId(FixedWidthRecordParser.parseLong(line, 0, 11));
                e.setActiveStatus(FixedWidthRecordParser.parseString(line, 11, 1));
                e.setCurrBal(parseMoney(line, 12, 12, MONEY_SCALE));
                e.setCreditLimit(parseMoney(line, 24, 12, MONEY_SCALE));
                e.setCashCreditLimit(parseMoney(line, 36, 12, MONEY_SCALE));
                e.setOpenDate(FixedWidthRecordParser.parseDate(line, 48, 10, ISO_DATE_PATTERN));
                e.setExpirationDate(FixedWidthRecordParser.parseDate(line, 58, 10, ISO_DATE_PATTERN));
                e.setReissueDate(FixedWidthRecordParser.parseDate(line, 68, 10, ISO_DATE_PATTERN));
                e.setCurrCycCredit(parseMoney(line, 78, 12, MONEY_SCALE));
                e.setCurrCycDebit(parseMoney(line, 90, 12, MONEY_SCALE));
                e.setAddrZip(FixedWidthRecordParser.parseString(line, 102, 10));
                e.setGroupId(FixedWidthRecordParser.parseString(line, 112, 10));
                entities.add(e);
            }
            accountRepository.saveAll(entities);
            contribution.incrementWriteCount(entities.size());
            log.info("loadAccountsStep: loaded {} Account record(s) from acctdata.txt", entities.size());
            return RepeatStatus.FINISHED;
        });
    }

    /**
     * Step 6 &mdash; loads {@link Card} master data from {@code carddata.txt} (50 records, 150-byte
     * {@code CVACT02Y} layout). {@code CARD-CVV-CD PIC 9(03)} maps to a {@link Short} (parsed
     * null-safely from the unsigned 3-digit field) and {@code CARD-EXPIRAION-DATE PIC X(10)} to
     * {@link java.time.LocalDate}. {@code CARD-ACCT-ID} establishes the FK to {@link Account}.
     * Replaces {@code app/jcl/CARDFILE.jcl}.
     *
     * @return the configured {@link Step}
     */
    @Bean
    public Step loadCardsStep() {
        return buildStep("loadCardsStep",
                (StepContribution contribution, ChunkContext chunkContext) -> {
            long existing = cardRepository.count();
            if (existing > 0) {
                log.info("loadCardsStep: cards already populated (count={}); skipping seed load", existing);
                return RepeatStatus.FINISHED;
            }
            List<String> lines = readFixtureLines("carddata.txt");
            List<Card> entities = new ArrayList<>(lines.size());
            for (String line : lines) {
                if (line.length() < 91) {
                    log.warn("Skipping short carddata.txt record (len={} < 91): '{}'", line.length(), line);
                    continue;
                }
                Card e = new Card();
                e.setCardNum(FixedWidthRecordParser.parseString(line, 0, 16));
                e.setAccountId(FixedWidthRecordParser.parseLong(line, 16, 11));
                // CARD-CVV-CD PIC 9(03) -> Short; leave null only when the field is blank/absent.
                Integer cvv = FixedWidthRecordParser.parseInteger(line, 27, 3);
                if (cvv != null) {
                    e.setCvvCd(cvv.shortValue());
                }
                e.setEmbossedName(FixedWidthRecordParser.parseString(line, 30, 50));
                e.setExpirationDate(FixedWidthRecordParser.parseDate(line, 80, 10, ISO_DATE_PATTERN));
                e.setActiveStatus(FixedWidthRecordParser.parseString(line, 90, 1));
                entities.add(e);
            }
            cardRepository.saveAll(entities);
            contribution.incrementWriteCount(entities.size());
            log.info("loadCardsStep: loaded {} Card record(s) from carddata.txt", entities.size());
            return RepeatStatus.FINISHED;
        });
    }

    /**
     * Step 7 &mdash; loads {@link CardXref} junction data from {@code cardxref.txt} (50 records,
     * 36-byte usable {@code CVACT03Y} layout: {@code XREF-CARD-NUM PIC X(16)} +
     * {@code XREF-CUST-ID PIC 9(09)} + {@code XREF-ACCT-ID PIC 9(11)}). This is the card&rarr;customer
     * &rarr;account cross-reference and is loaded after all three of those masters exist (PR-23).
     * Replaces {@code app/jcl/XREFFILE.jcl}.
     *
     * @return the configured {@link Step}
     */
    @Bean
    public Step loadCardXrefsStep() {
        return buildStep("loadCardXrefsStep",
                (StepContribution contribution, ChunkContext chunkContext) -> {
            long existing = cardXrefRepository.count();
            if (existing > 0) {
                log.info("loadCardXrefsStep: card_xref already populated (count={}); skipping seed load",
                        existing);
                return RepeatStatus.FINISHED;
            }
            List<String> lines = readFixtureLines("cardxref.txt");
            List<CardXref> entities = new ArrayList<>(lines.size());
            for (String line : lines) {
                if (line.length() < 36) {
                    log.warn("Skipping short cardxref.txt record (len={} < 36): '{}'", line.length(), line);
                    continue;
                }
                CardXref e = new CardXref();
                e.setXrefCardNum(FixedWidthRecordParser.parseString(line, 0, 16));
                e.setCustId(FixedWidthRecordParser.parseLong(line, 16, 9));
                e.setAccountId(FixedWidthRecordParser.parseLong(line, 25, 11));
                entities.add(e);
            }
            cardXrefRepository.saveAll(entities);
            contribution.incrementWriteCount(entities.size());
            log.info("loadCardXrefsStep: loaded {} CardXref record(s) from cardxref.txt", entities.size());
            return RepeatStatus.FINISHED;
        });
    }

    /**
     * Step 8 &mdash; loads {@link TransactionCategoryBalance} data from {@code tcatbal.txt}
     * (50 records, 50-byte {@code CVTRA01Y} layout: {@code TRANCAT-ACCT-ID PIC 9(11)} +
     * {@code TRANCAT-TYPE-CD PIC X(02)} + {@code TRANCAT-CD PIC 9(04)} +
     * {@code TRAN-CAT-BAL PIC S9(09)V99}). The composite key is account + type + category (PR-15);
     * the balance is a zoned-decimal money field decoded to {@link BigDecimal} scale 2 (PR-16).
     * Replaces {@code app/jcl/TCATBALF.jcl}.
     *
     * @return the configured {@link Step}
     */
    @Bean
    public Step loadTcatbalsStep() {
        return buildStep("loadTcatbalsStep",
                (StepContribution contribution, ChunkContext chunkContext) -> {
            long existing = transactionCategoryBalanceRepository.count();
            if (existing > 0) {
                log.info("loadTcatbalsStep: tran_cat_balances already populated (count={}); "
                        + "skipping seed load", existing);
                return RepeatStatus.FINISHED;
            }
            List<String> lines = readFixtureLines("tcatbal.txt");
            List<TransactionCategoryBalance> entities = new ArrayList<>(lines.size());
            for (String line : lines) {
                if (line.length() < 28) {
                    log.warn("Skipping short tcatbal.txt record (len={} < 28): '{}'", line.length(), line);
                    continue;
                }
                TransactionCategoryBalanceId id = new TransactionCategoryBalanceId();
                id.setAccountId(FixedWidthRecordParser.parseLong(line, 0, 11));
                id.setTypeCd(FixedWidthRecordParser.parseString(line, 11, 2));
                id.setCategoryCd(FixedWidthRecordParser.parseString(line, 13, 4));
                TransactionCategoryBalance e = new TransactionCategoryBalance();
                e.setId(id);
                e.setTranCatBal(parseMoney(line, 17, 11, MONEY_SCALE));
                entities.add(e);
            }
            transactionCategoryBalanceRepository.saveAll(entities);
            contribution.incrementWriteCount(entities.size());
            log.info("loadTcatbalsStep: loaded {} TransactionCategoryBalance record(s) from tcatbal.txt",
                    entities.size());
            return RepeatStatus.FINISHED;
        });
    }

    /**
     * Step 9 &mdash; deliberate no-op corresponding to {@code app/jcl/TRANFILE.jcl}. This
     * distribution ships no transaction seed fixture; the {@code transactions} table is populated by
     * the {@code POSTTRAN} job ({@code TransactionPostingJobConfig}). The step is retained to
     * preserve the one-to-one JCL correspondence and logs the current transaction count for operator
     * visibility (also exercising the injected {@link TransactionRepository}).
     *
     * @return the configured {@link Step}
     */
    @Bean
    public Step loadTransactionsStep() {
        return buildStep("loadTransactionsStep",
                (StepContribution contribution, ChunkContext chunkContext) -> {
            log.info("loadTransactionsStep: no transaction seed fixture; transactions are populated by "
                    + "POSTTRAN (TransactionPostingJobConfig). Current transaction count={}",
                    transactionRepository.count());
            return RepeatStatus.FINISHED;
        });
    }

    // ------------------------------------------------------------------------
    // Job bean
    // ------------------------------------------------------------------------

    /**
     * The composite seed-loading {@link Job}, chaining all nine steps in dependency-aware order
     * (PR-23): reference data first ({@code TransactionType} &rarr; {@code TransactionCategory} &rarr;
     * {@code DisclosureGroup}), then master data in FK order ({@code Customer} &rarr; {@code Account}
     * &rarr; {@code Card} &rarr; {@code CardXref}), then {@code TransactionCategoryBalance}, then the
     * {@code Transaction} no-op. Registered under {@link #JOB_NAME} by the {@code BatchConfig}
     * {@code JobRegistryBeanPostProcessor} for launch via the {@code BatchAdminController}.
     *
     * @return the fully built {@link Job}
     */
    @Bean
    public Job dataInitializationJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(loadTransactionTypesStep())
                .next(loadTransactionCategoriesStep())
                .next(loadDisclosureGroupsStep())
                .next(loadCustomersStep())
                .next(loadAccountsStep())
                .next(loadCardsStep())
                .next(loadCardXrefsStep())
                .next(loadTcatbalsStep())
                .next(loadTransactionsStep())
                .build();
    }
}
