# blog-backend 운영 문서

1.0 운영 범위(헌법 "1.0 운영 범위", 001 tasks T243)에서 서버를 띄우고 유지하는 데 필요한 것만 적는다.
설정 값의 기준은 `src/main/resources/application.yml`·`application-prod.yml`이고, API 쪽 프로퍼티 표는
`blog-docs/specs/001-blog-core/contracts/api.md`의 "프로퍼티" 절이다. 둘이 다르면 yml이 실제 동작이다.

> 이 문서에는 비밀 값을 적지 않는다. 비밀 값은 운영 서버의 환경 변수(또는 비밀 관리 도구)에만 둔다.

## 1. 실행

- 운영 프로필: `SPRING_PROFILES_ACTIVE=prod`. 프로필을 주지 않으면 `local`(개발 DB, `.env`)로 뜨므로 운영에서는 반드시 지정한다.
- Java 21. 빌드: `./mvnw -B -DskipTests package` → `target/blog-backend-*.jar`.
- 스키마는 Flyway 없이 Crowfoot 문서(blog 1.0)가 원천이다. 앱은 `ddl-auto=validate`로 맞는지만 확인하므로
  배포 전에 `blog-docs/db/migrations`의 ALTER를 먼저 반영한다. 스키마가 다르면 기동에 실패한다.
- 상태 확인: `GET /actuator/health`(상세 없음, 메일 서버 상태는 넣지 않음). 다른 actuator 엔드포인트는 열지 않는다.
- OpenAPI 문서(`/v3/api-docs`, Swagger UI)는 prod에서 꺼져 있다. front 타입 생성은 로컬 backend에서 한다.
- 정기 작업은 서버 1대를 전제로 한다(분산 락 없음). 같은 DB에 인스턴스를 둘 이상 띄우지 않는다.

## 2. 환경 변수

### 2.1 prod에서 반드시 주는 값

기본값이 없어 빠지면 기동에 실패한다.

| 환경 변수 | 프로퍼티 | 비밀 | 설명 |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `spring.profiles.active` | | `prod` |
| `SPRING_DATASOURCE_URL` | `spring.datasource.url` | | MySQL 8 JDBC URL. `connectionTimeZone=UTC&characterEncoding=UTF-8`을 붙인다 |
| `SPRING_DATASOURCE_USERNAME` | `spring.datasource.username` | | DB 계정 |
| `SPRING_DATASOURCE_PASSWORD` | `spring.datasource.password` | 예 | DB 비밀번호 |
| `BLOG_CRYPTO_KEY_V1` | `blog.crypto.keys[1]` | 예 | 개인정보 암호화 키(Base64 32바이트, `openssl rand -base64 32`) |
| `BLOG_CRYPTO_HASH_KEY` | `blog.crypto.hash-key` | 예 | 이메일 검색용 HMAC 키(Base64 32바이트). 한번 정하면 바꾸지 않는다(바꾸면 모든 `*_hash` 재계산) |
| `BLOG_AUTH_JWT_SECRET` | `blog.auth.jwt-secret` | 예 | 접근 토큰(HS256) 서명 키, UTF-8 32바이트 이상(`openssl rand -base64 48`). 바꾸면 발급된 접근 토큰이 모두 무효(리프레시로 다시 받음) |
| `BLOG_MAIL_HOST` | `blog.mail.host` | | SMTP 서버(비밀번호 재설정 메일) |
| `BLOG_MAIL_PORT` | `blog.mail.port` | | SMTP 포트 |
| `BLOG_MAIL_FROM` | `blog.mail.from` | | 보내는 사람 주소 |
| `BLOG_DATA_DIR` | `blog.media.*-dir`, `blog.export.dir` | | 파일 보관 루트(필수). `media/upload`(이미지 정식, **백업 대상**, 4절), `media/temp`(업로드 임시, 백업 제외), `media/thumbnail`(썸네일, 다시 만들 수 있어 백업 제외), `exports`(블로그 백업 zip, 004 FR-145, 백업 제외 4.1절)를 이 아래에 만든다 |
| `BLOG_CAPTCHA_SITE_KEY` | `blog.captcha.site-key` | | Cloudflare Turnstile 사이트 키(005 FR-141, 10.1절). 브라우저에 보이는 공개 값. **1.0에서는 CAPTCHA를 꺼서 넣지 않는다** |
| `BLOG_CAPTCHA_SECRET_KEY` | `blog.captcha.secret-key` | 예 | Turnstile 비밀 키. provider가 `turnstile`일 때 두 키 중 하나라도 비면 기동 실패. 1.0에서는 넣지 않는다 |

세 이미지 디렉터리와 백업 디렉터리는 앱 실행 계정이 쓸 수 있어야 한다. 없으면 기동 때 만들고, 쓸 수 없으면 기동을 멈춘다.
정식·임시 디렉터리는 같은 파일 시스템에 두는 것을 권한다(등록 때 임시 → 정식으로 옮긴다).

### 2.2 선택 값

| 환경 변수 | 프로퍼티 | 비밀 | 비우면 |
|---|---|---|---|
| `BLOG_MAIL_USERNAME` | `blog.mail.username` | | SMTP 인증 없음 |
| `BLOG_MAIL_PASSWORD` | `blog.mail.password` | 예 | SMTP 인증 없음 |
| `BLOG_MAIL_STARTTLS` | `blog.mail.starttls` | | `true` |
| `BLOG_CRYPTO_ACTIVE_KEY_VERSION` | `blog.crypto.active-key-version` | | `1` |
| `BLOG_SECURITY_TRUSTED_PROXIES` | `blog.security.trusted-proxies` | | `127.0.0.1,::1`. front 서버가 다른 호스트·컨테이너면 그 주소(IP·CIDR, 쉼표 구분)로 바꾼다. 틀리면 방문자 IP 대신 front 주소가 기록된다 |
| `BLOG_LEGAL_TERMS_VERSION` | `blog.legal.terms-version` | | `application.yml`의 값. 약관 문안을 바꾸면 올린다 |
| `BLOG_ADMIN_BOOTSTRAP_SUPER_ADMIN_EMAIL` | `blog.admin.bootstrap-super-admin-email` | | 아무것도 하지 않음(6절) |
| `LOGGING_FILE_PATH` | `logging.file.path` | | `./logs`(작업 디렉터리 기준). 3절 |
| `LOGGING_LOGBACK_ROLLINGPOLICY_MAX_HISTORY` | `logging.logback.rollingpolicy.max-history` | | `30`(일) |

암호화 키 교체: 새 버전 키를 `BLOG_CRYPTO_KEYS_2`처럼 추가 → `BLOG_CRYPTO_ACTIVE_KEY_VERSION=2` → 재암호화 후 이전 키 제거.

### 2.3 그 밖의 프로퍼티(기본값으로 운영)

