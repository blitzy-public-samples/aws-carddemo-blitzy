package com.carddemo.common.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * JPA attribute converter that transparently encrypts and decrypts sensitive
 * personally-identifiable string columns (SSN, government-issued id, EFT
 * account id, and the card verification value) at rest using AES-256-GCM.
 *
 * :purpose: Ensure regulated PII is never stored as plaintext in the database,
 *     satisfying the encrypt-permitted-PII control while leaving the in-memory
 *     Java field value unchanged for business logic.
 * :output: On write, a URL-safe Base64 token of ``IV || ciphertext || GCM-tag``;
 *     on read, the original plaintext. ``null`` and empty inputs pass through
 *     unchanged so nullable columns and blank fixed-width fields are preserved.
 *
 * The 256-bit key is resolved once from the ``CARDDEMO_PII_KEY`` environment
 * variable or the ``carddemo.pii.key`` system property, expected as a Base64
 * value of 32 bytes. When no key is configured any attempt to encrypt or
 * decrypt fails fast, so a misconfigured deployment cannot silently persist
 * plaintext.
 */
@Converter
public class CryptoConverter implements AttributeConverter<String, String> {

    /** :purpose: Environment variable holding the Base64-encoded 256-bit key. */
    public static final String KEY_ENV = "CARDDEMO_PII_KEY";

    /** :purpose: System property fallback holding the Base64-encoded key. */
    public static final String KEY_PROPERTY = "carddemo.pii.key";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int AES_KEY_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Encrypts an attribute value before it is written to the database column.
     *
     * :param attribute: the plaintext attribute value, possibly ``null``.
     * :output: the Base64 ciphertext token, or the original ``null``/empty value.
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
            cipher.init(Cipher.ENCRYPT_MODE, resolveKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to encrypt sensitive attribute for persistence", ex);
        }
    }

    /**
     * Decrypts a column value after it is read from the database.
     *
     * :param dbData: the stored Base64 ciphertext token, possibly ``null``.
     * :output: the recovered plaintext, or the original ``null``/empty value.
     */
    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return dbData;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(dbData);
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, resolveKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(combined, GCM_IV_LENGTH, combined.length - GCM_IV_LENGTH);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to decrypt sensitive attribute from persistence", ex);
        }
    }

    /**
     * Resolves the configured AES key from the environment or a system property.
     *
     * :output: the 256-bit AES {@link SecretKeySpec}.
     */
    private SecretKeySpec resolveKey() {
        String encoded = System.getenv(KEY_ENV);
        if (encoded == null || encoded.isBlank()) {
            encoded = System.getProperty(KEY_PROPERTY);
        }
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException(
                    "PII encryption key is not configured; set the " + KEY_ENV
                            + " environment variable or the " + KEY_PROPERTY
                            + " system property to a Base64-encoded 256-bit key");
        }
        byte[] keyBytes = Base64.getDecoder().decode(encoded.trim());
        if (keyBytes.length != AES_KEY_BYTES) {
            throw new IllegalStateException(
                    "PII encryption key must decode to " + AES_KEY_BYTES + " bytes (256 bits)");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}
