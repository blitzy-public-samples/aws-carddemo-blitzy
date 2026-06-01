package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.entity.DailyTransaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
import com.carddemo.repository.TransactionCategoryBalanceRepository;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit / parity test for {@link TransactionCategoryBalanceUpsertWriter}.
 *
 * <p>Verifies the writer reproduces the COBOL {@code 2700-UPDATE-TCATBAL} paragraph family
 * ({@code app/cbl/CBTRN02C.cbl} L467-L532) exactly (PR-06):</p>
 * <ul>
 *   <li>{@code INVALID KEY} (record not found) &rarr; {@code 2700-A-CREATE-TCATBAL-REC}:
 *       {@code INITIALIZE} (balance 0) then {@code ADD DALYTRAN-AMT} &rarr; starting balance equals
 *       the signed amount; {@code WRITE}.</li>
 *   <li>{@code NOT INVALID KEY} (record found) &rarr; {@code 2700-B-UPDATE-TCATBAL-REC}:
 *       {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} &rarr; existing balance plus the signed amount;
 *       {@code REWRITE}.</li>
 *   <li>The signed amount is added directly with no absolute value (negative amounts decrement an
 *       existing balance or create a negative-balance record).</li>
 *   <li>All balances are normalized to scale 2 (PR-16).</li>
 * </ul>
 *
 * <p>The repository is mocked (no Spring context, no database); {@code save(...)} echoes its
 * argument so the returned/captured entity reflects the writer's computed balance.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionCategoryBalanceUpsertWriter — CBTRN02C 2700-UPDATE-TCATBAL parity (PR-06)")
class TransactionCategoryBalanceUpsertWriterTest {

    private static final Long ACCT_ID = 12345678901L;
    private static final String TYPE_CD = "01";
    private static final String CAT_CD = "0001";

    @Mock
    private TransactionCategoryBalanceRepository repository;

    @InjectMocks
    private TransactionCategoryBalanceUpsertWriter writer;

    private TransactionCategoryBalanceId key() {
        return new TransactionCategoryBalanceId(ACCT_ID, TYPE_CD, CAT_CD);
    }

