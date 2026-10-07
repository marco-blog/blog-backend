-- =============================================================================
-- blog.java21.net 1.0 전체 스키마 (MySQL 8) — Crowfoot 내보내기 스냅숏
--
-- 원천(source of truth): Crowfoot ERD 문서 "blog 1.0"
--   https://crowfoot.java21.net/workspaces/53/models/646
-- 이 파일은 Crowfoot export_ddl 결과를 그대로 저장한 것이다. 손으로 고치지 않는다.
-- 스키마를 바꾸는 순서는 db/README.md 를 따른다.
--
-- 용도
--  - blog-backend 테스트가 테스트 DB(cf_u2_d3)를 만들 때 쓰는 스냅숏
--    (blog-backend/src/test/resources/db/schema-mysql.sql 로 복사)
--  - 새 환경에 처음 배포할 때의 참고본
--
-- 규칙(erd.md 참고): utf8mb4/InnoDB, PK BIGINT AUTO_INCREMENT, 시간은 UTC DATETIME(6),
-- 개인정보는 AES-256-GCM 암호문(VARBINARY, *_enc)과 HMAC 해시(*_hash),
-- FULLTEXT 인덱스는 ngram 파서(ngram_token_size=2).
-- COMMENT의 '-----'는 Crowfoot이 논리명과 설명을 잇는 구분자다(신고됨).
-- =============================================================================

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '회원 ID',
    profile_media_id BIGINT COMMENT '프로필 이미지-----(media, owner_type=PROFILE)',
    email_enc VARBINARY(512) NOT NULL COMMENT '이메일 AES-256-GCM 암호문-----(키 버전+IV+암호문+태그), 소문자 정규화 후 암호화 (001 FR-134)',
    email_hash CHAR(64) NOT NULL COMMENT '정규화한 이메일의 HMAC-SHA256-----로그인·중복 확인·관리자 정확 검색용 (001 FR-135)',
    password_hash VARCHAR(100) NOT NULL COMMENT '비밀번호 BCrypt-----(001 FR-003)',
    nickname VARCHAR(30) NOT NULL COMMENT '닉네임',
    bio VARCHAR(300) COMMENT '소개',
    role VARCHAR(15) NOT NULL DEFAULT 'USER' COMMENT '권한 USER/ADMIN/SUPER_ADMIN-----(006 FR-105)',
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' COMMENT '상태 ACTIVE/SUSPENDED/WITHDRAWN',
    locale VARCHAR(10) COMMENT '화면 언어 ko/en/ja/zh-CN-----NULL=미설정 (001 FR-149)',
    time_zone VARCHAR(40) NOT NULL DEFAULT 'Asia/Seoul' COMMENT 'IANA 시간대 ID-----(001 FR-153)',
    failed_login_count INT NOT NULL DEFAULT 0 COMMENT '연속 로그인 실패 수-----(001 FR-007)',
    locked_until DATETIME(6) COMMENT '로그인 잠금 해제 시각',
    withdrawn_at DATETIME(6) COMMENT '탈퇴 시각-----30일 후 개인정보 파기 (001 FR-138)',
    terms_version VARCHAR(20) NOT NULL COMMENT '동의한 약관 버전-----(001 FR-081)',
    terms_agreed_at DATETIME(6) NOT NULL COMMENT '약관 동의 일시',
    max_blogs INT COMMENT '회원별 블로그 한도-----NULL=기본값(3) (001 FR-158, 006 FR-160)',
    last_seen_release_version VARCHAR(20) COMMENT '마지막으로 확인한 릴리스 노트 버전-----값 참조(외래 키 없음) (003 FR-163)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '가입 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email_hash UNIQUE (email_hash),
    CONSTRAINT ck_users_max_blogs CHECK (max_blogs IS NULL OR max_blogs >= 0)
) COMMENT='회원-----계정 설정, 권한, 블로그 한도';
CREATE TABLE refresh_tokens (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '리프레시 토큰 ID',
    user_id BIGINT NOT NULL COMMENT '회원',
    family_id CHAR(36) NOT NULL COMMENT '한 번의 로그인에서 이어진 토큰 묶음-----(UUID)',
    token_hash CHAR(64) NOT NULL COMMENT '토큰 SHA-256',
    expires_at DATETIME(6) NOT NULL COMMENT '유휴 만료(발급 + 4시간)',
    family_expires_at DATETIME(6) NOT NULL COMMENT '절대 만료-----(family 최초 발급 + 7일)',
    used_at DATETIME(6) COMMENT '교체되어 사용 처리된 시각',
    replaced_by_id BIGINT COMMENT '교체로 새로 발급된 토큰 ID-----(외래 키 없음)',
    revoked_at DATETIME(6) COMMENT '로그아웃·재사용 감지·정지·탈퇴로 폐기된 시각',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '발급 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash)
) COMMENT='리프레시 토큰';
CREATE TABLE login_history (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '로그인 기록 ID',
    user_id BIGINT COMMENT '회원-----NULL=존재하지 않는 이메일로 시도',
    success TINYINT(1) NOT NULL COMMENT '성공 여부',
    ip_enc VARBINARY(128) COMMENT '접속 IP AES-256-GCM 암호문-----(001 FR-134)',
    user_agent VARCHAR(300) COMMENT '기기 정보(User-Agent)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '시도 시각-----3개월 후 파기 (001 FR-139)',
    PRIMARY KEY (id)
) COMMENT='로그인 기록-----(IP 암호화, 3개월 보관)';
CREATE TABLE password_reset_tokens (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '재설정 토큰 ID',
    user_id BIGINT NOT NULL COMMENT '회원',
    token_hash CHAR(64) NOT NULL COMMENT '토큰 SHA-256',
    expires_at DATETIME(6) NOT NULL COMMENT '만료(발급 + 30분)',
    used_at DATETIME(6) COMMENT '사용 시각-----(한 번만 사용, 001 FR-133)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '발급 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT uk_password_reset_tokens_token_hash UNIQUE (token_hash)
) COMMENT='비밀번호 재설정 토큰';
CREATE TABLE blogs (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '블로그 ID',
    user_id BIGINT NOT NULL COMMENT '주인 회원-----(회원당 여러 블로그, 001 FR-010)',
    default_topic_id BIGINT COMMENT '블로그 기본 주제-----(소분류) (003 FR-077)',
    cover_media_id BIGINT COMMENT '대표 이미지-----(media, owner_type=BLOG_COVER)',
    handle VARCHAR(20) NOT NULL COMMENT '블로그 주소-----삭제된 블로그 포함 유일·변경 불가 (001 FR-002, FR-159)',
    title VARCHAR(100) NOT NULL COMMENT '블로그 제목-----기본 "{닉네임}의 블로그"',
    description VARCHAR(500) COMMENT '블로그 소개',
    comment_enabled TINYINT(1) NOT NULL DEFAULT TRUE COMMENT '댓글 허용 (001 FR-029)',
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' COMMENT '상태 ACTIVE/DELETED-----(001 FR-159)',
    deleted_at DATETIME(6) COMMENT '블로그 삭제 시각',
    subscriber_count INT NOT NULL DEFAULT 0 COMMENT '구독자 수-----(비정규화) (002 FR-031)',
    feed_item_count INT NOT NULL DEFAULT 20 COMMENT '피드에 담을 글 수 10/20/30/50-----(002 FR-046)',
    feed_content_mode VARCHAR(10) NOT NULL DEFAULT 'FULL' COMMENT '피드 내용 FULL-----(전문)/SUMMARY(요약) (002 FR-046)',
    portal_enabled TINYINT(1) NOT NULL DEFAULT TRUE COMMENT '포털에 내 글 노출-----(003 FR-089)',
    first_published_at DATETIME(6) COMMENT '처음 글을 발행한 시각-----새로 시작한 블로그 (003 FR-087)',
    guestbook_enabled TINYINT(1) NOT NULL DEFAULT TRUE COMMENT '방명록 켜기 (004 FR-058)',
    guest_write_enabled TINYINT(1) NOT NULL DEFAULT FALSE COMMENT '비회원 댓글·방명록 허용-----(004 FR-066)',
    total_visitors BIGINT NOT NULL DEFAULT 0 COMMENT '전체 방문자 수-----(004 FR-067)',
    trackback_enabled TINYINT(1) NOT NULL DEFAULT TRUE COMMENT '트랙백 받기 (005 FR-053)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '만든 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT uk_blogs_handle UNIQUE (handle),
    CONSTRAINT ck_blogs_feed_item_count CHECK (feed_item_count IN (10, 20, 30, 50))
) COMMENT='블로그-----(회원당 여러 개)와 블로그별 설정';
CREATE TABLE categories (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '카테고리 ID',
    blog_id BIGINT NOT NULL COMMENT '블로그',
    parent_id BIGINT COMMENT '상위 카테고리-----NULL=최상위(2단계까지, 001 FR-023)',
    name VARCHAR(50) NOT NULL COMMENT '이름, 같은 부모 아래 유일',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '순서',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '만든 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT uk_categories_blog_parent_name UNIQUE (blog_id, parent_id, name)
) COMMENT='카테고리';
CREATE TABLE posts (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '글 ID(글 주소의 글번호)',
    topic_id BIGINT COMMENT '주제-----(소분류), NULL=주제 없음 (003 FR-076)',
    blog_id BIGINT NOT NULL COMMENT '블로그',
    category_id BIGINT COMMENT '카테고리, NULL=미분류',
    title VARCHAR(200) NOT NULL COMMENT '제목',
    content_md MEDIUMTEXT COMMENT '원문 Markdown',
    content_html MEDIUMTEXT COMMENT '변환·살균된 HTML-----(001 FR-021)',
    content_text MEDIUMTEXT COMMENT '태그 제거 텍스트(검색용)',
    summary VARCHAR(300) COMMENT '요약-----(content_text 앞 150자), 메타 description',
    thumbnail_url VARCHAR(500) COMMENT '대표 이미지 /media/{media_key}-----(001 FR-107)',
    visibility VARCHAR(10) NOT NULL DEFAULT 'PUBLIC' COMMENT '공개 범위 PUBLIC/PRIVATE/PROTECTED-----(004)',
    status VARCHAR(10) NOT NULL DEFAULT 'DRAFT' COMMENT '상태 DRAFT/PUBLISHED/DELETED/SCHEDULED-----(004)/HIDDEN(005)',
    status_before_delete VARCHAR(10) COMMENT '휴지통으로 옮기기 직전 상태-----(001 FR-084)',
    comment_enabled TINYINT(1) NOT NULL DEFAULT TRUE COMMENT '글별 댓글 허용-----(001 FR-107)',
    view_count INT NOT NULL DEFAULT 0 COMMENT '조회수(비정규화)',
    comment_count INT NOT NULL DEFAULT 0 COMMENT '댓글 수(비정규화)',
    published_at DATETIME(6) COMMENT '최초 발행 시각',
    deleted_at DATETIME(6) COMMENT '휴지통 이동 시각-----30일 후 영구 삭제 (001 FR-084)',
    like_count INT NOT NULL DEFAULT 0 COMMENT '좋아요 수-----(비정규화) (002 FR-030)',
    password_hash VARCHAR(100) COMMENT '보호 글 비밀번호 BCrypt-----(004 FR-062)',
    scheduled_at DATETIME(6) COMMENT '예약 발행 시각-----(004 FR-064)',
    notice TINYINT(1) NOT NULL DEFAULT FALSE COMMENT '공지 글 (004 FR-059)',
    status_before_hidden VARCHAR(10) COMMENT '관리자 숨김 직전 상태-----(005 FR-041)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '만든 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT ck_posts_protected_password CHECK ((visibility = 'PROTECTED') = (password_hash IS NOT NULL))
) COMMENT='글-----(공개 범위·상태, 글 노출 매트릭스의 대상)';
CREATE TABLE post_drafts (
    post_id BIGINT NOT NULL COMMENT '글(1:1)',
    title VARCHAR(200) COMMENT '작성 중 제목',
    content_md MEDIUMTEXT COMMENT '작성 중 Markdown',
    category_id BIGINT COMMENT '작성 중 카테고리-----(외래 키 없음, 발행 때 검증)',
    tags_json JSON COMMENT '작성 중 태그 목록',
    saved_at DATETIME(6) NOT NULL COMMENT '자동저장 시각',
    topic_id BIGINT COMMENT '작성 중 주제-----(외래 키 없음, 발행 때 검증) (003 FR-076)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '만든 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (post_id)
) COMMENT='글 작성 중 사본-----(001 FR-016, FR-108)';
CREATE TABLE tags (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '태그 ID',
    name VARCHAR(30) NOT NULL COMMENT '태그 이름-----(trim + 소문자 정규화)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '만든 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT uk_tags_name UNIQUE (name)
) COMMENT='태그';
CREATE TABLE post_tags (
    post_id BIGINT NOT NULL COMMENT '글',
    tag_id BIGINT NOT NULL COMMENT '태그',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '연결 시각',
    PRIMARY KEY (post_id, tag_id)
) COMMENT='글 태그-----(글당 최대 10개, 001 FR-025)';
CREATE TABLE media (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '이미지 ID-----(내부용, 주소에 쓰지 않음)',
    owner_id BIGINT NOT NULL COMMENT '올린 회원',
    media_key CHAR(22) NOT NULL COMMENT '무작위 128비트 base62 키-----주소 /media/{media_key} (001 FR-156)',
    owner_type VARCHAR(12) NOT NULL DEFAULT 'POST' COMMENT '용도 POST/PROFILE/BLOG_COVER',
    status VARCHAR(10) NOT NULL DEFAULT 'TEMP' COMMENT '상태 TEMP/ATTACHED/ORPHANED',
    stored_name VARCHAR(100) NOT NULL COMMENT '저장 파일 이름 {uuid}-----{ext} (001 FR-039)',
    stored_path VARCHAR(300) NOT NULL COMMENT '기준 디렉터리로부터의 상대 경로',
    mime VARCHAR(20) NOT NULL COMMENT 'MIME 타입-----(jpeg/png/gif/webp)',
    size_bytes INT NOT NULL COMMENT '파일 크기(바이트)',
    width INT NOT NULL COMMENT '원본 너비-----(px) (001 FR-132)',
    height INT NOT NULL COMMENT '원본 높이(px)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '업로드 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT uk_media_media_key UNIQUE (media_key)
) COMMENT='이미지';
CREATE TABLE post_media (
    post_id BIGINT NOT NULL COMMENT '글',
    media_id BIGINT NOT NULL COMMENT '이미지',
    source VARCHAR(10) NOT NULL COMMENT '참조 출처 PUBLISHED-----(발행본)/DRAFT(작성 중 사본)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '연결 시각',
    PRIMARY KEY (post_id, media_id, source)
) COMMENT='글 이미지 참조-----(발행본·작성 중 사본) (001 FR-071, FR-073)';
CREATE TABLE comments (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '댓글 ID',
    post_id BIGINT NOT NULL COMMENT '글',
    user_id BIGINT COMMENT '작성 회원-----NULL=비회원 (004 FR-066)',
    parent_id BIGINT COMMENT '부모 댓글-----NULL=댓글, 값=답글(1단계, 001 FR-027)',
    content VARCHAR(1000) NOT NULL COMMENT '내용(일반 텍스트)',
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' COMMENT '상태 ACTIVE/DELETED/HIDDEN-----(005)',
    guest_name VARCHAR(30) COMMENT '비회원 이름 (004 FR-066)',
    guest_password_hash VARCHAR(100) COMMENT '비회원 비밀번호 BCrypt-----(004 FR-066)',
    guest_ip_enc VARBINARY(128) COMMENT '비회원 작성 IP AES-256-GCM 암호문-----90일 뒤 NULL (001 FR-134)',
    secret TINYINT(1) NOT NULL DEFAULT FALSE COMMENT '비밀 댓글 (004 FR-065)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '작성 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT ck_comments_author CHECK ((user_id IS NOT NULL AND guest_name IS NULL) OR (user_id IS NULL AND guest_name IS NOT NULL AND guest_password_hash IS NOT NULL))
) COMMENT='댓글-----(답글 1단계, 비회원·비밀 댓글)';
CREATE TABLE guestbook_entries (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '방명록 글 ID',
    blog_id BIGINT NOT NULL COMMENT '블로그',
    user_id BIGINT COMMENT '작성 회원, NULL=비회원',
    parent_id BIGINT COMMENT 'NULL=방명록 글-----값=블로그 주인의 답글(1단계)',
    guest_name VARCHAR(30) COMMENT '비회원 이름-----(비회원이면 필수) (004 FR-066)',
    guest_password_hash VARCHAR(100) COMMENT '비회원 비밀번호 BCrypt-----(비회원이면 필수)',
    guest_ip_enc VARBINARY(128) COMMENT '비회원 작성 IP AES-256-GCM 암호문-----90일 뒤 NULL',
    content VARCHAR(1000) NOT NULL COMMENT '내용(일반 텍스트)',
    secret TINYINT(1) NOT NULL DEFAULT FALSE COMMENT '비밀글 (004 FR-057)',
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' COMMENT '상태 ACTIVE/DELETED/HIDDEN-----(005)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '작성 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT ck_guestbook_entries_author CHECK ((user_id IS NOT NULL AND guest_name IS NULL) OR (user_id IS NULL AND guest_name IS NOT NULL AND guest_password_hash IS NOT NULL))
) COMMENT='방명록 글-----(비밀글, 비회원, 주인 답글) (004 FR-056~058)';
CREATE TABLE trackbacks (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '트랙백 ID',
    post_id BIGINT NOT NULL COMMENT '트랙백을 받은 글',
    source_post_id BIGINT COMMENT '서비스 안의 글이 보낸 트랙백이면 그 글',
    source_url VARCHAR(1000) NOT NULL COMMENT '보낸 글 주소(http/https)',
    source_url_hash CHAR(64) NOT NULL COMMENT '정규화한 source_url의 SHA-256-----(005 FR-054)',
    title VARCHAR(255) COMMENT '제목(태그 제거)',
    excerpt VARCHAR(255) COMMENT '요약-----(태그 제거, 최대 255자) (005 FR-051)',
    blog_name VARCHAR(255) COMMENT '보낸 블로그 이름',
    sender_ip_enc VARBINARY(128) COMMENT '보낸 곳 IP AES-256-GCM 암호문-----90일 뒤 NULL',
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' COMMENT '상태 ACTIVE/DELETED/HIDDEN',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '받은 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT uk_trackbacks_post_source_url_hash UNIQUE (post_id, source_url_hash)
) COMMENT='받은 트랙백-----(005 FR-049~055)';
CREATE TABLE trackback_ping_logs (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '보낸 트랙백 기록 ID',
    post_id BIGINT NOT NULL COMMENT '트랙백을 보낸 글',
    target_url VARCHAR(1000) NOT NULL COMMENT '보낼 트랙백 주소',
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING' COMMENT '상태 PENDING/SUCCESS/FAILED',
    error_code VARCHAR(30) COMMENT '실패 이유 INVALID_URL/BLOCKED_ADDRESS/TIMEOUT/HTTP_ERROR/REMOTE_ERROR',
    error_message VARCHAR(255) COMMENT '상대가 돌려준 메시지(일반 텍스트)',
    attempted_at DATETIME(6) COMMENT '보낸 시각',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '요청(발행·수정) 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id)
) COMMENT='보낸 트랙백 기록-----(005 FR-052)';
CREATE TABLE post_likes (
    user_id BIGINT NOT NULL COMMENT '좋아요한 회원',
    post_id BIGINT NOT NULL COMMENT '글',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '누른 시각(인기 점수 7일 계산)',
    PRIMARY KEY (user_id, post_id)
) COMMENT='좋아요-----(회원·글 쌍마다 하나) (002 FR-030)';
CREATE TABLE blog_subscriptions (
    user_id BIGINT NOT NULL COMMENT '구독한 회원',
    blog_id BIGINT NOT NULL COMMENT '구독한 블로그',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '구독 시각',
    PRIMARY KEY (user_id, blog_id)
) COMMENT='블로그 구독-----(002 FR-031, FR-032)';
CREATE TABLE notifications (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '알림 ID',
    blog_id BIGINT COMMENT '관련된 내 블로그',
    user_id BIGINT NOT NULL COMMENT '받는 회원',
    actor_user_id BIGINT COMMENT '알림을 일으킨 회원-----비회원·시스템이면 NULL',
    type VARCHAR(30) NOT NULL COMMENT '종류 NEW_COMMENT/NEW_SUBSCRIBER/BACKUP_READY/EXTERNAL_BLOG_APPROVED/EXTERNAL_BLOG_REJECTED/EXTERNAL_FEED_STOPPED/REPORT_RESOLVED',
    target_type VARCHAR(20) COMMENT '관련 대상 종류 COMMENT/BLOG/BLOG_EXPORT/EXTERNAL_BLOG/REPORT',
    target_id BIGINT COMMENT '관련 대상 ID-----(다형 참조, 외래 키 없음)',
    params_json JSON COMMENT '문구에 넣을 값(번역하지 않음)',
    read_at DATETIME(6) COMMENT '읽은 시각, NULL=안 읽음',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '만든 시각, 90일 후 정리',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id)
) COMMENT='알림-----(002 FR-033의 모든 종류)';
CREATE TABLE topics (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '주제 ID',
    parent_id BIGINT COMMENT '대분류-----NULL=대분류 자신(2단계)',
    slug VARCHAR(40) NOT NULL COMMENT '주소용 영문 식별자-----변경 불가 (003 FR-075, FR-079)',
    name_ko VARCHAR(50) NOT NULL COMMENT '한국어 이름',
    name_en VARCHAR(50) NOT NULL COMMENT '영어 이름',
    name_ja VARCHAR(50) NOT NULL COMMENT '일본어 이름',
    name_zh_cn VARCHAR(50) NOT NULL COMMENT '중국어(간체) 이름',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '같은 부모 안의 순서',
    admin_hidden TINYINT(1) NOT NULL DEFAULT FALSE COMMENT '운영자 숨김 (003 FR-079)',
    pinned_on_tab TINYINT(1) NOT NULL DEFAULT FALSE COMMENT '주제 탭 고정-----(자동 숨김 제외) (003 FR-147)',
    card_color CHAR(7) COMMENT '대표 이미지 없는 글 카드 색 #RRGGBB',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '만든 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT uk_topics_slug UNIQUE (slug)
) COMMENT='주제-----(대분류·소분류, 4개 언어 이름)';
CREATE TABLE portal_curations (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '추천 ID',
    created_by BIGINT NOT NULL COMMENT '지정한 관리자',
    post_id BIGINT NOT NULL COMMENT '추천 글',
    starts_at DATETIME(6) NOT NULL COMMENT '노출 시작',
    ends_at DATETIME(6) NOT NULL COMMENT '노출 종료',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '추천 영역 안의 순서',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '만든 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT ck_portal_curations_period CHECK (ends_at > starts_at)
) COMMENT='포털 추천-----(003 FR-091, FR-092)';
CREATE TABLE portal_exclusions (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '포털 제외 ID',
    excluded_by BIGINT NOT NULL COMMENT '처리한 관리자',
    external_post_id BIGINT COMMENT '제외한 외부 글-----(007 FR-123)',
    post_id BIGINT COMMENT '제외한 내부 글',
    reason VARCHAR(500) NOT NULL COMMENT '제외 사유 (003 FR-093)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '제외 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT uk_portal_exclusions_post UNIQUE (post_id),
    CONSTRAINT uk_portal_exclusions_external_post UNIQUE (external_post_id),
    CONSTRAINT ck_portal_exclusions_target CHECK ((post_id IS NULL) <> (external_post_id IS NULL))
) COMMENT='포털 제외-----(내부 글 또는 외부 글 하나)';
CREATE TABLE post_daily_stats (
    post_id BIGINT NOT NULL COMMENT '글',
    stat_date DATE NOT NULL COMMENT 'UTC 날짜',
    views INT NOT NULL DEFAULT 0 COMMENT '그날 늘어난 조회수',
    read_completes INT NOT NULL DEFAULT 0 COMMENT '본문 끝까지 읽음 수-----(003 FR-086)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '만든 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (post_id, stat_date)
) COMMENT='글 일별 통계-----(인기 점수 입력, 90일 보관)';
CREATE TABLE system_settings (
    setting_key VARCHAR(100) NOT NULL COMMENT '설정 키-----(portal.*, ratelimit.*, spam.*, external.*)',
    updated_by BIGINT COMMENT '마지막으로 바꾼 관리자',
    value_json JSON NOT NULL COMMENT '설정 값',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '만든 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '바꾼 시각',
    PRIMARY KEY (setting_key)
) COMMENT='운영 설정값-----(포털·스팸·외부 피드)';
CREATE TABLE blog_sidebar_items (
    blog_id BIGINT NOT NULL COMMENT '블로그',
    item_type VARCHAR(20) NOT NULL COMMENT '항목 PROFILE/CATEGORIES/RECENT_POSTS/RECENT_COMMENTS/POPULAR_POSTS/TAGS/ARCHIVE/VISITORS/SEARCH/FEED_LINKS',
    enabled TINYINT(1) NOT NULL COMMENT '켜짐 여부',
    sort_order INT NOT NULL COMMENT '사이드바 안의 순서',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '만든 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (blog_id, item_type)
) COMMENT='블로그 사이드바 항목-----(004 FR-060)';
CREATE TABLE blog_daily_visits (
    blog_id BIGINT NOT NULL COMMENT '블로그',
    visit_date DATE NOT NULL COMMENT '방문 날짜-----(blog.stats.time-zone 기준)',
    visitors INT NOT NULL DEFAULT 0 COMMENT '그날 방문자 수-----(하루 1회) (004 FR-067)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '만든 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (blog_id, visit_date)
) COMMENT='블로그 일별 방문자';
CREATE TABLE blog_exports (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '백업 작업 ID',
    requested_by BIGINT NOT NULL COMMENT '요청한 블로그 주인',
    blog_id BIGINT NOT NULL COMMENT '블로그',
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING' COMMENT '상태 PENDING/RUNNING/READY/FAILED/EXPIRED',
    file_path VARCHAR(300) COMMENT 'blog-----export.dir 기준 상대 경로(READY일 때만)',
    file_size BIGINT COMMENT '파일 크기(바이트)',
    error_code VARCHAR(50) COMMENT '실패 원인 코드',
    completed_at DATETIME(6) COMMENT 'READY가 된 시각',
    expires_at DATETIME(6) COMMENT '만료-----(completed_at + 7일) (004 FR-145)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '요청 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id)
) COMMENT='블로그 백업-----(004 FR-145)';
CREATE TABLE blog_blocks (
    blog_id BIGINT NOT NULL COMMENT '블로그',
    blocked_user_id BIGINT NOT NULL COMMENT '차단된 회원',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '차단 시각',
    PRIMARY KEY (blog_id, blocked_user_id)
) COMMENT='블로그 회원 차단-----(004 FR-146)';
CREATE TABLE reports (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '신고 ID',
    reporter_id BIGINT COMMENT '신고한 회원-----(MEMBER 필수, RIGHTS_REQUEST는 NULL)',
    target_user_id BIGINT COMMENT '대상 작성자 또는 외부 블로그 소유 회원-----(006 FR-104)',
    handled_by BIGINT COMMENT '처리한 관리자',
    target_blog_id BIGINT COMMENT '대상이 속한 블로그-----(포털 점수 감점, 003 FR-086)',
    channel VARCHAR(15) NOT NULL COMMENT '경로 MEMBER-----(회원 신고)/RIGHTS_REQUEST(권리 침해 신고) (005 FR-040)',
    target_type VARCHAR(20) COMMENT '대상 종류 POST/COMMENT/GUESTBOOK/TRACKBACK/EXTERNAL_POST/EXTERNAL_BLOG',
    target_id BIGINT COMMENT '대상 ID-----(다형 참조, 외래 키 없음)',
    target_url VARCHAR(1000) COMMENT '권리 침해 양식의 대상 주소',
    reason VARCHAR(20) NOT NULL COMMENT '사유 SPAM/ABUSE/ADULT/ILLEGAL/PRIVACY/COPYRIGHT/DEFAMATION/OTHER',
    detail VARCHAR(1000) COMMENT '신고자 설명(OTHER면 필수)',
    rights_basis VARCHAR(2000) COMMENT '권리 근거-----(RIGHTS_REQUEST 필수)',
    contact_email_enc VARBINARY(512) COMMENT '비회원 연락 이메일 AES-256-GCM 암호문-----보존 기간 후 NULL',
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING' COMMENT '상태 PENDING/ACTIONED/DISMISSED',
    action VARCHAR(25) COMMENT '조치 HIDE_CONTENT/REMOVE_FROM_PORTAL/BLOCK_EXTERNAL_BLOG/SUSPEND_USER',
    resolution_note VARCHAR(1000) COMMENT '관리자 메모(신고자 비공개)',
    handled_at DATETIME(6) COMMENT '처리 시각',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '접수 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT uk_reports_reporter_target UNIQUE (reporter_id, target_type, target_id),
    CONSTRAINT ck_reports_reporter CHECK ((channel = 'MEMBER') = (reporter_id IS NOT NULL))
) COMMENT='신고-----(회원 신고 버튼, 비회원 권리 침해 신고 양식)';
CREATE TABLE banned_words (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '금칙어 ID',
    created_by BIGINT NOT NULL COMMENT '등록한 관리자',
    word VARCHAR(50) NOT NULL COMMENT '금칙어-----(NFKC·trim·소문자 정규화)',
    scope VARCHAR(10) NOT NULL DEFAULT 'ALL' COMMENT '적용 범위 NAME/CONTENT/ALL',
    action VARCHAR(10) NOT NULL DEFAULT 'REJECT' COMMENT '처리 REJECT/MASK',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '만든 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT uk_banned_words_word UNIQUE (word)
) COMMENT='금칙어';
CREATE TABLE admin_audit_logs (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '작업 기록 ID',
    admin_id BIGINT NOT NULL COMMENT '작업한 관리자',
    action VARCHAR(50) NOT NULL COMMENT '작업 종류-----(USER_SUSPEND, TOPIC_CREATE 등)',
    target_type VARCHAR(30) NOT NULL COMMENT '대상 종류-----(USER, TOPIC, SETTING 등)',
    target_id BIGINT COMMENT '대상 ID(외래 키 없음)',
    target_key VARCHAR(100) COMMENT '숫자 ID가 아닌 대상 식별 값-----(설정 키, 릴리스 노트 버전 등)',
    before_json JSON COMMENT '변경 전 값-----(바뀐 필드만, 개인정보 평문 금지)',
    after_json JSON COMMENT '변경 후 값-----(바뀐 필드만, 개인정보 평문 금지)',
    reason VARCHAR(500) COMMENT '관리자가 입력한 사유',
    request_ip_enc VARBINARY(128) COMMENT '요청 IP AES-256-GCM 암호문',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '작업 시각, 1년 후 정리',
    PRIMARY KEY (id)
) COMMENT='관리자 작업 기록-----(수정·삭제 불가, 1년 보관) (006 FR-106)';
CREATE TABLE release_notes (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '릴리스 노트 ID',
    created_by BIGINT NOT NULL COMMENT '만든 관리자',
    updated_by BIGINT NOT NULL COMMENT '마지막으로 저장한 관리자',
    version VARCHAR(20) NOT NULL COMMENT 'SemVer MAJOR-----MINOR.PATCH, 게시 후 변경 불가 (006 FR-167)',
    version_major INT NOT NULL COMMENT '버전 major',
    version_minor INT NOT NULL COMMENT '버전 minor',
    version_patch INT NOT NULL COMMENT '버전 patch',
    release_date DATE NOT NULL COMMENT '릴리스 날짜(표시용)',
    status VARCHAR(10) NOT NULL DEFAULT 'DRAFT' COMMENT '상태 DRAFT/PUBLISHED',
    current_revision_no INT NOT NULL DEFAULT 1 COMMENT '지금 내용의 수정본 번호-----(낙관적 잠금) (006 FR-168)',
    first_published_at DATETIME(6) COMMENT '처음 게시한 시각-----(포털 카드·배너 기준)',
    first_published_revision_no INT COMMENT '처음 게시한 때의 수정본 번호-----(003 FR-166)',
    published_at DATETIME(6) COMMENT '가장 최근 게시 시각-----DRAFT면 NULL',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '만든 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT uk_release_notes_version UNIQUE (version)
) COMMENT='릴리스 노트';
CREATE TABLE release_note_contents (
    release_note_id BIGINT NOT NULL COMMENT '릴리스 노트',
    lang VARCHAR(5) NOT NULL COMMENT '언어 ko/en/ja/zh-CN-----(ko 필수)',
    title VARCHAR(200) NOT NULL COMMENT '언어판 제목',
    content_md MEDIUMTEXT NOT NULL COMMENT '원문 Markdown-----(최대 100,000자)',
    content_html MEDIUMTEXT COMMENT '변환·살균한 HTML-----(제목 앵커 포함)',
    content_text MEDIUMTEXT COMMENT '태그 제거 텍스트(검색용)',
    toc_json JSON COMMENT '자동 목차 [{level-----text, anchor}]',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '만든 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (release_note_id, lang)
) COMMENT='릴리스 노트 언어판';
CREATE TABLE release_note_revisions (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '수정본 ID',
    release_note_id BIGINT NOT NULL COMMENT '릴리스 노트',
    edited_by BIGINT NOT NULL COMMENT '저장한 관리자',
    revision_no INT NOT NULL COMMENT '수정본 번호(1부터)',
    status VARCHAR(10) NOT NULL COMMENT '저장 당시 상태 DRAFT/PUBLISHED',
    version VARCHAR(20) NOT NULL COMMENT '저장 당시 버전',
    release_date DATE NOT NULL COMMENT '저장 당시 릴리스 날짜',
    contents_json JSON NOT NULL COMMENT '저장 당시 모든 언어판-----(제목, Markdown)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '저장 시각',
    PRIMARY KEY (id),
    CONSTRAINT uk_release_note_revisions_note_revision UNIQUE (release_note_id, revision_no)
) COMMENT='릴리스 노트 수정본-----(저장마다 1행, 불변) (006 FR-168)';
CREATE TABLE external_blogs (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '외부 블로그 ID',
    default_topic_id BIGINT NOT NULL COMMENT '기본 주제(소분류)',
    member_id BIGINT COMMENT '신청·소유 회원-----운영자 직접 등록이면 NULL (007 FR-129)',
    reviewed_by BIGINT COMMENT '승인·거절·직접 등록한 관리자',
    registration_type VARCHAR(15) NOT NULL COMMENT '등록 경로 MEMBER_REQUEST/ADMIN_DIRECT-----(007 FR-111)',
    ownership_verified TINYINT(1) NOT NULL DEFAULT FALSE COMMENT '소유 인증 여부-----(007 FR-128)',
    ownership_verified_at DATETIME(6) COMMENT '소유 인증 시각',
    registration_basis VARCHAR(500) COMMENT '등록 근거-----(ADMIN_DIRECT 필수)',
    title VARCHAR(200) COMMENT '블로그 이름(피드에서 읽음)',
    site_url VARCHAR(1000) COMMENT '블로그 주소',
    feed_url VARCHAR(1000) NOT NULL COMMENT 'RSS·Atom 주소',
    feed_url_hash CHAR(64) NOT NULL COMMENT '정규화한 feed_url의 SHA-256',
    active_feed_hash CHAR(64) GENERATED ALWAYS AS (IF(status IN ('REJECTED', 'RELEASED'), NULL, feed_url_hash)) STORED COMMENT '거절·해제되지 않은 등록은 피드당 하나-----(생성 컬럼) (007 FR-112)',
    feed_format VARCHAR(10) COMMENT '피드 형식 RSS/ATOM',
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING' COMMENT '상태 PENDING/REJECTED/ACTIVE/PAUSED/STOPPED/BLOCKED/RELEASED',
    reject_reason VARCHAR(500) COMMENT '거절 사유',
    reviewed_at DATETIME(6) COMMENT '승인·거절·직접 등록 시각',
    etag VARCHAR(255) COMMENT '마지막 응답 ETag-----(007 FR-116)',
    last_modified VARCHAR(64) COMMENT '마지막 응답 Last-Modified 원문',
    next_fetch_at DATETIME(6) COMMENT '다음 수집 시각(ACTIVE일 때만)',
    last_fetched_at DATETIME(6) COMMENT '마지막 시도 시각',
    last_success_at DATETIME(6) COMMENT '마지막 성공(200·304) 시각',
    last_fetch_result VARCHAR(20) COMMENT '마지막 결과 OK/NOT_MODIFIED/HTTP_ERROR/TIMEOUT/TOO_LARGE/PARSE_ERROR/BLOCKED_ADDRESS/DNS_ERROR',
    last_http_status INT COMMENT '마지막 HTTP 상태 코드',
    consecutive_failures INT NOT NULL DEFAULT 0 COMMENT '연속 실패 수',
    first_failed_at DATETIME(6) COMMENT '연속 실패 시작 시각-----7일이면 STOPPED (007 FR-117)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '등록 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT uk_external_blogs_active_feed_hash UNIQUE (active_feed_hash)
) COMMENT='외부 블로그';
CREATE TABLE external_blog_verifications (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '소유 인증 ID',
    external_blog_id BIGINT COMMENT '연결된 외부 블로그 등록',
    user_id BIGINT NOT NULL COMMENT '인증하는 회원',
    feed_url_hash CHAR(64) NOT NULL COMMENT '인증 대상 피드 해시-----(등록 전에도 사용)',
    code VARCHAR(32) NOT NULL COMMENT '발급한 인증 코드(무작위)',
    expires_at DATETIME(6) NOT NULL COMMENT '만료-----(발급 + 24시간) (007 FR-110)',
    verified_at DATETIME(6) COMMENT '인증 성공 시각',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '발급 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT uk_external_blog_verifications_code UNIQUE (code)
) COMMENT='외부 블로그 소유 인증-----(007 FR-110, FR-129)';
CREATE TABLE external_posts (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '외부 글 ID',
    external_blog_id BIGINT NOT NULL COMMENT '외부 블로그',
    topic_id BIGINT NOT NULL COMMENT '최종 주제(소분류)',
    classifier_topic_id BIGINT COMMENT '자동 분류가 낸 주제',
    guid VARCHAR(1000) COMMENT '피드의 고유값-----(RSS guid, Atom id)',
    guid_hash CHAR(64) COMMENT 'guid의 SHA-256-----(007 FR-115)',
    link VARCHAR(2000) NOT NULL COMMENT '원문 주소',
    link_hash CHAR(64) NOT NULL COMMENT '정규화한 link의 SHA-256',
    title VARCHAR(300) NOT NULL COMMENT '제목(태그 제거)',
    summary VARCHAR(200) COMMENT '요약-----(최대 200자, 본문은 저장하지 않음) (007 FR-114)',
    image_url VARCHAR(1000) COMMENT '피드의 대표 이미지 원본 주소',
    thumbnail_key CHAR(22) COMMENT '저장한 썸네일 키-----(소유 인증 블로그만 노출, 007 FR-128)',
    published_at DATETIME(6) COMMENT '피드의 발행 시각',
    feed_terms_json JSON COMMENT '피드의 카테고리·태그 원문 배열',
    topic_source VARCHAR(10) NOT NULL DEFAULT 'DEFAULT' COMMENT '주제 출처 OWNER/REVIEW/RULE/AUTO/DEFAULT-----(007 FR-118)',
    topic_decided_at DATETIME(6) COMMENT '사람이 주제를 정한 시각-----(OWNER·REVIEW)',
    classifier_confidence DECIMAL(4,3) COMMENT '자동 분류 신뢰도 0~1',
    classifier_version VARCHAR(30) COMMENT '분류 방식 식별자-----(007 FR-119)',
    click_count INT NOT NULL DEFAULT 0 COMMENT '포털 카드 클릭 누적-----(007 FR-124)',
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' COMMENT '상태 ACTIVE/REMOVED',
    removed_reason VARCHAR(20) COMMENT '내린 이유 LINK_BROKEN/BLOG_BLOCKED/MEMBER_WITHDRAWN/REPORT/ADMIN',
    link_checked_at DATETIME(6) COMMENT '마지막 원문 링크 점검 시각',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '수집 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT uk_external_posts_blog_guid_hash UNIQUE (external_blog_id, guid_hash),
    CONSTRAINT uk_external_posts_blog_link_hash UNIQUE (external_blog_id, link_hash)
) COMMENT='외부 글-----(메타데이터만, 본문 없음)';
CREATE TABLE external_post_daily_clicks (
    external_post_id BIGINT NOT NULL COMMENT '외부 글',
    click_date DATE NOT NULL COMMENT 'UTC 날짜',
    clicks INT NOT NULL DEFAULT 0 COMMENT '클릭 수-----(같은 방문자 30분 중복 제거)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '만든 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (external_post_id, click_date)
) COMMENT='외부 글 일별 클릭-----(007 FR-124)';
CREATE TABLE topic_mapping_rules (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '매핑 규칙 ID',
    topic_id BIGINT NOT NULL COMMENT '대상 주제(소분류)',
    created_by BIGINT NOT NULL COMMENT '만든 관리자',
    keyword VARCHAR(100) NOT NULL COMMENT '피드 카테고리·태그와 비교할 값-----(정규화, 완전 일치)',
    priority INT NOT NULL DEFAULT 0 COMMENT '우선순위(큰 값 먼저)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '만든 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT uk_topic_mapping_rules_keyword UNIQUE (keyword)
) COMMENT='주제 매핑 규칙-----(007 FR-118, FR-121)';
CREATE TABLE classification_reviews (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '분류 검수 ID',
    predicted_topic_id BIGINT COMMENT '자동 분류 결과 주제',
    confirmed_topic_id BIGINT COMMENT '운영자가 확정한 주제',
    external_post_id BIGINT NOT NULL COMMENT '외부 글(글당 하나)',
    reviewed_by BIGINT COMMENT '검수한 관리자',
    confidence DECIMAL(4,3) COMMENT '자동 분류 신뢰도',
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING' COMMENT '상태 PENDING/CONFIRMED/SKIPPED',
    reviewed_at DATETIME(6) COMMENT '확정 시각',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '만든 시각',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT uk_classification_reviews_external_post UNIQUE (external_post_id)
) COMMENT='분류 검수-----(007 FR-119, FR-121, FR-122)';

