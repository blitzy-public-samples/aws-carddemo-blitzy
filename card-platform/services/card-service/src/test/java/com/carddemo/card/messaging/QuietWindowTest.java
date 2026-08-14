package com.carddemo.card.messaging;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Proves the bounded watch every negative publication assertion depends on is measured from a
 * reading no clock adjustment moves.
 *
 * <p>{@link CardEventPublicationTest} concludes four times over that a card list, a card read, an
 * update that changed nothing and a replayed row published no event. Each conclusion rests on
 * {@code recordsDuring}: watch the topic for a window, and if nothing arrived, nothing was
 * published. The strength of all four therefore reduces to one question, which is whether the window
 * lasts as long as it claims.
 *
 * <p>It did not. The deadline was {@code Instant.now().plus(window)}, a calendar instant, and the
 * loop compared the calendar against it. A clock adjustment that moved the calendar forward inside
 * the window moved the deadline into the past, and the watch closed on its first reading. A second
 * event still in flight was then never polled for, and the assertion reported that nothing arrived
 * having barely looked. The reverse adjustment stretched the window instead and slowed every negative
 * test in the class.
 *
 * <p>What this class establishes, in order. The watch keeps the window it is given and asks its
 * source before it tests any deadline. A source that jumps forward past the deadline closes the watch
 * after one reading and loses a record that a well-behaved source sees, which is the defect measured
 * rather than described. A source that jumps backward doubles the readings. The deadline comparison
 * stays correct across a reading that overflows, which a plain {@code <} does not. And neither the
 * watch nor its caller reads a calendar at all.
 *
 * <p>Every scenario but two drives the watch from a fixed sequence of readings rather than from a
 * clock, so each reading count below is exact rather than approximate and no assertion here depends
 * on how fast the machine runs.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("The quiet window: a bounded watch measured from a reading no clock moves")
class QuietWindowTest {

    /** The window every scenario asks for, small and round so each reading count is arithmetic. */
    private static final long WINDOW_NANOS = 1_000L;

    private static final Duration WINDOW = Duration.ofNanos(WINDOW_NANOS);

    /** The record a scenario makes available part-way through, to be seen or missed. */
    private static final String LATE_RECORD = "the-second-event";

    /** Which reading of the source produces {@link #LATE_RECORD} in the scenarios that use it. */
    private static final int LATE_RECORD_ARRIVES_ON_READING = 2;

    /** Passed where a scenario wants a source that produces nothing at all. */
    private static final int NOTHING_EVER_ARRIVES = 0;

    /** Window the two clock-backed scenarios ask for, long enough to measure and short to wait. */
    private static final Duration MEASURABLE_WINDOW = Duration.ofMillis(40);

    /** Readings the monotonic assertion samples. */
    private static final int SAMPLED_READINGS = 20_000;

    /** Source file of the watch, relative to this module and to the repository root. */
    private static final String WATCH_SOURCE = "com/carddemo/card/messaging/QuietWindow.java";

    /** Source file of the caller whose four negative assertions rest on the watch. */
    private static final String CALLER_SOURCE =
            "com/carddemo/card/messaging/CardEventPublicationTest.java";

    /** How the watch binds the one reading a deadline may be built from. */
    private static final String PERMITTED_READING = "System::nanoTime";

    /** How the caller reaches the watch, rather than building a deadline of its own. */
    private static final String DELEGATION = "QuietWindow.collectDuring";

    /**
     * Readings a deadline may not be built from, each one a calendar an adjustment moves.
     *
     * <p>{@code System.currentTimeMillis} and every {@code now} factory answer a position on the
     * wall clock. Two readings of any of them can differ by a value that is not elapsed time.
     */
    private static final List<String> FORBIDDEN_READINGS = List.of(
            "System.currentTimeMillis", "Instant.now", "LocalDate.now", "LocalDateTime.now",
            "LocalTime.now", "ZonedDateTime.now", "OffsetDateTime.now", "Clock.system",
            "Clock.systemUTC", "Clock.systemDefaultZone", "new Date(");

    @Nested
    @DisplayName("The window the watch keeps")
    class TheWindowTheWatchKeeps {

