package com.carddemo.events.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Proves the correlation identifiers travel in headers and are read back unchanged. */
@DisplayName("The two correlation identifiers, and the header names they travel under")
class EventCorrelationTest {

    private static final UUID CORRELATION = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static final UUID CAUSATION = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

    @Nested
    @DisplayName("Reading one identifier out of text")
    class Parsing {

        @Test
        @DisplayName("a rendered identifier reads back as the identifier it renders")
        void aRenderedIdentifierReadsBack() {
            assertThat(EventCorrelation.parse(CORRELATION.toString())).contains(CORRELATION);
        }

        @Test
        @DisplayName("upper-case digits name the same identifier")
        void upperCaseDigitsNameTheSameIdentifier() {
            assertThat(EventCorrelation.parse(CORRELATION.toString().toUpperCase(
                    java.util.Locale.ROOT))).contains(CORRELATION);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {
            "",
            "   ",
            "not-an-identifier",
            "1-2-3-4-5",
            "11111111-2222-3333-4444-55555555555",
            "11111111-2222-3333-4444-5555555555555",
            "11111111-2222-3333-4444-555555555555 ",
            "11111111-2222-3333-4444-555555555555\nlevel=ERROR",
            "11111111222233334444555555555555"
        })
        @DisplayName("anything that is not one rendered identifier is refused")
        void anythingElseIsRefused(String candidate) {
            assertThat(EventCorrelation.parse(candidate)).isEmpty();
        }

