package com.carddemo.repository;

import com.carddemo.entity.TransactionCategory;
import com.carddemo.entity.TransactionCategoryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link TransactionCategory} reference data.
 *
 * <p>Replaces VSAM keyed access to the original {@code TRANCATG} file. The underlying
 * {@code transaction_categories} table maps the 60-byte {@code TRAN-CAT-RECORD} layout from
 * {@code app/cpy/CVTRA04Y.cpy} (CardDemo_v1.0-15-g27d6c6f-68), lines 4-9, whose key is the
 * concatenation of the transaction-type code and the transaction-category code:</p>
 * <pre>
 *   05 TRAN-CAT-KEY.
 *     10 TRAN-TYPE-CD  PIC X(02).   --&gt; typeCd      (type_cd CHAR(2))
 *     10 TRAN-CAT-CD   PIC 9(04).   --&gt; categoryCd  (cat_cd  CHAR(4))
 *   05 TRAN-CAT-TYPE-DESC PIC X(50). --&gt; categoryDesc
 * </pre>
 *
 * <p><strong>Composite primary key (PR-15).</strong> Because the COBOL record is keyed on two
 * fields, the {@link TransactionCategory} entity declares an {@code @EmbeddedId} of type
 * {@link TransactionCategoryId} (field order {@code type_cd} then {@code cat_cd}, mirroring the
 * COBOL key concatenation). This interface therefore extends
 * {@code JpaRepository<TransactionCategory, TransactionCategoryId>}: the inherited
 * {@code findById}, {@code existsById}, and {@code deleteById} signatures all bind to the
 * {@link TransactionCategoryId} composite-key type. A lookup is performed by constructing the
 * embeddable key, e.g. {@code findById(new TransactionCategoryId(typeCd, catCd))}.</p>
 *
 * <p>This is immutable reference/lookup data, so the inherited {@code JpaRepository} operations
 * satisfy every consumer and no custom finder methods are required (AAP &sect;0.4.1.5). The 18
 * standard transaction categories are seeded by {@code V3__seed_reference_data.sql} from
 * {@code app/data/ASCII/trancatg.txt}. The original CICS/VSAM access verbs this interface
 * supersedes:</p>
 * <ul>
 *   <li>{@code findById(TransactionCategoryId)} &mdash; replaces {@code EXEC CICS READ} with
 *       {@code RIDFLD} on the concatenated {@code (TRAN-TYPE-CD, TRAN-CAT-CD)} key. The COBOL
 *       {@code DFHRESP(NOTFND)} branch maps to {@code Optional.empty()}.</li>
 *   <li>{@code findAll()} &mdash; replaces the sequential full-file scan performed during
 *       reference-data enumeration and validation.</li>
 *   <li>{@code save(TransactionCategory)} / {@code saveAll(Iterable)} &mdash; replace
 *       {@code EXEC CICS WRITE} during the initial reference-data load.</li>
 *   <li>{@code existsById(TransactionCategoryId)}, {@code count()},
 *       {@code deleteById(TransactionCategoryId)} &mdash; standard inherited maintenance
 *       operations.</li>
 * </ul>
 *
 * <p><strong>Consumers.</strong> Transaction validation (resolving the
 * {@code (type, category)} pair carried by {@link com.carddemo.entity.Transaction} and
 * {@link com.carddemo.entity.DailyTransaction}) and reference-data enumeration. All access is by
 * primary key or full scan; there is no alternate-index (AIX) equivalent for this table.</p>
 *
 * <p><strong>No optimistic locking.</strong> This lookup table carries no {@code @Version}
 * column (AAP &sect;0.3.3 restricts {@code @Version} to {@code Account}, {@code Card},
 * {@code Customer}, and {@code Transaction}): the rows are seeded once and are not subject to
 * concurrent modification.</p>
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
 * @see com.carddemo.entity.TransactionCategory
 * @see com.carddemo.entity.TransactionCategoryId
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@Repository
public interface TransactionCategoryRepository
        extends JpaRepository<TransactionCategory, TransactionCategoryId> {
}
