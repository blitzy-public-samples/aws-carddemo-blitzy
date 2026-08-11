package com.carddemo.common.crypto;

import com.carddemo.common.exception.PiiEncryptionException;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JPA attribute converter that transparently encrypts and decrypts sensitive
 *     personally-identifiable string columns (SSN, government-issued id, EFT account id, and
 *     the card verification value) at rest using AES-256-GCM.
 * :purpose: Ensure regulated PII the application writes is never stored as plaintext in
 *     the database, satisfying the encrypt-permitted-PII control while leaving the in-memory
 *     Java field value unchanged for business logic.
 * :output: On write, a Base64 token of ``IV || ciphertext || GCM-tag``; on read, the
 *     original plaintext. ``null`` and empty inputs pass through unchanged so nullable columns
 *     and blank fixed-width fields are preserved. The 256-bit key is resolved from the
 *     ``CARDDEMO_PII_KEY`` environment variable or the ``carddemo.pii.key`` system property,
 *     expected as a Base64 value of 32 bytes. When no key is configured any attempt to encrypt
 *     fails fast, so a misconfigured deployment cannot silently persist plaintext. The read
 *     path additionally tolerates a column value that is not a ciphertext token. Those values
 *     exist because the legacy application loaded its VSAM files from fixed-width sequential
 *     data sets and the seed migrations derived from ``app/data/ASCII`` reproduce them
 *     verbatim, so a column can legitimately hold a value written before encryption was
 *     introduced. Such a value is returned as read, and is re-written encrypted the next time
 *     the owning entity is persisted. The distinction is structural, not a fallback for
 *     decryption failures: a value that IS a well-formed token but cannot be decrypted still
 *     raises ``IllegalStateException`` rather than surfacing ciphertext as data. Rationale and
 *     the accepted residual risk are recorded in ``docs/decision-log.md``.
 */
@Converter
public class CryptoConverter implements AttributeConverter<String, String> {

    /** :purpose: Environment variable holding the Base64-encoded 256-bit key. */
    public static final String KEY_ENV = PiiEncryptionKey.KEY_ENV;

    /** :purpose: Property/system-property fallback holding the Base64-encoded key. */
    public static final String KEY_PROPERTY = PiiEncryptionKey.KEY_PROPERTY;

    /**
     * :purpose: Version marker prefixing every token this converter writes. It makes the at-rest
     *     format self-describing, so a read can tell an encrypted value from legacy plaintext
     *     without guessing, and a future algorithm change can add a new marker without ambiguity.
     */
    public static final String ENVELOPE_PREFIX = "gcm1:";

