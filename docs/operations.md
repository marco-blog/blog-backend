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
| `BLOG_MEDIA_UPLOAD_DIR` | `blog.media.upload-dir` | | 이미지 정식 보관 디렉터리. **백업 대상**(4절) |
| `BLOG_MEDIA_TEMP_DIR` | `blog.media.temp-dir` | | 업로드 직후 임시 보관 디렉터리. 백업 제외 |
| `BLOG_MEDIA_THUMBNAIL_DIR` | `blog.media.thumbnail-dir` | | 썸네일 디렉터리. 다시 만들 수 있어 백업 제외 |

세 이미지 디렉터리는 앱 실행 계정이 쓸 수 있어야 한다. 없으면 기동 때 만들고, 쓸 수 없으면 기동을 멈춘다.
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
| `blog.release-notes.portal-card-days` | 14d | 최신 릴리스 노트를 포털 메인 카드로 보여주는 기간(처음 게시부터, 003 FR-162) |

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
rsync -a --delete ${PREV:+--link-dest="$PREV"} "$BLOG_MEDIA_UPLOAD_DIR"/ "/backup/media/$TODAY"/

# 3) 보관: 30일 지난 백업 삭제
find /backup/db -name 'blog-*.sql.gz' -mtime +30 -delete
find /backup/media -mindepth 1 -maxdepth 1 -type d -mtime +30 -exec rm -rf {} +
```

- 백업은 다른 디스크나 다른 호스트로 보낸다(같은 디스크의 사본은 디스크 장애에 쓸모가 없다).
- 복구: 앱을 멈추고 DB를 복원한 뒤 같은 날짜의 스냅숏을 `upload-dir`에 되돌린다(`rsync -a /backup/media/<날짜>/ "$BLOG_MEDIA_UPLOAD_DIR"/`).
  `thumbnail-dir`은 비워 두면 요청 때 다시 만들어진다. `temp-dir`은 비워도 된다.
- 한 달에 한 번은 복구 연습을 한다(덤프가 열리는지, 아무 글의 이미지가 보이는지).

## 5. 정기 작업

Spring `@Scheduled`(스케줄러 스레드 3개)로 앱 안에서 돈다. cron은 **JVM 기본 시간대** 기준이므로
서버를 UTC로 운영하거나(`TZ=UTC`, 또는 `-Duser.timezone=UTC`) 시간대를 정해 두고 아래 시각을 그 기준으로 읽는다.
각 작업은 `blog.jobs.purge-batch-size`(500)건씩 트랜잭션을 나눠 처리하고 처리 건수를 로그에 남긴다.

| 작업 | 클래스 | cron 프로퍼티 | 기본값 | 하는 일 |
|---|---|---|---|---|
| 이미지 정리 | `MediaCleanupJob` | `blog.media.cleanup-cron` | `0 0 * * * *`(매시 정각) | 24시간 지난 TEMP와 어디서도 쓰지 않는 ORPHANED 이미지의 행·원본·썸네일 삭제 (FR-072·073) |
| 휴지통 비우기 | `TrashPurgeJob` | `blog.jobs.trash-purge-cron` | `0 30 3 * * *`(매일 03:30) | 휴지통 30일 지난 글 영구 삭제, 삭제 30일 지난 블로그의 카테고리 삭제·제목 비우기(주소는 재사용 방지로 남김) (FR-084·159) |
| 개인정보 파기 | `PrivacyPurgeJob` | `blog.jobs.privacy-purge-cron` | `0 0 4 * * *`(매일 04:00) | 탈퇴 30일 지난 회원의 개인정보 파기, 90일 지난 로그인 기록 삭제, 만료된 재설정·리프레시 토큰 삭제 (FR-138·139) |
| 알림 정리 | `NotificationPurgeJob` | `blog.jobs.notification-purge-cron` | `0 15 4 * * *`(매일 04:15) | `blog.notifications.retention`(90일) 지난 알림 삭제 (002 FR-033) |
| 글 통계 정리 | `PostStatsPurgeJob` | `blog.jobs.post-stats-purge-cron` | `0 45 4 * * *`(매일 04:45) | `blog.posts.stats-retention`(90일) 지난 `post_daily_stats` 행 삭제 (003 research P4) |

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
