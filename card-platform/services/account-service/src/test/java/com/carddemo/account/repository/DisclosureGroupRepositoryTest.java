package com.carddemo.account.repository;

import com.carddemo.account.entity.DisclosureGroupEntity;
import com.carddemo.account.entity.DisclosureGroupEntity.DisclosureGroupId;
import jakarta.persistence.Embeddable;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads the seeded {@code disclosure_group} rows through {@link DisclosureGroupRepository} and
 * checks the composite key and the stored rate.
 *
 * <p>{@link AbstractAccountPostgresTest} owns the one PostgreSQL container, and this class declares
 * none. Every method here reads. None inserts, updates or deletes a row.</p>
 *
 * <p><b>The sixteen-character key.</b> {@code 05 DIS-GROUP-KEY.} at
 * {@code app/cpy/CVTRA02Y.cpy:L5} spans three fields. Their widths total sixteen, the key width
 * {@code app/jcl/DISCGRP.jcl:L40} declares as {@code KEYS(16 0)}.</p>
 *
 * | Key part | Source field | Locator | Width |
 * |---|---|---|---|
 * | account group identifier | {@code DIS-ACCT-GROUP-ID PIC X(10)} | {@code app/cpy/CVTRA02Y.cpy:L6} | 10 |
 * | transaction type code | {@code DIS-TRAN-TYPE-CD PIC X(02)} | {@code app/cpy/CVTRA02Y.cpy:L7} | 2 |
 * | transaction category code | {@code DIS-TRAN-CAT-CD PIC 9(04)} | {@code app/cpy/CVTRA02Y.cpy:L8} | 4 |
 *
 * <p><b>The one field after the key.</b> {@code DIS-INT-RATE PIC S9(04)V99} at
 * {@code app/cpy/CVTRA02Y.cpy:L9} holds six digits, two of them after the decimal point. Those 22
 * mapped characters and the 28-character {@code FILLER} at {@code app/cpy/CVTRA02Y.cpy:L10} total
 * the {@code RECORDSIZE(50 50)} of {@code app/jcl/DISCGRP.jcl:L41}.</p>
 *
 * <p><b>Where the rows come from.</b> {@code src/main/resources/db/migration/V2__seed.sql} loads
 * the 51 records of {@code app/data/ASCII/discgrp.txt}, reproducing the {@code REPRO} step at
 * {@code app/jcl/DISCGRP.jcl:L61}. Record 1 reads <code>A00000000001000100150{</code> and then 28
 * filler characters. Its six-character field <code>00150{</code> carries a positive sign in the
 * trailing brace, an encoding that decodes to 15.00. Every expected value below is read from the
 * database.</p>
 *
 * <p><b>The shape of the interface.</b> {@code app/cbl/CBSTM03B.CBL:L100} declares one parameter
 * area for every dataset, and {@code app/cbl/CBSTM03B.CBL:L102} holds a one-character operation
 * code with six values at {@code app/cbl/CBSTM03B.CBL:L103-L108}. The {@code EVALUATE} at
 * {@code app/cbl/CBSTM03B.CBL:L118-L127} routes one call to one of four datasets. The keyed read
 * code maps to {@code findById}. The open and close codes map to nothing here, and the framework
 * owns the connection lifecycle.</p>
 */
@DisplayName("DisclosureGroupRepository over the 51 seeded disclosure group rows")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DisclosureGroupRepositoryTest extends AbstractAccountPostgresTest {

    /** Method the interface exposes for a keyed read. */
    private static final String KEYED_READ_METHOD = "findById";

    /** Method the interface exposes for a full read. */
    private static final String FULL_READ_METHOD = "findAll";

    /** Group identifier of record 1, positions 1 to 10 of app/data/ASCII/discgrp.txt. */
    private static final String FIRST_GROUP_ID = "A000000000";

    /** Transaction type code of record 1, positions 11 and 12 of that record. */
    private static final String FIRST_TRANSACTION_TYPE_CODE = "01";

    /** Transaction category code of record 1, positions 13 to 16 of that record. */
    private static final String FIRST_TRANSACTION_CATEGORY_CODE = "0001";

    /**
     * Transaction category code no seeded row carries. The 51 rows of
     * src/main/resources/db/migration/V2__seed.sql span the four codes 0001 to 0004, and the check
     * constraint ck_disclosure_group_category_digits admits any four digit characters.
     */
    private static final String ABSENT_TRANSACTION_CATEGORY_CODE = "9999";

    /** Value the six-character field at positions 17 to 22 of record 1 decodes to. */
    private static final BigDecimal FIRST_SEEDED_RATE = new BigDecimal("15.00");

    /** Decimal places DIS-INT-RATE PIC S9(04)V99 holds, app/cpy/CVTRA02Y.cpy:L9. */
    private static final int MIGRATED_SCALE = 2;

    /** Records app/data/ASCII/discgrp.txt holds, each 50 characters wide. */
    private static final int FIXTURE_RECORD_COUNT = 51;

    /** The interface under test, discovered from the scan root com.carddemo.account. */
    @Autowired
    private DisclosureGroupRepository repository;

    @Test
    @DisplayName("the repository exposes findById and findAll, and no write operation")
    void repositoryExposesTwoReadMethods() {
        assertThat(DisclosureGroupRepository.class.getMethods())
                .extracting(Method::getName)
                .containsExactlyInAnyOrder(KEYED_READ_METHOD, FULL_READ_METHOD);
    }

    @Test
    @DisplayName("the key type argument is the embeddable composite key, not one flat string")
    void keyTypeArgumentIsTheEmbeddableCompositeKey() {
        Type[] declaredSuperInterfaces = DisclosureGroupRepository.class.getGenericInterfaces();
        assertThat(declaredSuperInterfaces).hasSize(1);

        Type[] typeArguments =
                ((ParameterizedType) declaredSuperInterfaces[0]).getActualTypeArguments();
        assertThat(typeArguments)
                .containsExactly(DisclosureGroupEntity.class, DisclosureGroupId.class);
        assertThat(DisclosureGroupId.class).hasAnnotation(Embeddable.class);
    }

    @Test
    @DisplayName("findById on group A000000000, type 01 and category 0001 resolves that row")
    void findByIdResolvesTheFirstSeededKey() {
        Optional<DisclosureGroupEntity> found = repository.findById(firstSeededKey());

        assertThat(found).isPresent();

        DisclosureGroupId storedKey = found.orElseThrow().getId();
        assertThat(storedKey.getAccountGroupId()).isEqualTo(FIRST_GROUP_ID);
        assertThat(storedKey.getTransactionTypeCode()).isEqualTo(FIRST_TRANSACTION_TYPE_CODE);
        assertThat(storedKey.getTransactionCategoryCode())
                .isEqualTo(FIRST_TRANSACTION_CATEGORY_CODE);
    }

    @Test
    @DisplayName("two keys built separately from the same three parts are equal and reach one row")
    void separatelyBuiltEqualKeysReachOneRow() {
        DisclosureGroupId first = firstSeededKey();
        DisclosureGroupId second = firstSeededKey();

        assertThat(first).isNotSameAs(second).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());

        DisclosureGroupId keyReadWithFirst = repository.findById(first).orElseThrow().getId();
        DisclosureGroupId keyReadWithSecond = repository.findById(second).orElseThrow().getId();
        assertThat(keyReadWithFirst).isEqualTo(keyReadWithSecond);
    }

    @Test
    @DisplayName("findById on category 9999, which no row carries, returns an empty Optional")
    void findByIdOnAnAbsentCategoryReturnsAnEmptyOptional() {
        DisclosureGroupId absentKey = new DisclosureGroupId(
                FIRST_GROUP_ID, FIRST_TRANSACTION_TYPE_CODE, ABSENT_TRANSACTION_CATEGORY_CODE);

        Optional<DisclosureGroupEntity> found = repository.findById(absentKey);

        assertThat(found).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("the table holds 51 rows, one per record of app/data/ASCII/discgrp.txt")
    void tableHoldsOneRowPerFixtureRecord() {
        assertThat(repository.findAll()).hasSize(FIXTURE_RECORD_COUNT);
    }

    @Test
    @DisplayName("group A000000000, type 01 and category 0001 carries 15.00")
    void firstSeededRowCarriesFifteen() {
        assertThat(firstSeededRate()).isEqualByComparingTo(FIRST_SEEDED_RATE);
    }

    @Test
    @DisplayName("the stored 15.00 reads back at scale 2, the decimal places of PIC S9(04)V99")
    void firstSeededRowReadsBackAtScaleTwo() {
        assertThat(firstSeededRate().scale()).isEqualTo(MIGRATED_SCALE);
    }

    /**
     * Reads the table once more, after the last test method above.
     *
     * <p>The row count and the first seeded value still match
     * {@code src/main/resources/db/migration/V2__seed.sql}.</p>
     */
    @AfterAll
    void tableStillCarriesEveryRowTheSeedMigrationWrote() {
        assertThat(repository.findAll()).hasSize(FIXTURE_RECORD_COUNT);
        assertThat(firstSeededRate()).isEqualByComparingTo(FIRST_SEEDED_RATE);
    }

    /**
     * Builds the key of record 1 in the form the three columns store.
     *
     * <p>{@code account_group_id} is {@code VARCHAR(10)} and holds the ten characters supplied.
     * {@code transaction_type_code} is {@code CHAR(2)} and {@code transaction_category_code} is
     * {@code CHAR(4)}, and both values here fill their column.</p>
     *
     * @return a key for group {@code A000000000}, type {@code 01} and category {@code 0001}
     */
    private DisclosureGroupId firstSeededKey() {
        return new DisclosureGroupId(
                FIRST_GROUP_ID, FIRST_TRANSACTION_TYPE_CODE, FIRST_TRANSACTION_CATEGORY_CODE);
    }

    /**
     * Reads the {@code interest_rate} column of record 1.
     *
     * @return the value the {@code NUMERIC(6,2)} column holds for the first seeded key
     */
    private BigDecimal firstSeededRate() {
        return repository.findById(firstSeededKey()).orElseThrow().getInterestRate();
    }
}