`application.yml`에 기본값이 있고 운영에서 보통 바꾸지 않는다. 바꿔야 하면 같은 이름의 환경 변수
(점·하이픈 → 밑줄, 대문자. 예: `blog.jobs.trash-retention` → `BLOG_JOBS_TRASHRETENTION`)로 덮어쓴다.

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `blog.base-url` | `https://blog.java21.net` | 메일 링크 등 절대 URL |
| `blog.security.allowed-origins` | `https://blog.java21.net`(prod) | 상태 변경 요청의 Origin 허용 목록 |
| `blog.auth.access-ttl` / `refresh-idle-ttl` / `refresh-absolute-ttl` / `refresh-reuse-grace` | 30m / 4h / 7d / 10s | 토큰 수명 |
| `blog.auth.login-max-failures` / `login-lock-duration` | 5 / 10m | 로그인 잠금 |
| `blog.blogs.default-max-per-member` | 3 | 회원별 한도가 없을 때 블로그 수 한도 |
| `blog.posts.view-dedup-ttl` / `view-dedup-max-size` | 30m / 100000 | 조회수 중복 제거 |
| `blog.privacy.withdrawn-retention` / `login-history-retention` | 30d / 90d | 개인정보 보관 기간 |
| `blog.jobs.trash-retention` / `purge-batch-size` | 30d / 500 | 휴지통 보관 기간, 정리 작업 한 번에 처리하는 건수 |
| `blog.notifications.retention` / `subscriber-dedup-window` | 90d / 24h | 알림 보관 기간, 같은 구독자의 새 구독 알림을 다시 만들지 않는 기간(002) |
| `blog.search.max-terms` / `min-term-length` | 5 / 2 | 검색어 낱말 수 상한, 낱말 최소 길이(MySQL `ngram_token_size`와 같게, 002) |
| `blog.sitemap.urls-per-file` | 50000 | `/sitemap/posts-{n}.xml` 파일 하나의 주소 수(002) |
| `blog.media.temp-ttl` / `temp-quota` / `max-size` / `max-pixels` | 24h / 200MB / 10MB / 40000000 | 이미지 한도 |
| `blog.media.allowed-types` | jpeg, png, gif, webp | 업로드 허용 형식(내용 기준) |
| `blog.media.thumbnail.sizes` | `application.yml` 참고 | 허용 썸네일 크기 |
| `blog.portal.cache-ttl` / `cache-max-size` | 5m / 2000 | 포털 목록·인기 점수·주제별 글 수 캐시(003 FR-090). 0s면 캐시하지 않음 |
| `blog.portal.score-weights.*` | view 1, read-complete 5, like 10, comment 8, half-life-hours 48, report-penalty 0.5 | 인기 점수 가중치(003 research P4). 운영 설정 `portal.score-weights`가 있으면 그 값 |
| `blog.portal.new-member-delay` | 24h | 가입 후 포털 노출까지 대기(003 FR-088). 운영 설정 `portal.new-member-delay` 우선 |
| `blog.portal.min-content-length` | 200 | 포털 노출 최소 본문 길이(문자 수). 운영 설정 `portal.min-content-length` 우선 |
| `blog.portal.topic-auto-hide-threshold` | 20 | 주제 탭 자동 숨김 기준(최근 30일 글 수). 운영 설정 `portal.topic-auto-hide-threshold` 우선 |
| `blog.portal.popular-window` / `topic-count-window` | 7d / 30d | 인기 점수 기간, 주제별 글 수 기간 |
| `blog.posts.stats-retention` | 90d | 글 일별 통계(`post_daily_stats`) 보관 기간(003) |
| `blog.posts.unlock-ttl` | 30m | 보호 글 열람 쿠키(`post_unlock`) 수명(004 FR-062) |
| `blog.posts.password-max-failures` / `password-lock-duration` | 5 / 10m | 보호 글·비회원 글 비밀번호 연속 실패 허용 수와 막는 시간(IP·방문자 쿠키 기준, 메모리) |
| `blog.posts.schedule-max-ahead` | 365d | 예약 발행으로 정할 수 있는 가장 먼 시각(004 FR-064) |
| `blog.stats.time-zone` / `visit-dedup-max-size` / `bot-user-agent-pattern` | Asia/Seoul / 200000 / `application.yml` 참고 | 방문자 수·월별 보관함의 날짜 기준 시간대, 방문 중복 제거 캐시 크기, 세지 않는 User-Agent(004 FR-061·067) |
| `blog.guest.ip-retention` | 90d | 비회원 작성 IP 보관 기간. 지나면 개인정보 파기 작업이 IP만 지운다(글은 남김) |
| `blog.export.retention` / `min-interval` / `stale-running` | 7d / 24h / 1h | 백업 파일 보관 기간, 블로그별 요청 간격(하루 한 번), 이 시간 넘게 RUNNING이면 기동 때 실패 처리(004 FR-145) |
| `blog.jobs.scheduled-publish-delay` | 30s | 예약 발행 작업 주기(앞 실행이 끝난 뒤 기준). 실제 발행 지연은 최대 이 값 + 처리 시간 |
| `blog.jobs.export-poll-delay` | 30s | 대기 중인 백업을 만드는 작업 주기(한 번에 최대 10건을 차례로) |
| `blog.release-notes.portal-card-days` | 14d | 최신 릴리스 노트를 포털 메인 카드로 보여주는 기간(처음 게시부터, 003 FR-162) |
| `blog.admin.dashboard-cache-ttl` | 5m | 관리 콘솔 대시보드 수치를 관리자 시간대별로 메모리에 두는 시간(006 FR-103). 0s면 매번 계산(E2E는 `BLOG_ADMIN_DASHBOARD_CACHE_TTL=0s`). 음수면 기동 실패 |
| `blog.ratelimit.post-publish-per-hour` / `comment-per-minute` / `guestbook-per-minute` / `media-upload-per-minute` / `signup-per-ip-per-hour` | 10 / 5 / 3 / 30 / 5 | 작성 속도 한도 기본값(005 FR-142, 10.2절). 운영 설정 `ratelimit.*`가 있으면 그 값. 004의 `blog.guest.comment-per-minute`·`guestbook-per-minute`는 **없어졌다**(남겨 두면 무시된다. 환경 변수 `BLOG_GUEST_COMMENT_PER_MINUTE` 등은 지운다) |
| `blog.spam.duplicate-comment.window-minutes` / `max-count` / `min-length` | 10 / 3 / 10 | 같은 내용 댓글·방명록 반복 기준(005 FR-144). 앞 두 값은 운영 설정 `spam.duplicate-comment` 우선. `min-length`(이보다 짧은 글은 세지 않음)는 프로퍼티로만 |
| `blog.captcha.provider` | prod `none`(1.0에서 끔), local `none` | `turnstile`·`test`·`none`. prod에서 `test`면 기동 실패. `test`는 E2E 전용(토큰 `blog.captcha.test-token`, 기본 `e2e-pass`) |
| `blog.captcha.verify-timeout` | 3s | Turnstile 검증 요청 시간 제한. 넘거나 Turnstile 장애면 400 `CAPTCHA_FAILED`(통과시키지 않음) |
| `blog.captcha.login-failures-before-captcha` | 3 | 같은 이메일 또는 같은 IP의 연속 로그인 실패가 이 수 이상이면 다음 로그인에 CAPTCHA(30분 창, 성공하면 초기화). 001의 5회 잠금은 그대로 |
| `blog.reports.member-per-hour` / `rights-request-per-ip-per-hour` | 30 / 5 | 회원 신고 1시간 한도, 권리 침해 신고 IP당 1시간 한도(005 FR-040) |
| `blog.reports.penalty-window` | 90d | 포털 인기 점수 감점에 넣는 처리(ACTIONED) 신고 기간 |
| `blog.trackback.receive-limit` / `receive-window` | 10 / 10m | 같은 IP가 보낼 수 있는 트랙백 수(005 FR-054). 넘으면 TrackBack 응답 `Too many pings` |
| `blog.trackback.connect-timeout` / `read-timeout` | 5s / 5s | 트랙백 보내기 시간 제한 |
| `blog.trackback.max-targets` | 10 | 글 하나에서 한 번에 보낼 수 있는 주소 수(중복 제외) |
| `blog.trackback.executor-threads` / `executor-queue` / `recover-pending-after` | 2 / 100 / 5m | 트랙백 보내기 스레드·대기열, 기동 때 다시 보낼 PENDING 기준(11절) |
| `blog.outbound.allowed-ports` / `allow-private` | 80,443,8080,8443 / false | 서버가 밖으로 보내는 요청(트랙백 송신, 007 피드 수집)의 허용 포트와 내부망 허용. `allow-private`는 시험용이며 prod에서 true면 기동 실패(11절) |
| `blog.privacy.rights-request-retention` / `trackback-ip-retention` | 365d / 90d | 처리한 권리 침해 신고의 연락 이메일, 받은 트랙백의 송신 IP 보관 기간. 개인정보 파기 작업이 값만 지운다(005) |
| `blog.admin.audit-retention` | 365d | 관리자 작업 기록 보관 기간(006 FR-106). 30일보다 짧게 주면 기동 실패 |

