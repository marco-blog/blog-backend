package net.java21.blog.backend.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** test 프로필(application-test.yml)의 고정 키로 빈이 뜨는지, 키가 잘못되면 기동이 실패하는지 확인한다. */
@SpringBootTest(classes = CryptoConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CryptoConfigTest {

    @Autowired
    private PersonalDataEncryptor encryptor;

    @Autowired
    private PersonalDataHasher hasher;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CryptoConfig.class);

    @Test
    void testProfileKeysBind() {
        assertThat(encryptor.activeKeyVersion()).isEqualTo(1);
        assertThat(encryptor.decrypt(encryptor.encrypt("user@example.com"))).isEqualTo("user@example.com");
        assertThat(hasher.hashEmail("User@Example.com"))
                .isEqualTo("994d625d89da0b18dcd0704c6deb71a36db502ac3d6bbebcf3e730b4231dea23");
    }

    @Test
    void startsWithVersionedKeys() {
        runner.withPropertyValues(
                        "blog.crypto.active-key-version=2",
                        "blog.crypto.keys[1]=" + TestKeys.KEY_V1,
                        "blog.crypto.keys[2]=" + TestKeys.KEY_V2,
                        "blog.crypto.hash-key=" + TestKeys.HASH_KEY)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PersonalDataEncryptor.class).activeKeyVersion()).isEqualTo(2);
                });
    }

    @Test
    void failsFastWithoutKeys() {
        runner.run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().isInstanceOf(PersonalDataCryptoException.class));
    }

    @Test
    void failsFastWithShortHashKey() {
        runner.withPropertyValues(
                        "blog.crypto.active-key-version=1",
                        "blog.crypto.keys[1]=" + TestKeys.KEY_V1,
                        "blog.crypto.hash-key=" + Base64.getEncoder().encodeToString(new byte[16]))
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("blog.crypto.hash-key must be 32 bytes"));
    }
}
