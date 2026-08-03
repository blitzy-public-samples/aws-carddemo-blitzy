package com.carddemo.authorization.repository;

import com.carddemo.authorization.entity.AccountCreditSnapshotEntity;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Reads the credit and expiry values that the authorization decline rules test for one account.
 *
 * <p>One parameter area at {@code app/cbl/CBSTM03B.CBL:L100-L112} carried every dataset access in
 * that program. That area held a dataset selector, a one-character operation code, a
 * two-character return code, a 25-byte key, a key length and a 1000-byte record buffer. One
 * interface per aggregate replaces the dataset selector, and the method below replaces operation
 * code {@code 'K'}. Operation codes {@code 'O'} and {@code 'C'} have no counterpart here.</p>
 *
 * <p>Paragraph {@code 4000-ACCTFILE-PROC} at {@code app/cbl/CBSTM03B.CBL:L206-L229} opens the
 * account dataset for input at {@code :L209} and serves one keyed read at {@code :L213-L216}. The
 * paragraph carries no {@code WRITE} statement and no {@code REWRITE} statement. This interface
 * declares no insert method and no update method.</p>
 *
 * <p>Table {@code account_credit_snapshot} takes no write from this service. Account state-change
 * events keep its five columns current, and the account service owns every write. The cycle-close
 * operation that zeroes both cycle accumulators also sits in the account service, reproducing
 * {@code app/cbl/CBACT04C.cbl:L353-L354}.</p>
 *
 * <p>{@code card-platform/docs/decision-log.md} records the decisions behind this projection.</p>
 */
public interface AccountCreditSnapshotRepository
        extends ListCrudRepository<AccountCreditSnapshotEntity, BigDecimal> {

    /**
     * Returns the snapshot row for one account identifier.
     *
     * <p>The method reproduces operation code {@code 'K'}, condition name {@code M03B-READ-K} at
     * {@code app/cbl/CBSTM03B.CBL:L106}, served at {@code :L213-L216}. Its immediate ancestor is
     * {@code app/cbl/CBTRN02C.cbl:L394-L395}, where paragraph {@code 1500-B-LOOKUP-ACCT} moves the
     * identifier into the key field and reads the account record. The key comes from
     * {@code KEYS(11 0)} at {@code app/jcl/ACCTFILE.jcl:L40}. The Virtual Storage Access Method
     * dataset defined there carries no alternate index, so one identifier matches at most one
     * row.</p>
     *
     * <p>The identifier holds eleven digits at scale zero, matching {@code ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT01Y.cpy:L5}. {@code CardCrossReferenceRepository} resolves a card number
     * to a cross-reference row, and a caller passes the account identifier from that row here. Line
     * {@code app/cbl/CBTRN02C.cbl:L394} moves {@code XREF-ACCT-ID PIC 9(11)} from
     * {@code app/cpy/CVACT03Y.cpy:L7} into the key field.</p>
     *
     * <p>An absent row yields an empty {@code Optional}, and the method throws nothing for a miss.
     * {@code app/cbl/CBTRN02C.cbl:L396-L399} answers the same miss with reason code 101 and the
     * text {@code ACCOUNT RECORD NOT FOUND}. {@code domain/rules/AccountExistsRule} turns the empty
     * {@code Optional} into that decline. A present row supplies the credit limit, both cycle
     * accumulators and the expiry text that reason codes 102 and 103 test at
     * {@code app/cbl/CBTRN02C.cbl:L403-L420}.</p>
     *
     * @param accountId the account identifier, eleven digits at scale zero and zero or greater
     * @return the snapshot row, or an empty {@code Optional} when no row carries the identifier
     */
    Optional<AccountCreditSnapshotEntity> findByAccountId(BigDecimal accountId);
}
