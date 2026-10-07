package net.java21.blog.backend.releasenote;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 릴리스 노트 설정(001 contracts "프로퍼티", 003 FR-162).
 *
 * @param portalCardDays 최신 노트를 포털 메인 카드로 보여주는 기간(처음 게시 시각부터)
 */
@ConfigurationProperties(prefix = "blog.release-notes")
public record ReleaseNoteProperties(@DefaultValue("14d") Duration portalCardDays) {
}
