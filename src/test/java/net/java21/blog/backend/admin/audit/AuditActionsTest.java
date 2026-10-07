package net.java21.blog.backend.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 006 T006(research A6): {@link AuditActions#ALL}·{@link AuditActions#TARGETS}가 클래스의 상수 전부와 같고(빠뜨림·중복 없음),
 * 006 data-model {@code action} 표의 003·001·006 행이 모두 있으며 {@code admin_audit_logs} 칸 길이(50·30자) 안이다.
 */
class AuditActionsTest {

    @Test
    void allListsEveryActionConstantOnce() {
        assertThat(AuditActions.ALL).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(constants(false));
    }

    @Test
    void targetsListsEveryTargetConstantOnce() {
        assertThat(AuditActions.TARGETS).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(constants(true));
    }

    @Test
    void dataModelRowsOf001003And006ArePresent() {
        assertThat(AuditActions.ALL).contains(
                "TOPIC_CREATE", "TOPIC_UPDATE", "TOPIC_REORDER", "TOPIC_HIDE", "TOPIC_UNHIDE", "TOPIC_PIN",
                "TOPIC_UNPIN", "CURATION_CREATE", "CURATION_UPDATE", "CURATION_DELETE", "PORTAL_EXCLUDE",
                "PORTAL_UNEXCLUDE", "SETTING_CHANGE", "RELEASE_NOTE_CREATE", "RELEASE_NOTE_UPDATE",
                "RELEASE_NOTE_PUBLISH", "RELEASE_NOTE_UNPUBLISH", "RELEASE_NOTE_DELETE", "USER_BLOG_LIMIT_CHANGE",
                "ROLE_GRANT", "ROLE_REVOKE");
        assertThat(AuditActions.TARGETS).contains("USER", "TOPIC", "CURATION", "POST", "SETTING", "RELEASE_NOTE");
    }

    @Test
    void externalRowsOf007ArePresent() {
        assertThat(AuditActions.ALL).contains("EXTERNAL_BLOG_CREATE", "EXTERNAL_BLOG_APPROVE", "EXTERNAL_BLOG_REJECT",
                "EXTERNAL_BLOG_UPDATE", "EXTERNAL_BLOG_PAUSE", "EXTERNAL_BLOG_RESUME", "EXTERNAL_BLOG_BLOCK",
                "EXTERNAL_POST_REMOVE", "TOPIC_MAPPING_RULE_CREATE", "TOPIC_MAPPING_RULE_UPDATE",
                "TOPIC_MAPPING_RULE_DELETE", "CLASSIFICATION_CONFIRM");
        assertThat(AuditActions.TARGETS).contains("EXTERNAL_BLOG", "EXTERNAL_POST", "TOPIC_MAPPING_RULE",
                "CLASSIFICATION_REVIEW");
    }

    @Test
    void valuesFitColumns() {
        assertThat(AuditActions.ALL).allSatisfy(action -> assertThat(action).hasSizeLessThanOrEqualTo(50)
                .matches("[A-Z_]+"));
        assertThat(AuditActions.TARGETS).allSatisfy(target -> assertThat(target).hasSizeLessThanOrEqualTo(30)
                .matches("[A-Z_]+"));
    }

    /** public static final String 상수의 값. {@code targets}면 {@code TARGET_*}만, 아니면 그 밖의 것만. */
    private static List<String> constants(boolean targets) {
        return Arrays.stream(AuditActions.class.getDeclaredFields())
                .filter(f -> Modifier.isPublic(f.getModifiers()) && Modifier.isStatic(f.getModifiers())
                        && f.getType() == String.class)
                .filter(f -> f.getName().startsWith("TARGET_") == targets)
                .map(AuditActionsTest::value)
                .toList();
    }

    private static String value(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
