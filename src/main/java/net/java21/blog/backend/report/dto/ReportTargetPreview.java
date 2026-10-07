package net.java21.blog.backend.report.dto;

import net.java21.blog.backend.report.domain.ReportTargetType;

/**
 * 관리자 대상 미리보기(005 contracts/api.md {@code ReportTargetPreview}). 글은 제목과(PUBLIC만) 요약, 댓글·방명록은 내용 전체(비밀 포함,
 * 결정 표 6번), 트랙백은 제목·요약·보낸 주소. 없는 대상은 {@code state: MISSING}이고 나머지 값은 null.
 */
public record ReportTargetPreview(ReportTargetType type, Long id, State state, String title, String text, String url,
        Author author, BlogRef blog) {

    /** 대상 상태. */
    public enum State {
        ACTIVE,
        HIDDEN,
        DELETED,
        MISSING
    }

    /** 작성자({@code guest}면 비회원 이름만, 밖에서 온 트랙백은 author 자체가 null). */
    public record Author(Long userId, String nickname, boolean guest) {
    }

    public record BlogRef(String handle, String title) {
    }

    public static ReportTargetPreview missing(ReportTargetType type, Long id) {
        return new ReportTargetPreview(type, id, State.MISSING, null, null, null, null, null);
    }
}
