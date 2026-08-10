package com.carddemo.fraud.entity;

import com.carddemo.cobol.PicClause;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.FraudFlagged;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.domain.Persistable;
import tools.jackson.databind.ObjectMapper;

/**
 * One risk assessment of one authorized transaction, held in the table {@code fraud_assessment} and
 * keyed by the transaction identifier.
 *
 * <p>No COBOL (Common Business Oriented Language) program scores risk. This table
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
 *   <li>{@code account_id CHAR(11) NOT NULL}. Leads the index
 *       {@code ix_fraud_assessment_account_cursor}, which continues with {@code assessed_at DESC}
 *       and {@code transaction_id DESC} so one account's page is walked from a named position in a
 *       total order. Shape only from {@code app/cpy/CVACT03Y.cpy:L7}.</li>
 *   <li>{@code risk_score INTEGER NOT NULL}. A whole number from 0 through 100 inclusive, and not
 *       a monetary value.</li>
 *   <li>{@code flagged BOOLEAN NOT NULL}. True when the score reached the configured threshold.</li>
 *   <li>{@code triggered_rules VARCHAR(64) NOT NULL}. The rule identifiers in evaluation order, as
 *       a JavaScript Object Notation (JSON) array. An assessment that triggered no rule stores
 *       {@code []}.</li>
 *   <li>{@code assessed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL}. The time the rules finished, in
 *       Coordinated Universal Time.</li>
 * </ul>
 *
 * <p>{@link TriggeredRuleListConverter} maps {@code triggered_rules} onto a list of identifiers and
 * keeps the order it reads. The permitted identifiers are {@link FraudFlagged#RULE_IDENTIFIERS},
 * which the published contract and the schema document {@code fraud-flagged-v1.json} both
 * enumerate. A rule may contribute points while the total remains below the configured threshold,
 * so a cleared row may retain rules. A flagged row must name at least one rule, which the migrated
 * schema enforces.</p>
 *
 * <p>The constructor checks the value-local invariants, and the migrated schema repeats the score
 * and rule-list constraints. The database additionally refuses a flagged row with no rule and a
 * stored array that repeats a rule. The exact score threshold depends on deployment configuration
 * and therefore cannot be recomputed by a static database constraint.</p>
 *
 * <ul>
 *   <li>{@code riskScore} falls from {@link FraudFlagged#MINIMUM_RISK_SCORE} through
 *       {@link FraudFlagged#MAXIMUM_RISK_SCORE}.</li>
 *   <li>{@code triggeredRules} names only known rules, repeats none, and serializes within the
 *       column width.</li>
 *   <li>{@code transactionId} and {@code accountId} hold the exact width of their columns.</li>
 * </ul>
 *
 * <p>The Jakarta Persistence API (JPA) provider validates this mapping against the migrated schema
 * and creates no schema object, so a column name or column type that differs stops start-up.</p>
 *
 * <p><b>Insert only.</b> {@link #isNew()} answers {@code true} for every instance, so a store
 * inserts and never updates. One transaction carries one verdict: a second store of a transaction
 * identifier the table already holds raises a primary-key violation and rolls the whole delivery
 * back, so the first verdict stands and no event announces a verdict the table no longer holds.</p>
 */
@Entity
@Table(
    name = "fraud_assessment",
    indexes = {
        @Index(name = "ix_fraud_assessment_account_cursor",
                columnList = "account_id, assessed_at DESC, transaction_id DESC"),
        @Index(name = "ix_fraud_assessment_assessed_at", columnList = "assessed_at")
    }
)
public class FraudAssessmentEntity implements Persistable<String> {

    /**
     * Width of {@code triggered_rules}, which holds every identifier of
     * {@link FraudFlagged#RULE_IDENTIFIERS} as a JSON array in 49 characters.
     */
    private static final int TRIGGERED_RULES_WIDTH = 64;

    /** Stored form of an assessment that triggered no rule, an empty JSON array. */
    private static final String NO_RULES = "[]";

