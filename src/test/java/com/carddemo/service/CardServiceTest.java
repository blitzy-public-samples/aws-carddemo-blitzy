package com.carddemo.service;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.carddemo.dto.CardListItem;
import com.carddemo.dto.CardResponse;
import com.carddemo.dto.CardUpdateRequest;
import com.carddemo.dto.PageResponse;
import com.carddemo.entity.Card;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.mapper.CardMapper;
import com.carddemo.repository.CardRepository;
import com.carddemo.util.CardDemoConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link CardService}, the Java replacement for the three CICS online
 * card programs {@code COCRDLIC} (list), {@code COCRDSLC} (view), and {@code COCRDUPC} (update).
 *
 * <h2>What this test pins (AAP &sect;0.4.1.3 / &sect;0.6.8 / &sect;0.7.1)</h2>
 * <ol>
 *   <li><strong>Page size 7 browse parity.</strong> {@link CardService#listCards(Long, int)} MUST
 *       build {@code PageRequest.of(page, 7)} &mdash; the legacy {@code WS-MAX-SCREEN-LINES VALUE 7}
 *       screen window of {@code COCRDLIC}. We capture the {@link Pageable} the service hands the
 *       repository with an {@link ArgumentCaptor} and assert its page size is exactly
 *       {@link CardDemoConstants#PAGE_SIZE} ({@code == 7}).</li>
 *   <li><strong>CVV suppression by design.</strong> The card verification value ({@code CARD-CVV-CD},
 *       persisted on the {@link Card} entity as an {@code Integer}) MUST NEVER be serialized. This is
 *       enforced <em>structurally</em>: neither {@link CardResponse} nor {@link CardListItem} declares
 *       a component for it. We prove the absence reflectively over the records' components and also
 *       serialize a {@link CardResponse} to JSON and assert no {@code "cvv"} / {@code "123"} leaks.</li>
 *   <li><strong>Card-identity immutability.</strong> {@code COCRDUPC} edits only the embossed name,
 *       expiration date, and active status; the card number and owning account id are part of the
 *       record key and are never rewritten. We capture the {@link Card} the service saves and assert
 *       its card number, account id, and CVV are unchanged while the three editable fields are
 *       applied. {@link CardUpdateRequest} cannot even carry the immutable keys &mdash; proven
 *       reflectively.</li>
 * </ol>
 *
 * <h2>Test character</h2>
 * <p>This is a <strong>pure unit test</strong>: {@code @ExtendWith(MockitoExtension.class)} with no
 * Spring context and no database. The real {@link CardService} constructor declares two collaborators
 * &mdash; {@link CardRepository} and {@link CardMapper} &mdash; so both are {@code @Mock}ed and wired
 * via {@code @InjectMocks}. Where the service delegates to the mapper, the mock is given a faithful
 * {@code thenAnswer}/{@code doAnswer} behaviour that mirrors the real {@code CardMapper} (it copies
 * only the editable subset and never touches the immutable keys or the CVV), so the captured entities
 * and responses are meaningful without coupling the test to a concrete mapper instance.</p>
 *
 * <p>{@code MockitoExtension} runs in strict-stubbing mode: every stub declared in a test is exercised
 * by that test, and argument captors are used in the {@code verify} position rather than during
 * stubbing, per Mockito best practice.</p>
 *
 * <p>No personally identifiable information leaves the service boundary in these tests: the CVV is
 * only ever set on the in-memory fixture entity and is asserted to be absent from every DTO and from
 * the serialized JSON (AAP &sect;0.6.8 / &sect;0.7.1).</p>
 *
 * @see CardService
 * @see CardRepository
 * @see CardMapper
 * @see CardResponse
 * @see CardListItem
 * @see CardUpdateRequest
 * @see PageResponse
 * @see CardDemoConstants#PAGE_SIZE
 */
@ExtendWith(MockitoExtension.class)
class CardServiceTest {

    // ---------------------------------------------------------------------------------------------
    // Fixture constants — a single, well-known card. cvvCode is an Integer (CARD-CVV-CD PIC 9(03)),
    // NOT a String: the test mirrors the real Card entity, which is authoritative.
    // ---------------------------------------------------------------------------------------------

    /** 16-character card number / PAN; the {@link Card} primary key and an immutable lookup key. */
    private static final String CARD_NUM = "4111111111111111";
    /** Owning account id ({@code CARD-ACCT-ID PIC 9(11)}); an immutable lookup key. */
    private static final Long ACCT_ID = 10L;
    /** Sensitive card verification value ({@code CARD-CVV-CD PIC 9(03)}); must never be serialized. */
    private static final Integer CVV = 123;
    /** Embossed cardholder name ({@code CARD-EMBOSSED-NAME PIC X(50)}); an editable field. */
    private static final String EMBOSSED_NAME = "JOHN DOE";
    /** Card expiration date ({@code CARD-EXPIRAION-DATE PIC X(10)}); an editable field. */
    private static final LocalDate EXPIRATION = LocalDate.of(2027, 4, 30);
    /** Active-status flag ({@code CARD-ACTIVE-STATUS PIC X(01)}); an editable field. */
    private static final String ACTIVE_STATUS = "Y";

    /**
     * Card persistence gateway &mdash; mocked. Supplies the primary-key read ({@code findById}), the
     * account-filtered browse ({@code findByCardAcctId}), the unfiltered admin browse
     * ({@code findAll(Pageable)}), and {@code save}.
     */
    @Mock
    private CardRepository cardRepository;

    /**
     * Entity&harr;DTO mapper &mdash; mocked. The real service delegates list/detail projection and the
     * editable-subset update to this collaborator; the mock is given faithful answers in the tests
     * that need them.
     */
    @Mock
    private CardMapper cardMapper;

    /** Class under test, with both mocks injected through its single constructor. */
    @InjectMocks
    private CardService cardService;

    /** A fresh, fully-populated card fixture rebuilt before every test to guarantee isolation. */
    private Card card;

    @BeforeEach
    void setUp() {
        // Convenience constructor order: (cardNum, cardAcctId, cvvCode, embossedName, expirationDate,
        // activeStatus). cvvCode is the Integer 123 — the entity persists CVV but never exposes it.
        card = new Card(CARD_NUM, ACCT_ID, CVV, EMBOSSED_NAME, EXPIRATION, ACTIVE_STATUS);
    }

    /**
     * Builds a {@link CardResponse} from a {@link Card} exactly as the real {@code CardMapper} does:
     * positional, CVV-free. Used as the stubbed mapper answer so the response under assertion reflects
     * the actual entity state while still proving the CVV can never be copied (the record has no slot
     * for it).
     */
    private static CardResponse toResponseLikeMapper(Card c) {
        return new CardResponse(
                c.getCardNum(),
                c.getCardAcctId(),
                c.getEmbossedName(),
                c.getExpirationDate(),
                c.getActiveStatus());
    }

    /**
     * Builds a {@link CardListItem} from a {@link Card} exactly as the real {@code CardMapper} does:
     * positional ({@code cardAcctId, cardNum, activeStatus}), CVV-free.
     */
    private static CardListItem toListItemLikeMapper(Card c) {
        return new CardListItem(c.getCardAcctId(), c.getCardNum(), c.getActiveStatus());
    }

    // =============================================================================================
    // Phase 2 — listCards: pagination pinned to the legacy 7-row browse (COCRDLIC parity)
    // =============================================================================================

    @Test
    @DisplayName("listCards(accountId): filters by CARDAIX account id and pins page size to 7")
    void listCardsFiltersByAccountIdAndPinsPageSizeSeven() {
        // Arrange — the repository returns a single-element page whose Pageable carries size 7.
        Page<Card> repoPage = new PageImpl<>(List.of(card), PageRequest.of(0, 7), 1);
        when(cardRepository.findByCardAcctId(eq(ACCT_ID), any(Pageable.class))).thenReturn(repoPage);
        // The mapper faithfully converts the page the service built into a CVV-free PageResponse.
        when(cardMapper.toPageResponse(any()))
                .thenAnswer(inv -> {
                    Page<Card> p = inv.getArgument(0);
                    return PageResponse.from(p, CardServiceTest::toListItemLikeMapper);
                });

        // Act — non-administrative path: a concrete account id filters the browse.
        PageResponse<CardListItem> resp = cardService.listCards(ACCT_ID, 0);

        // Assert — capture the Pageable the SERVICE constructed and pin the legacy 7-row window.
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(cardRepository).findByCardAcctId(eq(ACCT_ID), pageableCaptor.capture());
        Pageable usedPageable = pageableCaptor.getValue();
        assertThat(usedPageable.getPageSize())
                .as("legacy COCRDLIC browse window WS-MAX-SCREEN-LINES VALUE 7")
                .isEqualTo(CardDemoConstants.PAGE_SIZE)
                .isEqualTo(7);
        assertThat(usedPageable.getPageNumber()).isZero();

        // The admin "show all" path must NOT have been used when an account id is supplied.
        verify(cardRepository, never()).findAll(any(Pageable.class));

        // Assert — the response carries the size-7 window and one correctly-projected row.
        assertThat(resp).isNotNull();
        assertThat(resp.size()).isEqualTo(7);
        assertThat(resp.page()).isZero();
        assertThat(resp.totalElements()).isEqualTo(1);
        assertThat(resp.content()).hasSize(1);
        CardListItem firstRow = resp.content().get(0);
        assertThat(firstRow.cardAcctId()).isEqualTo(ACCT_ID);
        assertThat(firstRow.cardNum()).isEqualTo(CARD_NUM);
        assertThat(firstRow.activeStatus()).isEqualTo("Y");
    }

    @Test
    @DisplayName("listCards(null): administrator show-all path browses findAll(Pageable) at size 7")
    void listCardsWithNullAccountIdUsesFindAll() {
        // Arrange — administrator path: no account context => full-table browse via findAll(Pageable).
        Page<Card> repoPage = new PageImpl<>(List.of(card), PageRequest.of(0, 7), 1);
        when(cardRepository.findAll(any(Pageable.class))).thenReturn(repoPage);
        when(cardMapper.toPageResponse(any()))
                .thenAnswer(inv -> {
                    Page<Card> p = inv.getArgument(0);
                    return PageResponse.from(p, CardServiceTest::toListItemLikeMapper);
                });

        // Act
        PageResponse<CardListItem> resp = cardService.listCards(null, 0);

        // Assert — findAll received a size-7 Pageable, and the account-filtered browse was not used.
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(cardRepository).findAll(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(7);
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        verify(cardRepository, never()).findByCardAcctId(any(), any(Pageable.class));

        assertThat(resp.size()).isEqualTo(7);
        assertThat(resp.content()).hasSize(1);
        assertThat(resp.content().get(0).cardNum()).isEqualTo(CARD_NUM);
    }

    // =============================================================================================
    // Phase 3 — getCard: single-card detail view with CVV suppression (COCRDSLC parity)
    // =============================================================================================

    @Test
    @DisplayName("getCard: returns the card detail and NEVER exposes the CVV")
    void getCardReturnsDetailWithoutCvv() throws Exception {
        // Arrange — primary-key read returns the card; the mapper produces the CVV-free response.
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(card));
        when(cardMapper.toResponse(any(Card.class)))
                .thenAnswer(inv -> toResponseLikeMapper(inv.getArgument(0)));

        // Act
        CardResponse resp = cardService.getCard(CARD_NUM);

        // Assert — every visible field is present and correct.
        assertThat(resp).isNotNull();
        assertThat(resp.cardNum()).isEqualTo(CARD_NUM);
        assertThat(resp.cardAcctId()).isEqualTo(ACCT_ID);
        assertThat(resp.embossedName()).isEqualTo(EMBOSSED_NAME);
        assertThat(resp.expirationDate()).isEqualTo(EXPIRATION);
        assertThat(resp.activeStatus()).isEqualTo("Y");

        // CVV suppression (critical): the serialized JSON must not contain the field name or value.
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String json = mapper.writeValueAsString(resp);
        assertThat(json)
                .as("CardResponse JSON must never carry the card verification value")
                .doesNotContainIgnoringCase("cvv")
                .doesNotContain("123");
    }

    @Test
    @DisplayName("getCard: missing card raises ResourceNotFoundException and never touches the mapper")
    void getCardNotFoundThrows() {
        // Arrange — no record for the supplied card number.
        when(cardRepository.findById("0000")).thenReturn(Optional.empty());

        // Act + Assert — 404-mapped domain exception, carrying the resource type and id.
        assertThatThrownBy(() -> cardService.getCard("0000"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Card")
                .hasMessageContaining("0000");

        // The mapper is never consulted on the not-found path.
        verifyNoInteractions(cardMapper);
    }

    // =============================================================================================
    // Phase 4 — updateCard: editable fields applied; cardNum / cardAcctId / cvv immutable (COCRDUPC)
    // =============================================================================================

    @Test
    @DisplayName("updateCard: applies the 3 editable fields but keeps cardNum, cardAcctId and CVV immutable")
    void updateCardAppliesEditableFieldsAndKeepsKeysImmutable() {
        // Arrange — load the existing card by primary key.
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(card));
        // Faithful mapper behaviour: copy ONLY embossedName/expirationDate/activeStatus when present;
        // it must never touch the card number, account id, or CVV (mirrors the real CardMapper).
        doAnswer(inv -> {
            CardUpdateRequest r = inv.getArgument(0);
            Card target = inv.getArgument(1);
            if (r.embossedName() != null) {
                target.setEmbossedName(r.embossedName());
            }
            if (r.expirationDate() != null) {
                target.setExpirationDate(r.expirationDate());
            }
            if (r.activeStatus() != null) {
                target.setActiveStatus(r.activeStatus());
            }
            return null;
        }).when(cardMapper).applyUpdate(any(CardUpdateRequest.class), any(Card.class));
        // save() echoes the managed entity back.
        when(cardRepository.save(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cardMapper.toResponse(any(Card.class)))
                .thenAnswer(inv -> toResponseLikeMapper(inv.getArgument(0)));

        // Only the three editable fields are carried by the request (keys/CVV are not present).
        CardUpdateRequest request = new CardUpdateRequest("NEW NAME", "N", LocalDate.of(2030, 12, 31));

        // Act
        CardResponse resp = cardService.updateCard(CARD_NUM, request);

        // Assert — capture the exact entity handed to save() and prove the immutability contract.
        ArgumentCaptor<Card> savedCaptor = ArgumentCaptor.forClass(Card.class);
        verify(cardRepository).save(savedCaptor.capture());
        Card saved = savedCaptor.getValue();

        // Immutable lookup keys + sensitive CVV: UNCHANGED.
        assertThat(saved.getCardNum()).as("card number is an immutable key").isEqualTo(CARD_NUM);
        assertThat(saved.getCardAcctId()).as("owning account id is immutable").isEqualTo(ACCT_ID);
        assertThat(saved.getCvvCode()).as("CVV is never mutated through update").isEqualTo(CVV);

        // Editable subset: APPLIED.
        assertThat(saved.getEmbossedName()).isEqualTo("NEW NAME");
        assertThat(saved.getActiveStatus()).isEqualTo("N");
        assertThat(saved.getExpirationDate()).isEqualTo(LocalDate.of(2030, 12, 31));

        // The service delegated the editable-subset application to the mapper using the loaded entity.
        verify(cardMapper).applyUpdate(eq(request), same(card));

        // The returned response reflects the persisted state and remains CVV-free.
        assertThat(resp.cardNum()).isEqualTo(CARD_NUM);
        assertThat(resp.cardAcctId()).isEqualTo(ACCT_ID);
        assertThat(resp.embossedName()).isEqualTo("NEW NAME");
        assertThat(resp.activeStatus()).isEqualTo("N");
        assertThat(resp.expirationDate()).isEqualTo(LocalDate.of(2030, 12, 31));
    }

    @Test
    @DisplayName("updateCard: missing card raises ResourceNotFoundException and never saves")
    void updateCardNotFoundThrowsAndDoesNotSave() {
        // Arrange — no record for the supplied card number.
        when(cardRepository.findById("0000")).thenReturn(Optional.empty());
        CardUpdateRequest request = new CardUpdateRequest("NEW NAME", "N", LocalDate.of(2030, 12, 31));

        // Act + Assert
        assertThatThrownBy(() -> cardService.updateCard("0000", request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Card")
                .hasMessageContaining("0000");

        // No mutation is attempted when the record does not exist.
        verify(cardRepository, never()).save(any(Card.class));
        verify(cardMapper, never()).applyUpdate(any(), any());
    }

    // =============================================================================================
    // Structural suppression-by-design proofs (reflection; no mocks, no Spring, no DB)
    // ---------------------------------------------------------------------------------------------
    // These pin the type-level guarantees that make CVV un-leakable and the card keys un-mutable:
    // the DTO records simply have no component for the forbidden fields, so the values can never be
    // serialized into a response nor submitted for change. "You cannot leak/mutate a field that does
    // not exist on the contract."
    // =============================================================================================

    @Test
    @DisplayName("CardResponse declares no CVV component (CVV cannot be serialized)")
    void cardResponseStructurallyOmitsCvv() {
        List<String> components = recordComponentNames(CardResponse.class);
        assertThat(components)
                .containsExactly("cardNum", "cardAcctId", "embossedName", "expirationDate", "activeStatus");
        assertThat(components).noneMatch(name -> name.toLowerCase().contains("cvv"));
    }

    @Test
    @DisplayName("CardListItem declares no CVV component (list rows cannot serialize CVV)")
    void cardListItemStructurallyOmitsCvv() {
        List<String> components = recordComponentNames(CardListItem.class);
        assertThat(components).containsExactly("cardAcctId", "cardNum", "activeStatus");
        assertThat(components).noneMatch(name -> name.toLowerCase().contains("cvv"));
    }

    @Test
    @DisplayName("CardUpdateRequest declares no cardNum / cardAcctId / CVV components (keys immutable)")
    void cardUpdateRequestStructurallyOmitsKeysAndCvv() {
        List<String> components = recordComponentNames(CardUpdateRequest.class);
        // Only the three editable fields are present (mirrors COCRDUPC L318-320).
        assertThat(components).containsExactly("embossedName", "activeStatus", "expirationDate");
        assertThat(components).noneMatch(name -> {
            String lower = name.toLowerCase();
            return lower.contains("cvv")
                    || lower.contains("cardnum")
                    || lower.contains("acctid")
                    || lower.contains("accountid");
        });
    }

    /**
     * Returns the canonical, declaration-ordered list of record-component names for the given record
     * class &mdash; the basis for the structural suppression/immutability proofs above.
     *
     * @param recordClass a {@code record} type to introspect
     * @return the ordered component names
     */
    private static List<String> recordComponentNames(Class<?> recordClass) {
        return Arrays.stream(recordClass.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toList());
    }
}