        /**
         * Asserts a source stepping a quarter of the window at a time is read to the end of it.
         *
         * <p>Readings run 0, 250, 500, 750 and 1000 against a window of 1000. The first builds the
         * deadline, so four readings remain and the fourth reaches it, which is four polls. The
         * source refuses a fifth reading, so an off-by-one in either direction is an error rather
         * than a silent difference.
         */
        @Test
        @DisplayName("a well-behaved source is read to the end of the window")
        void aWellBehavedSourceIsReadToTheEndOfTheWindow() {
            CountingSource source = readings(0, 250, 500, 750, 1_000);
            CountingPoll poll = new CountingPoll(LATE_RECORD_ARRIVES_ON_READING);

            List<String> seen = QuietWindow.collectDuring(WINDOW, source, poll);

            assertAll(
                    () -> assertEquals(4, poll.polls(),
                            "a window of 1000 read in steps of 250 takes four polls"),
                    () -> assertEquals(5, source.readings(),
                            "one reading builds the deadline and four test it"),
                    () -> assertEquals(List.of(LATE_RECORD), seen,
                            "the record arriving on the second poll is seen"));
        }

        /**
         * Asserts the watch reads its source before it tests any deadline.
         *
         * <p>A window of nothing is the boundary. The loop reads once and then finds the deadline
         * already reached, so the watch never reports an empty result without having asked.
         */
        @Test
        @DisplayName("even a window of nothing takes one reading of the source")
        void evenAWindowOfNothingTakesOneReadingOfTheSource() {
            CountingSource source = readings(0, 0);
            CountingPoll poll = new CountingPoll(1);

            List<String> seen = QuietWindow.collectDuring(Duration.ZERO, source, poll);

            assertAll(
                    () -> assertEquals(1, poll.polls(), "one poll, before any deadline test"),
                    () -> assertEquals(List.of(LATE_RECORD), seen, "and its record is kept"));
        }

        /**
         * Asserts the watch bound to the shipped reading stays open for the window it was given.
         *
         * <p>This is the one scenario measured against the machine rather than against a fixed
         * sequence, and it is measured with the same reading the watch uses, so a calendar
         * adjustment cannot make it pass or fail either. It asserts the floor only: elapsed time is
         * at least the window. There is no ceiling, because a loaded machine may overshoot and that
         * costs a negative assertion nothing.
         */
        @Test
        @DisplayName("the shipped watch stays open for at least the window it was given")
        void theShippedWatchStaysOpenForAtLeastTheWindowItWasGiven() {
            long before = System.nanoTime();

            List<String> seen = QuietWindow.collectDuring(MEASURABLE_WINDOW,
                    new CountingPoll(NOTHING_EVER_ARRIVES));

            long elapsed = System.nanoTime() - before;
            assertAll(
                    () -> assertTrue(elapsed >= MEASURABLE_WINDOW.toNanos(),
                            "the watch closed after " + elapsed + " nanoseconds, short of the "
                                    + MEASURABLE_WINDOW.toNanos() + " it was given"),
                    () -> assertTrue(seen.isEmpty(), "a source producing nothing yields nothing"));
        }
    }

    @Nested
    @DisplayName("What a source that jumps costs")
    class WhatASourceThatJumpsCosts {

        /**
         * Asserts a source that jumps past the deadline closes the watch after one reading, and that
         * the record a well-behaved source sees is lost.
         *
         * <p>This is the defect the wall-clock deadline admitted, measured. The second reading
         * reports 1001 against a deadline of 1000, which is one nanosecond of elapsed time reported
         * as the whole window having passed. The watch closes, and the record that would have arrived
         * on the second poll never does.
         *
         * <p>The comparison that makes this a defect rather than a preference is with
         * {@code aWellBehavedSourceIsReadToTheEndOfTheWindow} above: same window, same poll
         * behaviour, same watch, and the record is seen there and missed here. The only difference is
         * the reading the deadline was measured against.
         */
        @Test
        @DisplayName("a forward jump closes the watch after one reading and loses the record")
        void aForwardJumpClosesTheWatchAfterOneReadingAndLosesTheRecord() {
            CountingSource source = readings(0, WINDOW_NANOS + 1);
            CountingPoll poll = new CountingPoll(LATE_RECORD_ARRIVES_ON_READING);

            List<String> seen = QuietWindow.collectDuring(WINDOW, source, poll);

            assertAll(
                    () -> assertEquals(1, poll.polls(),
                            "a reading past the deadline ends the watch immediately"),
                    () -> assertEquals(2, source.readings(), "the deadline, then one test of it"),
                    () -> assertTrue(seen.isEmpty(),
                            "the record arriving on the second poll is lost, and a negative"
                                    + " assertion built on this watch would report silence: "
                                    + seen));
        }

