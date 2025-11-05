package com.carddemo.repository;

import com.carddemo.entity.AccountGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository interface for AccountGroup entity operations.
 * 
 * <p>Provides data access layer for account group discount interest rate configuration.
 * Maps to COBOL DISCGRP-FILE random read operations for interest rate lookups in
 * CBACT04C.cbl interest calculation batch program.</p>
 * 
 * <p><strong>COBOL Mapping:</strong></p>
 * <pre>
 * COBOL: SELECT DISCGRP-FILE ORGANIZATION IS INDEXED ACCESS MODE IS RANDOM
 *        RECORD KEY IS FD-DISCGRP-KEY (groupId + typeCode + categoryCode)
 * Java:  AccountGroupRepository.findById(AccountGroup.GroupId compositeKey)
 * </pre>
 * 
 * <p><strong>Usage in Interest Calculation:</strong></p>
 * <ul>
 *   <li>InterestCalculationProcessor: Lookup interest rate for account group configuration</li>
 *   <li>Key Components: accountGroupId (10 chars) + transactionTypeCode (2 chars) + categoryCode (4 digits)</li>
 *   <li>Returns: AccountGroup entity with interestRate (BigDecimal scale 5)</li>
 * </ul>
 * 
 * <p>Spring Data JPA automatically provides implementation for standard CRUD operations
 * and query derivation based on method naming conventions.</p>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 * @see AccountGroup
 * @see com.carddemo.batch.processor.InterestCalculationProcessor
 */
@Repository
public interface AccountGroupRepository extends JpaRepository<AccountGroup, AccountGroup.GroupId> {
    // Spring Data JPA provides automatic implementation for:
    // - findById(AccountGroup.GroupId id) -> Random read by composite key (groupId + typeCode + categoryCode)
    // - Standard CRUD operations (save, delete, findAll, etc.)
    // Maps to COBOL: READ DISCGRP-FILE KEY IS FD-DISCGRP-KEY
}
