package net.java21.blog.backend.admin.releasenote.dto;

import java.util.List;

import net.java21.blog.backend.releasenote.domain.TocEntry;

/** 미리보기 결과: 독자 화면과 같은 변환. */
public record PreviewResponse(String contentHtml, List<TocEntry> toc) {
}
