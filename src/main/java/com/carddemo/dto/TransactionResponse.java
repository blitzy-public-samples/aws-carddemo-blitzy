package com.carddemo.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable JSON response payload returned by the transaction-view endpoint
 * {@code GET /transactions/{tranId}} exposed by {@code TransactionController}.
 *
 * <p><strong>Lineage.</strong> This DTO is the Spring/REST re-expression of the legacy
 * CICS online program {@code COTRN01C} ("View a Transaction from TRANSACT file"). Its
 * field set mirrors the VSAM {@code TRAN-RECORD} layout defined in copybook
 * {@code CVTRA05Y} (record length 350), and its role corresponds to the BMS 3270 map
 * {@code COTRN01}. Where the legacy screen truncated values for display (for example the
 * description to 60 characters and the merchant name to 30), this API contract
 * deliberately carries the <em>full record-length</em> values so that no business data is
 * lost across the service boundary.</p>
 *
 * <p><strong>Both timestamps are exposed.</strong> The legacy {@code TRAN-RECORD} carries
 * two 26-character timestamps: {@code TRAN-ORIG-TS} (origination) and {@code TRAN-PROC-TS}
 * (processing). Both are surfaced here as {@link #origTs()} and {@link #procTs()}
 * respectively, in line with the migration's discrepancy resolution that persists both
 * timestamps (AAP &sect;0.7.3 #10). {@code procTs} may be {@code null} for a transaction
 * that has been originated but not yet posted by the daily transaction-posting batch.</p>
 *
 * <p><strong>Type mapping.</strong> COBOL fixed-point money ({@code PIC S9(09)V99}) maps to
 * {@link java.math.BigDecimal}; the 26-character timestamp fields map to
 * {@link java.time.LocalDateTime}; identifiers that are stored and rendered but never used
 * in arithmetic remain {@link String} (transaction id, card number); the numeric merchant
 * identifier maps to {@link Long}; and the transaction category code maps to
 * {@link Integer}.</p>
 *
 * <p><strong>Serialization.</strong> Instances are serialized by the application's central
 * Jackson configuration: {@code amount} is emitted as a JSON number scaled to two decimal
 * places, and {@code origTs}/{@code procTs} are emitted as ISO-8601 strings. This record
 * contains no sensitive cardholder data (no CVV, no SSN), so no field-level suppression
 * applies to this contract.</p>
 *
 * <p>Being a Java {@code record}, this type is immutable and inherently thread-safe; the
 * compiler supplies the canonical constructor, the component accessors, and value-based
 * {@code equals}, {@code hashCode}, and {@code toString} implementations.</p>
 *
 * @param tranId       transaction identifier &mdash; COBOL {@code TRAN-ID PIC X(16)}
 * @param typeCd       transaction type code &mdash; COBOL {@code TRAN-TYPE-CD PIC X(02)}
 * @param categoryCd   transaction category code &mdash; COBOL {@code TRAN-CAT-CD PIC 9(04)}
 * @param source       transaction source channel &mdash; COBOL {@code TRAN-SOURCE PIC X(10)}
 * @param description  full transaction description, untruncated &mdash; COBOL {@code TRAN-DESC PIC X(100)}
 * @param amount       transaction amount &mdash; COBOL {@code TRAN-AMT PIC S9(09)V99}
 * @param merchantId   merchant identifier &mdash; COBOL {@code TRAN-MERCHANT-ID PIC 9(09)}
 * @param merchantName merchant name &mdash; COBOL {@code TRAN-MERCHANT-NAME PIC X(50)}
 * @param merchantCity merchant city &mdash; COBOL {@code TRAN-MERCHANT-CITY PIC X(50)}
 * @param merchantZip  merchant postal code &mdash; COBOL {@code TRAN-MERCHANT-ZIP PIC X(10)}
 * @param cardNum      card number associated with the transaction &mdash; COBOL {@code TRAN-CARD-NUM PIC X(16)}
 * @param origTs       origination timestamp &mdash; COBOL {@code TRAN-ORIG-TS PIC X(26)}
 * @param procTs       processing timestamp, {@code null} until posted &mdash; COBOL {@code TRAN-PROC-TS PIC X(26)}
 */
public record TransactionResponse(
        String tranId,           // TRAN-ID            PIC X(16)
        String typeCd,           // TRAN-TYPE-CD       PIC X(02)
        Integer categoryCd,      // TRAN-CAT-CD        PIC 9(04)
        String source,           // TRAN-SOURCE        PIC X(10)
        String description,      // TRAN-DESC          PIC X(100) - full, no truncation
        BigDecimal amount,       // TRAN-AMT           PIC S9(09)V99
        Long merchantId,         // TRAN-MERCHANT-ID   PIC 9(09)
        String merchantName,     // TRAN-MERCHANT-NAME PIC X(50)
        String merchantCity,     // TRAN-MERCHANT-CITY PIC X(50)
        String merchantZip,      // TRAN-MERCHANT-ZIP  PIC X(10)
        String cardNum,          // TRAN-CARD-NUM      PIC X(16)
        LocalDateTime origTs,    // TRAN-ORIG-TS       PIC X(26)
        LocalDateTime procTs     // TRAN-PROC-TS       PIC X(26)
) {
}
