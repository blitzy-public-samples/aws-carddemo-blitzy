package com.carddemo.card.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.card.entity.CardEntity;
import com.carddemo.card.entity.OutboxEventEntity;
import com.carddemo.card.repository.OutboxEventRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that an embossed cardholder name never enters the outbox payload.
 */
@DisplayName("Card outbox data minimization")
class OutboxWriterDataMinimizationTest {

    @Test
    @DisplayName("a card update stores no embossed cardholder name")
    void aCardUpdateStoresNoEmbossedCardholderName() {
        OutboxEventRepository rows = mock(OutboxEventRepository.class);
        when(rows.save(any(OutboxEventEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        String embossedName = "SENSITIVE CARDHOLDER NAME";
        CardEntity card = new CardEntity("0500024453765740", "00000000050", "747",
                embossedName, LocalDate.of(2028, 3, 9), "Y");

        OutboxEventEntity written = new OutboxWriter(rows).writeCardUpdated(card);

        assertThat(written.getPayload()).doesNotContain(embossedName);
        assertThat(written.getPayload()).doesNotContain("\"embossedName\"");
        assertThat(written.getPayload()).contains("\"maskedCardNumber\":\"************5740\"");
    }
}
