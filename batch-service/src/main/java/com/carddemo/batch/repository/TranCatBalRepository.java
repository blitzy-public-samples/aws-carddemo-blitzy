/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.batch.repository;

import com.carddemo.common.domain.TranCatBal;
import com.carddemo.common.domain.TranCatBalId;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link TranCatBal} transaction-category-balance entity.
 *
 * :purpose: Replaces legacy VSAM TCATBAL (KSDS) sequential-by-key access for the batch tier. It
 *     is the account-ordered driving reader source for the interest-calculation job
 *     (``CBACT04C``), which performs a control-break on the account identifier, and for the
 *     PRTCATBL category-balance print job. The composite key is modeled by {@link TranCatBalId}
 *     (account id, transaction type code and transaction category code); random reads and
 *     rewrites become the inherited ``findById`` and ``save`` operations.
 * :output: Managed {@link TranCatBal} instances; the declared query returns rows ordered by the
 *     full composite key ascending so a downstream control-break on ``trancatAcctId`` sees
 *     contiguous per-account rows.
 */
@Repository
public interface TranCatBalRepository extends JpaRepository<TranCatBal, TranCatBalId> {

    /**
     * Reads transaction-category-balance rows in ascending full-composite-key order for chunked
     * batch processing.
     *
     * :purpose: Backs the ``CBACT04C`` interest calculation and the PRTCATBL report, reproducing
     *     the VSAM TCATBAL sequential read in ``(trancatAcctId, trancatTypeCd, trancatCd)`` key
     *     order so that all category-balance rows for one account arrive contiguously for the
     *     per-account control-break.
     * :param pageable: the paging directive (page number and size) supplied by the batch reader
     *     for chunked iteration.
     * :return: the requested page of category-balance rows ordered by ``trancatAcctId``,
     *     ``trancatTypeCd`` then ``trancatCd`` ascending; an empty list once no further rows
     *     remain.
     */
    List<TranCatBal> findAllByOrderByTrancatAcctIdAscTrancatTypeCdAscTrancatCdAsc(Pageable pageable);
}
