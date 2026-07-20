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
package com.aws.carddemo.batch.reader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.CustomerRepository;
import com.aws.carddemo.repository.TransactionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only data-access service for statement generation &mdash; the idiomatic Java
 * re-platform of the COBOL I/O subprogram {@code CBSTM03B} (source
 * {@code legacy/cbl/CBSTM03B.CBL}, formerly {@code app/cbl/CBSTM03B.CBL}).
 *
 * <p>In the mainframe design the statement driver {@code CBSTM03A}
 * ({@code legacy/cbl/CBSTM03A.CBL}) does not open the VSAM files itself; it delegates
 * every file operation to the subprogram {@code CBSTM03B} through a static
 * {@code CALL 'CBSTM03B' USING WS-M03B-AREA}. That call appears <strong>13 times</strong>
 * in {@code CBSTM03A} (at source lines 351, 377, 401, 734, 746, 769, 787, 805, 835, 860,
 * 877, 893 and 909), covering the open/read/close of four VSAM datasets. The AAP
 * (&sect;0.5.7) prescribes translating an inter-program {@code CALL} into an injected
 * Spring bean; this service is that bean, and it is injected into
 * {@code StatementGenerationJob} and its statement processor in
 * {@code com.aws.carddemo.batch}. The Spring bean name is the decapitalized class name,
 * <strong>{@code statementFileService}</strong>.</p>
 *
 * <h2>Legacy linkage contract being replaced &mdash; {@code LK-M03B-AREA}</h2>
 * <p>The {@code CBSTM03B} {@code LINKAGE SECTION} defines a single dispatch structure.
 * It is reproduced here verbatim so a reviewer can trace every typed method on this
 * service back to a {@code (DD, OPER)} pair (100% field-level traceability):</p>
 * <pre>{@code
 * LK-M03B-AREA:
 *   LK-M03B-DD     PIC X(08)   -- logical file: 'TRNXFILE' | 'XREFFILE' | 'CUSTFILE' | 'ACCTFILE'
 *   LK-M03B-OPER   PIC X(01)   -- 'O'=OPEN 'C'=CLOSE 'R'=READ(seq) 'K'=READ keyed 'W'=WRITE 'Z'=REWRITE
 *   LK-M03B-RC     PIC X(02)   -- FILE STATUS returned ('00' ok, '10' EOF, ...)
 *   LK-M03B-KEY    PIC X(25)   -- key value for keyed reads
 *   LK-M03B-KEY-LN PIC S9(4)   -- key length
 *   LK-M03B-FLDT   PIC X(1000) -- record buffer (READ INTO)
 * }</pre>
 *
 * <p>{@code CBSTM03B} dispatches on {@code LK-M03B-DD} ({@code EVALUATE} at
 * {@code CBSTM03B.CBL} L118) to one file handler each. The four handlers and the typed
 * method that supersedes each are:</p>
 * <ul>
 *   <li><strong>{@code TRNXFILE}</strong> ({@code 1000-TRNXFILE-PROC}, INDEXED /
 *       {@code ACCESS SEQUENTIAL}, key card(16)+id(16)) &rarr;
 *       {@link #readTransactionsForCard(String)} (OPER {@code 'R'}).</li>
 *   <li><strong>{@code XREFFILE}</strong> ({@code 2000-XREFFILE-PROC}, INDEXED /
 *       {@code ACCESS SEQUENTIAL}, key card(16)) &rarr;
 *       {@link #readAllCrossReferences()} (OPER {@code 'R'}).</li>
 *   <li><strong>{@code CUSTFILE}</strong> ({@code 3000-CUSTFILE-PROC}, INDEXED /
 *       {@code ACCESS RANDOM}, key cust-id {@code X(09)}) &rarr;
 *       {@link #readCustomer(Long)} (OPER {@code 'K'}).</li>
 *   <li><strong>{@code ACCTFILE}</strong> ({@code 4000-ACCTFILE-PROC}, INDEXED /
 *       {@code ACCESS RANDOM}, key acct-id {@code 9(11)}) &rarr;
 *       {@link #readAccount(Long)} (OPER {@code 'K'}).</li>
 * </ul>
 *
 * <h2>Operations deliberately not implemented</h2>
 * <ul>
 *   <li><strong>OPEN ({@code 'O'}) / CLOSE ({@code 'C'})</strong> have no Java analog:
 *       Spring Data / JPA manages the connection and persistence session, so the driver's
 *       open calls (L734, L769, L787, L805) and close calls (L860, L877, L893, L909)
 *       collapse into implicit no-ops. This service exposes no open/close method.</li>
 *   <li><strong>WRITE ({@code 'W'}) / REWRITE ({@code 'Z'})</strong> are declared as
 *       condition names in {@code LK-M03B-AREA} but are <em>never</em> handled by any
 *       {@code CBSTM03B} paragraph and are <em>never</em> exercised by {@code CBSTM03A}:
 *       statement generation is strictly read-only. This service is therefore read-only
 *       and intentionally provides no write/rewrite method.</li>
 * </ul>
 *
 * <h2>Driver usage and return-code (abend) parity</h2>
 * <p>{@code CBSTM03A} opens all four files, reads {@code TRNXFILE} sequentially to build
 * per-card transaction groups ({@code 8500-READTRNX-READ}), loops {@code XREFFILE}
 * sequentially ({@code 1000-XREFFILE-GET-NEXT}), and for each cross-reference performs a
 * keyed read of {@code CUSTFILE} ({@code 2000-CUSTFILE-GET}, key {@code XREF-CUST-ID}) and
 * {@code ACCTFILE} ({@code 3000-ACCTFILE-GET}, key {@code XREF-ACCT-ID}). The driver's
 * {@code EVALUATE WS-M03B-RC} treats {@code '00'} as success and, for the keyed customer
 * and account reads, treats any other status as fatal &mdash;
 * {@code PERFORM 9999-ABEND-PROGRAM} (which issues {@code CALL 'CEE3ABD'}). The sequential
 * {@code XREFFILE} scan additionally treats {@code '10'} as end-of-file. Those semantics
 * are preserved by the method return shapes: a full ordered {@link List} whose exhaustion
 * is end-of-file, and an {@link Optional} whose emptiness represents the non-{@code '00'}
 * status that triggers an abend in the caller (mapped to batch return code 8 by the
 * processor/job).</p>
 *
 * <h2>Per-card transaction ordering (CRITICAL parity)</h2>
 * <p>The statement extract fed to {@code CBSTM03A} is pre-sorted by
 * {@code app/jcl/CREASTMT.JCL} STEP010 with {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)}
 * &mdash; ascending by {@code CARD-NUM} (record position 263, length 16) and then by
 * {@code TRAN-ID} (position 1, length 16). Consequently, within a single card, statement
 * transactions must appear ordered by transaction id ascending. The available repository
 * method orders by processing timestamp ({@code proc_ts}, AAP &sect;0.4.3), so
 * {@link #readTransactionsForCard(String)} re-sorts the per-card list by
 * {@code tranId} ascending in memory to reproduce the {@code CREASTMT} secondary sort key
 * exactly. See that method for details.</p>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>This service is deliberately thin. Statement formatting (the 80-byte text and
 *       100-byte HTML outputs), the per-card transaction rollup and the running totals live
 *       in the {@code StatementGenerationJob} processor/writer &mdash; not here &mdash;
 *       exactly as {@code CBSTM03A} (not {@code CBSTM03B}) owns that logic.</li>
 *   <li>Dependencies are supplied by constructor injection only; there is no field
 *       injection. A single constructor lets Spring resolve the four repositories without
 *       an explicit {@code @Autowired} annotation.</li>
 *   <li>Monetary fields on the returned entities remain {@link java.math.BigDecimal}; this
 *       service performs no arithmetic and no rounding.</li>
 *   <li>Sensitive-data rule: the returned {@link Customer} carries PII (SSN, government id,
 *       date of birth) and cards carry a CVV; this service never logs record contents, so
 *       no PII, password or CVV can leak from here. Masking remains a service/DTO concern.</li>
 * </ul>
 *
 * @see CardXrefRepository
 * @see CustomerRepository
 * @see AccountRepository
 * @see TransactionRepository
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@Service
@Transactional(readOnly = true)
public class StatementFileService {

    /** Cross-reference access &mdash; supersedes the {@code XREFFILE} handler of {@code CBSTM03B}. */
    private final CardXrefRepository cardXrefRepository;

    /** Customer access &mdash; supersedes the {@code CUSTFILE} handler of {@code CBSTM03B}. */
    private final CustomerRepository customerRepository;

    /** Account access &mdash; supersedes the {@code ACCTFILE} handler of {@code CBSTM03B}. */
    private final AccountRepository accountRepository;

    /** Transaction access &mdash; supersedes the {@code TRNXFILE} handler of {@code CBSTM03B}. */
    private final TransactionRepository transactionRepository;

    /**
     * Creates the statement file service with its four backing repositories.
     *
     * <p>Constructor injection is the Java analog of the static {@code CALL} linkage that
     * bound {@code CBSTM03A} to {@code CBSTM03B}: the collaborators are provided once, at
     * construction, and held in {@code final} fields. Because this is the only constructor,
     * Spring uses it for autowiring without an explicit {@code @Autowired} annotation.</p>
     *
     * @param cardXrefRepository    repository for {@link CardXref} cross-reference records
     *                              ({@code XREFFILE})
     * @param customerRepository    repository for {@link Customer} master records
     *                              ({@code CUSTFILE})
     * @param accountRepository     repository for {@link Account} master records
     *                              ({@code ACCTFILE})
     * @param transactionRepository repository for {@link Transaction} posted transactions
     *                              ({@code TRNXFILE})
     */
    public StatementFileService(CardXrefRepository cardXrefRepository,
                                CustomerRepository customerRepository,
                                AccountRepository accountRepository,
                                TransactionRepository transactionRepository) {
        this.cardXrefRepository = cardXrefRepository;
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Reads every card cross-reference in ascending card-number order.
     *
     * <p>Replaces the sequential {@code XREFFILE} scan
     * ({@code DD='XREFFILE', OPER='R'}) that {@code CBSTM03A} drives from
     * {@code 1000-XREFFILE-GET-NEXT} (CALL at {@code CBSTM03A.CBL} L351), looping until the
     * subprogram returns FILE STATUS {@code '10'} (end-of-file). Rather than surfacing one
     * record per call, this method returns the whole ordered list; the caller iterates it,
     * and reaching the end of the list is the analog of the COBOL end-of-file that stops the
     * mainline loop. The ordering (card number ascending) matches the VSAM
     * {@code RECORD KEY} browse order.</p>
     *
     * @return all cross-references ordered by {@code xrefCardNum} ascending; an empty list
     *         when none exist, never {@code null}
     */
    public List<CardXref> readAllCrossReferences() {
        return cardXrefRepository.findAllByOrderByXrefCardNumAsc();
    }

    /**
     * Reads a single customer master record by its identifier.
     *
     * <p>Replaces the keyed {@code CUSTFILE} read ({@code DD='CUSTFILE', OPER='K'}) that
     * {@code CBSTM03A} performs in {@code 2000-CUSTFILE-GET} (CALL at {@code CBSTM03A.CBL}
     * L377) using {@code XREF-CUST-ID} as the key. In the legacy flow, any FILE STATUS other
     * than {@code '00'} is fatal and triggers {@code 9999-ABEND-PROGRAM}
     * ({@code CALL 'CEE3ABD'}); there is no end-of-file branch for this random read.
     * Accordingly, an empty {@link Optional} here represents that non-{@code '00'} status:
     * the calling processor/job must treat it as a hard error (a controlled exception mapped
     * to batch return code 8), preserving the abend semantics rather than silently skipping
     * the statement.</p>
     *
     * @param custId the customer id ({@code XREF-CUST-ID}); the {@code Customer} primary key
     * @return the matching customer, or {@link Optional#empty()} when no record exists
     *         (abend-equivalent for the caller)
     */
    public Optional<Customer> readCustomer(Long custId) {
        return customerRepository.findById(custId);
    }

    /**
     * Reads a single account master record by its identifier.
     *
     * <p>Replaces the keyed {@code ACCTFILE} read ({@code DD='ACCTFILE', OPER='K'}) that
     * {@code CBSTM03A} performs in {@code 3000-ACCTFILE-GET} (CALL at {@code CBSTM03A.CBL}
     * L401) using {@code XREF-ACCT-ID} as the key. As with the customer read, the legacy flow
     * abends on any FILE STATUS other than {@code '00'} and has no end-of-file branch for this
     * random read; an empty {@link Optional} therefore carries the same abend-equivalent
     * meaning for the caller.</p>
     *
     * @param acctId the account id ({@code XREF-ACCT-ID}); the {@code Account} primary key
     * @return the matching account, or {@link Optional#empty()} when no record exists
     *         (abend-equivalent for the caller)
     */
    public Optional<Account> readAccount(Long acctId) {
        return accountRepository.findById(acctId);
    }

    /**
     * Reads all transactions for one card, ordered as the legacy statement expects.
     *
     * <p>Replaces the per-card {@code TRNXFILE} grouping ({@code DD='TRNXFILE', OPER='R'})
     * that {@code CBSTM03A} builds in {@code 8500-READTRNX-READ} (CALL at {@code CBSTM03A.CBL}
     * L835, plus the priming read at L746). The legacy program consumes a transaction extract
     * that {@code app/jcl/CREASTMT.JCL} STEP010 has already sorted with
     * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} &mdash; ascending by {@code CARD-NUM} then
     * by {@code TRAN-ID} &mdash; so within a card the transactions are in transaction-id
     * order.</p>
     *
     * <p><strong>Why the result is re-sorted.</strong> The backing repository query
     * {@link TransactionRepository#findByCardNumOrderByProcTsAscTranIdAsc(String)} orders by
     * processing timestamp ({@code proc_ts}, AAP &sect;0.4.3), which is <em>not</em> the
     * {@code CREASTMT} secondary key. To reproduce the statement's transaction ordering
     * exactly (byte-for-byte parity of the generated PS/HTML output), the list is copied into
     * a new mutable {@link ArrayList} (the query result may be immutable) and then re-sorted
     * by {@code tranId} ascending, matching {@code SORT FIELDS=(...,1,16,CH,A)}.</p>
     *
     * @param cardNum the 16-character card number ({@code TRNX-CARD-NUM})
     * @return the card's transactions ordered by {@code tranId} ascending; an empty list when
     *         the card has none, never {@code null}
     */
    public List<Transaction> readTransactionsForCard(String cardNum) {
        // Copy into a mutable list: the repository result may be immutable, and the
        // CREASTMT STEP010 secondary sort key (TRAN-ID) must be applied in memory because
        // the query orders by proc_ts rather than tran_id.
        List<Transaction> txns = new ArrayList<>(transactionRepository.findByCardNumOrderByProcTsAscTranIdAsc(cardNum));
        txns.sort(Comparator.comparing(Transaction::getTranId));
        return txns;
    }
}
