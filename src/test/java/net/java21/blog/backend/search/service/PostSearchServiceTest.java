package net.java21.blog.backend.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.search.SearchProperties;
import net.java21.blog.backend.search.dto.SearchPostResponse;
import net.java21.blog.backend.search.repository.PostSearchRepository;
import net.java21.blog.backend.search.repository.SearchPostRow;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * 검색 서비스(T061): 해석한 검색어로 저장소를 부르고, 태그는 한 번에 읽으며, 본문 노출 가능이 아닌 행(004 보호 글 자리)은
 * {@code summary}·{@code thumbnailUrl}을 null로 준다. 검색어가 틀리면 저장소를 부르지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class PostSearchServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Mock
    private PostSearchRepository searchRepository;
    @Mock
    private TagQueryRepository tagQueryRepository;

    private PostSearchService service;

    @BeforeEach
    void setUp() {
        service = new PostSearchService(new SearchQueryParser(new SearchProperties(5, 2)), searchRepository,
                tagQueryRepository);
    }

    @Test
    void searchesWithParsedQueryAndLoadsTagsOnce() {
        Pageable pageable = PageRequest.of(0, 2);
        when(searchRepository.search(any(), eq(pageable))).thenReturn(new PageImpl<>(
                List.of(row(1L, PostVisibility.PUBLIC), row(2L, PostVisibility.PUBLIC)), pageable, 7));
        when(tagQueryRepository.findTagNames(List.of(1L, 2L))).thenReturn(Map.of(1L, List.of("java", "spring")));

        Page<SearchPostResponse> page = service.search(" 스프링 부트 ", pageable);

        ArgumentCaptor<SearchQuery> query = ArgumentCaptor.forClass(SearchQuery.class);
        verify(searchRepository).search(query.capture(), eq(pageable));
        assertThat(query.getValue().booleanQuery()).isEqualTo("+\"스프링\" +\"부트\"");
        verify(tagQueryRepository, times(1)).findTagNames(any());
        assertThat(page.getTotalElements()).isEqualTo(7);
        SearchPostResponse first = page.getContent().getFirst();
        assertThat(first.id()).isEqualTo(1L);
        assertThat(first.tags()).containsExactly("java", "spring");
        assertThat(first.summary()).isEqualTo("요약 1");
        assertThat(first.thumbnailUrl()).isEqualTo("/media/k1/600x400");
        assertThat(first.blog().handle()).isEqualTo("marco");
        assertThat(first.blog().title()).isEqualTo("마르코의 블로그");
        assertThat(first.hasDraft()).isFalse();
        assertThat(page.getContent().get(1).tags()).isEmpty();
    }

    @Test
    void rowsWithoutVisibleBodyShowOnlyTheTitle() {
        Pageable pageable = PageRequest.of(0, 20);
        when(searchRepository.search(any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(row(3L, PostVisibility.PRIVATE)), pageable, 1));

        SearchPostResponse response = service.search("스프링", pageable).getContent().getFirst();

        assertThat(response.title()).isEqualTo("글 3");
        assertThat(response.summary()).isNull();
        assertThat(response.thumbnailUrl()).isNull();
    }

    @Test
    void invalidQueryDoesNotHitTheRepository() {
        assertThatThrownBy(() -> service.search("스", PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verifyNoInteractions(searchRepository, tagQueryRepository);
    }

    private static SearchPostRow row(Long id, PostVisibility visibility) {
        return new SearchPostRow(id, "글 " + id, "요약 " + id, "/media/k" + id + "/600x400", null, null, 3, 1,
                visibility, PostStatus.PUBLISHED, NOW, NOW, "marco", "마르코의 블로그");
    }
}
