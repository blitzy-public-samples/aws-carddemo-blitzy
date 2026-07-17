/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.repository;

import com.aws.carddemo.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for the {@link Account} master-account entity.
 *
 * <p>This repository is the Java re-platforming of the legacy COBOL keyed access
 * to the VSAM KSDS {@code ACCTDATA.VSAM.KSDS} ({@code ACCTFILE}, copybook
 * {@code legacy/cpy/CVACT01Y.cpy}, record length 300). The dataset becomes the
 * PostgreSQL table {@code account}, keyed by {@code acct_id} (COBOL
 * {@code ACCT-ID PIC 9(11)}, VSAM {@code KEYLEN=11}, {@code RKP=0}). The
 * {@code group_id} column relates each account to its {@code disclosure_group}
 * (enforced in application logic exactly as the COBOL original did).</p>
 *
 * <h2>Legacy source access re-platformed here</h2>
 * <ul>
 *   <li><strong>Sequential browse</strong> &mdash; the account master-print batch
 *       {@code legacy/cbl/CBACT01C.cbl} ({@code ACCESS MODE IS SEQUENTIAL},
 *       {@code RECORD KEY IS FD-ACCT-ID}) and the account iteration in the
 *       interest-calculation batch {@code legacy/cbl/CBACT04C.cbl} read the
 *       accounts in key order. Reproduced by {@link #findAllByOrderByAcctIdAsc()}.</li>
 *   <li><strong>Keyed single-record read</strong> &mdash; the online account
 *       inquiry {@code legacy/cbl/COACTVWC.cbl} ({@code EXEC CICS READ} on
 *       {@code ACCTDAT} by account id) is served by the inherited
 *       {@link JpaRepository#findById(Object) findById(Long)}.</li>
 *   <li><strong>Read-update-rewrite</strong> &mdash; the online account update
 *       {@code legacy/cbl/COACTUPC.cbl} and the posting balance update
 *       {@code legacy/cbl/CBTRN02C.cbl} are served by
 *       {@link JpaRepository#findById(Object) findById(Long)} followed by
 *       {@link JpaRepository#save(Object) save(Account)}. The integrity of the
 *       COBOL READ-UPDATE-REWRITE cycle is provided by the entity's
 *       {@code @Version} optimistic lock inside the calling service's
 *       {@code @Transactional} boundary &mdash; this repository intentionally
 *       declares no {@code @Lock} annotation.</li>
 * </ul>
 *
 * <p>Monetary arithmetic and credit-limit checks are deliberately absent here;
 * those remain {@code service/} and {@code batch/} concerns operating on the
 * entity's {@link java.math.BigDecimal} fields.</p>
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Returns every account ordered by ascending account id.
     *
     * <p>Reproduces the {@code ACCTFILE} KSDS sequential ascending browse used by
     * the account master-print batch ({@code legacy/cbl/CBACT01C.cbl}) and the
     * account iteration in the interest-calculation batch
     * ({@code legacy/cbl/CBACT04C.cbl}), both of which process accounts in
     * primary-key order.</p>
     *
     * @return all accounts sorted by {@code acctId} ascending; never {@code null}
     */
    List<Account> findAllByOrderByAcctIdAsc();
}
