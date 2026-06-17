package com.carddemo.dto;

import java.time.LocalDate;

/**
 * Immutable JSON response payload returned by the card-detail view endpoint
 * ({@code GET /cards/{cardNum}}), served by {@code CardController}.
 *
 * <h2>Legacy lineage</h2>
 * <p>This DTO is the Spring Boot re-expression of the card-detail screen produced by the
 * CICS online program {@code COCRDSLC} (Card Detail View). Its field set is derived from
 * the VSAM {@code CARDDAT} record copybook {@code CVACT02Y} (record length 150) and mirrors
 * exactly the values that the legacy program moved to its BMS map {@code COCRDSL}
 * (symbolic map {@code CCRDSLAO}): account identifier, card number, embossed name,
 * expiration date, and active status.</p>
 *
 * <h2>Field mapping ({@code CVACT02Y} record &rarr; record component)</h2>
 * <table border="1">
 *   <caption>Source-to-DTO field mapping</caption>
 *   <tr><th>COBOL field</th><th>Picture</th><th>Component</th><th>Java type</th></tr>
 *   <tr><td>{@code CARD-NUM}</td><td>{@code X(16)}</td><td>{@code cardNum}</td><td>{@link String}</td></tr>
 *   <tr><td>{@code CARD-ACCT-ID}</td><td>{@code 9(11)}</td><td>{@code cardAcctId}</td><td>{@link Long}</td></tr>
 *   <tr><td>{@code CARD-EMBOSSED-NAME}</td><td>{@code X(50)}</td><td>{@code embossedName}</td><td>{@link String}</td></tr>
 *   <tr><td>{@code CARD-EXPIRAION-DATE}</td><td>{@code X(10)}</td><td>{@code expirationDate}</td><td>{@link LocalDate}</td></tr>
 *   <tr><td>{@code CARD-ACTIVE-STATUS}</td><td>{@code X(01)}</td><td>{@code activeStatus}</td><td>{@link String}</td></tr>
 * </table>
 *
 * <h2>Sensitive-field suppression (PII)</h2>
 * <p>The {@code CVACT02Y} record additionally carries a sensitive three-digit card
 * verification security code immediately after {@code CARD-ACCT-ID}. Per the migration
 * mandate (AAP &sect;0.6.8 / &sect;0.7.1) that value is <strong>persisted on the entity but
 * never serialized to a client and never logged</strong>. Suppression here is
 * <em>structural</em>: this record deliberately declares <strong>no component</strong> for
 * it, so the value can never reach a JSON response &mdash; the safest possible posture
 * ("you cannot leak a field that does not exist"). This is also faithful to the original
 * program {@code COCRDSLC}, which likewise never moved that field to its output map; the
 * value existed there only as an inbound input-edit field. The trailing {@code FILLER X(59)}
 * of the source record is also excluded, as it carries no business data.</p>
 *
 * <p><strong>Maintenance note:</strong> do not add a component for the verification code
 * (under any name) to this record. If a client genuinely requires confirmation that a code
 * is present, expose a derived boolean elsewhere rather than the value itself.</p>
 *
 * <h2>Type rules</h2>
 * <ul>
 *   <li>{@code cardNum} is modelled as {@link String} rather than a numeric type so that
 *       the leading zeros of the 16-character primary account number (PAN) are preserved.</li>
 *   <li>{@code cardAcctId} is a {@link Long}, matching the 11-digit account identifier.</li>
 *   <li>{@code expirationDate} is a {@link LocalDate}; the legacy 10-character {@code X(10)}
 *       date is rendered on the wire as an ISO-8601 string ({@code yyyy-MM-dd}) by the
 *       application-wide Jackson JSR-310 (Java Time) configuration, so no per-field
 *       serialization annotation is required here.</li>
 *   <li>{@code activeStatus} is the single-character flag (typically {@code "Y"} or
 *       {@code "N"}) copied verbatim from {@code CARD-ACTIVE-STATUS}.</li>
 * </ul>
 *
 * <h2>Serialized shape</h2>
 * <pre>{@code
 * {
 *   "cardNum": "0000000000000123",
 *   "cardAcctId": 12345678901,
 *   "embossedName": "JOHN Q PUBLIC",
 *   "expirationDate": "2027-04-30",
 *   "activeStatus": "Y"
 * }
 * }</pre>
 *
 * <p>Being a Java {@code record}, this type is immutable and inherently thread-safe.
 * Instances are produced by the card mapper from a persisted {@code Card} entity and are
 * never mutated thereafter. It is a self-contained carrier with no dependency on any other
 * application type (only the JDK {@link LocalDate} is referenced).</p>
 *
 * @param cardNum        the 16-character card number / PAN; source {@code CARD-NUM PIC X(16)}.
 *                       Held as {@link String} to preserve leading zeros.
 * @param cardAcctId     the owning account identifier; source {@code CARD-ACCT-ID PIC 9(11)}.
 * @param embossedName   the cardholder name embossed on the card;
 *                       source {@code CARD-EMBOSSED-NAME PIC X(50)}.
 * @param expirationDate the card expiration date; source {@code CARD-EXPIRAION-DATE PIC X(10)},
 *                       serialized as ISO-8601 ({@code yyyy-MM-dd}).
 * @param activeStatus   the single-character active-status flag (for example {@code "Y"} or
 *                       {@code "N"}); source {@code CARD-ACTIVE-STATUS PIC X(01)}.
 */
public record CardResponse(
        String cardNum,
        Long cardAcctId,
        String embossedName,
        LocalDate expirationDate,
        String activeStatus
) {
    // The sensitive three-digit verification security code present in the source record
    // (CVACT02Y, immediately following CARD-ACCT-ID) is intentionally NOT declared here.
    // It must never be serialized to a client or written to logs (AAP 0.6.8 / 0.7.1).
    // Suppression by omission is deliberate and must be preserved across future edits.
}
