package net.java21.blog.backend.crypto;

import java.util.Base64;

/** Base64 키 해석과 길이 검사. */
final class CryptoKeys {

    static final int KEY_BYTES = 32;

    private CryptoKeys() {
    }

    static byte[] decode256(String base64, String name) {
        if (base64 == null || base64.isBlank()) {
            throw new PersonalDataCryptoException(name + " is missing");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new PersonalDataCryptoException(name + " is not valid Base64", e);
        }
        if (key.length != KEY_BYTES) {
            throw new PersonalDataCryptoException(
                    name + " must be " + KEY_BYTES + " bytes (256 bits) but was " + key.length);
        }
        return key;
    }
}
