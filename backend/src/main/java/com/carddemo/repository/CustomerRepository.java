/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.repository;

import com.carddemo.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA Repository for Customer entity.
 * 
 * <p>This repository replaces COBOL VSAM CUSTDAT file access patterns with
 * relational database queries. It provides CRUD operations and custom query
 * methods for customer data access.</p>
 * 
 * <p><b>COBOL Transformation Details:</b></p>
 * <ul>
 *   <li>VSAM KSDS CUSTDAT file → PostgreSQL customer table</li>
 *   <li>Sequential READ operations → Paginated findAll queries</li>
 *   <li>Direct READ by key → findById method</li>
 *   <li>VSAM record key (CUST-ID) → Primary key index on customer_id</li>
 * </ul>
 * 
 * <p><b>Key Access Patterns:</b></p>
 * <ul>
 *   <li>CBCUS01C.CBL: Sequential read of all customer records</li>
 *   <li>COSGN00C.CBL: Customer lookup by ID during authentication</li>
 *   <li>COACTVWC.CBL: Customer details for account view</li>
 * </ul>
 * 
 * <p><b>Custom Queries:</b></p>
 * <ul>
 *   <li>findByCustomerId: Direct lookup by primary key (9-digit ID)</li>
 *   <li>findByLastName: Search customers by last name</li>
 *   <li>findBySsn: Lookup by Social Security Number (PII-sensitive)</li>
 *   <li>findByEmail: Search by email address</li>
 * </ul>
 * 
 * <p><b>Performance Optimization:</b></p>
 * <ul>
 *   <li>Primary key index on customer_id for O(log n) lookup</li>
 *   <li>Secondary indexes on frequently searched fields (SSN, email)</li>
 *   <li>Pagination support for large result sets</li>
 *   <li>HikariCP connection pooling (20-50 connections per Section 0.5)</li>
 * </ul>
 * 
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 2024-01-01
 * @see Customer
 * @see com.carddemo.batch.reader.CustomerItemReader
 * @see com.carddemo.batch.writer.CustomerItemWriter
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /**
     * Finds a customer by their unique customer ID.
     * 
     * <p><b>COBOL Equivalent:</b> Direct READ CUSTFILE by CUST-ID key</p>
     * <p><b>VSAM Operation:</b> Random access by primary key</p>
     * 
     * <p>This is the primary key lookup inherited from JpaRepository but
     * explicitly documented for COBOL transformation clarity.</p>
     * 
     * @param customerId 9-digit customer identifier (CUST-ID PIC 9(09))
     * @return Optional containing customer if found, empty otherwise
     */
    @Override
    Optional<Customer> findById(Long customerId);

    /**
     * Finds customers by last name (case-insensitive partial match).
     * 
     * <p><b>COBOL Equivalent:</b> Sequential scan with CUST-LAST-NAME filter</p>
     * <p><b>Performance Note:</b> Uses index on last_name for faster searches</p>
     * 
     * @param lastName Customer last name (CUST-LAST-NAME PIC X(25))
     * @return List of customers matching the last name
     */
    List<Customer> findByLastNameContainingIgnoreCase(String lastName);

    /**
     * Finds a customer by Social Security Number (SSN).
     * 
     * <p><b>Security Warning:</b> This method accesses PII data (SSN).
     * All calls must be logged for audit compliance.</p>
     * 
     * <p><b>COBOL Equivalent:</b> Sequential scan with CUST-SSN filter</p>
     * <p><b>Database:</b> SSN field should be encrypted at rest</p>
     * 
     * @param ssn Customer Social Security Number (CUST-SSN PIC 9(09))
     * @return Optional containing customer if found, empty otherwise
     */
    Optional<Customer> findBySsn(String ssn);

    /**
     * Finds a customer by email address.
     * 
     * <p><b>COBOL Equivalent:</b> No direct COBOL equivalent (new field)</p>
     * <p><b>Index:</b> Secondary index on email for fast lookup</p>
     * 
     * @param email Customer email address (CUST-EMAIL)
     * @return Optional containing customer if found, empty otherwise
     */
    Optional<Customer> findByEmail(String email);

    /**
     * Finds customers by phone number (partial match).
     * 
     * <p><b>COBOL Equivalent:</b> Sequential scan with CUST-PHONE filter</p>
     * 
     * @param phone Customer phone number substring
     * @return List of customers matching the phone number pattern
     */
    List<Customer> findByPhoneContaining(String phone);

    /**
     * Finds customers by ZIP code.
     * 
     * <p><b>COBOL Equivalent:</b> Sequential scan with CUST-ADDR-ZIP filter</p>
     * <p><b>Use Case:</b> Geographic analysis and marketing campaigns</p>
     * 
     * @param zipCode ZIP code (CUST-ADDR-ZIP PIC X(10))
     * @return List of customers in the specified ZIP code
     */
    List<Customer> findByZipCode(String zipCode);

    /**
     * Finds customers by state code.
     * 
     * <p><b>COBOL Equivalent:</b> Sequential scan with CUST-ADDR-STATE-CD filter</p>
     * <p><b>Use Case:</b> State-level reporting and compliance</p>
     * 
     * @param stateCode Two-letter state code (CUST-ADDR-STATE-CD PIC X(02))
     * @param pageable Pagination parameters
     * @return Page of customers in the specified state
     */
    Page<Customer> findByStateCode(String stateCode, Pageable pageable);

    /**
     * Counts total number of customers.
     * 
     * <p><b>COBOL Equivalent:</b> Record count from CUSTFILE sequential read</p>
     * <p><b>Performance:</b> Uses database COUNT(*) for efficiency</p>
     * 
     * @return Total number of customer records
     */
    @Override
    long count();

    /**
     * Finds customers by first and last name (exact match, case-insensitive).
     * 
     * <p><b>COBOL Equivalent:</b> Sequential scan with name filters</p>
     * <p><b>Use Case:</b> Customer search in user interface</p>
     * 
     * @param firstName Customer first name (CUST-FIRST-NAME PIC X(25))
     * @param lastName Customer last name (CUST-LAST-NAME PIC X(25))
     * @return List of customers matching both first and last name
     */
    @Query("SELECT c FROM Customer c WHERE LOWER(c.firstName) = LOWER(:firstName) AND LOWER(c.lastName) = LOWER(:lastName)")
    List<Customer> findByFirstNameAndLastName(
        @Param("firstName") String firstName,
        @Param("lastName") String lastName
    );

    /**
     * Finds all customers with pagination support.
     * 
     * <p><b>COBOL Equivalent:</b> Sequential READ CUSTFILE</p>
     * <p><b>Batch Processing:</b> Used by CBCUS01C.CBL equivalent batch job</p>
     * 
     * <p>This method supports chunk-oriented processing in Spring Batch
     * with configurable page size (default 1000 records per chunk per Section 0.5).</p>
     * 
     * @param pageable Pagination parameters (page number, size, sort)
     * @return Page of customer records
     */
    @Override
    Page<Customer> findAll(Pageable pageable);

    /**
     * Checks if a customer exists with the given customer ID.
     * 
     * <p><b>COBOL Equivalent:</b> CUSTFILE READ with file-status check</p>
     * 
     * @param customerId Customer identifier
     * @return true if customer exists, false otherwise
     */
    @Override
    boolean existsById(Long customerId);

    /**
     * Checks if a customer exists with the given SSN.
     * 
     * <p><b>Security Note:</b> Access to SSN is PII-sensitive and must be logged</p>
     * <p><b>Use Case:</b> Duplicate customer detection during onboarding</p>
     * 
     * @param ssn Social Security Number
     * @return true if customer with SSN exists, false otherwise
     */
    boolean existsBySsn(String ssn);

    /**
     * Checks if a customer exists with the given email address.
     * 
     * <p><b>Use Case:</b> Email uniqueness validation</p>
     * 
     * @param email Email address
     * @return true if customer with email exists, false otherwise
     */
    boolean existsByEmail(String email);

    /**
     * Saves a customer entity to the database (insert or update).
     * 
     * <p><b>COBOL Equivalent:</b> WRITE or REWRITE CUSTFILE</p>
     * <p><b>Batch Usage:</b> Called by CustomerItemWriter in batch processing</p>
     * 
     * @param customer Customer entity to save
     * @return Saved customer entity with updated fields
     */
    @Override
    <S extends Customer> S save(S customer);

    /**
     * Saves all customers in a single batch operation.
     * 
     * <p><b>COBOL Equivalent:</b> Multiple WRITE operations to CUSTFILE</p>
     * <p><b>Batch Optimization:</b> Uses JDBC batch insert for performance</p>
     * <p><b>Transaction:</b> All saves commit together or rollback as atomic unit</p>
     * 
     * <p>This method is called by CustomerItemWriter.write() to persist
     * a chunk of customer records (default 1000 records per chunk).</p>
     * 
     * @param customers Iterable of customer entities to save
     * @param <S> Customer entity type
     * @return List of saved customer entities
     */
    @Override
    <S extends Customer> List<S> saveAll(Iterable<S> customers);

    /**
     * Deletes a customer by ID.
     * 
     * <p><b>COBOL Equivalent:</b> DELETE CUSTFILE by CUST-ID</p>
     * <p><b>Constraint:</b> May fail if customer has related accounts (foreign key)</p>
     * 
     * @param customerId Customer identifier to delete
     */
    @Override
    void deleteById(Long customerId);
}
