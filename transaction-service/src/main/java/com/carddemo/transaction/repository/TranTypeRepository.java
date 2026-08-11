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
package com.carddemo.transaction.repository;

import com.carddemo.common.domain.TranType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Data-access repository for the transaction-type reference table.
 *
 * :purpose: Data-access repository for the transaction-type reference table
 *     (``TRANTYPE``); replaces the VSAM keyed ``READ`` used by the ``CBTRN03C``
 *     transaction-detail report (paragraph ``1500-B-LOOKUP-TRANTYPE``) to resolve
 *     a two-character transaction-type code to its description. The inherited
 *     ``findById`` performs the keyed lookup and ``findAll`` enumerates the
 *     reference rows for reporting.
 * :output: managed ``TranType`` reference rows.
 */
@Repository
public interface TranTypeRepository extends JpaRepository<TranType, String> {
}