## 3. 로그

`src/main/resources/logback-spring.xml`.

- 모든 줄에 요청 추적 ID `[traceId]`가 붙는다. 응답 헤더 `X-Request-Id`와 오류 응답의 `header.traceId`가 같은 값이므로
  사용자가 알려 준 추적 ID로 로그를 찾는다(`grep 4bf92f3577b34da6 blog-backend.log`). 정기 작업 로그에는 비어 있다.
- 시각은 UTC.
- prod: 콘솔과 함께 `${LOGGING_FILE_PATH}/blog-backend.log`에 쓰고, 매일(UTC 자정) `blog-backend.yyyy-MM-dd.log.gz`로 굴린다.
  30일이 지난 파일은 자동으로 지운다. 콘솔 출력을 따로 모으는 환경(systemd journal 등)이면 보관 정책을 그쪽에도 맞춘다.
- 예상하지 못한 오류(500)의 스택은 로그에만 남고, 응답은 `INTERNAL_ERROR` / `"Internal error"`만 준다.
  prod는 `server.error.include-stacktrace=never`, `include-message=never`.
- 로그에 이메일·비밀번호·토큰을 남기지 않는다(회원은 ID로만 적는다).

## 4. 이미지 백업 (`blog.media.upload-dir`)

| 디렉터리 | 내용 | 백업 |
|---|---|---|
| `upload-dir` | 글·프로필·블로그 대표 이미지 원본(ATTACHED·ORPHANED). `yyyy/MM/{uuid}.{ext}`. 한번 쓰면 바뀌지 않는다 | **매일** |
| `thumbnail-dir` | 썸네일. 첫 요청 때 원본에서 다시 만든다 | 제외 |
| `temp-dir` | 업로드 후 아직 글에 등록되지 않은 파일(24시간 뒤 정리) | 제외 |

이미지 행(`media.stored_path`)은 DB에 있으므로 **DB 백업과 같은 날 함께** 받는다. 파일이 DB보다 조금 많은 쪽은 괜찮고
(정리 작업이 지움), DB에만 있고 파일이 없으면 그 이미지는 404가 된다. 그래서 순서는 DB 덤프 → upload-dir 복사다.

예(운영 서버 crontab, 매일 02:15. 정리 작업(매시 정각)·휴지통 비우기(03:30)와 겹치지 않는 시각):

```sh
# 1) DB: 잠금 없이 일관된 덤프(InnoDB). 접속 정보는 ~/.my.cnf(권한 600)에 둔다.
mysqldump --single-transaction --routines --triggers "$DB_NAME" | gzip > "/backup/db/blog-$(date -u +%F).sql.gz"

# 2) 이미지 원본: 날짜별 스냅숏. 바뀌지 않는 파일은 하드 링크로 공유해 공간을 아낀다.
TODAY=$(date -u +%F); PREV=$(ls -1d /backup/media/20* 2>/dev/null | tail -n 1)
rsync -a --delete ${PREV:+--link-dest="$PREV"} "$BLOG_DATA_DIR/media/upload"/ "/backup/media/$TODAY"/

# 3) 보관: 30일 지난 백업 삭제
find /backup/db -name 'blog-*.sql.gz' -mtime +30 -delete
find /backup/media -mindepth 1 -maxdepth 1 -type d -mtime +30 -exec rm -rf {} +
```

- 백업은 다른 디스크나 다른 호스트로 보낸다(같은 디스크의 사본은 디스크 장애에 쓸모가 없다).
- 복구: 앱을 멈추고 DB를 복원한 뒤 같은 날짜의 스냅숏을 `upload-dir`에 되돌린다(`rsync -a /backup/media/<날짜>/ "$BLOG_DATA_DIR/media/upload"/`).
  `thumbnail-dir`은 비워 두면 요청 때 다시 만들어진다. `temp-dir`은 비워도 된다.
- 한 달에 한 번은 복구 연습을 한다(덤프가 열리는지, 아무 글의 이미지가 보이는지).

## 4.1 블로그 백업 파일 (`blog.export.dir`, 004)

블로그 주인이 요청한 백업 zip(`yyyy/MM/{uuid}.zip`)을 둔다. 앱 실행 계정만 읽고 쓸 수 있게(`chmod 700`) 둔다. 없으면 기동 때
만들고, 쓸 수 없으면 기동을 멈춘다.

- **DB 백업에 포함되지 않고, 7일 뒤 지워지는 임시 파일**이라 서버 일일 백업 대상에서 빼도 된다. 잃어버리면 주인이 다시 요청하면 된다
  (`blog_exports` 행은 DB에 남지만 파일이 없으면 내려받기가 404).
- 디스크: 백업 하나는 그 블로그의 글(Markdown)과 주인 이미지 원본 크기의 합 정도다. 블로그별 하루 한 번, 7일 보관이라
  최악은 (블로그 수 × 7 × 블로그 크기)다. 여유 공간을 모니터링하고, 부족하면 `blog.export.retention`을 줄인다.
- zip에는 그 블로그의 글·임시 저장본·카테고리·주인이 올린 이미지만 들어간다. 비밀번호 해시·이메일·다른 회원의 댓글·방명록은 넣지 않는다.
- 기동 때 `blog.export.stale-running`(1시간) 넘게 RUNNING인 백업은 FAILED(`INTERRUPTED`)로 바꾸고 만들다 만 파일을 지운다.
- 블로그가 휴지통 비우기로 완전히 지워지면 그 블로그의 백업 행과 파일도 함께 지운다.

## 5. 정기 작업

