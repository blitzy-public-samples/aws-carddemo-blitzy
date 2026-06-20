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
package com.aws.carddemo.service.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.CustomerRepository;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.service.batch.FileIoService.WorkArea;
import com.aws.carddemo.service.batch.FileIoService.WorkArea.Operation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

/**
 * Pure Mockito unit tests for {@link FileIoService}, the centralized file-I/O subprogram that is
 * the 1:1 Java modernization of the legacy COBOL batch subroutine {@code CBSTM03B} (legacy source
 * {@code legacy/app/cbl/CBSTM03B.CBL}, called from {@code CBSTM03A} via {@code CALL 'CBSTM03B'
 * USING WS-M03B-AREA}; AAP &sect;0.4.1, &sect;0.4.2).
 *
 * <p>The four Spring Data repositories that replace the legacy VSAM files ({@code TRNXFILE} /
 * {@code XREFFILE} / {@code CUSTFILE} / {@code ACCTFILE}) are mocked so the {@code EVALUATE
 * LK-M03B-DD} dispatch and per-file {@code OPEN}/{@code READ}/{@code READ_KEY}/{@code CLOSE} logic
 * can be exercised in isolation — no Spring context, Testcontainers or database is involved.
 *
 * <p><strong>The contract these tests pin</strong> (faithful to {@code CBSTM03B}, AAP &sect;0.6.4):
 *
 * <ul>
 *   <li>{@code process} only <em>returns</em> a 2-byte FILE STATUS through the work area and
 *       <strong>never throws</strong> for an I/O outcome — not for end-of-file ({@code "10"}), not
 *       for record-not-found ({@code "23"}), not for an unknown ddname. Abend decisions belong to
 *       the caller.
 *   <li>the read record is passed back to the caller through {@link WorkArea#getRecord()} — the
 *       modern equivalent of {@code READ ... INTO LK-M03B-FLDT} (data passing via the work-area
 *       parameter, AAP &sect;0.4.2).
 *   <li>{@code TRNXFILE} / {@code XREFFILE} are sequential cursors ({@code OPEN} → {@code
 *       READ}&times;N → {@code "10"} → {@code CLOSE}); {@code XREFFILE} iterates in ascending
 *       {@code xrefCardNum} order.
 *   <li>{@code CUSTFILE} / {@code ACCTFILE} are keyed reads that parse the numeric id from {@code
 *       key.substring(0, keyLength)} and return {@code "00"} on a hit / {@code "23"} on a miss.
 *   <li>an unknown ddname is the {@code WHEN OTHER → GO TO 9999-GOBACK} case: it returns silently,
 *       leaving the status unchanged and touching no repository.
 * </ul>
 *
 * <p>The 2-byte FILE STATUS literals are restated locally ({@link #STATUS_OK} / {@link #STATUS_EOF}
 * / {@link #STATUS_NOT_FOUND}) so the assertions pin the exact COBOL wire values independently of
 * the production constants. Because {@link FileIoService} keeps mutable per-bean sequential-cursor
 * state, a fresh service is built in {@link #buildService()} for every test.
 *
 * <p>Test method names are intentionally {@code snake_case}; the project-wide {@code
 * junit-platform.properties} applies the {@code ReplaceUnderscores} display-name generator, so no
 * per-class {@code @DisplayNameGeneration} annotation is required.
 */
@ExtendWith(MockitoExtension.class)
class FileIoServiceTest {

  /** COBOL FILE STATUS {@code '00'} — successful open, close, or read returning a record. */
  private static final String STATUS_OK = "00";

  /** COBOL FILE STATUS {@code '10'} — end-of-file on a sequential read (not an error). */
  private static final String STATUS_EOF = "10";

  /** COBOL FILE STATUS {@code '23'} — record-not-found on a keyed read (VSAM INVALID KEY). */
  private static final String STATUS_NOT_FOUND = "23";

  /** A first transaction id (ascending order is asserted via the repository finder contract). */
  private static final String TRAN_ID_1 = "TXN0000000000001";

  /** A second transaction id, strictly greater than {@link #TRAN_ID_1}. */
  private static final String TRAN_ID_2 = "TXN0000000000002";

  /** A first (smaller) card number for the {@code XREFFILE} ascending-order cycle. */
  private static final String CARD_NUM_1 = "1234567890123456";

