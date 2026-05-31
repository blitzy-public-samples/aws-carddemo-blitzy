package com.carddemo.repository;

import com.carddemo.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link Customer} customer master-data records.
 *
 * <p>Replaces the VSAM {@code CUSTDAT} KSDS keyed file access. The underlying
 * {@code customers} table is the system-of-record, PII-rich customer store mapping the
 * 500-byte {@code CUSTOMER-RECORD} layout from {@code app/cpy/CVCUS01Y.cpy}
 * (CardDemo_v1.0-15-g27d6c6f-68): {@code CUST-ID PIC 9(09)} (key),
 * {@code CUST-FIRST-NAME}, {@code CUST-MIDDLE-NAME}, {@code CUST-LAST-NAME},
 * {@code CUST-ADDR-LINE-1/2/3}, {@code CUST-ADDR-STATE-CD}, {@code CUST-ADDR-COUNTRY-CD},
 * {@code CUST-ADDR-ZIP}, {@code CUST-PHONE-NUM-1/2}, {@code CUST-SSN},
 * {@code CUST-GOVT-ISSUED-ID}, {@code CUST-DOB-YYYY-MM-DD}, {@code CUST-EFT-ACCOUNT-ID},
 * {@code CUST-PRI-CARD-HOLDER-IND}, and {@code CUST-FICO-CREDIT-SCORE} (the trailing
 * {@code FILLER PIC X(168)} carries no business meaning and is not mapped). The 50 default
 * customers are seeded by {@code DataInitializationJobConfig} from
 * {@code app/data/ASCII/custdata.txt}.</p>
 *
 * <p>The original VSAM access verbs this interface supersedes:</p>
 * <ul>
 *   <li>{@code COACTVWC.cbl} customer-info portion
 *       ({@code EXEC CICS READ DATASET('CUSTDAT') RIDFLD(CUST-ID) INTO(CUSTOMER-RECORD)})
 *       &mdash; keyed read by customer id, reproduced by the inherited
 *       {@code findById(Long)}; the COBOL {@code DFHRESP(NOTFND)} branch maps to
 *       {@code Optional.empty()}, which {@code CustomerService}/{@code AccountService}
 *       surface as the appropriate not-found condition.</li>
 *   <li>{@code CBSTM03A.CBL} statement generation
 *       (its {@code CBSTM03B} I/O subroutine performs a keyed read of {@code CUSTFILE}
 *       using {@code XREF-CUST-ID} as the {@code WS-M03B-KEY}, moving the record image into
 *       {@code CUSTOMER-RECORD} &mdash; see {@code app/cbl/CBSTM03A.CBL:L372-L388})
 *       &mdash; reproduced by the inherited {@code findById(Long)} keyed read.</li>
 * </ul>
 *
 * <p><strong>Consumers (AAP &sect;0.4.1.1, &sect;0.4.1.2, &sect;0.4.1.6):</strong></p>
 * <ul>
 *   <li>{@code CustomerService} &mdash; backs {@code GET /api/customers/{custId}}
 *       (REST endpoint replacing the {@code COACTVWC.cbl} customer-info portion); results
 *       are mapped to {@code CustomerDto} with SSN masking performed by
 *       {@code CustomerMapper} per PR-20.</li>
 *   <li>{@code StatementService} / {@code StatementGenerationJobConfig} &mdash; reads
 *       customers during {@code CBSTM03A.CBL} statement generation (CREASTMT batch) to
 *       populate the customer name and address blocks of the HTML/plain-text statement
 *       documents.</li>
 *   <li>{@code AccountService} &mdash; reads the customer record alongside the account for
 *       the account view/update endpoints (the customer is reached from the account via the
 *       {@code CardXref} junction; see the relationship note below).</li>
 *   <li>{@code DataInitializationJobConfig} &mdash; initial seed load of the 50 default
 *       customers via {@code save}/{@code saveAll}.</li>
 * </ul>
 *
 * <p><strong>Primary key (PR-13).</strong> The {@link Customer} entity's {@code @Id} is
 * {@code Long custId} mapping {@code CUST-ID PIC 9(09)} &rarr;
 * {@code cust_id BIGINT NOT NULL PRIMARY KEY}; this interface therefore extends
 * {@code JpaRepository<Customer, Long>} (ID type {@code Long}, <em>not</em> {@code String}).
 * The original VSAM file keyed on a primary key positioned at offset 0, length 9. All
 * consumer access patterns are by primary key, so no custom finder methods are required
 * (AAP &sect;0.4.1.5): the inherited {@code findById(Long)} reproduces the keyed
 * {@code READ}, {@code save}/{@code saveAll} reproduce the seed-load {@code WRITE} and the
 * update {@code REWRITE}, and {@code existsById}/{@code deleteById}/{@code delete}/
 * {@code count}/{@code findAll}/{@code findAllById} round out the CRUD surface.</p>
 *
 * <p><strong>Customer&rarr;Account relationship.</strong> There is no direct
 * {@code customer.accounts} collection. The linkage is navigated through the
 * {@code CardXref} join entity (whose {@code xref_cust_id} references
 * {@code customers.cust_id} and whose {@code xref_acct_id} references
 * {@code accounts.acct_id}); browse-by-customer is therefore performed via
 * {@code CardXrefRepository}, not by a finder on this interface.</p>
 *
 * <p><strong>SSN handling (PR-20).</strong> The {@link Customer} entity stores the SSN in
 * plaintext (preserving leading zeros, see {@code Customer#ssn}). This repository performs
 * <strong>no masking</strong> &mdash; it returns the managed entity as-is. Masking to
 * {@code ***-**-####} (only the last 4 digits visible) is exclusively a DTO/mapper-boundary
 * concern handled by {@code CustomerMapper} when producing {@code CustomerDto} for outbound
 * REST responses. No {@code findBySsn} method is provided: the SSN is not indexed and all
 * administrative lookups are by {@code custId}.</p>
 *
 * <p><strong>PR-22 optimistic locking.</strong> The {@link Customer} entity carries a
 * {@code @Version} field; concurrent updates (the modern equivalent of the COBOL
 * {@code READ UPDATE} exclusive lock) raise {@code OptimisticLockException}, which
 * {@code GlobalExceptionHandler} maps to HTTP 409 Conflict &mdash; non-blocking optimistic
 * concurrency replacing the VSAM CI-level exclusive lock. The {@code @Version} field lives
 * on the <em>entity</em>, never on this repository.</p>
 *
 * <p><strong>PR-23 lock ordering.</strong> Service methods that lock multiple entities
 * acquire locks in the consistent order {@code CUSTOMER -> ACCOUNT -> CARD -> TRANSACTION}
 * to prevent deadlocks (matching the documented VSAM convention); the customer is the first
 * link in that chain.</p>
 *
 * <p><strong>Pattern &amp; scope.</strong> Per the Repository Pattern (AAP &sect;0.3.3 #1)
 * this extends {@code JpaRepository} (not {@code CrudRepository}) to inherit the full CRUD,
 * sort, and paging API. The explicit {@code @Repository} stereotype marks the interface for
 * component scanning and activates Spring's
 * {@code PersistenceExceptionTranslationPostProcessor}, translating provider-specific
 * persistence exceptions into the {@code org.springframework.dao.DataAccessException}
 * hierarchy. This is a pure data-access component and carries no business logic &mdash;
 * SSN masking lives in {@code CustomerMapper}, and customer read/view orchestration lives in
 * {@code CustomerService} and {@code StatementService}.</p>
 *
 * @see com.carddemo.entity.Customer
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {
}
