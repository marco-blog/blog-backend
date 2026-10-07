package net.java21.blog.backend.admin.audit;

/**
 * 관리자 작업 기록의 동작 코드·대상 종류(006 data-model {@code admin_audit_logs} 표). 003이 쓰는 행만 둔다(005~007이 더한다).
 */
public final class AuditActions {

    public static final String TARGET_TOPIC = "TOPIC";
    public static final String TARGET_CURATION = "CURATION";
    public static final String TARGET_POST = "POST";
    public static final String TARGET_SETTING = "SETTING";
    public static final String TARGET_RELEASE_NOTE = "RELEASE_NOTE";
    /** 005·006 회원(정지·해제·블로그 한도). */
    public static final String TARGET_USER = "USER";
    public static final String TARGET_REPORT = "REPORT";
    public static final String TARGET_COMMENT = "COMMENT";
    public static final String TARGET_GUESTBOOK = "GUESTBOOK";
    public static final String TARGET_TRACKBACK = "TRACKBACK";
    public static final String TARGET_BANNED_WORD = "BANNED_WORD";

    public static final String TOPIC_CREATE = "TOPIC_CREATE";
    public static final String TOPIC_UPDATE = "TOPIC_UPDATE";
    public static final String TOPIC_REORDER = "TOPIC_REORDER";
    public static final String TOPIC_HIDE = "TOPIC_HIDE";
    public static final String TOPIC_UNHIDE = "TOPIC_UNHIDE";
    public static final String TOPIC_PIN = "TOPIC_PIN";
    public static final String TOPIC_UNPIN = "TOPIC_UNPIN";

    public static final String CURATION_CREATE = "CURATION_CREATE";
    public static final String CURATION_UPDATE = "CURATION_UPDATE";
    public static final String CURATION_DELETE = "CURATION_DELETE";

    public static final String PORTAL_EXCLUDE = "PORTAL_EXCLUDE";
    public static final String PORTAL_UNEXCLUDE = "PORTAL_UNEXCLUDE";

    public static final String SETTING_CHANGE = "SETTING_CHANGE";

    public static final String RELEASE_NOTE_CREATE = "RELEASE_NOTE_CREATE";
    public static final String RELEASE_NOTE_UPDATE = "RELEASE_NOTE_UPDATE";
    public static final String RELEASE_NOTE_PUBLISH = "RELEASE_NOTE_PUBLISH";
    public static final String RELEASE_NOTE_UNPUBLISH = "RELEASE_NOTE_UNPUBLISH";
    public static final String RELEASE_NOTE_DELETE = "RELEASE_NOTE_DELETE";

    // 005 신고·숨김·정지·금칙어(005 contracts/api.md, 006 FR-106)
    public static final String USER_SUSPEND = "USER_SUSPEND";
    public static final String USER_UNSUSPEND = "USER_UNSUSPEND";
    public static final String REPORT_ACTION = "REPORT_ACTION";
    public static final String REPORT_DISMISS = "REPORT_DISMISS";
    public static final String CONTENT_HIDE = "CONTENT_HIDE";
    public static final String CONTENT_UNHIDE = "CONTENT_UNHIDE";
    public static final String BANNED_WORD_CREATE = "BANNED_WORD_CREATE";
    public static final String BANNED_WORD_UPDATE = "BANNED_WORD_UPDATE";
    public static final String BANNED_WORD_DELETE = "BANNED_WORD_DELETE";

    private AuditActions() {
    }
}
