package net.java21.blog.backend.sidebar.dto;

import net.java21.blog.backend.sidebar.domain.SidebarItemType;

/** 사이드바 항목 하나의 켜짐 여부(설정 읽기·저장). */
public record SidebarItemRequest(SidebarItemType type, boolean enabled) {
}
