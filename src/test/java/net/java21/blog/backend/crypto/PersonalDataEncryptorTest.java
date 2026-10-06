package net.java21.blog.backend.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PersonalDataEncryptorTest {

    private final PersonalDataEncryptor encryptor = new PersonalDataEncryptor(TestKeys.v1());

    @Nested
    class RoundTrip {

        @ParameterizedTest
        @ValueSource(strings = {"user@example.com", "", "192.168.0.1", "한글 이메일@예시.한국", "2001:db8::1"})
        void decryptsWhatWasEncrypted(String plain) {
            assertThat(encryptor.decrypt(encryptor.encrypt(plain))).isEqualTo(plain);
        }

        @Test
        void nullStaysNull() {
            assertThat(encryptor.encrypt(null)).isNull();
            assertThat(encryptor.decrypt(null)).isNull();
        }

        @Test
        void formatIsVersionIvCiphertextTag() {
            byte[] plain = "user@example.com".getBytes(StandardCharsets.UTF_8);
            byte[] sealed = encryptor.encrypt("user@example.com");
            assertThat(sealed[0]).isEqualTo((byte) 1);
            assertThat(sealed).hasSize(1 + 12 + plain.length + 16);
            assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("user@example.com");
        }

        @Test
        void sameValueEncryptsDifferentlyEachTime() {
            byte[] a = encryptor.encrypt("same");
            byte[] b = encryptor.encrypt("same");
            assertThat(a).isNotEqualTo(b);
            assertThat(encryptor.decrypt(a)).isEqualTo(encryptor.decrypt(b));
        }
    }

    @Nested
    class Tampering {

        @ParameterizedTest
        @ValueSource(ints = {1, 12, 13, 20, -1})
        void flippedByteIsRejected(int index) {
            byte[] sealed = encryptor.encrypt("user@example.com");
            int i = index < 0 ? sealed.length + index : index;
            sealed[i] ^= 0x01;
            assertThatThrownBy(() -> encryptor.decrypt(sealed))
                    .isInstanceOf(PersonalDataCryptoException.class)
                    .hasMessageNotContaining("user@example.com");
        }

        @Test
        void changedVersionByteIsRejected() {
            PersonalDataEncryptor rotated = new PersonalDataEncryptor(TestKeys.v1AndV2Active2());
            byte[] sealed = rotated.encrypt("user@example.com");
            sealed[0] = 1;
            assertThatThrownBy(() -> rotated.decrypt(sealed)).isInstanceOf(PersonalDataCryptoException.class);
        }

        @Test
        void truncatedIsRejected() {
            assertThatThrownBy(() -> encryptor.decrypt(new byte[] {1, 2, 3}))
                    .isInstanceOf(PersonalDataCryptoException.class)
                    .hasMessageContaining("too short");
        }

        @Test
        void otherKeyCannotDecrypt() {
            byte[] sealed = encryptor.encrypt("user@example.com");
            PersonalDataEncryptor other = new PersonalDataEncryptor(
                    new CryptoProperties(1, Map.of(1, TestKeys.KEY_V2), TestKeys.HASH_KEY));
            assertThatThrownBy(() -> other.decrypt(sealed)).isInstanceOf(PersonalDataCryptoException.class);
        }
    }

    @Nested
    class KeyRotation {

        private final PersonalDataEncryptor rotated = new PersonalDataEncryptor(TestKeys.v1AndV2Active2());

        @Test
        void oldVersionStillDecryptsAfterRotation() {
            byte[] old = encryptor.encrypt("user@example.com");
            assertThat(rotated.keyVersionOf(old)).isEqualTo(1);
            assertThat(rotated.decrypt(old)).isEqualTo("user@example.com");
        }

        @Test
        void newValuesUseActiveVersion() {
            byte[] sealed = rotated.encrypt("user@example.com");
            assertThat(rotated.activeKeyVersion()).isEqualTo(2);
            assertThat(rotated.keyVersionOf(sealed)).isEqualTo(2);
            assertThat(rotated.decrypt(sealed)).isEqualTo("user@example.com");
        }

        @Test
        void reEncryptionMovesToActiveVersion() {
            byte[] old = encryptor.encrypt("user@example.com");
            byte[] renewed = rotated.encrypt(rotated.decrypt(old));
            assertThat(rotated.keyVersionOf(renewed)).isEqualTo(rotated.activeKeyVersion());
        }

        @Test
        void removedKeyVersionFailsClearly() {
            byte[] fromV2 = rotated.encrypt("user@example.com");
            assertThatThrownBy(() -> encryptor.decrypt(fromV2))
                    .isInstanceOf(PersonalDataCryptoException.class)
                    .hasMessageContaining("No key for version 2");
        }
    }

    @Nested
    class InvalidConfiguration {

        @Test
        void keyWithWrongLengthFails() {
            String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
            assertThatThrownBy(() -> new PersonalDataEncryptor(new CryptoProperties(1, Map.of(1, shortKey), null)))
                    .isInstanceOf(PersonalDataCryptoException.class)
                    .hasMessageContaining("must be 32 bytes")
                    .hasMessageContaining("was 16");
        }

        @Test
        void missingKeysFail() {
            assertThatThrownBy(() -> new PersonalDataEncryptor(new CryptoProperties(1, null, null)))
                    .isInstanceOf(PersonalDataCryptoException.class)
                    .hasMessageContaining("blog.crypto.keys is missing");
            assertThatThrownBy(() -> new PersonalDataEncryptor(new CryptoProperties(1, Map.of(), null)))
                    .isInstanceOf(PersonalDataCryptoException.class);
        }

        @Test
        void blankKeyFails() {
            assertThatThrownBy(() -> new PersonalDataEncryptor(new CryptoProperties(1, Map.of(1, " "), null)))
                    .isInstanceOf(PersonalDataCryptoException.class)
                    .hasMessageContaining("blog.crypto.keys[1] is missing");
        }

        @Test
        void invalidBase64Fails() {
            assertThatThrownBy(() -> new PersonalDataEncryptor(new CryptoProperties(1, Map.of(1, "not base64!"), null)))
                    .isInstanceOf(PersonalDataCryptoException.class)
                    .hasMessageContaining("not valid Base64");
        }

        @Test
        void activeVersionWithoutKeyFails() {
            assertThatThrownBy(() -> new PersonalDataEncryptor(new CryptoProperties(2, Map.of(1, TestKeys.KEY_V1), null)))
                    .isInstanceOf(PersonalDataCryptoException.class)
                    .hasMessageContaining("active-key-version 2");
            assertThatThrownBy(() -> new PersonalDataEncryptor(new CryptoProperties(null, Map.of(1, TestKeys.KEY_V1), null)))
                    .isInstanceOf(PersonalDataCryptoException.class);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 256, -1})
        void versionOutsideOneByteFails(int version) {
            assertThatThrownBy(() -> new PersonalDataEncryptor(
                    new CryptoProperties(version, Map.of(version, TestKeys.KEY_V1), null)))
                    .isInstanceOf(PersonalDataCryptoException.class)
                    .hasMessageContaining("1..255");
        }

        @Test
        void nullVersionFails() {
            Map<Integer, String> keys = new HashMap<>();
            keys.put(null, TestKeys.KEY_V1);
            assertThatThrownBy(() -> new PersonalDataEncryptor(new CryptoProperties(1, keys, null)))
                    .isInstanceOf(PersonalDataCryptoException.class);
        }
    }
}
