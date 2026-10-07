package net.java21.blog.backend.external.verify;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/** 소유 인증 코드(007 research E8): {@code java21-verify-} + base62 12자. */
@Component
public class VerificationCodeGenerator {

    public static final String PREFIX = "java21-verify-";
    public static final int RANDOM_LENGTH = 12;
    private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
            .toCharArray();

    private final SecureRandom random = new SecureRandom();

    public String next() {
        char[] out = new char[RANDOM_LENGTH];
        for (int i = 0; i < out.length; i++) {
            out[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return PREFIX + new String(out);
    }

    public static boolean isCode(String value) {
        return value != null && value.matches(PREFIX + "[0-9A-Za-z]{" + RANDOM_LENGTH + "}");
    }
}
