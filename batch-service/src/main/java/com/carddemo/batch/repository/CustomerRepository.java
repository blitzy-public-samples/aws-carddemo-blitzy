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

import com.carddemo.common.domain.Customer;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the Customer master entity.
 *
 * :purpose: Persistent access to the ``customers`` table, replacing the legacy
 *           VSAM CUSTFILE (KSDS) access used by the CBCUS01C read/print batch
 *           program. Single-record lookup and persistence operations are
 *           inherited from {@link JpaRepository}.
 * :output: Managed {@link Customer} instances sourced from PostgreSQL; the
 *          key-ordered query returns them in ascending customer-id order to
 *          mirror the legacy indexed-sequential read.
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /**
     * Reads customers in ascending customer-id order for chunked sequential processing.
     *
     * :param pageable: the paging and chunk-size directive applied to the ordered scan.
     * :return: the requested page of customers ordered by ascending customer id.
     */
    List<Customer> findAllByOrderByCustIdAsc(Pageable pageable);
}
