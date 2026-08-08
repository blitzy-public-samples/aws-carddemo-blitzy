package com.carddemo.equivalence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/** Holds every producer service to one executable retention contract. */
class RetentionSweepContractTest {

    /**
     * One {@code COMMENT ON TABLE} statement: the table it names and the text it carries.
     *
     * <p>Every migration in this platform writes the statement over several lines with the text in
     * single quotes, so the body is read up to the closing quote rather than to a line end. A later
     * migration re-issuing a comment produces a second match for the same table, and the last match
     * wins, which is what lets a correction supersede the original.
     */
    private static final Pattern TABLE_COMMENT = Pattern.compile(
            "COMMENT ON TABLE (\\w+) IS\\s*'([^']*)'", Pattern.DOTALL);

    /** The Flyway version of a migration filename, the digits following the leading V. */
    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__");

    /**
     * A retention window a sweep can apply: a span, or the setting that carries the span.
     *
     * <p>{@code retention=relationship} and {@code retention=reference} match neither alternative,
     * which is how a row that nothing expires is told from one that something does.
     */
    private static final Pattern HORIZON_WINDOW = Pattern.compile(
            "retention=(\\d+\\s*(hour|day|week|month|year)s?|carddemo\\.[a-z.-]+)");

    /** A dropped table, so a comment left behind in the migration text is not held against it. */
    private static final Pattern TABLE_DROP =
            Pattern.compile("DROP TABLE (?:IF EXISTS )?(\\w+)");

    /**
     * Every service that owns a {@code domain/RetentionSweep}, including the one with no outbox.
     *
     * <p>{@link #SERVICES} covers the five producer services and asserts the outbox and marker
     * contract each of them shares. The notification service publishes no event and therefore has
     * no outbox, so it is absent from that list, and it is the service with the most declared
     * horizons. The horizon contract below applies to all six.
     */
    private static final List<SweepSource> SWEEP_SOURCES = List.of(
            new SweepSource("account-service", "account", "purgeExpiredRows"),
            new SweepSource("authorization-service", "authorization", "purgeExpiredRows"),
            new SweepSource("card-service", "card", "purgeExpiredRows"),
            new SweepSource("fraud-detection-service", "fraud", "purgeExpiredRows"),
            new SweepSource("ledger-posting-service", "ledger", "purgeExpiredRows"),
            new SweepSource("notification-service", "notification", "sweepExpiredRows"));

    private static final List<ServiceContract> SERVICES = List.of(
            new ServiceContract(
                    "account",
                    "account-service",
                    com.carddemo.account.domain.RetentionSweep.class,
                    com.carddemo.account.config.AccountProperties.class,
                    com.carddemo.account.repository.OutboxEventRepository.class,
                    com.carddemo.account.repository.ProcessedEventRepository.class),
            new ServiceContract(
                    "authorization",
                    "authorization-service",
                    com.carddemo.authorization.domain.RetentionSweep.class,
                    com.carddemo.authorization.config.AuthorizationProperties.class,
                    com.carddemo.authorization.repository.OutboxEventRepository.class,
                    com.carddemo.authorization.repository.ProcessedEventRepository.class),
            new ServiceContract(
                    "card",
                    "card-service",
                    com.carddemo.card.domain.RetentionSweep.class,
                    com.carddemo.card.config.CardProperties.class,
                    com.carddemo.card.repository.OutboxEventRepository.class,
                    com.carddemo.card.repository.ProcessedEventRepository.class),
            new ServiceContract(
                    "fraud",
                    "fraud-detection-service",
                    com.carddemo.fraud.domain.RetentionSweep.class,
                    com.carddemo.fraud.config.FraudProperties.class,
                    com.carddemo.fraud.repository.OutboxEventRepository.class,
                    com.carddemo.fraud.repository.ProcessedEventRepository.class),
            new ServiceContract(
                    "ledger",
                    "ledger-posting-service",
                    com.carddemo.ledger.domain.RetentionSweep.class,
                    com.carddemo.ledger.config.LedgerProperties.class,
                    com.carddemo.ledger.repository.OutboxEventRepository.class,
                    com.carddemo.ledger.repository.ProcessedEventRepository.class));

    @Test
    void everyProducerRunsBothDeletesThroughExplicitTransactions() throws Exception {
        for (ServiceContract service : SERVICES) {
            Method sweep = service.sweepClass().getMethod("purgeExpiredRows");
            Scheduled scheduled = sweep.getAnnotation(Scheduled.class);

            assertThat(scheduled)
                    .as("%s retention schedule", service.name())
                    .isNotNull();
            assertThat(scheduled.fixedDelayString())
                    .isEqualTo("${carddemo.retention.sweep-interval-ms:3600000}");
            assertThat(Arrays.stream(service.sweepClass().getDeclaredFields())
                    .map(field -> field.getType()))
                    .as("%s explicit transaction boundary", service.name())
                    .anyMatch(TransactionTemplate.class::equals);
            assertThat(Arrays.stream(sweep.getAnnotations())
                    .map(annotation -> annotation.annotationType().getSimpleName()))
                    .doesNotContain("Transactional");

            String source = Files.readString(sourceOf(service));
            assertThat(occurrences(source, "transactionTemplate.execute"))
                    .as("%s transactions, one per delete", service.name())
                    .isEqualTo(1);
            assertThat(source)
                    .contains("deletePublishedBefore", "deleteMarkersProcessedBefore");
        }
    }

