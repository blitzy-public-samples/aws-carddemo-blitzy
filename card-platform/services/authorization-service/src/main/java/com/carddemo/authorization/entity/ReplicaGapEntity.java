package com.carddemo.authorization.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * One row per account whose replica copy is missing a change, on one replica stream, in table
 * {@code replica_gap}.
 *
 * <p>ADDITIVE. No CardDemo copybook and no CardDemo program is the ancestor of this table, because
 * the source holds no replica: {@code app/cbl/CBTRN02C.cbl:L382} and
 * {@code app/cbl/CBTRN02C.cbl:L395} issue keyed reads against the cross-reference and account
 * datasets themselves, so nothing they read can be missing a change that was applied elsewhere.
 *
 * <p>This service reads {@code card_xref} and {@code account_credit_snapshot}, which two listeners
 * keep current. A record that is delivered and cannot be applied has its offset advanced once its
 * diagnostic is away, so consumer lag returns to zero while that one account's copy is behind. That
 * is the case this table names, and it is the case the retired
 * {@code carddemo.replica.max-staleness} bound could not see: a failed application left
 * {@code observed_at} exactly as recent as a successful one, so an age comparison reported the
 * damaged row as fresh and the untouched correct row as stale.
 *
 * <p>{@code domain/AuthorizationService} refuses a decision that would read either replica row of an
 * account named here, and {@code messaging/AccountStateChangedConsumer} and
 * {@code messaging/CardUpdatedConsumer} delete the row when a later record for that account applies.
 * Nothing expires it on a timer, deliberately: a window would let a decision read a copy this service
 * knows is missing a change.
 *
 * <p>Six columns and a composite primary key, created on PostgreSQL 18.4 by
 * {@code src/main/resources/db/migration/V12__replica_gap.sql}, which is authoritative for their
 * definitions.
 *
 * <p>The {@link Table} annotation carries no {@code schema} attribute. The default schema arrives
 * from {@code spring.jpa.properties.hibernate.default_schema} in
 * {@code src/main/resources/application.yml}, so one class serves every environment.
 */
@Entity
@Table(name = "replica_gap")
public class ReplicaGapEntity {

    /** Widest value {@code stream} holds, from {@code stream VARCHAR(64)}. */
    public static final int STREAM_MAX_LENGTH = 64;

    /** Widest value {@code last_failure} holds, from {@code last_failure VARCHAR(64)}. */
    public static final int LAST_FAILURE_MAX_LENGTH = 64;

    /** The account and the stream, which together identify one gap. */
    @EmbeddedId
    private Key id;

    /** When this account first failed on this stream. Diagnostic only. */
    @Column(name = "first_failed_at", nullable = false)
    private Instant firstFailedAt;

    /** When this account last failed on this stream. Diagnostic only. */
    @Column(name = "last_failed_at", nullable = false)
    private Instant lastFailedAt;

    /** How many deliveries for this account have failed on this stream. */
    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    /** The class name of the last failure, and nothing else from it. */
    @Column(name = "last_failure", length = LAST_FAILURE_MAX_LENGTH)
    private String lastFailure;

    /** Required by the persistence provider. */
    protected ReplicaGapEntity() {
    }

    /**
     * Opens a gap for one account on one stream.
     *
     * @param aggregateId the account whose copy is missing a change, eleven digits
     * @param stream      the replica topic whose record could not be applied
     * @param failedAt    when the delivery failed
     * @param lastFailure the class name of the failure, or {@code null} when none is available
     * @throws NullPointerException     when the account, the stream or the moment is absent
     * @throws IllegalArgumentException when the stream is blank or wider than its column
     */
    public ReplicaGapEntity(String aggregateId, String stream, Instant failedAt,
            String lastFailure) {
        this.id = new Key(aggregateId, stream);
        this.firstFailedAt = Objects.requireNonNull(failedAt, "failedAt must be present");
        this.lastFailedAt = failedAt;
        this.failureCount = 1;
        this.lastFailure = atWidth(lastFailure);
    }

    /**
     * Records one further failure for a gap that already stands.
     *
     * @param failedAt    when this delivery failed
     * @param lastFailure the class name of this failure, or {@code null} when none is available
     * @throws NullPointerException when {@code failedAt} is absent
     */
    public void recordFurtherFailure(Instant failedAt, String lastFailure) {
        Objects.requireNonNull(failedAt, "failedAt must be present");
        if (failedAt.isAfter(this.lastFailedAt)) {
            this.lastFailedAt = failedAt;
        }
        this.failureCount = this.failureCount + 1;
        this.lastFailure = atWidth(lastFailure);
    }

