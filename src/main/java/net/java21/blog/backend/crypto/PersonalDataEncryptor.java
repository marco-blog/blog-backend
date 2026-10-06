package net.java21.blog.backend.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 개인정보 AES-256-GCM 암복호화(FR-134, FR-136).
 * 암호문 형식: {@code [키 버전 1바이트][IV 12바이트][암호문][태그 16바이트]}. 키 버전 바이트는 AAD로도 묶는다.
 * 복호화는 암호문의 키 버전으로 키를 고르므로, 키를 교체한 뒤에도 이전 버전 암호문을 읽을 수 있다.
 * 키 설정이 잘못되면 생성 시점(애플리케이션 기동)에 실패한다.
 */
public class PersonalDataEncryptor {

    static final int IV_BYTES = 12;
    static final int TAG_BITS = 128;
    private static final int MIN_LENGTH = 1 + IV_BYTES + TAG_BITS / 8;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final int activeVersion;
    private final Map<Integer, SecretKey> keys;
    private final SecureRandom random = new SecureRandom();

    public PersonalDataEncryptor(CryptoProperties properties) {
        Map<Integer, String> configured = properties.keys();
        if (configured == null || configured.isEmpty()) {
            throw new PersonalDataCryptoException("blog.crypto.keys is missing");
        }
        Map<Integer, SecretKey> parsed = new TreeMap<>();
        configured.forEach((version, base64) -> {
            if (version == null || version < 1 || version > 255) {
                throw new PersonalDataCryptoException("blog.crypto.keys version must be 1..255 but was " + version);
            }
            parsed.put(version, new SecretKeySpec(
                    CryptoKeys.decode256(base64, "blog.crypto.keys[" + version + "]"), "AES"));
        });
        Integer active = properties.activeKeyVersion();
        if (active == null || !parsed.containsKey(active)) {
            throw new PersonalDataCryptoException(
                    "blog.crypto.active-key-version " + active + " has no key in blog.crypto.keys");
        }
        this.activeVersion = active;
        this.keys = Map.copyOf(parsed);
    }

    /** 현재 키 버전으로 암호화한다. {@code null}은 {@code null}. */
    public byte[] encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        byte version = (byte) activeVersion;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeVersion), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(new byte[] {version});
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[1 + IV_BYTES + sealed.length];
            out[0] = version;
            System.arraycopy(iv, 0, out, 1, IV_BYTES);
            System.arraycopy(sealed, 0, out, 1 + IV_BYTES, sealed.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new PersonalDataCryptoException("Encryption failed", e);
        }
    }

    /** 암호문에 적힌 키 버전의 키로 복호화한다. 변조됐거나 키가 없으면 예외. {@code null}은 {@code null}. */
    public String decrypt(byte[] ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        int version = keyVersionOf(ciphertext);
        SecretKey key = keys.get(version);
        if (key == null) {
            throw new PersonalDataCryptoException("No key for version " + version);
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, ciphertext, 1, IV_BYTES));
            cipher.updateAAD(ciphertext, 0, 1);
            byte[] plain = cipher.doFinal(ciphertext, 1 + IV_BYTES, ciphertext.length - 1 - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new PersonalDataCryptoException("Decryption failed (tampered data or wrong key)", e);
        }
    }

    /** 암호문의 키 버전. 재암호화 대상(현재 버전이 아닌 것)을 고를 때 쓴다. */
    public int keyVersionOf(byte[] ciphertext) {
        if (ciphertext.length < MIN_LENGTH) {
            throw new PersonalDataCryptoException("Ciphertext is too short");
        }
        return Byte.toUnsignedInt(ciphertext[0]);
    }

    public int activeKeyVersion() {
        return activeVersion;
    }
}
