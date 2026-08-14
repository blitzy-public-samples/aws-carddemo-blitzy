package com.carddemo.notification.entity;

import com.carddemo.cobol.PanMasker;
import com.carddemo.events.EventEnvelope;
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
 * Records one cardholder alert this service rendered and did not send.
 *
 * <p>One instance maps to one row of the table {@code notification_log}, whose seven columns this
 * module declares in {@code src/main/resources/db/migration/V1__schema.sql} and
 * {@code V5__rendered_not_delivered.sql}. The primary key
 * {@code pk_notification_log} covers {@code id} alone. The check constraint
 * {@code ck_notification_log_card_token} holds {@code card_token} to the token shape and
 * {@code ck_notification_log_card_number} holds {@code masked_card_number} to the masked shape.
 * That constraint is named for the column's earlier name and the column it checks is
 * {@code masked_card_number}; the name is the identifier the migration created and is left as it
 * is. A row names no statement row: the migration declares no foreign key.
 *
 * <p>Nothing here is evidence that a cardholder was told anything. This service reaches no mail,
 * message, webhook or push gateway; its own README lists all four under deliberate non-additions.
 * {@link #getOutcome()} returns {@link #RENDERED_NOT_SENT} and the constructor offers no way to
 * write another value, and {@code ck_notification_log_outcome} refuses one written any other way.
 * {@code V5__rendered_not_delivered.sql} is the migration that puts that fact in the column and the
 * constraint, where reading a row as a delivery would claim a transport that has never existed.
 *
 * <p>No COBOL ancestor: this class has no source ancestor. No Common Business Oriented Language
 * (COBOL) program in CardDemo records a rendered alert. Every locator below is a reference.
 *
 * <p>{@code channel} names which rendered format one row carried. The source renders two: the
 * text layout {@code 01 STATEMENT-LINES.} at {@code app/cbl/CBSTM03A.CBL:L85}, and the markup
 * layout {@code 01 HTML-LINES.} at {@code app/cbl/CBSTM03A.CBL:L148}. Those two reach two
 * datasets: {@code STMTFILE} at {@code LRECL=80} in {@code app/jcl/CREASTMT.JCL:L89}, and
 * {@code HTMLFILE} at {@code LRECL=100} in {@code app/jcl/CREASTMT.JCL:L94}. The classes
 * {@code PlainTextRenderer} and {@code HtmlRenderer} produce them.
 *
 * <p>Two columns identify the card, and neither holds a full one. {@code card_token} carries the
 * stable opaque identifier {@link PanMasker#cardToken(String)} derives, and it is the value
 * {@code ix_notification_log_card_token} reads one card's rows by. {@code masked_card_number} carries
 * the masked form for display. Both are additions, and the source masks nothing: the card number
 * occupies all sixteen characters on the card detail map, where {@code CARDSID DFHMDF} carries
 * {@code LENGTH=16} at {@code app/bms/COCRDSL.bms:L99}. No column, field or accessor here carries a
 * card verification value.
 *
 * <p>The caller supplies five values, {@code id} and {@code renderedAt} among them. The sixth is
 * {@link #RENDERED_NOT_SENT}, which this class writes itself.
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
     * The one outcome a row of this table records: the alert was rendered and was not sent.
     *
     * <p>{@code ck_notification_log_outcome} in
     * {@code src/main/resources/db/migration/V5__rendered_not_delivered.sql} permits this value and
     * no other, and the constructor writes it rather than accepting it, so no caller can record a
     * delivery this platform does not perform. A deployment that adds a transport widens the
     * constraint and this class together, in a commit a reviewer can see.</p>
     */
    public static final String RENDERED_NOT_SENT = "RENDERED_NOT_SENT";

    /** Widest value {@code outcome VARCHAR(20)} holds. */
    public static final int OUTCOME_MAX_LENGTH = 20;

    /**
     * Identifier of this rendered alert, assigned by the caller.
     *
     * <p>Maps to {@code id UUID NOT NULL}, which carries the primary key
     * {@code pk_notification_log}.</p>
     */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * The card this alert covered, named by its token.
     *
     * <p>Maps to {@code card_token NOT NULL}. The index
     * {@code ix_notification_log_card_token} reads the rendered history of one card through this
     * column, so it and not {@code masked_card_number} is the identity a query names.</p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_token", nullable = false, length = CARD_TOKEN_LENGTH)
    private String cardToken;

    /**
     * The card this alert covered, held as twelve mask characters then the last four digits.
     *
     * <p>Maps to {@code masked_card_number CHAR(16) NOT NULL}. PostgreSQL pads a {@code CHAR}
     * value to its declared width on read, and a masked card number already fills all
     * {@value #CARD_NUMBER_LENGTH} characters. Display only: every card ending in the same four
     * digits shares one masked form, so no query names this column.</p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "masked_card_number", nullable = false, length = CARD_NUMBER_LENGTH)
    private String maskedCardNumber;

    /**
     * The transaction this alert reported.
     *
     * <p>Maps to {@code transaction_id CHAR(16) NOT NULL}. The source field
     * {@code TRNX-ID PIC X(16)} at {@code app/cpy/COSTM01.CPY:L23} is alphanumeric, so a value
     * here carries any {@value #TRANSACTION_ID_LENGTH} characters.</p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "transaction_id", nullable = false, length = TRANSACTION_ID_LENGTH)
    private String transactionId;

    /**
     * Which rendered format this alert carried.
     *
     * <p>Maps to {@code channel VARCHAR(20) NOT NULL}. The column is {@code VARCHAR}, and this
     * field carries the default character mapping: no {@code JdbcTypeCode} annotation. The
     * migration constrains the value set no further, and neither does this field.</p>
     */
    @Column(name = "channel", nullable = false, length = CHANNEL_MAX_LENGTH)
    private String channel;

    /**
     * Instant at which this service finished rendering the alert, supplied by the caller.
     *
     * <p>Maps to {@code rendered_at TIMESTAMP(6) WITH TIME ZONE NOT NULL}, named
     * {@code attempted_at} until {@code V5__rendered_not_delivered.sql}. The value never changed:
     * it has always been the renderer's own clock reading, and only the name claimed more.</p>
     */
    @Column(name = "rendered_at", nullable = false)
    private Instant renderedAt;

    /**
     * What became of the alert, which is always {@link #RENDERED_NOT_SENT}.
     *
     * <p>Maps to {@code outcome VARCHAR(20) NOT NULL}. The constructor assigns the constant and
     * takes no argument for it, so the value cannot be anything else without a code change.</p>
     */
    @Column(name = "outcome", nullable = false, length = OUTCOME_MAX_LENGTH)
    private String outcome;

    /**
     * No-argument constructor for the Jakarta Persistence provider.
     *
     * <p>The provider calls it while materialising a row, then assigns all seven fields.
     * Application code calls
     * {@link #NotificationLogEntity(UUID, String, String, String, String, Instant)}.</p>
     */
    protected NotificationLogEntity() {
    }

    /**
     * Records one rendered alert, with {@link #RENDERED_NOT_SENT} as its outcome.
     *
     * @param id identifier of this rendered alert
     * @param cardToken the card token, exactly {@value #CARD_TOKEN_LENGTH} lower-case hexadecimal
     *        characters
     * @param maskedCardNumber the display card number: twelve mask characters then four digits
     * @param transactionId the transaction identifier, exactly {@value #TRANSACTION_ID_LENGTH}
     *        characters
     * @param channel which rendered format the row carried, up to
     *        {@value #CHANNEL_MAX_LENGTH} characters
     * @param renderedAt instant at which rendering finished
     * @throws NullPointerException when an argument is {@code null}, naming the field
     * @throws IllegalArgumentException when the card token is not the token form, when the card
     *         number is not masked, when the transaction identifier is not exactly
     *         {@value #TRANSACTION_ID_LENGTH} characters, or when the channel is blank or over
     *         {@value #CHANNEL_MAX_LENGTH} characters
     */
    public NotificationLogEntity(UUID id, String cardToken, String maskedCardNumber,
            String transactionId, String channel, Instant renderedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.cardToken = requireCardToken(cardToken);
        this.maskedCardNumber = requireMaskedCardNumber(maskedCardNumber);
        this.transactionId = requireTransactionId(transactionId);
        this.channel = requireChannel(channel);
        this.renderedAt = Objects.requireNonNull(renderedAt, "renderedAt must not be null");
        this.outcome = RENDERED_NOT_SENT;
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

    /** @return the identifier of this rendered alert */
    public UUID getId() {
        return id;
    }

    /** @return the token of the card this alert covered */
    public String getCardToken() {
        return cardToken;
    }

    /** @return the masked card number this alert displayed, which identifies no single card */
    public String getMaskedCardNumber() {
        return maskedCardNumber;
    }

    /** @return the transaction identifier this alert reported */
    public String getTransactionId() {
        return transactionId;
    }

    /** @return which rendered format this alert carried */
    public String getChannel() {
        return channel;
    }

    /** @return the instant at which rendering finished */
    public Instant getRenderedAt() {
        return renderedAt;
    }

    /**
     * Returns what became of the alert, which is {@link #RENDERED_NOT_SENT} for every row.
     *
     * <p>A caller reading this value is reading a fact rather than a status: nothing on this
     * platform sends a cardholder alert, so no row reports anything else.</p>
     *
     * @return {@link #RENDERED_NOT_SENT}
     */
    public String getOutcome() {
        return outcome;
    }

    /**
     * Compares two rows by {@code id} alone, which is the whole primary key.
     *
     * @param other the object to compare with this row
     * @return {@code true} when {@code other} is a row whose {@code id} equals this one
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
     * Renders all seven fields, with the card token replaced by {@link EventEnvelope#WITHHELD}.
     *
     * <p>The token discloses no digit of a card number, and it names one card for as long as its
     * key stands. A log line carrying it would let any reader of that log follow that card across
     * every request that touched it, so this rendering reports the token as present and withholds
     * its value. A prefix would be equally linkable, so no prefix is rendered either. That is the
     * decision recorded under "Report a card token as present or withheld in a log rendering, never
     * in full" in {@code card-platform/docs/decision-log.md}, and
     * {@link EventEnvelope#WITHHELD} is the marker every record and entity of this platform uses
     * for it.</p>
     *
     * <p>Every other field is safe to render. The card number is already masked, and no field names
     * a cardholder, an account or an amount. An operator locates a row by its transaction
     * identifier.</p>
     *
     * @return the simple class name followed by the seven field values, the card token withheld
     */
    @Override
    public String toString() {
        return "NotificationLogEntity[id=" + id
                + ", cardToken=" + EventEnvelope.WITHHELD
                + ", maskedCardNumber=" + maskedCardNumber
                + ", transactionId=" + transactionId
                + ", channel=" + channel
                + ", renderedAt=" + renderedAt
                + ", outcome=" + outcome + "]";
    }
}
