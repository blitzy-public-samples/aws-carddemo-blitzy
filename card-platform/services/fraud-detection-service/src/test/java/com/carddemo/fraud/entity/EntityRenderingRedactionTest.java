package com.carddemo.fraud.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.events.EventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Asserts that no rendering of a fraud row carries a risk score or a spending counter.
 *
 * <p>{@link Object#toString()} lands in a log line the moment code concatenates an entity into a
 * message. Two kinds of value are withheld here for two different reasons. The velocity counters
 * are how much one cardholder spent inside one window, which is spending behaviour rather than an
 * operational fact. The risk score is this service's whole output, and a log line carrying it
 * teaches a reader how close a transaction came to the threshold; a sequence of such lines maps the
 * threshold itself, which is the one thing a fraud model cannot afford to disclose.
 *
 * <p>The verdict is withheld along with the score. A cleared verdict beside a flagged one over a
 * sequence of log lines is the shape of the threshold, which is the one thing a fraud model cannot
 * afford to disclose. The account identifier is withheld too, as it is in every rendering on this
 * platform, because it is the key every event of that account carries. The transaction identifier
 * and the window times stay, so a row can be named and a stale window recognised, and every
 * withheld component still names its column so a reader can see the row is complete.
 *
 * <p>Every test runs in memory. None opens a database connection or contacts a broker.
 */
@DisplayName("fraud row renderings carry no risk score and no spending counter")
class EntityRenderingRedactionTest {

    /** Row one of {@code app/data/ASCII/acctdata.txt}, columns 1 through 11. */
    private static final String ACCOUNT_ID = "00000000001";

    /** Row one of {@code app/data/ASCII/dailytran.txt}, columns 1 through 16. */
    private static final String TRANSACTION_ID = "0000000000000001";

    /** A fixed instant, so no test depends on the clock. */
    private static final Instant AT = Instant.parse("2031-08-31T10:11:12Z");

    @Nested
    @DisplayName("The assessment withholds the score and the verdict")
    class AssessmentRendering {

        @Test
        @DisplayName("the score and the verdict stay out, the transaction identifier stays in")
        void theScoreStaysOut() {
            String rendered = new FraudAssessmentEntity(TRANSACTION_ID, ACCOUNT_ID, 87, true,
                    List.of("VELOCITY", "AMOUNT_ANOMALY"), AT).toString();

            assertFalse(rendered.contains("87"),
                    "the rendering carries the risk score: " + rendered);
            assertTrue(rendered.contains("riskScore=" + EventEnvelope.WITHHELD),
                    "the withheld score still names its column: " + rendered);
            assertTrue(rendered.contains("flagged=" + EventEnvelope.WITHHELD),
                    "a cleared verdict beside a flagged one maps the threshold over a sequence of"
                            + " log lines, so the verdict names its column and withholds its value: "
                            + rendered);
            assertTrue(rendered.contains("transactionId=" + TRANSACTION_ID),
                    "the transaction identifier is kept, so a row is nameable: " + rendered);
            assertTrue(rendered.contains("accountId=" + EventEnvelope.WITHHELD),
                    "the account identifier is the key every event of this account carries: "
                            + rendered);
        }

        @Test
        @DisplayName("a score of zero is withheld too, and the triggered rules never render")
        void aZeroScoreAndTheRuleListStayOut() {
            // A cleared row names no rule: the entity refuses a verdict that disagrees with its
            // rule list, so a cleared assessment carries an empty list.
            String rendered = new FraudAssessmentEntity(TRANSACTION_ID, ACCOUNT_ID, 0, false,
                    List.of(), AT).toString();

            assertFalse(rendered.contains("riskScore=0"),
                    "a rendering that printed zero would locate the cleared end of the scale: "
                            + rendered);
            assertFalse(rendered.contains("flagged=false"),
                    "a cleared verdict locates the cleared end of the scale as surely as a zero"
                            + " score does: " + rendered);
            assertFalse(rendered.contains("VELOCITY"),
                    "the triggered rule list names the model's own rules and never renders: "
                            + rendered);
            assertTrue(rendered.contains("flagged=" + EventEnvelope.WITHHELD),
                    "the withheld verdict still names its column: " + rendered);
        }
    }

    @Nested
    @DisplayName("The velocity window withholds both counters")
    class VelocityWindowRendering {

        @Test
        @DisplayName("the count and the total stay out, the key and the update time stay in")
        void bothCountersStayOut() {
            String rendered = new VelocityWindowEntity(ACCOUNT_ID, AT, 7,
                    new BigDecimal("1234.56"), AT.plusSeconds(60)).toString();

            assertFalse(rendered.contains("authorizationCount=7"),
                    "the rendering carries the authorization count: " + rendered);
            assertFalse(rendered.contains("1234.56"),
                    "the rendering carries the window total: " + rendered);
            assertTrue(rendered.contains("authorizationCount=" + EventEnvelope.WITHHELD)
                            && rendered.contains("totalAmount=" + EventEnvelope.WITHHELD),
                    "each withheld counter still names its column: " + rendered);
            assertTrue(rendered.contains("accountId=" + EventEnvelope.WITHHELD),
                    "the account identifier is withheld, and still names its column: " + rendered);
            assertTrue(rendered.contains("windowStart=" + AT),
                    "the window start is kept, so a stale window is recognisable: " + rendered);
            assertTrue(rendered.contains("updatedAt=" + AT.plusSeconds(60)),
                    "the rendering keeps the update time, so a stale window is recognisable: "
                            + rendered);
        }

        @Test
        @DisplayName("the key's own rendering carries the key and nothing else")
        void theKeyRendersItsTwoColumns() {
            String rendered = new VelocityWindowEntity.VelocityWindowId(ACCOUNT_ID, AT).toString();

            assertTrue(rendered.contains("accountId=" + EventEnvelope.WITHHELD)
                            && rendered.contains("windowStart=" + AT),
                    "the key names both of its columns and withholds the account identifier: "
                            + rendered);
            assertFalse(rendered.contains("totalAmount") || rendered.contains("Count"),
                    "the key carries no counter to withhold: " + rendered);
        }
    }
}
