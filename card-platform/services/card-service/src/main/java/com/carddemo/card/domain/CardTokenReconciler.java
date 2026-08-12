package com.carddemo.card.domain;

import com.carddemo.card.entity.CardEntity;
import com.carddemo.card.entity.CardTokenRotationEntity;
import com.carddemo.card.entity.CardTokenRotationMappingEntity;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.card.repository.CardTokenRotationMappingRepository;
import com.carddemo.card.repository.CardTokenRotationRepository;
import com.carddemo.cobol.PanMasker;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Brings every {@code card_token} into line with the card-token key the deployment supplied, before
 * the service accepts a request, and refuses to rotate one without being asked.
 *
 * <p>A card token is the platform identity of one card. It names the row a card history route reads,
 * it is the cursor of the card list, and it is the subject of a {@code SCOPE_CARD} authority. It is
 * also a keyed value: {@link PanMasker#cardToken(String)} takes an {@code HmacSHA256} code over the
 * card number under the configured key, so the token of one card under two keys is two values.
 *
 * <h2>Two reasons a stored token can differ from the derivation, and only one is routine</h2>
 *
 * <p>{@code V2__seed.sql} carries a checked-in token literal on each of its fifty rows, derived under
 * the build-scope key {@code card-platform/pom.xml} supplies, which is what lets a test compare a
 * literal against the derivation. A deployment supplies a key of its own - {@code .env.example} ships
 * a placeholder and this service refuses to start without a real value - so those fifty literals
 * arrive in the database belonging to a key the running service does not hold. Bringing them onto the
 * live key is a <strong>bootstrap</strong>. Those rows carry
 * {@code card_token_provenance = }{@value CardEntity#TOKEN_PROVENANCE_SEED}, and this class rewrites
 * them without being asked, because nothing outside this service can be holding one: the reconciliation
 * finishes before the readiness probe accepts traffic and before any event of this platform carries a
 * token derived here.
 *
 * <p>A row this deployment already derived carries
 * {@code card_token_provenance = }{@value CardEntity#TOKEN_PROVENANCE_DERIVED}. A difference there
 * means the key or the version moved under it, and rewriting it is a <strong>rotation</strong>. That
 * is not a consequence of a restart, because three other places hold the same token and hold no card
 * number: {@code statement_transaction.card_token} and {@code notification_log.card_token} in the
 * notification service, {@code authorization_decision.card_token} in the authorization service, and a
 * granted {@code SCOPE_CARD} authority. Rewriting such a row silently would leave all three naming a
 * card nobody could reach, so this class refuses to start unless
 * {@value PanMasker#CARD_TOKEN_ROTATION_VARIABLE} states that an operator asked for the rotation.
 *
 * <h2>What a rotation produces, so the rest of the platform can follow it</h2>
 *
 * <p>A rotation reads under two pairs. {@value PanMasker#CARD_TOKEN_PREVIOUS_SECRET_VARIABLE} carries
 * the key the stored tokens were taken under, so {@link PanMasker#previousCardToken(String)} derives
 * what a card was called while {@link PanMasker#cardToken(String)} derives what it is called now. Each
 * rewritten row therefore yields a mapping row, and the mapping is what the three stores above and the
 * granted authority are re-keyed from. Read in the other direction it is the rollback: put the
 * previous key back as the current one and the same rows map the other way.
 *
 * <p>A rotation also refuses to guess. Where the stored token equals neither the current derivation
 * nor the derivation under the configured previous key, the row's provenance cannot be established and
 * no mapping row can be written for it, so the run stops and names the row's position rather than
 * rewriting an identity it cannot account for.
 *
 * <p>One audit row records the run: when it ran, the version it moved from and to, how many rows it
 * read and rewrote, and the identity the process ran under. A bootstrap writes no audit row, because a
 * bootstrap moves nothing another store holds.
 *
 * <h2>When it runs, and what it costs</h2>
 *
 * <p>The reconciliation runs as an {@link ApplicationRunner}, which Spring Boot invokes after the
 * context has refreshed - so after Flyway has applied every migration - and before it publishes the
 * ready event that turns the readiness probe to accepting traffic. No request therefore reads a token
 * this class is about to change.
 *
 * <p>It is idempotent and bounded. A row whose stored token already equals the derivation is left
 * untouched, so a second start-up rewrites nothing and the ordinary case costs one read per page. A
 * page holds {@value #PAGE_SIZE} rows and one run reads at most {@value #ROW_CEILING}, which keeps one
 * statement's lock footprint small and stops a start-up that would otherwise walk an unexpectedly
 * large table.
 *
 * <p>No log line here carries a card number or a token. A run reports how many rows it read and how
 * many it rewrote, which is what an operator needs to tell a rotation from a bootstrap from a no-op.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}. The operator procedure, including
 * the rollback and the three re-key statements, is {@code card-platform/services/card-service/README.md}.
 */
@Component
public class CardTokenReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CardTokenReconciler.class);

    /**
     * Rows one page reads, and therefore rows one write transaction covers at most.
     *
     * <p>Paging keeps the work bounded in the same way {@code RetentionSweep} bounds a delete: an
     * unbounded pass over the table would hold a lock on every row it rewrote for the length of one
     * transaction.
     */
    static final int PAGE_SIZE = 500;

    /**
     * Rows one run reads at most.
     *
     * <p>The fixture loads fifty. The ceiling is the guard for a table that is not the fixture: a
     * start-up that has to rewrite more rows than this is a migration and not a start-up, and it says
     * so rather than delaying readiness without explanation.
     */
    static final int ROW_CEILING = 200_000;

    /** The identity recorded where the runtime reports no process owner. */
    static final String UNKNOWN_ACTOR = "unknown";

    /** Characters the audit row's actor column holds at most. */
    private static final int ACTOR_CEILING = 64;

    private final CardRepository cards;
    private final CardTokenRotationRepository rotations;
    private final CardTokenRotationMappingRepository mappings;
    private final TransactionTemplate transactionTemplate;

    /**
     * Supplies the two instants the audit row records. A field rather than a constructor argument,
     * matching {@code domain/CardCrossReferenceReconciler} and {@code outbox/OutboxWriter}: no
     * {@link Clock} bean is declared in this module, and the value this clock produces is a timestamp
     * column rather than anything a caller reads.
     */
    private final Clock clock = Clock.systemUTC();

    /**
     * Builds the reconciler.
     *
     * @param cards               the card repository, read a page at a time and updated by card number
     * @param rotations           the audit store one row per rotation is written to
     * @param mappings            the store the previous-to-current token mapping is written to
     * @param transactionTemplate the template each page's writes run inside
     */
    public CardTokenReconciler(CardRepository cards, CardTokenRotationRepository rotations,
            CardTokenRotationMappingRepository mappings,
            TransactionTemplate transactionTemplate) {
        this.cards = Objects.requireNonNull(cards, "cards");
        this.rotations = Objects.requireNonNull(rotations, "rotations");
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate");
    }

    /**
     * Runs the reconciliation once, at start-up.
     *
     * <p>A failure here is not swallowed. This service cannot serve a correct card token while the
     * stored values belong to another key, and a service that answers with the wrong identity for a
     * card is worse than one that does not start. A refusal to rotate is the same kind of failure: the
     * stored identity is intact and the operator has not said what should happen to it.
     *
     * @param arguments the application arguments, which this runner does not read
     */
    @Override
    public void run(ApplicationArguments arguments) {
        reconcile();
    }

    /**
     * Rewrites every stored token that does not equal the derivation under the configured key.
     *
     * @return how many rows were rewritten, bootstrap and rotation together
     * @throws IllegalStateException when no usable card-token key is configured, when a row of
     *                               {@value CardEntity#TOKEN_PROVENANCE_DERIVED} provenance has
     *                               drifted and no rotation was asked for, or when a drifted row's
     *                               stored token belongs to neither the current nor the configured
     *                               previous key
     */
    public int reconcile() {
        Instant startedAt = clock.instant();
        String currentVersion = PanMasker.cardTokenVersion();
        Rotation rotation = new Rotation(startedAt, currentVersion);

        String after = null;
        int read = 0;
        int bootstrapped = 0;
        int rotated = 0;

        while (read < ROW_CEILING) {
            List<CardEntity> page = after == null
                    ? cards.findFirstPage(Limit.of(PAGE_SIZE))
                    : cards.findPageAfter(after, Limit.of(PAGE_SIZE));
            if (page.isEmpty()) {
                break;
            }
            read += page.size();
            after = page.get(page.size() - 1).getCardNumber();

            List<Rewrite> rewrites = classify(page, read - page.size());
            bootstrapped += apply(rewrites, currentVersion, false, rotation);
            rotated += apply(rewrites, currentVersion, true, rotation);

            if (page.size() < PAGE_SIZE) {
                break;
            }
        }

        rotation.close(clock.instant(), read, rotated);
        report(read, bootstrapped, rotated, rotation);
        return bootstrapped + rotated;
    }

    /**
     * Decides what each row of one page needs, and refuses a rewrite it cannot account for.
     *
     * <p>The comparison happens here, outside any write transaction, so a page that needs no change
     * opens none. The returned list keeps the page's order, which is the primary-key order the update
     * statements then follow.
     *
     * @param page        one page of card rows
     * @param rowsBefore  how many rows the run read before this page, used to name a refused row's
     *                    position without naming its card
     * @return the rewrites this page needs, in page order
     * @throws IllegalStateException where a drifted row of
     *                               {@value CardEntity#TOKEN_PROVENANCE_DERIVED} provenance was found
     *                               and no rotation was asked for, or where a drifted row's stored
     *                               token belongs to neither key
     */
    private static List<Rewrite> classify(List<CardEntity> page, int rowsBefore) {
        List<Rewrite> rewrites = new ArrayList<>();
        for (int at = 0; at < page.size(); at++) {
            CardEntity card = page.get(at);
            String expected = PanMasker.cardToken(card.getCardNumber());
            if (expected.equals(card.getCardToken())) {
                continue;
            }
            if (CardEntity.TOKEN_PROVENANCE_SEED.equals(card.getCardTokenProvenance())) {
                rewrites.add(new Rewrite(card.getCardNumber(), card.getCardToken(), expected, false));
                continue;
            }
            rewrites.add(new Rewrite(card.getCardNumber(), card.getCardToken(), expected,
                    true, rotationOf(card, rowsBefore + at + 1)));
        }
        return rewrites;
    }

    /**
     * Establishes that one drifted derived row really is the rotation an operator asked for.
     *
     * @param card       the drifted row
     * @param rowOrdinal the one-based position of the row within the run, for a message that names no
     *                   card
     * @return the version the stored token was taken under
     * @throws IllegalStateException where no rotation was asked for, or where the stored token belongs
     *                               to neither the current nor the configured previous key
     */
    private static String rotationOf(CardEntity card, int rowOrdinal) {
        if (!PanMasker.cardTokenRotationRequested()) {
            throw new IllegalStateException("row " + rowOrdinal + " of the card table carries a card"
                    + " token this deployment derived under a key or version that is no longer"
                    + " configured, and rewriting it moves an identity three other stores and every"
                    + " granted SCOPE_CARD authority already name. Set "
                    + PanMasker.CARD_TOKEN_ROTATION_PROPERTY + " or "
                    + PanMasker.CARD_TOKEN_ROTATION_VARIABLE + " to true and supply "
                    + PanMasker.CARD_TOKEN_PREVIOUS_SECRET_VARIABLE + ", or restore the previous key"
                    + " as the current one. card-platform/services/card-service/README.md is the"
                    + " procedure.");
        }
        String previous = PanMasker.previousCardToken(card.getCardNumber());
        if (!previous.equals(card.getCardToken())) {
            throw new IllegalStateException("row " + rowOrdinal + " of the card table carries a card"
                    + " token that belongs to neither the configured key nor the configured previous"
                    + " key, so no mapping from it can be written and the rotation would move an"
                    + " identity it cannot account for. Supply the key those tokens were taken under"
                    + " in " + PanMasker.CARD_TOKEN_PREVIOUS_SECRET_VARIABLE + ".");
        }
        return PanMasker.previousCardTokenVersion();
    }

    /**
     * Applies one page's rewrites of one kind in a single transaction.
     *
     * <p>Each statement carries its own {@code AND card_token <> :cardToken} guard, so a row another
     * instance has already corrected counts as zero here rather than as a conflict. Two instances
     * starting together therefore converge on the same rows without coordinating.
     *
     * <p>A rotation writes its mapping row inside the same transaction as the rewrite it describes, so
     * a crash cannot leave a moved token with no mapping back.
     *
     * @param rewrites      the page's rewrites, of both kinds
     * @param version       the version the new tokens are taken under
     * @param rotationsOnly {@code true} to apply the rotation rewrites, {@code false} to apply the
     *                      bootstrap rewrites
     * @param rotation      the audit record, opened on the first rotation rewrite
     * @return how many rows the statements changed
     */
    private int apply(List<Rewrite> rewrites, String version, boolean rotationsOnly,
            Rotation rotation) {
        List<Rewrite> selected = rewrites.stream()
                .filter(rewrite -> rewrite.rotation() == rotationsOnly)
                .toList();
        if (selected.isEmpty()) {
            return 0;
        }
        if (rotationsOnly) {
            rotation.open(selected.getFirst().previousVersion());
        }
        Integer changed = transactionTemplate.execute(status -> {
            int applied = 0;
            for (Rewrite rewrite : selected) {
                applied += cards.reassignCardToken(rewrite.cardNumber(), rewrite.cardToken(),
                        version, CardEntity.TOKEN_PROVENANCE_DERIVED);
                if (rotationsOnly) {
                    mappings.save(new CardTokenRotationMappingEntity(rotation.identifier(),
                            rewrite.previousCardToken(), rewrite.cardToken(),
                            rewrite.previousVersion(), version));
                }
            }
            return applied;
        });
        return changed == null ? 0 : changed;
    }

    /**
     * Reports the run at the level its outcome deserves.
     *
     * @param read         rows the run read
     * @param bootstrapped seeded rows brought onto the deployment key
     * @param rotated      derived rows whose identity moved
     * @param rotation     the audit record, which names the run where one was opened
     */
    private static void report(int read, int bootstrapped, int rotated, Rotation rotation) {
        if (rotated > 0) {
            log.warn("Rotated {} of {} card tokens from version {} to version {}. Rotation {} holds"
                            + " the mapping the notification read model, the authorization decision"
                            + " diagnostics and every granted SCOPE_CARD authority are re-keyed from",
                    rotated, read, rotation.fromVersion(), rotation.toVersion(),
                    rotation.identifier());
        }
        if (bootstrapped > 0) {
            log.info("Brought {} of {} seeded card tokens onto the configured card-token key",
                    bootstrapped, read);
        }
        if (rotated == 0 && bootstrapped == 0) {
            log.debug("Card tokens already belong to the configured key across {} rows", read);
        }
    }

    /**
     * One row of the card table that has to be rewritten, and why.
     *
     * @param cardNumber        the row to rewrite
     * @param previousCardToken the token the row carries now
     * @param cardToken         the token the row should carry
     * @param rotation          {@code true} where the row was derived by this deployment, so the
     *                          rewrite moves an identity other stores hold
     * @param previousVersion   the version the stored token was taken under, which a bootstrap does
     *                          not establish and therefore leaves {@code null}
     */
    private record Rewrite(String cardNumber, String previousCardToken, String cardToken,
            boolean rotation, String previousVersion) {

        /**
         * Builds a bootstrap rewrite, which establishes no previous version.
         *
         * @param cardNumber        the row to rewrite
         * @param previousCardToken the token the row carries now
         * @param cardToken         the token the row should carry
         * @param rotation          {@code false}, since a bootstrap is not a rotation
         */
        private Rewrite(String cardNumber, String previousCardToken, String cardToken,
                boolean rotation) {
            this(cardNumber, previousCardToken, cardToken, rotation, null);
        }
    }

    /**
     * The audit record of one run, opened on the first rotation rewrite and closed at the end.
     *
     * <p>It is opened rather than written once because a mapping row references it and the counts are
     * not known until the last page is read. A run that rotates nothing opens nothing, so a bootstrap
     * and a no-op both leave the table as they found it.
     */
    private final class Rotation {

        private final UUID identifier = UUID.randomUUID();
        private final Instant startedAt;
        private final String toVersion;
        private String fromVersion;
        private CardTokenRotationEntity record;

        private Rotation(Instant startedAt, String toVersion) {
            this.startedAt = startedAt;
            this.toVersion = toVersion;
        }

        private UUID identifier() {
            return identifier;
        }

        private String fromVersion() {
            return fromVersion;
        }

        private String toVersion() {
            return toVersion;
        }

        /**
         * Writes the audit row, once, before the first rotation rewrite of the run.
         *
         * @param previousVersion the version the rewritten tokens were taken under
         */
        private void open(String previousVersion) {
            if (record != null) {
                return;
            }
            fromVersion = previousVersion;
            record = transactionTemplate.execute(status -> rotations.save(
                    new CardTokenRotationEntity(identifier, startedAt, startedAt, previousVersion,
                            toVersion, 0, 0, actor())));
        }

        /**
         * Closes the audit row with the figures the finished run measured.
         *
         * @param finishedAt when the run finished
         * @param read       rows the run read
         * @param rotated    rows the run rotated
         */
        private void close(Instant finishedAt, int read, int rotated) {
            if (record == null) {
                return;
            }
            record.complete(finishedAt, read, rotated);
            transactionTemplate.execute(status -> rotations.save(record));
        }

        /**
         * Reads the identity the process runs under, for the audit row.
         *
         * <p>A rotation is performed by an operator setting a variable and restarting, so the useful
         * record is which process applied it. The value is bounded to the column width and never
         * blank, because a blank actor is worse than a stated {@value #UNKNOWN_ACTOR}.
         *
         * @return the process owner, or {@value #UNKNOWN_ACTOR}
         */
        private String actor() {
            String owner = System.getProperty("user.name");
            if (owner == null || owner.isBlank()) {
                return UNKNOWN_ACTOR;
            }
            String value = owner.strip().toLowerCase(Locale.ROOT);
            return value.length() <= ACTOR_CEILING ? value : value.substring(0, ACTOR_CEILING);
        }
    }
}
