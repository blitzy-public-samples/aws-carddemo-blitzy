package com.carddemo.account.repository;

import com.carddemo.account.entity.AccountEntity;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Finds and stores the account master record, one row of the {@code account} table in the private
 * schema of this service.
 *
 * <p>{@code app/cbl/CBSTM03B.CBL:L99-L112} declares a generic parameter area whose operation code
 * selects the access path, and this package reproduces that contract as one interface per
 * aggregate. The dispatch at {@code app/cbl/CBSTM03B.CBL:L118-L128} names {@code 'ACCTFILE'} at
 * {@code L125}, and the paragraph body {@code 4000-ACCTFILE-PROC.} at {@code L206} implements the
 * keyed read {@code 'K'} at {@code L213-L215}. That paragraph carries no other operation code, and
 * {@code app/cbl/CBSTM03B.CBL} implements the sequential read {@code 'R'} for the transaction and
 * cross-reference datasets alone.
 *
 * <p>The key holds eleven digits, from {@code KEYS(11 0)} at {@code app/jcl/ACCTFILE.jcl:L40} and
 * {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}; the inherited {@code save}
 * reproduces the write {@code 'W'} at {@code app/cbl/CBSTM03B.CBL:L107} and the rewrite
 * {@code 'Z'} at {@code L108}.
 */
public interface AccountRepository extends ListCrudRepository<AccountEntity, BigDecimal> {

    /**
     * Reproduces the keyed read {@code 'K'}, which {@code 9300-GETACCTDATA-BYACCT.} at
     * {@code app/cbl/COACTVWC.cbl:L774} issues at {@code L776-L784} and the same paragraph at
     * {@code app/cbl/COACTUPC.cbl:L3701} issues at {@code L3703-L3711}. A miss returns an empty
     * {@link Optional}.
     *
     * @param accountId the eleven-digit account identifier
     * @return the matching account, or an empty {@link Optional} when no row carries that key
     */
    Optional<AccountEntity> findByAccountId(BigDecimal accountId);

    /**
     * Reproduces the keyed read for update at {@code app/cbl/COACTUPC.cbl:L3894-L3903}, inside
     * {@code 9600-WRITE-PROCESSING.} at {@code app/cbl/COACTUPC.cbl:L3888}, where the source issues
     * a Customer Information Control System (CICS) {@code READ} with {@code UPDATE} against
     * {@code LIT-ACCTFILENAME}. A response other than {@code DFHRESP(NORMAL)} at
     * {@code app/cbl/COACTUPC.cbl:L3907} makes the source set
     * {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE} at {@code L3912} and exit at {@code L3914}; that
     * condition carries the text {@code 'Could not lock account record for update'} at
     * {@code app/cbl/COACTUPC.cbl:L517-L518}, and the domain layer reports that message. A miss
     * returns an empty {@link Optional}.
     *
     * @param accountId the eleven-digit account identifier
     * @return the locked account, or an empty {@link Optional} when no row carries that key
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AccountEntity> findForUpdateByAccountId(BigDecimal accountId);
}
