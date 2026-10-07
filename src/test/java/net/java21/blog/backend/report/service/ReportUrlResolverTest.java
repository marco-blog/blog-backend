package net.java21.blog.backend.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.config.SiteProperties;
import net.java21.blog.backend.report.domain.ReportTargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 005 T031: 권리 침해 주소 해석. 같은 호스트·포트의 글·댓글·트랙백·방명록 주소만, 소속(글 id·블로그 주소)이 맞아야 하고, 그 밖에는 빈 값.
 */
@ExtendWith(MockitoExtension.class)
class ReportUrlResolverTest {

    @Mock
    private ReportTargetHandler posts;
    @Mock
    private ReportTargetHandler comments;
    @Mock
    private ReportTargetHandler guestbook;
    @Mock
    private ReportTargetHandler trackbacks;

    private ReportUrlResolver resolver;

    @BeforeEach
    void setUp() {
        lenient().when(posts.type()).thenReturn(ReportTargetType.POST);
        lenient().when(comments.type()).thenReturn(ReportTargetType.COMMENT);
        lenient().when(guestbook.type()).thenReturn(ReportTargetType.GUESTBOOK);
        lenient().when(trackbacks.type()).thenReturn(ReportTargetType.TRACKBACK);
        resolver = resolver("https://blog.java21.net");
        lenient().when(posts.resolveForAdmin(5L)).thenReturn(target(ReportTargetType.POST, 5L, 5L, "marco"));
        lenient().when(comments.resolveForAdmin(9L)).thenReturn(target(ReportTargetType.COMMENT, 9L, 5L, "marco"));
        lenient().when(trackbacks.resolveForAdmin(3L)).thenReturn(target(ReportTargetType.TRACKBACK, 3L, 5L,
                "marco"));
        lenient().when(guestbook.resolveForAdmin(4L)).thenReturn(target(ReportTargetType.GUESTBOOK, 4L, null,
                "marco"));
    }

    @Test
    void resolvesPostsCommentsTrackbacksAndGuestbook() {
        assertThat(resolver.resolve("https://blog.java21.net/marco/5").orElseThrow().id()).isEqualTo(5L);
        assertThat(resolver.resolve(" https://BLOG.java21.net:443/marco/5/?utm=x ").orElseThrow().type())
                .isEqualTo(ReportTargetType.POST);
        assertThat(resolver.resolve("https://blog.java21.net/marco/5#comment-9").orElseThrow().type())
                .isEqualTo(ReportTargetType.COMMENT);
        assertThat(resolver.resolve("https://blog.java21.net/marco/5#trackback-3").orElseThrow().type())
                .isEqualTo(ReportTargetType.TRACKBACK);
        assertThat(resolver.resolve("https://blog.java21.net/marco/guestbook#guestbook-4").orElseThrow().type())
                .isEqualTo(ReportTargetType.GUESTBOOK);
        assertThat(resolver.resolve("https://blog.java21.net/marco/5#unknown-1").orElseThrow().type())
                .isEqualTo(ReportTargetType.POST);
        // 글 주소의 방명록 조각은 무시하고 글로 본다.
        assertThat(resolver.resolve("https://blog.java21.net/marco/5#guestbook-4").orElseThrow().type())
                .isEqualTo(ReportTargetType.POST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example/marco/5", "http://blog.java21.net/marco/5",
            "https://blog.java21.net:8443/marco/5", "https://blog.java21.net/other/5",
            "https://blog.java21.net/marco/5#comment-10", "https://blog.java21.net/other/5#comment-9",
            "https://blog.java21.net/marco/6#trackback-3", "https://blog.java21.net/other/guestbook#guestbook-4",
            "https://blog.java21.net/marco/guestbook", "https://blog.java21.net/marco/guestbook#comment-9",
            "https://blog.java21.net/marco", "https://blog.java21.net/marco/5/edit", "not a url ^",
            "/marco/5", "https://blog.java21.net/marco/99999999999999999999"})
    void everythingElseIsUnresolved(String url) {
        lenient().when(comments.resolveForAdmin(10L)).thenThrow(new BusinessException(ErrorCode.CONTENT_NOT_FOUND,
                "x"));
        assertThat(resolver.resolve(url)).isEmpty();
    }

    @Test
    void basePathAndHttpDefaultPort() {
        ReportUrlResolver withPath = resolver("http://localhost/blog/");
        assertThat(withPath.resolve("http://localhost:80/blog/marco/5")).isPresent();
        assertThat(withPath.resolve("http://localhost/marco/5")).isEmpty();
        when(posts.resolveForAdmin(6L)).thenThrow(new BusinessException(ErrorCode.CONTENT_NOT_FOUND, "x"));
        assertThat(withPath.resolve("http://localhost/blog/marco/6")).isEmpty();
    }

    private ReportUrlResolver resolver(String base) {
        return new ReportUrlResolver(new SiteProperties(base),
                new ReportTargetHandlers(List.of(posts, comments, guestbook, trackbacks)));
    }

    private static ReportTarget target(ReportTargetType type, long id, Long postId, String handle) {
        return new ReportTarget(type, id, null, null, postId, handle);
    }
}
