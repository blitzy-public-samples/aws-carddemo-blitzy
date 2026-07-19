package com.aws.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Offline, deterministic unit tests for {@link CategoryBalancePrintJobConfig}.
 *
 * <p>These tests are the executable parity oracle for the {@code PRTCATBL.jcl} {@code STEP10R}
 * {@code SORT}/{@code OUTREC}/{@code EDIT} translation. They exercise the two pure, package-private
 * static formatting methods ({@link CategoryBalancePrintJobConfig#formatEditedBalance(BigDecimal)}
 * and {@link CategoryBalancePrintJobConfig#toReportLine(TransactionCategoryBalance)}) and the Spring
 * Batch bean wiring, with <strong>no database and no container</strong> — the whole parity contract
 * (exact 40-byte report layout, the {@code EDIT=(TTTTTTTTT.TT)} balance mask including negative and
 * zero balances, and the field byte positions) is validated purely in-process.</p>
 *
 * <p>The canonical {@code PRTCATBL} {@code OUTREC} layout being asserted is, for a row
 * {@code (acctId=99, typeCd="01", catCd=5, balance=504.77)}:</p>
 *
 * <pre>
 * col: 0         1         2         3
 *      0123456789012345678901234567890123456789
 *      00000000099 01 0005 000000504.77        
 *      |          | |  |    |           |      |
 *      +acctId(11) +type +cat +balance(12) +pad(8)
 * </pre>
 *
 * <p>The DFSORT {@code EDIT=(TTTTTTTTT.TT)} mask uses all {@code T} digit positions (significant,
 * i.e. leading zeros are retained — no zero suppression) and no {@code S} (so no sign is emitted and
 * the magnitude is shown). {@code TRAN-CAT-BAL} is {@code PIC S9(09)V99} with no {@code ROUNDED}
 * phrase, so surplus fractional digits are truncated toward zero.</p>
 *
 * @see CategoryBalancePrintJobConfig
 */
class CategoryBalancePrintJobConfigTest {

    /** Width of every emitted report record — {@code PRTCATBL.jcl} {@code SORTOUT LRECL=40}. */
    private static final int REPORT_WIDTH = 40;

    /** Builds a {@link TransactionCategoryBalance} row for the formatting assertions. */
    private static TransactionCategoryBalance row(long acctId, String typeCd, int catCd,
            String balance) {
        TransactionCategoryBalance record = new TransactionCategoryBalance();
        record.setAcctId(acctId);
        record.setTypeCd(typeCd);
        record.setCatCd(catCd);
        record.setBalance(balance == null ? null : new BigDecimal(balance));
        return record;
    }

    @Nested
    @DisplayName("formatEditedBalance: DFSORT EDIT=(TTTTTTTTT.TT) mask parity")
    class FormatEditedBalanceTests {

        @Test
        @DisplayName("zero balance renders all-zero magnitude with retained leading zeros")
        void zeroBalance() {
            assertThat(CategoryBalancePrintJobConfig.formatEditedBalance(new BigDecimal("0.00")))
                    .isEqualTo("000000000.00");
        }

        @Test
        @DisplayName("null balance is treated as zero (CobolDecimal.nullToZero)")
        void nullBalance() {
            assertThat(CategoryBalancePrintJobConfig.formatEditedBalance(null))
                    .isEqualTo("000000000.00");
        }

        @Test
        @DisplayName("typical positive balance retains all leading zeros (all-T mask)")
        void typicalPositive() {
            assertThat(CategoryBalancePrintJobConfig.formatEditedBalance(new BigDecimal("504.77")))
                    .isEqualTo("000000504.77");
        }

        @Test
        @DisplayName("negative balance drops the sign (no S in mask) and shows magnitude")
        void negativeDropsSign() {
            assertThat(CategoryBalancePrintJobConfig.formatEditedBalance(new BigDecimal("-504.77")))
                    .isEqualTo("000000504.77");
        }

        @Test
        @DisplayName("sub-dollar positive balance renders correctly")
        void smallPositive() {
            assertThat(CategoryBalancePrintJobConfig.formatEditedBalance(new BigDecimal("0.01")))
                    .isEqualTo("000000000.01");
        }

        @Test
        @DisplayName("sub-dollar negative balance drops sign")
        void smallNegative() {
            assertThat(CategoryBalancePrintJobConfig.formatEditedBalance(new BigDecimal("-0.01")))
                    .isEqualTo("000000000.01");
        }

        @Test
        @DisplayName("positive value with >2 fraction digits truncates toward zero (no ROUNDED)")
        void truncatesPositive() {
            assertThat(CategoryBalancePrintJobConfig.formatEditedBalance(new BigDecimal("504.779")))
                    .isEqualTo("000000504.77");
        }

        @Test
        @DisplayName("negative value with >2 fraction digits truncates toward zero then drops sign")
        void truncatesNegative() {
            assertThat(CategoryBalancePrintJobConfig.formatEditedBalance(new BigDecimal("-504.779")))
                    .isEqualTo("000000504.77");
        }

        @Test
        @DisplayName("value with scale < 2 is padded to two fraction digits")
        void scaleLessThanTwo() {
            assertThat(CategoryBalancePrintJobConfig.formatEditedBalance(new BigDecimal("5")))
                    .isEqualTo("000000005.00");
        }

        @Test
        @DisplayName("maximum in-range magnitude S9(9)V99 fills all nine integer digits")
        void maximumMagnitude() {
            assertThat(CategoryBalancePrintJobConfig
                    .formatEditedBalance(new BigDecimal("999999999.99")))
                    .isEqualTo("999999999.99");
        }

        @Test
        @DisplayName("edited balance is always exactly twelve characters wide")
        void alwaysTwelveWide() {
            assertThat(CategoryBalancePrintJobConfig.formatEditedBalance(new BigDecimal("0.00")))
                    .hasSize(12);
            assertThat(CategoryBalancePrintJobConfig
                    .formatEditedBalance(new BigDecimal("999999999.99")))
                    .hasSize(12);
        }

        @Test
        @DisplayName("integer part exceeding nine digits fails fast (cannot fit the EDIT field)")
        void overflowThrows() {
            assertThatThrownBy(() -> CategoryBalancePrintJobConfig
                    .formatEditedBalance(new BigDecimal("1000000000.00")))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("toReportLine: exact 40-byte PRTCATBL OUTREC layout")
    class ToReportLineTests {

        @Test
        @DisplayName("canonical row produces the exact 40-byte report record")
        void canonicalRow() {
            String line = CategoryBalancePrintJobConfig.toReportLine(row(99L, "01", 5, "504.77"));
            assertThat(line).isEqualTo("00000000099 01 0005 000000504.77        ");
            assertThat(line).hasSize(REPORT_WIDTH);
        }

        @Test
        @DisplayName("every field occupies its exact byte range")
        void fieldByteRanges() {
            String line = CategoryBalancePrintJobConfig.toReportLine(row(99L, "01", 5, "504.77"));
            assertThat(line).hasSize(REPORT_WIDTH);
            assertThat(line.substring(0, 11)).as("acctId, zero-padded 11").isEqualTo("00000000099");
            assertThat(line.charAt(11)).as("separator after acctId").isEqualTo(' ');
            assertThat(line.substring(12, 14)).as("typeCd, 2 chars").isEqualTo("01");
            assertThat(line.charAt(14)).as("separator after typeCd").isEqualTo(' ');
            assertThat(line.substring(15, 19)).as("catCd, zero-padded 4").isEqualTo("0005");
            assertThat(line.charAt(19)).as("separator after catCd").isEqualTo(' ');
            assertThat(line.substring(20, 32)).as("edited balance, 12 chars").isEqualTo("000000504.77");
            assertThat(line.substring(32, 40)).as("trailing pad, 8 blanks").isEqualTo("        ");
        }

        @Test
        @DisplayName("negative balance row stays 40 bytes and shows magnitude only")
        void negativeBalanceRow() {
            String line = CategoryBalancePrintJobConfig.toReportLine(row(1L, "99", 9999, "-12.34"));
            assertThat(line).hasSize(REPORT_WIDTH);
            assertThat(line.substring(0, 11)).isEqualTo("00000000001");
            assertThat(line.substring(12, 14)).isEqualTo("99");
            assertThat(line.substring(15, 19)).isEqualTo("9999");
            assertThat(line.substring(20, 32)).isEqualTo("000000012.34");
            assertThat(line.substring(32, 40)).isEqualTo("        ");
        }

        @Test
        @DisplayName("zero balance row stays 40 bytes with all-zero balance field")
        void zeroBalanceRow() {
            String line = CategoryBalancePrintJobConfig
                    .toReportLine(row(12_345_678_901L, "AA", 42, "0.00"));
            assertThat(line).hasSize(REPORT_WIDTH);
            assertThat(line.substring(0, 11)).isEqualTo("12345678901");
            assertThat(line.substring(12, 14)).isEqualTo("AA");
            assertThat(line.substring(15, 19)).isEqualTo("0042");
            assertThat(line.substring(20, 32)).isEqualTo("000000000.00");
        }

        @Test
        @DisplayName("null typeCd is rendered as two blanks, preserving the fixed layout")
        void nullTypeCode() {
            String line = CategoryBalancePrintJobConfig.toReportLine(row(7L, null, 1, "1.00"));
            assertThat(line).hasSize(REPORT_WIDTH);
            assertThat(line.substring(12, 14)).isEqualTo("  ");
            assertThat(line.substring(20, 32)).isEqualTo("000000001.00");
        }

        @Test
        @DisplayName("maximum balance row fills the balance field with nine integer digits")
        void maximumBalanceRow() {
            String line = CategoryBalancePrintJobConfig
                    .toReportLine(row(11_111_111_111L, "ZZ", 1234, "999999999.99"));
            assertThat(line).hasSize(REPORT_WIDTH);
            assertThat(line.substring(20, 32)).isEqualTo("999999999.99");
        }
    }

    @Nested
    @DisplayName("Spring Batch bean wiring")
    class BeanWiringTests {

        private CategoryBalancePrintJobConfig newConfig() {
            JobRepository jobRepository = mock(JobRepository.class);
            PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
            TransactionCategoryBalanceRepository repository =
                    mock(TransactionCategoryBalanceRepository.class);
            return new CategoryBalancePrintJobConfig(jobRepository, transactionManager, repository);
        }

        @Test
        @DisplayName("report record length constant matches PRTCATBL SORTOUT LRECL=40")
        void reportRecordLengthConstant() {
            assertThat(CategoryBalancePrintJobConfig.REPORT_RECORD_LENGTH).isEqualTo(REPORT_WIDTH);
        }

        @Test
        @DisplayName("reader bean is built over the repository")
        void readerBean() {
            RepositoryItemReader<TransactionCategoryBalance> reader =
                    newConfig().categoryBalanceReportReader();
            assertThat(reader).isNotNull();
        }

        @Test
        @DisplayName("writer bean is built for a supplied output path")
        void writerBean() {
            String outputPath = System.getProperty("java.io.tmpdir")
                    + "/blitzy_adhoc_test_categorybalance_writer.rept";
            FlatFileItemWriter<String> writer = newConfig().categoryBalanceReportWriter(outputPath);
            assertThat(writer).isNotNull();
        }

        @Test
        @DisplayName("processor bean formats a row into a 40-byte report line")
        void processorBean() throws Exception {
            ItemProcessor<TransactionCategoryBalance, String> processor =
                    newConfig().categoryBalanceReportProcessor();
            assertThat(processor).isNotNull();
            String line = processor.process(row(99L, "01", 5, "504.77"));
            assertThat(line).isEqualTo("00000000099 01 0005 000000504.77        ");
        }

        @Test
        @DisplayName("step bean is named categoryBalancePrintStep")
        void stepBean() {
            Step step = newConfig().categoryBalancePrintStep();
            assertThat(step).isNotNull();
            assertThat(step.getName()).isEqualTo("categoryBalancePrintStep");
        }

        @Test
        @DisplayName("job bean is named categoryBalancePrintJob")
        void jobBean() {
            Job job = newConfig().categoryBalancePrintJob();
            assertThat(job).isNotNull();
            assertThat(job.getName()).isEqualTo("categoryBalancePrintJob");
        }
    }
}