ALTER TABLE users ADD CONSTRAINT fk_users_profile_media FOREIGN KEY (profile_media_id) REFERENCES media (id);
ALTER TABLE refresh_tokens ADD CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE login_history ADD CONSTRAINT fk_login_history_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE password_reset_tokens ADD CONSTRAINT fk_password_reset_tokens_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE blogs ADD CONSTRAINT fk_blogs_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE blogs ADD CONSTRAINT fk_blogs_cover_media FOREIGN KEY (cover_media_id) REFERENCES media (id);
ALTER TABLE blogs ADD CONSTRAINT fk_blogs_default_topic FOREIGN KEY (default_topic_id) REFERENCES topics (id);
ALTER TABLE categories ADD CONSTRAINT fk_categories_blog FOREIGN KEY (blog_id) REFERENCES blogs (id);
ALTER TABLE categories ADD CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES categories (id);
ALTER TABLE posts ADD CONSTRAINT fk_posts_blog FOREIGN KEY (blog_id) REFERENCES blogs (id);
ALTER TABLE posts ADD CONSTRAINT fk_posts_category FOREIGN KEY (category_id) REFERENCES categories (id);
ALTER TABLE posts ADD CONSTRAINT fk_posts_topic FOREIGN KEY (topic_id) REFERENCES topics (id);
ALTER TABLE post_drafts ADD CONSTRAINT fk_post_drafts_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE;
ALTER TABLE post_tags ADD CONSTRAINT fk_post_tags_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE;
ALTER TABLE post_tags ADD CONSTRAINT fk_post_tags_tag FOREIGN KEY (tag_id) REFERENCES tags (id);
ALTER TABLE media ADD CONSTRAINT fk_media_owner FOREIGN KEY (owner_id) REFERENCES users (id);
ALTER TABLE post_media ADD CONSTRAINT fk_post_media_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE;
ALTER TABLE post_media ADD CONSTRAINT fk_post_media_media FOREIGN KEY (media_id) REFERENCES media (id);
ALTER TABLE comments ADD CONSTRAINT fk_comments_post FOREIGN KEY (post_id) REFERENCES posts (id);
ALTER TABLE comments ADD CONSTRAINT fk_comments_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE comments ADD CONSTRAINT fk_comments_parent FOREIGN KEY (parent_id) REFERENCES comments (id);
ALTER TABLE guestbook_entries ADD CONSTRAINT fk_guestbook_entries_blog FOREIGN KEY (blog_id) REFERENCES blogs (id);
ALTER TABLE guestbook_entries ADD CONSTRAINT fk_guestbook_entries_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE guestbook_entries ADD CONSTRAINT fk_guestbook_entries_parent FOREIGN KEY (parent_id) REFERENCES guestbook_entries (id);
ALTER TABLE trackbacks ADD CONSTRAINT fk_trackbacks_post FOREIGN KEY (post_id) REFERENCES posts (id);
ALTER TABLE trackbacks ADD CONSTRAINT fk_trackbacks_source_post FOREIGN KEY (source_post_id) REFERENCES posts (id);
ALTER TABLE trackback_ping_logs ADD CONSTRAINT fk_trackback_ping_logs_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE;
ALTER TABLE post_likes ADD CONSTRAINT fk_post_likes_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE post_likes ADD CONSTRAINT fk_post_likes_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE;
ALTER TABLE blog_subscriptions ADD CONSTRAINT fk_blog_subscriptions_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE blog_subscriptions ADD CONSTRAINT fk_blog_subscriptions_blog FOREIGN KEY (blog_id) REFERENCES blogs (id);
ALTER TABLE notifications ADD CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE notifications ADD CONSTRAINT fk_notifications_actor_user FOREIGN KEY (actor_user_id) REFERENCES users (id);
ALTER TABLE notifications ADD CONSTRAINT fk_notifications_blog FOREIGN KEY (blog_id) REFERENCES blogs (id);
ALTER TABLE topics ADD CONSTRAINT fk_topics_parent FOREIGN KEY (parent_id) REFERENCES topics (id);
ALTER TABLE portal_curations ADD CONSTRAINT fk_portal_curations_post FOREIGN KEY (post_id) REFERENCES posts (id);
ALTER TABLE portal_curations ADD CONSTRAINT fk_portal_curations_created_by FOREIGN KEY (created_by) REFERENCES users (id);
ALTER TABLE portal_exclusions ADD CONSTRAINT fk_portal_exclusions_post FOREIGN KEY (post_id) REFERENCES posts (id);
ALTER TABLE portal_exclusions ADD CONSTRAINT fk_portal_exclusions_external_post FOREIGN KEY (external_post_id) REFERENCES external_posts (id);
ALTER TABLE portal_exclusions ADD CONSTRAINT fk_portal_exclusions_excluded_by FOREIGN KEY (excluded_by) REFERENCES users (id);
ALTER TABLE post_daily_stats ADD CONSTRAINT fk_post_daily_stats_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE;
ALTER TABLE system_settings ADD CONSTRAINT fk_system_settings_updated_by FOREIGN KEY (updated_by) REFERENCES users (id);
ALTER TABLE blog_sidebar_items ADD CONSTRAINT fk_blog_sidebar_items_blog FOREIGN KEY (blog_id) REFERENCES blogs (id);
ALTER TABLE blog_daily_visits ADD CONSTRAINT fk_blog_daily_visits_blog FOREIGN KEY (blog_id) REFERENCES blogs (id);
ALTER TABLE blog_exports ADD CONSTRAINT fk_blog_exports_blog FOREIGN KEY (blog_id) REFERENCES blogs (id);
ALTER TABLE blog_exports ADD CONSTRAINT fk_blog_exports_requested_by FOREIGN KEY (requested_by) REFERENCES users (id);
ALTER TABLE blog_blocks ADD CONSTRAINT fk_blog_blocks_blog FOREIGN KEY (blog_id) REFERENCES blogs (id);
ALTER TABLE blog_blocks ADD CONSTRAINT fk_blog_blocks_blocked_user FOREIGN KEY (blocked_user_id) REFERENCES users (id);
ALTER TABLE reports ADD CONSTRAINT fk_reports_reporter FOREIGN KEY (reporter_id) REFERENCES users (id);
ALTER TABLE reports ADD CONSTRAINT fk_reports_target_user FOREIGN KEY (target_user_id) REFERENCES users (id);
ALTER TABLE reports ADD CONSTRAINT fk_reports_target_blog FOREIGN KEY (target_blog_id) REFERENCES blogs (id);
ALTER TABLE reports ADD CONSTRAINT fk_reports_handled_by FOREIGN KEY (handled_by) REFERENCES users (id);
ALTER TABLE banned_words ADD CONSTRAINT fk_banned_words_created_by FOREIGN KEY (created_by) REFERENCES users (id);
ALTER TABLE admin_audit_logs ADD CONSTRAINT fk_admin_audit_logs_admin FOREIGN KEY (admin_id) REFERENCES users (id);
ALTER TABLE release_notes ADD CONSTRAINT fk_release_notes_created_by FOREIGN KEY (created_by) REFERENCES users (id);
ALTER TABLE release_notes ADD CONSTRAINT fk_release_notes_updated_by FOREIGN KEY (updated_by) REFERENCES users (id);
ALTER TABLE release_note_contents ADD CONSTRAINT fk_release_note_contents_release_note FOREIGN KEY (release_note_id) REFERENCES release_notes (id) ON DELETE CASCADE;
ALTER TABLE release_note_revisions ADD CONSTRAINT fk_release_note_revisions_release_note FOREIGN KEY (release_note_id) REFERENCES release_notes (id) ON DELETE CASCADE;
ALTER TABLE release_note_revisions ADD CONSTRAINT fk_release_note_revisions_edited_by FOREIGN KEY (edited_by) REFERENCES users (id);
ALTER TABLE external_blogs ADD CONSTRAINT fk_external_blogs_member FOREIGN KEY (member_id) REFERENCES users (id);
ALTER TABLE external_blogs ADD CONSTRAINT fk_external_blogs_default_topic FOREIGN KEY (default_topic_id) REFERENCES topics (id);
ALTER TABLE external_blogs ADD CONSTRAINT fk_external_blogs_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES users (id);
ALTER TABLE external_blog_verifications ADD CONSTRAINT fk_external_blog_verifications_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE external_blog_verifications ADD CONSTRAINT fk_external_blog_verifications_external_blog FOREIGN KEY (external_blog_id) REFERENCES external_blogs (id);
ALTER TABLE external_posts ADD CONSTRAINT fk_external_posts_external_blog FOREIGN KEY (external_blog_id) REFERENCES external_blogs (id);
ALTER TABLE external_posts ADD CONSTRAINT fk_external_posts_topic FOREIGN KEY (topic_id) REFERENCES topics (id);
ALTER TABLE external_posts ADD CONSTRAINT fk_external_posts_classifier_topic FOREIGN KEY (classifier_topic_id) REFERENCES topics (id);
ALTER TABLE external_post_daily_clicks ADD CONSTRAINT fk_external_post_daily_clicks_external_post FOREIGN KEY (external_post_id) REFERENCES external_posts (id) ON DELETE CASCADE;
ALTER TABLE topic_mapping_rules ADD CONSTRAINT fk_topic_mapping_rules_topic FOREIGN KEY (topic_id) REFERENCES topics (id);
ALTER TABLE topic_mapping_rules ADD CONSTRAINT fk_topic_mapping_rules_created_by FOREIGN KEY (created_by) REFERENCES users (id);
ALTER TABLE classification_reviews ADD CONSTRAINT fk_classification_reviews_external_post FOREIGN KEY (external_post_id) REFERENCES external_posts (id) ON DELETE CASCADE;
ALTER TABLE classification_reviews ADD CONSTRAINT fk_classification_reviews_predicted_topic FOREIGN KEY (predicted_topic_id) REFERENCES topics (id);
ALTER TABLE classification_reviews ADD CONSTRAINT fk_classification_reviews_confirmed_topic FOREIGN KEY (confirmed_topic_id) REFERENCES topics (id);
ALTER TABLE classification_reviews ADD CONSTRAINT fk_classification_reviews_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES users (id);

