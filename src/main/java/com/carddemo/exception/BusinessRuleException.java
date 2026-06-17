package com.carddemo.exception;

/**
 * Signals that a request violated a domain business rule and therefore cannot be
 * fulfilled, even though it was syntactically well formed and the resources it
 * referenced exist.
 *
 * <p>This is the generic carrier for <em>synchronous</em> (online) business-rule
 * violations ported from the legacy CICS online programs of the AWS CardDemo
 * application. When mapped by {@code GlobalExceptionHandler} (via
 * {@code @ExceptionHandler(BusinessRuleException.class)}) it yields an
 * <strong>HTTP 400 Bad Request</strong> response. The status mapping deliberately
 * lives in the handler — this class carries <em>no</em> framework annotations such
 * as {@code @ResponseStatus} — so the exception type stays free of any Spring,
 * Jakarta, or other web dependency.</p>
 *
 * <p><strong>Representative violations.</strong> The following are typical reasons a
 * service throws this exception; the underlying arithmetic and date comparisons
 * live in the service layer, not in this class:</p>
 * <ul>
 *   <li><strong>Overlimit</strong> — a transaction would push the account past its
 *       credit limit (add-transaction path of {@code COTRN02C}; the cycle-based
 *       overlimit math, AAP &#167;0.6.1, is owned by the service layer).</li>
 *   <li><strong>Expired account or card</strong> — the account expiration date is
 *       earlier than the transaction's origination date ({@code COTRN02C};
 *       AAP &#167;0.6.1).</li>
 *   <li><strong>Insufficient available credit</strong> — a bill payment exceeds the
 *       available credit, computed as {@code creditLimit - currentBalance}
 *       (bill-payment path of {@code COBIL00C}; AAP &#167;0.4.1.3).</li>
 * </ul>
 *
 * <p><strong>Scope boundary — batch reject codes are NOT modeled here.</strong> The
 * legacy daily-posting batch program {@code CBTRN02C} emits numeric reject codes
 * (100 / 101 / 102 / 103 / 109) into its fixed-width DALYREJS reject file. Those
 * codes are an internal batch concern owned by the {@code batch} package's
 * reject-record writer and are intentionally <em>not</em> represented by this
 * REST-surface exception. The {@link #getRuleCode() ruleCode} carried here is a
 * free-form, human-oriented label (for example {@code "OVERLIMIT"},
 * {@code "ACCOUNT_EXPIRED"}, {@code "INSUFFICIENT_CREDIT"}) and must never be
 * conflated with the batch reject numbers.</p>
 *
 * <p><strong>PII suppression (AAP &#167;0.6.8).</strong> Callers constructing this
 * exception must never place sensitive data — a card verification value (CVV), a
 * full Social Security Number, or any password — into the message or rule code,
 * because both may be serialized to the client and written to logs. Non-sensitive
 * values such as amounts, credit limits, and account or card identifiers are
 * acceptable.</p>
 *
 * <p>The exception is <em>unchecked</em> ({@code extends RuntimeException}) so that
 * the {@code @Transactional} service methods that detect a violation are not forced
 * to declare it, mirroring the fail-fast control flow of the original COBOL
 * paragraphs.</p>
 */
public class BusinessRuleException extends RuntimeException {

    /**
     * Serialization version identifier. {@link RuntimeException} is
     * {@link java.io.Serializable}; pinning this value keeps the serialized form
     * stable across builds.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Optional, free-form identifier of the business rule that was violated (for
     * example {@code "OVERLIMIT"}, {@code "ACCOUNT_EXPIRED"},
     * {@code "INSUFFICIENT_CREDIT"}). It is {@code null} when the caller does not
     * supply one. This is a human- and client-oriented label and is deliberately
     * decoupled from the batch DALYREJS numeric reject codes.
     */
    private final String ruleCode;

    /**
     * Creates a business-rule violation with the supplied detail message and no
     * rule code.
     *
     * @param message human-readable description of the violated rule; must not
     *                contain PII (CVV, full SSN, or passwords)
     */
    public BusinessRuleException(String message) {
        super(message);
        this.ruleCode = null;
    }

    /**
     * Creates a business-rule violation with the supplied detail message and
     * underlying cause, and no rule code.
     *
     * @param message human-readable description of the violated rule; must not
     *                contain PII (CVV, full SSN, or passwords)
     * @param cause   the underlying cause (may be {@code null}); retained for the
     *                standard exception chain
     */
    public BusinessRuleException(String message, Throwable cause) {
        super(message, cause);
        this.ruleCode = null;
    }

    /**
     * Creates a business-rule violation that identifies the specific rule that
     * fired via a free-form rule code.
     *
     * @param ruleCode free-form rule identifier (for example {@code "OVERLIMIT"});
     *                 may be {@code null}; must not contain PII
     * @param message  human-readable description of the violated rule; must not
     *                 contain PII (CVV, full SSN, or passwords)
     */
    public BusinessRuleException(String ruleCode, String message) {
        super(message);
        this.ruleCode = ruleCode;
    }

    /**
     * Creates a business-rule violation that identifies the specific rule that
     * fired, along with the underlying cause.
     *
     * @param ruleCode free-form rule identifier (for example {@code "OVERLIMIT"});
     *                 may be {@code null}; must not contain PII
     * @param message  human-readable description of the violated rule; must not
     *                 contain PII (CVV, full SSN, or passwords)
     * @param cause    the underlying cause (may be {@code null}); retained for the
     *                 standard exception chain
     */
    public BusinessRuleException(String ruleCode, String message, Throwable cause) {
        super(message, cause);
        this.ruleCode = ruleCode;
    }

    /**
     * Returns the free-form business rule code identifying which rule was violated,
     * or {@code null} if none was supplied at construction time.
     *
     * @return the rule code, or {@code null} when not set
     */
    public String getRuleCode() {
        return ruleCode;
    }
}
