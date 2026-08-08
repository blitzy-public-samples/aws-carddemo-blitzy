package com.carddemo.authorization.domain;

import java.util.Objects;

/**
 * Reports whether the replica streams this service authorizes against are caught up with their
 * producers.
 *
 * <p>ADDITIVE, and it has no COBOL ancestor because the source has nothing that can fall behind.
 * {@code app/cbl/CBTRN02C.cbl:L382} and {@code app/cbl/CBTRN02C.cbl:L395} issue keyed reads against
 * the cross-reference and account datasets themselves. This service reads {@code card_xref} and
 * {@code account_credit_snapshot}, which are replicas kept current by {@code CardUpdated} and
 * {@code AccountStateChanged}, so it has to answer a question the source never had: is what this
 * copy holds what the owner published?
 *
 * <p>The question is about the <strong>stream</strong> and never about the age of a row. Both
 * producers publish on a state change and on nothing else, so an unchanged card is a row whose last
 * observation recedes for ever while the copy stays perfectly correct. Treating that recession as
 * staleness refused every call for every unchanged card once the window elapsed, which took the
 * whole service down for the ordinary case rather than for the failing one. Consumer lag says the
 * opposite thing about the same situation: a caught-up consumer of a quiet topic reports zero lag,
 * so a card nobody has touched stays authorizable indefinitely and a change that was published and
 * not yet applied is what registers.
 *
 * <p>Lag alone is not the whole answer, and {@link ReplicaGapLog} carries the rest. A record that
 * was delivered and could not be applied advances the offset once its diagnostic is away, so lag
 * returns to zero while that account's copy is missing a change. Lag answers "is the stream
 * flowing", the gap log answers "did any account lose a change", and a decision needs both.
 *
 * <p>An implementation is polled once per authorization call, so it answers from state it already
 * holds and issues no broker request of its own.
 *
 * @see KafkaReplicaSynchronization the delivered implementation, which reads the lag each replica
 *      listener already measures
 */
public interface ReplicaSynchronization {

    /**
     * Answers whether the replica streams are usable for a decision right now.
     *
     * @return the verdict, never {@code null}
     */
    Verdict verdict();

    /**
     * One verdict about the replica streams.
     *
     * <p>{@code reason} is a short, fixed phrase naming why a verdict refuses. It names no account,
     * no card and no value read from any record, so it is safe in a log line, in a health document
     * and in an exception message.
     *
     * @param usable        whether a decision may read the replica tables
     * @param reason        why not, or {@code "synchronized"} when it may
     * @param observedLag   the highest record lag observed across the replica streams, or
     *                      {@link #UNKNOWN_LAG} when no consumer reported one
     */
    record Verdict(boolean usable, String reason, long observedLag) {

        /** Reported where a consumer has published no lag measurement yet. */
        public static final long UNKNOWN_LAG = -1L;

        /** The reason a usable verdict carries. */
        public static final String SYNCHRONIZED = "synchronized";

        /**
         * Holds both components present.
         *
         * @throws NullPointerException when {@code reason} is absent
         */
        public Verdict {
            Objects.requireNonNull(reason, "reason must be present");
        }

        /**
         * Returns the verdict of a stream that is caught up.
         *
         * @param observedLag the highest lag observed, which a caught-up stream reports at or under
         *                    its configured ceiling
         * @return a usable verdict
         */
        public static Verdict synchronizedAt(long observedLag) {
            return new Verdict(true, SYNCHRONIZED, observedLag);
        }

        /**
         * Returns the verdict of a stream that is not caught up.
         *
         * @param reason      the fixed phrase naming what is wrong, carrying no identifier
         * @param observedLag the highest lag observed, or {@link #UNKNOWN_LAG}
         * @return an unusable verdict
         */
        public static Verdict behind(String reason, long observedLag) {
            return new Verdict(false, reason, observedLag);
        }
    }
}
