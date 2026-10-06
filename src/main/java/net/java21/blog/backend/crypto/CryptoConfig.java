package net.java21.blog.backend.crypto;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 개인정보 암호화 빈. 키가 없거나 길이가 틀리면 기동에 실패한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CryptoProperties.class)
public class CryptoConfig {

    @Bean
    PersonalDataEncryptor personalDataEncryptor(CryptoProperties properties) {
        return new PersonalDataEncryptor(properties);
    }

    @Bean
    PersonalDataHasher personalDataHasher(CryptoProperties properties) {
        return new PersonalDataHasher(properties);
    }
}
