/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.repository;

import com.aws.carddemo.domain.TransactionType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for the {@link TransactionType} reference entity.
 *
 * <p>Migrated from the COBOL keyed access to the {@code TRANTYPE} VSAM KSDS,
 * whose record layout is defined by the copybook {@code CVTRA03Y.cpy}
 * (relocated to {@code legacy/cpy/CVTRA03Y.cpy}). The batch transaction-detail
 * report program {@code CBTRN03C} (relocated to
 * {@code legacy/cbl/CBTRN03C.cbl}) opens the dataset {@code OPEN INPUT} and, in
 * paragraph {@code 1500-B-LOOKUP-TRANTYPE}, issues a keyed
 * {@code READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD} to resolve the two-character
 * transaction-type code to its description ({@code TRAN-TYPE-DESC}) for each
 * report line. That record-at-a-time indexed read is re-expressed here as
 * set-based access over the PostgreSQL {@code transaction_type} table
 * (AAP repository pattern; VSAM KSDS &rarr; Spring Data repository).</p>
 *
 * <p>Keyed lookups are provided by the inherited
 * {@link JpaRepository#findById(Object)} using the natural key {@code type_cd}
 * (COBOL {@code TRAN-TYPE PIC X(02)}, VSAM {@code KEYLEN=2}); the inherited
 * {@code existsById}, {@code findAll}, and {@code save} operations are likewise
 * available and are intentionally not redeclared. Only the deterministic
 * ordered listing below is added, because {@code transaction_type} is a small,
 * immutable reference table. This interface declares data-access operations
 * only; business logic lives in the service layer.</p>
 */
@Repository
public interface TransactionTypeRepository extends JpaRepository<TransactionType, String> {

    /**
     * Returns every transaction-type reference row ordered by ascending type
     * code ({@code type_cd}), reproducing the deterministic sequential read of
     * the {@code TRANTYPE} KSDS by ascending key used when resolving type
     * descriptions.
     *
     * @return an ordered list of all {@link TransactionType} rows sorted by
     *         {@code typeCd} ascending; empty when the table contains no rows
     */
    List<TransactionType> findAllByOrderByTypeCdAsc();
}
