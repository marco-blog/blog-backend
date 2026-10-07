package net.java21.blog.backend.external.classify;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import net.java21.blog.backend.topic.repository.TopicRow;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/** 분류 시험 도구: 주제 행과 작은 사전. */
final class ClassifyTestSupport {

    private ClassifyTestSupport() {
    }

    static TopicRow major(long id, String slug) {
        return new TopicRow(id, null, slug, slug, slug, slug, slug, 0, false, false, false, null, Instant.EPOCH,
                Instant.EPOCH);
    }

    static TopicRow minor(long id, long parentId, String slug) {
        return new TopicRow(id, parentId, slug, slug, slug, slug, slug, 0, false, false, false, null, Instant.EPOCH,
                Instant.EPOCH);
    }

    static Resource yaml(String text) {
        return new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8));
    }

    /** 1 대분류 아래 slug마다 소분류(id 11부터). */
    static List<TopicRow> rows(String... slugs) {
        List<TopicRow> rows = new ArrayList<>();
        rows.add(major(1, "root"));
        for (int i = 0; i < slugs.length; i++) {
            rows.add(minor(11 + i, 1, slugs[i]));
        }
        return rows;
    }
}
