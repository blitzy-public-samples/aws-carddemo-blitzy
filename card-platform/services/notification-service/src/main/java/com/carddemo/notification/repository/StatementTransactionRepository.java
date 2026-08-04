package com.carddemo.notification.repository;

import com.carddemo.notification.entity.StatementTransactionEntity;
import com.carddemo.notification.entity.StatementTransactionEntity.StatementTransactionId;
import java.util.List;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Reads the card-keyed read model.
 *
 * <p>The finder below returns one card's rows in ascending transaction-identifier order,
 * reproducing the sort {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at
 * {@code app/jcl/CREASTMT.JCL:L53}. That sort orders on the card number first and the transaction
 * identifier second, so within one card the order is the transaction identifier ascending.
 *
 * <p>The card number argument is the masked form the key column holds.
 */
public interface StatementTransactionRepository
        extends ListCrudRepository<StatementTransactionEntity, StatementTransactionId> {

    /**
     * Returns one card's rows, ordered by transaction identifier ascending.
     *
     * @param cardNumber the masked card number, twelve mask characters then the last four digits
     * @return the rows, empty when the card holds none
     */
    List<StatementTransactionEntity> findByIdCardNumberOrderByIdTransactionIdAsc(String cardNumber);
}
