package com.aws.carddemo.config.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import ch.qos.logback.core.status.Status;

/**
 * Unit tests for {@link MaskingLogbackEncoder} &mdash; the delegating Logback encoder that redacts
 * card PANs from rendered log bytes (QA finding F-1).
 *
 * <p>A lightweight {@link RecordingEncoder} stands in for the real delegate (Spring Boot's ECS
 * {@code StructuredLogEncoder} or a {@code PatternLayoutEncoder}) so the wrapper's masking, lifecycle
 * management, byte-identity fast path and UTF-8 safety can be verified with no Spring context and no
 * real logging event.</p>
 */
class MaskingLogbackEncoderTest {

    private static final String PAN = "4111222233334444";

    /** A representative single-line ECS-style payload carrying the PAN in a raw-record excerpt. */
    private static final String ECS_LINE_WITH_PAN =
            "{\"@timestamp\":\"2026-07-20T17:26:40.216074Z\",\"log.level\":\"ERROR\","
            + "\"message\":\"Parsing error at line: 1, input=[..72112     " + PAN + "2022-06-10]\","
            + "\"process.pid\":4242}";

    @Test
    @DisplayName("encode() masks the PAN in the delegate's output and produces no full PAN")
    void encodeMasksDelegateOutput() {
        MaskingLogbackEncoder encoder = startedEncoder(new RecordingEncoder(ECS_LINE_WITH_PAN));

        String out = new String(encoder.encode(anyEvent()), StandardCharsets.ISO_8859_1);

        assertThat(out).doesNotContain(PAN);
        assertThat(out).contains("411122");   // BIN retained
        assertThat(out).contains("2022-06-10"); // surrounding text intact
        assertThat(out).contains("\"process.pid\":4242"); // short numbers untouched
    }

    @Test
    @DisplayName("start() sets the delegate's context and starts it; stop() stops it")
    void managesDelegateLifecycle() {
        LoggerContext context = new LoggerContext();
        RecordingEncoder delegate = new RecordingEncoder("no digits here");
        MaskingLogbackEncoder encoder = new MaskingLogbackEncoder();
        encoder.setContext(context);
        encoder.setDelegate(delegate);

        encoder.start();
        assertThat(encoder.isStarted()).isTrue();
        assertThat(delegate.isStarted()).isTrue();
        assertThat(delegate.getContext()).isSameAs(context);
        assertThat(delegate.startCount).isEqualTo(1);

        encoder.stop();
        assertThat(encoder.isStarted()).isFalse();
        assertThat(delegate.isStarted()).isFalse();
        assertThat(delegate.stopCount).isEqualTo(1);
    }

    @Test
    @DisplayName("start() does not re-start a delegate that Logback already started")
    void doesNotDoubleStartDelegate() {
        RecordingEncoder delegate = new RecordingEncoder("x");
        delegate.setContext(new LoggerContext());
        delegate.start(); // simulate Logback having started the nested delegate

        MaskingLogbackEncoder encoder = new MaskingLogbackEncoder();
        encoder.setContext(new LoggerContext());
        encoder.setDelegate(delegate);
        encoder.start();

        assertThat(delegate.startCount).isEqualTo(1);
    }

    @Test
    @DisplayName("When nothing needs masking the delegate's exact byte array is returned (no re-encode)")
    void returnsIdenticalBytesWhenNothingMasked() {
        RecordingEncoder delegate = new RecordingEncoder("clean line with short id 12345");
        MaskingLogbackEncoder encoder = startedEncoder(delegate);

        byte[] result = encoder.encode(anyEvent());

        assertThat(result).isSameAs(delegate.lastReturned);
    }

    @Test
    @DisplayName("Header and footer bytes are masked too")
    void masksHeaderAndFooter() {
        RecordingEncoder delegate = new RecordingEncoder("body");
        delegate.header = ("HDR " + PAN).getBytes(StandardCharsets.ISO_8859_1);
        delegate.footer = ("FTR " + PAN).getBytes(StandardCharsets.ISO_8859_1);
        MaskingLogbackEncoder encoder = startedEncoder(delegate);

        assertThat(new String(encoder.headerBytes(), StandardCharsets.ISO_8859_1)).doesNotContain(PAN);
        assertThat(new String(encoder.footerBytes(), StandardCharsets.ISO_8859_1)).doesNotContain(PAN);
    }

    @Test
    @DisplayName("UTF-8 multi-byte characters around a PAN survive the ISO-8859-1 mask round-trip")
    void preservesUtf8MultibyteBytes() {
        // Delegate emits genuine UTF-8 bytes (euro sign, cat ideograph) around the PAN.
        byte[] utf8 = ("\u20ac " + PAN + " \u732b").getBytes(StandardCharsets.UTF_8);
        RecordingEncoder delegate = new RecordingEncoder(utf8);
        MaskingLogbackEncoder encoder = startedEncoder(delegate);

        byte[] result = encoder.encode(anyEvent());

        // Re-decoding as UTF-8 must yield the original multi-byte characters with only the PAN masked.
        String decoded = new String(result, StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo("\u20ac 411122******4444 \u732b");
    }

    @Test
    @DisplayName("A missing <delegate> is reported as a Logback status error and encode() is inert")
    void missingDelegateIsReportedAndInert() {
        LoggerContext context = new LoggerContext();
        MaskingLogbackEncoder encoder = new MaskingLogbackEncoder();
        encoder.setContext(context);

        encoder.start();

        assertThat(encoder.isStarted()).isFalse();
        assertThat(encoder.encode(anyEvent())).isEmpty();
        assertThat(context.getStatusManager().getCopyOfStatusList())
                .anyMatch(s -> s.getLevel() == Status.ERROR
                        && s.getMessage().contains("MaskingLogbackEncoder requires a nested <delegate>"));
    }

    // --- helpers -----------------------------------------------------------------------------

    private static MaskingLogbackEncoder startedEncoder(RecordingEncoder delegate) {
        MaskingLogbackEncoder encoder = new MaskingLogbackEncoder();
        encoder.setContext(new LoggerContext());
        encoder.setDelegate(delegate);
        encoder.start();
        return encoder;
    }

    private static ILoggingEvent anyEvent() {
        // RecordingEncoder ignores the event, so a null event is sufficient for these unit tests.
        return null;
    }

    /**
     * Minimal {@link ch.qos.logback.core.encoder.Encoder} test double that returns a fixed payload
     * and records lifecycle calls. Extends {@link EncoderBase} to inherit the {@code ContextAware} /
     * {@code LifeCycle} plumbing so only the three encode methods need to be supplied.
     */
    private static final class RecordingEncoder extends EncoderBase<ILoggingEvent> {
        private final byte[] payload;
        private byte[] header = new byte[0];
        private byte[] footer = new byte[0];
        private byte[] lastReturned;
        private int startCount;
        private int stopCount;

        RecordingEncoder(String payload) {
            this(payload.getBytes(StandardCharsets.ISO_8859_1));
        }

        RecordingEncoder(byte[] payload) {
            this.payload = payload;
        }

        @Override
        public void start() {
            startCount++;
            super.start();
        }

        @Override
        public void stop() {
            stopCount++;
            super.stop();
        }

        @Override
        public byte[] headerBytes() {
            lastReturned = header;
            return header;
        }

        @Override
        public byte[] encode(ILoggingEvent event) {
            lastReturned = payload;
            return payload;
        }

        @Override
        public byte[] footerBytes() {
            lastReturned = footer;
            return footer;
        }
    }
}
