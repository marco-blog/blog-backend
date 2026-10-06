package net.java21.blog.backend.media.service;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 이미지 주소 키(FR-156): {@link SecureRandom} 128비트를 base62로 나타낸 22자({@code ^[0-9A-Za-z]{22}$}).
 * 순번이 아니므로 다른 이미지 주소를 추측할 수 없다.
 */
@Component
public class MediaKeyGenerator {

    public static final int LENGTH = 22;
    public static final Pattern KEY = Pattern.compile("[0-9A-Za-z]{22}");

    private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final BigInteger BASE = BigInteger.valueOf(ALPHABET.length);

    private final SecureRandom random;

    public MediaKeyGenerator() {
        this(new SecureRandom());
    }

    MediaKeyGenerator(SecureRandom random) {
        this.random = random;
    }

    public String next() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return encode(bytes);
    }

    /** 16바이트(부호 없는 128비트)를 앞을 0으로 채운 22자 base62로. */
    static String encode(byte[] bytes) {
        BigInteger value = new BigInteger(1, bytes);
        char[] out = new char[LENGTH];
        for (int i = LENGTH - 1; i >= 0; i--) {
            BigInteger[] qr = value.divideAndRemainder(BASE);
            out[i] = ALPHABET[qr[1].intValue()];
            value = qr[0];
        }
        return new String(out);
    }

    public static boolean isKey(String value) {
        return value != null && KEY.matcher(value).matches();
    }
}
