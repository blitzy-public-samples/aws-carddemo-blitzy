package com.carddemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Objects;

/**
 * JPA entity representing card-account cross-reference data.
 * 
 * Converted from COBOL copybook: CVACT03Y.cpy (CARD-XREF-RECORD)
 * Original record length: 50 bytes
 * 
 * This entity associates credit cards with accounts and customers, enabling the
 * many-to-many relationship between cards and accounts. A single account can have
 * multiple cards (primary cardholder, authorized users), and this cross-reference
 * table tracks all such associations along with the customer responsible for each card.
 * 
 * Conversion notes:
 * - COBOL PIC X(16) XREF-CARD-NUM converted to String primary key component (length 16)
 * - COBOL PIC 9(11) XREF-ACCT-ID converted to Long primary key component
 * - COBOL PIC 9(09) XREF-CUST-ID converted to Long foreign key to Customer entity
 * - Composite primary key (xrefCardNum + xrefAcctId) implemented using @IdClass pattern
 * - COBOL FILLER field (14 bytes) removed as not used
 * - NO audit fields (createdAt, updatedAt) per database schema Section 0.3.4
 * - NO version field for optimistic locking per database schema (pure cross-reference table)
 * 
 * Database schema (from Section 0.3.4):
 *   CREATE TABLE card_account_xref (
 *       xref_card_num VARCHAR(16) NOT NULL REFERENCES card(card_num),
 *       xref_acct_id BIGINT NOT NULL REFERENCES account(acct_id),
 *       xref_cust_id BIGINT NOT NULL REFERENCES customer(cust_id),
 *       PRIMARY KEY (xref_card_num, xref_acct_id)
 *   );
 * 
 * Composite Primary Key Strategy:
 * - Uses @IdClass pattern with CardAccountXrefId class
 * - Both xrefCardNum and xrefAcctId are annotated with @Id
 * - Foreign key relationships use insertable=false, updatable=false to prevent
 *   conflicts with @Id columns per JPA specification
 * 
 * Referenced by:
 * - Card entity (card_num foreign key)
 * - Account entity (acct_id foreign key)
 * - Customer entity (cust_id foreign key)
 * 
 * Usage:
 * This cross-reference table is queried to:
 * - Find all cards associated with an account
 * - Find the account associated with a specific card
 * - Identify the customer responsible for a card-account relationship
 * - Validate card-account associations during transaction processing
 * 
 * @see Card
 * @see Account
 * @see Customer
 * @see CardAccountXrefId
 */
