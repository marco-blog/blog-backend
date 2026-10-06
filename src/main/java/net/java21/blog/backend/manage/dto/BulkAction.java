package net.java21.blog.backend.manage.dto;

/** 글 관리 일괄 작업 종류(006 FR-101). */
public enum BulkAction {
    /** 공개 범위 변경({@code visibility} 필수) */
    CHANGE_VISIBILITY,
    /** 카테고리 이동({@code categoryId}, 생략하면 미분류, T180) */
    MOVE_CATEGORY,
    /** 휴지통으로(FR-084) */
    DELETE
}
