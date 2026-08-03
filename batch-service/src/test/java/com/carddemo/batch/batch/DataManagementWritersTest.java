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
package com.carddemo.batch.batch;

import com.carddemo.common.batch.BatchOutputPathResolver;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.carddemo.batch.testsupport.AsciiFixtures;
import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.TranCatBal;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/**
 * Unit tests for the data-management writers.
 *
 * :purpose: Verify the printed output of the three writers the data-management
 *     jobs use - :class:`RecordDumpItemWriter` (the ``CBACT01C``/``CBACT02C``/
 *     ``CBACT03C``/``CBCUS01C`` dumps with their ``START/END OF EXECUTION OF
 *     PROGRAM`` banners), :class:`CategoryBalanceReportWriter` (the ``PRTCATBL``
 *     report line) and :class:`LoggingItemWriter` (the count-only writer of the
 *     validation job) - by writing real files and asserting their exact content.
 * :output: JUnit 5 / AssertJ assertions over files in a JUnit temporary directory
 *     plus a Logback ``ListAppender``; no Spring context and no database.
 */
class DataManagementWritersTest {

    @TempDir
    private Path tempDir;

    private BatchOutputPathResolver pathResolver;

    @BeforeEach
    void createResolver() throws IOException {
        Path outputRoot = Files.createDirectories(tempDir.resolve("out"));
        Path inputRoot = Files.createDirectories(tempDir.resolve("in"));
        pathResolver = new BatchOutputPathResolver(outputRoot.toString(), inputRoot.toString());
    }

    /**
     * Builds the account whose fields the first ``acctdata.txt`` record carries.
     *
     * :output: the account under test.
     */
    private static Account firstFixtureAccount() {
        String line = AsciiFixtures.lines("acctdata.txt").get(0);
        Account account = new Account();
        account.setAcctId(Long.valueOf(line.substring(0, 11)));
        account.setAcctActiveStatus(line.substring(11, 12));
        account.setAcctCurrBal(AsciiFixtures.decodeZoned(line.substring(12, 24)));
        account.setAcctCreditLimit(AsciiFixtures.decodeZoned(line.substring(24, 36)));
        account.setAcctCashCreditLimit(AsciiFixtures.decodeZoned(line.substring(36, 48)));
        account.setAcctOpenDate(line.substring(48, 58));
        account.setAcctExpiraionDate(line.substring(58, 68));
        account.setAcctReissueDate(line.substring(68, 78));
        account.setAcctCurrCycCredit(AsciiFixtures.decodeZoned(line.substring(78, 90)));
        account.setAcctCurrCycDebit(AsciiFixtures.decodeZoned(line.substring(90, 102)));
        account.setAcctGroupId(line.substring(102, 112));
        return account;
    }

    @Nested
    @DisplayName("RecordDumpItemWriter: the CBACT01C-style labelled dump")
    class RecordDump {

        @Test
        @DisplayName("wraps the records between the START and END OF EXECUTION banners")
        void wrapsRecordsBetweenTheProgramBanners() throws Exception {
            Path output = pathResolver.resolveOutput("acctdump.txt");
            RecordDumpItemWriter<Account> writer = new RecordDumpItemWriter<>(
                    "accountDumpWriter", output,
                    "START OF EXECUTION OF PROGRAM CBACT01C",
                    "END OF EXECUTION OF PROGRAM CBACT01C",
                    CobolRecordFormatter::accountDump);

            writer.open(new ExecutionContext());
            try {
                Chunk<Account> chunk = new Chunk<>();
                chunk.add(firstFixtureAccount());
                writer.write(chunk);
                writer.update(new ExecutionContext());
            } finally {
                writer.close();
            }

            List<String> lines = Files.readAllLines(output, StandardCharsets.ISO_8859_1);
            assertThat(lines.get(0)).isEqualTo("START OF EXECUTION OF PROGRAM CBACT01C");
            // One record renders as eleven labelled lines plus the 49-hyphen separator.
            assertThat(lines.get(1)).startsWith("ACCT-ID");
            assertThat(lines.get(1)).contains(":00000000001");
            assertThat(lines.get(7)).startsWith("ACCT-EXPIRAION-DATE");
            assertThat(lines.get(12)).isEqualTo("-".repeat(49));
            assertThat(lines.get(13)).isEqualTo("END OF EXECUTION OF PROGRAM CBACT01C");
            assertThat(lines).hasSize(14);
        }

