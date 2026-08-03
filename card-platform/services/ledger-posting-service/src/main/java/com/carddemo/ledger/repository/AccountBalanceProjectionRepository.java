package com.carddemo.ledger.repository;

import com.carddemo.ledger.entity.AccountBalanceProjectionEntity;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Finds and stores rows of {@link AccountBalanceProjectionEntity}, the balance and the two
 * billing-cycle accumulators the ledger keeps for one account.
 *
 * <p>The identifier is the eleven-digit account number, from {@code ACCT-ID PIC 9(11)} at
 * {@code app/cpy/CVACT01Y.cpy:L5}, sized by {@code KEYS(11 0)} at {@code app/jcl/ACCTFILE.jcl:L40}.
 *
 * <p>Parameter area {@code LK-M03B-AREA} at {@code app/cbl/CBSTM03B.CBL:L100-L112} held every read
 * and write behind one operation code. Its keyed read and its rewrite become the inherited
 * {@code findById} and {@code save}.
 *
 * <p>{@code domain/AccountBalanceUpdater} reads one row by identifier, then saves it.
 * {@code api/BalanceQueryController} only reads.
 *
 * <p>Rationale, source mapping and flagged findings:
 * {@code card-platform/docs/decision-log.md}, {@code card-platform/docs/traceability-matrix.md}
 * and {@code card-platform/docs/business-rule-flags.md}.
 */
public interface AccountBalanceProjectionRepository
        extends ListCrudRepository<AccountBalanceProjectionEntity, String> {
}
