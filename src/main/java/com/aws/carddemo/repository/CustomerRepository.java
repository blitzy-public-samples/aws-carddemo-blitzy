package com.aws.carddemo.repository;

import com.aws.carddemo.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