    /**
     * Truncates a failure class name to the width its column holds.
     *
     * <p>Truncating rather than refusing, because a gap that cannot be recorded is a gap a decision
     * never learns about, and the value is diagnostic.
     *
     * @param lastFailure the class name, possibly {@code null}
     * @return the value at or under {@link #LAST_FAILURE_MAX_LENGTH} characters
     */
    private static String atWidth(String lastFailure) {
        if (lastFailure == null || lastFailure.length() <= LAST_FAILURE_MAX_LENGTH) {
            return lastFailure;
        }
        return lastFailure.substring(0, LAST_FAILURE_MAX_LENGTH);
    }

    /**
     * Returns the account and stream this gap names.
     *
     * @return the key, never {@code null} on a persisted row
     */
    public Key getId() {
        return id;
    }

    /**
     * Returns when this account first failed on this stream.
     *
     * @return the first failure moment
     */
    public Instant getFirstFailedAt() {
        return firstFailedAt;
    }

    /**
     * Returns when this account last failed on this stream.
     *
     * @return the last failure moment
     */
    public Instant getLastFailedAt() {
        return lastFailedAt;
    }

    /**
     * Returns how many deliveries for this account have failed on this stream.
     *
     * @return the failure count, never under one
     */
    public int getFailureCount() {
        return failureCount;
    }

    /**
     * Returns the class name of the last failure.
     *
     * @return the class name, or {@code null} when none was available
     */
    public String getLastFailure() {
        return lastFailure;
    }

    /**
     * The account and the replica stream, which together identify one gap.
     *
     * <p>The stream is part of the identity because one account can be behind on both replica
     * streams independently, and clearing one must not clear the other.
     */
    @Embeddable
    public static class Key implements Serializable {

        /** Serialization identity of this key. */
        private static final long serialVersionUID = 1L;

        /** Width of {@code aggregate_id}, from {@code CHAR(11)}. */
        private static final int AGGREGATE_ID_LENGTH = 11;

        /**
         * The account whose copy is missing a change.
         *
         * <p>{@code columnDefinition} names the fixed-width type the migration declares, because the
         * persistence provider validates the entity model against the migrated schema rather than
         * generating it, and a declared {@code length} alone reads as {@code varchar}. Every
         * eleven-digit account column on this service is declared the same way.
         */
        @Column(name = "aggregate_id", nullable = false, length = AGGREGATE_ID_LENGTH,
                columnDefinition = "bpchar(" + AGGREGATE_ID_LENGTH + ")")
        private String aggregateId;

        /** The replica topic whose record could not be applied. */
        @Column(name = "stream", nullable = false, length = STREAM_MAX_LENGTH)
        private String stream;

        /** Required by the persistence provider. */
        protected Key() {
        }

        /**
         * Holds both components at the width their columns declare.
         *
         * @param aggregateId the account, eleven digits
         * @param stream      the replica topic
         * @throws NullPointerException     when either component is absent
         * @throws IllegalArgumentException when the account is not eleven digits, or the stream is
         *                                  blank or wider than its column
         */
        public Key(String aggregateId, String stream) {
            Objects.requireNonNull(aggregateId, "aggregateId must be present");
            Objects.requireNonNull(stream, "stream must be present");
            if (!aggregateId.matches("^[0-9]{" + AGGREGATE_ID_LENGTH + "}$")) {
                throw new IllegalArgumentException("aggregateId must be " + AGGREGATE_ID_LENGTH
                        + " digits");
            }
            if (stream.isBlank()) {
                throw new IllegalArgumentException("stream must name a topic");
            }
            if (stream.length() > STREAM_MAX_LENGTH) {
                throw new IllegalArgumentException("stream is " + stream.length()
                        + " characters, over the " + STREAM_MAX_LENGTH + " its column holds");
            }
            this.aggregateId = aggregateId;
            this.stream = stream;
        }

        /**
         * Returns the account this gap names.
         *
         * @return the account identifier, eleven digits
         */
        public String getAggregateId() {
            return aggregateId;
        }

        /**
         * Returns the replica stream this gap names.
         *
         * @return the topic name
         */
        public String getStream() {
            return stream;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(aggregateId, key.aggregateId)
                    && Objects.equals(stream, key.stream);
        }

        @Override
        public int hashCode() {
            return Objects.hash(aggregateId, stream);
        }
    }
}
