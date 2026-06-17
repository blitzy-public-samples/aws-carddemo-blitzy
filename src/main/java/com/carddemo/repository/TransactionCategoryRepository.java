package com.carddemo.repository;

import com.carddemo.entity.TransactionCategory;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for the {@link TransactionCategory} lookup entity,
 * providing data access to the relational {@code transaction_category} table.
 *
 * <p><b>Legacy source.</b> This repository abstracts access to the data migrated
 * from the legacy VSAM KSDS dataset {@code TRANCATG}, whose record layout is
 * defined by the COBOL copybook {@code app/cpy/CVTRA04Y.cpy}
 * ({@code TRAN-CAT-RECORD}). The original 6-byte VSAM key
 * (verified in {@code app/catlg/LISTCAT.txt}: {@code TRANCATG KEYLEN=6}) is a
 * 2-character transaction type code plus a 4-digit transaction category code,
 * which the entity models as the two-column composite primary key
 * {@code (type_cd, cat_cd)}.</p>
 *
 * <p><b>Identifier type.</b> Because the entity uses a composite primary key, the
 * repository's identifier type is the entity's nested, public, static
 * {@code @Embeddable} key class {@link TransactionCategory.TransactionCategoryId}
 * (fields {@code typeCd} &rarr; {@code type_cd CHAR(2)} and {@code catCd} &rarr;
 * {@code cat_cd INTEGER}). The id class is deliberately nested within the entity
 * to preserve the convention of exactly ten entity files in the
 * {@code com.carddemo.entity} package while remaining referenceable here.</p>
 *
 * <p><b>Operations.</b> Only the standard {@link JpaRepository} CRUD contract is
 * required; the {@code TRANCATG} dataset is a small reference/lookup table with no
 * alternate-index browse semantics, so no derived or custom query methods are
 * declared. Consumers resolve a category by its full composite key and validate
 * foreign-key references during transaction posting via the inherited methods, for
 * example:</p>
 * <pre>{@code
 *   // Look up "Regular Sales Draft" (seeded by V2 migration):
 *   Optional<TransactionCategory> category =
 *       transactionCategoryRepository.findById(
 *           new TransactionCategory.TransactionCategoryId("01", 1));
 *
 *   // FK-style existence check used during daily transaction posting:
 *   boolean exists = transactionCategoryRepository.existsById(
 *       new TransactionCategory.TransactionCategoryId("01", 5));
 * }</pre>
 *
 * <p><b>Component detection.</b> The interface is intentionally not annotated with
 * {@code @Repository}; Spring Data automatically detects and creates a proxy
 * implementation for interfaces extending {@link JpaRepository} during the
 * component scan rooted at {@code com.carddemo}.</p>
 *
 * @see TransactionCategory
 * @see TransactionCategory.TransactionCategoryId
 */
public interface TransactionCategoryRepository
        extends JpaRepository<TransactionCategory, TransactionCategory.TransactionCategoryId> {
}
