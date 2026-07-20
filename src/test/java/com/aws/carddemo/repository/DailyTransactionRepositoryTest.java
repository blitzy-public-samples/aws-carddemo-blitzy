/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.domain.DailyTransaction;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;

/**
 * Testcontainers-backed Spring Data JPA integration test for
 * {@link DailyTransactionRepository} over its managed entity
 * {@link DailyTransaction} (table {@code daily_transaction}).
 *
 * <p><strong>Legacy provenance.</strong> This exercises the staging-table access
 * that the COBOL daily-transaction batch programs performed against the
 * {@code DALYTRAN} sequential dataset (copybook {@code legacy/cpy/CVTRA06Y.cpy},
 * {@code DALYTRAN-RECORD}, record length 350). Both
 * {@code legacy/cbl/CBTRN01C.cbl} (the daily validate batch) and
 * {@code legacy/cbl/CBTRN02C.cbl} (the daily posting batch) declare the file as
 * {@code ORGANIZATION IS SEQUENTIAL} / {@code ACCESS MODE IS SEQUENTIAL} and read
 * each record in file order via paragraph {@code 1000-DALYTRAN-GET-NEXT}
 * ({@code READ DALYTRAN-FILE INTO DALYTRAN-RECORD}) until end-of-file. That
 * record-at-a-time sequential read is re-expressed as set-based access over the
 * PostgreSQL {@code daily_transaction} table (AAP &sect;0.4.3), and this test
 * contributes to the &ge;80% JaCoCo line-coverage gate (AAP &sect;0.8.1,
 * &sect;0.9.2).
 *
 * <p><strong>Staging distinctives under test.</strong> Unlike the posted
 * {@code transaction} table, {@code daily_transaction} is a keyless staging area:
 * it has a database-generated {@code IDENTITY} surrogate primary key
 * ({@code id}), carries the business identifier {@code DALYTRAN-ID} as an ordinary
 * non-key column ({@code dalytran_id}), maps its merchant columns
 * <em>without</em> a {@code tran_} prefix
 * ({@code merchant_id}/{@code merchant_name}/{@code merchant_city}/{@code merchant_zip}),
 * and has no foreign keys. These tests specifically guard the surrogate-key
 * generation, the unprefixed merchant column mapping, the insertion-order read,
 * and monetary {@code DECIMAL(11,2)} fidelity.
 *
 * <p><strong>Harness.</strong> A {@link DataJpaTest} slice runs against a real
 * {@code postgres:16-alpine} container managed by {@link Testcontainers}. The
 * container is wired into Spring Boot's datasource by {@link ServiceConnection},
 * so no JDBC URL, username, or password is ever hardcoded (AAP &sect;0.9.3).
 * {@link AutoConfigureTestDatabase.Replace#NONE} keeps the real PostgreSQL
 * datasource rather than substituting an embedded one. The {@code test} profile
 * (see {@code src/test/resources/application-test.yml}) enables Flyway against
 * {@code classpath:db/migration} and sets {@code hibernate.ddl-auto=validate};
 * consequently {@code V1__schema.sql} and {@code V2__reference_data.sql} run at
 * context startup and Hibernate then <em>validates</em> the
 * {@link DailyTransaction} mapping against the Flyway-authored
 * {@code daily_transaction} table &mdash; so a green context start is itself the
 * entity&harr;schema parity guard (including the {@code IDENTITY} key,
 * {@code tran_amt DECIMAL(11,2)}, and the unprefixed {@code merchant_*} columns).
 *
 * <p><strong>Empty-table awareness.</strong> {@code V2__reference_data.sql} seeds
 * only the reference tables; it does <em>not</em> seed {@code daily_transaction},
 * so the staging table starts empty and each test inserts its own rows. Every
 * test runs inside the {@link DataJpaTest} transaction and is rolled back, so a
 * test never observes another test's rows. Note the PostgreSQL {@code IDENTITY}
 * sequence is non-transactional: generated {@code id} values keep advancing even
 * across rolled-back inserts, which is why the ordering test captures the actual
 * generated ids rather than asserting fixed values.
 *
 * <p>The class and its test methods are package-private: JUnit 5 does not require
 * {@code public}, and the class name ends in {@code Test} so maven-surefire
 * executes it (and its coverage counts toward the JaCoCo gate). The repository is
 * supplied by constructor injection; there is no field {@code @Autowired}. The
 * constructor parameter is autowired because {@code junit-platform.properties}
 * sets {@code spring.test.constructor.autowire.mode=all} for the whole suite.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
class DailyTransactionRepositoryTest {

    /**
     * Real PostgreSQL 16 backing store for the slice, started once for the class
     * by the {@link Testcontainers} extension. {@link ServiceConnection} exposes
     * its JDBC coordinates to Spring Boot automatically, keeping the datasource
     * free of any hardcoded credentials.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /** Repository under test. */
    private final DailyTransactionRepository repository;

    /**
     * Constructor injection (no field {@code @Autowired}); {@link DataJpaTest}
     * resolves the parameter from the slice's application context via the Spring
     * {@code SpringExtension} and the suite-wide constructor autowire mode.
     *
     * @param repository the {@link DailyTransactionRepository} under test
     */
    DailyTransactionRepositoryTest(DailyTransactionRepository repository) {
        this.repository = repository;
    }

    /**
     * Persisting a transient staging row assigns the database-generated
     * {@code IDENTITY} surrogate primary key. Before {@code saveAndFlush} the
     * entity's {@link DailyTransaction#getId() id} is {@code null} (the row has no
     * natural unique key); after the insert PostgreSQL has generated it. This
     * confirms the surrogate-key strategy chosen for the keyless {@code DALYTRAN}
     * staging record.
     */
    @Test
    void save_assignsIdentityPrimaryKey() {
        DailyTransaction entity = newDaily("0000000000000001", "10.00");
        assertThat(entity.getId()).isNull();

        DailyTransaction saved = repository.saveAndFlush(entity);

        assertThat(saved.getId()).isNotNull();
    }

    /**
     * A saved row round-trips by its surrogate id with its business identifier and
     * (crucially) its unprefixed merchant columns intact. This guards the
     * {@code merchant_id}/{@code merchant_name}/{@code merchant_zip} mapping, which
     * intentionally omits the {@code tran_} prefix carried by the posted
     * {@code transaction} table; a wrong column name would fail the insert flush
     * or the {@code ddl-auto=validate} check at context startup.
     */
    @Test
    void saveAndFindById_roundTripsFieldsIncludingUnprefixedMerchant() {
        DailyTransaction saved = repository.saveAndFlush(newDaily("0000000000000042", "25.50"));

        assertThat(repository.findById(saved.getId()))
                .isPresent()
                .hasValueSatisfying(found -> {
                    assertThat(found.getDalytranId()).isEqualTo("0000000000000042");
                    assertThat(found.getMerchantId()).isEqualTo(123L);
                    assertThat(found.getMerchantName()).isEqualTo("ACME");
                    assertThat(found.getMerchantZip()).isEqualTo("12345");
                });
    }

    /**
     * {@link DailyTransactionRepository#findAllByOrderByIdAsc()} returns the staged
     * rows ordered by the surrogate {@code id} ascending, i.e. in insertion order.
     * This reproduces the sequential file-order read of the {@code DALYTRAN}
     * dataset by {@code CBTRN01C} and {@code CBTRN02C}.
     *
     * <p>Three rows are inserted and their monotonically increasing generated ids
     * captured (the {@code IDENTITY} sequence guarantees {@code id1 < id2 < id3}).
     * Because {@code daily_transaction} is unseeded and each test is rolled back,
     * the table holds exactly these three rows, so the query returns them sorted
     * and in exactly the insertion sequence.
     */
    @Test
    void findAllByOrderByIdAsc_returnsInsertionOrder() {
        Long id1 = repository.saveAndFlush(newDaily("0000000000000001", "10.00")).getId();
        Long id2 = repository.saveAndFlush(newDaily("0000000000000002", "20.00")).getId();
        Long id3 = repository.saveAndFlush(newDaily("0000000000000003", "30.00")).getId();

        List<DailyTransaction> ordered = repository.findAllByOrderByIdAsc();

        assertThat(ordered)
                .extracting(DailyTransaction::getId)
                .isSorted()
                .containsExactly(id1, id2, id3);
    }

    /**
     * The monetary amount persists at {@code DECIMAL(11,2)} without drift. The
     * value is constructed from a {@link String} (never a binary {@code double}/
     * {@code float}) and asserted both numerically equal to {@code "1234.56"} and
     * exactly at scale 2 &mdash; the {@code BigDecimal} fidelity discipline the
     * migration requires for every {@code COMP-3} amount (AAP &sect;0.4.3,
     * &sect;0.9.2).
     */
    @Test
    void tranAmtPersistsAtScale2() {
        DailyTransaction saved = repository.saveAndFlush(newDaily("0000000000000007", "1234.56"));

        assertThat(repository.findById(saved.getId()))
                .isPresent()
                .hasValueSatisfying(found -> {
                    assertThat(found.getTranAmt()).isEqualByComparingTo("1234.56");
                    assertThat(found.getTranAmt().scale()).isEqualTo(2);
                });
    }

    /**
     * Builds a transient {@link DailyTransaction} staging row with the given
     * business id and amount and fixed, representative values for the remaining
     * fields. The surrogate {@code id} is deliberately left unset so the database
     * {@code IDENTITY} column assigns it on insert.
     *
     * @param dalytranId the business transaction id ({@code DALYTRAN-ID})
     * @param amt        the transaction amount as a decimal string, parsed with the
     *                   {@link BigDecimal#BigDecimal(String)} constructor to
     *                   preserve exact scale (never {@code double}/{@code float})
     * @return a new, unpersisted {@link DailyTransaction}
     */
    private DailyTransaction newDaily(String dalytranId, String amt) {
        return new DailyTransaction(
                dalytranId,
                "01",
                1000,
                "POS",
                "TEST",
                new BigDecimal(amt),
                123L,
                "ACME",
                "CITY",
                "12345",
                "4000000000000001",
                "2024-01-01-00.00.00.000000",
                "2024-01-01-00.00.00.000000");
    }
}