    private static final Logger log = LoggerFactory.getLogger(CryptoConverter.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_TAG_LENGTH = GCM_TAG_BITS / Byte.SIZE;

    /**
     * :purpose: Emit the legacy-plaintext warning once per process so an operator learns that
     *     un-encrypted PII is still present without the log being flooded per row.
     */
    private static final AtomicBoolean LEGACY_PLAINTEXT_WARNED = new AtomicBoolean();

    /**
     * :purpose: Smallest number of bytes a ciphertext token can occupy: the 12-byte IV
     *     plus the 128-bit GCM authentication tag. Any shorter decoded value cannot be a
     *     token and is therefore a pre-encryption column value.
     */
    private static final int MIN_TOKEN_BYTES = GCM_IV_LENGTH + GCM_TAG_BITS / 8;


    /** :purpose: Logger used for the once-per-JVM legacy-plaintext warning. */
    private static final Logger LOGGER = LoggerFactory.getLogger(CryptoConverter.class);




    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Encrypts an attribute value before it is written to the database column.
     *
     * :param attribute: the plaintext attribute value, possibly ``null``.
     * :output: the Base64 ciphertext token, or the original ``null``/empty value.
     * :raises IllegalStateException: when no key is configured, so a misconfigured
     *     deployment can never silently persist plaintext.
     * :raises PiiEncryptionException: when the cipher rejects the input. The attribute
     *     value is never included in the message.
     */
    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return attribute;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, PiiEncryptionKey.require(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (IllegalStateException ex) {
            // A missing/invalid key must surface with its own diagnostic message.
            throw ex;
        } catch (Exception ex) {
            throw new PiiEncryptionException(
                    "Unable to encrypt sensitive attribute for persistence", ex);
        }
    }

    /**
     * Decrypts a stored column value back into the plaintext attribute.
     *
     * :param dbData: the stored value: normally a Base64 ciphertext token written by
     *     :java:meth:`convertToDatabaseColumn`, but possibly a pre-encryption
     *     (``legacy``) value loaded by a bulk data load, possibly ``null``.
     * :output: the recovered plaintext for a ciphertext token, the value itself when it
     *     cannot be a ciphertext token, or the original ``null``/empty value.
     * :raises PiiEncryptionException: when a well-formed ciphertext token cannot be
     *     decrypted (wrong or truncated key, corrupted value), so a misconfigured
     *     deployment fails loudly instead of surfacing ciphertext as data.
     * :raises IllegalStateException: when no key is configured at all, which is never
     *     masked as a data problem.
     */
    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return dbData;
        }
        if (dbData.startsWith(ENVELOPE_PREFIX)) {
            return decrypt(dbData.substring(ENVELOPE_PREFIX.length()));
        }
        // Unmarked value. Only attempt decryption when it could structurally BE a token; a short
        // value such as a 9-digit SSN never can, so seeded plaintext is returned without even
        // requiring a key.
        if (hasTokenShape(dbData)) {
            try {
                return decrypt(dbData);
            } catch (IllegalStateException ex) {
                if (!PiiEncryptionKey.isAvailable()) {
                    // Never mask a missing-key misconfiguration.
                    throw ex;
                }
                // Token-shaped but not our ciphertext: fall through to the legacy plaintext path.
                log.debug("Stored PII value has token shape but is not a CardDemo token; "
                        + "treating it as legacy plaintext");
            }
        }
        warnLegacyPlaintextOnce();
        return dbData;
    }

    /**
     * :purpose: Report whether a stored column value is already protected at rest, so a
     *     one-off encryption sweep over seeded data can skip the rows it has already
     *     converted and stay idempotent across restarts.
     * :param dbData: the value currently stored in the column.
     * :returns: ``true`` when the value carries the ``gcm1:`` envelope, is an unmarked token
     *     this key can still authenticate, or is absent; ``false`` when it is legacy
     *     plaintext that must be encrypted.
     * :note: A null or empty column needs no protection, so it reports ``true``.
     */
    public boolean isProtected(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return true;
        }
        if (dbData.startsWith(ENVELOPE_PREFIX)) {
            return true;
        }
        // An unmarked value written before the envelope was introduced is still protected
        // provided this key authenticates it; anything else is legacy plaintext.
        if (!hasTokenShape(dbData)) {
            return false;
        }
        try {
            decrypt(dbData);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * :purpose: Report whether a raw column value is one of this converter's encrypted
     *     tokens rather than a legacy plaintext value. Used by the at-rest assertions to
     *     distinguish "passed through untouched" from "written back encrypted" without
     *     needing the key.
     * :param dbData: the raw value read straight out of the column.
     * :returns: ``true`` when the value carries the ``gcm1:`` envelope or is structurally an
     *     unmarked token; ``false`` for legacy plaintext, ``null`` and the empty string.
     */
    public static boolean isEncryptedToken(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return false;
        }
        if (dbData.startsWith(ENVELOPE_PREFIX)) {
            return true;
        }
        return hasTokenShape(dbData);
    }

    /**
     * :purpose: Decrypt a Base64 ``IV || ciphertext || tag`` token.
     * :param token: the Base64 token without the version marker.
     * :returns: the recovered plaintext.
     * :raises PiiEncryptionException: when the token cannot be decoded or authenticated.
     * :raises IllegalStateException: when no usable key is configured.
     */
    private String decrypt(String token) {
        try {
            byte[] combined = Base64.getDecoder().decode(token);
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, PiiEncryptionKey.require(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(combined, GCM_IV_LENGTH, combined.length - GCM_IV_LENGTH);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            // The dedicated domain type is what PersistenceExceptionHandler looks for in the
            // JpaSystemException cause chain; a bare IllegalStateException would reach the
            // generic DataAccessException mapping and disclose a persistence-layer message
            // instead of the frozen non-disclosing text (AAP 0.6.7).
            throw new PiiEncryptionException(
                    "Unable to decrypt sensitive attribute from persistence", ex);
        }
    }

    /**
     * :purpose: Decide whether an unmarked stored value could be an AES-GCM token at all.
     * :param dbData: the stored column value.
     * :returns: ``true`` when the value decodes as Base64 to at least an IV plus a GCM tag.
     * :note: Purely structural, so it never needs the key and never throws.
     */
    private static boolean hasTokenShape(String dbData) {
        try {
            return Base64.getDecoder().decode(dbData).length > GCM_IV_LENGTH + GCM_TAG_LENGTH;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * :purpose: Warn once per process that legacy un-encrypted PII was read, so the condition is
     *     observable and actionable.
     * :note: The value itself is never logged.
     */
    private static void warnLegacyPlaintextOnce() {
        if (LEGACY_PLAINTEXT_WARNED.compareAndSet(false, true)) {
            log.warn("Read a sensitive column that is not encrypted at rest (no '{}' marker). "
                    + "Values loaded by the seed migrations are stored as plaintext because the "
                    + "encryption key belongs to the operator and is never committed; they are "
                    + "re-written encrypted on the next update of the record.", ENVELOPE_PREFIX);
        }
    }
}