    @Test
    void everyProducerBindsBothHorizonsAndTheSweepInterval() throws Exception {
        for (ServiceContract service : SERVICES) {
            Class<?> outbox = nested(service.propertiesClass(), "Outbox");
            Class<?> processedEvent = nested(service.propertiesClass(), "ProcessedEvent");
            Class<?> retention = nested(service.propertiesClass(), "Retention");

            assertThat(componentNames(outbox)).contains("relay", "publishedRetentionHours");
            // Two components rather than one since a security review found the marker horizon
            // equal to broker log retention. The record now carries the broker figure as well
            // and refuses a horizon under twice it, so the relationship is enforced at
            // start-up rather than documented.
            assertThat(componentNames(processedEvent))
                    .containsExactly("markerRetentionHours", "brokerRetentionHours");
            assertThat(componentNames(retention)).contains("sweepIntervalMs");
            assertThat(hasHorizonDelete(service.outboxRepository(), "deletePublishedBefore"))
                    .as("%s outbox retention method", service.name())
                    .isTrue();
            assertThat(hasHorizonDelete(service.processedRepository(),
                    "deleteMarkersProcessedBefore"))
                    .as("%s marker retention method", service.name())
                    .isTrue();
        }
    }

    /**
     * Every table whose {@code COMMENT ON TABLE} declares a retention horizon must have a sweep
     * that applies it.
     *
     * <p>This is the general form of the defect a security review found. Six tables carried
     * {@code retention=} in their catalogue comment while nothing deleted a row of them:
     * {@code fraud_assessment}, {@code velocity_window} and {@code rejected_transaction} were the
     * three that mattered, and a reader took each for bounded when it only grew. Asserting the
     * relationship rather than the three names is what stops the seventh table from repeating it: a
     * migration that writes a horizon into a comment and no caller fails here, and so does a sweep
     * that stops calling one.
     *
     * <p>A table with no horizon in its comment is not required to be swept, and several must not
     * be. {@code transaction}, {@code transaction_category_balance} and
     * {@code account_balance_projection} hold the ledger, and the source expires none of them.
     *
     * <p>The comment is matched rather than parsed for a number, because a horizon written as
     * {@code retention=relationship} or {@code retention=reference} names no window and expires
     * nothing. Only a comment naming a {@code purge_key} column declares a sweepable horizon, which
     * is the convention every migration in this platform already follows.
     */
    @Test
    void everyDeclaredHorizonHasASweepThatAppliesIt() throws Exception {
        List<String> divergences = new ArrayList<>();

        for (SweepSource service : SWEEP_SOURCES) {
            String sweep = Files.readString(sweepSourceOf(service));
            Map<String, Boolean> declared = horizonsDeclaredBy(service.moduleDirectory());

            declared.forEach((table, hasHorizon) -> {
                boolean swept = sweep.contains("\"" + table + "\"");

                if (hasHorizon && !swept) {
                    divergences.add(service.moduleDirectory() + " declares a retention horizon on "
                            + table + " and domain/RetentionSweep never purges it, so the "
                            + "COMMENT ON TABLE describes an intention rather than the table");
                }
                if (!hasHorizon && swept) {
                    divergences.add(service.moduleDirectory() + " purges " + table
                            + " on a timer while its COMMENT ON TABLE declares no window, so an "
                            + "operator reading the catalogue plans for a row already gone");
                }
            });
        }

        assertThat(divergences)
                .as("a horizon nothing enforces is worse than none, and a purge nothing "
                        + "declares is worse still")
                .isEmpty();
    }

    /**
     * Reads which tables of one service declare a retention horizon, and which declare none.
     *
     * <p>A declared horizon names a purge column and a window. The window may be a span,
     * {@code retention=90 days}, or the setting that carries it,
     * {@code retention=carddemo.processed-event.marker-retention-hours}; naming the setting is the
     * better form, because a number restated in a comment drifts from the one the sweep reads.
     * {@code retention=relationship} and {@code retention=reference} name no window, and
     * {@code purge_key=none} says outright that nothing expires the row.
     *
     * <p>Only tables that still exist are reported. A later migration may drop a table, and its
     * original comment stays in the migration text forever.
     *
     * <p>A later comment on the same table supersedes the earlier one, which is what lets a
     * correction land as a forward migration rather than as an edit to one that already ran. Later
     * means later by version rather than later by filename: {@code V10} follows {@code V5} for
     * Flyway and precedes it for a string comparison, so the files are ordered by
     * {@link #BY_MIGRATION_VERSION} and a service that reaches its tenth migration keeps reading in
     * the order the database applied.
     *
     * @param moduleDirectory directory name of the service
     * @return table name to whether it declares a horizon
     */
    /**
     * Orders migration files the way Flyway applies them, by version number.
     *
     * <p>A string comparison puts {@code V10} before {@code V2}, which reverses every superseding
     * comment once a service passes its ninth migration. The version is the digits between the
     * leading {@code V} and the {@code __} separator.
     */
    private static final Comparator<Path> BY_MIGRATION_VERSION =
            Comparator.comparingInt(RetentionSweepContractTest::versionOf)
                    .thenComparing(path -> path.getFileName().toString());

