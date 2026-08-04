package com.carddemo.events.serde;

import tools.jackson.core.StreamReadConstraints;

/**
 * The size limits every event on this platform is held to, on the way out and on the way in.
 *
 * <p>ADDITIVE IN FULL. No COBOL program and no copybook defines these limits. The source has a
 * different answer to the same question: every record it reads or writes is fixed width, declared by
 * a Picture clause, so a 350-byte daily transaction record cannot arrive at 350 kilobytes. A JSON
 * document carries no such ceiling of its own, so one is declared here and applied at both ends.
 *
 * <p>Both serde classes of this package read these constants, so a producer cannot publish a
 * document a consumer would refuse, and a consumer cannot be made to allocate a document no
 * producer of this platform could have written. The reasoning behind each number sits in
 * {@code card-platform/docs/decision-log.md} (planned).
 *
 * <p>{@link #MAX_EVENT_BYTES} is also the width of the {@code payload} column of every
 * {@code outbox_event} table, so an event that serializes cannot fail to persist and a row that
 * persists cannot fail to publish.
 */
public final class EventWireBounds {

    /**
     * The greatest number of bytes one serialized event may occupy, 8192.
     *
     * <p>The widest contract this module ships is {@code TransactionAuthorized}. Its eighteen
     * declared properties sum to roughly 760 bytes of names, punctuation and maximum-width values,
     * and its bounded {@code extensions} object adds at most sixteen members of a forty-character
     * name and a 256-character value, roughly 4800 bytes more. The limit therefore leaves room for
     * the widest event any version-1 producer can legitimately write, and refuses anything beyond
     * it before a schema is even consulted.
     */
    public static final int MAX_EVENT_BYTES = 8192;

    /**
     * The greatest depth of nested objects and arrays one event may carry, 8.
     *
     * <p>Every shipped schema is two levels deep at most: the flat event object, plus one array of
     * strings in {@code FraudFlagged} or one bounded object in {@code extensions}. Eight leaves
     * headroom for a later version and still refuses a document built to exhaust a parser's stack.
     */
    public static final int MAX_NESTING_DEPTH = 8;

    /**
     * The greatest length of one JSON string value, 4096 characters.
     *
     * <p>The widest string any schema declares is the 256-character {@code extensions} value; the
     * widest contract field is the 100-character transaction description. This limit stops a single
     * enormous value from being materialised in memory before validation rejects it.
     */
    public static final int MAX_STRING_LENGTH = 4096;

    /**
     * The greatest length of one JSON property name, 64 characters.
     *
     * <p>The longest name any schema declares is {@code declineReasonDescription} at
     * twenty-four characters, and {@code extensions} bounds its own names to forty.
     */
    public static final int MAX_NAME_LENGTH = 64;

    /**
     * The greatest number of JSON tokens one event may carry, 512.
     *
     * <p>The widest contract reaches roughly seventy tokens with a full {@code extensions} object.
     * The limit refuses a document whose token count alone would cost more to walk than any event
     * of this platform can justify.
     */
    public static final long MAX_TOKEN_COUNT = 512L;

    /** Nothing constructs this class. It holds constants and a single factory method. */
    private EventWireBounds() {
        throw new AssertionError("EventWireBounds holds constants and is never instantiated");
    }

    /**
     * Returns the parser limits above as one {@link StreamReadConstraints} value.
     *
     * <p>These limits act while the parser reads, which is earlier than schema validation and
     * earlier than any mapping to a record. A document that breaks one of them is refused before
     * this platform allocates a tree for it.
     *
     * @return the constraints both serde classes install on their parser
     */
    public static StreamReadConstraints streamReadConstraints() {
        return StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .maxDocumentLength(MAX_EVENT_BYTES)
                .maxStringLength(MAX_STRING_LENGTH)
                .maxNameLength(MAX_NAME_LENGTH)
                .maxTokenCount(MAX_TOKEN_COUNT)
                .build();
    }
}
