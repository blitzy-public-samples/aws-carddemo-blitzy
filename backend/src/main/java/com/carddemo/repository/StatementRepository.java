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
package com.carddemo.repository;

import com.carddemo.entity.Statement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Spring Data JPA repository for Statement entity operations.
 */
@Repository
public interface StatementRepository extends JpaRepository<Statement, Long> {

    /**
     * Find statements by account ID.
     *
     * @param accountId Account ID
     * @return List of statements for the account
     */
    List<Statement> findByAccountId(Long accountId);

    /**
     * Find statements by status.
     *
     * @param status Statement status
     * @return List of statements with the given status
     */
    List<Statement> findByStatus(String status);

    /**
     * Find statements by account ID and statement date.
     *
     * @param accountId Account ID
     * @param statementDate Statement date
     * @return List of statements matching criteria
     */
    List<Statement> findByAccountIdAndStatementDate(Long accountId, LocalDate statementDate);
}
