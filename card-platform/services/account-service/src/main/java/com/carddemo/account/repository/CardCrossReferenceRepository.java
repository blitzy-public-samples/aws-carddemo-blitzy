package com.carddemo.account.repository;

import com.carddemo.account.entity.CardCrossReferenceEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.Repository;

/**
 * Reads the account service's private {@code card_xref} replica.
 *
 * <p>The account alternate index is non-unique. The source reads one row through that path, so the
 * lowest card-number key is the deterministic relationship used here. The returned row is held
 * under a read lock for the surrounding account-update transaction.
 */
public interface CardCrossReferenceRepository
        extends Repository<CardCrossReferenceEntity, String> {

    /**
     * Resolves one account to the customer identifier stored by the platform.
     *
     * @param accountId account identifier, exactly eleven digits
     * @return the first source row in card-number order, or empty when no relationship exists
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<CardCrossReferenceEntity> findFirstByAccountIdOrderByCardNumberAsc(String accountId);
}