  /** A second (larger) card number for the {@code XREFFILE} ascending-order cycle. */
  private static final String CARD_NUM_2 = "6543210987654321";

  @Mock private TransactionRepository transactionRepository;
  @Mock private CardXrefRepository cardXrefRepository;
  @Mock private CustomerRepository customerRepository;
  @Mock private AccountRepository accountRepository;

  /** The system under test; rebuilt fresh before every test so cursor state never leaks. */
  private FileIoService service;

  @BeforeEach
  void buildService() {
    service =
        new FileIoService(
            transactionRepository, cardXrefRepository, customerRepository, accountRepository);
  }

  // ---------------------------------------------------------------------------------------------
  // Phase A — TRNXFILE sequential cycle (OPEN -> READ x N -> EOF -> CLOSE)
  // ---------------------------------------------------------------------------------------------

  /**
   * The transaction master is a sequential cursor: {@code OPEN} establishes the iterator and
   * returns {@code "00"}; each {@code READ} advances it, returning the next {@link Transaction} in
   * {@link WorkArea#getRecord()} with {@code "00"}; the read past the end returns {@code "10"} with
   * a {@code null} record; {@code CLOSE} returns {@code "00"}. The whole cycle is wrapped in {@link
   * org.assertj.core.api.Assertions#assertThatNoException()} to pin that no step throws.
   */
  @Test
  void trnxfile_open_read_to_eof_then_close_returns_status_codes_and_records() {
    Transaction t1 = transaction(TRAN_ID_1);
    Transaction t2 = transaction(TRAN_ID_2);
    when(transactionRepository.findAllByOrderByTranIdAsc()).thenReturn(List.of(t1, t2));

    WorkArea wa = new WorkArea(WorkArea.TRNXFILE, Operation.OPEN);

    assertThatNoException()
        .isThrownBy(
            () -> {
              service.process(wa); // OPEN INPUT TRNX-FILE
              assertThat(wa.getReturnCode()).isEqualTo(STATUS_OK);

              wa.setOperation(Operation.READ);
              service.process(wa); // READ -> t1
              assertThat(wa.getReturnCode()).isEqualTo(STATUS_OK);
              assertThat(wa.getRecord()).isSameAs(t1);

              service.process(wa); // READ -> t2
              assertThat(wa.getReturnCode()).isEqualTo(STATUS_OK);
              assertThat(wa.getRecord()).isSameAs(t2);

              service.process(wa); // READ -> end-of-file
              assertThat(wa.getReturnCode()).isEqualTo(STATUS_EOF);
              assertThat(wa.getRecord()).isNull();

              wa.setOperation(Operation.CLOSE);
              service.process(wa); // CLOSE TRNX-FILE
              assertThat(wa.getReturnCode()).isEqualTo(STATUS_OK);
            });

    verify(transactionRepository).findAllByOrderByTranIdAsc();
  }

  // ---------------------------------------------------------------------------------------------
  // Phase B — XREFFILE sequential cycle + ascending xrefCardNum sort
  // ---------------------------------------------------------------------------------------------

