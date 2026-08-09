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
package com.carddemo.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.dto.TransactionAddRequestDto;
import com.carddemo.common.dto.TransactionAddResponseDto;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.transaction.TransactionServiceApplication;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * :purpose: Integration test for ``COTRN02C`` transaction-id assignment. The legacy
 *     program browsed to the last record and incremented it; the migration substitutes a
 *     database sequence (AAP 0.6.5). That substitution introduced a defect: the sequence
 *     counts up from one while the ``DALYTRAN`` posting feed writes its own sixteen-digit
 *     numeric ids, so a raw sequence draw can name a row that already holds posted
 *     financial data - and JPA ``save`` merges an entity whose identifier exists, silently
 *     overwriting it instead of failing.
 * :output: Assertions that a generated id keeps the sixteen-digit zero-padded
 *     ``TRAN-ID PIC X(16)`` wire format, that an id already present in the transaction
 *     master is skipped rather than overwritten, and that an exhausted probe bound raises
 *     the frozen ``COTRN02C`` message while leaving every stored row untouched.
 * :note: Runs against a throwaway ``postgres:18`` container whose schema and seed rows come
 *     from the committed Flyway migrations, so the real ``transaction_id_seq`` (created by
 *     ``V1__create_schema.sql``), the real ``transactions`` primary key and the real
 *     ``fk_transactions_card`` foreign key added by ``V8__transactions_card_fk.sql`` all
 *     participate, and Hibernate validates the scanned entities against that schema
 *     (docs/decision-log.md, section 53.3).
 */
@SpringBootTest(classes = TransactionServiceApplication.class)
class TransactionIdGenerationIT {

    /** Probe bound enforced by the service before it refuses to assign an id. */
    private static final int MAX_TRAN_ID_PROBES = 1000;

    /** Seeded card number used for the happy-path add (``V3__seed_test_data.sql``). */
    private static final String CARD_NUM = "0923877193247330";

    /** Seeded account that owns {@link #CARD_NUM} and is cross-referenced to it. */
    private static final long ACCT_ID = 2L;

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18"));

    static {
        POSTGRES.start();
    }

