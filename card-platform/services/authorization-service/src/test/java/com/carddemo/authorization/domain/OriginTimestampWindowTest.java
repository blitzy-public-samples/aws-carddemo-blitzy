package com.carddemo.authorization.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Measures the capture-moment window: what it accepts, what it refuses, and what it says.
 *
 * <p>ADDITIVE. No source program tests the capture moment against a clock, so no COBOL (Common
 * Business Oriented Language) paragraph is reproduced here.
 * {@code app/cbl/CBTRN02C.cbl:L414-L420} compares {@code DALYTRAN-ORIG-TS} against
 * {@code ACCT-EXPIRAION-DATE} and nothing else, and the value it compares comes from a file the
 * nightly job owns. A synchronous caller supplies the value, which is the difference this class
 * exists for: a caller naming a moment far enough in the past would authorize against an account
 * that expired years ago and reject reason {@code 0103} would still approve.
 */
@DisplayName("OriginTimestampWindow, the bound on the capture moment a caller may supply")
class OriginTimestampWindowTest {

    /** The moment the service clock reports in every test below. */
    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");

    /** A window of one day back and five minutes forward, the shipped values. */
    private final OriginTimestampWindow window = new OriginTimestampWindow(1440, 5);

    @Test
    @DisplayName("a moment inside the window is accepted")
    void aMomentInsideTheWindowIsAccepted() {
        assertDoesNotThrow(() -> window.require("2026-08-04 11:59:00.000000", NOW),
                "a capture moment one minute old was refused");
        assertDoesNotThrow(() -> window.require("2026-08-03 12:00:00.000000", NOW),
                "a capture moment exactly one day old sits on the boundary and is accepted");
    }

    @Test
    @DisplayName("a backdated moment is refused, which is what stops the expiry rule being bypassed")
    void aBackdatedMomentIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> window.require("2020-01-01 00:00:00.000000", NOW),
                "a moment six years old reached reject reason 0103, which compares it against the "
                        + "account expiry and would approve an account that expired in 2021");

        assertEquals(OriginTimestampWindow.TOO_OLD_MESSAGE, refused.getMessage(),
                "the refusal names the bound and carries no value");
    }

    @Test
    @DisplayName("a moment past the forward bound is refused")
    void aMomentPastTheForwardBoundIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> window.require("2026-08-04 12:06:00.000000", NOW),
                "a moment six minutes ahead of the clock was accepted");

        assertEquals(OriginTimestampWindow.TOO_NEW_MESSAGE, refused.getMessage(),
                "the refusal names the bound and carries no value");
    }

    @Test
    @DisplayName("a value the formatter cannot read is refused rather than assumed")
    void anUnreadableValueIsRefused() {
        assertEquals(OriginTimestampWindow.UNREADABLE_MESSAGE,
                assertThrows(IllegalArgumentException.class,
                        () -> window.require("2026-08-04T12:00:00Z", NOW)).getMessage(),
                "a value in another shape was read anyway");
        assertEquals(OriginTimestampWindow.UNREADABLE_MESSAGE,
                assertThrows(IllegalArgumentException.class,
                        () -> window.require(null, NOW)).getMessage(),
                "an absent value was read anyway");
    }

    @Test
    @DisplayName("a wide window holds the 2022 records of the daily-transaction fixture")
    void aWideWindowHoldsTheFixtureRecords() {
        OriginTimestampWindow wide =
                new OriginTimestampWindow(Duration.ofDays(36500).toMinutes(), 5);

        assertDoesNotThrow(() -> wide.require("2022-06-10 19:27:53.412000", NOW),
                "an equivalence run replays the 2022 records of app/data/ASCII/dailytran.txt, and "
                        + "widening the window is a configuration decision rather than a code path");
    }

    @Test
    @DisplayName("a bound below its floor stops start-up and names its property")
    void aBoundBelowItsFloorStopsStartUp() {
        assertEquals("carddemo.authorization.origin-timestamp.max-age-minutes counts up from one",
                assertThrows(IllegalStateException.class,
                        () -> new OriginTimestampWindow(0, 5)).getMessage(),
                "a window with no width was built");
        assertEquals("carddemo.authorization.origin-timestamp.max-future-minutes counts up from "
                        + "zero",
                assertThrows(IllegalStateException.class,
                        () -> new OriginTimestampWindow(1440, -1)).getMessage(),
                "a negative forward bound was accepted");
    }
}
