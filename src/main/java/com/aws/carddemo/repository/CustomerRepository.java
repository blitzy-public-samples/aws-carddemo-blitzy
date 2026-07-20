package com.aws.carddemo.repository;

import com.aws.carddemo.domain.Customer;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link Customer} master record, replacing the legacy IBM z/OS
 * VSAM {@code CUSTDAT} KSDS I/O (the COBOL {@code EXEC CICS READ} / {@code FILE SECTION} access
 * paths) with Spring Data access over PostgreSQL in the AWS CardDemo migration.
 *
 * <p>Origin: legacy/cpy/CVCUS01Y.cpy (CUSTOMER-RECORD, RECLN 500); VSAM CUSTDAT; CSD DEFINE FILE(CUSTDAT).</p>
 *
 * <p>The repository is keyed by {@code custId} (COBOL {@code CUST-ID PIC 9(09)}), the sole VSAM
 * base-cluster key. The inherited {@link JpaRepository} CRUD surface reproduces every COBOL access
 * path against {@code CUSTDAT}: the random read by customer id (for example the account-view flow
 * that reads the customer after resolving the card cross-reference) maps to {@code findById}, and
 * the sequential customer-load/print maps to {@code findAll} ordered by {@code custId}. No
 * alternate index or secondary access path exists for {@code CUSTDAT}, so no derived-query finder is
 * declared; the inherited CRUD operations are the complete access surface.</p>
 *
 * <p>Consumed by the account-view service (customer lookup after cross-reference resolution) and by
 * the customer-load batch job. Rationale for the VSAM-to-relational mapping decisions is recorded in
 * {@code docs/decision-log.md} and is not repeated in code.</p>
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /**
     * Reads the customer by its primary key while acquiring a row-level <strong>pessimistic write
     * lock</strong> (JPA {@link LockModeType#PESSIMISTIC_WRITE}; PostgreSQL {@code SELECT ... FOR
     * UPDATE}).
     *
     * <p><strong>Origin / parity (AAP &sect;0.3.3, &sect;0.6.5):</strong> the account-update program
     * {@code legacy/cbl/COACTUPC.cbl} paragraph {@code 9600-WRITE-PROCESSING} re-reads the customer
     * master with {@code EXEC CICS READ FILE(CUSTDAT) UPDATE} &mdash; an exclusive record lock held
     * until the {@code REWRITE} &mdash; and a failure to acquire it maps to the program's
     * {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} condition. Under the migrated {@code READ COMMITTED}
     * stack an ordinary {@code findById} does <em>not</em> lock the row, so the compare-before-write
     * (paragraph {@code 9700-CHECK-CHANGE-IN-REC}) leaves a lost-update window (CWE-362) the COBOL
     * never had. Acquiring this write lock inside the update service's {@code @Transactional}
     * unit-of-work, held until commit, makes the change-check and the {@code REWRITE} atomic and
     * serializes concurrent writers of the same customer &mdash; reproducing the legacy record-lock
     * semantics exactly. This is the <em>same</em> primary-key access path as
     * {@link JpaRepository#findById(Object)} with a lock added; it is not a new finder and therefore
     * not feature expansion.</p>
     *
     * <p><strong>Lock order:</strong> {@code AccountUpdateService} acquires the account lock first
     * and the customer lock second (the COBOL order in {@code 9600-WRITE-PROCESSING}); no path locks
     * these two records in the opposite order, so no deadlock cycle can form. Must be invoked within
     * an active transaction; outside one the {@code FOR UPDATE} clause has no lasting lock effect.</p>
     *
     * @param custId the customer primary key ({@code CUST-ID PIC 9(09)})
     * @return the locked customer if present, otherwise empty
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.custId = :custId")
    Optional<Customer> findByIdForUpdate(@Param("custId") Long custId);
}
