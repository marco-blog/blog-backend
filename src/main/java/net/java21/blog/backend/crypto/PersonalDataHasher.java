package net.java21.blog.backend.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 암호화된 개인정보를 정확히 찾기 위한 검색용 해시(FR-135). HMAC-SHA256, 결과는 소문자 16진수 64자({@code *_hash} 컬럼).
 * 해시 키는 암호화 키와 따로 두며 교체하지 않는다.
 */
public class PersonalDataHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public PersonalDataHasher(CryptoProperties properties) {
        this.key = new SecretKeySpec(CryptoKeys.decode256(properties.hashKey(), "blog.crypto.hash-key"), ALGORITHM);
    }

    /** 이메일 정규화: 앞뒤 공백 제거 + 소문자. 저장(email_enc)과 해시(email_hash) 모두 이 값을 쓴다. */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    /** 정규화한 이메일의 해시. */
    public String hashEmail(String email) {
        return hash(normalizeEmail(email));
    }

    /** 값 그대로의 해시. {@code null}은 {@code null}. */
    public String hash(String value) {
        if (value == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new PersonalDataCryptoException("Hashing failed", e);
        }
    }
}
