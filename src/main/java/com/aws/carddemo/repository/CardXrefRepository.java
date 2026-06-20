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

import com.aws.carddemo.domain.CardXref;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link CardXref} (table {@code card_xref}).
 *
 * <p>Replaces legacy VSAM {@code CARDXREF} KSDS access (copybook {@code CVACT03Y}). Primary key
 * {@code xref_card_num} ({@code String}) — random read by card number (e.g. {@code CBTRN02C}). The
 * non-unique alternate index on {@code XREF-ACCT-ID} (LISTCAT AXRKP 25, DDL index {@code
 * ix_card_xref_acct_id}) backs {@link #findByXrefAcctId(Long)}, mirroring the {@code COACTVWC}
 * (CAVW) read-by-account path. A missing primary-key record returns {@link
 * java.util.Optional#empty()} from {@code findById} (FILE STATUS {@code '23'}).
 */
@Repository
public interface CardXrefRepository extends JpaRepository<CardXref, String> {

  /**
   * Returns all card cross-reference rows for the given account id, mirroring the legacy non-unique
   * alternate-index path on {@code XREF-ACCT-ID}. Returns an empty list when none match (never
   * {@code null}); callers needing a single row take the first element.
   *
   * @param xrefAcctId the account id ({@code xref_acct_id})
   * @return matching cross-reference rows (possibly empty)
   */
  List<CardXref> findByXrefAcctId(Long xrefAcctId);
}
