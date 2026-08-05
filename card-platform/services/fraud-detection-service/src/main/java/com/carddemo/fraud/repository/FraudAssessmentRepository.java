package com.carddemo.fraud.repository;

import com.carddemo.fraud.entity.FraudAssessmentEntity;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes the risk assessment the fraud consumer records once per authorized transaction.
 *
 * <p>No Common Business Oriented Language (COBOL) ancestor: no COBOL program scores risk.</p>
 *
 * <p>The identifier is the transaction identifier, and the lookup on that primary key is inherited
 * from {@link JpaRepository}.</p>
 *
 * <p>{@code LK-M03B-AREA} at {@code app/cbl/CBSTM03B.CBL:L99-L114} sends every read and write
 * through one subroutine, which this interface borrows as shape only, no logic.</p>
 */
@Repository
public interface FraudAssessmentRepository extends JpaRepository<FraudAssessmentEntity, String> {

    /**
     * Returns one account's assessments, newest first.
     *
     * <p>The read-only controller in the sibling {@code api} package reads them, and
     * {@code pageable} caps how many rows arrive.</p>
     *
     * <p>{@code accountId} is opaque text of eleven digits, and no caller parses it into a number,
     * so a leading zero survives.</p>
     *
     * @param accountId the account whose assessments to return, eleven digits of text
     * @param pageable  how many rows to return and where to start
     * @return the assessments, newest first, empty when the account holds none
     */
    List<FraudAssessmentEntity> findByAccountIdOrderByAssessedAtDesc(String accountId, Pageable pageable);
}
