package net.java21.blog.backend.portal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 같은 블로그 2편 제한(003 T036, FR-080, research P6). */
class PerBlogCapTest {

    record Row(long id, long blogId) {
    }

    @Test
    void skipsRowsOverThePerBlogLimitKeepingOrderAndStopsAtTheLimit() {
        List<Row> rows = List.of(new Row(1, 10), new Row(2, 10), new Row(3, 10), new Row(4, 20), new Row(5, 10),
                new Row(6, 30), new Row(7, 20), new Row(8, 40));

        PerBlogCap.Result<Row> result = PerBlogCap.apply(rows, Row::blogId, 2, 4);

        assertThat(result.items()).extracting(Row::id).containsExactly(1L, 2L, 4L, 6L);
        assertThat(result.lastExamined().id()).isEqualTo(6);
        assertThat(result.examined()).isEqualTo(6);
    }

    @Test
    void examinesEverythingWhenTheLimitIsNotReached() {
        List<Row> rows = List.of(new Row(1, 10), new Row(2, 10), new Row(3, 10));

        PerBlogCap.Result<Row> result = PerBlogCap.apply(rows, Row::blogId, PerBlogCap.PER_BLOG, 20);

        assertThat(result.items()).extracting(Row::id).containsExactly(1L, 2L);
        assertThat(result.lastExamined().id()).isEqualTo(3);
        assertThat(result.examined()).isEqualTo(3);
    }

    @Test
    void emptyInputGivesEmptyResult() {
        PerBlogCap.Result<Row> result = PerBlogCap.apply(List.<Row>of(), Row::blogId, 2, 20);
        assertThat(result.items()).isEmpty();
        assertThat(result.lastExamined()).isNull();
        assertThat(result.examined()).isZero();
    }
}
