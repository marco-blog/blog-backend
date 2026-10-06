package net.java21.blog.backend.search.service;

import java.util.List;
import java.util.Map;

import net.java21.blog.backend.search.dto.SearchPostResponse;
import net.java21.blog.backend.search.repository.PostSearchRepository;
import net.java21.blog.backend.search.repository.SearchPostRow;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서비스 전체 글 검색(002 FR-035, SC-007). 검색어를 해석해({@link SearchQueryParser}, 틀리면 400) 저장소를 부르고 태그를 한 번에 읽는다.
 * 쿼리 3회(목록, 전체 수, 태그) 고정.
 */
@Service
public class PostSearchService {

    private final SearchQueryParser parser;
    private final PostSearchRepository searchRepository;
    private final TagQueryRepository tagQueryRepository;

    public PostSearchService(SearchQueryParser parser, PostSearchRepository searchRepository,
            TagQueryRepository tagQueryRepository) {
        this.parser = parser;
        this.searchRepository = searchRepository;
        this.tagQueryRepository = tagQueryRepository;
    }

    @Transactional(readOnly = true)
    public Page<SearchPostResponse> search(String q, Pageable pageable) {
        SearchQuery query = parser.parse(q);
        Page<SearchPostRow> rows = searchRepository.search(query, pageable);
        Map<Long, List<String>> tags = tagQueryRepository.findTagNames(
                rows.getContent().stream().map(SearchPostRow::id).toList());
        return rows.map(row -> SearchPostResponse.of(row, tags.get(row.id())));
    }
}
