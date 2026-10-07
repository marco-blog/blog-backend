package net.java21.blog.backend.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** 007 T012: 알림 종류·대상 값(notifications.type 30자, target_type 20자). */
class NotificationTypeTest {

    @Test
    void externalTypesExistAndFitColumns() {
        assertThat(NotificationType.valueOf("EXTERNAL_BLOG_APPROVED")).isNotNull();
        assertThat(NotificationType.valueOf("EXTERNAL_BLOG_REJECTED")).isNotNull();
        assertThat(NotificationType.valueOf("EXTERNAL_FEED_STOPPED")).isNotNull();
        assertThat(NotificationTargetType.valueOf("EXTERNAL_BLOG")).isNotNull();
        assertThat(Arrays.stream(NotificationType.values()).map(Enum::name))
                .allSatisfy(name -> assertThat(name).hasSizeLessThanOrEqualTo(30));
        assertThat(Arrays.stream(NotificationTargetType.values()).map(Enum::name))
                .allSatisfy(name -> assertThat(name).hasSizeLessThanOrEqualTo(20));
    }
}
