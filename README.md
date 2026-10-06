# blog-backend

**한국어** | [English](README.en.md) | [日本語](README.ja.md) | [简体中文](README.zh-CN.md)

[blog.java21.net](https://blog.java21.net)에서 운영하는 멀티 유저 블로그 플랫폼(티스토리와 비슷한 서비스)의 REST API 서버다.
회원 가입·로그인, 회원당 여러 블로그, 글 작성·발행(Markdown), 카테고리·태그, 댓글, 이미지 업로드·썸네일, 4개 언어(ko·en·ja·zh-CN)를 제공한다.
화면은 형제 저장소 [blog-front](https://github.com/marco-blog/blog-front)(React SSR)가 맡고, 스펙은 [blog-docs](https://github.com/marco-blog/blog-docs)에 있다.

## 스택

- Java 21, Maven(래퍼 `./mvnw` 포함), Spring Boot 4.1
- Spring Web MVC, Spring Security, Spring Data JPA + QueryDSL(OpenFeign), Bean Validation, Spring Mail, Actuator
- 인증: JWT 접근 토큰(30분, HS256) + 리프레시 토큰(사용 시 교체, 유휴 4시간·절대 7일), 모두 HttpOnly 쿠키
- DB: MySQL 8.4(FULLTEXT ngram, `--ngram-token-size=2`). 스키마는 Crowfoot ERD 문서가 원천이고 Flyway는 쓰지 않는다(`ddl-auto=validate`)
- 본문: commonmark(GFM 표·취소선)로 HTML을 만들고 OWASP Java HTML Sanitizer로 정리한다(코드 강조는 front SSR)
- 이미지: Thumbnailator, TwelveMonkeys(WebP). 조회수 중복 제거·썸네일 잠금에 Caffeine
- API 문서: springdoc-openapi(`/v3/api-docs`, prod에서는 꺼짐)
- 테스트: JUnit 5, Mockito, Spring 슬라이스 테스트, H2(MySQL 모드), JaCoCo(라인 80% 이상)

## 요구 사항

- JDK 21
- Docker(로컬 MySQL 8.4와 개발용 메일 서버 Mailpit을 띄울 때)
- 세 저장소를 형제 디렉터리로 두면 문서의 상대 경로가 맞는다: `blog/blog-docs`, `blog/blog-backend`, `blog/blog-front`

## 프로필

| 프로필 | 파일 | 용도 |
|---|---|---|
| `local`(기본) | `src/main/resources/application-local.yml` | 개발. 저장소 루트의 `.env`를 읽는다. 프로필을 지정하지 않으면 이 프로필로 뜬다 |
| `prod` | `src/main/resources/application-prod.yml` | 운영. 모든 접속 정보·비밀 값을 환경 변수로만 받는다([운영 문서](docs/operations.md)) |
| `test` | `src/test/resources/application-test.yml` | 테스트 전용(surefire가 켠다). 테스트용 고정 키만 있고 개발 DB는 쓰지 않는다 |

## 로컬 실행

### 1. 비밀 값 준비(`.env`)

```bash
cp .env.example .env
```

`.env`에 다음 값을 채운다. `.env`는 `.gitignore`에 있으며 절대 커밋하지 않는다.

| 이름 | 만드는 법 |
|---|---|
| `DB_PASSWORD` | 개발 DB(Crowfoot `cf_u2_d2`)를 쓸 때만. Crowfoot 화면의 "데이터베이스" 탭에서 확인 |
| `BLOG_CRYPTO_KEY_V1` | `openssl rand -base64 32` (개인정보 암호화 키) |
| `BLOG_CRYPTO_HASH_KEY` | `openssl rand -base64 32` (이메일 검색용 HMAC 키, 한번 정하면 바꾸지 않는다) |
| `BLOG_AUTH_JWT_SECRET` | `openssl rand -base64 48` (접근 토큰 서명 키) |

선택 값(메일, 첫 SUPER_ADMIN 이메일, 이미지 디렉터리 등)은 `.env.example`의 주석을 본다. 같은 이름의 환경 변수를 주면 `.env`보다 우선한다.

### 2. DB 고르기

**A. 개발 DB(Crowfoot `cf_u2_d2`)**: `.env`의 `DB_PASSWORD`만 있으면 된다. 추가 설정 없이 다음 단계로 간다.

**B. 내 MySQL(Docker)**: 빈 DB로는 `ddl-auto=validate`에서 기동에 실패하므로 스키마를 먼저 만든다.
스키마 파일은 [blog-docs `db/schema-mysql.sql`](https://github.com/marco-blog/blog-docs/blob/main/db/schema-mysql.sql)이고, 같은 스냅숏이 이 저장소의 `src/test/resources/db/schema-mysql.sql`에 있다.

```bash
docker run -d --name blog-mysql -p 3306:3306 \
  -e MYSQL_DATABASE=blog -e MYSQL_USER=blog -e MYSQL_PASSWORD=blog -e MYSQL_ROOT_PASSWORD=root \
  mysql:8.4 --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci --ngram-token-size=2
# 몇 초 뒤 서버가 뜨면
docker exec -i blog-mysql mysql -uroot -proot blog < src/test/resources/db/schema-mysql.sql

# local 프로필의 접속 정보를 환경 변수로 덮어쓴다(이 경우 DB_PASSWORD는 쓰지 않는다)
export SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/blog?connectionTimeZone=UTC&characterEncoding=UTF-8'
export SPRING_DATASOURCE_USERNAME=blog SPRING_DATASOURCE_PASSWORD=blog
```

위 비밀번호(`blog`, `root`)는 내 컴퓨터의 일회용 컨테이너용 예시다. 다른 곳에서 쓰지 않는다.

### 3. 메일 서버(Mailpit)

비밀번호 재설정 메일은 local 프로필에서 `localhost:1025`(인증·STARTTLS 없음)로 보낸다.

```bash
docker run -d --name blog-mailpit -p 1025:1025 -p 8025:8025 axllent/mailpit
# 받은 메일: http://localhost:8025
```

### 4. 실행

```bash
./mvnw spring-boot:run          # http://localhost:8080
curl http://localhost:8080/actuator/health
```

- 메일 링크 주소(`blog.base-url`)는 `http://localhost:5173`(front 개발 서버)이다. 바꾸려면 `BLOG_BASE_URL`.
- 상태 변경 요청은 허용 Origin(`http://localhost:5173`, `http://localhost:3000`)에서만 받는다. 화면은 blog-front를 띄워 확인한다.
- 이미지 디렉터리를 정하지 않으면 시스템 임시 디렉터리 아래(`blog-media/upload|temp|thumb`)를 쓴다.
- OpenAPI 문서: `http://localhost:8080/v3/api-docs`
- jar로 실행: `./mvnw -B -DskipTests package` → `java -jar target/blog-backend-*.jar`

## 테스트

```bash
./mvnw verify      # 전체 테스트 + JaCoCo 검사(라인 커버리지 80% 미만이면 실패)
./mvnw test        # 테스트만
```

- 커버리지 보고서: `target/site/jacoco/index.html`
- Controller는 `@WebMvcTest`, Service는 Mockito 단위 테스트, Repository는 H2(MySQL 모드) `@DataJpaTest`로 돈다. 외부 DB가 필요 없다.
- MySQL 전용 동작(FULLTEXT ngram, 행 잠금 동시성)을 확인하는 `@MySqlRepositoryTest`는 아래 환경 변수가 있을 때만 돌고, 없으면 건너뛴다. 테스트 시작 때 스키마를 다시 만들므로 **개발·운영 DB를 가리키면 안 된다.**

```bash
docker run -d --name blog-mysql-test -p 3307:3306 \
  -e MYSQL_ROOT_PASSWORD=test-only -e MYSQL_DATABASE=blog_test mysql:8.4 --ngram-token-size=2
export BLOG_TEST_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3307/blog_test?connectionTimeZone=UTC&characterEncoding=UTF-8'
export BLOG_TEST_DATASOURCE_USERNAME=root BLOG_TEST_DATASOURCE_PASSWORD=test-only BLOG_TEST_ALLOW_CLEAN=true
./mvnw verify
```

CI(`.github/workflows/ci.yml`)는 PR과 `main` 푸시마다 MySQL 8.4 컨테이너를 띄워 `./mvnw -B verify`를 돌리고 JaCoCo 보고서를 아티팩트로 올린다.

## 구조

```text
src/main/java/net/java21/blog/backend/
  auth/ user/ blog/ post/ category/ tag/ comment/ media/ manage/ admin/   # 도메인별 controller·service·repository·dto·domain
  content/   # Markdown 렌더링·sanitize
  crypto/    # 개인정보 암호화·HMAC
  security/ config/ common/ i18n/ legal/ mail/
src/main/resources/   # application*.yml, messages_*.properties, logback-spring.xml, legal/
src/test/resources/db/schema-mysql.sql   # 테스트·로컬용 스키마 스냅숏
docs/operations.md    # 운영 문서
```

API 응답은 공통 틀 `{ header: { isSuccessful, resultCode, resultMessage, traceId }, result }`을 쓴다(blog-docs `api-guidelines.md`).

## 문서

- 운영(필수 환경 변수, 로그, 백업, 정기 작업, 첫 SUPER_ADMIN): [docs/operations.md](docs/operations.md)
- 스펙: [blog-docs/specs](https://github.com/marco-blog/blog-docs/tree/main/specs) — 001 핵심 기능은 [specs/001-blog-core](https://github.com/marco-blog/blog-docs/tree/main/specs/001-blog-core)(spec, plan, contracts/api.md, quickstart.md)
- API 규칙: [blog-docs/api-guidelines.md](https://github.com/marco-blog/blog-docs/blob/main/api-guidelines.md)
- 스키마·마이그레이션: [blog-docs/db](https://github.com/marco-blog/blog-docs/tree/main/db)
- 개발 규칙: [CLAUDE.md](CLAUDE.md), 원칙은 blog-docs `.specify/memory/constitution.md`