    /**
     * Reads the Flyway version out of one migration filename.
     *
     * @param file the migration file
     * @return its version number, or {@link Integer#MAX_VALUE} when the name carries none
     */
    private static int versionOf(Path file) {
        Matcher version = MIGRATION_VERSION.matcher(file.getFileName().toString());
        return version.find() ? Integer.parseInt(version.group(1)) : Integer.MAX_VALUE;
    }

    private static Map<String, Boolean> horizonsDeclaredBy(String moduleDirectory)
            throws Exception {
        Path migrations = migrationDirectory(moduleDirectory);
        StringBuilder joined = new StringBuilder();

        try (var files = Files.list(migrations)) {
            for (Path file : files.sorted(BY_MIGRATION_VERSION).toList()) {
                joined.append(Files.readString(file)).append('\n');
            }
        }
        String text = joined.toString();

        Map<String, Boolean> declared = new LinkedHashMap<>();
        Matcher comments = TABLE_COMMENT.matcher(text);
        while (comments.find()) {
            String body = comments.group(2).replaceAll("\\s+", " ");
            boolean namesAWindow = HORIZON_WINDOW.matcher(body).find();
            boolean namesAPurgeColumn = body.contains("purge_key=")
                    && !body.contains("purge_key=none");
            declared.put(comments.group(1), namesAWindow && namesAPurgeColumn);
        }

        Matcher dropped = TABLE_DROP.matcher(text);
        while (dropped.find()) {
            declared.remove(dropped.group(1));
        }
        return declared;
    }

    /** Locates one service's migration directory by walking up from the working directory. */
    private static Path migrationDirectory(String moduleDirectory) {
        return moduleRoot().resolve(Path.of("services", moduleDirectory, "src", "main", "resources",
                "db", "migration"));
    }

    /** Locates the sweep class of one service. */
    private static Path sweepSourceOf(SweepSource service) {
        return moduleRoot().resolve(Path.of("services", service.moduleDirectory(), "src", "main",
                "java", "com", "carddemo", service.packageName(), "domain", "RetentionSweep.java"));
    }

    /** Walks up from the working directory to the {@code card-platform} module root. */
    private static Path moduleRoot() {
        Path base = Path.of("").toAbsolutePath().normalize();
        while (base != null && !Files.isDirectory(base.resolve("services"))) {
            base = base.getParent();
        }
        if (base == null) {
            throw new AssertionError("card-platform module root was not found");
        }
        return base;
    }

    /**
     * One service that owns a retention sweep.
     *
     * @param moduleDirectory directory name under {@code services/}
     * @param packageName     the segment under {@code com.carddemo} the sweep lives in
     * @param sweepMethod     the scheduled method, which the notification service names differently
     */
    private record SweepSource(String moduleDirectory, String packageName, String sweepMethod) {
    }

    /**
     * Reports whether a repository exposes a bounded or unbounded delete whose first argument is
     * the retention horizon.
     */
    private static boolean hasHorizonDelete(Class<?> repository, String methodName) {
        return Arrays.stream(repository.getMethods())
                .filter(method -> method.getName().equals(methodName))
                .map(Method::getParameterTypes)
                .anyMatch(parameters -> parameters.length >= 1
                        && parameters[0].equals(java.time.Instant.class));
    }

    private static Path sourceOf(ServiceContract service) {
        Path base = Path.of("").toAbsolutePath().normalize();
        while (base != null && !Files.isDirectory(base.resolve("services"))) {
            base = base.getParent();
        }
        if (base == null) {
            throw new AssertionError("card-platform module root was not found");
        }
        return base.resolve(Path.of("services", service.moduleDirectory(), "src", "main", "java",
                "com", "carddemo", service.name(), "domain", "RetentionSweep.java"));
    }

    private static long occurrences(String text, String token) {
        return text.split(java.util.regex.Pattern.quote(token), -1).length - 1L;
    }

    private static Class<?> nested(Class<?> enclosing, String simpleName) {
        return Arrays.stream(enclosing.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals(simpleName))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private record ServiceContract(
            String name,
            String moduleDirectory,
            Class<?> sweepClass,
            Class<?> propertiesClass,
            Class<?> outboxRepository,
            Class<?> processedRepository) {
    }
}