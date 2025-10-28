/**
 * Account Type Definitions
 * 
 * TypeScript type definitions for account data structures.
 * Converted from COBOL ACCOUNT-RECORD copybook (CVACT01Y.cpy) to match
 * backend AccountDto.java for type-safe API communication.
 * 
 * Original COBOL source: app/cpy/CVACT01Y.cpy
 * Backend DTO: backend/src/main/java/com/carddemo/model/dto/AccountDto.java
 */

/**
 * Account status enumeration
 * 
 * Maps COBOL status codes to descriptive enum values for type-safe
 * status field validation. These values correspond to the single-character
 * status codes used in the mainframe VSAM ACCTFILE.
 */
export enum AccountStatus {
  /** Account is active and operational (COBOL: 'Y') */
  ACTIVE = 'Y',
  
  /** Account is closed (COBOL: 'N') */
  CLOSED = 'N',
  
  /** Account is temporarily suspended (COBOL: 'S') */
  SUSPENDED = 'S'
}

/**
 * Account data structure
 * 
 * Represents a credit card account with balance, credit limits, and lifecycle dates.
 * This interface matches the backend AccountDto and was converted from the COBOL
 * ACCOUNT-RECORD structure (CVACT01Y.cpy) used in the mainframe CardDemo application.
 * 
 * All monetary amounts are represented as numbers with 2 decimal places precision,
 * matching the COBOL PIC S9(10)V99 COMP-3 packed decimal fields.
 * 
 * Date fields use ISO 8601 format (YYYY-MM-DD) for JSON serialization.
 */
export interface Account {
  /**
   * Account identifier (primary key)
   * 
   * Unique 11-digit account number.
   * Source: ACCT-ID PIC 9(11)
   * 
   * @example 12345678901
   */
  acctId: number;
  
  /**
   * Account active status indicator
   * 
   * Single-character status code: 'Y' = Active, 'N' = Closed, 'S' = Suspended.
   * Source: ACCT-ACTIVE-STATUS PIC X(01)
   * 
   * @see AccountStatus enum for type-safe status values
   * @example 'Y'
   */
  acctActiveStatus: string;
  
  /**
   * Current account balance
   * 
   * Balance with 2 decimal precision. Positive values indicate credit (amount owed),
   * negative values indicate debit (overpayment/credit balance).
   * Source: ACCT-CURR-BAL PIC S9(10)V99 COMP-3
   * 
   * @example 1234.56
   */
  acctCurrBal: number;
  
  /**
   * Credit limit
   * 
   * Maximum credit limit for purchases and cash advances combined.
   * Source: ACCT-CREDIT-LIMIT PIC S9(10)V99 COMP-3
   * 
   * @example 10000.00
   */
  acctCreditLimit: number;
  
  /**
   * Cash credit limit
   * 
   * Maximum credit limit specifically for cash advances.
   * Must be less than or equal to the total credit limit.
   * Source: ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 COMP-3
   * 
   * @example 2000.00
   */
  acctCashCreditLimit: number;
  
  /**
   * Account open date
   * 
   * Date the account was originally opened in ISO 8601 format (YYYY-MM-DD).
   * Source: ACCT-OPEN-DATE PIC X(10)
   * 
   * @example '2020-01-15'
   */
  acctOpenDate: string;
  
  /**
   * Account expiration date
   * 
   * Date when the account expires or is scheduled for closure.
   * Null if no expiration date is set.
   * Source: ACCT-EXPIRAION-DATE PIC X(10)
   * 
   * @example '2025-12-31'
   */
  acctExpirationDate: string | null;
  
  /**
   * Account reissue date
   * 
   * Date when the account was reissued (e.g., after card replacement).
   * Null if the account has never been reissued.
   * Source: ACCT-REISSUE-DATE PIC X(10)
   * 
   * @example '2023-06-01'
   */
  acctReissueDate: string | null;
  
  /**
   * Current cycle credit amount
   * 
   * Total credits (payments, returns) posted during the current billing cycle.
   * Source: ACCT-CURR-CYC-CREDIT PIC S9(10)V99 COMP-3
   * 
   * @example 500.00
   */
  acctCurrCycCredit: number;
  
  /**
   * Current cycle debit amount
   * 
   * Total debits (purchases, fees, interest) posted during the current billing cycle.
   * Source: ACCT-CURR-CYC-DEBIT PIC S9(10)V99 COMP-3
   * 
   * @example 1234.56
   */
  acctCurrCycDebit: number;
  
  /**
   * Account address ZIP code
   * 
   * ZIP/postal code for the account billing address.
   * Null if not provided.
   * Source: ACCT-ADDR-ZIP PIC X(10)
   * 
   * @example '12345'
   */
  acctAddrZip: string | null;
  
  /**
   * Account group identifier
   * 
   * Group ID for categorizing accounts (e.g., by product type, risk level).
   * Null if not assigned to a group.
   * Source: ACCT-GROUP-ID PIC X(10)
   * 
   * @example 'PRIME001'
   */
  acctGroupId: string | null;
  
  /**
   * Record creation timestamp
   * 
   * ISO 8601 timestamp when the account record was created in the database.
   * Added by backend JPA @CreatedDate annotation.
   * 
   * @example '2020-01-15T10:30:00Z'
   */
  createdAt: string;
  
  /**
   * Record last update timestamp
   * 
   * ISO 8601 timestamp when the account record was last modified.
   * Updated automatically by backend JPA @LastModifiedDate annotation.
   * 
   * @example '2024-03-20T14:45:30Z'
   */
  updatedAt: string;
}
