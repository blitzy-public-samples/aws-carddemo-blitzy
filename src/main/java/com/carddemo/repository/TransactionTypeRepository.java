package com.carddemo.repository;

import com.carddemo.entity.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link TransactionType} reference data.
 *
 * <p>Replaces VSAM keyed access to the original {@code TRANTYPE} file. The underlying
 * {@code transaction_types} table maps the 60-byte {@code TRAN-TYPE-RECORD} layout from
 * {@code app/cpy/CVTRA03Y.cpy} (CardDemo_v1.0-15-g27d6c6f-68): {@code TRAN-TYPE PIC X(02)}
 * (the natural primary key) and {@code TRAN-TYPE-DESC PIC X(50)} (the description); the
 * trailing {@code FILLER PIC X(08)} is not mapped. The seven standard transaction types
 * (<em>01 Purchase, 02 Payment, 03 Credit, 04 Authorization, 05 Refund, 06 Reversal,
 * 07 Adjustment</em>) are seeded by {@code DataInitializationJobConfig} from
 * {@code app/data/ASCII/trantype.txt}.</p>
 *
 * <p>This is immutable reference/lookup data with a simple single-column primary key, so
 * the inherited {@code JpaRepository} operations satisfy every consumer and no custom finder
 * methods are required (AAP &sect;0.4.1.5). The original CICS/VSAM access verbs this
 * interface supersedes:</p>
 * <ul>
 *   <li>{@code findById(String typeCd)} &mdash; replaces {@code EXEC CICS READ} with
 *       {@code RIDFLD(TRAN-TYPE-CD)} (keyed lookup by transaction-type code). The COBOL
 *       {@code DFHRESP(NOTFND)} branch maps to {@code Optional.empty()}.</li>
 *   <li>{@code findAll()} &mdash; replaces the sequential full-file scan performed during
 *       initialization and reference-data enumeration.</li>
 *   <li>{@code save(TransactionType)} / {@code saveAll(Iterable)} &mdash; replace
 *       {@code EXEC CICS WRITE} during the initial data load.</li>
 *   <li>{@code existsById(String)}, {@code count()}, {@code deleteById(String)},
 *       {@code delete(TransactionType)} &mdash; standard inherited maintenance operations.</li>
 * </ul>
 *
 * <p><strong>Consumers.</strong> Transaction validation (resolving the transaction-type code
 * carried by {@link com.carddemo.entity.Transaction} and
 * {@link com.carddemo.entity.TransactionCategory}), reference-data enumeration, and the
 * batch data-initialization load. All access is by primary key or full scan; there is no
 * alternate-index (AIX) equivalent for this table.</p>
 *
 * <p><strong>Primary key (PR-13).</strong> The {@link TransactionType} entity's {@code @Id}
 * is {@code String typeCd} mapping {@code TRAN-TYPE PIC X(02)} &rarr;
 * {@code tran_type CHAR(2) NOT NULL PRIMARY KEY}; this interface therefore extends
 * {@code JpaRepository<TransactionType, String>} (identifier type {@code String}). The
 * inherited {@code findById}, {@code existsById}, and {@code deleteById} signatures all bind
 * to the {@code String} key accordingly.</p>
 *
 * <p><strong>No optimistic locking.</strong> Unlike {@link com.carddemo.entity.Account},
 * {@link com.carddemo.entity.Card}, {@link com.carddemo.entity.Customer}, and
 * {@link com.carddemo.entity.Transaction}, this lookup table carries no {@code @Version}
 * column (AAP &sect;0.3.3 restricts {@code @Version} to those four entities): the rows are
 * seeded once and are not subject to concurrent modification.</p>
 *
 * <p><strong>Pattern &amp; scope.</strong> Per the Repository Pattern (AAP &sect;0.3.3 #1)
 * this extends {@code JpaRepository} (not {@code CrudRepository}) to inherit the full CRUD,
 * sort, and paging API. The explicit {@code @Repository} stereotype marks the interface for
 * component scanning and activates Spring's
 * {@code PersistenceExceptionTranslationPostProcessor}, translating provider-specific
 * persistence exceptions into the {@code org.springframework.dao.DataAccessException}
 * hierarchy. This is a pure data-access component and carries no business logic. Spring Data
 * uses standard {@code org.springframework.*} imports here (PR-28 reserves the
 * {@code jakarta.*} namespace for the entity's persistence annotations).</p>
 *
 * @see com.carddemo.entity.TransactionType
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@Repository
public interface TransactionTypeRepository extends JpaRepository<TransactionType, String> {
}