Spring `@Scheduled`(스케줄러 스레드 3개)로 앱 안에서 돈다. cron은 **JVM 기본 시간대** 기준이므로
서버를 UTC로 운영하거나(`TZ=UTC`, 또는 `-Duser.timezone=UTC`) 시간대를 정해 두고 아래 시각을 그 기준으로 읽는다.
각 작업은 `blog.jobs.purge-batch-size`(500)건씩 트랜잭션을 나눠 처리하고 처리 건수를 로그에 남긴다.

| 작업 | 클래스 | cron 프로퍼티 | 기본값 | 하는 일 |
|---|---|---|---|---|
| 이미지 정리 | `MediaCleanupJob` | `blog.media.cleanup-cron` | `0 0 * * * *`(매시 정각) | 24시간 지난 TEMP와 어디서도 쓰지 않는 ORPHANED 이미지의 행·원본·썸네일 삭제 (FR-072·073) |
| 휴지통 비우기 | `TrashPurgeJob` | `blog.jobs.trash-purge-cron` | `0 30 3 * * *`(매일 03:30) | 휴지통 30일 지난 글 영구 삭제, 삭제 30일 지난 블로그의 카테고리 삭제·제목 비우기(주소는 재사용 방지로 남김) (FR-084·159) |
| 개인정보 파기 | `PrivacyPurgeJob` | `blog.jobs.privacy-purge-cron` | `0 0 4 * * *`(매일 04:00) | 탈퇴 30일 지난 회원의 개인정보 파기, 90일 지난 로그인 기록 삭제, 만료된 재설정·리프레시 토큰 삭제 (FR-138·139), `blog.guest.ip-retention`(90일) 지난 비회원 댓글·방명록의 작성 IP 삭제 (004 FR-066), 처리 뒤 `blog.privacy.rights-request-retention`(365일) 지난 권리 침해 신고의 연락 이메일과 `blog.privacy.trackback-ip-retention`(90일) 지난 트랙백 송신 IP 삭제 (005) |
| 알림 정리 | `NotificationPurgeJob` | `blog.jobs.notification-purge-cron` | `0 15 4 * * *`(매일 04:15) | `blog.notifications.retention`(90일) 지난 알림 삭제 (002 FR-033) |
| 예약 발행 | `ScheduledPublishJob` | `blog.jobs.scheduled-publish-delay`(고정 지연) | 30초 | 예약 시각이 지난 SCHEDULED 글을 발행(구독자 알림·피드 반영 포함, 004 FR-064) |
| 백업 생성 | `BlogExportJob` | `blog.jobs.export-poll-delay`(고정 지연) | 30초 | PENDING 백업을 RUNNING으로 바꿔 zip을 만들고 READY(완료 알림) 또는 FAILED로 둔다 (004 FR-145) |
| 백업 정리 | `BlogExportCleanupJob` | `blog.jobs.export-cleanup-cron` | `0 10 * * * *`(매시 10분) | 만료(`blog.export.retention`, 7일)된 READY 백업의 파일을 지우고 EXPIRED로 바꾼다 |
| 글 통계 정리 | `PostStatsPurgeJob` | `blog.jobs.post-stats-purge-cron` | `0 45 4 * * *`(매일 04:45) | `blog.posts.stats-retention`(90일) 지난 `post_daily_stats` 행 삭제 (003 research P4) |
| 작업 기록 정리 | `AdminAuditPurgeJob` | `blog.jobs.audit-purge-cron` | `0 15 5 * * *`(매일 05:15) | `blog.admin.audit-retention`(365일) 지난 `admin_audit_logs` 행 삭제 (006 FR-106). 작업 기록을 지우는 유일한 경로 |
| 외부 피드 수집 | `FeedFetchScheduler` | `blog.external.poll-interval`(고정 지연) | 1분 | 수집할 때가 된 외부 블로그를 골라 임대하고 `feedFetchExecutor`(스레드 `blog.external.fetch-threads`)에 넘긴다 (007, 13절) |
| 외부 글 링크 확인 | `LinkCheckJob` | `blog.external.link-check-cron` | `0 30 4 * * MON`(월요일 04:30) | 노출 중인 외부 글 원문 주소를 `link-check-batch`(500)건씩 확인해 사라진 글을 내린다 (007) |
| 외부 블로그 정리 | `ExternalCleanupJob` | `blog.external.cleanup-cron` | `0 30 5 * * *`(매일 05:30) | 만료 7일 지난 인증 코드, 해제된 등록의 `REMOVED` 글 중 `release-retention`(30일) 지난 것, 90일 지난 일별 클릭 삭제. 월요일 실행분은 `thumbnail-dir/external/` 아래 DB에 없는 파일도 지운다 (007, 13.5절) |

포털 인기 점수·주제별 글 수는 정기 작업이 아니라 요청 때 계산해 `blog.portal.cache-ttl`(5분) 동안 메모리에 둔다(서버를 여러 대 두면
서버마다 따로 계산한다).

cron 형식은 Spring 6자리(초 분 시 일 월 요일)다. 작업을 잠시 멈추려면 cron을 `-`로 준다(예: `BLOG_JOBS_TRASHPURGECRON=-`).

## 5.1 검색(MySQL FULLTEXT, 002)

글 검색은 `post`의 FULLTEXT(ngram) 색인을 쓴다. 배포 전에 MySQL에서 다음을 확인한다.

- `SHOW VARIABLES LIKE 'ngram_token_size';` 값이 **2**여야 한다(`blog.search.min-term-length`와 같게).
  다르면 `my.cnf`에 `ngram_token_size=2`를 넣고 재시작한 뒤 FULLTEXT 색인을 다시 만든다(`ALTER TABLE … DROP INDEX …, ADD FULLTEXT …`).
- InnoDB 기본 불용어 목록(`a`, `i`, `the` 등)이 켜져 있으면 ngram 토큰 중 불용어를 **포함한** 토큰이 색인에서 빠진다.
  그래서 `java`처럼 `a`가 들어간 낱말은 검색되지 않는다(로컬 MySQL 8.4에서 확인). 이를 피하려면
  `innodb_ft_enable_stopword=OFF`(또는 빈 사용자 불용어 표 `innodb_ft_server_stopword_table`)로 설정하고 FULLTEXT 색인을 다시 만든다.
  스키마 변경이 아니라 서버 설정이다.

## 5.2 포털(003)

### 설정의 우선순위와 반영 시간

포털 노출 조건·인기 점수 가중치는 다음 순서로 정한다.

1. 관리자 콘솔 `/admin/portal/settings`에서 저장한 값(`system_settings` 표, 키 `portal.*`)
2. 없으면(콘솔에서 "기본값으로") `application.yml`·환경 변수의 `blog.portal.*` 프로퍼티(2.3절)

- 관리자 콘솔의 변경(포털 제외·추천·주제·운영 설정·릴리스 노트)은 커밋 직후 포털 캐시 전체를 비워 **다음 요청부터** 반영된다.
- 글 발행·비공개·삭제, 회원 정지, 블로그의 "포털에 내 글 노출" 끄기는 **대략 `blog.portal.cache-ttl`(5분) 안에** 반영된다(FR-090). 정확히는 TTL이 지난 뒤 시작한 뒤 갱신이 끝날 때다(아래 "포털 캐시 갱신 방식").
- 서버를 여러 대 두면 캐시 비우기는 변경을 받은 서버에만 즉시 적용되고 나머지는 5분 안에 반영된다.

