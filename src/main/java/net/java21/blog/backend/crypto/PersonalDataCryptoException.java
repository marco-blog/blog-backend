package net.java21.blog.backend.crypto;

/** 개인정보 암복호화 실패 또는 잘못된 키 설정. 메시지에 평문·키를 넣지 않는다. */
public class PersonalDataCryptoException extends IllegalStateException {

    public PersonalDataCryptoException(String message) {
        super(message);
    }

    public PersonalDataCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