    /**
     * :purpose: Point the context at the throwaway container, whose schema and seed data this
     *     context's OWN Flyway provisions from the committed migration set exactly as a
     *     deployed service does.
     * :param registry: the dynamic property registry.
     * :note: ``ddl-auto`` is ``validate``: the entities are checked against the migrated
     *     schema instead of the schema being generated from the entities, which is what makes
     *     the sequence, the primary key and the card foreign key under test the REAL ones.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * :purpose: Empty the transaction master and rewind the id generator, so each case starts
     *     from a known draw and the row counts below describe only the rows the case wrote.
     * :output: ``transactions`` is empty, ``transaction_id_seq`` will next draw 1, and the
     *     seeded customer, account, card and cross-reference the add request resolves against
     *     are in place.
     * :note: Only the transaction master is emptied. The customer, account and card rows come
     *     from the migration seed and are LEFT INTACT: they are the parents the
     *     ``fk_cards_account`` / ``fk_card_xref_*`` / ``fk_transactions_card`` constraints
     *     require, and deleting them would fail against the real schema rather than prove
     *     anything about id generation.
     */
    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.queryForObject("SELECT setval('transaction_id_seq', 1, false)", Long.class);
    }

    /**
     * :purpose: The fixture this class relies on is the migration seed, not test-authored
     *     rows: the card must exist, belong to {@link #ACCT_ID} and be cross-referenced to it,
     *     which is what the add request resolves and what ``fk_transactions_card`` enforces.
     */
    @Test
    @DisplayName("the seeded card, account and cross-reference the add resolves against exist")
    void seededFixtureIsPresent() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT card_acct_id FROM cards WHERE card_num = ?", Long.class, CARD_NUM))
                .isEqualTo(ACCT_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT xref_acct_id FROM card_xref WHERE xref_card_num = ?", Long.class, CARD_NUM))
                .isEqualTo(ACCT_ID);
    }

    /**
     * :purpose: A newly assigned id preserves the sixteen-digit zero-padded wire format
     *     the legacy ``TRAN-ID PIC X(16)`` field carries.
     */
    @Test
    @DisplayName("a generated transaction id is sixteen zero-padded digits")
    void generatedIdIsSixteenDigitZeroPadded() {
        TransactionAddResponseDto response = transactionService.addTransaction(request(), new SessionContext());

        assertThat(response.getTranId()).hasSize(16);
        assertThat(response.getTranId()).matches("\\d{16}");
        assertThat(response.getMessage())
                .isEqualTo("Transaction added successfully.  Your Tran ID is " + response.getTranId() + ".");
    }

    /**
     * :purpose: When the sequence draws an id the posting job already wrote, the service
     *     advances past it instead of merging over the stored financial row.
     */
    @Test
    @DisplayName("an id already posted by the feed is skipped, never overwritten")
    void alreadyPostedIdIsSkippedNotOverwritten() {
        long nextDraw = 683580L;
        String collidingId = String.format("%016d", nextDraw);
        jdbcTemplate.update("INSERT INTO transactions (tran_id, tran_type_cd, tran_cat_cd, "
                + "tran_source, tran_desc, tran_amt, tran_card_num, tran_orig_ts, tran_proc_ts) "
                + "VALUES (?, '01', 5, 'DALYTRAN', 'Posted by feed', 4321.99, ?, "
                + "'2026-07-01-08.00.00.000000', '2026-07-01-08.00.00.000000')", collidingId, CARD_NUM);
        jdbcTemplate.queryForObject("SELECT setval('transaction_id_seq', ?, false)",
                Long.class, nextDraw);

        TransactionAddResponseDto response = transactionService.addTransaction(request(), new SessionContext());

        assertThat(response.getTranId()).isNotEqualTo(collidingId);
        assertThat(response.getTranId()).hasSize(16);
        BigDecimal preserved = jdbcTemplate.queryForObject(
                "SELECT tran_amt FROM transactions WHERE tran_id = ?", BigDecimal.class, collidingId);
        assertThat(preserved).isEqualByComparingTo("4321.99");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT tran_desc FROM transactions WHERE tran_id = ?", String.class, collidingId))
                .isEqualTo("Posted by feed");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions", Integer.class))
                .isEqualTo(2);
    }

    /**
     * :purpose: When every id within the probe bound is already in use the service refuses
     *     the add with the frozen ``COTRN02C`` message and stores nothing, rather than
     *     overwriting an existing row.
     */
    @Test
    @DisplayName("an exhausted probe bound raises the frozen duplicate-id message")
    void exhaustedProbeBoundRaisesFrozenMessage() {
        long start = 5_000_000L;
        jdbcTemplate.update("INSERT INTO transactions (tran_id, tran_amt, tran_desc) "
                + "SELECT lpad(g::text, 16, '0'), 1.00, 'occupied' "
                + "FROM generate_series(?, ?) AS g", start, start + MAX_TRAN_ID_PROBES);
        jdbcTemplate.queryForObject("SELECT setval('transaction_id_seq', ?, false)", Long.class, start);
        int before = jdbcTemplate.queryForObject("SELECT count(*) FROM transactions", Integer.class);

        assertThatThrownBy(() -> transactionService.addTransaction(request(), new SessionContext()))
                .isInstanceOf(CardDemoException.class)
                .hasMessage("Tran ID already exist...");

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions", Integer.class))
                .isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions "
                + "WHERE tran_desc <> 'occupied'", Integer.class)).isZero();
    }

    /**
     * :purpose: Build a fully valid add request that passes every COTRN02C field check.
     * :returns: the populated request.
     */
    private TransactionAddRequestDto request() {
        TransactionAddRequestDto request = new TransactionAddRequestDto();
        request.setAcctId(String.format("%011d", ACCT_ID));
        request.setTranCardNum(CARD_NUM);
        request.setTranTypeCd("01");
        request.setTranCatCd(5);
        request.setTranSource("POS TERM");
        request.setTranDesc("Id generation check");
        request.setTranAmt(new BigDecimal("25.00"));
        request.setTranMerchantId(900000001L);
        request.setTranMerchantName("Store");
        request.setTranMerchantCity("Seattle");
        request.setTranMerchantZip("98101");
        request.setTranOrigTs("2026-08-01-10.00.00.000000");
        request.setTranProcTs("2026-08-01-10.00.00.000000");
        request.setConfirm("Y");
        return request;
    }

}
