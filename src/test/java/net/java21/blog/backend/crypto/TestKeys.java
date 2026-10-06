package net.java21.blog.backend.crypto;

import java.util.Base64;
import java.util.Map;

/** 테스트 전용 고정 키(비밀 아님). application-test.yml 과 같은 값. */
final class TestKeys {

    static final String KEY_V1 = "dGVzdC1vbmx5LWtleS12MS0wMDAwMDAwMDAwMDAwMDA=";
    static final String KEY_V2 = Base64.getEncoder().encodeToString("test-only-key-v2-000000000000000".getBytes());
    static final String HASH_KEY = "dGVzdC1vbmx5LWhhc2gta2V5LTAwMDAwMDAwMDAwMDA=";

    private TestKeys() {
    }

    static CryptoProperties v1() {
        return new CryptoProperties(1, Map.of(1, KEY_V1), HASH_KEY);
    }

    static CryptoProperties v1AndV2Active2() {
        return new CryptoProperties(2, Map.of(1, KEY_V1, 2, KEY_V2), HASH_KEY);
    }
}
