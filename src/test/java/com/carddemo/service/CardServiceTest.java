package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.dto.card.CardDto;
import com.carddemo.dto.card.CardListResponse;
import com.carddemo.entity.Card;
import com.carddemo.entity.CardXref;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.exception.InvalidCardException;
import com.carddemo.mapper.CardMapper;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CardXrefRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit test for {@link CardService}.
 *
 * <p>Verifies the service reproduces the three CICS card programs' behavior against the
 * schema-conformant API ({@code listByAccount}/{@code getCard}/{@code updateCard}) with the exact
 * COBOL message literals (PR-03): the COCRDLIC cross-reference account-existence check and 7-row
 * default page size, the COCRDSLC card-number view edits, and the COCRDUPC field validation,
 * no-change detection, and optimistic-lock rewrite path (PR-22/PR-24). The repositories and mapper
 * are mocked; no Spring context or database is involved.</p>
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

    /** A managed card with known mutable values so change-detection can be exercised. */
    private Card card(Long accountId) {
        Card c = new Card();
        c.setCardNum(CARD_NUM);
        c.setAccountId(accountId);
        c.setEmbossedName("JOHN DOE");
        c.setActiveStatus("Y");
        c.setExpirationDate(LocalDate.of(2025, 1, 1));
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

    private CardListResponse response(List<CardDto> content, int pageSize) {
        return new CardListResponse(content, content.size(), 1, 0, pageSize, false, false);
    }

    @Nested
    @DisplayName("listByAccount (COCRDLIC card list)")
    class ListByAccount {

        @Test
        @DisplayName("Maps the repository page through the mapper and defaults page size to 7")
        void mapsPageAndDefaultsSizeToSeven() {
            Card c = card(ACCT_ID);
            Page<Card> page = new PageImpl<>(List.of(c));
            CardListResponse expected = response(List.of(dto()), 7);
            when(cardXrefRepository.findByAccountId(ACCT_ID)).thenReturn(List.of(new CardXref()));
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            when(cardRepository.findByAccountId(eq(ACCT_ID), pageable.capture())).thenReturn(page);
            when(cardMapper.toListResponse(page)).thenReturn(expected);

            // size <= 0 must fall back to DEFAULT_PAGE_SIZE (7) — COCRDLIC WS-MAX-SCREEN-LINES.
            CardListResponse result = cardService.listByAccount(ACCT_ID, 0, 0);

            assertThat(result).isSameAs(expected);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(7);
            assertThat(pageable.getValue().getPageNumber()).isZero();
            verify(cardMapper).toListResponse(page);
        }

        @Test
        @DisplayName("Honors an explicit positive page size and page index")
        void honorsExplicitSize() {
            Card c = card(ACCT_ID);
            Page<Card> page = new PageImpl<>(List.of(c));
            when(cardXrefRepository.findByAccountId(ACCT_ID)).thenReturn(List.of(new CardXref()));
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            when(cardRepository.findByAccountId(eq(ACCT_ID), pageable.capture())).thenReturn(page);
            when(cardMapper.toListResponse(page)).thenReturn(response(List.of(dto()), 25));

            cardService.listByAccount(ACCT_ID, 2, 25);

            assertThat(pageable.getValue().getPageSize()).isEqualTo(25);
            assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        }

        @Test
        @DisplayName("Account absent from the cross-reference throws the exact COBOL 404 message")
        void accountNotInXrefThrows404() {
            when(cardXrefRepository.findByAccountId(ACCT_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> cardService.listByAccount(ACCT_ID, 0, 7))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Did not find this account in cards database");
            // Stage 1 short-circuits before any card-master read.
            verify(cardRepository, never()).findByAccountId(any(), any());
        }

        @Test
        @DisplayName("Empty first page for a cross-referenced account throws InvalidCardException (data inconsistency)")
        void emptyFirstPageThrowsInvalidCard() {
            when(cardXrefRepository.findByAccountId(ACCT_ID)).thenReturn(List.of(new CardXref()));
            when(cardRepository.findByAccountId(eq(ACCT_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            assertThatThrownBy(() -> cardService.listByAccount(ACCT_ID, 0, 7))
                    .isInstanceOf(InvalidCardException.class);
            verify(cardMapper, never()).toListResponse(any());
        }

        @Test
        @DisplayName("Paging beyond the last page for a cross-referenced account returns an empty response (no throw)")
        void pagingBeyondEndReturnsEmptyResponse() {
            // COCRDLIC defensive guard is scoped to page 0 only: when the cross-reference confirms
            // the account but a non-first page (page > 0) yields no rows, the service must NOT raise
            // the "NO RECORDS FOUND" InvalidCardException — it returns a well-formed empty page
            // (HTTP 200), mirroring a PF8 scroll past the final card.
            Page<Card> emptyPage = new PageImpl<>(List.of());
            CardListResponse empty = new CardListResponse(List.of(), 0, 0, 1, 7, false, true);
            when(cardXrefRepository.findByAccountId(ACCT_ID)).thenReturn(List.of(new CardXref()));
            when(cardRepository.findByAccountId(eq(ACCT_ID), any(Pageable.class))).thenReturn(emptyPage);
            when(cardMapper.toListResponse(emptyPage)).thenReturn(empty);

            CardListResponse result = cardService.listByAccount(ACCT_ID, 1, 7);

            assertThat(result).isSameAs(empty);
            assertThat(result.content()).isEmpty();
            assertThat(result.currentPage()).isEqualTo(1);
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
        @DisplayName("Blank card number throws the exact COBOL 'not provided' message")
        void blankCardNumberThrows() {
            assertThatThrownBy(() -> cardService.getCard("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Card number not provided");
            verify(cardRepository, never()).findById(any());
        }

        @Test
        @DisplayName("Non-16-digit card number throws the exact COBOL format message")
        void wrongFormatThrows() {
            assertThatThrownBy(() -> cardService.getCard("4111"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Card number if supplied must be a 16 digit number");
            verify(cardRepository, never()).findById(any());
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
    @DisplayName("updateCard (COCRDUPC read-update-rewrite)")
    class UpdateCard {

        @Test
        @DisplayName("Applies a changed field, flushes the managed entity, and returns the mapped DTO")
        void updatesAndReturnsDto() {
            Card existing = card(ACCT_ID);
            CardDto request = CardDto.builder().embossedName("JANE DOE").build();
            CardDto expected = dto();
            when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(existing));
            when(cardRepository.saveAndFlush(existing)).thenReturn(existing);
            when(cardMapper.toDto(existing)).thenReturn(expected);

            CardDto result = cardService.updateCard(CARD_NUM, request);

            assertThat(result).isSameAs(expected);
            assertThat(existing.getEmbossedName()).isEqualTo("JANE DOE");
            verify(cardRepository).saveAndFlush(existing);
        }

        @Test
        @DisplayName("Throws 404 with the exact COBOL message when the card to update is absent")
        void throwsExactMessageWhenNotFound() {
            when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cardService.updateCard(CARD_NUM, dto()))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Did not find this account in cards database");
            verify(cardRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("Blank embossed name throws the exact COBOL message")
        void blankNameThrows() {
            when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(card(ACCT_ID)));

            assertThatThrownBy(() -> cardService.updateCard(CARD_NUM,
                    CardDto.builder().embossedName("   ").build()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Card name not provided");
        }

        @Test
        @DisplayName("Non-alphabetic embossed name throws the exact COBOL message")
        void invalidNameThrows() {
            when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(card(ACCT_ID)));

            assertThatThrownBy(() -> cardService.updateCard(CARD_NUM,
                    CardDto.builder().embossedName("JOHN 3 DOE").build()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Card name can only contain alphabets and spaces");
        }

        @Test
        @DisplayName("Active status other than Y/N throws the exact COBOL message")
        void invalidStatusThrows() {
            when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(card(ACCT_ID)));

            assertThatThrownBy(() -> cardService.updateCard(CARD_NUM,
                    CardDto.builder().activeStatus("X").build()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Card Active Status must be Y or N");
        }

        @Test
        @DisplayName("Expiry month outside 1..12 throws the exact COBOL message")
        void invalidMonthThrows() {
            when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(card(ACCT_ID)));

            assertThatThrownBy(() -> cardService.updateCard(CARD_NUM,
                    CardDto.builder().expirationDate("2025-13-01").build()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Card expiry month must be between 1 and 12");
        }

        @Test
        @DisplayName("Expiry year outside 1950..2099 throws the exact COBOL message")
        void invalidYearThrows() {
            when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(card(ACCT_ID)));

            assertThatThrownBy(() -> cardService.updateCard(CARD_NUM,
                    CardDto.builder().expirationDate("2150-01-01").build()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid card expiry year");
        }

        @Test
        @DisplayName("Identical values to those on file throws the exact COBOL no-change message")
        void noChangeThrows() {
            when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(card(ACCT_ID)));
            CardDto unchanged = CardDto.builder()
                    .embossedName("JOHN DOE").activeStatus("Y").expirationDate("2025-01-01").build();

            assertThatThrownBy(() -> cardService.updateCard(CARD_NUM, unchanged))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("No change detected with respect to values fetched.");
            verify(cardRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("Optimistic-lock conflict is re-thrown with the exact COCRDUPC message (PR-22)")
        void optimisticLockRethrowsExactMessage() {
            Card existing = card(ACCT_ID);
            when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(existing));
            when(cardRepository.saveAndFlush(existing))
                    .thenThrow(new ObjectOptimisticLockingFailureException("conflict", new RuntimeException()));

            assertThatThrownBy(() -> cardService.updateCard(CARD_NUM,
                    CardDto.builder().activeStatus("N").build()))
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class)
                    .hasMessage("Record changed by some one else. Please review");
        }
    }
}
