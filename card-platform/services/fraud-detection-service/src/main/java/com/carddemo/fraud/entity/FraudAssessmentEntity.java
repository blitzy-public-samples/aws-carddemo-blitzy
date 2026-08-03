package com.carddemo.fraud.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One risk assessment of one authorized transaction, held in the table {@code fraud_assessment} and
 * keyed by the transaction identifier.
 *
 * <p>ADDITIVE IN FULL. No COBOL (Common Business Oriented Language) program scores risk. This table
 * is net new; no COBOL ancestor exists.</p>
 *
 * <p>Two column widths are borrowed shape only. {@code transaction_id} takes its width from
 * {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5}, and {@code account_id} takes its
 * width from {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. Every other column
 * translates no source field.</p>
 *
 * <p>Column contract:</p>
 * <ul>
 *   <li>{@code transaction_id CHAR(16) NOT NULL}. The primary key {@code pk_fraud_assessment}.
 *       Shape only from {@code app/cpy/CVTRA05Y.cpy:L5}.</li>
 *   <li>{@code account_id CHAR(11) NOT NULL}. Carries the index
 *       {@code ix_fraud_assessment_account}. Shape only from {@code app/cpy/CVACT03Y.cpy:L7}.</li>
 *   <li>{@code risk_score INTEGER NOT NULL}. A whole number from 0 through 100 inclusive, and not
 *       a monetary value.</li>
 *   <li>{@code flagged BOOLEAN NOT NULL}. True when at least one rule triggered.</li>
 *   <li>{@code triggered_rules VARCHAR(64) NOT NULL}. The rule identifiers in evaluation order,
 *       comma separated, with no trailing separator.</li>
 *   <li>{@code assessed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL}. The time the rules finished, in
 *       Coordinated Universal Time.</li>
 * </ul>
 *
 * <p>{@link TriggeredRuleListConverter} maps {@code triggered_rules} onto a list of identifiers and
 * keeps the order it reads. The three identifiers the rules produce are {@code VELOCITY},
 * {@code AMOUNT_ANOMALY} and {@code MERCHANT_CATEGORY}. An assessment that triggered no rule
 * carries {@code flagged} false and an empty {@code triggered_rules} value, and publishes as
 * {@code FraudCleared}.</p>
 *
 * <p>The Jakarta Persistence API (JPA) provider validates this mapping against the migrated schema
 * and creates no schema object, so a column name or column type that differs stops start-up.</p>
 *
 * <p>Rationale for this entity: {@code card-platform/docs/decision-log.md}.</p>
 */
@Entity
@Table(
    name = "fraud_assessment",
    indexes = @Index(name = "ix_fraud_assessment_account", columnList = "account_id")
)
public class FraudAssessmentEntity {

    /** Text that separates two rule identifiers inside {@code triggered_rules}. */
    private static final String RULE_SEPARATOR = ",";

    /** Stored form of an assessment that triggered no rule. */
    private static final String NO_RULES = "";

    /**
     * Identifier of the assessed transaction, sixteen characters.
     *
     * <p>The column is {@code CHAR(16)}. PostgreSQL reports that type as {@code bpchar} through
     * Java Database Connectivity (JDBC) metadata, and {@link Column#columnDefinition()} names it
     * verbatim so the start-up check matches.</p>
     */
    @Id
    @Column(name = "transaction_id", length = 16, nullable = false, updatable = false,
            columnDefinition = "bpchar(16)")
    private String transactionId;

    /**
     * Account the transaction belongs to, eleven characters with leading zeros kept.
     *
     * <p>The column is {@code CHAR(11)}, and {@link Column#columnDefinition()} names it
     * {@code bpchar} the same way.</p>
     */
    @Column(name = "account_id", length = 11, nullable = false, updatable = false,
            columnDefinition = "bpchar(11)")
    private String accountId;

    /** Score the risk rules produced, from 0 through 100 inclusive. */
    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    /** Whether at least one rule triggered. */
    @Column(name = "flagged", nullable = false)
    private boolean flagged;

    /** Identifiers of the rules that triggered, in evaluation order, empty when none did. */
    @Convert(converter = FraudAssessmentEntity.TriggeredRuleListConverter.class)
    @Column(name = "triggered_rules", length = 64, nullable = false)
    private List<String> triggeredRules = List.of();

    /** Time the risk rules finished, in Coordinated Universal Time. */
    @Column(name = "assessed_at", nullable = false)
    private Instant assessedAt;

    /** Required by the persistence provider. */
    protected FraudAssessmentEntity() {
    }

    /**
     * Builds one assessment row.
     *
     * @param transactionId  identifier of the assessed transaction, sixteen characters
     * @param accountId      account the transaction belongs to, eleven characters
     * @param riskScore      score the risk rules produced, from 0 through 100 inclusive
     * @param flagged        whether at least one rule triggered
     * @param triggeredRules identifiers of the rules that triggered, in evaluation order, empty
     *                       when none did
     * @param assessedAt     time the risk rules finished
     * @throws NullPointerException if {@code transactionId}, {@code accountId},
     *                              {@code triggeredRules}, {@code assessedAt} or any element of
     *                              {@code triggeredRules} is {@code null}
     */
    public FraudAssessmentEntity(String transactionId, String accountId, int riskScore,
            boolean flagged, List<String> triggeredRules, Instant assessedAt) {
        this.transactionId =
                Objects.requireNonNull(transactionId, "transactionId must not be null");
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.riskScore = riskScore;
        this.flagged = flagged;
        this.triggeredRules = List.copyOf(
                Objects.requireNonNull(triggeredRules, "triggeredRules must not be null"));
        this.assessedAt = Objects.requireNonNull(assessedAt, "assessedAt must not be null");
    }

    /**
     * Returns the transaction this row assesses.
     *
     * @return identifier of the assessed transaction, sixteen characters
     */
    public String getTransactionId() {
        return transactionId;
    }

    /**
     * Returns the account the assessed transaction belongs to.
     *
     * @return account identifier, eleven characters with leading zeros kept
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Returns the score the risk rules produced, where a higher number means more risk.
     *
     * @return score from 0 through 100 inclusive
     */
    public int getRiskScore() {
        return riskScore;
    }

    /**
     * Reports whether at least one rule triggered.
     *
     * @return {@code true} when at least one rule triggered
     */
    public boolean isFlagged() {
        return flagged;
    }

    /**
     * Returns the rules that triggered, in the order the rules ran. The caller receives a copy and
     * cannot change this row through it.
     *
     * @return identifiers of the rules that triggered, empty when none did
     */
    public List<String> getTriggeredRules() {
        return List.copyOf(triggeredRules);
    }

    /**
     * Returns the time the risk rules finished.
     *
     * @return assessment time, in Coordinated Universal Time
     */
    public Instant getAssessedAt() {
        return assessedAt;
    }

    /**
     * Compares two rows on {@code transaction_id}, the primary key, and on nothing else.
     *
     * @param other the object to compare against
     * @return {@code true} when {@code other} is an assessment of the same transaction
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FraudAssessmentEntity that)) {
            return false;
        }
        return Objects.equals(transactionId, that.transactionId);
    }

    /**
     * Hashes {@code transaction_id}, the primary key, and nothing else.
     *
     * @return hash of the transaction identifier
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(transactionId);
    }

    /**
     * Renders the key, the account, the score and the verdict. No card number and no card
     * verification value reaches this text, since the row holds neither.
     *
     * @return one line naming the four fields
     */
    @Override
    public String toString() {
        return "FraudAssessmentEntity[transactionId=" + transactionId
                + ", accountId=" + accountId
                + ", riskScore=" + riskScore
                + ", flagged=" + flagged
                + "]";
    }

    /**
     * Maps {@code triggered_rules} between a list of identifiers and one comma-separated value.
     *
     * <p>The converter keeps the order it reads and keeps every entry, so a repeated identifier
     * survives a round trip. An empty list and a {@code null} list both store the empty string, and
     * the empty string and a {@code null} value both read back as an empty list. The list it
     * returns is immutable.</p>
     */
    public static class TriggeredRuleListConverter
            implements AttributeConverter<List<String>, String> {

        /** Required by the persistence provider. */
        public TriggeredRuleListConverter() {
        }

        /**
         * Joins the identifiers with a comma, keeping their order and adding no trailing separator.
         *
         * @param attribute identifiers of the rules that triggered, in evaluation order
         * @return the joined identifiers, or the empty string when {@code attribute} is
         *         {@code null} or holds nothing
         */
        @Override
        public String convertToDatabaseColumn(List<String> attribute) {
            if (attribute == null || attribute.isEmpty()) {
                return NO_RULES;
            }
            return String.join(RULE_SEPARATOR, attribute);
        }

        /**
         * Splits the stored value on each comma, keeping the order and every entry. The empty
         * string yields an empty list, never a list holding one empty identifier.
         *
         * @param dbData the stored value
         * @return identifiers of the rules that triggered, empty when {@code dbData} is
         *         {@code null} or empty
         */
        @Override
        public List<String> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isEmpty()) {
                return List.of();
            }
            return List.of(dbData.split(RULE_SEPARATOR, -1));
        }
    }
}
