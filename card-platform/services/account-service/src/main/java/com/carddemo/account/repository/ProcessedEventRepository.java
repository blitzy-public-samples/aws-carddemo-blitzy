package com.carddemo.account.repository;

import com.carddemo.account.entity.ProcessedEventEntity;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Access path to the processed-event idempotency marker of the account service private schema,
 * table {@code processed_event}.
 *
 * <p>{@code app/cbl/CBSTM03B.CBL:L99-L112} declares a generic parameter area whose operation code
 * selects the access path, and this package reproduces that contract as one interface per
 * aggregate. Inherited {@code existsById} and {@code findById} reproduce the keyed read
 * {@code 'K'} at {@code app/cbl/CBSTM03B.CBL:L106}, and {@code save} reproduces the write
 * {@code 'W'} at {@code L107} and the rewrite {@code 'Z'} at {@code L108}.
 *
 * <p>ADDITIVE: no Customer Information Control System (CICS) program and no Job Control Language
 * (JCL) member declares this marker, and every file definition in {@code app/csd/CARDDEMO.CSD}
 * carries {@code RECOVERY(NONE)} and {@code JOURNAL(NO)}. This module consumes no event, and no
 * caller writes a row today.
 */
public interface ProcessedEventRepository extends ListCrudRepository<ProcessedEventEntity, String> {
}
