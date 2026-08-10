package com.carddemo.events.correlation;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.MDC;

/**
 * The two identifiers that join one authorization to every record any service writes about it, and
 * the names each one travels under.
 *
 * <p>No COBOL program and no copybook defines either identifier. Both are ADDITIVE. The source
 * carried one process per unit of work, so the job name in the job log was the whole of its
 * correlation: {@code app/cbl/CBTRN02C.cbl:L227-L230} prints two counts and
 * {@code app/cbl/CBTRN02C.cbl:L714-L727} formats a file status, and nothing needed to join a record
 * written by one program to a record written by another. Six services and five topics need that
 * join, so the platform supplies it.
 *
 * <p>Neither identifier reaches an event payload. Both travel as Kafka record headers, so no
 * schema document under {@code card-platform/libs/event-contracts/src/main/resources/schemas} names
 * either one and the five-field envelope of {@link com.carddemo.events.EventEnvelope} stays as
 * Agent Action Plan section 0.3.1 declares it.
 *
 * <p>The two identifiers answer different questions.
 * <ul>
 * <li>The <strong>correlation</strong> identifier names the root unit of work. One authorization
 * call is given one, and every derived event and every dead-letter record of that call carries the
 * same value, unchanged. It answers "which call was this?"</li>
 * <li>The <strong>causation</strong> identifier names the immediate parent, which is the
 * {@code eventId} of the event whose handling produced this one. It answers "what produced
 * this?"</li>
 * </ul>
 *
 * <p>{@code eventId} keeps its own meaning and stays unique per event, because each consumer claims
 * a {@code processed_event} row keyed on it. A correlation identifier repeats across a fan-out by
 * design and would make a duplicate check useless.
 *
 * <p>Every value this class accepts is one Universally Unique Identifier (UUID) in its
 * thirty-six-character form and nothing else. {@link #parse(String)} refuses anything that does not
 * render back to the text it was read from, so a value a caller supplies cannot widen a log field,
 * inject a line break, or grow without bound.
 *
 * <p>The current values live in the Mapped Diagnostic Context (MDC) of the handling thread, which
 * {@link CorrelationScope} opens and closes. Structured output carries every MDC entry as a
 * top-level member, so a reader queries {@code correlationId} as a field rather than searching
 * message text. {@link #currentCorrelationId()} and {@link #currentCausationId()} let a writer far
 * from the listener read the ambient values without every method between them carrying a parameter.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
public final class EventCorrelation {

    /** Kafka record header carrying the root correlation identifier. */
    public static final String CORRELATION_ID_HEADER = "carddemo-correlation-id";

    /** Kafka record header carrying the identifier of the event that produced this one. */
    public static final String CAUSATION_ID_HEADER = "carddemo-causation-id";

    /**
     * Request header a caller may supply to name the correlation identifier of one call.
     *
     * <p>A caller that supplies a well-formed value keeps it, so a correlation started outside this
     * platform reaches every log record inside it. A caller that supplies nothing, or supplies
     * something that is not one UUID, is given a fresh value.
     */
    public static final String CORRELATION_ID_REQUEST_HEADER = "X-Correlation-Id";

    /** Structured log member and MDC key of the root correlation identifier. */
    public static final String CORRELATION_ID_FIELD = "correlationId";

    /** Structured log member and MDC key of the identifier of the causing event. */
    public static final String CAUSATION_ID_FIELD = "causationId";

    /** Structured log member and MDC key of the identifier of the event being handled. */
    public static final String EVENT_ID_FIELD = "eventId";

    /** Structured log member and MDC key of the type of the event being handled. */
    public static final String EVENT_TYPE_FIELD = "eventType";

    /**
     * Structured log member and MDC key of the transaction one record concerns.
     *
     * <p>From {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5}. A transaction
     * identifier names no cardholder, no account and no card, so it discloses nothing a masked
     * event does not already carry.
     */
    public static final String TRANSACTION_ID_FIELD = "transactionId";

    /** The one form either identifier takes: a UUID in its thirty-six-character rendering. */
    private static final Pattern UUID_FORM =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                    + "-[0-9a-fA-F]{12}$");

    private EventCorrelation() {
        throw new AssertionError("EventCorrelation holds static members only");
    }

    /**
     * Mints the correlation identifier of one new unit of work.
     *
     * @return a fresh random identifier, never {@code null}
     */
    public static UUID newCorrelationId() {
        return UUID.randomUUID();
    }

    /**
     * Reads one identifier out of text, refusing anything that is not one UUID.
     *
     * <p>The check is the rendering rather than the parse. {@link UUID#fromString(String)} accepts
     * short groups and renders them back padded, so a parse alone would let two different texts
     * name one identifier. Requiring the rendered form to equal the supplied text, ignoring case,
     * admits exactly one text per identifier.
     *
     * @param candidate the text to read, possibly {@code null} or blank
     * @return the identifier, or an empty value when the text is absent or is not one UUID
     */
    public static Optional<UUID> parse(String candidate) {
        if (candidate == null || !UUID_FORM.matcher(candidate).matches()) {
            return Optional.empty();
        }
        UUID parsed;
        try {
            parsed = UUID.fromString(candidate);
        } catch (IllegalArgumentException notOneIdentifier) {
            return Optional.empty();
        }
        return parsed.toString().equalsIgnoreCase(candidate) ? Optional.of(parsed)
                : Optional.empty();
    }

    /**
     * Reads one identifier out of a header value of any form a header mapper produces.
     *
     * <p>A Kafka header holds bytes. Which Java type reaches a listener parameter depends on the
     * header mapper the container is configured with: one mapper hands over the bytes and another
     * decodes them to text first. Both forms are read here, so a listener binds the value as
     * {@link Object} and no listener depends on the mapper in use.
     *
     * @param headerValue the bound header value: bytes, text, or {@code null}
     * @return the identifier, or an empty value when the header is absent or holds anything but one
     *         UUID
     */
    public static Optional<UUID> readHeaderValue(Object headerValue) {
        if (headerValue == null) {
            return Optional.empty();
        }
        if (headerValue instanceof byte[] bytes) {
            return parse(new String(bytes, StandardCharsets.UTF_8));
        }
        if (headerValue instanceof CharSequence text) {
            return parse(text.toString());
        }
        return Optional.empty();
    }

    /**
     * Reads one identifier out of the headers of a delivery.
     *
     * @param headers the delivered headers, possibly {@code null}
     * @param name    the header name, one of {@link #CORRELATION_ID_HEADER} and
     *                {@link #CAUSATION_ID_HEADER}
     * @return the identifier, or an empty value when the header is absent or holds anything but one
     *         UUID
     */
    public static Optional<UUID> read(Headers headers, String name) {
        Objects.requireNonNull(name, "name must be present");
        if (headers == null) {
            return Optional.empty();
        }
        Header header = headers.lastHeader(name);
        return header == null ? Optional.empty() : readHeaderValue(header.value());
    }

    /**
     * Builds the headers one publish carries, holding whichever of the two identifiers is known.
     *
     * <p>An absent identifier contributes no header rather than a header holding nothing, so a
     * consumer reading a header that is present always reads a value.
     *
     * @param correlationId the root correlation identifier, or {@code null} when none is known
     * @param causationId   the identifier of the causing event, or {@code null} when this event
     *                      begins a chain
     * @return the headers to attach, in correlation-then-causation order, possibly empty and never
     *         {@code null}
     */
    public static List<Header> headersFor(UUID correlationId, UUID causationId) {
        if (correlationId == null && causationId == null) {
            return List.of();
        }
        if (causationId == null) {
            return List.of(header(CORRELATION_ID_HEADER, correlationId));
        }
        if (correlationId == null) {
            return List.of(header(CAUSATION_ID_HEADER, causationId));
        }
        return List.of(header(CORRELATION_ID_HEADER, correlationId),
                header(CAUSATION_ID_HEADER, causationId));
    }

    /**
     * Renders one identifier as one header.
     *
     * @param name  the header name
     * @param value the identifier the header carries
     * @return the header, carrying the thirty-six-character rendering in Unicode Transformation
     *         Format 8-bit (UTF-8)
     */
    public static Header header(String name, UUID value) {
        Objects.requireNonNull(name, "name must be present");
        Objects.requireNonNull(value, "value must be present");
        return new RecordHeader(name, value.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns the correlation identifier the handling thread is working under.
     *
     * <p>An outbox writer reads this rather than taking a parameter, so no domain method between a
     * listener and a writer carries an identifier it does not otherwise use.
     *
     * @return the ambient correlation identifier, or an empty value outside a
     *         {@link CorrelationScope}
     */
    public static Optional<UUID> currentCorrelationId() {
        return parse(MDC.get(CORRELATION_ID_FIELD));
    }

    /**
     * Returns the identifier of the event whose handling the thread is inside.
     *
     * <p>An outbox writer records this as the causation of the row it writes: the event being
     * handled now is the parent of anything written while handling it.
     *
     * @return the ambient event identifier, or an empty value on a thread that is not handling one
     */
    public static Optional<UUID> currentEventId() {
        return parse(MDC.get(EVENT_ID_FIELD));
    }

    /**
     * Returns the causation identifier the record the thread is working on declares.
     *
     * <p>A publisher attaches this as {@link #CAUSATION_ID_HEADER}. It is read from its own field
     * rather than from {@link #currentEventId()}, because the two differ exactly where it matters: a
     * relay publishing row {@code E2} works under {@code eventId = E2} and has to send the parent
     * {@code E1} the row recorded.
     *
     * @return the ambient causation identifier, or an empty value where the record begins a chain
     */
    public static Optional<UUID> currentCausationId() {
        return parse(MDC.get(CAUSATION_ID_FIELD));
    }
}
