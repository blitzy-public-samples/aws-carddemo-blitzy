/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.repository;

import com.carddemo.entity.DailyTransactionStaging;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA Repository for DailyTransactionStaging entity.
 * 
 * <p>Provides CRUD operations and query methods for the daily_transaction_staging
 * table used in batch transaction processing. This staging table holds transactions
 * that are pending validation and posting to the permanent transaction table.</p>
 * 
 * <p><strong>Purpose:</strong></p>
 * <ul>
 *   <li>Support test data setup for batch job testing</li>
 *   <li>Enable cleanup of staging records after batch processing</li>
 *   <li>Facilitate monitoring and auditing of batch processing status</li>
 * </ul>
 * 
 * <p><strong>Usage Context:</strong></p>
 * <ul>
 *   <li>Test Setup: Create PENDING transactions for batch job testing</li>
 *   <li>Test Teardown: Clean up staging records after test execution</li>
 *   <li>Batch Monitoring: Query processing status and validation results</li>
 * </ul>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@Repository
public interface DailyTransactionStagingRepository extends JpaRepository<DailyTransactionStaging, String> {
    
    // JpaRepository provides standard CRUD operations:
    // - save(DailyTransactionStaging entity)
    // - findById(String id)
    // - findAll()
    // - deleteAll()
    // - count()
    
    // Additional custom query methods can be added here if needed
}