  /**
   * The card cross-reference is a sequential cursor whose deterministic order is obtained with
   * {@code Sort.by("xrefCardNum")} (ascending). The {@code OPEN}/{@code READ}/{@code CLOSE} cycle
   * mirrors {@code TRNXFILE}; additionally the {@link Sort} passed to {@code findAll} is captured
   * and asserted to be ascending by {@code xrefCardNum}, pinning the golden-file ordering
   * guarantee.
   */
  @Test
  void xreffile_sequential_cycle_uses_ascending_xref_card_num_and_returns_records_then_eof() {
    CardXref x1 = cardXref(CARD_NUM_1);
    CardXref x2 = cardXref(CARD_NUM_2);
    when(cardXrefRepository.findAll(any(Sort.class))).thenReturn(List.of(x1, x2));

    WorkArea wa = new WorkArea(WorkArea.XREFFILE, Operation.OPEN);

    assertThatNoException()
        .isThrownBy(
            () -> {
              service.process(wa); // OPEN INPUT XREF-FILE
              assertThat(wa.getReturnCode()).isEqualTo(STATUS_OK);

              wa.setOperation(Operation.READ);
              service.process(wa); // READ -> x1
              assertThat(wa.getReturnCode()).isEqualTo(STATUS_OK);
              assertThat(wa.getRecord()).isSameAs(x1);

              service.process(wa); // READ -> x2
              assertThat(wa.getReturnCode()).isEqualTo(STATUS_OK);
              assertThat(wa.getRecord()).isSameAs(x2);

              service.process(wa); // READ -> end-of-file
              assertThat(wa.getReturnCode()).isEqualTo(STATUS_EOF);
              assertThat(wa.getRecord()).isNull();

              wa.setOperation(Operation.CLOSE);
              service.process(wa); // CLOSE XREF-FILE
              assertThat(wa.getReturnCode()).isEqualTo(STATUS_OK);
            });

    ArgumentCaptor<Sort> sortCaptor = ArgumentCaptor.forClass(Sort.class);
    verify(cardXrefRepository).findAll(sortCaptor.capture());
    Sort usedSort = sortCaptor.getValue();
    assertThat(usedSort).isEqualTo(Sort.by("xrefCardNum"));
    Sort.Order order = usedSort.getOrderFor("xrefCardNum");
    assertThat(order).isNotNull();
    assertThat(order.isAscending()).isTrue();
  }

  // ---------------------------------------------------------------------------------------------
  // Phase C — CUSTFILE keyed READ_KEY (hit and miss)
  // ---------------------------------------------------------------------------------------------

  /**
   * A keyed read of an existing customer: {@code OPEN} returns {@code "00"} (random access needs no
   * cursor), {@code READ_KEY} parses the 9-digit zoned-numeric key {@code "000000123"} to {@code
   * 123L}, looks the customer up and returns it in {@link WorkArea#getRecord()} with {@code "00"},
   * and {@code CLOSE} returns {@code "00"}. The {@code findById(123L)} verification proves the
   * {@code key.substring(0, keyLength)} parse (the COBOL {@code LK-M03B-KEY (1:LK-M03B-KEY-LN)}
   * reference modification).
   */
  @Test
  void custfile_read_key_hit_sets_record_and_status_00() {
    Customer cust = customer(123L);
    when(customerRepository.findById(123L)).thenReturn(Optional.of(cust));

    WorkArea wa = new WorkArea(WorkArea.CUSTFILE, Operation.OPEN);
    service.process(wa); // OPEN INPUT CUST-FILE
    assertThat(wa.getReturnCode()).isEqualTo(STATUS_OK);

    wa.setOperation(Operation.READ_KEY).setKey("000000123").setKeyLength(9);
    service.process(wa); // READ CUST-FILE by key
    assertThat(wa.getReturnCode()).isEqualTo(STATUS_OK);
    assertThat(wa.getRecord()).isSameAs(cust);

    wa.setOperation(Operation.CLOSE);
    service.process(wa); // CLOSE CUST-FILE
    assertThat(wa.getReturnCode()).isEqualTo(STATUS_OK);

    verify(customerRepository).findById(123L);
  }

  /**
   * A keyed read of a missing customer returns {@code "23"} (record-not-found) and never throws —
   * the VSAM {@code INVALID KEY} outcome. The key {@code "000000999"} (length 9) parses to {@code
   * 999L}; the missing row is reported only through the status, with no exception.
   */
  @Test
  void custfile_read_key_miss_returns_23_and_does_not_throw() {
    when(customerRepository.findById(999L)).thenReturn(Optional.empty());

    WorkArea wa = new WorkArea(WorkArea.CUSTFILE, Operation.READ_KEY, "000000999", 9);

    assertThatNoException().isThrownBy(() -> service.process(wa));

    assertThat(wa.getReturnCode()).isEqualTo(STATUS_NOT_FOUND);
    verify(customerRepository).findById(999L);
  }

  // ---------------------------------------------------------------------------------------------
  // Phase D — ACCTFILE keyed READ_KEY (hit and miss)
  // ---------------------------------------------------------------------------------------------

