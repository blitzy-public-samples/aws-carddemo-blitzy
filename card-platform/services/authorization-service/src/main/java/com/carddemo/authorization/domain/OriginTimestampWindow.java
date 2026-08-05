package com.carddemo.authorization.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Holds the span of capture moments this service accepts, and refuses one outside it.
 *
 * <p>ADDITIVE. No source program tests the capture moment against a clock.
 * {@code app/cbl/CBTRN02C.cbl:L414-L420} compares {@code DALYTRAN-ORIG-TS} against
 * {@code ACCT-EXPIRAION-DATE} and nothing else, and the value it compares arrives from a file the
 * nightly job owns rather than from a caller. A synchronous caller supplies the value, and reject
 * reason {@code 0103} approves whenever the account expiry is at or after the first ten characters
 * of it, so a caller who names a moment far enough in the past authorizes against an account that
 * expired years ago. This class is the bound that closes that path.
 *
 * <p>Two settings shape the span, both in {@code src/main/resources/application.yml}:
 * {@code carddemo.authorization.origin-timestamp.max-age-minutes} bounds how far behind the clock a
 * capture moment may sit, and {@code carddemo.authorization.origin-timestamp.max-future-minutes}
 * bounds how far ahead. An equivalence run that replays the 2022 records of
 * {@code app/data/ASCII/dailytran.txt} widens the first setting, which is a configuration decision
 * and not a code path.
 *
 * <p>The refusal is an {@link IllegalArgumentException}, so {@code api/GlobalExceptionHandler}
 * answers {@code 422} and the body carries one fixed text. Reject reason {@code 0103} keeps its
 * meaning: it reports an expired account, not a caller who lied about the clock.
 *
 * <p>The value is read as a moment in Coordinated Universal Time (UTC), which is the offset every
 * timestamp on this platform carries. Reject reason {@code 0103} still compares the first ten
 * characters as text, per the transformation rule that preserves comparison semantics, and this
 * class parses a copy rather than replacing that comparison.
 *
 * <p>An instance holds no mutable state, so request threads may share one.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 */
@Component
public class OriginTimestampWindow {

    /**
     * Reads {@code DALYTRAN-ORIG-TS PIC X(26)} at {@code app/cpy/CVTRA06Y.cpy:L16}: a date, a space,
     * a time to the second, a point, then six fractional digits.
     */
    private static final DateTimeFormatter ORIGIN_TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS");

    /** Refusal text for a capture moment behind the accepted span. Carries no value. */
    static final String TOO_OLD_MESSAGE =
            "originTimestamp sits further behind the service clock than "
                    + "carddemo.authorization.origin-timestamp.max-age-minutes allows";

    /** Refusal text for a capture moment ahead of the accepted span. Carries no value. */
    static final String TOO_NEW_MESSAGE =
            "originTimestamp sits further ahead of the service clock than "
                    + "carddemo.authorization.origin-timestamp.max-future-minutes allows";

    /** Refusal text for a value the formatter cannot read. Carries no value. */
    static final String UNREADABLE_MESSAGE =
            "originTimestamp does not read as a moment, so no window can hold it";

    /** How far behind the clock a capture moment may sit. */
    private final Duration maximumAge;

    /** How far ahead of the clock a capture moment may sit. */
    private final Duration maximumFuture;

    /**
     * Takes both bounds in minutes.
     *
     * @param maximumAgeMinutes    value of
     *                             {@code carddemo.authorization.origin-timestamp.max-age-minutes},
     *                             one minute or more
     * @param maximumFutureMinutes value of
     *                             {@code carddemo.authorization.origin-timestamp.max-future-minutes},
     *                             zero minutes or more
     * @throws IllegalStateException when either bound falls below its floor. The message names the
     *                               property and carries no value
     */
    public OriginTimestampWindow(
            @Value("${carddemo.authorization.origin-timestamp.max-age-minutes:1440}")
            long maximumAgeMinutes,
            @Value("${carddemo.authorization.origin-timestamp.max-future-minutes:5}")
            long maximumFutureMinutes) {

        if (maximumAgeMinutes < 1) {
            throw new IllegalStateException(
                    "carddemo.authorization.origin-timestamp.max-age-minutes counts up from one");
        }
        if (maximumFutureMinutes < 0) {
            throw new IllegalStateException(
                    "carddemo.authorization.origin-timestamp.max-future-minutes counts up from "
                            + "zero");
        }
        this.maximumAge = Duration.ofMinutes(maximumAgeMinutes);
        this.maximumFuture = Duration.ofMinutes(maximumFutureMinutes);
    }

    /**
     * Refuses a capture moment outside the accepted span, and returns quietly for one inside it.
     *
     * @param originTimestamp the value the request carried, shaped by
     *                        {@code AuthorizationRequest.ORIGIN_TIMESTAMP_PATTERN}
     * @param now             the moment the service clock reports
     * @throws IllegalArgumentException when the value does not read as a moment, or sits outside the
     *                                 span. The message names the bound and never repeats the value
     */
    public void require(String originTimestamp, Instant now) {
        Instant captured = read(originTimestamp);

        if (captured.isBefore(now.minus(maximumAge))) {
            throw new IllegalArgumentException(TOO_OLD_MESSAGE);
        }
        if (captured.isAfter(now.plus(maximumFuture))) {
            throw new IllegalArgumentException(TOO_NEW_MESSAGE);
        }
    }

    /**
     * Reads one capture moment as an instant in Coordinated Universal Time.
     *
     * @param originTimestamp the value the request carried
     * @return the moment it names
     * @throws IllegalArgumentException when the formatter cannot read it
     */
    private static Instant read(String originTimestamp) {
        if (originTimestamp == null) {
            throw new IllegalArgumentException(UNREADABLE_MESSAGE);
        }
        try {
            return LocalDateTime.parse(originTimestamp.strip(), ORIGIN_TIMESTAMP)
                    .toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException unreadable) {
            throw new IllegalArgumentException(UNREADABLE_MESSAGE);
        }
    }
}
