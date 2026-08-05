package com.carddemo.equivalence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/** Holds every producer service to one executable retention contract. */
class RetentionSweepContractTest {

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
            assertThat(componentNames(processedEvent)).containsExactly("markerRetentionHours");
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