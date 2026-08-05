package com.carddemo.notification.entity;

import com.carddemo.cobol.PanMasker;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Records one cardholder alert this service rendered and attempted to deliver.
 *
 * <p>One instance maps to one row of the table {@code notification_log}, whose six columns this
 * module declares in {@code src/main/resources/db/migration/V1__schema.sql}. The primary key
 * {@code pk_notification_log} covers {@code id} alone. The check constraint
 * {@code ck_notification_log_card_token} holds {@code card_token} to the token shape and
 * {@code ck_notification_log_card_number} holds {@code card_number} to the masked shape. A row
 * names no statement row: the migration declares no foreign key.
 *
 * <p>No COBOL ancestor: this class has no source ancestor. No Common Business Oriented Language
 * (COBOL) program in CardDemo records a delivery attempt. Every locator below is a reference.
 *
 * <p>{@code channel} names which rendered format one attempt carried. The source renders two: the
 * text layout {@code 01 STATEMENT-LINES.} at {@code app/cbl/CBSTM03A.CBL:L85}, and the markup
 * layout {@code 01 HTML-LINES.} at {@code app/cbl/CBSTM03A.CBL:L148}. Those two reach two
 * datasets: {@code STMTFILE} at {@code LRECL=80} in {@code app/jcl/CREASTMT.JCL:L89}, and
 * {@code HTMLFILE} at {@code LRECL=100} in {@code app/jcl/CREASTMT.JCL:L94}. The classes
 * {@code PlainTextRenderer} and {@code HtmlRenderer} produce them.
 *
 * <p>Two columns identify the card, and neither holds a full one. {@code card_token} carries the
 * stable opaque identifier {@link PanMasker#cardToken(String)} derives, and it is the value
 * {@code ix_notification_log_card_token} reads one card's attempts by. {@code card_number} carries
 * the masked form for display. Both are additions, and the source masks nothing: the card number
 * occupies all sixteen characters on the card detail map, where {@code CARDSID DFHMDF} carries
 * {@code LENGTH=16} at {@code app/bms/COCRDSL.bms:L99}. No column, field or accessor here carries a
 * card verification value.
 *
 * <p>The caller supplies all five values, {@code id} and {@code attemptedAt} among them.
 */
@Entity
@Table(name = "notification_log")
public class NotificationLogEntity {

    /**
     * Shape of a masked card number: twelve mask characters then four digits.
     *
     * <p>The check constraint {@code ck_notification_log_card_number} holds
     * {@code masked_card_number} to the same shape.</p>
     */
    private static final Pattern MASKED_CARD_NUMBER = Pattern.compile("^\\*{12}[0-9]{4}$");

    /**
     * Characters {@code masked_card_number CHAR(16)} holds, matching {@code TRNX-CARD-NUM PIC X(16)}
     * {@code app/cpy/COSTM01.CPY:L22}.
     */
    public static final int CARD_NUMBER_LENGTH = 16;

    /**
     * Characters the {@code card_token} column holds, the width
     * {@link com.carddemo.cobol.PanMasker#CARD_TOKEN_LENGTH} fixes.
     */
    public static final int CARD_TOKEN_LENGTH = PanMasker.CARD_TOKEN_LENGTH;

    /**
     * Shape of a card token: {@value PanMasker#CARD_TOKEN_LENGTH} lower-case hexadecimal
     * characters.
     *
     * <p>The check constraint {@code ck_notification_log_card_token} holds {@code card_token} to
     * the same shape.</p>
     */
    private static final Pattern CARD_TOKEN = Pattern.compile(PanMasker.CARD_TOKEN_PATTERN);

    /**
     * Characters {@code transaction_id CHAR(16)} holds, matching {@code TRNX-ID PIC X(16)} at
     * {@code app/cpy/COSTM01.CPY:L23}.
     */
    public static final int TRANSACTION_ID_LENGTH = 16;

    /** Widest value {@code channel VARCHAR(20)} holds. */
    public static final int CHANNEL_MAX_LENGTH = 20;

    /**
     * Identifier of this delivery attempt, assigned by the caller.
     *
     * <p>Maps to {@code id UUID NOT NULL}, which carries the primary key
     * {@code pk_notification_log}.</p>
     */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * The card this attempt alerted, named by its token.
     *
     * <p>Maps to {@code card_token NOT NULL}. The index
     * {@code ix_notification_log_card_token} reads the delivery history of one card through this
     * column, so it and not {@code card_number} is the identity a query names.</p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_token", nullable = false, length = CARD_TOKEN_LENGTH)
    private String cardToken;

    /**
     * The card this attempt alerted, held as twelve mask characters then the last four digits.
     *
     * <p>Maps to {@code card_number CHAR(16) NOT NULL}. PostgreSQL pads a {@code CHAR} value to
     * its declared width on read, and a masked card number already fills all
     * {@value #CARD_NUMBER_LENGTH} characters. Display only: every card ending in the same four
     * digits shares one masked form, so no query names this column.</p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "masked_card_number", nullable = false, length = CARD_NUMBER_LENGTH)
    private String maskedCardNumber;

    /**
     * The transaction this attempt alerted on.
     *
     * <p>Maps to {@code transaction_id CHAR(16) NOT NULL}. The source field
     * {@code TRNX-ID PIC X(16)} at {@code app/cpy/COSTM01.CPY:L23} is alphanumeric, so a value
     * here carries any {@value #TRANSACTION_ID_LENGTH} characters.</p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "transaction_id", nullable = false, length = TRANSACTION_ID_LENGTH)
    private String transactionId;

    /**
     * Which rendered format this attempt carried.
     *
     * <p>Maps to {@code channel VARCHAR(20) NOT NULL}. The column is {@code VARCHAR}, and this
     * field carries the default character mapping: no {@code JdbcTypeCode} annotation. The
     * migration constrains the value set no further, and neither does this field.</p>
     */
    @Column(name = "channel", nullable = false, length = CHANNEL_MAX_LENGTH)
    private String channel;

    /**
     * Instant at which this service attempted the delivery, supplied by the caller.
     *
     * <p>Maps to {@code attempted_at TIMESTAMP(6) WITH TIME ZONE NOT NULL}.</p>
     */
    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    /**
     * No-argument constructor for the Jakarta Persistence provider.
     *
     * <p>The provider calls it while materialising a row, then assigns all six fields.
     * Application code calls
     * {@link #NotificationLogEntity(UUID, String, String, String, String, Instant)}.</p>
     */
    protected NotificationLogEntity() {
    }

    /**
     * Records one delivery attempt.
     *
     * @param id identifier of this attempt
     * @param cardToken the card token, exactly {@value #CARD_TOKEN_LENGTH} lower-case hexadecimal
     *        characters
     * @param maskedCardNumber the display card number: twelve mask characters then four digits
     * @param transactionId the transaction identifier, exactly {@value #TRANSACTION_ID_LENGTH}
     *        characters
     * @param channel which rendered format the attempt carried, up to
     *        {@value #CHANNEL_MAX_LENGTH} characters
     * @param attemptedAt instant at which the attempt ran
     * @throws NullPointerException when an argument is {@code null}, naming the field
     * @throws IllegalArgumentException when the card token is not the token form, when the card
     *         number is not masked, when the transaction identifier is not exactly
     *         {@value #TRANSACTION_ID_LENGTH} characters, or when the channel is blank or over
     *         {@value #CHANNEL_MAX_LENGTH} characters
     */
    public NotificationLogEntity(UUID id, String cardToken, String maskedCardNumber,
            String transactionId, String channel, Instant attemptedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.cardToken = requireCardToken(cardToken);
        this.maskedCardNumber = requireMaskedCardNumber(maskedCardNumber);
        this.transactionId = requireTransactionId(transactionId);
        this.channel = requireChannel(channel);
        this.attemptedAt = Objects.requireNonNull(attemptedAt, "attemptedAt must not be null");
    }

    /**
     * Checks a card token against the shape the column holds.
     *
     * <p>The message reports the width of the argument and never the argument.</p>
     *
     * @param cardToken the argument
     * @return the argument
     * @throws NullPointerException when {@code cardToken} is {@code null}
     * @throws IllegalArgumentException when {@code cardToken} is not
     *         {@value #CARD_TOKEN_LENGTH} lower-case hexadecimal characters
     */
    private static String requireCardToken(String cardToken) {
        Objects.requireNonNull(cardToken, "cardToken must not be null");
        if (!CARD_TOKEN.matcher(cardToken).matches()) {
            throw new IllegalArgumentException("cardToken holds " + cardToken.length()
                    + " characters and this column holds " + CARD_TOKEN_LENGTH
                    + " lower-case hexadecimal characters");
        }
        return cardToken;
    }

    /**
     * Checks the argument against the masked shape the column holds.
     *
     * @param maskedCardNumber the argument
     * @return the argument
     * @throws NullPointerException when {@code maskedCardNumber} is {@code null}
     * @throws IllegalArgumentException when {@code maskedCardNumber} is not twelve mask characters
     *         followed by four digits
     */
    private static String requireMaskedCardNumber(String maskedCardNumber) {
        Objects.requireNonNull(maskedCardNumber, "maskedCardNumber must not be null");
        if (!MASKED_CARD_NUMBER.matcher(maskedCardNumber).matches()) {
            throw new IllegalArgumentException("maskedCardNumber holds "
                    + maskedCardNumber.length()
                    + " characters and this column holds " + CARD_NUMBER_LENGTH
                    + " in the masked form");
        }
        return maskedCardNumber;
    }

    /**
     * Checks a transaction identifier against the width the column holds.
     *
     * @param transactionId the argument
     * @return the argument
     * @throws NullPointerException when {@code transactionId} is {@code null}
     * @throws IllegalArgumentException when {@code transactionId} is not exactly
     *         {@value #TRANSACTION_ID_LENGTH} characters
     */
    private static String requireTransactionId(String transactionId) {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        if (transactionId.length() != TRANSACTION_ID_LENGTH) {
            throw new IllegalArgumentException("transactionId holds " + transactionId.length()
                    + " characters and this column holds " + TRANSACTION_ID_LENGTH);
        }
        return transactionId;
    }

    /**
     * Checks a channel name against the width the column holds.
     *
     * @param channel the argument
     * @return the argument
     * @throws NullPointerException when {@code channel} is {@code null}
     * @throws IllegalArgumentException when {@code channel} is blank, or over
     *         {@value #CHANNEL_MAX_LENGTH} characters
     */
    private static String requireChannel(String channel) {
        Objects.requireNonNull(channel, "channel must not be null");
        if (channel.isBlank()) {
            throw new IllegalArgumentException("channel names no rendered format");
        }
        if (channel.length() > CHANNEL_MAX_LENGTH) {
            throw new IllegalArgumentException("channel holds " + channel.length()
                    + " characters and this column holds up to " + CHANNEL_MAX_LENGTH);
        }
        return channel;
    }

    /** @return the identifier of this delivery attempt */
    public UUID getId() {
        return id;
    }

    /** @return the token of the card this attempt alerted */
    public String getCardToken() {
        return cardToken;
    }

    /** @return the masked card number this attempt displayed, which identifies no single card */
    public String getMaskedCardNumber() {
        return maskedCardNumber;
    }

    /** @return the transaction identifier this attempt alerted on */
    public String getTransactionId() {
        return transactionId;
    }

    /** @return which rendered format this attempt carried */
    public String getChannel() {
        return channel;
    }

    /** @return the instant at which the attempt ran */
    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    /**
     * Compares two attempts by {@code id} alone, which is the whole primary key.
     *
     * @param other the object to compare with this attempt
     * @return {@code true} when {@code other} is an attempt whose {@code id} equals this one
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof NotificationLogEntity that)) {
            return false;
        }
        return Objects.equals(id, that.id);
    }

    /**
     * Derives the hash code from {@code id} alone, matching {@link #equals(Object)}.
     *
     * @return the hash code of the identifier, and zero while the identifier is unset
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    /**
     * Renders all six fields. The card number is already masked, the card token carries no digit of
     * a card number, and no field names a cardholder, an account or an amount.
     *
     * @return the simple class name followed by the six field values
     */
    @Override
    public String toString() {
        return "NotificationLogEntity[id=" + id
                + ", cardToken=" + cardToken
                + ", maskedCardNumber=" + maskedCardNumber
                + ", transactionId=" + transactionId
                + ", channel=" + channel
                + ", attemptedAt=" + attemptedAt + "]";
    }
}