    /**
     * Reads and writes {@code triggered_rules}.
     *
     * <p>A JSON array carries a separator inside a quoted string and never between two values, so a
     * rule identifier holding punctuation survives a round trip. Joining on a comma does not: the
     * identifier {@code A,B} is stored and read back as the two identifiers {@code A} and
     * {@code B}. The mapper holds no mutable state after construction, so one instance serves every
     * call.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Identifier of the assessed transaction, sixteen characters.
     *
     * <p>The column is {@code CHAR(16)}. PostgreSQL reports that type as {@code bpchar} through
     * Java Database Connectivity (JDBC) metadata, and {@link Column#columnDefinition()} names it
     * verbatim so the start-up check matches.</p>
     */
    @Id
    @Column(name = "transaction_id", length = PicClause.TRAN_ID_WIDTH, nullable = false,
            updatable = false, columnDefinition = "bpchar(" + PicClause.TRAN_ID_WIDTH + ")")
    private String transactionId;

    /**
     * Account the transaction belongs to, eleven characters with leading zeros kept.
     *
     * <p>The column is {@code CHAR(11)}, and {@link Column#columnDefinition()} names it
     * {@code bpchar} the same way.</p>
     */
    @Column(name = "account_id", length = PicClause.XREF_ACCT_ID_WIDTH, nullable = false,
            updatable = false,
            columnDefinition = "bpchar(" + PicClause.XREF_ACCT_ID_WIDTH + ")")
    private String accountId;

    /** Score the risk rules produced, from 0 through 100 inclusive. */
    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    /** Whether the score reached the configured flag threshold. */
    @Column(name = "flagged", nullable = false)
    private boolean flagged;

    /** Identifiers of the rules that triggered, in evaluation order, empty when none did. */
    @Convert(converter = FraudAssessmentEntity.TriggeredRuleListConverter.class)
    @Column(name = "triggered_rules", length = TRIGGERED_RULES_WIDTH, nullable = false)
    private List<String> triggeredRules = List.of();

    /** Time the risk rules finished, in Coordinated Universal Time. */
    @Column(name = "assessed_at", nullable = false)
    private Instant assessedAt;

    protected FraudAssessmentEntity() {
    }

    /**
     * Builds one assessment row.
     *
     * @param transactionId  identifier of the assessed transaction, sixteen characters
     * @param accountId      account the transaction belongs to, eleven characters
     * @param riskScore      score the risk rules produced, from 0 through 100 inclusive
     * @param flagged        whether the score reached the configured flag threshold
     * @param triggeredRules identifiers of the rules that triggered, in evaluation order, empty
     *                       when none did
     * @param assessedAt     time the risk rules finished
     * @throws NullPointerException     if {@code transactionId}, {@code accountId},
     *                                  {@code triggeredRules}, {@code assessedAt} or any element of
     *                                  {@code triggeredRules} is {@code null}
     * @throws IllegalArgumentException if an identifier holds the wrong width or a non-digit, if
     *                                  {@code riskScore} falls outside
     *                                  {@link FraudFlagged#MINIMUM_RISK_SCORE} through
     *                                  {@link FraudFlagged#MAXIMUM_RISK_SCORE}, if
     *                                  {@code triggeredRules} names an unknown rule or repeats one,
     *                                  if the serialized rule list exceeds its column
     */
    public FraudAssessmentEntity(String transactionId, String accountId, int riskScore,
            boolean flagged, List<String> triggeredRules, Instant assessedAt) {
        this.transactionId = requireTransactionId(transactionId);
        this.accountId = requireAccountId(accountId);
        this.riskScore = requireRiskScore(riskScore);
        this.triggeredRules = requireTriggeredRules(triggeredRules);
        this.flagged = flagged;
        this.assessedAt = Objects.requireNonNull(assessedAt, "assessedAt must not be null");
    }

