package net.java21.blog.backend.media.thumbnail;

import java.util.Locale;

/** 썸네일 맞춤 방식(FR-130). */
public enum Fit {
    /** 꽉 채우고 넘치는 부분을 가운데 기준으로 자른다(기본). */
    COVER,
    /** 전체가 보이게 상자 안에 맞춘다(비율 유지, 자르지 않음). */
    CONTAIN;

    /** {@code cover}·{@code contain}(대소문자 무시). 비었으면 COVER, 그 외는 null. */
    public static Fit parse(String value) {
        if (value == null || value.isBlank()) {
            return COVER;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "cover" -> COVER;
            case "contain" -> CONTAIN;
            default -> null;
        };
    }

    /** 파일 이름에 쓰는 소문자 이름. */
    public String fileName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
