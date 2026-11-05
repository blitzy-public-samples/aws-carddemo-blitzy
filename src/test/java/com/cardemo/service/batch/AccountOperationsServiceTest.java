/*
 * AccountOperationsServiceTest.java — Unit tests for AccountOperationsService
 *
 * Tests the cross-reference file processing logic translated from CBACT03C.cbl.
 * Covers: processAccountOperations() happy path, displayXrefRecord(),
 * error path where findAll() throws, and protected repository accessors.
 */
package com.cardemo.service.batch;

import com.cardemo.common.exception.CardDemoException;
import com.cardemo.entity.CardXref;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardRepository;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.CategoryBalanceRepository;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountOperationsServiceTest {

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CardRepository cardRepository;

    @Mock
    private CategoryBalanceRepository categoryBalanceRepository;

    @InjectMocks
    private AccountOperationsService service;

    private CardXref sampleXref;

    @BeforeEach
    void setUp() {
        sampleXref = new CardXref("4111111111111111", "000000001", "00000000001");
    }

    @Nested
    @DisplayName("processAccountOperations — Happy Path")
    class HappyPath {

        @Test
        @DisplayName("processes empty xref list without error")
        void emptyXrefList() {
            when(cardXrefRepository.findAll()).thenReturn(Collections.emptyList());
            assertThatCode(() -> service.processAccountOperations())
                    .doesNotThrowAnyException();
            verify(cardXrefRepository).findAll();
        }

        @Test
        @DisplayName("processes single xref record successfully")
        void singleXref() {
            when(cardXrefRepository.findAll()).thenReturn(List.of(sampleXref));
            assertThatCode(() -> service.processAccountOperations())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("processes multiple xref records successfully")
        void multipleXrefs() {
            CardXref xref2 = new CardXref("5500000000000004", "000000002", "00000000002");
            when(cardXrefRepository.findAll()).thenReturn(List.of(sampleXref, xref2));
            assertThatCode(() -> service.processAccountOperations())
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("processAccountOperations — Error Path")
    class ErrorPath {

        @Test
        @DisplayName("findAll() exception → CardDemoException wrapping error")
        void repositoryError() {
            when(cardXrefRepository.findAll())
                    .thenThrow(new RuntimeException("DB error"));
            assertThatThrownBy(() -> service.processAccountOperations())
                    .isInstanceOf(CardDemoException.class)
                    .hasMessageContaining("ABENDING PROGRAM");
        }

        @Test
        @DisplayName("CardDemoException is re-thrown without wrapping")
        void cardDemoExceptionRethrown() {
            when(cardXrefRepository.findAll())
                    .thenThrow(new CardDemoException("Test abend"));
            assertThatThrownBy(() -> service.processAccountOperations())
                    .isInstanceOf(CardDemoException.class)
                    .hasMessageContaining("Test abend");
        }
    }

    @Nested
    @DisplayName("displayXrefRecord")
    class DisplayXrefTests {

        @Test
        @DisplayName("displays valid xref record without error")
        void validXref() {
            assertThatCode(() -> service.displayXrefRecord(sampleXref))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("displays xref with null fields without NPE")
        void xrefWithNullFields() {
            CardXref nullXref = new CardXref(null, null, null);
            assertThatCode(() -> service.displayXrefRecord(nullXref))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Protected Accessors")
    class Accessors {

        @Test
        @DisplayName("getAccountRepository returns injected instance")
        void accountRepoAccessor() {
            assertThat(service.getAccountRepository()).isSameAs(accountRepository);
        }

        @Test
        @DisplayName("getCardRepository returns injected instance")
        void cardRepoAccessor() {
            assertThat(service.getCardRepository()).isSameAs(cardRepository);
        }

        @Test
        @DisplayName("getCategoryBalanceRepository returns injected instance")
        void categoryBalRepoAccessor() {
            assertThat(service.getCategoryBalanceRepository())
                    .isSameAs(categoryBalanceRepository);
        }
    }
}
