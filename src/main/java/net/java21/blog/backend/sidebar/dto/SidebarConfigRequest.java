package net.java21.blog.backend.sidebar.dto;

import java.util.List;

/**
 * 사이드바 설정(004 contracts/api.md {@code PUT /blogs/{handle}/sidebar}의 요청·{@code GET .../manage/sidebar}의 응답). 10개 항목을
 * 빠짐·중복 없이 모두 보내며 배열 순서가 사이드바 순서다.
 */
public record SidebarConfigRequest(List<SidebarItemRequest> items) {
}
