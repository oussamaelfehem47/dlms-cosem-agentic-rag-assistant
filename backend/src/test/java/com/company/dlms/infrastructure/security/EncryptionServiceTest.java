package com.company.dlms.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptionServiceTest {

    @Test
    void encryptDecrypt_roundTrip_returnsOriginal() {
        EncryptionService svc = validService();
        String original = "sensitive-dlms-payload-{obis:1.0.1.8.0.255}";
        String encrypted = svc.encrypt(original);
        assertThat(encrypted).isNotEqualTo(original);
        assertThat(svc.decrypt(encrypted)).isEqualTo(original);
    }

    @Test
    void encrypt_differentPlaintexts_produceDifferentCiphertexts() {
        EncryptionService svc = validService();
        String a = svc.encrypt("hello");
        String b = svc.encrypt("hello");
        // Random IV means even same plaintext produces different ciphertext
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void encrypt_null_returnsNull() {
        EncryptionService svc = validService();
        assertThat(svc.encrypt(null)).isNull();
        assertThat(svc.decrypt(null)).isNull();
    }

    @Test
    void validate_missingKey_throwsIllegalStateException() {
        EncryptionService svc = new EncryptionService("");
        assertThatThrownBy(svc::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SESSION_ENCRYPTION_KEY");
    }

    @Test
    void validate_shortKey_throwsIllegalStateException() {
        EncryptionService svc = new EncryptionService("tooshort15byte");
        assertThatThrownBy(svc::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("16 bytes");
    }

    @Test
    void validate_longKey_throwsIllegalStateException() {
        EncryptionService svc = new EncryptionService("thiskeyiswaytoolong_17chars");
        assertThatThrownBy(svc::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("16 bytes");
    }

    @Test
    void validate_exactly16Bytes_noException() {
        EncryptionService svc = validService();
        svc.validate(); // must not throw
    }

    private static EncryptionService validService() {
        EncryptionService svc = new EncryptionService("dlmsassistant16c");
        svc.validate();
        return svc;
    }
}
