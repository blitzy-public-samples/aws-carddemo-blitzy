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

import com.aws.carddemo.domain.TransactionCategory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link TransactionCategory} reference table.
 *
 * <p>Replaces the COBOL VSAM keyed access to the {@code TRANCATG} KSDS (copybook
 * {@code CVTRA04Y.cpy}, record {@code TRAN-CAT-RECORD}) with set-based access to
 * the PostgreSQL {@code transaction_category} table. In the legacy application
 * this dataset is read at {@code legacy/cbl/CBTRN03C.cbl} (paragraph
 * {@code 1500-C-LOOKUP-TRANCATG}), which performs a random {@code READ} on the
 * composite record key {@code FD-TRAN-CAT-KEY} to resolve a transaction category
 * description ({@code TRAN-CAT-TYPE-DESC}) while printing the transaction detail
 * report.</p>
 *
 * <p>The COBOL group {@code TRAN-CAT-KEY} (VSAM KEYLEN=6 =
 * {@code TRAN-TYPE-CD PIC X(02)} + {@code TRAN-CAT-CD PIC 9(04)}) is modeled as
 * the composite primary key {@code (type_cd, cat_cd)}. The identifier type is
 * therefore the nested {@code @Embeddable}
 * {@link TransactionCategory.TransactionCategoryId}, which is used as the second
 * type argument to {@link JpaRepository}.</p>
 *
 * <p>The legacy keyed {@code READ} maps directly to the inherited
 * {@link JpaRepository#findById(Object)} using a fully populated
 * {@code new TransactionCategory.TransactionCategoryId(typeCd, catCd)}. Because
 * this is a small, static reference table, the inherited CRUD operations
 * ({@code findById}, {@code findAll}, {@code save}) are sufficient and no custom
 * query methods are declared &mdash; repositories carry no business logic.</p>
 */
@Repository
public interface TransactionCategoryRepository
        extends JpaRepository<TransactionCategory, TransactionCategory.TransactionCategoryId> {
    // Keyed access uses the inherited findById(TransactionCategory.TransactionCategoryId);
    // enumeration uses findAll(). No custom methods are required for this
    // compound-key reference table.
}