    /**
     * Checks one transaction identifier against the width of {@code CHAR(16)}.
     *
     * @param value candidate identifier
     * @return {@code value}
     * @throws NullPointerException     if {@code value} is null
     * @throws IllegalArgumentException if the width differs. The failure text names the length,
     *                                  never the identifier
     */
    private static String requireTransactionId(String value) {
        Objects.requireNonNull(value, "transactionId must not be null");
        if (value.length() != PicClause.TRAN_ID_WIDTH) {
            throw new IllegalArgumentException("transactionId holds " + PicClause.TRAN_ID_WIDTH
                    + " characters; the value supplied holds " + value.length());
        }
        return value;
    }

    /**
     * Checks one account identifier against the width and the digits of {@code CHAR(11)}.
     *
     * @param value candidate identifier
     * @return {@code value}
     * @throws NullPointerException     if {@code value} is null
     * @throws IllegalArgumentException if {@code value} is not eleven digits. The failure text names
     *                                  the length or the position, never the identifier
     */
    private static String requireAccountId(String value) {
        Objects.requireNonNull(value, "accountId must not be null");
        if (value.length() != PicClause.XREF_ACCT_ID_WIDTH) {
            throw new IllegalArgumentException("accountId holds " + PicClause.XREF_ACCT_ID_WIDTH
                    + " characters; the value supplied holds " + value.length());
        }
        for (int position = 0; position < value.length(); position++) {
            char character = value.charAt(position);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException("accountId holds digits only; the value supplied"
                        + " carries something else at position " + position);
            }
        }
        return value;
    }

    /**
     * Checks one score against the bounds the published contract and the schema document declare.
     *
     * @param value candidate score
     * @return {@code value}
     * @throws IllegalArgumentException if {@code value} falls outside the bounds
     */
    private static int requireRiskScore(int value) {
        if (value < FraudFlagged.MINIMUM_RISK_SCORE || value > FraudFlagged.MAXIMUM_RISK_SCORE) {
            throw new IllegalArgumentException("riskScore falls from "
                    + FraudFlagged.MINIMUM_RISK_SCORE + " through "
                    + FraudFlagged.MAXIMUM_RISK_SCORE + "; the value supplied is " + value);
        }
        return value;
    }

    /**
     * Checks one rule list against the identifier set, against repetition, and against the column.
     *
     * <p>The list may be empty, which is the cleared verdict. Any entry it holds has to be a member
     * of {@link FraudFlagged#RULE_IDENTIFIERS}, which is what keeps a separator out of a stored
     * identifier.
     *
     * @param value candidate rule list
     * @return an immutable copy holding the order supplied
     * @throws NullPointerException     if {@code value} or any entry is null
     * @throws IllegalArgumentException if an entry is unknown, an entry repeats, or the serialized
     *                                  form exceeds {@link #TRIGGERED_RULES_WIDTH}
     */
    private static List<String> requireTriggeredRules(List<String> value) {
        Objects.requireNonNull(value, "triggeredRules must not be null");
        Set<String> named = new LinkedHashSet<>();
        for (String rule : value) {
            Objects.requireNonNull(rule, "triggeredRules must not hold a null rule");
            if (!FraudFlagged.RULE_IDENTIFIERS.contains(rule)) {
                throw new IllegalArgumentException("triggeredRules names only "
                        + FraudFlagged.RULE_IDENTIFIERS + "; the list supplied holds " + rule);
            }
            if (!named.add(rule)) {
                throw new IllegalArgumentException(
                        "triggeredRules holds no duplicate; the list supplied repeats " + rule);
            }
        }
        List<String> copy = List.copyOf(value);
        int width = TriggeredRuleListConverter.serialize(copy).length();
        if (width > TRIGGERED_RULES_WIDTH) {
            throw new IllegalArgumentException("triggeredRules serializes within "
                    + TRIGGERED_RULES_WIDTH + " characters; the list supplied serializes to "
                    + width);
        }
        return copy;
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
     * Returns the primary key of this row.
     *
     * @return identifier of the assessed transaction, sixteen characters
     */
    @Override
    public String getId() {
        return transactionId;
    }

    /**
     * Reports this row as new, always.
     *
     * <p>The identifier arrives from the assessed event and not from the database, so a provider
     * given a populated key would otherwise read the table and choose an update. This answer
     * removes that choice: every store is an insert.
     *
     * @return {@code true}
     */
    @Override
    @Transient
    public boolean isNew() {
        return true;
    }

    public String getAccountId() {
        return accountId;
    }

    public int getRiskScore() {
        return riskScore;
    }

    public boolean isFlagged() {
        return flagged;
    }

    public List<String> getTriggeredRules() {
        return List.copyOf(triggeredRules);
    }

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
     * Renders the key and withholds the account, the score and the verdict.
     *
     * <p>The transaction identifier stays, because it correlates this row with the event that
     * produced it. The account identifier, the risk score and the verdict appear as
     * {@link EventEnvelope#WITHHELD}, the platform-wide redaction marker: a score and a verdict
     * together describe how this platform rates a cardholder. No card number and no card
     * verification value reaches this text, since the row holds neither.
     *
     * @return one line naming the four fields and disclosing only the transaction identifier
     */
    @Override
    public String toString() {
        return "FraudAssessmentEntity[transactionId=" + transactionId
                + ", accountId=" + EventEnvelope.WITHHELD
                + ", riskScore=" + EventEnvelope.WITHHELD
                + ", flagged=" + EventEnvelope.WITHHELD
                + "]";
    }

    /**
     * Maps {@code triggered_rules} between a list of identifiers and one JSON array.
     *
     * <p>The converter keeps the order it reads. An empty list and a {@code null} list both store
     * {@code []}, and {@code []}, the empty string and a {@code null} value all read back as an
     * empty list. The list it returns is immutable.</p>
     *
     * <p>Values read from storage pass through {@link #requireTriggeredRules(List)}, so an unknown
     * or repeated identifier fails before it reaches the domain.</p>
     */
    public static class TriggeredRuleListConverter
            implements AttributeConverter<List<String>, String> {

        public TriggeredRuleListConverter() {
        }

        /**
         * Writes the identifiers as a JSON array, keeping their order.
         *
         * @param attribute identifiers of the rules that triggered, in evaluation order
         * @return the JSON array, or {@code []} when {@code attribute} is {@code null} or holds
         *         nothing
         */
        @Override
        public String convertToDatabaseColumn(List<String> attribute) {
            return serialize(attribute);
        }

        /**
         * Reads a JSON array of identifiers, keeping the order and every entry.
         *
         * @param dbData the stored value
         * @return identifiers of the rules that triggered, empty when {@code dbData} is
         *         {@code null}, empty or {@code []}
         * @throws IllegalArgumentException when {@code dbData} is not a JSON array of strings
         */
        @Override
        public List<String> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isEmpty() || NO_RULES.equals(dbData)) {
                return List.of();
            }
            String[] rules;
            try {
                rules = JSON.readValue(dbData, String[].class);
            } catch (RuntimeException malformed) {
                throw new IllegalArgumentException(
                        "triggered_rules holds a JSON array of strings; the stored value does not",
                        malformed);
            }
            List<String> read = new ArrayList<>(rules.length);
            for (String rule : rules) {
                read.add(Objects.requireNonNull(rule, "triggered_rules holds no null rule"));
            }
            return requireTriggeredRules(read);
        }

        /**
         * Writes one rule list as a JSON array.
         *
         * <p>{@link #requireTriggeredRules(List)} calls this to measure the stored width before it
         * accepts a list, so the measurement and the write cannot disagree.
         *
         * @param rules identifiers of the rules that triggered, which may be {@code null}
         * @return the JSON array, or {@code []} when {@code rules} is {@code null} or holds nothing
         */
        private static String serialize(List<String> rules) {
            if (rules == null || rules.isEmpty()) {
                return NO_RULES;
            }
            return JSON.writeValueAsString(rules);
        }
    }
}
