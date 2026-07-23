package com.aws.carddemo.repository;

import com.aws.carddemo.domain.TransactionType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link TransactionType} reference entity.
 *
 * <p>This repository replaces the legacy VSAM {@code TRANTYPE} reference-file I/O with
 * Spring Data access over PostgreSQL. In the mainframe application {@code TRANTYPE} is a
 * small, static key-sequenced (KSDS) lookup table whose records are read randomly by the
 * two-character transaction-type code to resolve and validate transaction-type
 * descriptions. That access path is preserved here by typing the repository on the
 * primary key so that {@link JpaRepository#findById(Object) findById(String)} performs
 * the equivalent keyed lookup, while {@link JpaRepository#findAll()} covers full-table
 * reference reads and seed loading.</p>
 *
 * <p>Lookups are keyed by {@code tranType} (COBOL {@code TRAN-TYPE PIC X(02)}), the
 * entity primary key. Because this is a reference table, no derived-query finders are
 * declared: the inherited CRUD surface (for example {@code findById}, {@code findAll},
 * {@code existsById}, and {@code save}) is the complete and intended access contract, in
 * line with the migration's no-feature-expansion rule.</p>
 *
 * <p>Origin: legacy/cpy/CVTRA03Y.cpy (TRAN-TYPE-RECORD, RECLN 60); VSAM TRANTYPE
 * reference table.</p>
 */
@Repository
public interface TransactionTypeRepository extends JpaRepository<TransactionType, String> {
}