@Entity
@Table(name = "card_account_xref")
@IdClass(CardAccountXrefId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CardAccountXref {

    /**
     * Card number (composite primary key component, foreign key to Card).
     * 
     * Converted from: COBOL PIC X(16) XREF-CARD-NUM
     * Maximum length: 16 characters
     * 
     * Part of composite primary key along with xrefAcctId.
     * References card.card_num primary key in Card entity.
     * 
     * Annotated with @Id as part of composite key strategy using @IdClass.
     * The @ManyToOne relationship uses insertable=false, updatable=false
     * to avoid conflicts with the @Id annotation per JPA specification.
     * 
     * SECURITY NOTE: This field contains sensitive PII (Payment Card Number).
     * - Must be masked in logs and API responses
     * - Access must be restricted to authorized personnel only
     */
    @Id
    @Column(name = "xref_card_num", nullable = false, length = 16)
    private String xrefCardNum;

    /**
     * Account ID (composite primary key component, foreign key to Account).
     * 
     * Converted from: COBOL PIC 9(11) XREF-ACCT-ID
     * Maximum value: 99,999,999,999 (11 digits)
     * 
     * Part of composite primary key along with xrefCardNum.
     * References account.acct_id primary key in Account entity.
     * 
     * Annotated with @Id as part of composite key strategy using @IdClass.
     * The @ManyToOne relationship uses insertable=false, updatable=false
     * to avoid conflicts with the @Id annotation per JPA specification.
     */
    @Id
    @Column(name = "xref_acct_id", nullable = false)
    private Long xrefAcctId;

    /**
     * Customer ID (foreign key to Customer).
     * 
     * Converted from: COBOL PIC 9(09) XREF-CUST-ID
     * Maximum value: 999,999,999 (9 digits)
     * 
     * Identifies the customer responsible for this card-account association.
     * Typically represents the cardholder (primary or authorized user) for
     * this specific card, which may differ from the primary account holder.
     * 
     * References customer.cust_id primary key in Customer entity.
     * 
     * Used to track:
     * - Who is authorized to use this card
     * - Customer contact information for card-specific communications
     * - Liability assignment for card transactions
     * 
     * Note: Unlike xref_card_num and xref_acct_id which are part of the composite primary key,
     * xref_cust_id is NOT part of the primary key and CAN be managed directly for insert/update.
     * The customer @ManyToOne relationship provides an alternative way to manage this foreign key.
     */
    @Column(name = "xref_cust_id", nullable = false)
    private Long xrefCustId;

    /**
     * Card entity relationship.
     * 
     * Many-to-One relationship: Multiple cross-reference records can point to one card.
     * 
     * Enables JPA to automatically fetch Card details when accessing CardAccountXref.card
     * and maintains referential integrity through foreign key constraint.
     * 
     * Join column xref_card_num references card.card_num primary key per
     * database schema defined in Section 0.3.4.
     * 
     * Important: Uses insertable=false, updatable=false because xref_card_num
     * is also part of the @Id composite key. This prevents JPA from attempting
     * to manage the same column twice, which would cause exceptions.
     */
    @ManyToOne
    @JoinColumn(name = "xref_card_num", referencedColumnName = "card_num", 
                nullable = false, insertable = false, updatable = false)
    private Card card;

    /**
     * Account entity relationship.
     * 
     * Many-to-One relationship: Multiple cross-reference records can point to one account.
     * 
     * Enables JPA to automatically fetch Account details when accessing CardAccountXref.account
     * and maintains referential integrity through foreign key constraint.
     * 
     * Join column xref_acct_id references account.acct_id primary key per
     * database schema defined in Section 0.3.4.
     * 
     * Important: Uses insertable=false, updatable=false because xref_acct_id
     * is also part of the @Id composite key. This prevents JPA from attempting
     * to manage the same column twice, which would cause exceptions.
     */
    @ManyToOne
    @JoinColumn(name = "xref_acct_id", referencedColumnName = "acct_id", 
                nullable = false, insertable = false, updatable = false)
    private Account account;

    /**
     * Customer entity relationship.
     * 
     * Many-to-One relationship: Multiple cross-reference records can point to one customer.
     * 
     * Enables JPA to automatically fetch Customer details when accessing CardAccountXref.customer
     * and maintains referential integrity through foreign key constraint.
     * 
     * Join column xref_cust_id references customer.cust_id primary key per
     * database schema defined in Section 0.3.4.
     * 
     * Uses insertable=false, updatable=false to avoid duplicate column management since
     * xref_cust_id is already mapped as a direct field above. The xrefCustId field is
     * the primary way to manage this foreign key value, and this relationship provides
     * read-only navigation to the Customer entity.
     */
    @ManyToOne
    @JoinColumn(name = "xref_cust_id", referencedColumnName = "cust_id", 
                nullable = false, insertable = false, updatable = false)
    private Customer customer;

    /**
     * Lifecycle callback to synchronize xrefCustId from customer relationship before persist/update.
     * 
     * This method ensures that when a CardAccountXref is created or updated with a customer relationship,
     * the xrefCustId foreign key field is automatically populated from customer.getCustId().
     * 
     * This handles two scenarios:
     * 1. New entity: xrefCustId is NULL, needs to be populated
     * 2. Updated entity: customer changed, xrefCustId needs to be synchronized
     * 
     * This prevents ConstraintViolationException when xrefCustId is NULL and keeps
     * the foreign key field in sync with the relationship object.
     */
    @jakarta.persistence.PrePersist
    @jakarta.persistence.PreUpdate
    protected void syncCustomerId() {
        if (customer != null) {
            // Always sync customer ID - handles both new entities and customer changes
            xrefCustId = customer.getCustId();
        }
    }
}
