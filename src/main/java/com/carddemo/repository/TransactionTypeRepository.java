package com.carddemo.repository;

import com.carddemo.entity.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for the {@link TransactionType} reference/lookup entity.
 *
 * <p>This interface abstracts all data access to the {@code transaction_type} table, the
 * relational replacement for the legacy VSAM {@code TRANTYPE} key-sequenced dataset whose
 * record layout is defined by copybook {@code app/cpy/CVTRA03Y.cpy} ({@code TRAN-TYPE-RECORD},
 * {@code RECLN = 60}). It supersedes the direct COBOL VSAM reads of the two-character
 * transaction-type code with idiomatic, declarative Spring Data access (AAP &sect;0.3.2
 * Repository pattern, &sect;0.4.1.2).</p>
 *
 * <p>The identifier type is {@code String} because the sibling entity
 * {@link TransactionType} declares an assigned business key,
 * {@code @Id private String typeCd} (column {@code type_cd CHAR(2)}, values {@code "01".."07"});
 * the key is supplied by the V2 reference seed and the application, never database-generated.</p>
 *
 * <h2>Provided operations</h2>
 * <p>This repository exposes <strong>standard {@link JpaRepository} CRUD only</strong> — no
 * derived or custom query methods are declared, because none are required. Consumers such as
 * {@code TransactionService} and the transaction-posting / interest-calculation batch validation
 * resolve transaction types through the inherited operations, principally
 * {@link JpaRepository#findById(Object) findById(String)},
 * {@code existsById(String)}, {@link JpaRepository#findAll() findAll()}, and
 * {@link JpaRepository#save(Object) save(...)}. For example, {@code findById("01")} returns the
 * seeded {@code "Purchase"} row.</p>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li><strong>No {@code @Repository} annotation</strong> — Spring Data JPA automatically detects
 *       {@link JpaRepository} sub-interfaces under the {@code com.carddemo} component scan rooted at
 *       the {@code @SpringBootApplication} bootstrap class {@code CardDemoApplication} (which uses
 *       the default base-package scan with no narrowing {@code @EnableJpaRepositories}). A proxy
 *       implementation is generated and registered at runtime; an explicit stereotype annotation is
 *       therefore redundant and is omitted for idiomatic style.</li>
 *   <li><strong>No business logic</strong> — per the layered architecture (AAP &sect;0.3.2),
 *       repositories perform data access only; business rules live in the {@code service/} layer.
 *       The interface body is intentionally empty.</li>
 * </ul>
 *
 * @see TransactionType
 * @see JpaRepository
 */
public interface TransactionTypeRepository extends JpaRepository<TransactionType, String> {
}
