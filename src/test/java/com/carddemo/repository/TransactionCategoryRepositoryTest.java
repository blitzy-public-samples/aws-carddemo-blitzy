package com.carddemo.repository;

import com.carddemo.entity.TransactionCategory;
import com.carddemo.entity.TransactionCategory.TransactionCategoryId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @DataJpaTest} slice test for {@link TransactionCategoryRepository}.
 *
 * <p>Parity: replaces VSAM {@code TRANCATG} composite-key reads (copybook
 * {@code app/cpy/CVTRA04Y.cpy}; key length 6 = type_cd 2 + cat_cd 4 per
 * {@code app/catlg/LISTCAT.txt}). Exercises {@code @EmbeddedId} access over the
 * {@code transaction_category} lookup seeded by {@code V2__seed_reference.sql}
 * (18 rows; ground-truth {@code app/data/ASCII/trancatg.txt}).</p>
 *
 * <p><b>Test conventions.</b> {@code @AutoConfigureTestDatabase(replace = NONE)}
 * preserves the H2 {@code test} datasource so Flyway migrations
 * {@code V1__schema.sql .. V4__seed_users.sql} run and seed the table;
 * {@code @ActiveProfiles("test")} selects {@code application-test.yml}. The
 * composite identifier is the entity's nested
 * {@link TransactionCategory.TransactionCategoryId}, whose {@code cat_cd} component
 * is an {@code Integer} (the fixture's 4-digit {@code 0001} normalises to {@code 1}
 * in the {@code INTEGER} column). No {@code @Transactional}/{@code @Rollback} is
 * applied and the Flyway-seeded rows are never mutated.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class TransactionCategoryRepositoryTest {

    @Autowired
    private TransactionCategoryRepository transactionCategoryRepository;

    @Test
    void findById_regularSalesDraft() {
        Optional<TransactionCategory> result =
                transactionCategoryRepository.findById(new TransactionCategoryId("01", 1));
        assertThat(result).isPresent();
        assertThat(result.get().getCatTypeDesc()).isEqualTo("Regular Sales Draft");
    }

    @Test
    void findById_interestAmount() {
        Optional<TransactionCategory> result =
                transactionCategoryRepository.findById(new TransactionCategoryId("01", 5));
        assertThat(result).isPresent();
        assertThat(result.get().getCatTypeDesc()).isEqualTo("Interest Amount");
    }

    @Test
    void findById_cashPayment() {
        Optional<TransactionCategory> result =
                transactionCategoryRepository.findById(new TransactionCategoryId("02", 1));
        assertThat(result).isPresent();
        assertThat(result.get().getCatTypeDesc()).isEqualTo("Cash payment");
    }

    @Test
    void findById_salesDraftCreditAdjustment() {
        Optional<TransactionCategory> result =
                transactionCategoryRepository.findById(new TransactionCategoryId("07", 1));
        assertThat(result).isPresent();
        assertThat(result.get().getCatTypeDesc()).isEqualTo("Sales draft credit adjustment");
    }

    @Test
    void findById_unknownCompositeKey_isEmpty() {
        assertThat(transactionCategoryRepository.findById(new TransactionCategoryId("99", 99)))
                .isEmpty();
    }

    @Test
    void count_matchesSeedRowCount() {
        // V2__seed_reference.sql seeds 18 transaction categories (trancatg.txt has 18 rows)
        assertThat(transactionCategoryRepository.count()).isEqualTo(18L);
    }
}
