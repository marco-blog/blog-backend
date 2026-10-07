package net.java21.blog.backend.external.feed;

import java.time.Instant;
import java.util.List;

/**
 * 피드 항목 하나를 저장할 값으로 바꾼 것(007 research E4). <b>본문 문자열 필드가 없다</b>(SC-021): 본문은 요약·대표 이미지 계산에만 쓰고
 * 버린다.
 *
 * @param guid        RSS guid·Atom id(없으면 null)
 * @param link        원문 주소(절대 http/https, 2000자 이하)
 * @param title       제목(태그 제거, 300자, 비면 링크 마지막 조각)
 * @param summary     요약(200자, 없으면 null)
 * @param imageUrl    대표 이미지 원본 주소(없으면 null)
 * @param publishedAt 발행 시각(없으면 수정 시각, 둘 다 없으면 null, 미래면 수집 시각)
 * @param categories  카테고리·태그 원문(최대 20개, 각 100자)
 */
public record FeedItem(String guid, String link, String title, String summary, String imageUrl, Instant publishedAt,
        List<String> categories) {

    public FeedItem {
        categories = categories == null ? List.of() : List.copyOf(categories);
    }
}
