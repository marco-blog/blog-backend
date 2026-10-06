package net.java21.blog.backend.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 개인정보 문자열 ↔ 암호문(VARBINARY {@code *_enc} 컬럼). 엔티티 필드에 {@code @Convert(converter = EncryptedStringConverter.class)}로 붙인다.
 * Hibernate가 Spring 빈 컨테이너로 만들므로 생성자 주입을 받는다.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, byte[]> {

    private final PersonalDataEncryptor encryptor;

    public EncryptedStringConverter(PersonalDataEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    @Override
    public byte[] convertToDatabaseColumn(String attribute) {
        return encryptor.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(byte[] dbData) {
        return encryptor.decrypt(dbData);
    }
}
