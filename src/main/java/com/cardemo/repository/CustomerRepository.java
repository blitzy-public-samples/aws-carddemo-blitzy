/*
 * CustomerRepository.java — Spring Data JPA Repository for Customer entity
 *
 * Source COBOL: app/cbl/CBCUS01C.cbl (batch customer file management)
 * Source Copybook: app/cpy/CVCUS01Y.cpy (CUSTOMER-RECORD, 500-byte layout)
 * VSAM Dataset: AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS
 *
 * COBOL Access Patterns Mapped:
 *   1. CBCUS01C.cbl — Batch: sequential READ NEXT until EOF → findAll()
 *   2. COACTVWC.cbl — Online: READ CUSTDAT by CUST-ID key → findById(String)
 *   3. COACTUPC.cbl — Online: READ/REWRITE CUSTDAT by key → findById(String), save(Customer)
 *   4. CBSTM03A.CBL — Batch: statement generation customer lookup → findById(String)
 *
 * VSAM-to-JPA Mapping:
 *   EXEC CICS READ DATASET('CUSTDAT') INTO(CUSTOMER-RECORD) RIDFLD(CUST-ID)
 *     → findById(String custId)
 *   OPEN INPUT / READ NEXT / CLOSE (CBCUS01C sequential pattern)
 *     → findAll() / findAll(Pageable)
 *   EXEC CICS WRITE DATASET('CUSTDAT') FROM(CUSTOMER-RECORD)
 *     → save(Customer)
 *   EXEC CICS REWRITE DATASET('CUSTDAT') FROM(CUSTOMER-RECORD)
 *     → save(Customer) with existing ID (JPA merge)
 *   EXEC CICS DELETE DATASET('CUSTDAT') RIDFLD(CUST-ID)
 *     → deleteById(String)
 *
 * Primary Key: CUST-ID PIC 9(09) → String (9-character numeric, leading zeros preserved)
 *
 * No custom query methods are defined — all access patterns from the COBOL source
 * are fully satisfied by the inherited JpaRepository CRUD and pagination operations.
 *
 * Ver: CardDemo_v1.0 — Migrated from COBOL to Java 25 + Spring Boot 3.5.x
 */
package com.cardemo.repository;

import com.cardemo.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link Customer} entities.
 *
 * <p>Provides data access for the CUSTDATA VSAM KSDS dataset (500-byte records
 * defined in CVCUS01Y.cpy). The primary key is a 9-character numeric string
 * ({@code CUST-ID PIC 9(09)}) preserving leading zeros.</p>
 *
 * <p>All COBOL VSAM access patterns are satisfied by inherited JpaRepository methods:</p>
 * <ul>
 *   <li>{@code findById(String)} — Keyed read by CUST-ID
 *       (EXEC CICS READ DATASET('CUSTDAT') RIDFLD(CUST-ID))</li>
 *   <li>{@code findAll()} — Sequential read of all customer records
 *       (CBCUS01C.cbl batch OPEN INPUT / READ NEXT / CLOSE pattern)</li>
 *   <li>{@code findAll(Pageable)} — Paginated sequential read
 *       (STARTBR/READNEXT with page-size control)</li>
 *   <li>{@code save(Customer)} — Write new or rewrite existing record
 *       (EXEC CICS WRITE/REWRITE DATASET('CUSTDAT'))</li>
 *   <li>{@code saveAll(Iterable)} — Batch write for multiple customer records</li>
 *   <li>{@code deleteById(String)} — Delete by CUST-ID
 *       (EXEC CICS DELETE DATASET('CUSTDAT') RIDFLD(CUST-ID))</li>
 *   <li>{@code deleteAll()} — Purge all customer records</li>
 *   <li>{@code existsById(String)} — Check if customer exists by CUST-ID</li>
 *   <li>{@code count()} — Total customer record count</li>
 * </ul>
 *
 * <p>The {@code @Repository} annotation enables Spring component scanning and
 * provides automatic translation of JPA/JDBC exceptions into Spring's
 * {@code DataAccessException} hierarchy, mapping VSAM file status codes
 * (00=success, 22=duplicate, 23=not found) to appropriate Spring exceptions.</p>
 *
 * @see Customer
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, String> {
    // All required access patterns are satisfied by inherited JpaRepository methods.
    // No custom query methods needed — the AAP specifies JpaRepository<Customer, String>
    // with standard CRUD + pagination operations only.
    //
    // Inherited methods providing VSAM-equivalent operations:
    //   findById(String)         — VSAM READ by primary key (CUST-ID)
    //   findAll()                — VSAM sequential read (CBCUS01C batch pattern)
    //   findAll(Pageable)        — VSAM browse with pagination
    //   save(Customer)           — VSAM WRITE / REWRITE
    //   saveAll(Iterable)        — Batch WRITE for multiple records
    //   deleteById(String)       — VSAM DELETE by primary key
    //   deleteAll()              — Purge all records
    //   existsById(String)       — Existence check by primary key
    //   count()                  — Record count
}
