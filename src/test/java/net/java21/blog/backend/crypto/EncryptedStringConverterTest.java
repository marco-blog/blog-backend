package net.java21.blog.backend.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EncryptedStringConverterTest {

    private final EncryptedStringConverter converter =
            new EncryptedStringConverter(new PersonalDataEncryptor(TestKeys.v1()));

    @Test
    void storesCiphertextAndReadsPlaintext() {
        byte[] column = converter.convertToDatabaseColumn("user@example.com");
        assertThat(column[0]).isEqualTo((byte) 1);
        assertThat(converter.convertToEntityAttribute(column)).isEqualTo("user@example.com");
    }

    @Test
    void nullColumnStaysNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
