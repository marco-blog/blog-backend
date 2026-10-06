package net.java21.blog.backend.blog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;

import net.java21.blog.backend.blog.ReservedHandles;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 블로그 주소 규칙(T049, FR-002, AS1·2, Edge Cases). */
class HandlePolicyTest {

    /** contracts/routes.md "예약어" 목록 그대로(문서와 코드 상수가 어긋나면 실패한다). */
    static final List<String> ROUTES_MD_RESERVED = List.of(
            "admin", "api", "assets", "static", "media", "public", "build", "favicon.ico", "robots.txt", "sitemap",
            "sitemap.xml", "signup", "login", "logout", "auth", "oauth", "me", "settings", "manage", "write", "edit",
            "password-reset", "search", "tags", "tag", "topics", "topic", "category", "feed", "rss", "atom",
            "notifications", "explore", "popular", "external", "external-blogs", "report", "reports",
            "rights-request", "trackback", "locale", "lang", "legal", "help", "about", "terms", "privacy", "policy",
            "notice", "support", "health", "blog", "www", "mail", "root", "system", "updates");

    private final HandlePolicy policy = new HandlePolicy();

    @ParameterizedTest
    @ValueSource(strings = {"marco", "abc", "a1b", "marco-dev", "a-b-c", "0123456789abcdefghij", "1st-blog"})
    void acceptsValidHandles(String handle) {
        assertThat(policy.violation(handle)).isNull();
        assertThatCode(() -> policy.check(handle)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "a", "", "0123456789abcdefghijk", "-marco", "marco-", "mar--co", "Marco", "MARCO",
            "mar co", "marco!", "마르코", "marco_dev", "marco.dev", " marco"})
    void rejectsInvalidHandles(String handle) {
        assertThat(policy.violation(handle)).isEqualTo(HandlePolicy.Violation.INVALID);
        assertThatThrownBy(() -> policy.check(handle))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.HANDLE_INVALID));
    }

    @Test
    void nullIsInvalid() {
        assertThat(policy.violation(null)).isEqualTo(HandlePolicy.Violation.INVALID);
    }

    @Test
    void reservedListMatchesRoutesDocument() {
        assertThat(ReservedHandles.NAMES).containsExactlyInAnyOrderElementsOf(ROUTES_MD_RESERVED);
    }

    static Stream<String> reservedNames() {
        return ROUTES_MD_RESERVED.stream();
    }

    @ParameterizedTest
    @MethodSource("reservedNames")
    void rejectsEveryReservedName(String name) {
        assertReserved(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "Admin", " admin", "admin ", "\tlogin\n", "Robots.TXT", "External-Blogs"})
    void reservedCheckIgnoresCaseAndSurroundingWhitespace(String name) {
        assertReserved(name);
    }

    private void assertReserved(String handle) {
        assertThat(policy.violation(handle)).isEqualTo(HandlePolicy.Violation.RESERVED);
        assertThatThrownBy(() -> policy.check(handle))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.HANDLE_RESERVED));
    }
}
