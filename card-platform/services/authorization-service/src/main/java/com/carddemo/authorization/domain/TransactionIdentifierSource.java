package com.carddemo.authorization.domain;

import com.carddemo.cobol.PicClause;
import jakarta.persistence.EntityManager;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Allocates one transaction identifier per authorization call.
 *
 * <p>This replaces the source mechanism rather than reproducing it, which is a declared behaviour
 * change.
 *
 * <p>Both online capture paths allocate an identifier by browsing the transaction file backwards from
 * high values and adding one. {@code app/cbl/COTRN02C.cbl:L444-L451} does it for a new transaction and
 * {@code app/cbl/COBIL00C.cbl:L212-L219} does it for a bill payment. Read, add and write are three
 * steps there with nothing holding the highest key between them, so two concurrent callers read one
 * value and write two records under it.
 *
 * <p>A database sequence allocates instead. {@code transaction_id_seq}, created by
 * {@code src/main/resources/db/migration/V1__schema.sql}, hands each caller a value no other caller
 * receives, so the race has no equivalent here.
 *
 * <p>The value is rendered left-padded with zeros to the
 * {@value PicClause#DALYTRAN_ID_WIDTH} characters {@code TRAN-ID PIC X(16)} holds at
 * {@code app/cpy/CVTRA05Y.cpy:L5}. The field holds characters, so a comparison of two identifiers
 * runs on text and the padding keeps that comparison ordered.
 *
 * <p>The sequence lives in the private schema this service owns, and the statement below names that
 * schema. A mapped entity carries its schema from {@value #SCHEMA_PROPERTY}, and a statement written in
 * native Structured Query Language (SQL) carries none, so an unqualified sequence name resolves through
 * the connection search path instead and reaches nothing. The statement is therefore qualified with the
 * same configured schema the entities are mapped into, so the sequence and every table
 * {@code src/main/resources/db/migration/V1__schema.sql} creates are read out of one place with no
 * second setting to keep in step.
 */
@Component
public class TransactionIdentifierSource {

    /** Characters an identifier holds, from {@code TRAN-ID PIC X(16)}. */
    public static final int IDENTIFIER_WIDTH = PicClause.DALYTRAN_ID_WIDTH;

    /** The sequence the migration creates, and the only allocator this service uses. */
    static final String SEQUENCE_NAME = "transaction_id_seq";

    /**
     * The configured schema every entity of this service is mapped into, and therefore the schema the
     * sequence lives in. {@code src/main/resources/application.yml} carries it, and
     * {@code spring.flyway.schemas} resolves from the same value.
     */
    static final String SCHEMA_PROPERTY = "spring.jpa.properties.hibernate.default_schema";

    /**
     * The one shape of schema name this class qualifies the sequence with: a plain identifier of
     * letters, digits and underscores opening on a letter or an underscore, inside the 63 characters
     * PostgreSQL holds.
     */
    private static final Pattern PLAIN_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    /** The character an identifier is left-padded with. */
    private static final String PAD_DIGIT = "0";

    /** Reads the sequence. */
    private final EntityManager entityManager;

    /**
     * The allocation statement, built once so a call performs no string work. The sequence name is
     * quoted, so a schema created under any letter case resolves to the object Flyway created.
     */
    private final String nextValueQuery;

    /**
     * Takes the persistence context this source reads the sequence through, and the schema that
     * sequence lives in.
     *
     * @param entityManager the persistence context
     * @param mappedSchema the value of {@value #SCHEMA_PROPERTY}, empty to leave the sequence name
     *        resolving through the connection search path
     * @throws IllegalStateException when the schema is neither empty nor a plain identifier, since a
     *         name this class cannot qualify with would leave allocation resolving through the
     *         connection search path and reaching a different sequence or none
     */
    public TransactionIdentifierSource(EntityManager entityManager,
            @Value("${spring.jpa.properties.hibernate.default_schema:}") String mappedSchema) {
        this.entityManager = entityManager;
        this.nextValueQuery = nextValueQuery(contentOrAbsent(mappedSchema));
    }

    /**
     * Allocates the next identifier.
     *
     * <p>The call runs inside whatever transaction the caller opened. A sequence advances outside
     * transaction control, so a rolled-back call consumes a value and no two calls share one.
     * Consuming a value costs nothing, and reusing one would reintroduce the race this class removes.
     *
     * @return {@value #IDENTIFIER_WIDTH} characters, left-padded with zeros
     */
    public String nextIdentifier() {
        Number allocated =
                (Number) entityManager.createNativeQuery(nextValueQuery).getSingleResult();
        return pad(Long.toString(allocated.longValue()));
    }

    /**
     * Reduces a configured schema to its content.
     *
     * @param schema the configured value
     * @return the value without surrounding blanks, or {@code null} when it carries no content
     */
    private static String contentOrAbsent(String schema) {
        if (schema == null) {
            return null;
        }
        String trimmed = schema.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Builds the allocation statement against a schema.
     *
     * @param schema the schema the sequence lives in, or {@code null} to leave the sequence name
     *        resolving through the connection search path
     * @return the statement {@link #nextIdentifier()} runs
     * @throws IllegalStateException when the schema is not a plain identifier
     */
    private static String nextValueQuery(String schema) {
        if (schema == null) {
            return "SELECT nextval('\"" + SEQUENCE_NAME + "\"')";
        }
        if (!PLAIN_IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalStateException("Property " + SCHEMA_PROPERTY
                    + " must name a plain identifier for " + SEQUENCE_NAME
                    + " to be allocated from, and names: " + schema);
        }
        return "SELECT nextval('\"" + schema + "\".\"" + SEQUENCE_NAME + "\"')";
    }

    /**
     * Left-pads an allocated value to the width the record field holds.
     *
     * @param digits the allocated value as text
     * @return the value at {@value #IDENTIFIER_WIDTH} characters, unchanged when it already holds
     *         that many or more
     */
    private static String pad(String digits) {
        if (digits.length() >= IDENTIFIER_WIDTH) {
            return digits;
        }
        return PAD_DIGIT.repeat(IDENTIFIER_WIDTH - digits.length()) + digits;
    }
}
