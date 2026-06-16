package com.carddemo.repository;

import com.carddemo.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for the {@link Customer} aggregate, providing
 * persistence access to the relational {@code customers} table.
 *
 * <p><strong>Legacy source.</strong> This repository abstracts the data-access
 * behavior that the legacy CardDemo system performed against the VSAM KSDS dataset
 * {@code AWS.M2.CARDDEMO.CUSTDATA}, whose {@code CUSTOMER-RECORD} layout is defined
 * by the COBOL copybook {@code app/cpy/CVCUS01Y.cpy} and mapped 1:1 onto the
 * {@link Customer} entity. The migration replaces the COBOL {@code READ}/{@code WRITE}/
 * {@code REWRITE} file operations and the primary-key access on {@code CUST-ID}
 * (VSAM key length 9, RKP 0) with Spring Data JPA CRUD, per the repository-layer
 * transformation plan of AAP &sect;0.4.1.2 and the layered/repository design pattern
 * of AAP &sect;0.3.2.</p>
 *
 * <p><strong>Contract.</strong> The repository extends
 * {@code JpaRepository<Customer, Long>}. The identifier type is {@link Long} because
 * {@link Customer} declares an assigned primary key {@code @Id private Long custId}
 * mapped to column {@code cust_id BIGINT} (the legacy 9-digit {@code CUST-ID PIC 9(09)}).
 * Extending {@link JpaRepository} supplies the full complement of standard persistence
 * operations &mdash; for example {@link JpaRepository#save(Object) save},
 * {@link JpaRepository#findById(Object) findById}, {@link JpaRepository#findAll()
 * findAll}, {@link JpaRepository#existsById(Object) existsById},
 * {@link JpaRepository#count() count}, and {@link JpaRepository#deleteById(Object)
 * deleteById}.</p>
 *
 * <p><strong>Access pattern.</strong> Only the inherited CRUD surface is required;
 * this interface intentionally declares <em>no</em> derived or custom query methods.
 * The account- and card-detail flows that need customer data first resolve the
 * customer identifier through the card cross-reference (the {@code CardXref}
 * {@code xrefCustId} field) and then load the customer via the inherited
 * {@link JpaRepository#findById(Object) findById(Long custId)}, so no
 * {@code findByCustId}-style lookups are needed here.</p>
 *
 * <p><strong>PII boundary.</strong> The {@code customers} table carries personally
 * identifiable information &mdash; the full Social Security Number, the
 * government-issued identifier, and the date of birth. Suppression of that data
 * (for example, exposing the SSN as at most its last four digits) is enforced at the
 * DTO/mapper boundary and reinforced by Logback masking per AAP &sect;0.6.8; it is
 * deliberately <em>not</em> a concern of this repository, which returns the entity
 * as persisted.</p>
 *
 * <p><strong>Component detection.</strong> No {@code @Repository} annotation is
 * declared: Spring Data automatically detects and instantiates a proxy for this
 * interface during repository scanning, and the persistence-exception translation it
 * would enable is already applied to Spring Data JPA repositories.</p>
 */
public interface CustomerRepository extends JpaRepository<Customer, Long> {
}
