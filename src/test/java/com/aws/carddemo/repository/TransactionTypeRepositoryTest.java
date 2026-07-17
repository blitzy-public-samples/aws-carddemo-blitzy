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

import com.aws.carddemo.domain.TransactionType;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

/**
 * Testcontainers-backed Spring Data JPA integration test for
 * {@link TransactionTypeRepository} over its managed entity
 * {@link TransactionType} (table {@code transaction_type}).
 *
 * <p><strong>Legacy provenance.</strong> This exercises the reference/lookup
 * access that the COBOL batch transaction-detail report {@code CBTRN03C}
 * (relocated to {@code legacy/cbl/CBTRN03C.cbl}) performed against the
 * {@code TRANTYPE} VSAM KSDS. In paragraph {@code 1500-B-LOOKUP-TRANTYPE} the
 * program issues a keyed {@code READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD} to
 * resolve a two-character transaction-type code to its description, and treats an
 * {@code INVALID KEY} outcome as {@code 'INVALID TRANSACTION TYPE'}. That
 * record-at-a-time indexed read is re-expressed as set-based access over the
 * PostgreSQL {@code transaction_type} table (AAP &sect;0.4.3), and this test
 * contributes to the &ge;80% JaCoCo line-coverage gate (AAP &sect;0.8.1,
 * &sect;0.9.2). The record layout under test is the copybook
 * {@code CVTRA03Y.cpy}: {@code TRAN-TYPE PIC X(02)} &rarr; {@code type_cd} and
 * {@code TRAN-TYPE-DESC PIC X(50)} &rarr; {@code type_desc}.
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
 * context startup and Hibernate then <em>validates</em> the {@link TransactionType}
 * mapping against the Flyway-authored {@code transaction_type} table &mdash; so a
 * green context start is itself the entity&harr;schema parity guard.
 *
 * <p><strong>Reference-data awareness.</strong> {@code V2__reference_data.sql}
 * seeds {@code transaction_type} (codes {@code 01}&ndash;{@code 07}) and commits
 * before each rolled-back test transaction begins, so the table is never empty.
 * Tests therefore use synthetic, non-colliding codes ({@code ZZ}, {@code Z1},
 * {@code Z2}, {@code Z3}, {@code Q9}) and never assert an absolute count from
 * zero. The ordering test intentionally does <em>not</em> call
 * {@code deleteAllInBatch()}: the seeded {@code transaction_category} rows
 * foreign-key reference {@code transaction_type}, so a blanket delete would raise
 * a constraint violation. Instead it inserts scrambled synthetic rows and asserts
 * the query returns them ascending, validating the ordering contract without
 * mutating foreign-key-referenced seed data.
 *
 * <p>The class and its test methods are package-private: JUnit 5 does not require
 * {@code public}, and the class name ends in {@code Test} so maven-surefire
 * executes it (and its coverage counts toward the JaCoCo gate). Both collaborators
 * are supplied by constructor injection; there is no field {@code @Autowired}.
 * {@link TestConstructor @TestConstructor(autowireMode = ALL)} enables Spring to
 * autowire the (deliberately un-annotated) constructor parameters, since the
 * framework default ({@code ANNOTATED}) would otherwise leave them unresolved.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class TransactionTypeRepositoryTest {

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
    private final TransactionTypeRepository repository;

    /** JPA test helper used to flush and clear the persistence context. */
    private final TestEntityManager entityManager;

    /**
     * Constructor injection (no field {@code @Autowired}); {@link DataJpaTest}
     * resolves both parameters from the slice's application context via the Spring
     * {@code SpringExtension}.
     *
     * @param repository    the {@link TransactionTypeRepository} under test
     * @param entityManager the JPA {@link TestEntityManager} test helper
     */
    TransactionTypeRepositoryTest(TransactionTypeRepository repository, TestEntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    /**
     * A saved row round-trips by its natural key with both fields intact. After
     * {@code saveAndFlush} the persistence context is cleared so the subsequent
     * {@code findById} is served from a fresh database read rather than the
     * first-level cache &mdash; the relational analogue of the keyed
     * {@code READ TRANTYPE-FILE} in {@code CBTRN03C}'s {@code 1500-B-LOOKUP-TRANTYPE}.
     */
    @Test
    void saveAndFindById_roundTripsAllFields() {
        repository.saveAndFlush(new TransactionType("ZZ", "TEST TYPE DESC"));
        entityManager.clear();

        assertThat(repository.findById("ZZ"))
                .isPresent()
                .hasValueSatisfying(found -> {
                    assertThat(found.getTypeCd()).isEqualTo("ZZ");
                    assertThat(found.getTypeDesc()).isEqualTo("TEST TYPE DESC");
                });
    }

    /**
     * {@link TransactionTypeRepository#findAllByOrderByTypeCdAsc()} returns rows
     * ordered by ascending {@code type_cd}, reproducing the deterministic
     * ascending-key read of the {@code TRANTYPE} KSDS.
     *
     * <p>Synthetic {@code Z}-range codes are inserted in a deliberately scrambled
     * order. They never collide with the {@code V2} seed
     * ({@code 01}&ndash;{@code 07}), so the assertion holds without deleting seed
     * rows &mdash; important because {@code transaction_category} foreign-key
     * references {@code transaction_type} and would block a blanket
     * {@code deleteAllInBatch()}. The extracted code list must be globally sorted,
     * and the three scrambled inserts must come back in ascending relative order.
     */
    @Test
    void findAllByOrderByTypeCdAsc_returnsAscendingByTypeCd() {
        repository.saveAll(List.of(
                new TransactionType("Z3", "GAMMA TYPE"),
                new TransactionType("Z1", "ALPHA TYPE"),
                new TransactionType("Z2", "BETA TYPE")));
        repository.flush();
        entityManager.clear();

        List<TransactionType> ordered = repository.findAllByOrderByTypeCdAsc();

        assertThat(ordered)
                .extracting(TransactionType::getTypeCd)
                .isSorted()
                .containsSubsequence("Z1", "Z2", "Z3");
    }

    /**
     * The {@code V2__reference_data.sql} migration seeds the
     * {@code transaction_type} reference table before the (rolled-back) test
     * transaction begins, so the repository is never empty at the start of a test.
     * The assertion is deliberately tolerant (a positive count, not an exact
     * number) so it survives future edits to the seed data.
     */
    @Test
    void referenceDataSeededByFlywayV2IsPresent() {
        assertThat(repository.count()).isGreaterThan(0);
    }

    /**
     * A lookup for a code that is neither seeded nor inserted yields an empty
     * {@code Optional}, modelling the CICS {@code NOTFND} / COBOL
     * {@code INVALID KEY} ({@code 'INVALID TRANSACTION TYPE'}) outcome of
     * {@code CBTRN03C}'s {@code 1500-B-LOOKUP-TRANTYPE}.
     */
    @Test
    void findById_absentCode_returnsEmptyOptional() {
        assertThat(repository.findById("Q9")).isEmpty();
    }
}
