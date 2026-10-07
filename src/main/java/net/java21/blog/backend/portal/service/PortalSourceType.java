package net.java21.blog.backend.portal.service;

/**
 * 포털 카드의 출처(007 FR-123, research E13): 우리 서비스 글(INTERNAL)과 외부 블로그 글(EXTERNAL). id는 출처마다 따로다. 같은 발행
 * 시각이면 INTERNAL이 먼저 온다. 커서에는 {@code P}·{@code E}로 담는다.
 */
public enum PortalSourceType {
    INTERNAL("P"),
    EXTERNAL("E");

    private final String code;

    PortalSourceType(String code) {
        this.code = code;
    }

    /** 커서·블로그당 2편 제한 키에 쓰는 한 글자. */
    public String code() {
        return code;
    }

    /** @return 코드에 맞는 출처, 없으면 null */
    public static PortalSourceType ofCode(String code) {
        for (PortalSourceType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