  /**
   * A keyed read of an existing account: {@code READ_KEY} parses the 11-digit zoned-numeric key
   * {@code "00000000123"} to {@code 123L}, looks the account up and returns it in {@link
   * WorkArea#getRecord()} with {@code "00"}. {@code OPEN} and {@code CLOSE} bracket the read with
   * {@code "00"} statuses, and the {@code findById(123L)} verification proves the key parse.
   */
  @Test
  void acctfile_read_key_hit_sets_record_and_status_00() {
    Account acct = account(123L);
    when(accountRepository.findById(123L)).thenReturn(Optional.of(acct));

    WorkArea wa = new WorkArea(WorkArea.ACCTFILE, Operation.OPEN);
    service.process(wa); // OPEN INPUT ACCT-FILE
    assertThat(wa.getReturnCode()).isEqualTo(STATUS_OK);

    wa.setOperation(Operation.READ_KEY).setKey("00000000123").setKeyLength(11);
    service.process(wa); // READ ACCT-FILE by key
    assertThat(wa.getReturnCode()).isEqualTo(STATUS_OK);
    assertThat(wa.getRecord()).isSameAs(acct);

    wa.setOperation(Operation.CLOSE);
    service.process(wa); // CLOSE ACCT-FILE
    assertThat(wa.getReturnCode()).isEqualTo(STATUS_OK);

    verify(accountRepository).findById(123L);
  }

  /**
   * A keyed read of a missing account returns {@code "23"} (record-not-found) and never throws. The
   * key {@code "00000000999"} (length 11) parses to {@code 999L}; the missing row is reported only
   * through the status.
   */
  @Test
  void acctfile_read_key_miss_returns_23_and_does_not_throw() {
    when(accountRepository.findById(999L)).thenReturn(Optional.empty());

    WorkArea wa = new WorkArea(WorkArea.ACCTFILE, Operation.READ_KEY, "00000000999", 11);

    assertThatNoException().isThrownBy(() -> service.process(wa));

    assertThat(wa.getReturnCode()).isEqualTo(STATUS_NOT_FOUND);
    verify(accountRepository).findById(999L);
  }

  // ---------------------------------------------------------------------------------------------
  // Phase E — unknown ddname -> silent WHEN OTHER (GO TO 9999-GOBACK)
  // ---------------------------------------------------------------------------------------------

  /**
   * An unrecognized ddname is the COBOL {@code WHEN OTHER → GO TO 9999-GOBACK} case: {@code
   * process} matches no dispatch branch, so it returns silently — it does not throw, it leaves the
   * pre-existing return code untouched (the {@code "ZZ"} sentinel survives), and it touches no
   * repository at all.
   */
  @Test
  void unknown_ddname_returns_silently_without_touching_repositories_or_changing_status() {
    WorkArea wa = new WorkArea("BOGUSDD ", Operation.READ).setReturnCode("ZZ");

    assertThatNoException().isThrownBy(() -> service.process(wa));

    assertThat(wa.getReturnCode()).isEqualTo("ZZ");
    verifyNoInteractions(
        transactionRepository, cardXrefRepository, customerRepository, accountRepository);
  }

  // ---------------------------------------------------------------------------------------------
  // Phase F — never-throws contract (consolidated) + WRITE/REWRITE no-ops
  // ---------------------------------------------------------------------------------------------

  /**
   * Consolidated proof of the {@code CBSTM03B} FILE STATUS contract (AAP &sect;0.6.4): none of the
   * three non-success outcomes raises — an end-of-file sequential read ({@code "10"}), a keyed
   * record-not-found ({@code "23"}), and an unknown ddname (silent). Only the caller escalates a
   * bad status to an abend; the subprogram itself merely reports it.
   */
  @Test
  void process_never_throws_for_eof_not_found_or_unknown_ddname() {
    when(transactionRepository.findAllByOrderByTranIdAsc()).thenReturn(List.of());
    when(customerRepository.findById(999L)).thenReturn(Optional.empty());

    // (1) End-of-file on an exhausted sequential read (TRNXFILE opened over an empty master).
    WorkArea eofRead = new WorkArea(WorkArea.TRNXFILE, Operation.OPEN);
    assertThatNoException()
        .isThrownBy(
            () -> {
              service.process(eofRead); // OPEN over an empty file
              eofRead.setOperation(Operation.READ);
              service.process(eofRead); // READ at end-of-file must not throw
            });
    assertThat(eofRead.getReturnCode()).isEqualTo(STATUS_EOF);

    // (2) Record-not-found on a keyed read (CUSTFILE miss).
    WorkArea keyedMiss = new WorkArea(WorkArea.CUSTFILE, Operation.READ_KEY, "000000999", 9);
    assertThatNoException().isThrownBy(() -> service.process(keyedMiss));
    assertThat(keyedMiss.getReturnCode()).isEqualTo(STATUS_NOT_FOUND);

    // (3) Unknown ddname -> silent no-op, status left untouched.
    WorkArea unknown = new WorkArea("ZZZZZZZZ", Operation.READ).setReturnCode("QQ");
    assertThatNoException().isThrownBy(() -> service.process(unknown));
    assertThat(unknown.getReturnCode()).isEqualTo("QQ");
  }