        @Test
        @DisplayName("a short group is refused rather than padded")
        void aShortGroupIsRefused() {
            // UUID.fromString accepts this and renders it back padded, so two texts would name one
            // identifier. Requiring the rendering to equal the text admits exactly one.
            assertThat(EventCorrelation.parse("1-2-3-4-5")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Reading one identifier out of a bound header value")
    class HeaderValues {

        @Test
        @DisplayName("bytes are decoded as Unicode Transformation Format 8-bit text")
        void bytesAreDecoded() {
            Object bound = CORRELATION.toString().getBytes(StandardCharsets.UTF_8);
            assertThat(EventCorrelation.readHeaderValue(bound)).contains(CORRELATION);
        }

        @Test
        @DisplayName("text is read as it stands")
        void textIsReadAsItStands() {
            assertThat(EventCorrelation.readHeaderValue(CORRELATION.toString()))
                    .contains(CORRELATION);
        }

        @Test
        @DisplayName("an absent header reads as no identifier")
        void anAbsentHeaderReadsAsNoIdentifier() {
            assertThat(EventCorrelation.readHeaderValue(null)).isEmpty();
        }

        @Test
        @DisplayName("a value of any other type reads as no identifier")
        void anyOtherTypeReadsAsNoIdentifier() {
            assertThat(EventCorrelation.readHeaderValue(42L)).isEmpty();
        }

        @Test
        @DisplayName("bytes that are not one identifier read as no identifier")
        void bytesThatAreNotOneIdentifierAreRefused() {
            Object bound = "SECRET".getBytes(StandardCharsets.UTF_8);
            assertThat(EventCorrelation.readHeaderValue(bound)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Writing and reading the headers of one delivery")
    class DeliveryHeaders {

        @Test
        @DisplayName("both identifiers travel, and both read back")
        void bothIdentifiersTravel() {
            Headers headers = new RecordHeaders();
            EventCorrelation.headersFor(CORRELATION, CAUSATION).forEach(headers::add);

            assertThat(EventCorrelation.read(headers, EventCorrelation.CORRELATION_ID_HEADER))
                    .contains(CORRELATION);
            assertThat(EventCorrelation.read(headers, EventCorrelation.CAUSATION_ID_HEADER))
                    .contains(CAUSATION);
        }

        @Test
        @DisplayName("an event that begins a chain carries the correlation header alone")
        void anEventThatBeginsAChainCarriesOneHeader() {
            List<Header> headers = EventCorrelation.headersFor(CORRELATION, null);

            assertThat(headers).singleElement()
                    .extracting(Header::key)
                    .isEqualTo(EventCorrelation.CORRELATION_ID_HEADER);
        }

        @Test
        @DisplayName("a causation with no correlation carries the causation header alone")
        void aCausationWithNoCorrelationCarriesOneHeader() {
            List<Header> headers = EventCorrelation.headersFor(null, CAUSATION);

            assertThat(headers).singleElement()
                    .extracting(Header::key)
                    .isEqualTo(EventCorrelation.CAUSATION_ID_HEADER);
        }

        @Test
        @DisplayName("neither identifier known attaches no header at all")
        void neitherIdentifierAttachesNoHeader() {
            assertThat(EventCorrelation.headersFor(null, null)).isEmpty();
        }

        @Test
        @DisplayName("absent headers read as no identifier")
        void absentHeadersReadAsNoIdentifier() {
            assertThat(EventCorrelation.read(new RecordHeaders(),
                    EventCorrelation.CORRELATION_ID_HEADER)).isEmpty();
            assertThat(EventCorrelation.read(null, EventCorrelation.CORRELATION_ID_HEADER))
                    .isEmpty();
        }

        @Test
        @DisplayName("a header holding anything but one identifier reads as none")
        void aMalformedHeaderReadsAsNone() {
            Headers headers = new RecordHeaders();
            headers.add(EventCorrelation.CORRELATION_ID_HEADER,
                    "../../etc/passwd".getBytes(StandardCharsets.UTF_8));

            assertThat(EventCorrelation.read(headers, EventCorrelation.CORRELATION_ID_HEADER))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("The values the handling thread is working under")
    class AmbientValues {

        @Test
        @DisplayName("a scope makes every value readable, and closing it takes them away")
        void aScopeMakesEveryValueReadable() {
            UUID handled = UUID.randomUUID();
            try (CorrelationScope scope = CorrelationScope.open()
                    .withCorrelation(CORRELATION)
                    .withCausation(CAUSATION)
                    .withEvent(handled, "TransactionAuthorized")) {
                assertThat(EventCorrelation.currentCorrelationId()).contains(CORRELATION);
                assertThat(EventCorrelation.currentEventId()).contains(handled);
                assertThat(EventCorrelation.currentCausationId()).contains(CAUSATION);
            }
            assertThat(EventCorrelation.currentCorrelationId()).isEmpty();
            assertThat(EventCorrelation.currentEventId()).isEmpty();
            assertThat(EventCorrelation.currentCausationId()).isEmpty();
        }

        @Test
        @DisplayName("the handled event and the declared cause are read from different fields")
        void theHandledEventAndTheDeclaredCauseAreDifferentFields() {
            // A relay publishing row E2 works under eventId = E2 and has to send the parent E1 the
            // row recorded, so reading one field for both would send the wrong parent.
            UUID published = UUID.randomUUID();
            try (CorrelationScope scope = CorrelationScope.open()
                    .withCausation(CAUSATION)
                    .withEvent(published, "TransactionPosted")) {
                assertThat(EventCorrelation.currentEventId()).contains(published);
                assertThat(EventCorrelation.currentCausationId()).contains(CAUSATION);
            }
        }

        @Test
        @DisplayName("outside a scope no value is readable")
        void outsideAScopeNoValueIsReadable() {
            assertThat(EventCorrelation.currentCorrelationId()).isEqualTo(Optional.empty());
            assertThat(EventCorrelation.currentEventId()).isEqualTo(Optional.empty());
            assertThat(EventCorrelation.currentCausationId()).isEqualTo(Optional.empty());
        }
    }

    @Test
    @DisplayName("a fresh correlation identifier differs from the last")
    void aFreshIdentifierDiffersFromTheLast() {
        assertThat(EventCorrelation.newCorrelationId())
                .isNotEqualTo(EventCorrelation.newCorrelationId());
    }

    @Test
    @DisplayName("the class holds static members only")
    void theClassHoldsStaticMembersOnly() throws Exception {
        var constructor = EventCorrelation.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThrows(java.lang.reflect.InvocationTargetException.class, constructor::newInstance);
    }
}