    /** Makes {@code save(...)} return the exact entity passed to it (JPA merge echo). */
    private void stubSaveEcho() {
        when(repository.save(any(TransactionCategoryBalance.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("INSERT branch (2700-A-CREATE-TCATBAL-REC — INVALID KEY)")
    class InsertBranch {

        @Test
        @DisplayName("Missing key creates a new record with starting balance = signed amount")
        void createsNewRecordWhenKeyAbsent() {
            when(repository.findById(key())).thenReturn(Optional.empty());
            stubSaveEcho();

            TransactionCategoryBalance result =
                    writer.upsert(key(), new BigDecimal("100.00"));

            ArgumentCaptor<TransactionCategoryBalance> captor =
                    ArgumentCaptor.forClass(TransactionCategoryBalance.class);
            verify(repository).save(captor.capture());
            TransactionCategoryBalance saved = captor.getValue();

            // INITIALIZE (0) + ADD 100.00 -> 100.00, exact scale 2.
            assertThat(saved.getTranCatBal()).isEqualTo(new BigDecimal("100.00"));
            assertThat(saved.getId()).isEqualTo(key());
            assertThat(result.getTranCatBal()).isEqualTo(new BigDecimal("100.00"));
        }

        @Test
        @DisplayName("Missing key with negative amount creates a negative-balance record (no abs())")
        void createsNegativeBalanceRecordForNegativeAmount() {
            when(repository.findById(key())).thenReturn(Optional.empty());
            stubSaveEcho();

            TransactionCategoryBalance result =
                    writer.upsert(key(), new BigDecimal("-40.00"));

            assertThat(result.getTranCatBal()).isEqualTo(new BigDecimal("-40.00"));
        }

        @Test
        @DisplayName("Amount is normalized to scale 2 on insert (PR-16)")
        void normalizesScaleOnInsert() {
            when(repository.findById(key())).thenReturn(Optional.empty());
            stubSaveEcho();

            TransactionCategoryBalance result =
                    writer.upsert(key(), new BigDecimal("10.1"));

            assertThat(result.getTranCatBal()).isEqualTo(new BigDecimal("10.10"));
            assertThat(result.getTranCatBal().scale()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("UPDATE branch (2700-B-UPDATE-TCATBAL-REC — NOT INVALID KEY)")
    class UpdateBranch {

        @Test
        @DisplayName("Existing key adds the signed amount to the existing balance")
        void addsAmountToExistingBalance() {
            TransactionCategoryBalance existing =
                    new TransactionCategoryBalance(key(), new BigDecimal("50.00"));
            when(repository.findById(key())).thenReturn(Optional.of(existing));
            stubSaveEcho();

            TransactionCategoryBalance result =
                    writer.upsert(key(), new BigDecimal("25.00"));

            // ADD 25.00 TO 50.00 -> 75.00.
            assertThat(result.getTranCatBal()).isEqualTo(new BigDecimal("75.00"));
            // The same managed entity is mutated and re-saved (REWRITE).
            verify(repository).save(existing);
        }

        @Test
        @DisplayName("Negative amount decrements an existing balance (raw signed ADD)")
        void decrementsExistingBalanceForNegativeAmount() {
            TransactionCategoryBalance existing =
                    new TransactionCategoryBalance(key(), new BigDecimal("100.00"));
            when(repository.findById(key())).thenReturn(Optional.of(existing));
            stubSaveEcho();

            TransactionCategoryBalance result =
                    writer.upsert(key(), new BigDecimal("-30.00"));

            assertThat(result.getTranCatBal()).isEqualTo(new BigDecimal("70.00"));
        }

        @Test
        @DisplayName("Update preserves scale 2 when adding a sub-cent-normalized amount (PR-16)")
        void preservesScaleOnUpdate() {
            TransactionCategoryBalance existing =
                    new TransactionCategoryBalance(key(), new BigDecimal("0.00"));
            when(repository.findById(key())).thenReturn(Optional.of(existing));
            stubSaveEcho();

            TransactionCategoryBalance result =
                    writer.upsert(key(), new BigDecimal("5.5"));

            assertThat(result.getTranCatBal()).isEqualTo(new BigDecimal("5.50"));
            assertThat(result.getTranCatBal().scale()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("DailyTransaction convenience overload — key assembly")
    class ConvenienceOverload {

        @Test
        @DisplayName("Builds the composite key from XREF-ACCT-ID + DALYTRAN type/category codes")
        void buildsKeyFromTransactionAndAccountId() {
            DailyTransaction tran = DailyTransaction.builder()
                    .typeCd(TYPE_CD)
                    .categoryCd(CAT_CD)
                    .amount(new BigDecimal("12.34"))
                    .build();
            when(repository.findById(key())).thenReturn(Optional.empty());
            stubSaveEcho();

            TransactionCategoryBalance result = writer.upsert(tran, ACCT_ID);

            ArgumentCaptor<TransactionCategoryBalance> captor =
                    ArgumentCaptor.forClass(TransactionCategoryBalance.class);
            verify(repository).save(captor.capture());
            TransactionCategoryBalanceId savedKey = captor.getValue().getId();

            assertThat(savedKey.getAccountId()).isEqualTo(ACCT_ID);
            assertThat(savedKey.getTypeCd()).isEqualTo(TYPE_CD);
            assertThat(savedKey.getCategoryCd()).isEqualTo(CAT_CD);
            assertThat(result.getTranCatBal()).isEqualTo(new BigDecimal("12.34"));
        }
    }

    @Nested
    @DisplayName("Null-argument guards")
    class NullGuards {

        @Test
        @DisplayName("Null key throws IllegalArgumentException and never touches the repository")
        void nullKeyThrows() {
            assertThatThrownBy(() -> writer.upsert((TransactionCategoryBalanceId) null,
                    new BigDecimal("1.00")))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("Null amount throws IllegalArgumentException and never touches the repository")
        void nullAmountThrows() {
            assertThatThrownBy(() -> writer.upsert(key(), null))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("Null transaction (convenience overload) throws IllegalArgumentException")
        void nullTransactionThrows() {
            assertThatThrownBy(() -> writer.upsert((DailyTransaction) null, ACCT_ID))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("Null accountId (convenience overload) throws IllegalArgumentException")
        void nullAccountIdThrows() {
            DailyTransaction tran = DailyTransaction.builder()
                    .typeCd(TYPE_CD).categoryCd(CAT_CD).amount(new BigDecimal("1.00")).build();
            assertThatThrownBy(() -> writer.upsert(tran, null))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(repository, never()).save(any());
        }
    }

    @Test
    @DisplayName("Insert path reads by key exactly once and writes exactly once")
    void insertReadsOnceWritesOnce() {
        when(repository.findById(key())).thenReturn(Optional.empty());
        stubSaveEcho();

        writer.upsert(key(), new BigDecimal("1.00"));

        verify(repository, times(1)).findById(key());
        verify(repository, times(1)).save(any(TransactionCategoryBalance.class));
    }
}