CREATE INDEX idx_users_role_status ON users (role ASC, status ASC);
CREATE INDEX idx_users_nickname ON users (nickname ASC);
CREATE INDEX idx_users_created ON users (created_at ASC);
CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens (family_id ASC);
CREATE INDEX idx_login_history_user_created ON login_history (user_id ASC, created_at ASC);
CREATE INDEX idx_login_history_created ON login_history (created_at ASC);
CREATE INDEX idx_blogs_user_status ON blogs (user_id ASC, status ASC);
CREATE INDEX idx_blogs_first_published_at ON blogs (first_published_at ASC);
CREATE INDEX idx_posts_blog_status_visibility_published ON posts (blog_id ASC, status ASC, visibility ASC, published_at DESC);
CREATE INDEX idx_posts_category ON posts (category_id ASC);
CREATE INDEX idx_posts_topic_status_visibility_published ON posts (topic_id ASC, status ASC, visibility ASC, published_at DESC);
CREATE INDEX idx_posts_status_scheduled ON posts (status ASC, scheduled_at ASC);
CREATE INDEX idx_posts_blog_notice_published ON posts (blog_id ASC, notice ASC, published_at DESC);
CREATE FULLTEXT INDEX ft_posts_title_content ON posts (title, content_text) WITH PARSER ngram;
CREATE FULLTEXT INDEX ft_posts_title ON posts (title) WITH PARSER ngram;
CREATE INDEX idx_posts_status_visibility_published ON posts (status ASC, visibility ASC, published_at DESC, id DESC);
CREATE INDEX idx_posts_published_at ON posts (published_at ASC);
CREATE INDEX idx_posts_topic_status_visibility_published_id ON posts (topic_id ASC, status ASC, visibility ASC, published_at DESC, id DESC);
CREATE FULLTEXT INDEX ft_tags_name ON tags (name) WITH PARSER ngram;
CREATE INDEX idx_post_tags_tag ON post_tags (tag_id ASC);
CREATE INDEX idx_media_status_created ON media (status ASC, created_at ASC);
CREATE INDEX idx_media_owner_status ON media (owner_id ASC, status ASC);
CREATE INDEX idx_post_media_media ON post_media (media_id ASC);
CREATE INDEX idx_comments_post_created ON comments (post_id ASC, created_at ASC);
CREATE INDEX idx_comments_user ON comments (user_id ASC);
CREATE INDEX idx_comments_parent ON comments (parent_id ASC);
CREATE INDEX idx_comments_status_created ON comments (status ASC, created_at ASC);
CREATE INDEX idx_guestbook_entries_blog_status_created ON guestbook_entries (blog_id ASC, status ASC, created_at DESC);
CREATE INDEX idx_guestbook_entries_parent ON guestbook_entries (parent_id ASC);
CREATE INDEX idx_guestbook_entries_user ON guestbook_entries (user_id ASC);
CREATE INDEX idx_guestbook_entries_status_created ON guestbook_entries (status ASC, created_at ASC);
CREATE INDEX idx_trackbacks_post_status_created ON trackbacks (post_id ASC, status ASC, created_at DESC);
CREATE INDEX idx_trackbacks_source_post ON trackbacks (source_post_id ASC);
CREATE INDEX idx_trackback_ping_logs_post_created ON trackback_ping_logs (post_id ASC, created_at DESC);
CREATE INDEX idx_trackback_ping_logs_status_created ON trackback_ping_logs (status ASC, created_at ASC);
CREATE INDEX idx_post_likes_post_created ON post_likes (post_id ASC, created_at ASC);
CREATE INDEX idx_blog_subscriptions_blog ON blog_subscriptions (blog_id ASC);
CREATE INDEX idx_notifications_user_created ON notifications (user_id ASC, created_at DESC);
CREATE INDEX idx_notifications_user_read ON notifications (user_id ASC, read_at ASC);
CREATE INDEX idx_topics_parent_sort ON topics (parent_id ASC, sort_order ASC);
CREATE INDEX idx_portal_curations_period ON portal_curations (starts_at ASC, ends_at ASC);
CREATE INDEX idx_post_daily_stats_stat_date ON post_daily_stats (stat_date ASC);
CREATE INDEX idx_blog_exports_blog_created ON blog_exports (blog_id ASC, created_at DESC);
CREATE INDEX idx_blog_exports_status_expires ON blog_exports (status ASC, expires_at ASC);
CREATE INDEX idx_blog_blocks_blocked_user ON blog_blocks (blocked_user_id ASC);
CREATE INDEX idx_reports_status_created ON reports (status ASC, created_at ASC);
CREATE INDEX idx_reports_target ON reports (target_type ASC, target_id ASC);
CREATE INDEX idx_reports_target_user ON reports (target_user_id ASC);
CREATE INDEX idx_reports_target_blog_status ON reports (target_blog_id ASC, status ASC);
CREATE INDEX idx_reports_handled_by ON reports (handled_by ASC);
CREATE INDEX idx_admin_audit_logs_created ON admin_audit_logs (created_at ASC);
CREATE INDEX idx_admin_audit_logs_admin_created ON admin_audit_logs (admin_id ASC, created_at ASC);
CREATE INDEX idx_admin_audit_logs_action_created ON admin_audit_logs (action ASC, created_at ASC);
CREATE INDEX idx_admin_audit_logs_target ON admin_audit_logs (target_type ASC, target_id ASC);
CREATE INDEX idx_release_notes_status_version ON release_notes (status ASC, version_major ASC, version_minor ASC, version_patch ASC);
CREATE FULLTEXT INDEX ft_release_note_contents ON release_note_contents (title, content_text) WITH PARSER ngram;
CREATE INDEX idx_external_blogs_status_next_fetch ON external_blogs (status ASC, next_fetch_at ASC);
CREATE INDEX idx_external_blogs_member_status ON external_blogs (member_id ASC, status ASC);
CREATE INDEX idx_external_blogs_feed_url_hash ON external_blogs (feed_url_hash ASC);
CREATE INDEX idx_external_blog_verifications_user_feed_created ON external_blog_verifications (user_id ASC, feed_url_hash ASC, created_at DESC);
CREATE INDEX idx_external_posts_status_published ON external_posts (status ASC, published_at DESC);
CREATE INDEX idx_external_posts_topic_status_published ON external_posts (topic_id ASC, status ASC, published_at DESC);
CREATE INDEX idx_external_posts_blog_published ON external_posts (external_blog_id ASC, published_at DESC);
CREATE INDEX idx_external_posts_status_link_checked ON external_posts (status ASC, link_checked_at ASC);
CREATE INDEX idx_external_posts_classifier_topic ON external_posts (classifier_topic_id ASC);
CREATE INDEX idx_external_posts_topic_decided ON external_posts (topic_decided_at ASC);
CREATE INDEX idx_external_post_daily_clicks_click_date ON external_post_daily_clicks (click_date ASC);
CREATE INDEX idx_classification_reviews_status_created ON classification_reviews (status ASC, created_at ASC);
