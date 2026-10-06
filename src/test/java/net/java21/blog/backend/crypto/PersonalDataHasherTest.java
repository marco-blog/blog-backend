package net.java21.blog.backend.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PersonalDataHasherTest {

    private final PersonalDataHasher hasher = new PersonalDataHasher(TestKeys.v1());

    @Test
    void matchesKnownHmacSha256() {
        assertThat(hasher.hash("user@example.com"))
                .isEqualTo("994d625d89da0b18dcd0704c6deb71a36db502ac3d6bbebcf3e730b4231dea23");
    }

    @Test
    void isStableAnd64LowercaseHex() {
        String first = hasher.hash("value");
        assertThat(first).matches("[0-9a-f]{64}").isEqualTo(hasher.hash("value"));
        assertThat(new PersonalDataHasher(TestKeys.v1()).hash("value")).isEqualTo(first);
    }

    @ParameterizedTest
    @ValueSource(strings = {"user@example.com", " User@Example.COM ", "USER@EXAMPLE.COM\t", "\nuser@example.com"})
    void emailIsNormalizedBeforeHashing(String email) {
        assertThat(hasher.hashEmail(email)).isEqualTo(hasher.hash("user@example.com"));
    }

    @Test
    void normalizeEmailTrimsAndLowercasesWithoutLocaleSurprises() {
        assertThat(PersonalDataHasher.normalizeEmail("  TITLE@EXAMPLE.COM ")).isEqualTo("title@example.com");
        assertThat(PersonalDataHasher.normalizeEmail(null)).isNull();
    }

    @Test
    void differentValuesAndKeysGiveDifferentHashes() {
        assertThat(hasher.hash("a@example.com")).isNotEqualTo(hasher.hash("b@example.com"));
        PersonalDataHasher other = new PersonalDataHasher(new CryptoProperties(1, Map.of(), TestKeys.KEY_V1));
        assertThat(other.hash("a@example.com")).isNotEqualTo(hasher.hash("a@example.com"));
    }

    @Test
    void nullStaysNull() {
        assertThat(hasher.hash(null)).isNull();
        assertThat(hasher.hashEmail(null)).isNull();
    }

    @Test
    void hashKeyWithWrongLengthFails() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[31]);
        assertThatThrownBy(() -> new PersonalDataHasher(new CryptoProperties(1, Map.of(), shortKey)))
                .isInstanceOf(PersonalDataCryptoException.class)
                .hasMessageContaining("blog.crypto.hash-key must be 32 bytes");
    }

    @Test
    void missingHashKeyFails() {
        assertThatThrownBy(() -> new PersonalDataHasher(new CryptoProperties(1, Map.of(), null)))
                .isInstanceOf(PersonalDataCryptoException.class)
                .hasMessageContaining("blog.crypto.hash-key is missing");
    }
}
