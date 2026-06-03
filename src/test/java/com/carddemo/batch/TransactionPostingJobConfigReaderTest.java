package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.entity.DailyTransaction;
import com.carddemo.repository.DailyTransactionRepository;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Regression tests for {@link TransactionPostingJobConfig.UnprocessedDailyTransactionReader}.
 *
 * <p>The CP4 data-integrity fix requires POSTTRAN to read only staging rows where
 * {@code processed=false}. The reader implements the process-indicator pattern by repeatedly asking
 * for page zero of {@code findByProcessedFalse(...)}; after the writer commits a chunk and flips rows
 * to {@code processed=true}, the next page-zero query naturally returns the next unprocessed window.
 * These tests pin that behavior without needing a database-backed Spring Batch launch.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("POSTTRAN unprocessed-row reader")
class TransactionPostingJobConfigReaderTest {

    private static final Sort SORT = Sort.by(Sort.Direction.ASC, "dalytranId");

    @Mock
    private DailyTransactionRepository repository;

    @Test
    @DisplayName("full pages are followed by another page-zero query for the next unprocessed window")
    void fullPageRequeriesFirstUnprocessedPageAfterBufferDrains() throws Exception {
        DailyTransaction first = dailyTransaction("DT0000000000001");
        DailyTransaction second = dailyTransaction("DT0000000000002");
        when(repository.findByProcessedFalse(any(Pageable.class)))
                .thenReturn(List.of(first, second))
                .thenReturn(List.of());

        TransactionPostingJobConfig.UnprocessedDailyTransactionReader reader =
                new TransactionPostingJobConfig.UnprocessedDailyTransactionReader(repository, 2, SORT);

        reader.open(new ExecutionContext());
        assertThat(reader.read()).isSameAs(first);
        assertThat(reader.read()).isSameAs(second);
        assertThat(reader.read()).isNull();
        reader.close();

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository, times(2)).findByProcessedFalse(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(pageable -> {
                    assertThat(pageable.getPageNumber()).isZero();
                    assertThat(pageable.getPageSize()).isEqualTo(2);
                    assertThat(pageable.getSort().getOrderFor("dalytranId")).isNotNull();
                    assertThat(pageable.getSort().getOrderFor("dalytranId").getDirection())
                            .isEqualTo(Sort.Direction.ASC);
                });
    }

    @Test
    @DisplayName("already processed rows are skipped on rerun because only processed=false rows are queried")
    void rerunReturnsNoRowsWhenNoUnprocessedRowsRemain() throws Exception {
        when(repository.findByProcessedFalse(any(Pageable.class))).thenReturn(List.of());

        TransactionPostingJobConfig.UnprocessedDailyTransactionReader reader =
                new TransactionPostingJobConfig.UnprocessedDailyTransactionReader(repository, 100, SORT);

        reader.open(new ExecutionContext());
        assertThat(reader.read()).isNull();
        reader.close();

        verify(repository).findByProcessedFalse(any(Pageable.class));
    }

    @Test
    @DisplayName("partial final pages are served once and do not requery before EOF")
    void partialFinalPageDoesNotRequeryBeforeEndOfData() throws Exception {
        DailyTransaction tail = dailyTransaction("DT0000000000099");
        when(repository.findByProcessedFalse(any(Pageable.class))).thenReturn(List.of(tail));

        TransactionPostingJobConfig.UnprocessedDailyTransactionReader reader =
                new TransactionPostingJobConfig.UnprocessedDailyTransactionReader(repository, 2, SORT);

        reader.open(new ExecutionContext());
        assertThat(reader.read()).isSameAs(tail);
        assertThat(reader.read()).isNull();
        reader.close();

        verify(repository, times(1)).findByProcessedFalse(any(Pageable.class));
    }

    private static DailyTransaction dailyTransaction(String id) {
        DailyTransaction tx = new DailyTransaction();
        tx.setDalytranId(id);
        tx.setProcessed(Boolean.FALSE);
        return tx;
    }
}