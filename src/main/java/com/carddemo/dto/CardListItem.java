package com.carddemo.dto;

/**
 * Immutable per-row projection for the paginated credit-card browse endpoint
 * ({@code GET /cards}), returned to clients wrapped inside a
 * {@code PageResponse<CardListItem>}.
 *
 * <p><strong>Lineage.</strong> This DTO reproduces the three data columns rendered on each of
 * the seven rows of the legacy 3270 card-list screen. It is derived from:
 * <ul>
 *   <li>the BMS list map {@code COCRDLI} (online program {@code COCRDLIC}), whose per-row
 *       fields are {@code ACCTNOnI PIC X(11)}, {@code CRDNUMnI PIC X(16)} and
 *       {@code CRDSTSnI PIC X(1)} for rows {@code n = 1..7}; and</li>
 *   <li>the card record copybook {@code CVACT02Y} ({@code CARD-ACCT-ID PIC 9(11)},
 *       {@code CARD-NUM PIC X(16)}, {@code CARD-ACTIVE-STATUS PIC X(01)}).</li>
 * </ul>
 * The fixed legacy page size of seven rows per screen is enforced upstream by the
 * repository/controller layer (e.g. {@code findByCardAcctId(..., PageRequest.of(n, 7))}); this
 * record models a single row only and is intentionally agnostic of pagination.
 *
 * <p><strong>Sensitive-data policy (MUST).</strong> This projection deliberately carries
 * <em>no</em> cardholder-sensitive attributes. The card verification value
 * ({@code CARD-CVV-CD PIC 9(03)}) is <strong>structurally absent</strong> and must never be
 * added&nbsp;&mdash; it is neither serialized nor logged anywhere in the system. The embossed
 * name ({@code CARD-EMBOSSED-NAME}) and the expiration date ({@code CARD-EXPIRAION-DATE}) are
 * likewise excluded here; they belong to the fuller single-card view DTO
 * ({@code CardResponse}), not to this lightweight list item.
 *
 * <p><strong>Type mapping.</strong> COBOL fixed-point / alphanumeric pictures are mapped to
 * JDK types per the migration's uniform rules: the 11-digit numeric account identifier becomes
 * a {@link Long}; the 16-character card number is a {@link String} (preserving any leading
 * zeros, which a numeric type would strip); and the one-character active-status flag is a
 * {@link String} of length one ({@code "Y"} / {@code "N"}).
 *
 * <p><strong>Serialization.</strong> As a Java record this type is immutable and serializes
 * via its component accessors to the JSON contract:
 * <pre>{@code {"cardAcctId":12345678901,"cardNum":"0000123456789012","activeStatus":"Y"}}</pre>
 *
 * @param cardAcctId   the owning account identifier ({@code CARD-ACCT-ID PIC 9(11)}, shown as
 *                     {@code ACCTNO} on the legacy screen); a {@link Long}
 * @param cardNum      the 16-digit card number / PAN ({@code CARD-NUM PIC X(16)}); a
 *                     {@link String} so leading zeros are preserved
 * @param activeStatus the single-character active-status flag
 *                     ({@code CARD-ACTIVE-STATUS PIC X(01)}): {@code "Y"} (active) or
 *                     {@code "N"} (inactive)
 */
public record CardListItem(
        Long cardAcctId,
        String cardNum,
        String activeStatus
) {
}
