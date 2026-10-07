package net.java21.blog.backend.portal.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 메인 영역의 "같은 블로그 최대 2편"(003 FR-080, research P6). 순서를 지키며 블로그별 {@code perBlog}편을 넘는 행은 건너뛰고
 * {@code limit}편이 차면 멈춘다. 키는 블로그를 가리키는 값이면 무엇이든 된다(007: 내부 {@code P:{blogId}}, 외부 {@code E:{id}}). 건너뛴 행은 다음 묶음에도 나오지 않는다(커서는 마지막으로 살펴본 행, 결정 표 10번).
 */
public final class PerBlogCap {

    public static final int PER_BLOG = 2;

    private PerBlogCap() {
    }

    /**
     * @param items        고른 행(최대 {@code limit})
     * @param lastExamined 마지막으로 살펴본 행(아무것도 살펴보지 않았으면 null)
     * @param examined     살펴본 행 수
     */
    public record Result<T>(List<T> items, T lastExamined, int examined) {
    }

    public static <T> Result<T> apply(List<T> rows, Function<T, ?> blogId, int perBlog, int limit) {
        List<T> items = new ArrayList<>();
        Map<Object, Integer> counts = new HashMap<>();
        T last = null;
        int examined = 0;
        for (T row : rows) {
            if (items.size() >= limit) {
                break;
            }
            examined++;
            last = row;
            int count = counts.merge(blogId.apply(row), 1, Integer::sum);
            if (count <= perBlog) {
                items.add(row);
            }
        }
        return new Result<>(List.copyOf(items), last, examined);
    }
}
