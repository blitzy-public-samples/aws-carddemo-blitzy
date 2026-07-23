package com.aws.carddemo.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.transaction.PlatformTransactionManager;

import com.aws.carddemo.domain.Card;
import com.aws.carddemo.repository.CardRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit tests for {@link CardPrintJobConfig}, the Spring Batch translation of COBOL {@code CBACT02C}
 * / JCL {@code READCARD} (read &amp; print the card master file).
 *
 * <p>These tests are deliberately self-contained: the Spring Batch collaborators
 * ({@link JobRepository}, {@link PlatformTransactionManager}, {@link CardRepository}) are Mockito
 * mocks so the bean-wiring, the {@code DISPLAY CARD-RECORD} logging behavior, and the
 * sensitive-data masking can all be verified with no database and no container. The end-to-end job
 * run against a real PostgreSQL (read count equals seeded count, batch status {@code COMPLETED}) is
 * exercised separately by the Testcontainers integration suite.</p>
 */
class CardPrintJobConfigTest {

    /** A representative card whose PAN ends in {@code 1111} and whose CVV is {@code 987}. */
    private static Card sampleCard() {
        return new Card(
                "4111111111111111",
                11111111111L,
                987,
                "JOHN Q CARDHOLDER",
                LocalDate.of(2027, 5, 31),
                "Y");
    }

    /** Builds the configuration under test with non-null mock collaborators. */
    private static CardPrintJobConfig newConfig() {
        JobRepository jobRepository = mock(JobRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        CardRepository cardRepository = mock(CardRepository.class);
        return new CardPrintJobConfig(jobRepository, transactionManager, cardRepository);
    }

    @Test
    void maskPan_masks_all_but_last_four_of_a_sixteen_digit_pan() {
        assertEquals("************1111", CardPrintJobConfig.maskPan("4111111111111111"));
    }

    @Test
    void maskPan_strips_padding_before_masking() {
        // A fixed-width CHAR(16) value arrives space-padded; the padding must be stripped first.
        assertEquals("****1234", CardPrintJobConfig.maskPan("55551234  "));
        assertEquals("****", CardPrintJobConfig.maskPan("  1234  "));
    }

    @Test
    void maskPan_null_renders_the_literal_null() {
        assertEquals("null", CardPrintJobConfig.maskPan(null));
    }

    @Test
    void maskPan_short_values_are_fully_masked_revealing_no_digit() {
        assertEquals("**", CardPrintJobConfig.maskPan("12"));
        assertEquals("****", CardPrintJobConfig.maskPan("1234"));
        assertEquals("", CardPrintJobConfig.maskPan("   "));
    }

    @Test
    void formatCardLine_masks_pan_omits_cvv_and_keeps_non_sensitive_fields() {
        String line = CardPrintJobConfig.formatCardLine(sampleCard());

        // PAN is masked to last four and the raw PAN never appears.
        assertTrue(line.contains("card-num=************1111"), line);
        assertFalse(line.contains("4111111111111111"), line);

        // The CVV value and any CVV label are never emitted (CWE-532).
        assertFalse(line.contains("987"), line);
        assertFalse(line.toLowerCase().contains("cvv"), line);

        // Non-sensitive fields are present for diagnostic value.
        assertTrue(line.contains("acct-id=11111111111"), line);
        assertTrue(line.contains("active-status=Y"), line);
        assertTrue(line.contains("expiry-date=2027-05-31"), line);
        assertTrue(line.contains("embossed-name=JOHN Q CARDHOLDER"), line);
    }

    @Test
    void writer_logs_exactly_one_masked_line_per_record() throws Exception {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(CardPrintJobConfig.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            ItemWriter<Card> writer = newConfig().cardPrintWriter();
            writer.write(Chunk.of(sampleCard(), sampleCard()));
        } finally {
            logbackLogger.detachAppender(appender);
        }

        // One line per record == the DISPLAY CARD-RECORD contract.
        assertEquals(2, appender.list.size());
        for (ILoggingEvent event : appender.list) {
            assertEquals(Level.INFO, event.getLevel());
            String message = event.getFormattedMessage();
            assertTrue(message.contains("************1111"), message);
            assertFalse(message.contains("4111111111111111"), message);
            assertFalse(message.contains("987"), message);
        }
    }

    @Test
    void reader_bean_is_a_repository_item_reader() {
        RepositoryItemReader<Card> reader = newConfig().cardPrintReader();
        assertNotNull(reader);
        assertInstanceOf(RepositoryItemReader.class, reader);
    }

    @Test
    void step_and_job_beans_are_built_with_the_expected_names() {
        CardPrintJobConfig config = newConfig();

        Step step = config.cardPrintStep();
        assertNotNull(step);
        assertEquals("cardPrintStep", step.getName());

        Job job = config.cardPrintJob();
        assertNotNull(job);
        assertEquals("cardPrintJob", job.getName());
    }
}
