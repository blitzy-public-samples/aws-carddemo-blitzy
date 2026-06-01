package com.carddemo.mapper;

import java.time.LocalDate;
import java.util.List;

import com.carddemo.dto.card.CardDto;
import com.carddemo.dto.card.CardListResponse;
import com.carddemo.entity.Card;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * Hand-coded mapper between the {@link Card} JPA entity and the card REST DTOs
 * ({@link CardDto} and the paginated {@link CardListResponse}). Consumed by
 * {@code CardController} (the {@code GET /api/cards/{cardNum}} single view, the
 * {@code GET /api/accounts/{acctId}/cards} paginated list, and the
 * {@code PUT /api/cards/{cardNum}} update endpoints) and by {@code CardService}.
 *
 * <p>This class is a Spring {@code @Component} so it can be constructor-injected
 * into its consumers (PR-29: constructor injection only, no field injection). It
 * carries no injectable dependencies — the default constructor is sufficient — and
 * therefore needs no Lombok {@code @RequiredArgsConstructor}. The class is
 * intentionally NOT {@code final} so Spring may create a CGLIB proxy if required.</p>
 *
 * <p><strong>Field-name translation (CRITICAL).</strong> The entity field
 * {@code cvvCd} (which preserves the terse COBOL {@code CARD-CVV-CD} abbreviation) is
 * exposed to clients as the more readable {@code cvvCode} on {@link CardDto}. This
 * mapper performs that rename in both directions: {@code card.getCvvCd()} &rarr;
 * {@code dto.cvvCode} (outbound) and {@code dto.getCvvCode()} &rarr;
 * {@code card.setCvvCd(...)} (inbound). It mirrors the analogous
 * {@code Transaction.cardNum} &harr; {@code TransactionDto.cardNumber} rename performed
 * by {@code TransactionMapper}.</p>
 *
 * <p><strong>CVV type conversion ({@code Short} &harr; {@code String}).</strong> The
 * {@link Card} entity stores the CVV as a {@link Short} ({@code cvv_cd SMALLINT NOT
 * NULL} in the committed Flyway DDL — an exact small integer), whereas {@link CardDto}
 * carries it as a 3-character {@link String} so that leading zeros such as
 * {@code "007"} survive JSON serialization (the COBOL {@code PIC 9(03)} fixed-width
 * semantics). This mapper therefore <em>converts</em> between the two representations
 * rather than passing the value through: outbound, {@link #formatCvv(Short)} zero-pads
 * the {@code Short} to exactly three digits ({@code String.format("%03d", ...)}, e.g.
 * {@code 7 -> "007"}); inbound, {@link #parseCvv(String)} parses the three-digit string
 * back to a {@code Short}. Both helpers are {@code null}-safe (and treat a blank inbound
 * string as {@code null}); the produced string satisfies the DTO's
 * {@code @Pattern("\\d{3}")} contract.</p>
 *
 * <p><strong>Expiration-date type conversion ({@code LocalDate} &harr;
 * {@code String}).</strong> The {@link Card} entity persists the expiration date as a
 * {@link LocalDate} (SQL {@code DATE} column), whereas {@link CardDto} carries it as a
 * {@link String} in ISO {@code yyyy-MM-dd} form (matching the COBOL {@code PIC X(10)}
 * external shape and the DTO's {@code @Pattern("\\d{4}-\\d{2}-\\d{2}")} contract).
 * Outbound, {@link #formatExpirationDate(LocalDate)} renders the date with
 * {@link LocalDate#toString()} (which emits ISO {@code yyyy-MM-dd}); inbound,
 * {@link #parseExpirationDate(String)} parses it with {@link LocalDate#parse(CharSequence)}
 * (which consumes the same ISO form). Both helpers are {@code null}-safe. This
 * self-contained conversion mirrors the pattern used by {@code TransactionMapper} for its
 * timestamp fields; the produced and consumed values are identical to those of the shared
 * {@code com.carddemo.util.DateConversionUtil} ISO formatter.</p>
 *
 * <p><strong>PR-14 (typo correction).</strong> The COBOL field
 * {@code CARD-EXPIRAION-DATE} [sic — misspelled in {@code app/cpy/CVACT02Y.cpy}] is
 * normalized to the correctly spelled {@code expirationDate} on BOTH the entity and the
 * DTO; the names match across the boundary, so no field-name translation is required for
 * the expiration date (only the {@code LocalDate} &harr; {@code String} type conversion
 * described above).</p>
 *
 * <p><strong>Exposed surface (PR-13, PR-22).</strong> Exactly the 6 user fields from the
 * 150-byte {@code CARD-RECORD} are mapped ({@code cardNum}, {@code accountId},
 * {@code cvvCd}/{@code cvvCode}, {@code embossedName}, {@code expirationDate}, and
 * {@code activeStatus}); the trailing COBOL {@code FILLER PIC X(59)} carries no business
 * meaning and is NOT exposed. The entity's {@code @Version} optimistic-locking field is
 * intentionally NOT exposed in the DTO (PR-22) — JPA manages concurrency transparently and
 * a conflict surfaces as {@code OptimisticLockException} (HTTP 409). The Spring Data audit
 * fields ({@code createdAt}/{@code updatedAt}/{@code createdBy}/{@code updatedBy}) are
 * server-controlled and also NOT exposed.</p>
 *
 * <p><strong>Pagination (AAP &sect;0.6.1).</strong> {@link #toListResponse(Page)} builds the
 * Java 17 {@code record} {@link CardListResponse} from a Spring Data {@link Page}, replacing
 * the original VSAM {@code STARTBR DATASET('CARDAIX')} browse cursor (the {@code COCRDLIC}
 * PF7/PF8 pagination) with a stateless paginated response computed per request from the
 * {@code page}/{@code size} parameters; no server-side cursor lifecycle is maintained.</p>
 *
 * <p>Reference COBOL source: {@code app/cpy/CVACT02Y.cpy} (150-byte {@code CARD-RECORD},
 * CardDemo_v1.0-15-g27d6c6f-68); card list/view/update flows {@code app/cbl/COCRDLIC.cbl},
 * {@code app/cbl/COCRDSLC.cbl}, and {@code app/cbl/COCRDUPC.cbl}. The persistence and
 * validation annotations on the entity and DTO use the {@code jakarta.*} namespace (PR-28);
 * this mapper itself requires none.</p>
 *
 * @see com.carddemo.entity.Card
 * @see com.carddemo.dto.card.CardDto
 * @see com.carddemo.dto.card.CardListResponse
 */
@Component
public class CardMapper {

    /**
     * Converts a {@link Card} entity to a {@link CardDto} for outbound REST responses.
     *
     * <p>Performs the field-name translation {@code Card.cvvCd} (entity) &rarr;
     * {@code CardDto.cvvCode} (DTO) together with the {@code Short} &rarr; 3-character
     * {@code String} conversion via {@link #formatCvv(Short)} (zero-padded, preserving
     * leading zeros such as {@code "007"}). The {@link LocalDate} expiration date is
     * rendered to an ISO {@code yyyy-MM-dd} {@code String} via
     * {@link #formatExpirationDate(LocalDate)}.</p>
     *
     * <p>The {@code @Version} field and audit fields are intentionally NOT exposed
     * (PR-22). Exactly the 6 user fields are mapped; the COBOL {@code FILLER} is not
     * (PR-13).</p>
     *
     * <p>Returns {@code null} if the input is {@code null}.</p>
     *
     * @param card the {@code Card} entity (may be {@code null})
     * @return a {@code CardDto}, or {@code null} if the input is {@code null}
     */
    public CardDto toDto(Card card) {
        if (card == null) {
            return null;
        }
        return CardDto.builder()
                .cardNum(card.getCardNum())
                .accountId(card.getAccountId())
                .cvvCode(formatCvv(card.getCvvCd()))
                .embossedName(card.getEmbossedName())
                .expirationDate(formatExpirationDate(card.getExpirationDate()))
                .activeStatus(card.getActiveStatus())
                .build();
    }

    /**
     * Builds a transient {@link Card} entity from a {@link CardDto} for the
     * {@code PUT /api/cards/{cardNum}} update flow or for service-layer testing.
     *
     * <p>Performs the field-name translation {@code CardDto.cvvCode} (DTO) &rarr;
     * {@code Card.cvvCd} (entity) together with the 3-character {@code String} &rarr;
     * {@code Short} conversion via {@link #parseCvv(String)}. The ISO {@code yyyy-MM-dd}
     * expiration-date {@code String} is parsed to a {@link LocalDate} via
     * {@link #parseExpirationDate(String)}.</p>
     *
     * <p>The {@code version} field is NOT copied from the DTO (managed by JPA optimistic
     * locking, PR-22), and the audit fields are NOT copied (managed by the JPA
     * {@code AuditingEntityListener}).</p>
     *
     * <p>Returns {@code null} if the input is {@code null}.</p>
     *
     * @param dto the {@code CardDto} (may be {@code null})
     * @return a transient {@code Card} entity, or {@code null} if the input is {@code null}
     */
    public Card toEntity(CardDto dto) {
        if (dto == null) {
            return null;
        }
        Card card = new Card();
        card.setCardNum(dto.getCardNum());
        card.setAccountId(dto.getAccountId());
        card.setCvvCd(parseCvv(dto.getCvvCode()));
        card.setEmbossedName(dto.getEmbossedName());
        card.setExpirationDate(parseExpirationDate(dto.getExpirationDate()));
        card.setActiveStatus(dto.getActiveStatus());
        return card;
    }

    /**
     * Applies a partial update from a {@link CardDto} to an existing managed {@link Card}
     * entity for the {@code PUT /api/cards/{cardNum}} flow. The entity is mutated in place
     * (PATCH-like semantics) so JPA dirty-checking issues the {@code UPDATE} within the
     * active transaction.
     *
     * <p>Performs the field-name translation {@code CardDto.cvvCode} (DTO) &rarr;
     * {@code Card.cvvCd} (entity) with the {@code String} &rarr; {@code Short} conversion
     * via {@link #parseCvv(String)}, and parses the ISO {@code yyyy-MM-dd} expiration date
     * to a {@link LocalDate} via {@link #parseExpirationDate(String)}.</p>
     *
     * <p>The {@code cardNum} primary key is NOT updated (immutable). Server-controlled
     * fields ({@code version}, audit fields) are NOT modified.</p>
     *
     * @param dto      the source {@code CardDto} carrying updated values (must not be
     *                 {@code null})
     * @param existing the existing managed {@code Card} entity to mutate (must not be
     *                 {@code null})
     * @throws NullPointerException if {@code dto} or {@code existing} is {@code null}
     */
    public void updateEntity(CardDto dto, Card existing) {
        if (dto == null || existing == null) {
            throw new NullPointerException("dto and existing must not be null");
        }
        // cardNum intentionally NOT updated (PK is immutable)
        existing.setAccountId(dto.getAccountId());
        existing.setCvvCd(parseCvv(dto.getCvvCode()));
        existing.setEmbossedName(dto.getEmbossedName());
        existing.setExpirationDate(parseExpirationDate(dto.getExpirationDate()));
        existing.setActiveStatus(dto.getActiveStatus());
    }

    /**
     * Converts a list of {@link Card} entities to a list of {@link CardDto} objects,
     * preserving order. Each element is mapped via {@link #toDto(Card)}, so the CVV and
     * expiration-date conversions apply uniformly to every entry.
     *
     * <p>Returns an empty, immutable list when the input is {@code null} or empty; the
     * result is never {@code null}.</p>
     *
     * @param cards the list of {@code Card} entities (may be {@code null})
     * @return a new immutable list of {@code CardDto} in input order (never {@code null})
     */
    public List<CardDto> toDtoList(List<Card> cards) {
        if (cards == null || cards.isEmpty()) {
            return List.of();
        }
        return cards.stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Converts a Spring Data {@link Page} of {@link Card} entities to a
     * {@link CardListResponse} record for the {@code GET /api/accounts/{acctId}/cards}
     * paginated list endpoint.
     *
     * <p>Replaces the original VSAM {@code STARTBR DATASET('CARDAIX')} browse cursor
     * ({@code COCRDLIC} PF7/PF8 pagination) with a stateless paginated response built from
     * Spring Data {@code Pageable} parameters (AAP &sect;0.6.1). All 7 components of the
     * Java 17 {@code record} are populated from the {@link Page} metadata, with the page
     * entries mapped via {@link #toDto(Card)}. Per Spring Data convention,
     * {@link Page#getNumber()} is the zero-based index of the current page.</p>
     *
     * @param page the Spring Data {@code Page} of {@code Card} entities (must not be
     *             {@code null})
     * @return a {@code CardListResponse} carrying the mapped page content and pagination
     *         metadata
     * @throws NullPointerException if {@code page} is {@code null}
     */
    public CardListResponse toListResponse(Page<Card> page) {
        if (page == null) {
            throw new NullPointerException("page must not be null");
        }
        List<CardDto> content = page.getContent().stream()
                .map(this::toDto)
                .toList();
        return new CardListResponse(
                content,
                page.getTotalElements(),
                page.getTotalPages(),
                page.getNumber(),
                page.getSize(),
                page.hasNext(),
                page.hasPrevious());
    }

    // -------------------- private conversion helpers --------------------

    /**
     * Renders the entity's {@link Short} CVV as the DTO's 3-character {@code String},
     * zero-padding to exactly three digits ({@code String.format("%03d", ...)}) so that
     * leading zeros are preserved (COBOL {@code CARD-CVV-CD PIC 9(03)}, e.g. {@code 7 ->
     * "007"}). The result satisfies the DTO's {@code @Pattern("\\d{3}")} contract.
     * {@code null}-safe.
     *
     * @param cvvCd the entity CVV (may be {@code null})
     * @return the 3-digit CVV string, or {@code null} if {@code cvvCd} is {@code null}
     */
    private static String formatCvv(Short cvvCd) {
        return cvvCd == null ? null : String.format("%03d", cvvCd);
    }

    /**
     * Parses the DTO's 3-character CVV {@code String} into the entity's {@link Short}.
     * {@code null}-safe; a {@code null} or blank input maps to {@code null}. A non-blank,
     * non-numeric value (one that bypassed the DTO's {@code @Pattern} validation) raises
     * {@link NumberFormatException}, which is handled at the REST boundary.
     *
     * @param cvvCode the DTO CVV string (may be {@code null} or blank)
     * @return the parsed {@link Short}, or {@code null} if {@code cvvCode} is {@code null}
     *         or blank
     */
    private static Short parseCvv(String cvvCode) {
        if (cvvCode == null || cvvCode.isBlank()) {
            return null;
        }
        return Short.valueOf(cvvCode.trim());
    }

    /**
     * Renders the entity's {@link LocalDate} expiration date as the DTO's {@code String}
     * in ISO {@code yyyy-MM-dd} form via {@link LocalDate#toString()} (matching the COBOL
     * {@code PIC X(10)} external shape and the DTO's {@code @Pattern} contract).
     * {@code null}-safe.
     *
     * @param expirationDate the entity expiration date (may be {@code null})
     * @return the ISO {@code yyyy-MM-dd} string, or {@code null} if {@code expirationDate}
     *         is {@code null}
     */
    private static String formatExpirationDate(LocalDate expirationDate) {
        return expirationDate == null ? null : expirationDate.toString();
    }

    /**
     * Parses the DTO's ISO {@code yyyy-MM-dd} expiration-date {@code String} into the
     * entity's {@link LocalDate} via {@link LocalDate#parse(CharSequence)}.
     * {@code null}-safe; a {@code null} or blank input maps to {@code null}. A non-blank,
     * malformed value (one that bypassed the DTO's {@code @Pattern} validation) raises
     * {@link java.time.format.DateTimeParseException}, which is handled at the REST boundary.
     *
     * @param expirationDate the DTO date string (may be {@code null} or blank)
     * @return the parsed {@link LocalDate}, or {@code null} if {@code expirationDate} is
     *         {@code null} or blank
     */
    private static LocalDate parseExpirationDate(String expirationDate) {
        if (expirationDate == null || expirationDate.isBlank()) {
            return null;
        }
        return LocalDate.parse(expirationDate.trim());
    }
}
