package com.carddemo.authorization.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Allocation tests for {@link TransactionIdentifierSource}.
 *
 * <p>Two properties are under test. The statement names the schema the service owns, because a
 * statement written in native Structured Query Language (SQL) carries no schema of its own and an
 * unqualified sequence name resolves through the connection search path instead. And an allocated
 * value is rendered at the width {@code TRAN-ID PIC X(16)} holds at {@code app/cpy/CVTRA05Y.cpy:L5}.
 *
 * <p>The persistence context is stubbed, so these tests prove the statement and the rendering without
 * a database.
 */
final class TransactionIdentifierSourceTest {

    /** The schema {@code src/main/resources/application.yml} configures by default. */
    private static final String SCHEMA = "authorization_service";

    /** The first value {@code V1__schema.sql} starts the sequence at. */
    private static final long FIRST_VALUE = 1_000_000_000L;

    @Test
    void theStatementNamesTheConfiguredSchema() {
        assertEquals("SELECT nextval('\"authorization_service\".\"transaction_id_seq\"')",
                statementOf(SCHEMA, FIRST_VALUE), "qualified statement");
    }

    @Test
    void aConfiguredSchemaIsReadWithoutItsSurroundingBlanks() {
        assertEquals("SELECT nextval('\"authorization_service\".\"transaction_id_seq\"')",
                statementOf("  authorization_service  ", FIRST_VALUE), "trimmed statement");
    }

    @Test
    void anUnconfiguredSchemaLeavesTheSequenceNameAlone() {
        assertEquals("SELECT nextval('\"transaction_id_seq\"')", statementOf("", FIRST_VALUE),
                "unqualified statement");
    }

    @Test
    void aBlankSchemaCountsAsUnconfigured() {
        assertEquals("SELECT nextval('\"transaction_id_seq\"')", statementOf("   ", FIRST_VALUE),
                "unqualified statement");
    }

    @Test
    void aSchemaThatIsNotAPlainIdentifierStopsConstruction() {
        EntityManager entityManager = mock(EntityManager.class);
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new TransactionIdentifierSource(entityManager, "public\"; DROP TABLE x"),
                "a name this class cannot qualify with is refused");
        assertTrue(refused.getMessage().contains(TransactionIdentifierSource.SCHEMA_PROPERTY),
                "the message names the property that carries the schema");
        assertTrue(refused.getMessage().contains(TransactionIdentifierSource.SEQUENCE_NAME),
                "the message names the sequence that cannot be reached");
    }

    @Test
    void anAllocatedValueIsLeftPaddedToTheRecordWidth() {
        String identifier = allocate(SCHEMA, FIRST_VALUE);
        assertEquals(TransactionIdentifierSource.IDENTIFIER_WIDTH, identifier.length(), "width");
        assertEquals("0000001000000000", identifier, "left-padded with zeros");
    }

    @Test
    void aValueAlreadyAtTheRecordWidthIsRenderedUnchanged() {
        assertEquals("1234567890123456", allocate(SCHEMA, 1_234_567_890_123_456L), "unchanged");
    }

    @Test
    void twoCallsRenderTheTwoValuesTheSequenceHandsOut() {
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(FIRST_VALUE, FIRST_VALUE + 1L);

        TransactionIdentifierSource source = new TransactionIdentifierSource(entityManager, SCHEMA);
        assertEquals("0000001000000000", source.nextIdentifier(), "first");
        assertEquals("0000001000000001", source.nextIdentifier(), "second");
    }

    /**
     * Allocates one identifier through a stubbed persistence context.
     *
     * @param schema the configured schema
     * @param allocated the value the sequence hands out
     * @return the rendered identifier
     */
    private static String allocate(String schema, long allocated) {
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(allocated);
        return new TransactionIdentifierSource(entityManager, schema).nextIdentifier();
    }

    /**
     * Captures the statement one allocation runs.
     *
     * @param schema the configured schema
     * @param allocated the value the sequence hands out
     * @return the statement passed to the persistence context
     */
    private static String statementOf(String schema, long allocated) {
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(allocated);

        new TransactionIdentifierSource(entityManager, schema).nextIdentifier();

        ArgumentCaptor<String> statement = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(statement.capture());
        return statement.getValue();
    }
}