        /**
         * Asserts a source that jumps backward stretches the watch instead of shortening it.
         *
         * <p>Readings run 0, then a full window behind where a well-behaved source would be, then
         * step forward by 250 until the deadline of 1000 is reached. Eight polls, twice the four a
         * well-behaved source takes. Nothing is lost, which is why the forward jump is the dangerous
         * direction and this one only costs time.
         */
        @Test
        @DisplayName("a backward jump doubles the readings and loses nothing")
        void aBackwardJumpDoublesTheReadingsAndLosesNothing() {
            CountingSource source = readings(0, -750, -500, -250, 0, 250, 500, 750, 1_000);
            CountingPoll poll = new CountingPoll(LATE_RECORD_ARRIVES_ON_READING);

            List<String> seen = QuietWindow.collectDuring(WINDOW, source, poll);

            assertAll(
                    () -> assertEquals(8, poll.polls(),
                            "twice the four polls a well-behaved source takes"),
                    () -> assertEquals(List.of(LATE_RECORD), seen, "and the record is still seen"));
        }

        /**
         * Asserts the deadline comparison survives a reading that overflows.
         *
         * <p>{@link System#nanoTime()} documents that its readings overflow, and that two of them
         * must therefore be compared by subtracting one from the other and testing the sign rather
         * than by comparing them directly. This scenario is the case that distinguishes the two
         * forms. A first reading of {@code Long.MAX_VALUE - 500} makes a deadline of
         * {@code Long.MAX_VALUE + 500}, which wraps to {@code Long.MIN_VALUE + 499}. The next reading
         * is still five hundred nanoseconds short of the deadline, so the watch must stay open, and
         * the subtraction says so while a direct comparison of a large positive against a large
         * negative says the opposite and closes the watch after one poll.
         *
         * <p>Three polls is therefore the assertion that holds only for the overflow-safe form.
         */
        @Test
        @DisplayName("a reading that overflows still closes the watch at the right point")
        void aReadingThatOverflowsStillClosesTheWatchAtTheRightPoint() {
            CountingSource source = readings(Long.MAX_VALUE - 500, Long.MAX_VALUE - 250,
                    Long.MIN_VALUE + 249, Long.MIN_VALUE + 599);
            CountingPoll poll = new CountingPoll(LATE_RECORD_ARRIVES_ON_READING);

            List<String> seen = QuietWindow.collectDuring(WINDOW, source, poll);

            assertAll(
                    () -> assertEquals(3, poll.polls(),
                            "the window spans the overflow and the watch stays open across it"),
                    () -> assertEquals(List.of(LATE_RECORD), seen, "and the record is seen"),
                    () -> assertTrue(Long.MAX_VALUE - 250 > Long.MIN_VALUE + 499,
                            "a direct comparison of these two readings reports the deadline"
                                    + " already reached, which is why the watch subtracts"));
        }
    }

    @Nested
    @DisplayName("What the wait path reads")
    class WhatTheWaitPathReads {

        /**
         * Asserts the reading the watch is bound to never goes backward.
         *
         * <p>Sampled rather than proved, which is all a test can do for a platform guarantee. A
         * calendar reading fails this the moment an adjustment lands during the sample.
         */
        @Test
        @DisplayName("the bound reading never goes backward")
        void theBoundReadingNeverGoesBackward() {
            long previous = QuietWindow.MONOTONIC_NANOS.getAsLong();

            for (int sample = 0; sample < SAMPLED_READINGS; sample++) {
                long current = QuietWindow.MONOTONIC_NANOS.getAsLong();
                long difference = current - previous;
                assertTrue(difference >= 0, "reading " + sample + " went backward by "
                        + (-difference) + " nanoseconds");
                previous = current;
            }
        }

