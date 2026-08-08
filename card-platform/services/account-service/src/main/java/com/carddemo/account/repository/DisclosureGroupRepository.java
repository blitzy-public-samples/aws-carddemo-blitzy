package com.carddemo.account.repository;

import com.carddemo.account.entity.DisclosureGroupEntity;
import org.springframework.data.repository.ListCrudRepository;

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
 * <p>These rows are reference data and this interface reads them. They arrive from
 * {@code src/main/resources/db/migration/V2__seed.sql}, which reproduces the {@code REPRO} step at
 * {@code app/jcl/DISCGRP.jcl:L54-L61} loading {@code app/data/ASCII/discgrp.txt}. No program in
 * {@code app/cbl/} writes, rewrites or deletes a disclosure group, so nothing in this platform
 * calls the write methods the base interface offers. The interface declares no method of its
 * own, and {@code com.carddemo.equivalence.RepositorySurfaceTest} holds it to that: a finder
 * added here would be a second access path the source does not have.</p>
 */
public interface DisclosureGroupRepository
        extends ListCrudRepository<DisclosureGroupEntity, DisclosureGroupEntity.DisclosureGroupId> {
}
