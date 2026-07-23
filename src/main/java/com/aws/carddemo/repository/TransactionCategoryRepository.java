package com.aws.carddemo.repository;

import com.aws.carddemo.domain.TransactionCategory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link TransactionCategory}, replacing the
 * legacy VSAM {@code TRANCATG} reference-file I/O with relational access over
 * PostgreSQL.
 *
 * <p>Origin: legacy/cpy/CVTRA04Y.cpy (TRAN-CAT-RECORD, RECLN 60); VSAM TRANCATG;
 * composite key TRAN-CAT-KEY (type+category).</p>
 *
 * <p>In the mainframe system {@code TRANCATG} is a keyed reference/lookup file
 * whose base-cluster key is the composite {@code TRAN-CAT-KEY}
 * (transaction-type + transaction-category). The COBOL access path is a random
 * {@code READ} by that composite key; this repository preserves those semantics
 * exactly by typing its identifier parameter on the entity's nested composite
 * identifier class {@link TransactionCategory.TransactionCategoryId}, which
 * mirrors the entity's {@code @IdClass} declaration.</p>
 *
 * <p>This is a reference-lookup repository only, in keeping with the migration's
 * no-feature-expansion rule: no derived-query finders, JPQL, or custom methods
 * are declared. The inherited CRUD surface is the complete contract. Composite
 * key lookup is performed via
 * {@code findById(new TransactionCategory.TransactionCategoryId(typeCd, catCd))},
 * and {@code findAll()} supports reference/seed loading of the full lookup
 * table.</p>
 *
 * <p>The backing table {@code transaction_category} (primary key
 * {@code (tran_type_cd, tran_cat_cd)}) is created and seeded by the Flyway
 * migrations; this interface performs no schema management and is discovered by
 * Spring Boot's repository auto-configuration through the application's
 * base-package component scan.</p>
 */
@Repository
public interface TransactionCategoryRepository
        extends JpaRepository<TransactionCategory, TransactionCategory.TransactionCategoryId> {
}
