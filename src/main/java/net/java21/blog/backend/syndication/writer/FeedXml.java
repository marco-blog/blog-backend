package net.java21.blog.backend.syndication.writer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.regex.Pattern;

import com.rometools.rome.feed.WireFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.WireFeedOutput;

/** RSS·Atom 작성기 공통: XML 1.0에서 쓸 수 없는 문자 제거, 날짜 변환, UTF-8 출력. */
final class FeedXml {

    /** XML 1.0 허용 문자(탭·줄바꿈·CR, U+0020~U+D7FF, U+E000~U+FFFD, U+10000~)가 아닌 것. */
    private static final Pattern INVALID = Pattern.compile(
            "[^\\x09\\x0A\\x0D\\x20-\\uD7FF\\uE000-\\uFFFD\\x{10000}-\\x{10FFFF}]");

    private FeedXml() {
    }

    /** 제목·본문 등 사용자 값에서 XML에 쓸 수 없는 제어 문자를 지운다(그대로 두면 피드 전체가 깨진다). null은 null. */
    static String clean(String value) {
        return value == null ? null : INVALID.matcher(value).replaceAll("");
    }

    static Date date(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    static byte[] output(WireFeed feed) {
        feed.setEncoding(StandardCharsets.UTF_8.name());
        try {
            return new WireFeedOutput().outputString(feed).getBytes(StandardCharsets.UTF_8);
        } catch (FeedException e) {
            throw new IllegalStateException("Failed to write " + feed.getFeedType() + " feed", e);
        }
    }
}
