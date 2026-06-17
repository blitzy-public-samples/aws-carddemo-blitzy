package com.carddemo.mapper;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import com.carddemo.dto.CardListItem;
import com.carddemo.dto.CardResponse;
import com.carddemo.dto.CardUpdateRequest;
import com.carddemo.dto.PageResponse;
import com.carddemo.entity.Card;

/**
 * Stateless, hand-written mapper that converts the {@link Card} JPA entity into its outbound
 * response/list DTOs and applies the editable subset of a {@link CardUpdateRequest} back onto a
 * managed {@link Card} instance.
 *
 * <h2>Role in the layered architecture</h2>
 * <p>This component sits on the DTO / mapper boundary between the persistence layer
 * ({@code Card} entity) and the REST API contract ({@code CardResponse}, {@code CardListItem},
 * {@code CardUpdateRequest}). Controllers and services depend on it to translate between the two
 * representations; it deliberately holds no business logic and no state, so a single shared
 * instance is safe to inject anywhere (Spring registers it as a singleton {@code @Component}).</p>
 *
 * <h2>Legacy lineage</h2>
 * <p>The mapping reproduces the field movements performed by the CICS online card programs:
 * {@code COCRDSLC} (card detail view &rarr; {@link #toResponse(Card)}), {@code COCRDLIC}
 * (seven-row card-list browse &rarr; {@link #toListItem(Card)} / {@link #toPageResponse(Page)}),
 * and {@code COCRDUPC} (card maintenance &rarr; {@link #applyUpdate(CardUpdateRequest, Card)}).
 * The underlying record layout is the COBOL {@code CARD-RECORD} of copybook
 * {@code app/cpy/CVACT02Y.cpy} (record length 150).</p>
 *
 * <h2>Security boundary &mdash; verification-code suppression (AAP &sect;0.6.8 / &sect;0.7.1)</h2>
 * <p>The three-digit card verification value ({@code CARD-CVV-CD PIC 9(03)}) is persisted on the
 * {@link Card} entity but <strong>MUST NEVER be serialized to a client nor written to logs</strong>.
 * This mapper enforces that rule <em>structurally</em>: it never reads the verification-code
 * accessor on {@link Card}, and the target DTOs ({@link CardResponse}, {@link CardListItem})
 * intentionally declare no verification-code component. "You cannot leak a field you never touch."</p>
 *
 * <h2>Security boundary &mdash; card identity immutability (AAP &sect;0.6.8 / &sect;0.7.1)</h2>
 * <p>The card number ({@code CARD-NUM}) and the owning account id ({@code CARD-ACCT-ID}) are lookup
 * keys that are immutable after creation, exactly as in {@code COCRDUPC}, whose editable record
 * comprises only {@code CARD-UPDATE-EMBOSSED-NAME}, {@code CARD-UPDATE-EXPIRAION-DATE}, and
 * {@code CARD-UPDATE-ACTIVE-STATUS} (app/cbl/COCRDUPC.cbl L318-L320). Accordingly
 * {@link #applyUpdate(CardUpdateRequest, Card)} never invokes the card-number setter or the
 * account-id setter &mdash; and {@link CardUpdateRequest} does not even carry those fields.</p>
 *
 * <p>This class is implemented as plain, explicit Java (no MapStruct, ModelMapper, or Lombok) so the
 * suppression and immutability guarantees are auditable by reading the source directly.</p>
 *
 * @see Card
 * @see CardResponse
 * @see CardListItem
 * @see CardUpdateRequest
 * @see PageResponse
 */
@Component
public class CardMapper {

    /**
     * Maps a persisted {@link Card} to the immutable {@link CardResponse} returned by the
     * single-card view endpoint ({@code GET /cards/{cardNum}}).
     *
     * <p>The arguments are passed positionally in the exact canonical-constructor order declared by
     * the {@code CardResponse} record: {@code (cardNum, cardAcctId, embossedName, expirationDate,
     * activeStatus)}.</p>
     *
     * <p><strong>Verification-code suppression:</strong> the card verification value is deliberately
     * never read here; {@link CardResponse} has no component for it by design
     * (AAP &sect;0.6.8 / &sect;0.7.1).</p>
     *
     * @param card the source entity; may be {@code null}
     * @return the corresponding {@link CardResponse}, or {@code null} if {@code card} is {@code null}
     */
    public CardResponse toResponse(Card card) {
        if (card == null) {
            return null;
        }
        return new CardResponse(
                card.getCardNum(),
                card.getCardAcctId(),
                card.getEmbossedName(),
                card.getExpirationDate(),
                card.getActiveStatus());
    }

