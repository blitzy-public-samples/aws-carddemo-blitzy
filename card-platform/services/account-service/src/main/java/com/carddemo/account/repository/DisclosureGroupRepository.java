package com.carddemo.account.repository;

import com.carddemo.account.entity.DisclosureGroupEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Stores and retrieves the disclosure group aggregate and its interest rate, table
 * {@code disclosure_group} in the private schema of the account service.
 *
 * <p>{@code app/cbl/CBSTM03B.CBL:L99-L112} declares a generic parameter area whose operation code
 * selects the access path, a contract this package reproduces as one interface per aggregate.</p>
 *
 * <p>The composite identifier spans sixteen characters, matching {@code KEYS(16 0)} at
 * {@code app/jcl/DISCGRP.jcl:L40}, and decomposes as {@code 05 DIS-GROUP-KEY.} does at
 * {@code app/cpy/CVTRA02Y.cpy:L5-L8}: {@code DIS-ACCT-GROUP-ID PIC X(10)},
 * {@code DIS-TRAN-TYPE-CD PIC X(02)} and {@code DIS-TRAN-CAT-CD PIC 9(04)}. The single non-key
 * field is {@code DIS-INT-RATE PIC S9(04)V99} at {@code app/cpy/CVTRA02Y.cpy:L9}.</p>
 *
 * <p>{@code card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/InterestCalculationEquivalenceTest.java}
 * reads these rows to verify the interest rate rules and the {@code DEFAULT} group fallback, while
 * this service computes no interest and exposes no interest endpoint.</p>
 *
 * <p>These rows are reference data, and the interface exposes reads alone. They arrive from the
 * seed migration {@code src/main/resources/db/migration/V2__seed.sql}, which reproduces the
 * {@code REPRO} step at {@code app/jcl/DISCGRP.jcl:L54-L61} loading
 * {@code app/data/ASCII/discgrp.txt}. {@code 0200-DISCGRP-OPEN} issues
 * {@code OPEN INPUT DISCGRP-FILE} at {@code app/cbl/CBACT04C.cbl:L272}, and no program in
 * {@code app/cbl/} writes, rewrites or deletes a disclosure group. Extending {@link Repository}
 * keeps {@code save} and every {@code delete} off the interface, so no caller can reach an
 * operation the aggregate does not support.</p>
 */
public interface DisclosureGroupRepository
        extends Repository<DisclosureGroupEntity, DisclosureGroupEntity.DisclosureGroupId> {

    /**
     * Finds the interest rate row for one composite group key.
     *
     * <p>{@code 1200-GET-INTEREST-RATE} at {@code app/cbl/CBACT04C.cbl:L415-L420} reads on the
     * supplied key. On a miss its caller moves the literal {@code DEFAULT} into the group
     * identifier at {@code :L437} and reads again, so resolving one rate can take two reads.
     *
     * @param key the group identifier, transaction type and transaction category
     * @return the row, or an empty {@code Optional} when the table holds no such key
     */
    Optional<DisclosureGroupEntity> findById(DisclosureGroupEntity.DisclosureGroupId key);

    /**
     * Returns every disclosure group row.
     *
     * <p>The table holds the 51 rows of {@code app/data/ASCII/discgrp.txt} and grows only when a
     * later seed migration adds one, so this read is bounded by the reference data itself.
     *
     * @return all rows, in no guaranteed order
     */
    List<DisclosureGroupEntity> findAll();
}
