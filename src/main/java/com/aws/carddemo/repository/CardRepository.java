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

import com.aws.carddemo.domain.Card;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link Card} (table {@code card}).
 *
 * <p>Replaces legacy VSAM {@code CARDDATA} KSDS access (copybook {@code CVACT02Y}). Primary key
 * {@code card_num} ({@code String}). The legacy non-unique alternate index on {@code CARD-ACCT-ID}
 * (LISTCAT AXRKP 16, DDL index {@code ix_card_acct_id}) backs {@link #findByCardAcctId(Long)},
 * which mirrors the {@code COCRDLIC} (CCLI) browse of all cards for an account. A missing
 * primary-key record returns {@link java.util.Optional#empty()} from {@code findById} (FILE STATUS
 * {@code '23'}).
 */
@Repository
public interface CardRepository extends JpaRepository<Card, String> {

  /**
   * Returns all cards for the given account id, mirroring the legacy non-unique alternate-index
   * browse on {@code CARD-ACCT-ID}. Returns an empty list when none match (never {@code null}).
   *
   * @param cardAcctId the owning account id ({@code card_acct_id})
   * @return matching cards (possibly empty)
   */
  List<Card> findByCardAcctId(Long cardAcctId);
}
