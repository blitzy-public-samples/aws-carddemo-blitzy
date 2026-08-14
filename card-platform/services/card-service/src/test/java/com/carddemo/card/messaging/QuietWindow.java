package com.carddemo.card.messaging;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A bounded watch over a source of records, whose deadline is monotonic.
 *
 * <p>A negative publication assertion needs a window rather than one reading. One reading that finds
 * nothing proves nothing about a publish still in flight, so the watch has to stay open long enough
 * for a second event to arrive and only then conclude that none did.
 *
 * <p>The deadline is built from {@link System#nanoTime()}, which reports elapsed time from an origin
 * no clock adjustment moves. A deadline built from a wall clock is a calendar instant instead, and
 * the two behave differently in exactly the case the watch exists for: an adjustment that moves the
 * wall clock forward during the window brings the deadline forward with it and closes the watch
 * early, so a duplicate arriving afterwards is never polled for and the assertion passes having seen
 * nothing. An adjustment the other way stretches the window and slows every negative test instead.
 * Neither happens here, because {@link #MONOTONIC_NANOS} is not a calendar.
 *
 * <p>Two further properties are deliberate. The comparison subtracts before it tests the sign, which
 * is the overflow-safe form {@link System#nanoTime()} documents for comparing two of its readings.
 * And the loop reads once before it tests the deadline, so this never reports an empty result without
 * having asked its source at least once.
 *
 * <p>The time source is a parameter so that {@link QuietWindowTest} can drive the watch from a
 * reading it controls and demonstrate what a source that jumps costs.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
final class QuietWindow {

    /**
     * The reading every production use of this class takes its deadline from.
     *
     * <p>{@link System#nanoTime()} answers elapsed nanoseconds from a fixed but arbitrary origin. Its
     * value is not a calendar instant and carries no relation to one, which is the whole reason it is
     * the reading a window uses.
     */
    static final LongSupplier MONOTONIC_NANOS = System::nanoTime;

    private QuietWindow() {
        throw new AssertionError("QuietWindow holds only static members");
    }

    /**
     * Watches for the given window using the monotonic reading, and answers everything seen.
     *
     * @param window   how long to watch
     * @param pollOnce one reading of the source, which answers the records it found, possibly none
     * @param <T>      the record type
     * @return every record seen inside the window, in the order the readings produced them
     */
    static <T> List<T> collectDuring(Duration window, Supplier<List<T>> pollOnce) {
        return collectDuring(window, MONOTONIC_NANOS, pollOnce);
    }

    /**
     * Watches for the given window using the supplied reading, and answers everything seen.
     *
     * @param window       how long to watch
     * @param elapsedNanos the reading the deadline is built from and compared against
     * @param pollOnce     one reading of the source, which answers the records it found, possibly
     *                     none
     * @param <T>          the record type
     * @return every record seen inside the window, in the order the readings produced them
     */
    static <T> List<T> collectDuring(Duration window, LongSupplier elapsedNanos,
            Supplier<List<T>> pollOnce) {
        List<T> collected = new ArrayList<>();
        long deadline = elapsedNanos.getAsLong() + window.toNanos();
        do {
            collected.addAll(pollOnce.get());
        } while (elapsedNanos.getAsLong() - deadline < 0);
        return collected;
    }
}
