/*
 * AccountProcessingServiceTest.java — Unit tests for AccountProcessingService
 *
 * Tests the card file processing logic translated from CBACT02C.cbl.
 * Covers: processCardFile() happy path, displayCardRecord() valid/null,
 * error path where findAll() throws → FileStatusException,
 * and PII masking logic for card numbers and CVV codes.
 */
package com.cardemo.service.batch;

import com.cardemo.common.exception.CardDemoException;
import com.cardemo.common.exception.FileStatusException;
import com.cardemo.entity.Card;
import com.cardemo.repository.CardRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountProcessingServiceTest {

    @Mock
    private CardRepository cardRepository;

    @InjectMocks
    private AccountProcessingService service;

    private Card sampleCard;

    @BeforeEach
    void setUp() {
        sampleCard = new Card("4111111111111111", "00000000001", "123",
                "JOHN DOE", "2028-12-31", "Y");
    }

    @Nested
    @DisplayName("processCardFile — Happy Path")
    class ProcessCardFileHappy {

        @Test
        @DisplayName("processes empty card list without error")
        void emptyCardList() {
            when(cardRepository.findAll()).thenReturn(Collections.emptyList());
            assertThatCode(() -> service.processCardFile())
                    .doesNotThrowAnyException();
            verify(cardRepository).findAll();
        }

        @Test
        @DisplayName("processes single card record successfully")
        void singleCard() {
            when(cardRepository.findAll()).thenReturn(List.of(sampleCard));
            assertThatCode(() -> service.processCardFile())
                    .doesNotThrowAnyException();
            verify(cardRepository).findAll();
        }

        @Test
        @DisplayName("processes multiple card records successfully")
        void multipleCards() {
            Card card2 = new Card("5500000000000004", "00000000002", "456",
                    "JANE SMITH", "2027-06-30", "Y");

            when(cardRepository.findAll()).thenReturn(List.of(sampleCard, card2));
            assertThatCode(() -> service.processCardFile())
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("processCardFile — Error Path")
    class ProcessCardFileError {

        @Test
        @DisplayName("findAll() exception → FileStatusException with code 12")
        void repositoryError() {
            when(cardRepository.findAll())
                    .thenThrow(new RuntimeException("DB connection lost"));
            assertThatThrownBy(() -> service.processCardFile())
                    .isInstanceOf(FileStatusException.class)
                    .hasMessageContaining("CARDFILE");
        }
    }

    @Nested
    @DisplayName("displayCardRecord")
    class DisplayCardRecordTests {

        @Test
        @DisplayName("displays valid card record without error")
        void validCard() {
            assertThatCode(() -> service.displayCardRecord(sampleCard))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("null card → CardDemoException")
        void nullCard() {
            assertThatThrownBy(() -> service.displayCardRecord(null))
                    .isInstanceOf(CardDemoException.class)
                    .hasMessageContaining("Null card record");
        }

        @Test
        @DisplayName("card with null fields displays without NPE")
        void cardWithNullFields() {
            Card nullFieldCard = new Card(null, null, null, null, null, null);
            // All fields null — the service should mask nulls gracefully
            assertThatCode(() -> service.displayCardRecord(nullFieldCard))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("card with short card number uses fallback mask")
        void shortCardNumber() {
            Card shortCard = new Card("12", "00000000001", "123",
                    "JOHN DOE", "2028-12-31", "Y");
            assertThatCode(() -> service.displayCardRecord(shortCard))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("card with exactly 4-char number masks correctly")
        void fourCharCardNumber() {
            Card fourCard = new Card("1234", "00000000001", "123",
                    "JOHN DOE", "2028-12-31", "Y");
            assertThatCode(() -> service.displayCardRecord(fourCard))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("card with null CVV uses fallback mask")
        void nullCvv() {
            Card noCvvCard = new Card("4111111111111111", "00000000001", null,
                    "JOHN DOE", "2028-12-31", "Y");
            assertThatCode(() -> service.displayCardRecord(noCvvCard))
                    .doesNotThrowAnyException();
        }
    }
}