### 포털 캐시 갱신 방식(방문자가 다시 계산을 기다리지 않게)

글이 많으면(10만 편 측정, T130) 빈 캐시에서 `/api/v1/portal`을 처음 계산하는 데 15초, 주제 페이지는 4초 안팎이 걸린다.
색인 보강은 승인 대기 중이고, 그 전까지 `PortalCache`·`PortalCacheWarmer`가 방문자를 그 계산에서 떼어 놓는다.

- **묵은 값 먼저**: 항목이 `cache-ttl`(5분)보다 오래되면 묵은 값을 바로 주고, 그 키의 갱신을 전용 풀(`portal-cache-*`, 2개)에서
  한 번만 돌린다. 갱신이 끝나면 다음 요청부터 새 값이다. 글 상태 변화는 TTL 뒤 첫 요청이 시작한 갱신이 끝날 때 반영된다.
  갱신이 실패하면(로그 `Portal cache refresh failed`) 묵은 값을 계속 주고 다음 요청이 다시 시도한다.
- **미리 채우기**: 기동 직후와 그 뒤 TTL마다 메인 묶음·인기 점수·최신 글 첫 묶음·주제 트리를 채우거나 갱신한다. 그래서 첫 방문자도
  메인에서 기다리지 않는다(기동 직후 수십 초 안에 온 요청은 진행 중인 계산을 함께 기다린다).
- **한 번만 계산**: 비어 있는 키에 동시에 요청이 몰리면 한 요청만 계산하고 나머지는 그 결과를 함께 받는다.
- **오래 안 찾은 항목**: TTL의 12배(기본 1시간) 동안 갱신되지 않은 항목(찾는 사람이 없던 주제 페이지 등)은 버리고, 다음 요청이
  처음부터 계산한다. 주제 페이지는 키가 많아 미리 채우지 않는다.
- **관리자 변경**: 캐시를 모두 비운 뒤 곧바로 미리 채우기를 시작한다(바로 반영이 우선, FR-094). 큰 데이터에서는 그 사이
  메인 요청이 진행 중인 계산을 기다릴 수 있다(동시 요청이 몰려도 계산은 한 번).
- `BLOG_PORTAL_CACHE_TTL=0s`(시험용)면 캐시·갱신 풀·미리 채우기 모두 쓰지 않고 요청마다 계산한다.

### 주제 seed와 티스토리 목록 대조

초기 주제는 `src/main/resources/portal/topics-seed.json`에 있고 `TopicSeeder`가 기동 때 **없는 slug만** 넣는다(이미 있는 주제의 이름·순서·
숨김·고정은 덮어쓰지 않는다). 출시 전 티스토리 현행 주제 목록과 맞추는 절차:

1. 티스토리 주제 목록(대분류·소분류 이름)을 seed 파일과 나란히 놓고 빠진 주제·이름 차이를 표로 만든다.
2. 아직 배포 전이면 seed 파일만 고친다(slug는 소문자·숫자·`-`, 2~40자, 4개 언어 이름 모두 필수). 형식이 틀리면 기동이 멈추므로
   로컬에서 `./mvnw test -Dtest=TopicSeederTest`로 먼저 확인한다.
3. 이미 배포한 뒤라면 이름·순서·숨김은 관리자 콘솔 `/admin/topics`에서 고친다(작업 기록이 남는다). 새 주제는 콘솔에서 추가하거나
   seed에 더하고 다시 배포한다. slug는 주소(`/topics/{slug}`)라 바꾸지 않는다.

### 블로그 처음 발행 시각 보정(003 배포 때 한 번)

포털 "새 블로그" 영역은 `blogs.first_published_at`을 쓴다. 003 이전에 발행한 블로그는 이 값이 비어 있으므로 003 배포 때
`blog-docs/db/migrations/`의 보정 SQL(승인된 파일)을 한 번 실행한다. 실행 전에는 그 블로그들이 "새 블로그"에 나오지 않을 뿐 다른 기능에는
영향이 없다.

## 6. 첫 최고 관리자(SUPER_ADMIN) 지정

관리 화면에서 관리자를 지정할 수 있는 사람이 아직 없을 때 쓰는 유일한 방법이다(006 FR-105, `SuperAdminBootstrap`).

1. 관리자로 쓸 사람이 서비스에 일반 회원으로 가입한다(정상 상태 회원이어야 한다).
2. 운영 서버 환경 변수에 `BLOG_ADMIN_BOOTSTRAP_SUPER_ADMIN_EMAIL=<그 회원의 가입 이메일>`을 넣고 앱을 다시 띄운다.
3. 기동 때 SUPER_ADMIN이 한 명도 없으면 그 이메일의 회원을 SUPER_ADMIN으로 바꾸고 로그에
   `Bootstrap: user {id} is now SUPER_ADMIN`을 남긴다(이메일은 로그에 남기지 않는다).
   그런 회원이 없거나 정지·탈퇴 상태면 `Bootstrap: no active member ...` 경고만 남기고 아무것도 바꾸지 않는다.
4. 확인 뒤 환경 변수를 지운다. 남겨 둬도 SUPER_ADMIN이 이미 있으면 아무것도 하지 않지만, 이메일을 설정에 오래 두지 않는다.

권한은 요청마다 DB에서 다시 읽으므로, 지정된 회원은 다시 로그인하지 않아도 다음 요청부터 관리자 API를 쓸 수 있다
(화면 메뉴는 접근 토큰의 role 힌트를 쓰므로 30분 안에 갱신된다).

## 7. 관리자 권한 부여·회수(006)

첫 최고 관리자(6절) 다음부터는 관리 콘솔 `/admin/admins`에서 최고 관리자(SUPER_ADMIN)가 권한을 바꾼다(`PUT /api/v1/admin/users/{id}/role`).

- 일반 관리자(ADMIN)는 목록을 볼 수만 있다. 권한 변경 요청은 403 `FORBIDDEN`.
- 자기 권한은 바꿀 수 없다(422 `CANNOT_CHANGE_OWN_ROLE`). 다른 최고 관리자가 바꿔야 하므로 **최고 관리자를 2명 이상 두기를 권장**한다.
- 마지막 활성 최고 관리자는 낮출 수 없다(409 `LAST_SUPER_ADMIN`). 판단은 활성 최고 관리자 행을 잠근(`SELECT ... FOR UPDATE`) 트랜잭션 안에서
  하므로 두 최고 관리자가 서로를 동시에 낮춰도 한 명은 남는다.
- 정지·탈퇴 회원에게 관리자 이상을 줄 수 없다(409 `USER_NOT_ACTIVE`). 회수는 상태와 상관없이 된다.
- 반영은 다음 관리자 API 요청부터다(요청마다 DB 권한 확인). 회수된 사람의 관리자 API는 바로 404가 된다.
- 바꿀 때마다 작업 기록에 `ROLE_GRANT`·`ROLE_REVOKE`(전후 `role`)가 남는다. 같은 값이면 기록하지 않는다.

