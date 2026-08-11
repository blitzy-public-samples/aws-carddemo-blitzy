package com.carddemo.equivalence;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.events.serde.EventContracts;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the one service-local contract document to the governed original it copies.
 *
 * <p>No COBOL ancestor. {@code app/cpy/} holds twenty-eight copybooks and every one of them is
 * included textually by {@code COPY}, so a program that needed a record layout received its own
 * copy. That is how the customer record forked: {@code app/cpy/CUSTREC.cpy:L19} and
 * {@code app/cpy/CVCUS01Y.cpy:L19} declare the same eighteen fields under two different date-field
 * names, with version stamps one second apart. This class exists so the one copy this platform does
 * keep cannot fork the same way.
 *
 * <p><b>What the copy is for.</b> The account service publishes {@code AccountStateChanged}, and
 * the checkpoint inventory requires the contract document for that event to sit inside the module
 * that produces it, at
 * {@code services/account-service/src/main/resources/schemas/account-state-changed-v1.json}. Every
 * schema document is otherwise packaged once, in {@code libs/event-contracts}, which is the module
 * {@code EventSchemas} compiles them from. Both statements are satisfied by one document present at
 * both paths and identical at both, rather than by two documents that agree today.
 *
 * <p><b>Identity is the requirement, not agreement.</b> Both files land on the classpath
 * of the running account service under the same resource name, {@code
 * schemas/account-state-changed-v1.json}: one from the module's own classes, one from the packaged
 * contract library. {@code EventSchemas} reads that name through
 * {@code ClassLoader.getResourceAsStream}, which answers with whichever copy the classpath orders
 * first, and a Spring Boot layered archive orders the module's own classes ahead of its
 * dependencies. Two documents that differ would therefore validate differently depending on how the
 * service was launched, which is the least debuggable failure this platform could carry. Byte
 * identity removes the question: whichever copy the classloader answers with, the validation is the
 * same.
 *
 * <p>Editing the governed document without copying it here fails
 * {@link #theServiceLocalCopyIsByteIdenticalToTheGovernedDocument()}. Adding a private copy of any
 * other schema document to any service fails {@link #onlyTheRequiredPathCarriesAServiceLocalCopy()}.
 * Between them, one source of truth survives the presence of the required path.
 *
 * <p>Decisions behind the arrangement: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("Service-local contract documents against the governed originals")
class ServiceSchemaResourceContractTest {

    /** Resource name both copies of the account state document occupy on the classpath. */
    private static final String ACCOUNT_STATE_RESOURCE = "schemas/account-state-changed-v1.json";

    /** The module that packages every governed contract document. */
    private static final String CONTRACT_LIBRARY = "libs/event-contracts";

    /** The one module the inventory requires to carry a copy of a contract document. */
    private static final String COPY_HOLDER = "services/account-service";

    /** Resource root inside a module, the segment a classpath resource name is relative to. */
    private static final String RESOURCE_ROOT = "src/main/resources";

    /** Directory name every schema document sits in, inside a resource root. */
    private static final String SCHEMA_DIRECTORY = "schemas";

    /** Directory Maven writes build output to, which mirrors every packaged resource. */
    private static final String BUILD_OUTPUT = "target";

    @Test
    @DisplayName("the required account-service schema path exists and parses")
    void theRequiredAccountServiceSchemaPathExists() {
        Path copy = platformDirectory().resolve(COPY_HOLDER).resolve(RESOURCE_ROOT)
                .resolve(ACCOUNT_STATE_RESOURCE);

        assertThat(copy)
                .as("the checkpoint inventory requires %s/%s/%s", COPY_HOLDER, RESOURCE_ROOT,
                        ACCOUNT_STATE_RESOURCE)
                .isRegularFile();
        assertThat(read(copy))
                .as("the copy must be the AccountStateChanged contract document")
                .contains("\"const\": \"AccountStateChanged\"")
                .contains("\"$id\": \"https://carddemo.example.com/schemas/"
                        + "account-state-changed-v1.json\"");
    }

    @Test
    @DisplayName("the service-local copy is byte-identical to the governed document")
    void theServiceLocalCopyIsByteIdenticalToTheGovernedDocument() {
        Path governed = platformDirectory().resolve(CONTRACT_LIBRARY).resolve(RESOURCE_ROOT)
                .resolve(ACCOUNT_STATE_RESOURCE);
        Path copy = platformDirectory().resolve(COPY_HOLDER).resolve(RESOURCE_ROOT)
                .resolve(ACCOUNT_STATE_RESOURCE);

        assertThat(bytes(copy))
                .as("%s must hold the bytes of %s, because both reach the running service under "
                        + "the resource name %s and the classloader chooses between them",
                        COPY_HOLDER + "/" + RESOURCE_ROOT + "/" + ACCOUNT_STATE_RESOURCE,
                        CONTRACT_LIBRARY + "/" + RESOURCE_ROOT + "/" + ACCOUNT_STATE_RESOURCE,
                        ACCOUNT_STATE_RESOURCE)
                .isEqualTo(bytes(governed));
    }

    @Test
    @DisplayName("the registry resolves that event to the resource name both copies occupy")
    void theRegistryResolvesThatEventToTheSharedResourceName() {
        assertThat(EventContracts.schemaResourceFor(EventContracts.ACCOUNT_STATE_CHANGED))
                .as("the name %s resolves to, which is what makes the two copies interchangeable",
                        EventContracts.ACCOUNT_STATE_CHANGED)
                .isEqualTo(ACCOUNT_STATE_RESOURCE);
    }

    @Test
    @DisplayName("only the required path carries a service-local copy")
    void onlyTheRequiredPathCarriesAServiceLocalCopy() {
        Path services = platformDirectory().resolve("services");
        List<String> serviceLocalDocuments = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(services)) {
            tree.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".json"))
                    .filter(file -> file.getParent() != null
                            && SCHEMA_DIRECTORY.equals(file.getParent().getFileName().toString()))
                    .map(file -> platformDirectory().relativize(file).toString()
                            .replace('\\', '/'))
                    // Build output holds a copy of every packaged resource, and it is the same file
                    // by construction. Reading it as a second source of truth would report a fork
                    // on any machine that had run the build and none on a fresh clone.
                    .filter(relative -> !relative.contains("/" + BUILD_OUTPUT + "/"))
                    .sorted(Comparator.naturalOrder())
                    .forEach(serviceLocalDocuments::add);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot walk " + services, unreadable);
        }

        assertThat(serviceLocalDocuments)
                .as("a contract document is packaged once, in %s. The one exception is the "
                        + "inventory-required copy, and every further copy is a fork waiting to "
                        + "happen", CONTRACT_LIBRARY)
                .containsExactly(COPY_HOLDER + "/" + RESOURCE_ROOT + "/" + ACCOUNT_STATE_RESOURCE);
    }

    @Test
    @DisplayName("every released contract the baseline records exists in the source tree")
    void everyReleasedContractTheBaselineRecordsExistsInTheSourceTree() {
        Path library = platformDirectory().resolve(CONTRACT_LIBRARY).resolve(RESOURCE_ROOT);
        Path baselineFile = library.resolve("contracts/released-contracts.json");
        assertThat(baselineFile)
                .as("the released-contract baseline is what makes the immutability of a shipped"
                        + " document measurable, so it ships beside the documents it records")
                .isRegularFile();

        String baseline = read(baselineFile);
        List<String> recorded = new ArrayList<>();
        java.util.regex.Matcher resource = java.util.regex.Pattern
                .compile("\"resource\"\\s*:\\s*\"(schemas/[a-z0-9\\-]+\\.json)\"")
                .matcher(baseline);
        while (resource.find()) {
            recorded.add(resource.group(1));
        }

        assertThat(recorded)
                .as("the baseline records no released document, so nothing is pinned")
                .isNotEmpty();
        for (String relative : recorded) {
            assertThat(library.resolve(relative))
                    .as("%s is recorded as released and is missing from the source tree. A released"
                            + " document is never deleted: every record retained on a topic under it"
                            + " becomes unreadable the moment it goes. This check reads the source"
                            + " tree rather than the classpath, because build output keeps a stale"
                            + " copy of a deleted resource", relative)
                    .isRegularFile();
        }

        List<String> shipped = new ArrayList<>();
        try (Stream<Path> documents = Files.list(library.resolve(SCHEMA_DIRECTORY))) {
            documents.filter(Files::isRegularFile)
                    .map(file -> SCHEMA_DIRECTORY + "/" + file.getFileName())
                    .sorted(Comparator.naturalOrder())
                    .forEach(shipped::add);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + library.resolve(SCHEMA_DIRECTORY),
                    unreadable);
        }
        assertThat(shipped)
                .as("every shipped document is a released contract and every released contract"
                        + " ships, so a document added without a baseline entry is a version with"
                        + " nothing pinning its wire contract")
                .containsExactlyInAnyOrderElementsOf(recorded);
    }

    /** Returns the {@code card-platform} directory of this checkout. */
    private static Path platformDirectory() {
        return CardDemoFixtureLoader.fixtureDirectory()
                .getParent()
                .getParent()
                .getParent()
                .resolve("card-platform");
    }

    /**
     * Reads one file as text.
     *
     * @param file the file to read
     * @return the whole file
     */
    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + file, unreadable);
        }
    }

    /**
     * Reads one file as octets, so a comparison sees line endings and trailing bytes.
     *
     * @param file the file to read
     * @return every octet of the file
     */
    private static byte[] bytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + file, unreadable);
        }
    }
}
