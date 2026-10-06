package net.java21.blog.backend.topic.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/** 주제의 4개 언어 이름(FR-079, 001 FR-151). 모두 필수다. JSON·응답의 키는 {@code ko}, {@code en}, {@code ja}, {@code zh-CN}. */
public record TopicNames(String ko, String en, String ja, String zhCn) {

    public static final String ZH_CN = "zh-CN";

    public static TopicNames of(Map<String, String> names) {
        return new TopicNames(names.get("ko"), names.get("en"), names.get("ja"), names.get(ZH_CN));
    }

    /** 이름이 하나라도 비었는지. */
    public boolean hasBlank() {
        return isBlank(ko) || isBlank(en) || isBlank(ja) || isBlank(zhCn);
    }

    public Map<String, String> asMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("ko", ko);
        map.put("en", en);
        map.put("ja", ja);
        map.put(ZH_CN, zhCn);
        return map;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