        @Test
        @DisplayName("writes multiple records in chunk order and logs the aggregate count on close")
        void writesEveryRecordAndLogsTheCount() throws Exception {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            ch.qos.logback.classic.Logger logger =
                    context.getLogger(RecordDumpItemWriter.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.setContext(context);
            appender.start();
            logger.addAppender(appender);
            logger.setLevel(Level.INFO);

            Path output = pathResolver.resolveOutput("multi.txt");
            RecordDumpItemWriter<Account> writer = new RecordDumpItemWriter<>(
                    "accountDumpWriter", output, "START", "END",
                    account -> "ACCT " + account.getAcctId());
            try {
                writer.open(new ExecutionContext());
                Chunk<Account> first = new Chunk<>();
                Account one = new Account();
                one.setAcctId(1L);
                Account two = new Account();
                two.setAcctId(2L);
                first.add(one);
                first.add(two);
                writer.write(first);
                writer.close();

                assertThat(Files.readAllLines(output, StandardCharsets.ISO_8859_1))
                        .containsExactly("START", "ACCT 1", "ACCT 2", "END");
                assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                        .anySatisfy(message -> assertThat(message)
                                .isEqualTo("accountDumpWriter wrote 2 record(s) to the dump output file"));
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
        }
    }

    @Nested
    @DisplayName("CategoryBalanceReportWriter: the PRTCATBL report line")
    class CategoryBalanceReport {

        /**
         * Runs the writer over the given balances and returns the produced lines.
         *
         * :param balances: the category-balance rows to render.
         * :output: the report lines in write order.
         */
        private List<String> render(TranCatBal... balances) throws Exception {
            CategoryBalanceReportWriter writer =
                    new CategoryBalanceReportWriter("tcatbal.rpt", pathResolver);
            writer.open(new ExecutionContext());
            try {
                Chunk<TranCatBal> chunk = new Chunk<>();
                for (TranCatBal balance : balances) {
                    chunk.add(balance);
                }
                writer.write(chunk);
                writer.update(new ExecutionContext());
            } finally {
                writer.close();
            }
            return Files.readAllLines(pathResolver.resolveOutput("tcatbal.rpt"),
                    StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("renders the account id, type, category and edited balance in fixed columns")
        void rendersTheReportLineInFixedColumns() throws Exception {
            List<String> lines = render(new TranCatBal(1L, "01", 5, new BigDecimal("1234.56")));

            assertThat(lines).hasSize(1);
            String line = lines.get(0);
            assertThat(line.substring(0, 11)).isEqualTo("00000000001");
            assertThat(line.charAt(11)).isEqualTo(' ');
            assertThat(line.substring(12, 14)).isEqualTo("01");
            assertThat(line.charAt(14)).isEqualTo(' ');
            assertThat(line.substring(15, 19)).isEqualTo("0005");
            assertThat(line.charAt(19)).isEqualTo(' ');
            assertThat(line.substring(20, 32)).isEqualTo("000001234.56");
            assertThat(line.substring(32)).isEqualTo(" ".repeat(9));
            assertThat(line).hasSize(41);
        }

        @Test
        @DisplayName("the edited balance is the zero-padded magnitude at exact scale two")
        void editedBalanceIsTheZeroPaddedMagnitude() throws Exception {
            List<String> lines = render(
                    new TranCatBal(2L, "02", 1, new BigDecimal("0.00")),
                    new TranCatBal(3L, "03", 2, new BigDecimal("-987.65")),
                    new TranCatBal(4L, "04", 3, new BigDecimal("999999999.99")),
                    new TranCatBal(5L, "05", 4, new BigDecimal("0.01")));

            assertThat(lines.get(0).substring(20, 32)).isEqualTo("000000000.00");
            // The legacy PIC 9(9).99 edit mask carries no sign position.
            assertThat(lines.get(1).substring(20, 32)).isEqualTo("000000987.65");
            assertThat(lines.get(2).substring(20, 32)).isEqualTo("999999999.99");
            assertThat(lines.get(3).substring(20, 32)).isEqualTo("000000000.01");
        }

        @Test
        @DisplayName("every committed tcatbal.txt row renders with the declared column layout")
        void everyCommittedRowRenders() throws Exception {
            List<String> fixtures = AsciiFixtures.lines("tcatbal.txt");
            assertThat(fixtures).isNotEmpty();

            TranCatBal[] balances = fixtures.stream()
                    .map(line -> new TranCatBal(
                            Long.valueOf(line.substring(0, 11)),
                            line.substring(11, 13),
                            Integer.valueOf(line.substring(13, 17)),
                            AsciiFixtures.decodeZoned(line.substring(17, 28))))
                    .toArray(TranCatBal[]::new);

            List<String> rendered = render(balances);

            assertThat(rendered).hasSameSizeAs(fixtures);
            for (int i = 0; i < rendered.size(); i++) {
                assertThat(rendered.get(i)).as("row %d", i).hasSize(41);
                assertThat(rendered.get(i).substring(0, 11))
                        .as("row %d account id", i)
                        .isEqualTo(fixtures.get(i).substring(0, 11));
                assertThat(rendered.get(i).substring(12, 14))
                        .as("row %d type code", i)
                        .isEqualTo(fixtures.get(i).substring(11, 13));
            }
        }

        @Test
        @DisplayName("a null balance renders as zero rather than failing")
        void nullBalanceRendersAsZero() throws Exception {
            assertThat(render(new TranCatBal(9L, "09", 9, null)).get(0).substring(20, 32))
                    .isEqualTo("000000000.00");
        }
    }

    @Nested
    @DisplayName("LoggingItemWriter: the count-only writer")
    class Logging {

        private ch.qos.logback.classic.Logger logger;

        private ListAppender<ILoggingEvent> appender;

        @BeforeEach
        void attachAppender() {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            logger = context.getLogger(LoggingItemWriter.class);
            appender = new ListAppender<>();
            appender.setContext(context);
            appender.start();
            logger.addAppender(appender);
            logger.setLevel(Level.INFO);
        }

        @AfterEach
        void detachAppender() {
            logger.detachAppender(appender);
            appender.stop();
        }

        @Test
        @DisplayName("logs the chunk size and the running total for each chunk")
        void logsTheChunkSizeAndRunningTotal() {
            LoggingItemWriter<String> writer = new LoggingItemWriter<>("daily transaction");

            writer.write(Chunk.of("a", "b"));
            writer.write(Chunk.of("c"));

            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly(
                            "Processed 2 daily transaction record(s); running total 2",
                            "Processed 1 daily transaction record(s); running total 3");
        }

        @Test
        @DisplayName("a null label falls back to the default 'record' wording")
        void nullLabelFallsBackToTheDefault() {
            new LoggingItemWriter<String>(null).write(Chunk.of("a"));

            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("Processed 1 record record(s); running total 1");
        }

        @Test
        @DisplayName("an empty chunk still reports the unchanged running total")
        void emptyChunkReportsTheUnchangedTotal() {
            LoggingItemWriter<String> writer = new LoggingItemWriter<>("account");

            writer.write(Chunk.of("a"));
            writer.write(new Chunk<>());

            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly(
                            "Processed 1 account record(s); running total 1",
                            "Processed 0 account record(s); running total 1");
        }
    }
}
