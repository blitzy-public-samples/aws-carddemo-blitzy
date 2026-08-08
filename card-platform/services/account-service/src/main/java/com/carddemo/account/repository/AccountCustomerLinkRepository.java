package com.carddemo.account.repository;

import com.carddemo.account.entity.AccountCustomerLinkEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.Repository;

/**
 * Reads the account service's private {@code account_customer_link} table.
 *
 * <p>One row per account, so the lookup is a keyed read rather than a choice between rows: the
 * previous {@code card_xref} replica held one row per card and the query had to pick the lowest card
 * number to be deterministic. The returned row is held under a read lock for the surrounding
 * account-update transaction.
 */
public interface AccountCustomerLinkRepository
        extends Repository<AccountCustomerLinkEntity, String> {

    /**
     * Resolves one account to the customer identifier stored by the platform.
     *
     * @param accountId account identifier, exactly eleven digits
     * @return the relationship row, or empty when no relationship exists
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<AccountCustomerLinkEntity> findByAccountId(String accountId);
}
