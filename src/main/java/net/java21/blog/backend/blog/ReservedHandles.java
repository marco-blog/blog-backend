package net.java21.blog.backend.blog;

import java.util.Locale;
import java.util.Set;

/**
 * 블로그 주소로 쓸 수 없는 이름(contracts/routes.md "예약어"). 이 상수 한 곳에서만 관리하며 운영 중 바꾸지 않는다.
 * front 최상위 경로를 추가할 때 routes.md와 이 목록을 같은 PR에서 고친다.
 */
public final class ReservedHandles {

    public static final Set<String> NAMES = Set.of(
            "admin", "api", "assets", "static", "media", "public", "build", "favicon.ico", "robots.txt", "sitemap",
            "sitemap.xml",
            "signup", "login", "logout", "auth", "oauth", "me", "settings", "manage", "write", "edit", "password-reset",
            "search", "tags", "tag", "topics", "topic", "category", "feed", "rss", "atom", "notifications", "explore",
            "popular",
            "external", "external-blogs", "report", "reports", "rights-request", "trackback", "locale", "lang", "legal",
            "help", "about", "terms", "privacy", "policy", "notice", "support", "health", "blog", "www", "mail", "root",
            "system",
            "updates");

    private ReservedHandles() {
    }

    /** 앞뒤 공백·대소문자를 무시하고 예약어인지 본다. */
    public static boolean isReserved(String handle) {
        return handle != null && NAMES.contains(handle.strip().toLowerCase(Locale.ROOT));
    }
}