  /**
   * {@code WRITE} ({@code 'W'}) and {@code REWRITE} ({@code 'Z'}) exist as COBOL {@code
   * LK-M03B-OPER} 88-levels but have no handler branch in {@code CBSTM03B}, so every handler treats
   * them as silent no-ops: they do not throw, they leave the return code untouched (the {@code
   * "ZZ"} sentinel survives), and they touch no repository. Both a sequential handler ({@code
   * TRNXFILE}) and a keyed handler ({@code CUSTFILE}) are exercised.
   */
  @Test
  void write_and_rewrite_operations_do_not_throw() {
    WorkArea seqWrite = new WorkArea(WorkArea.TRNXFILE, Operation.WRITE).setReturnCode("ZZ");
    assertThatNoException().isThrownBy(() -> service.process(seqWrite));
    assertThat(seqWrite.getReturnCode()).isEqualTo("ZZ");

    WorkArea seqRewrite = new WorkArea(WorkArea.TRNXFILE, Operation.REWRITE).setReturnCode("ZZ");
    assertThatNoException().isThrownBy(() -> service.process(seqRewrite));
    assertThat(seqRewrite.getReturnCode()).isEqualTo("ZZ");

    WorkArea keyedWrite = new WorkArea(WorkArea.CUSTFILE, Operation.WRITE).setReturnCode("ZZ");
    assertThatNoException().isThrownBy(() -> service.process(keyedWrite));
    assertThat(keyedWrite.getReturnCode()).isEqualTo("ZZ");

    verifyNoInteractions(
        transactionRepository, cardXrefRepository, customerRepository, accountRepository);
  }

  // ---------------------------------------------------------------------------------------------
  // Test fixtures — minimal domain records (no-arg ctor + setters; BigDecimal for money fields)
  // ---------------------------------------------------------------------------------------------

  /**
   * Builds a minimal {@link Transaction} with the given id and a non-zero {@link BigDecimal} amount
   * (decimal money is never {@code float}/{@code double}; AAP &sect;0.6.1).
   *
   * @param tranId the 16-character transaction id
   * @return a populated transaction
   */
  private static Transaction transaction(String tranId) {
    Transaction txn = new Transaction();
    txn.setTranId(tranId);
    txn.setTranAmt(new BigDecimal("100.00"));
    return txn;
  }

  /**
   * Builds a minimal {@link CardXref} with the given card number and representative
   * account/customer ids.
   *
   * @param cardNum the 16-character card number (primary key)
   * @return a populated cross-reference record
   */
  private static CardXref cardXref(String cardNum) {
    CardXref xref = new CardXref();
    xref.setXrefCardNum(cardNum);
    xref.setXrefAcctId(10_000_000_001L);
    xref.setXrefCustId(100_000_001L);
    return xref;
  }

  /**
   * Builds a minimal {@link Customer} with the given numeric primary key.
   *
   * @param custId the customer id
   * @return a populated customer
   */
  private static Customer customer(Long custId) {
    Customer cust = new Customer();
    cust.setCustId(custId);
    return cust;
  }

  /**
   * Builds a minimal {@link Account} with the given numeric primary key and a non-zero {@link
   * BigDecimal} balance (decimal money is never {@code float}/{@code double}; AAP &sect;0.6.1).
   *
   * @param acctId the account id
   * @return a populated account
   */
  private static Account account(Long acctId) {
    Account acct = new Account();
    acct.setAcctId(acctId);
    acct.setAcctCurrBal(new BigDecimal("250.00"));
    return acct;
  }
}
