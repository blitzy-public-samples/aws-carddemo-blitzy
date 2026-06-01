package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.dto.card.CardDto;
import com.carddemo.dto.card.CardListResponse;
import com.carddemo.entity.Card;
import com.carddemo.entity.CardXref;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.mapper.CardMapper;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CardXrefRepository;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit test for {@link CardService}.
 *
 * <p>Verifies the service reproduces the three CICS card programs' behavior with the exact
 * COBOL message literals (PR-03), pagination-as-200-for-empty semantics (COCRDLIC), the
 * two-stage COCRDSLC account/card validation, and the PK-preserving read-then-rewrite
 * update path (COCRDUPC). The repositories and mapper are mocked; no Spring context or
 * database is involved.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardService — COCRDLIC/COCRDSLC/COCRDUPC behavior (PR-03/PR-22/PR-24)")
class CardServiceTest {

    private static final Long ACCT_ID = 12345678901L;
    private static final String CARD_NUM = "4111111111111111";

    @Mock
    private CardRepository cardRepository;

    @Mock
    private CardMapper cardMapper;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @InjectMocks
    private CardService cardService;

    private Card card(Long accountId) {
        Card c = new Card();
        c.setCardNum(CARD_NUM);
        c.setAccountId(accountId);
        return c;
    }

    private CardDto dto() {
        return CardDto.builder()
                .cardNum(CARD_NUM)
                .accountId(ACCT_ID)
                .cvvCode("123")
                .embossedName("JANE DOE")
                .expirationDate("2027-12-31")
                .activeStatus("Y")
                .build();
    }

    private CardListResponse response(List<CardDto> content) {
        return new CardListResponse(content, content.size(), 1, 0, 10, false, false);
    }

    @Nested
    @DisplayName("findByAccountId (COCRDLIC card list)")
    class FindByAccountId {

        @Test
        @DisplayName("Maps the repository page through the mapper and returns the response")
        void mapsPageToResponse() {
            Card c = card(ACCT_ID);
            Page<Card> page = new PageImpl<>(List.of(c), PageRequest.of(0, 10), 1);
            CardListResponse expected = response(List.of(dto()));
            when(cardRepository.findByAccountId(ACCT_ID, PageRequest.of(0, 10))).thenReturn(page);
            when(cardMapper.toListResponse(page)).thenReturn(expected);

            CardListResponse result = cardService.findByAccountId(ACCT_ID, PageRequest.of(0, 10));

            assertThat(result).isSameAs(expected);
            verify(cardMapper).toListResponse(page);
        }

        @Test
        @DisplayName("Empty page is a normal 200 (no exception) — COCRDLIC NO RECORDS is informational")
        void emptyPageReturnsEmptyResponseNoThrow() {
            Page<Card> empty = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
            CardListResponse emptyResponse = response(List.of());
            when(cardRepository.findByAccountId(ACCT_ID, PageRequest.of(0, 10))).thenReturn(empty);
            when(cardMapper.toListResponse(empty)).thenReturn(emptyResponse);

            CardListResponse result = cardService.findByAccountId(ACCT_ID, PageRequest.of(0, 10));

            assertThat(result).isSameAs(emptyResponse);
            assertThat(result.content()).isEmpty();
        }
    }

    @Nested
    @DisplayName("getCard (COCRDSLC card-number view)")
    class GetCard {

        @Test
        @DisplayName("Returns the mapped DTO when the card exists")
        void returnsDtoWhenFound() {
            Card c = card(ACCT_ID);
            CardDto expected = dto();
            when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(c));
            when(cardMapper.toDto(c)).thenReturn(expected);

            assertThat(cardService.getCard(CARD_NUM)).isSameAs(expected);
        }

        @Test
        @DisplayName("Throws 404 with the exact COBOL message when the card is absent")
        void throwsExactMessageWhenNotFound() {
            when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cardService.getCard(CARD_NUM))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Did not find cards for this search condition");
        }
    }

    @Nested
    @DisplayName("getCardForAccount (COCRDSLC two-stage account/card validation)")
    class GetCardForAccount {

        @Test
        @DisplayName("Returns the DTO when the account has xrefs and the card belongs to it")
        void returnsDtoForValidCombination() {
            Card c = card(ACCT_ID);
            CardDto expected = dto();
            when(cardXrefRepository.findByAccountId(ACCT_ID)).thenReturn(List.of(new CardXref()));
            when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(c));
            when(cardMapper.toDto(c)).thenReturn(expected);

            assertThat(cardService.getCardForAccount(ACCT_ID, CARD_NUM)).isSameAs(expected);
        }

        @Test
        @DisplayName("Account absent from cross-reference throws DID-NOT-FIND-ACCT-IN-CARDXREF message")
        void throwsAcctNotInXrefWhenNoCrossReference() {
            when(cardXrefRepository.findByAccountId(ACCT_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> cardService.getCardForAccount(ACCT_ID, CARD_NUM))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Did not find this account in cards database");
            // Stage 1 short-circuits before any card read.
            verify(cardRepository, never()).findById(any());
        }

        @Test
        @DisplayName("Card belonging to a different account throws DID-NOT-FIND-ACCTCARD-COMBO message")
        void throwsComboMissWhenCardBelongsToOtherAccount() {
            Card otherAccountsCard = card(99999999999L);
            when(cardXrefRepository.findByAccountId(ACCT_ID)).thenReturn(List.of(new CardXref()));
            when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(otherAccountsCard));

            assertThatThrownBy(() -> cardService.getCardForAccount(ACCT_ID, CARD_NUM))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Did not find cards for this search condition");
        }

        @Test
        @DisplayName("Missing card (xref present) throws DID-NOT-FIND-ACCTCARD-COMBO message")
        void throwsComboMissWhenCardAbsent() {
            when(cardXrefRepository.findByAccountId(ACCT_ID)).thenReturn(List.of(new CardXref()));
            when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cardService.getCardForAccount(ACCT_ID, CARD_NUM))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Did not find cards for this search condition");
        }
    }

    @Nested
    @DisplayName("updateCard (COCRDUPC read-update-rewrite)")
    class UpdateCard {

        @Test
        @DisplayName("Applies mutable fields, saves the managed entity, and returns the mapped DTO")
        void updatesAndReturnsDto() {
            Card existing = card(ACCT_ID);
            CardDto request = dto();
            CardDto expected = dto();
            when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(existing));
            when(cardRepository.save(existing)).thenReturn(existing);
            when(cardMapper.toDto(existing)).thenReturn(expected);

            CardDto result = cardService.updateCard(CARD_NUM, request);

            assertThat(result).isSameAs(expected);
            // PK-preserving update applied to the managed entity, then REWRITE.
            verify(cardMapper).updateEntity(request, existing);
            verify(cardRepository).save(existing);
        }

        @Test
        @DisplayName("Throws 404 with the exact COBOL message when the card to update is absent")
        void throwsExactMessageWhenNotFound() {
            when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cardService.updateCard(CARD_NUM, dto()))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Did not find cards for this search condition");
            verify(cardRepository, never()).save(any());
            verify(cardMapper, never()).updateEntity(any(), any());
        }
    }
}
