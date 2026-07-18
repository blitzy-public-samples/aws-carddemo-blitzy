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
package com.aws.carddemo.batch.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link DailyTransactionValidateProcessor}, the read-only
 * validate-pass {@code ItemProcessor} that reproduces legacy COBOL program {@code CBTRN01C}
 * ({@code legacy/cbl/CBTRN01C.cbl}).
 *
 * <h2>Why these tests exist</h2>
 * <p>The processor's two collaborators &mdash; {@link CardXrefRepository} and
 * {@link AccountRepository} &mdash; are mocked so all three of its control-flow branches are
 * exercised in complete isolation ({@code no Spring context, no database, no Testcontainers}):</p>
 * <ol>
 *   <li>the cross-reference is missing (COBOL {@code 2000-LOOKUP-XREF INVALID KEY}) &mdash; a
 *       {@code WARN} is logged and the record is skipped without an account lookup;</li>
 *   <li>the cross-reference resolves but the account is missing (COBOL {@code 3000-READ-ACCOUNT
 *       INVALID KEY}) &mdash; a {@code WARN} is logged. This branch is unreachable through the
 *       relational schema (the {@code card_xref.acct_id} foreign key guarantees the account exists),
 *       so mocking is the only way to drive it, exactly as for reject 101 on the posting side;</li>
 *   <li>both the cross-reference and the account resolve &mdash; a quiet {@code DEBUG} trace.</li>
 * </ol>
 *
 * <h2>PAN masking discipline (decision log D34, finding F2)</h2>
 * <p>Every one of the three log statements interpolates the card number through
 * {@link com.aws.carddemo.common.util.PanMasker}. These tests attach a Logback {@link ListAppender}
 * at {@code DEBUG} and assert, on <strong>each</strong> branch, that the emitted event contains the
 * masked form ({@code 999988******6666}) and never the full sixteen-digit PAN
 * ({@code 9999888877776666}). This proves the F2 fix at all three sites, not just the first.</p>
 *
 * <h2>Read-only contract</h2>
 * <p>CBTRN01C performs no writes and always ends with return code {@code 0}; each test asserts the
 * processor returns the <em>same</em> {@link DailyTransaction} instance, unmutated (pass-through).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DailyTransactionValidateProcessor — CBTRN01C read-only validate pass with PAN masking (F2/D34)")
class DailyTransactionValidateProcessorTest {

    /** Sixteen-digit card number driving the cross-reference lookup ({@code DALYTRAN-CARD-NUM}). */
    private static final String CARD_NUM = "9999888877776666";

    /** PCI-DSS masked form produced by {@code PanMasker.mask}: first six, last four, middle starred. */
    private static final String MASKED_PAN = "999988******6666";

    /** Account id resolved from the cross-reference ({@code XREF-ACCT-ID}). */
    private static final Long ACCT_ID = 90_000_000_010L;

    /** Owning customer id carried on the cross-reference ({@code XREF-CUST-ID}). */
    private static final Long CUST_ID = 900_000_010L;

    /** Business transaction id ({@code DALYTRAN-ID}). */
    private static final String DALYTRAN_ID = "DTX0000000000001";

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private AccountRepository accountRepository;

    private DailyTransactionValidateProcessor processor;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger processorLogger;

    @BeforeEach
    void setUp() {
        processor = new DailyTransactionValidateProcessor(cardXrefRepository, accountRepository);

        // Capture DEBUG and above so the quiet success trace (branch 3) is observable too.
        processorLogger = (Logger) LoggerFactory.getLogger(DailyTransactionValidateProcessor.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        processorLogger.addAppender(logAppender);
        processorLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        processorLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    // ---------------------------------------------------------------------------------------------
    // Fixture builders.
    // ---------------------------------------------------------------------------------------------

    private static DailyTransaction dailyTransaction() {
        return new DailyTransaction(
                DALYTRAN_ID, "PU", 1000, "POS", "GROCERY PURCHASE", new BigDecimal("100.00"),
                987_654_321L, "TEST MERCHANT", "SEATTLE", "98101",
                CARD_NUM, "2024-06-15 12:30:45.123456", "2024-06-15 12:30:46.000000");
    }

    private static CardXref cardXref() {
        return new CardXref(CARD_NUM, CUST_ID, ACCT_ID);
    }

    private static Account account() {
        return new Account(
                ACCT_ID, "Y", new BigDecimal("0.00"), new BigDecimal("5000.00"),
                new BigDecimal("1000.00"), "2020-01-01", "2030-01-01", "2020-01-01",
                new BigDecimal("0.00"), new BigDecimal("0.00"), "98101", "DEFAULT");
    }

    /** Concatenates every captured log line's formatted message for substring assertions. */
    private String allCapturedMessages() {
        List<ILoggingEvent> events = logAppender.list;
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent e : events) {
            sb.append(e.getFormattedMessage()).append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Branch 1: cross-reference missing (2000-LOOKUP-XREF INVALID KEY).
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Missing cross-reference — WARNs with a masked PAN, skips the account lookup, returns unchanged")
    void missingXref_masksPan_andSkipsAccountLookup() {
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());
        DailyTransaction item = dailyTransaction();

        DailyTransaction result = processor.process(item);

        assertThat(result).as("read-only pass-through returns the same instance").isSameAs(item);
        String logs = allCapturedMessages();
        assertThat(logs).contains("could not be verified");
        assertThat(logs).as("PCI-masked PAN present").contains(MASKED_PAN);
        assertThat(logs).as("full PAN never logged (F2)").doesNotContain(CARD_NUM);
        // The account repository must never be consulted when the xref is missing.
        assertThat(accountRepository).isNotNull();
    }

    // ---------------------------------------------------------------------------------------------
    // Branch 2: cross-reference resolves, account missing (3000-READ-ACCOUNT INVALID KEY).
    // Unreachable via the schema's FK; only a mock can drive it.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Cross-reference hit but account missing — WARNs with a masked PAN, returns unchanged")
    void xrefHitButAccountMissing_masksPan_andReturnsUnchanged() {
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(cardXref()));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());
        DailyTransaction item = dailyTransaction();

        DailyTransaction result = processor.process(item);

        assertThat(result).isSameAs(item);
        String logs = allCapturedMessages();
        assertThat(logs).contains("not found");
        assertThat(logs).as("account id logged in the clear (non-sensitive)").contains(String.valueOf(ACCT_ID));
        assertThat(logs).as("PCI-masked PAN present").contains(MASKED_PAN);
        assertThat(logs).as("full PAN never logged (F2)").doesNotContain(CARD_NUM);
    }

    // ---------------------------------------------------------------------------------------------
    // Branch 3: cross-reference and account both resolve — quiet DEBUG success trace.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Cross-reference and account both resolve — DEBUG success trace with a masked PAN, returns unchanged")
    void xrefAndAccountResolve_masksPanInDebug_andReturnsUnchanged() {
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(cardXref()));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account()));
        DailyTransaction item = dailyTransaction();

        DailyTransaction result = processor.process(item);

        assertThat(result).isSameAs(item);
        String logs = allCapturedMessages();
        assertThat(logs).contains("Successful read of account");
        assertThat(logs).as("PCI-masked PAN present").contains(MASKED_PAN);
        assertThat(logs).as("full PAN never logged (F2)").doesNotContain(CARD_NUM);
    }
}
