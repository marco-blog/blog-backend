package net.java21.blog.backend.support;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.java21.blog.backend.common.net.HostResolver;
import net.java21.blog.backend.common.net.OutboundProperties;
import net.java21.blog.backend.common.net.OutboundUrlGuard;
import net.java21.blog.backend.common.net.SafeHttpFetcher;
import net.java21.blog.backend.config.ExternalFeedProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * 007 시험 도구. {@link StubHttpServer}(127.0.0.1)를 공인 주소로 답하는 가짜 해석기로 내부망 검사를 통과시킨 {@link SafeHttpFetcher}와,
 * 값 일부만 바꾼 {@link ExternalFeedProperties}. 실제 인터넷에는 나가지 않는다({@code internal.test}는 사설 주소, 그 밖의 이름은
 * 해석 실패).
 */
public final class ExternalTestKit {

    public static final String SELF_HOST = "blog.java21.net";

    private ExternalTestKit() {
    }

    public static final HostResolver RESOLVER = host -> switch (host) {
        case "127.0.0.1" -> List.of(InetAddress.getByName("93.184.216.34"));
        case "internal.test" -> List.of(InetAddress.getByName("10.0.0.5"));
        default -> throw new UnknownHostException(host);
    };

    public static SafeHttpFetcher fetcher(StubHttpServer server) {
        return fetcher(server, Duration.ofSeconds(5));
    }

    public static SafeHttpFetcher fetcher(StubHttpServer server, Duration requestTimeout) {
        OutboundProperties outbound = new OutboundProperties(List.of(80, 443, server.port()), false);
        SafeHttpFetcher.Settings settings = new SafeHttpFetcher.Settings(Duration.ofSeconds(2), requestTimeout, 3,
                2 * 1024 * 1024, 1024 * 1024, 5 * 1024 * 1024, Set.of(SELF_HOST), "java21-blog-feed/1.0 (+test)");
        return new SafeHttpFetcher(new OutboundUrlGuard(outbound, RESOLVER), settings);
    }

    /** 기본값에서 {@code blog.external.*} 일부만 바꾼 프로퍼티(키는 kebab-case, 예: {@code preview-per-hour}). */
    public static ExternalFeedProperties properties(String... keyValues) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            values.put("blog.external." + keyValues[i], keyValues[i + 1]);
        }
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("blog.external",
                ExternalFeedProperties.class);
    }

    /** RSS 2.0 문서. 항목은 {@code [guid, title, link, pubDate(RFC 1123) 또는 null, description]}. */
    public static String rss(String title, String siteUrl, String description, String[]... items) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel>")
                .append("<title>").append(title).append("</title><link>").append(siteUrl).append("</link><description>")
                .append(description == null ? "" : description).append("</description>");
        for (String[] item : items) {
            sb.append("<item>");
            if (item[0] != null) {
                sb.append("<guid isPermaLink=\"false\">").append(item[0]).append("</guid>");
            }
            sb.append("<title>").append(item[1]).append("</title><link>").append(item[2]).append("</link>");
            if (item.length > 3 && item[3] != null) {
                sb.append("<pubDate>").append(item[3]).append("</pubDate>");
            }
            if (item.length > 4 && item[4] != null) {
                sb.append("<description>").append(item[4]).append("</description>");
            }
            sb.append("</item>");
        }
        return sb.append("</channel></rss>").toString();
    }
}
