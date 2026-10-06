package net.java21.blog.backend.crypto;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 개인정보 암호화 키(FR-134~136, 001 data-model "개인정보 암호화 규칙").
 * 값은 저장소에 커밋하지 않는다. local은 저장소 루트 {@code .env}, prod는 환경 변수, test는 테스트 전용 고정 키.
 *
 * @param activeKeyVersion 새로 암호화할 때 쓰는 키 버전(1~255, 암호문 첫 바이트)
 * @param keys             키 버전 → Base64 256비트(32바이트) AES 키. 교체 중에는 이전 버전 키도 남겨 둔다.
 * @param hashKey          검색용 HMAC-SHA256 키(Base64, 32바이트). 바꾸면 모든 {@code *_hash}를 다시 계산해야 한다.
 */
@ConfigurationProperties("blog.crypto")
public record CryptoProperties(Integer activeKeyVersion, Map<Integer, String> keys, String hashKey) {
}