모든 최고 관리자를 잃었다면(예: 탈퇴) 6절의 부트스트랩 환경 변수로 다시 지정한다(SUPER_ADMIN이 한 명도 없을 때만 동작).

## 8. 작업 기록(006)

- 관리자 변경 API는 모두 같은 트랜잭션에서 `admin_audit_logs`에 남는다(변경 전후 값은 바뀐 필드만, 개인정보 평문 없음). 수정·삭제 API는 없다.
- 보관은 `blog.admin.audit-retention`(365일). 지난 행은 `AdminAuditPurgeJob`(5절)만 지운다.
- 요청 IP는 암호화해 저장하고, 콘솔 상세 화면에서 **최고 관리자에게만** 복호화해 보여 준다(일반 관리자는 비어 있음).
- 조회 기간은 최대 366일이다. 인덱스(`created_at`, `(admin_id, created_at)`, `(action, created_at)`, `(target_type, target_id)`)에 맞춘 조건만 쓴다.

## 9. 관리 콘솔 대시보드 계산 비용(006)

대시보드는 요청한 관리자 시간대의 오늘·7일 추이를 쿼리 6번으로 계산하고 `blog.admin.dashboard-cache-ttl`(5분) 동안 시간대별로 캐시한다.
`users.created_at`, `posts.published_at`, `comments.created_at`에는 아직 인덱스가 없어(006 data-model "선택 인덱스" 4개
`idx_users_created`·`idx_posts_published_at`·`idx_comments_status_created`·`idx_guestbook_entries_status_created`, 승인 대기)
7일 범위 조건이 표 전체를 훑는다. 회원·글이 수십만 건을 넘어 대시보드가 느려지면 그 인덱스 추가를 검토한다.
캐시 TTL을 줄이면 계산이 그만큼 자주 돈다(0s는 E2E·수동 검증용).

## 10. 스팸 방어(005)

### 10.1 CAPTCHA(Cloudflare Turnstile) 키 발급

**1.0에서는 CAPTCHA를 끈다(marco 2026-10-07).** prod의 `blog.captcha.provider`가 `none`이라 위젯이 나오지 않고 검증도 하지 않는다. 그동안 스팸은 속도 제한·반복 스팸·금칙어(10.2절 이후)로 막는다. 켤 때는 아래 키를 넣고 `application-prod.yml`의 provider를 `turnstile`로 바꾼 뒤, front 환경 변수 `BLOG_CAPTCHA_PROVIDER=turnstile`(CSP에 Turnstile 출처 추가)도 넣는다.

1. Cloudflare 대시보드 → Turnstile → "Add widget". 도메인에 `blog.java21.net`(로컬 확인이 필요하면 `localhost`도)을 넣고
   위젯 모드는 "Managed"로 둔다.
2. 발급된 **Site Key**를 `BLOG_CAPTCHA_SITE_KEY`, **Secret Key**를 `BLOG_CAPTCHA_SECRET_KEY`로 운영 서버 환경 변수에 넣는다(2.1절).
   secret은 저장소·로그에 남기지 않는다. front는 `GET /api/v1/captcha/config`(1시간 캐시)로 사이트 키를 받으므로 front 설정은 없다.
3. 키를 바꾸면 backend만 다시 띄운다. 이미 열린 화면은 길게는 1시간 동안 옛 사이트 키를 쓸 수 있다.

CAPTCHA가 걸리는 곳: 가입, 비회원 댓글·방명록 쓰기, 권리 침해 신고, 로그인 반복 실패 뒤 재시도(2.3절 `login-failures-before-captcha`).
Turnstile 장애·시간 초과는 실패로 본다(사람도 잠시 가입·비회원 쓰기를 못 한다). 장애가 길면 Cloudflare 상태를 확인한다.

### 10.2 운영 설정 키(관리 콘솔 "스팸 방어 설정", `/admin/spam`)

운영 설정(`site_settings`)에 값이 있으면 프로퍼티 기본값보다 우선하고, 저장하면 바로 적용된다(재기동 없음). "기본값으로"는 행을 지워
프로퍼티 값으로 돌아간다. 바꿀 때마다 작업 기록에 `SETTING_CHANGE`가 남는다. **관리자는 작성 속도 한도와 반복 기준을 적용받지 않는다.**

| 키 | 값 | 범위 | 기본값(프로퍼티) | 단위·기준 |
|---|---|---|---|---|
| `ratelimit.post-publish-per-hour` | 정수 | 1~10000 | 10 | 회원 한 명이 1시간에 처음 발행·예약하는 글 수(여러 블로그 합계) |
| `ratelimit.comment-per-minute` | 정수 | 1~10000 | 5 | 회원은 회원별, 비회원은 IP별 1분 댓글 수 |
| `ratelimit.guestbook-per-minute` | 정수 | 1~10000 | 3 | 회원은 회원별, 비회원은 IP별 1분 방명록 글 수 |
| `ratelimit.media-upload-per-minute` | 정수 | 1~10000 | 30 | 회원별 1분 이미지 업로드 수 |
| `ratelimit.signup-per-ip-per-hour` | 정수 | 1~100000 | 5 | IP별 1시간 가입 수 |
| `spam.duplicate-comment` | `{ windowMinutes, maxCount }` | 1~1440 / 2~100 | 10 / 3 | 같은 작성자(회원 또는 비회원 IP)가 `windowMinutes`분 안에 같은 내용(NFKC·소문자·공백 정규화)을 `maxCount`번 쓴 뒤 다음 댓글·방명록은 422 `DUPLICATE_CONTENT_SPAM`. 댓글·방명록, 글을 가리지 않고 센다 |

한도는 서버 메모리(Caffeine 고정 창) 카운터라 재기동하면 비고, 서버를 여러 대 두면 서버마다 따로 센다(backend 1대 전제, 001 R26).
같은 공유기·회사망처럼 IP 하나를 여럿이 쓰는 곳에서 가입이 막힌다는 문의가 오면 `ratelimit.signup-per-ip-per-hour`를 올린다.

### 10.3 금칙어

같은 화면에서 관리한다. 범위는 이름류(`NAME`: 닉네임·블로그 주소·블로그 제목), 본문류(`CONTENT`: 댓글·방명록), 둘 다(`ALL`),
처리는 거부(`REJECT`, 400 `BANNED_WORD`) 또는 가림(`MASK`, 같은 길이 `*`로 저장). 이름류는 거부만 된다. 단어는 NFKC·소문자로 맞춰
비교하고 이미 저장된 글은 바꾸지 않는다. 추가·변경·삭제는 바로 적용되고 작업 기록(`BANNED_WORD_*`)이 남는다.

## 11. 트랙백(005)

### 11.1 보내기 스레드 풀과 재기동 복구

- 발행(예약 발행 포함)·발행된 글 수정 때 보낼 주소마다 `trackback_ping_logs`에 PENDING 행을 만들고, 커밋 뒤 전용 스레드 풀
  (`blog.trackback.executor-threads` 2개, 대기열 `executor-queue` 100)이 보낸다. 대기열이 차면 그 요청은 FAILED `REMOTE_ERROR`
  "Queue full"로 남고 다시 보내지 않는다(글쓴이가 발행 설정에서 다시 보낼 수 있다).
