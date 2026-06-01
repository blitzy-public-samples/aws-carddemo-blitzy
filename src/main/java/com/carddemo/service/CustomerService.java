package com.carddemo.service;

import com.carddemo.dto.customer.CustomerDto;
import com.carddemo.entity.Customer;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.mapper.CustomerMapper;
import com.carddemo.repository.CustomerRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer retrieval service replacing the customer-info portion of
 * {@code app/cbl/COACTVWC.cbl} (CICS online program, TRANID {@code 'CAVW'}).
 *
 * <p>In the original COBOL program the account-view transaction performs a
 * <strong>two-step</strong> lookup: it first reads the card cross-reference
 * ({@code CXACAIX}) by account id to obtain the customer id, then reads the
 * customer master ({@code CUSTDAT}) by customer id in paragraph
 * {@code 9400-GETCUSTDATA-BYCUST} ({@code app/cbl/COACTVWC.cbl} lines 825-870),
 * after which {@code 1100-SCREEN-VARS-DISP} populates the customer detail fields
 * for display. This service handles the <em>second</em> step &mdash; the keyed
 * customer-master read &mdash; exposing it as {@link #getCustomer(Long)} behind
 * {@code GET /api/customers/{custId}}. The cross-reference (account &rarr;
 * customer) resolution is handled separately by {@code AccountService}.</p>
 *
 * <p>The COBOL {@code EXEC CICS READ DATASET('CUSTDAT') RIDFLD(CUST-ID)} keyed
 * read is reproduced by {@link CustomerRepository#findById(Object)}; the COBOL
 * {@code DFHRESP(NOTFND)} branch maps to {@link java.util.Optional#empty()},
 * which this service surfaces as {@link AccountNotFoundException} carrying the
 * exact COBOL message literal
 * {@code "Did not find associated customer in master file"}
 * ({@code app/cbl/COACTVWC.cbl} line 134, 88-level
 * {@code DID-NOT-FIND-CUST-IN-CUSTDAT}). {@code GlobalExceptionHandler} maps that
 * exception to HTTP 404 and copies the message verbatim onto the error payload.</p>
 *
 * <p><strong>PR-20 &mdash; SSN masking (CRITICAL SECURITY BOUNDARY).</strong> The
 * raw 9-digit Social Security Number stored on the {@link Customer} entity is PII
 * and is <em>never</em> returned in plain form. Masking to {@code ***-**-####}
 * (only the last four digits visible) is performed exclusively by
 * {@link CustomerMapper#toDto(Customer)}. This service therefore maps the managed
 * entity to a {@link CustomerDto} through the mapper and never reads, logs, or
 * otherwise exposes {@code customer.getSsn()} directly &mdash; the mapper is the
 * single authoritative point at which customer data crosses the REST boundary.</p>
 *
 * <p><strong>PR-24 &mdash; transactional boundary.</strong> {@link #getCustomer(Long)}
 * is annotated {@code @Transactional(readOnly = true)}, bracketing the lookup in a
 * read-only transaction scope (replacing the implicit CICS unit-of-work) and
 * enabling Hibernate to skip dirty-checking/flush for this query-only path.</p>
 *
 * <p><strong>PR-23 &mdash; lock ordering.</strong> This is a read-only lookup that
 * acquires no write locks, so it cannot participate in a deadlock; it is the first
 * link ({@code CUSTOMER}) in the documented {@code CUSTOMER -> ACCOUNT -> CARD ->
 * TRANSACTION} lock-acquisition order observed by the multi-entity write services.</p>
 *
 * <p><strong>PR-28 / PR-29.</strong> All imports use the {@code jakarta.*}-aligned
 * Spring stack (no {@code javax.*}); dependencies are injected by constructor over
 * {@code final} fields via Lombok {@link RequiredArgsConstructor} &mdash; no field
 * injection.</p>
 *
 * @see com.carddemo.mapper.CustomerMapper
 * @see com.carddemo.repository.CustomerRepository
 * @see com.carddemo.exception.AccountNotFoundException
 * @since 1.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerService {

    /**
     * Spring Data JPA repository for the customer master ({@code CUSTDAT}).
     * Injected by constructor (PR-29). Supplies the keyed
     * {@link CustomerRepository#findById(Object)} lookup that replaces the COBOL
     * {@code EXEC CICS READ DATASET('CUSTDAT') RIDFLD(CUST-ID)}.
     */
    private final CustomerRepository customerRepository;

    /**
     * Entity&rarr;DTO mapper and PR-20 SSN-masking security boundary. Injected by
     * constructor (PR-29). {@link CustomerMapper#toDto(Customer)} produces the
     * outbound {@link CustomerDto} with the SSN already masked to
     * {@code ***-**-####}; this service never accesses the raw SSN itself.
     */
    private final CustomerMapper customerMapper;

    /**
     * Looks up a single customer by primary key and returns the masked
     * {@link CustomerDto} for {@code GET /api/customers/{custId}}.
     *
     * <p>Ports the customer-master read of {@code app/cbl/COACTVWC.cbl} paragraph
     * {@code 9400-GETCUSTDATA-BYCUST}: the {@link CustomerRepository#findById(Object)}
     * keyed read reproduces {@code EXEC CICS READ DATASET('CUSTDAT') RIDFLD(CUST-ID)},
     * and an empty {@link java.util.Optional} (the COBOL {@code DFHRESP(NOTFND)}
     * branch) raises {@link AccountNotFoundException} carrying the exact COBOL
     * message {@code "Did not find associated customer in master file"}
     * ({@code COACTVWC.cbl} line 134). The found entity is converted by
     * {@link CustomerMapper#toDto(Customer)}, which masks the SSN per PR-20 before
     * it can leave the server.</p>
     *
     * <p>Runs read-only and transactional (PR-24). No write locks are taken (PR-23).
     * The {@code custId} is logged at {@code DEBUG} only; the returned DTO's PII
     * (SSN, government id, date of birth, phone numbers) is never logged.</p>
     *
     * @param custId the customer primary key ({@code CUST-ID PIC 9(09)}); the
     *               REST/validation layer guarantees a non-null value for the path
     *               variable
     * @return the customer as a {@link CustomerDto} with the SSN masked to
     *         {@code ***-**-####} (PR-20)
     * @throws AccountNotFoundException if no customer exists for {@code custId}
     *         (COBOL {@code DFHRESP(NOTFND)} &rarr; HTTP 404), with the exact message
     *         {@code "Did not find associated customer in master file"}
     */
    @Transactional(readOnly = true)
    public CustomerDto getCustomer(Long custId) {
        log.debug("Looking up customer {}", custId);
        Customer customer = customerRepository.findById(custId)
                .orElseThrow(() -> AccountNotFoundException.withMessage(
                        "Did not find associated customer in master file"));
        // PR-20: SSN masking happens inside the mapper; service code never reads
        // the raw SSN. The DTO returned here already carries the masked value.
        return customerMapper.toDto(customer);
    }
}
