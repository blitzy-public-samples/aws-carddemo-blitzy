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

import com.carddemo.common.domain.Card;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * :purpose: Spring Data JPA repository for the {@link Card} master entity;
 *  replaces the legacy VSAM CARDFILE (KSDS) access used by the ``CBACT02C``
 *  card read/print batch program.
 * :output: {@link Card} instances through the CRUD operations inherited from
 *  {@link JpaRepository}, plus a card-number-ordered sequential scan.
 */
@Repository
public interface CardRepository extends JpaRepository<Card, String> {

    /**
     * :purpose: Read cards in ``cardNum`` (card-number) ascending order,
     *  mirroring the ``CBACT02C``/READCARD sequential CARDFILE browse performed
     *  in ``FD-CARD-NUM`` key order.
     * :param pageable: the paging request controlling chunk offset and size.
     * :return: the requested chunk of cards ordered by ``cardNum`` ascending.
     */
    List<Card> findAllByOrderByCardNumAsc(Pageable pageable);
}
