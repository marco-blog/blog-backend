package net.java21.blog.backend.external.release;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.external.repository.ExternalPostRepository.PurgeRow;
import net.java21.blog.backend.external.thumbnail.ThumbnailDeleteRequested;
import net.java21.blog.backend.portal.repository.PortalExclusionRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 외부 글 행 삭제(007 research E15·E16): 포털 제외 행(FK CASCADE 없음) → 글(검수·일별 클릭은 DB CASCADE) 순서로 500개씩, 썸네일
 * 파일은 커밋 뒤 {@link ThumbnailDeleteRequested}로 지운다. 호출한 쪽 트랜잭션 안에서 부른다.
 */
@Component
public class ExternalPostPurger {

    static final int CHUNK = 500;

    private final ExternalPostRepository postRepository;
    private final PortalExclusionRepository exclusionRepository;
    private final ApplicationEventPublisher events;

    public ExternalPostPurger(ExternalPostRepository postRepository, PortalExclusionRepository exclusionRepository,
            ApplicationEventPublisher events) {
        this.postRepository = postRepository;
        this.exclusionRepository = exclusionRepository;
        this.events = events;
    }

    /** @return 지운 글 수 */
    public int purge(List<PurgeRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (int from = 0; from < rows.size(); from += CHUNK) {
            List<Long> ids = rows.subList(from, Math.min(rows.size(), from + CHUNK)).stream().map(PurgeRow::id)
                    .toList();
            exclusionRepository.deleteByExternalPostIds(ids);
            deleted += postRepository.deleteByIds(ids);
        }
        List<String> keys = new ArrayList<>(rows.stream().map(PurgeRow::thumbnailKey).filter(Objects::nonNull)
                .toList());
        if (!keys.isEmpty()) {
            events.publishEvent(new ThumbnailDeleteRequested(List.copyOf(keys)));
        }
        return deleted;
    }
}
