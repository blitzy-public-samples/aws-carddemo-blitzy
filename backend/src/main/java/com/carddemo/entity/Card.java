package com.carddemo.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * JPA entity representing credit card master data table, transformed from COBOL copybook
 * CVACT02Y.cpy (CARD-RECORD with RECLN 150 bytes).
 * 
 * Contains card identification (16-character card number as primary key), account association 
 * (11-digit account ID foreign key), security information (3-digit CVV code), cardholder details 
 * (50-character embossed name), expiration date (10 characters), and active status indicator 
 * (1 character). Foreign key relationship to Account entity establishing card-to-account ownership.
 * 
 * Critical entity for transaction processing and card management operations with PII data 
 * (card number, CVV) requiring encryption and masking per security requirements.
 */
@Entity
@Table(name = "card")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Card implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * Card number - 16-character primary key (PII data)
     * Maps to CARD-NUM PIC X(16) from COBOL
     * WARNING: Contains PII - must be masked in logs and encrypted at rest
     * PCI-DSS compliance: display only last 4 digits in UI
     */
    @Id
    @Column(name = "card_number", length = 16, nullable = false)
    private String cardNumber;
    
    /**
     * Account ID - 11-digit foreign key to Account entity
     * Maps to CARD-ACCT-ID PIC 9(11) from COBOL
     */
    @Column(name = "account_id", nullable = false)
    private Long accountId;
    
    /**
     * CVV code - 3-digit security code (PII data)
     * Maps to CARD-CVV-CD PIC 9(03) from COBOL
     * WARNING: Highly sensitive PII - must NEVER be logged or displayed
     * PCI-DSS requires encryption at rest
     */
    @Column(name = "cvv_code", length = 3, nullable = false)
    private String cvvCode;
    
    /**
     * Embossed name - cardholder name as it appears on card
     * Maps to CARD-EMBOSSED-NAME PIC X(50) from COBOL
     */
    @Column(name = "embossed_name", length = 50, nullable = false)
    private String embossedName;
    
    /**
     * Expiration date - card expiration date
     * Maps to CARD-EXPIRAION-DATE PIC X(10) from COBOL (typo in COBOL)
     * Convert from COBOL date string format (typically MM/YY or YYYY-MM-DD)
     */
    @Column(name = "expiration_date")
    private LocalDate expirationDate;
    
    /**
     * Active status - card operational status
     * Maps to CARD-ACTIVE-STATUS PIC X(01) from COBOL
     * Values: 'Y' (active), 'N' (inactive), 'B' (blocked), 'E' (expired)
     */
    @Column(name = "active_status", length = 1, nullable = false)
    private String activeStatus;
    
    /**
     * Many-to-one relationship to Account entity
     * Lazy loading for performance optimization
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", insertable = false, updatable = false)
    @JsonIgnore
    private Account account;
    
    /**
     * Custom toString() to mask PII fields for security
     * Card number shows only last 4 digits
     * CVV is completely masked
     */
    @Override
    public String toString() {
        String maskedCardNumber = cardNumber != null && cardNumber.length() >= 4 
            ? "**** **** **** " + cardNumber.substring(cardNumber.length() - 4)
            : "****";
        
        return "Card{" +
                "cardNumber='" + maskedCardNumber + '\'' +
                ", accountId=" + accountId +
                ", cvvCode='***'" +
                ", embossedName='" + embossedName + '\'' +
                ", expirationDate=" + expirationDate +
                ", activeStatus='" + activeStatus + '\'' +
                '}';
    }
}