    /**
     * Maps a persisted {@link Card} to the lightweight {@link CardListItem} projection used as a row
     * of the paginated card-list response ({@code GET /cards}).
     *
     * <p>The arguments are passed positionally in the exact canonical-constructor order declared by
     * the {@code CardListItem} record: {@code (cardAcctId, cardNum, activeStatus)} &mdash; mirroring
     * the {@code ACCTNO} / {@code CRDNUM} / {@code CRDSTS} columns of the legacy {@code COCRDLI}
     * screen.</p>
     *
     * <p><strong>Verification-code suppression:</strong> the card verification value is never read;
     * {@link CardListItem} carries no component for it (AAP &sect;0.6.8 / &sect;0.7.1).</p>
     *
     * @param card the source entity; may be {@code null}
     * @return the corresponding {@link CardListItem}, or {@code null} if {@code card} is {@code null}
     */
    public CardListItem toListItem(Card card) {
        if (card == null) {
            return null;
        }
        return new CardListItem(
                card.getCardAcctId(),
                card.getCardNum(),
                card.getActiveStatus());
    }

    /**
     * Wraps a Spring Data {@link Page} of {@link Card} entities into the stable
     * {@link PageResponse} envelope whose content elements are verification-code-free
     * {@link CardListItem} rows.
     *
     * <p>Every pagination attribute (page number, size, total elements, total pages, first/last) is
     * preserved verbatim from the source {@link Page}. In particular the legacy fixed page size of
     * <strong>7</strong> is carried through from the {@code Pageable} the repository layer supplies;
     * it is never hard-coded here. The per-element conversion is delegated to {@link #toListItem(Card)}
     * via the {@link PageResponse#from(Page, java.util.function.Function)} factory, which routes both
     * mapping factories through a single code path.</p>
     *
     * @param page the source page of card entities; must not be {@code null}
     * @return a {@link PageResponse} of {@link CardListItem} reflecting the source page's content and
     *         metadata
     * @throws NullPointerException if {@code page} is {@code null} (enforced by
     *                              {@link PageResponse#from(Page, java.util.function.Function)})
     */
    public PageResponse<CardListItem> toPageResponse(Page<Card> page) {
        return PageResponse.from(page, this::toListItem);
    }

    /**
     * Applies the editable subset of a {@link CardUpdateRequest} to an existing managed {@link Card}.
     *
     * <p>Only the three fields that the legacy {@code COCRDUPC} program permits an operator to change
     * are applied &mdash; {@code embossedName}, {@code expirationDate}, and {@code activeStatus}
     * (app/cbl/COCRDUPC.cbl L318-L320). Each field is applied only when its request accessor returns a
     * non-{@code null} value, so the operation is partial-update friendly: an omitted (null) field
     * means "leave the existing value unchanged", consistent with the field-by-field change detection
     * of {@code COCRDUPC}.</p>
     *
     * <p><strong>Immutability (AAP &sect;0.6.8 / &sect;0.7.1):</strong> this method never invokes the
     * card-number setter or the owning-account-id setter &mdash; both are immutable lookup keys.
     * {@link CardUpdateRequest} does not carry those fields.</p>
     *
     * <p><strong>Verification-code suppression (AAP &sect;0.6.8 / &sect;0.7.1):</strong> the card
     * verification value is neither read from nor written to the request or the entity in the update
     * path; {@link CardUpdateRequest} has no component for it.</p>
     *
     * <p>If either argument is {@code null}, the call is a no-op (defensive guard).</p>
     *
     * @param request the inbound update payload carrying the editable fields; may be {@code null}
     * @param card    the managed entity to mutate in place; may be {@code null}
     */
    public void applyUpdate(CardUpdateRequest request, Card card) {
        if (request == null || card == null) {
            return;
        }
        // Editable field 1 of 3 — CARD-UPDATE-EMBOSSED-NAME (COCRDUPC L318).
        if (request.embossedName() != null) {
            card.setEmbossedName(request.embossedName());
        }
        // Editable field 2 of 3 — CARD-UPDATE-EXPIRAION-DATE (COCRDUPC L319).
        if (request.expirationDate() != null) {
            card.setExpirationDate(request.expirationDate());
        }
        // Editable field 3 of 3 — CARD-UPDATE-ACTIVE-STATUS (COCRDUPC L320).
        if (request.activeStatus() != null) {
            card.setActiveStatus(request.activeStatus());
        }
        // Intentionally NOT applied: the card number and owning account id are immutable keys, and
        // the card verification value is never accepted from any API contract (AAP 0.6.8 / 0.7.1).
    }
}