- 보내는 중 재기동되면 PENDING이 남는다. 기동 때(`PendingPingRecovery`) `recover-pending-after`(5분)보다 오래된 PENDING을 다시
  대기열에 넣는다. 글쓴이는 관리 글 목록·발행 설정의 "트랙백 결과"에서 상태를 본다.
- 받는 쪽 응답이 `<error>1</error>`이거나 XML이 아니면 FAILED `REMOTE_ERROR`(상대 메시지), 2xx가 아니거나 연결 실패면 `HTTP_ERROR`,
  시간 초과는 `TIMEOUT`, 내부망·허용하지 않는 포트는 `BLOCKED_ADDRESS`, 형식·이름 해석 실패는 `INVALID_URL`. 리다이렉트는 따르지
  않는다(3xx도 `HTTP_ERROR`). 응답은 64KB까지만 읽는다.

### 11.2 내부망 차단과 남은 위험

`OutboundUrlGuard`가 http/https와 허용 포트만 통과시키고, 호스트 이름이 가리키는 **모든** 주소가 공인 주소일 때만 보낸다(루프백·사설·
링크 로컬·CGNAT·멀티캐스트·IPv6 ULA 등 차단). 다만 JDK `HttpClient`는 검사한 주소로 연결을 고정할 수 없어, 검사와 연결 사이에 DNS 응답을
바꾸는 **DNS 재바인딩**은 막지 못한다. 서버에서 다음을 함께 둔다.

- 방화벽(nftables·보안 그룹)에서 앱 실행 계정의 나가는 연결 중 사설 대역(10/8, 172.16/12, 192.168/16, 169.254/16, 100.64/10, 127/8,
  IPv6 fc00::/7·fe80::/10)과 DB·관리 포트를 막는다. 메타데이터 주소 169.254.169.254는 꼭 막는다.
- nginx를 앞에 둔다면 트랙백 받기 경로(`POST /{handle}/{postId}/trackback`)에 요청 크기 제한(`client_max_body_size 256k`)과
  IP별 속도 제한(`limit_req`)을 둔다. 앱도 같은 IP의 받기를 `blog.trackback.receive-limit`으로 막는다.
- `blog.outbound.allow-private`는 시험용이다. prod 프로필에서 true면 기동을 멈춘다.

### 11.3 받기

블로그 설정 "트랙백 받기"가 꺼졌거나 본문을 볼 수 없는 글(비공개·보호·숨김 등)은 `Trackback is not allowed`로 거절한다. 같은 글에
같은 출처 주소(정규화 뒤 SHA-256)는 한 번만 받는다(주인이 지운 뒤에도 다시 받지 않음). 송신 IP는 암호화해 저장하고 어떤 응답에도
나오지 않으며, `blog.privacy.trackback-ip-retention`(90일) 뒤 파기된다.

## 12. 신고 처리·숨김·정지(005)

1. 관리 콘솔 `/admin/reports`에서 처리 대기 신고를 대상별로 본다(메뉴의 배지는 처리 대기 수). 권리 침해 신고는 상세에서 연락 이메일과
   권리 근거를 보고, 대상 콘텐츠를 지정할 수 있다(지정도 작업 기록 `REPORT_TARGET_ASSIGN`에 남는다). 콘솔 대시보드의 "처리 대기 신고"
   카드는 메뉴 배지와 같은 수다.
2. 처리(신고 상세의 "처리"): 조치는 "콘텐츠 숨김"(대상 글·댓글·방명록 글·트랙백을 HIDDEN으로) 또는 "작성자 정지", 아니면 "기각".
   같은 대상의 처리 대기 신고가 한 번에 ACTIONED·DISMISSED로 닫히고, 회원 신고자에게는 결과 알림이, 권리 침해 신고는 연락 이메일로 결과
   메일이 간다. 숨긴 글은 주인에게만 숨김 안내와 함께 보이고, 숨긴 댓글·방명록은 작성 회원에게만 보인다.
3. 신고 없이 숨기거나 해제할 때는 콘솔 "콘텐츠 관리"(`/admin/contents/{posts|comments|guestbook}`)의 행마다 있는 "숨김"(사유 필수)·"숨김 해제"를
   쓴다(API `PUT`·`DELETE /api/v1/admin/contents/{posts|comments|guestbook-entries|trackbacks}/{id}/hidden`). 이 경로는 신고를 닫지 않는다.
   숨긴 글·댓글·방명록은 각 탭의 상태 "숨김"(`?status=HIDDEN`)으로 찾아 해제하고(옛 주소 `/admin/contents/hidden-posts`는 글 탭의 숨김 목록으로
   이동), 해제하면 숨기기 전 상태로 돌아간다.
4. 회원 정지는 `/admin/users/{id}`에서 사유와 함께 한다. 정지 즉시 그 회원의 리프레시 토큰이 모두 폐기되고, 이미 발급된 접근
   토큰도 다음 요청부터 인증되지 않는다(정지 목록은 서버 메모리, 1대 전제). 모든 블로그는 "이용이 제한된 블로그"(`BLOG_RESTRICTED`)로 안내되며 글은 목록·피드·포털·검색에서 빠진다.
   해제하면 그대로 돌아온다. 자기 자신과 마지막 최고 관리자는 정지할 수 없다.
5. 숨김·해제·정지·해제·신고 처리는 `/admin/audit-log`에서 `CONTENT_HIDE`·`CONTENT_UNHIDE`·`USER_SUSPEND`·
   `USER_UNSUSPEND`·`REPORT_ACTION`·`REPORT_DISMISS`로 확인한다(8절).

## 13. 외부 블로그(007)

### 13.1 프로퍼티(`blog.external.*`)

`fetch-interval`·`auto-classify-min-confidence`·`score-weight`는 운영 설정(콘솔 "외부 블로그 관리 › 설정", 키 `external.*`)에 값이
있으면 그 값이 먼저다. 나머지는 프로퍼티로만 바꾼다(환경 변수 이름 규칙은 2.3절, 예: `BLOG_EXTERNAL_FETCHINTERVAL`).

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `fetch-threads` / `batch-size` / `poll-interval` / `lease-time` | 4 / 50 / 1m / PT10M | 수집 스레드 수, 한 번에 고르는 등록 수, 고르는 주기, 고른 등록을 다른 실행이 다시 고르지 않는 시간 |
| `fetch-interval` / `fetch-jitter` | PT30M / PT5M | 등록별 수집 주기와 흩뿌림(같은 시각에 몰리지 않게). 운영 설정 `external.fetch-interval` 우선 |
| `connect-timeout` / `request-timeout` / `max-redirects` | 5s / 10s / 3 | 피드·블로그 첫 화면·이미지 요청 제한. 넘으면 그 회차는 `TIMEOUT` |
| `max-feed-size` / `max-page-size` / `max-image-size` | 2MB / 1MB / 5MB | 응답 크기 한도. 넘으면 읽기를 멈추고 `TOO_LARGE` |
| `initial-window` / `max-items-per-fetch` | P30D / 100 | 첫 수집에서 받는 기간과 한 회차 최대 글 수 |
| `max-backoff` / `stop-after` | PT12H / P7D | 실패가 이어지면 수집 주기를 두 배씩 늘리는 상한, 첫 실패부터 이 기간이 지나면 자동 중지(`STOPPED`, 회원에게 알림) |
| `auto-classify-min-confidence` / `score-weight` | 0.7 / 1.0 | 자동 분류를 그대로 쓰는 신뢰도(미만이면 검수 대기), 포털 인기 점수에서 외부 글 가중치 |
| `member-limit` / `preview-per-hour` / `verify-checks-per-hour` | 3 / 20 / 10 | 회원별 외부 블로그 수(거절·해제 제외), 미리보기·인증 확인 1시간 한도 |
| `verification-ttl` / `click-dedupe-window` / `release-retention` | PT24H / PT30M / P30D | 인증 코드 유효 시간, 같은 방문자의 클릭 중복 제거 시간, 해제된 등록의 `REMOVED` 글 보관 기간 |
| `link-check-cron` / `link-check-batch` / `cleanup-cron` | 월 04:30 / 500 / 매일 05:30 | 5절 정기 작업 |
| `forbidden-hosts` | `blog.java21.net` | 외부 블로그로 등록할 수 없는 호스트(하위 도메인 포함). `blog.base-url`의 호스트는 늘 포함 |
| `user-agent` | `java21-blog-feed/1.0 (+{base-url}/updates)` | 외부 요청의 User-Agent |

