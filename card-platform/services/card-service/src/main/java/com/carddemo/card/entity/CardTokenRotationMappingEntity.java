package com.carddemo.card.entity;

import com.carddemo.cobol.PanMasker;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The token one card carried before a rotation, beside the token it carries now.
 *
 * <p>This is the artifact the rest of the platform is re-keyed from. A card token keys
 * {@code statement_transaction} and {@code notification_log} in the notification service, it is
 * recorded on {@code authorization_decision} in the authorization service, and it is the subject of a
 * {@code SCOPE_CARD} authority an operator grants in configuration. None of those holds a card number,
 * so none can derive its own replacement. A row here states that two tokens name one card, which is
 * exactly what a re-key needs and no more than a holder of either token already knows about that
 * token.
 *
 * <p>Read in the other direction it is the rollback: setting the previous key back as the current one
 * and applying {@code card_token} to {@code previous_card_token} returns every store to the value it
 * held.
 *
 * <p>No card number, no card verification value and no key material reaches this table. The previous
 * token is derivable at all only because {@code CARD_TOKEN_PREVIOUS_SECRET} carries the key it was
 * taken under while the rotation runs, which is the dual read
 * {@link PanMasker#previousCardToken(String)} performs.
 *
 * <p>No field of {@code app/cpy/CVACT02Y.cpy} declares a card token, so this table is additive in
 * full. {@code src/main/resources/db/migration/V10__card_token_version_and_rotation.sql} declares it
 * and states the same contract in the catalogue.
 */
@Entity
@Table(name = "card_token_rotation_mapping",
        indexes = @Index(name = "ix_card_token_rotation_mapping_previous",
                columnList = "previous_card_token"))
public class CardTokenRotationMappingEntity {

    /** Characters {@code previous_version} and {@code version} hold at most. */
    static final int VERSION_WIDTH = 3;

    /** The run this mapping belongs to, together with the token it moved from. */
    @EmbeddedId
    private CardTokenRotationMappingId id;

    /** The token the card carries now, sixty-four lower-case hexadecimal characters. */
    @Column(name = "card_token", nullable = false, updatable = false,
            length = PanMasker.CARD_TOKEN_LENGTH,
            columnDefinition = "bpchar(" + PanMasker.CARD_TOKEN_LENGTH + ")")
    private String cardToken;

    /** The version the previous token was taken under. */
    @Column(name = "previous_version", nullable = false, updatable = false, length = VERSION_WIDTH)
    private String previousVersion;

    /** The version the current token is taken under. */
    @Column(name = "version", nullable = false, updatable = false, length = VERSION_WIDTH)
    private String version;

    /**
     * No-argument constructor for the persistence provider.
     *
     * <p>Hibernate calls this to materialise a row and then populates the fields directly.
     * Application code calls
     * {@link #CardTokenRotationMappingEntity(UUID, String, String, String, String)}.
     */
    protected CardTokenRotationMappingEntity() {
    }

    /**
     * Builds one mapping row.
     *
     * @param rotationId        the run this mapping belongs to
     * @param previousCardToken the token the card carried, which another store still holds
     * @param cardToken         the token the card carries now
     * @param previousVersion   the version the previous token was taken under
     * @param version           the version the current token is taken under
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if either token is not sixty-four lower-case hexadecimal
     *                                  characters, or if the two tokens are equal, because a mapping
     *                                  onto itself re-keys nothing and would hide a rotation that
     *                                  moved nothing
     */
    public CardTokenRotationMappingEntity(UUID rotationId, String previousCardToken,
            String cardToken, String previousVersion, String version) {
        Objects.requireNonNull(previousCardToken, "previousCardToken must not be null");
        Objects.requireNonNull(cardToken, "cardToken must not be null");
        requireTokenShape("previousCardToken", previousCardToken);
        requireTokenShape("cardToken", cardToken);
        if (previousCardToken.equals(cardToken)) {
            throw new IllegalArgumentException("a mapping row states that one card moved from one"
                    + " token to another, and both tokens supplied here are the same value");
        }
        this.id = new CardTokenRotationMappingId(rotationId, previousCardToken);
        this.cardToken = cardToken;
        this.previousVersion =
                Objects.requireNonNull(previousVersion, "previousVersion must not be null");
        this.version = Objects.requireNonNull(version, "version must not be null");
    }

    /**
     * Holds a token to the shape {@link PanMasker#CARD_TOKEN_PATTERN} declares.
     *
     * <p>The message names the argument and its width and never a character of the value, because a
     * token names a card.
     *
     * @param name  the argument name, for the message
     * @param value the token to check
     * @throws IllegalArgumentException if the value is not sixty-four lower-case hexadecimal
     *                                  characters
     */
    private static void requireTokenShape(String name, String value) {
        if (!value.matches(PanMasker.CARD_TOKEN_PATTERN)) {
            throw new IllegalArgumentException(name + " must be "
                    + PanMasker.CARD_TOKEN_LENGTH + " lower-case hexadecimal characters, and the"
                    + " value supplied holds " + value.length());
        }
    }

    /**
     * @return the run and the previous token, which together key the row
     */
    public CardTokenRotationMappingId getId() {
        return id;
    }

    /**
     * @return the token the card carries now
     */
    public String getCardToken() {
        return cardToken;
    }

    /**
     * @return the version the previous token was taken under
     */
    public String getPreviousVersion() {
        return previousVersion;
    }

    /**
     * @return the version the current token is taken under
     */
    public String getVersion() {
        return version;
    }

    /**
     * Compares on the composite primary key alone.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a mapping row carrying the same key
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        return Objects.equals(id, ((CardTokenRotationMappingEntity) other).id);
    }

    /**
     * @return the hash of the composite key
     */
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    /**
     * Renders the row without either token in full.
     *
     * <p>A token names a card, so the rendering carries the run, the two versions and the last eight
     * characters of each token, which is enough to correlate a row and not enough to name a card.
     *
     * @return the run, the two versions and the two token suffixes
     */
    @Override
    public String toString() {
        return "CardTokenRotationMappingEntity[rotationId=" + id.getRotationId()
                + ", previousCardToken=..." + suffixOf(id.getPreviousCardToken())
                + ", cardToken=..." + suffixOf(cardToken)
                + ", previousVersion=" + previousVersion
                + ", version=" + version + "]";
    }

    /**
     * Returns the last eight characters of a token, for a rendering that names no card.
     *
     * @param token the token to shorten, which may be {@code null} on a partially built row
     * @return the last eight characters, or the value itself where it is shorter
     */
    private static String suffixOf(String token) {
        if (token == null || token.length() <= 8) {
            return String.valueOf(token);
        }
        return token.substring(token.length() - 8);
    }

    /**
     * The composite key of one mapping row: the run, and the token that run moved away from.
     *
     * <p>The previous token is the key rather than the current one because it is the value another
     * store still holds, so a re-key statement joins on it.
     */
    @Embeddable
    public static class CardTokenRotationMappingId implements Serializable {

        private static final long serialVersionUID = 1L;

        /** The run this mapping belongs to. */
        @Column(name = "rotation_id", nullable = false, updatable = false)
        private UUID rotationId;

        /** The token the card carried before the run. */
        @Column(name = "previous_card_token", nullable = false, updatable = false,
                length = PanMasker.CARD_TOKEN_LENGTH,
                columnDefinition = "bpchar(" + PanMasker.CARD_TOKEN_LENGTH + ")")
        private String previousCardToken;

        /**
         * No-argument constructor for the persistence provider.
         */
        protected CardTokenRotationMappingId() {
        }

        /**
         * Builds one composite key.
         *
         * @param rotationId        the run this mapping belongs to
         * @param previousCardToken the token the card carried before the run
         * @throws NullPointerException if either argument is {@code null}
         */
        public CardTokenRotationMappingId(UUID rotationId, String previousCardToken) {
            this.rotationId = Objects.requireNonNull(rotationId, "rotationId must not be null");
            this.previousCardToken =
                    Objects.requireNonNull(previousCardToken, "previousCardToken must not be null");
        }

        /**
         * @return the run this mapping belongs to
         */
        public UUID getRotationId() {
            return rotationId;
        }

        /**
         * @return the token the card carried before the run
         */
        public String getPreviousCardToken() {
            return previousCardToken;
        }

        /**
         * Compares on both key columns.
         *
         * @param other the object to compare with
         * @return {@code true} when both columns are equal
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (other == null || getClass() != other.getClass()) {
                return false;
            }
            CardTokenRotationMappingId that = (CardTokenRotationMappingId) other;
            return Objects.equals(rotationId, that.rotationId)
                    && Objects.equals(previousCardToken, that.previousCardToken);
        }

        /**
         * @return the hash of both key columns
         */
        @Override
        public int hashCode() {
            return Objects.hash(rotationId, previousCardToken);
        }

        /**
         * Renders the key without the token in full, for the reason
         * {@link CardTokenRotationMappingEntity#toString()} gives.
         *
         * @return the run and the last eight characters of the previous token
         */
        @Override
        public String toString() {
            return "CardTokenRotationMappingId[rotationId=" + rotationId
                    + ", previousCardToken=..." + suffixOf(previousCardToken) + "]";
        }
    }
}
