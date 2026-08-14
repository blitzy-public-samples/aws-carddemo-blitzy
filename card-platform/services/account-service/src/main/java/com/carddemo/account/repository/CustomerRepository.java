package com.carddemo.account.repository;

import com.carddemo.account.entity.CustomerEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Finds and stores the customer master record, one row of the {@code customer} table in the
 * private schema of this service.
 *
 * <p>{@code app/cbl/CBSTM03B.CBL:L99-L112} declares a generic parameter area whose operation code
 * selects the access path, and this package reproduces that contract as one interface per
 * aggregate. The dispatch at {@code app/cbl/CBSTM03B.CBL:L118-L128} names {@code 'CUSTFILE'} at
 * {@code L123}, and the paragraph body {@code 3000-CUSTFILE-PROC.} at {@code L181} implements the
 * keyed read {@code 'K'} at {@code L188-L190}. That paragraph implements no other read, and
 * {@code app/cbl/CBSTM03B.CBL} implements the sequential read {@code 'R'} for the transaction and
 * cross-reference datasets alone.
 *
 * <p>The key holds nine digits, from {@code KEYS(9 0)} at {@code app/jcl/CUSTFILE.jcl:L50} and
 * {@code CUST-ID PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L5}; the inherited {@code save}
 * reproduces the write {@code 'W'} at {@code app/cbl/CBSTM03B.CBL:L107} and the rewrite
 * {@code 'Z'} at {@code L108}.
 */
public interface CustomerRepository extends ListCrudRepository<CustomerEntity, String> {

    /**
     * Reproduces the keyed read {@code 'K'}, which {@code 9400-GETCUSTDATA-BYCUST.} at
     * {@code app/cbl/COACTVWC.cbl:L825} issues at {@code L826-L834} and the same paragraph at
     * {@code app/cbl/COACTUPC.cbl:L3752} issues at {@code L3753-L3761}. A miss returns an empty
     * {@link Optional}.
     *
     * @param customerId the nine-digit customer identifier
     * @return the matching customer, or an empty {@link Optional} when no row carries that key
     */
    Optional<CustomerEntity> findByCustomerId(String customerId);

    /**
     * Reproduces the keyed read for update at {@code app/cbl/COACTUPC.cbl:L3921-L3930}, inside
     * {@code 9600-WRITE-PROCESSING.} at {@code L3888}, where the source issues a Customer
     * Information Control System (CICS) {@code READ} with {@code UPDATE} against
     * {@code LIT-CUSTFILENAME}.
     *
     * <p>A response other than {@code DFHRESP(NORMAL)} at {@code L3934} makes the source set
     * {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} at {@code L3939}, carrying the text
     * {@code 'Could not lock customer record for update'} from
     * {@code app/cbl/COACTUPC.cbl:L519-L520}, and exit at {@code L3941}. The domain layer reports
     * that message, and a miss returns an empty {@link Optional}.</p>
     *
     * @param customerId the nine-digit customer identifier
     * @return the locked customer, or an empty {@link Optional} when no row carries that key
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CustomerEntity> findForUpdateByCustomerId(String customerId);
}
