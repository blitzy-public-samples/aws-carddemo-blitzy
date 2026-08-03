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
 */
public interface DisclosureGroupRepository
        extends ListCrudRepository<DisclosureGroupEntity, DisclosureGroupEntity.DisclosureGroupId> {
}
