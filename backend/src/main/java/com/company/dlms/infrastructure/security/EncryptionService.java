package com.company.dlms.infrastructure.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class EncryptionService {

    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final int IV_LENGTH = 16;
    private static final int KEY_LENGTH = 16;

    private final String encryptionKey;

    public EncryptionService(@Value("${dlms.security.session.encryption-key:}") String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    @PostConstruct
    void validate() {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            throw new IllegalStateException(
                    "SESSION_ENCRYPTION_KEY is not configured — application cannot start");
        }
        if (encryptionKey.getBytes(StandardCharsets.UTF_8).length != KEY_LENGTH) {
            throw new IllegalStateException(
                    "SESSION_ENCRYPTION_KEY must be exactly 16 bytes (AES-128). Got: "
                            + encryptionKey.getBytes(StandardCharsets.UTF_8).length + " bytes");
        }
    }

    /** Encrypts plaintext using AES-128-CBC with a random IV. Returns Base64(IV || ciphertext). */
    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(encryptionKey.getBytes(StandardCharsets.UTF_8), "AES"),
                    new IvParameterSpec(iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, IV_LENGTH, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /** Decrypts Base64(IV || ciphertext) produced by {@link #encrypt}. */
    public String decrypt(String encoded) {
        if (encoded == null) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            System.arraycopy(combined, IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(encryptionKey.getBytes(StandardCharsets.UTF_8), "AES"),
                    new IvParameterSpec(iv));

            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }
}
