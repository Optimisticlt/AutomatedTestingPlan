package com.webtestpro.api.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

@DisplayName("EncryptionService – AES-256-GCM with AAD")
class EncryptionServiceTest {

    // 32 bytes, Base64-encoded valid test key
    private static final String TEST_KEY_B64 =
            Base64.getEncoder().encodeToString(new byte[32]); // 32 zero bytes = "AAAA...="

    private EncryptionService svc;

    @BeforeEach
    void setUp() {
        svc = new EncryptionService(TEST_KEY_B64);
    }

    @Test
    @DisplayName("encrypt then decrypt returns original plaintext")
    void roundTripReturnsPlaintext() {
        String plain = "super-secret-password";
        String cipher = svc.encrypt(plain, "tc_env_variable", "var_value", 1L, 42L);
        String decrypted = svc.decrypt(cipher, "tc_env_variable", "var_value", 1L, 42L);
        assertThat(decrypted).isEqualTo(plain);
    }

    @Test
    @DisplayName("same plaintext produces different ciphertexts (random IV)")
    void samePlaintextDifferentCiphertext() {
        String plain = "my-secret";
        String c1 = svc.encrypt(plain, "tc_env_variable", "var_value", 1L, 1L);
        String c2 = svc.encrypt(plain, "tc_env_variable", "var_value", 1L, 1L);
        assertThat(c1).isNotEqualTo(c2);
    }

    @Test
    @DisplayName("wrong AAD (different column) causes decryption to throw")
    void wrongAadThrows() {
        String cipher = svc.encrypt("secret", "tc_env_variable", "var_value", 1L, 42L);
        // Attempt to decrypt with different column name – AAD mismatch → GCM auth failure
        assertThatThrownBy(() ->
                svc.decrypt(cipher, "tc_env_variable", "OTHER_COLUMN", 1L, 42L)
        ).isInstanceOf(RuntimeException.class)
         .hasMessageContaining("Decryption failed");
    }

    @Test
    @DisplayName("wrong AAD (different recordId) causes decryption to throw")
    void wrongRecordIdThrows() {
        String cipher = svc.encrypt("secret", "tc_env_variable", "var_value", 1L, 42L);
        assertThatThrownBy(() ->
                svc.decrypt(cipher, "tc_env_variable", "var_value", 1L, 99L)
        ).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("null plaintext returns null (null-safe)")
    void nullPlaintextReturnsNull() {
        assertThat(svc.encrypt(null, "t", "c", 1L, 1L)).isNull();
    }

    @Test
    @DisplayName("null ciphertext returns null (null-safe)")
    void nullCiphertextReturnsNull() {
        assertThat(svc.decrypt(null, "t", "c", 1L, 1L)).isNull();
    }

    @Test
    @DisplayName("constructor throws when key is not 32 bytes")
    void shortKeyThrows() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]); // 16 bytes = AES-128
        assertThatThrownBy(() -> new EncryptionService(shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("blank key uses insecure fallback (dev only)")
    void blankKeyUsesFallback() {
        // Should not throw, just log a warning and use zero-byte fallback
        EncryptionService fallbackSvc = new EncryptionService("");
        String cipher = fallbackSvc.encrypt("hello", "t", "c", 0L, 0L);
        assertThat(fallbackSvc.decrypt(cipher, "t", "c", 0L, 0L)).isEqualTo("hello");
    }
}
