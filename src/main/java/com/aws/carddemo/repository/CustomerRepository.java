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
package com.aws.carddemo.repository;

import com.aws.carddemo.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for the {@link Customer} entity.
 *
 * <p>Replaces the COBOL VSAM keyed access to {@code CUSTDATA.VSAM.KSDS}
 * (logical file {@code CUSTFILE}, copybook {@code CVCUS01Y.cpy}) with
 * set-based data access over the PostgreSQL {@code customer} table. The
 * dataset is keyed on {@code cust_id} ({@code CUST-ID PIC 9(09)}), mapped to
 * the entity's {@code custId} primary key.
 *
 * <p><strong>Legacy source access.</strong> The original file access this
 * repository supersedes lives in:
 * <ul>
 *   <li>{@code legacy/cbl/CBCUS01C.cbl} — customer master-print batch; opens
 *       {@code CUSTFILE} with {@code ACCESS MODE IS SEQUENTIAL} and reads every
 *       record in ascending key order, reproduced by
 *       {@link #findAllByOrderByCustIdAsc()};</li>
 *   <li>{@code legacy/cbl/COACTVWC.cbl} — online account view; a keyed
 *       single-record read of {@code CUSTFILE} to resolve the customer for an
 *       account, served by the inherited {@link JpaRepository#findById};</li>
 *   <li>{@code legacy/cbl/COACTUPC.cbl} — online account update; the same keyed
 *       single-record customer resolution, also served by
 *       {@link JpaRepository#findById}.</li>
 * </ul>
 *
 * <p><strong>Sensitive PII.</strong> The customer SSN, government-issued id,
 * and date of birth carried by {@link Customer} are sensitive personal
 * information and must never be written to logs. Masking is a service/DTO-layer
 * concern and is deliberately not performed here.
 *
 * @see Customer
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /**
     * Reads all customers in ascending primary-key order.
     *
     * <p>Reproduces the {@code CUSTFILE} KSDS sequential browse performed by the
     * customer master-print batch ({@code legacy/cbl/CBCUS01C.cbl}), which reads
     * records in ascending {@code CUST-ID} key order. The derived query resolves
     * against the {@code custId} property of {@link Customer}.
     *
     * @return every customer ordered by {@code custId} ascending; an empty list
     *         when the {@code customer} table holds no rows
     */
    List<Customer> findAllByOrderByCustIdAsc();
}