        /**
         * Asserts neither the watch nor the caller whose assertions depend on it reads a calendar.
         *
         * <p>Comments are stripped before the scan, so the prose above explaining what a wall clock
         * costs cannot satisfy or break this. What is left is code, and the only reading it may take
         * is the elapsed one.
         *
         * <p>Two positive claims sit alongside the refusals. The watch still binds the elapsed
         * reading, and the caller still reaches the watch rather than timing a window of its own, so
         * neither the binding nor the delegation can be removed without failing here.
         */
        @Test
        @DisplayName("neither the watch nor its caller reads a calendar")
        void neitherTheWatchNorItsCallerReadsACalendar() throws IOException {
            String watch = codeOf(WATCH_SOURCE);
            String caller = codeOf(CALLER_SOURCE);

            List<Runnable> checks = new ArrayList<>();
            checks.add(() -> assertTrue(watch.contains(PERMITTED_READING),
                    "the watch no longer binds the elapsed reading as " + PERMITTED_READING));
            checks.add(() -> assertTrue(caller.contains(DELEGATION),
                    "the caller no longer reaches the watch through " + DELEGATION
                            + ", so it is timing a window of its own again"));
            for (String forbidden : FORBIDDEN_READINGS) {
                checks.add(() -> assertTrue(!watch.contains(forbidden),
                        "the watch reads the calendar through " + forbidden));
                checks.add(() -> assertTrue(!caller.contains(forbidden),
                        "the caller reads the calendar through " + forbidden
                                + ", and its four negative assertions rest on the window"));
            }
            assertAll(checks.stream().map(check -> (org.junit.jupiter.api.function.Executable)
                    check::run).toList());
        }

        /** Asserts the watch is a holder of static members and refuses construction. */
        @Test
        @DisplayName("the watch cannot be instantiated")
        void theWatchCannotBeInstantiated() throws Exception {
            var constructor = QuietWindow.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            var raised = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    constructor::newInstance);

            assertEquals(AssertionError.class, raised.getCause().getClass(),
                    "the constructor refuses with an assertion error");
        }
    }

    /**
     * Reads one test source file and strips its comments, so a scan sees code only.
     *
     * <p>Resolution walks upward from the working directory, because a module build and a
     * repository-root build start in different places.
     *
     * @param relativeToTestRoot path of the file below {@code src/test/java}
     * @return the file with block and line comments removed
     * @throws IOException if the file cannot be read
     */
    private static String codeOf(String relativeToTestRoot) throws IOException {
        Path base = Path.of("").toAbsolutePath().normalize();
        List<Path> candidates = List.of(
                Path.of("src/test/java").resolve(relativeToTestRoot),
                Path.of("services/card-service/src/test/java").resolve(relativeToTestRoot),
                Path.of("card-platform/services/card-service/src/test/java")
                        .resolve(relativeToTestRoot));
        List<Path> attempted = new ArrayList<>();

        for (int depth = 0; depth <= 5 && base != null; depth++) {
            for (Path candidate : candidates) {
                Path resolved = base.resolve(candidate).normalize();
                attempted.add(resolved);
                if (Files.isRegularFile(resolved)) {
                    return stripComments(Files.readString(resolved));
                }
            }
            base = base.getParent();
        }
        throw new AssertionError(relativeToTestRoot + " was not found; tried: " + attempted);
    }

    /**
     * Removes block and line comments from Java source.
     *
     * @param source the source text
     * @return the same text with every comment replaced by a single space
     */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//[^\n]*", " ");
    }

    /**
     * Builds a source that answers a fixed sequence of readings and counts how many were taken.
     *
     * <p>Exhausting the sequence is an error rather than a wrap, so a watch that takes more readings
     * than a scenario allows for fails there and then instead of looping.
     *
     * @param values the readings, in order
     * @return the source
     */
    private static CountingSource readings(long... values) {
        return new CountingSource(values);
    }

    /** A source of a fixed sequence of readings, which counts the readings taken from it. */
    private static final class CountingSource implements LongSupplier {

        private final long[] values;
        private int taken;

        private CountingSource(long[] values) {
            this.values = values.clone();
        }

        @Override
        public long getAsLong() {
            if (taken >= values.length) {
                throw new AssertionError("the watch took reading " + (taken + 1)
                        + " from a source given only " + values.length);
            }
            return values[taken++];
        }

        /**
         * Answers how many readings were taken.
         *
         * @return the count
         */
        private int readings() {
            return taken;
        }
    }

    /** A poll that counts its calls and produces one record on a chosen call. */
    private static final class CountingPoll implements Supplier<List<String>> {

        private final int arrivesOnPoll;
        private int polls;

        private CountingPoll(int arrivesOnPoll) {
            this.arrivesOnPoll = arrivesOnPoll;
        }

        @Override
        public List<String> get() {
            polls++;
            return polls == arrivesOnPoll ? List.of(LATE_RECORD) : List.of();
        }

        /**
         * Answers how many times this poll ran.
         *
         * @return the count
         */
        private int polls() {
            return polls;
        }
    }
}
