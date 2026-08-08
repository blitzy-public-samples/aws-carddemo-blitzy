package com.carddemo.card.domain;

import com.carddemo.card.entity.CardEntity;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.cobol.PanMasker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Brings every {@code card_token} into line with the card-token key the deployment supplied, before
 * the service accepts a request.
 *
 * <p>A card token is the platform identity of one card. It names the row a card history route reads,
 * it is the cursor of the card list, and it is the subject of a {@code SCOPE_CARD} authority. It is
 * also a keyed value: {@link PanMasker#cardToken(String)} takes an {@code HmacSHA256} code over the
 * card number under the configured key, so the token of one card under two keys is two values.
 *
 * <p>That is why this class exists. {@code V2__seed.sql} carries a checked-in token literal on each
 * of its fifty rows, derived under the build-scope key {@code card-platform/pom.xml} supplies, which
 * is what lets a test compare a literal against the derivation. A deployment supplies a key of its
 * own - {@code card-platform/.env.example} ships a placeholder and this service refuses to start
 * without a real value - so those fifty literals arrive in the database belonging to a key the
 * running service does not hold. Left alone they would split every card's identity in two: a row
 * this service later writes would carry a token derived under the live key, while a seeded row went
 * on carrying one derived under the build key, and a cursor or an authority naming either would
 * reach one of the two.
 *
 * <p>The reconciliation runs as an {@link ApplicationRunner}, which Spring Boot invokes after the
 * context has refreshed - so after Flyway has applied both migrations - and before it publishes the
 * ready event that turns the readiness probe to accepting traffic. No request therefore reads a
 * token this class is about to change.
 *
 * <p>It is idempotent and bounded. A row whose stored token already equals the derivation is left
 * untouched, so a second start-up rewrites nothing and the ordinary case costs one read per page. A
 * page holds {@value #PAGE_SIZE} rows and one run reads at most {@value #ROW_CEILING}, which keeps
 * one statement's lock footprint small and stops a start-up that would otherwise walk an
 * unexpectedly large table.
 *
 * <p>The same mechanism applies a version rollover. {@code CARD_TOKEN_VERSION} is part of the
 * message the code covers, so raising it changes every token under the same key, and the next
 * start-up rewrites the rows to match.
 *
 * <p>No log line here carries a card number or a token. A run reports how many rows it read and how
 * many it rewrote, which is what an operator needs to tell a rollover from a no-op.
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
     * start-up that has to rewrite more rows than this is a migration and not a start-up, and it
     * says so rather than delaying readiness without explanation.
     */
    static final int ROW_CEILING = 200_000;

    private final CardRepository cards;
    private final TransactionTemplate transactionTemplate;

    /**
     * Builds the reconciler.
     *
     * @param cards              the card repository, read a page at a time and updated by card
     *                           number
     * @param transactionTemplate the template each page's writes run inside
     */
    public CardTokenReconciler(CardRepository cards, TransactionTemplate transactionTemplate) {
        this.cards = Objects.requireNonNull(cards, "cards");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate");
    }

    /**
     * Runs the reconciliation once, at start-up.
     *
     * <p>A failure here is not swallowed. This service cannot serve a correct card token while the
     * stored values belong to another key, and a service that answers with the wrong identity for a
     * card is worse than one that does not start. The message an operator sees is the underlying
     * database failure, because at this point the datasource has already migrated the schema and a
     * failure is about the data rather than about the configuration.
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
     * @return how many rows were rewritten
     * @throws IllegalStateException when no usable card-token key is configured, which
     *                               {@link PanMasker#cardToken(String)} reports
     */
    public int reconcile() {
        String after = null;
        int read = 0;
        int rewritten = 0;

        while (read < ROW_CEILING) {
            List<CardEntity> page = after == null
                    ? cards.findFirstPage(Limit.of(PAGE_SIZE))
                    : cards.findPageAfter(after, Limit.of(PAGE_SIZE));
            if (page.isEmpty()) {
                break;
            }
            read += page.size();
            after = page.get(page.size() - 1).getCardNumber();
            rewritten += rewriteDrifted(expectedTokensOf(page));
            if (page.size() < PAGE_SIZE) {
                break;
            }
        }

        if (rewritten == 0) {
            log.debug("Card tokens already belong to the configured key across {} rows", read);
        } else {
            log.info("Re-derived {} of {} card tokens under the configured card-token key",
                    rewritten, read);
        }
        return rewritten;
    }

    /**
     * Returns the card numbers of one page whose stored token is not the derived one, each mapped to
     * the token it should carry.
     *
     * <p>The comparison happens here, outside the write transaction, so a page that needs no change
     * opens none. The map preserves the page's order, which is the primary-key order the update
     * statements then follow.
     *
     * @param page one page of card rows
     * @return the rows to rewrite, in page order
     */
    private static Map<String, String> expectedTokensOf(List<CardEntity> page) {
        Map<String, String> drifted = new LinkedHashMap<>();
        for (CardEntity card : page) {
            String expected = PanMasker.cardToken(card.getCardNumber());
            if (!expected.equals(card.getCardToken())) {
                drifted.put(card.getCardNumber(), expected);
            }
        }
        return drifted;
    }

    /**
     * Applies one page's rewrites in a single transaction.
     *
     * <p>Each statement carries its own {@code AND card_token <> :cardToken} guard, so a row another
     * instance has already corrected counts as zero here rather than as a conflict. Two instances
     * starting together therefore converge on the same rows without coordinating.
     *
     * @param drifted the card numbers to rewrite, each mapped to its expected token
     * @return how many rows the statements changed
     */
    private int rewriteDrifted(Map<String, String> drifted) {
        if (drifted.isEmpty()) {
            return 0;
        }
        List<Integer> changes = new ArrayList<>(drifted.size());
        transactionTemplate.executeWithoutResult(status ->
                drifted.forEach((cardNumber, token) ->
                        changes.add(cards.reassignCardToken(cardNumber, token))));
        return changes.stream().mapToInt(Integer::intValue).sum();
    }
}