### 13.2 수집 주기, 자동 중지와 재개

- 승인하면 바로 한 번 수집하고 그 뒤 `fetch-interval` ± `fetch-jitter`마다 읽는다. `ETag`·`Last-Modified`가 있으면 조건부 요청으로
  바뀐 것이 없을 때 본문을 받지 않는다(`NOT_MODIFIED`).
- 실패(`TIMEOUT`·`HTTP_ERROR`·`PARSE_ERROR`·`TOO_LARGE`·`DNS_ERROR`·`BLOCKED_ADDRESS`)가 이어지면 다음 수집까지 간격을 두 배씩 늘린다
  (`max-backoff`까지). 첫 실패부터 `stop-after`(7일)가 지나면 `STOPPED`가 되고 회원에게 "수집을 멈췄습니다" 알림이 간다. 이미 수집된 글은
  포털에 남는다.
- 회원은 스스로 재개하지 않는다. 피드가 다시 열린 것을 확인했다는 문의를 받으면 콘솔 `/admin/external-blogs/{id}`에서 "재개"를 누른다
  (실패 수·첫 실패 시각 초기화, 바로 수집). 운영자가 잠시 멈출 때는 "일시 중지"(`PAUSED`, 글 유지), 되돌릴 수 없는 정리는 "차단"
  (`BLOCKED`, 그 등록과 같은 피드의 해제된 등록에 남은 글까지 모두 포털에서 내림, 같은 피드는 다시 신청할 수 없음).
- 회원이 해제할 때는 "남기기"(글은 기한 없이 포털에 남고 새 글은 가져오지 않음, 같은 피드를 다시 등록하면 남긴 글이 새 등록으로 옮겨 감)
  또는 "삭제"(바로 삭제, 되돌릴 수 없음)를 반드시 고른다. 회원이 탈퇴하면 그 회원의 등록은 해제되고 남긴 글까지 포털에서 내려간다.
- 일시 중지·재개·차단·외부 글 내림·외부 글 포털 제외·해제는 작업 기록(`EXTERNAL_BLOG_PAUSE`·`EXTERNAL_BLOG_RESUME`·
  `EXTERNAL_BLOG_BLOCK`·`EXTERNAL_POST_REMOVE`·`PORTAL_EXCLUDE`·`PORTAL_UNEXCLUDE`, 대상 `EXTERNAL_POST`)에 남는다.
- 신고: 포털 외부 카드의 "삭제 요청"은 회원이면 신고(`EXTERNAL_POST`), 비회원이면 권리 침해 신고로 간다. 신고 상세의 "포털에서 내림"은
  그 글을 `REMOVED`(`REPORT`)로, "외부 블로그 차단"은 등록을 차단한다(12절과 같은 처리 흐름).

### 13.3 외부 요청 보안

모든 외부 요청(미리보기·인증 확인·피드·블로그 첫 화면·이미지)은 `SafeHttpFetcher` 한 곳을 지나며 11.2절의 `OutboundUrlGuard` 규칙
(http/https, 허용 포트, `user@` 금지, 해석한 **모든** 주소가 공인 주소)과 우리 서비스 호스트(`blog.base-url`·`forbidden-hosts`, 하위
도메인 포함) 거부를 따른다. 리다이렉트마다 다시 검사하고 `max-redirects`를 넘으면 멈춘다. 응답 크기와 시간도 13.1절 한도로 자른다.

DNS 재바인딩(검사한 뒤 다른 주소로 연결)은 앱만으로 완전히 막지 못한다. **앱 실행 계정의 나가는 연결에서 사설 대역과 메타데이터 주소를
방화벽으로 막는다**(11.2절 목록 그대로, 169.254.169.254 필수). `blog.outbound.allow-private`는 시험 전용(루프백 피드 스텁)이며 prod
프로필에서 true면 기동이 멈춘다.

### 13.4 썸네일(`{blog.media.thumbnail-dir}/external/`)

소유 인증된 외부 블로그 글의 대표 이미지만 600×400으로 줄여 `thumbnail-dir/external/{키 앞 2자}/{키}.{jpg|png}`에 둔다. 원본은 저장하지
않는다. 이 디렉터리는 **백업하지 않는다**(4절 백업은 `upload-dir`만 대상이고, `thumbnail-dir`을 따로 백업한다면 `--exclude external/`).
잃어도 다음 수집에서 다시 받거나 썸네일 없이 주제 색 카드로 보인다. 지운 글의 파일은 커밋 뒤 지우고, 놓친 파일은 월요일 정리 작업이 지운다.

### 13.5 키워드 사전 고치기

자동 분류 사전은 `src/main/resources/external/topic-keywords.yml`이다. 고칠 때는

1. 소분류 slug별 낱말을 고치고(한 낱말은 한 주제에만, 한글·가나·한자는 두 글자 이상) 파일 맨 위 `version`을 올린다(예: `keyword-v2`).
   새 버전은 이후 분류되는 글의 `classifier_version`에 남아 분류 현황에서 버전별 정확도를 비교할 수 있다.
2. `KeywordDictionaryTest`(중복·형식)와 분류 시험이 통과하는지 보고 PR로 낸다. 배포해야 반영된다(이미 분류된 글은 다시 분류하지 않는다).

운영 중 특정 낱말을 바로 바꾸려면 사전 대신 콘솔 "매핑 규칙"을 쓴다(그 뒤 수집되는 글에만 적용).

### 13.6 분류 현황 비용과 선택 인덱스

콘솔 "분류 현황"(`GET /admin/classification-stats`)은 최근 기간의 검수·외부 글을 모아 계산하고 5분 동안 메모리에 둔다(검수 확정이나 주인의
주제 변경이 있으면 바로 다시 계산). 선택 인덱스 `idx_external_posts_topic_decided`는 1.0 스키마에 **넣지 않았다**. 외부 글이 수십만 건을
넘어 현황 계산이 느려지면(느린 쿼리 로그로 확인) DBA가 그때 추가를 검토한다.

