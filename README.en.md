# blog-backend

[한국어](README.md) | **English** | [日本語](README.ja.md) | [简体中文](README.zh-CN.md)

REST API server of the multi-user blog platform running at [blog.java21.net](https://blog.java21.net) (a Tistory-like service).
It provides sign-up and login, multiple blogs per member, writing and publishing posts (Markdown), categories and tags, comments, image upload and thumbnails, and four languages (ko, en, ja, zh-CN).
The UI lives in the sibling repository [blog-front](https://github.com/marco-blog/blog-front) (React SSR), and the specs are in [blog-docs](https://github.com/marco-blog/blog-docs).

## Stack

- Java 21, Maven (wrapper `./mvnw` included), Spring Boot 4.1
- Spring Web MVC, Spring Security, Spring Data JPA + QueryDSL (OpenFeign), Bean Validation, Spring Mail, Actuator
- Auth: JWT access token (30 min, HS256) + refresh token (rotated on use, 4 h idle / 7 days absolute), both in HttpOnly cookies
- DB: MySQL 8.4 (FULLTEXT ngram, `--ngram-token-size=2`). The schema is owned by the Crowfoot ERD document; no Flyway (`ddl-auto=validate`)
- Content: commonmark (GFM tables, strikethrough) renders HTML, cleaned by OWASP Java HTML Sanitizer (code highlighting happens in the front SSR)
- Images: Thumbnailator, TwelveMonkeys (WebP). Caffeine for view-count de-duplication and thumbnail locks
- API docs: springdoc-openapi (`/v3/api-docs`, disabled in prod)
- Tests: JUnit 5, Mockito, Spring slice tests, H2 (MySQL mode), JaCoCo (line coverage ≥ 80%)

## Prerequisites

- JDK 21
- Docker (for a local MySQL 8.4 and the Mailpit development mail server)
- Check out the three repositories as siblings so relative paths in the docs work: `blog/blog-docs`, `blog/blog-backend`, `blog/blog-front`

## Profiles

| Profile | File | Purpose |
|---|---|---|
| `local` (default) | `src/main/resources/application-local.yml` | Development. Reads `.env` in the repository root. Used when no profile is given |
| `prod` | `src/main/resources/application-prod.yml` | Production. Every connection setting and secret comes from environment variables only ([operations guide](docs/operations.md)) |
| `test` | `src/test/resources/application-test.yml` | Tests only (enabled by surefire). Contains fixed test-only keys and never uses the development DB |

## Running locally

### 1. Secrets (`.env`)

```bash
cp .env.example .env
```

Fill in the values below. `.env` is in `.gitignore`; never commit it.

| Name | How to create |
|---|---|
| `DB_PASSWORD` | Only for the development DB (Crowfoot `cf_u2_d2`). See the "Database" tab in Crowfoot |
| `BLOG_CRYPTO_KEY_V1` | `openssl rand -base64 32` (personal-data encryption key) |
| `BLOG_CRYPTO_HASH_KEY` | `openssl rand -base64 32` (HMAC key for email lookup; never change it once set) |
| `BLOG_AUTH_JWT_SECRET` | `openssl rand -base64 48` (access-token signing key) |

Optional values (mail, first SUPER_ADMIN email, image directories, …) are documented in the comments of `.env.example`. Environment variables with the same name take precedence over `.env`.

### 2. Choose a database

**A. Development DB (Crowfoot `cf_u2_d2`)**: only `DB_PASSWORD` in `.env` is needed. Go to the next step.

**B. Your own MySQL (Docker)**: the app fails to start on an empty DB because of `ddl-auto=validate`, so create the schema first.
The schema file is [blog-docs `db/schema-mysql.sql`](https://github.com/marco-blog/blog-docs/blob/main/db/schema-mysql.sql); the same snapshot is in this repository at `src/test/resources/db/schema-mysql.sql`.

```bash
docker run -d --name blog-mysql -p 3306:3306 \
  -e MYSQL_DATABASE=blog -e MYSQL_USER=blog -e MYSQL_PASSWORD=blog -e MYSQL_ROOT_PASSWORD=root \
  mysql:8.4 --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci --ngram-token-size=2
# once the server is up (a few seconds)
docker exec -i blog-mysql mysql -uroot -proot blog < src/test/resources/db/schema-mysql.sql

# override the local profile's connection settings (DB_PASSWORD is not used then)
export SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/blog?connectionTimeZone=UTC&characterEncoding=UTF-8'
export SPRING_DATASOURCE_USERNAME=blog SPRING_DATASOURCE_PASSWORD=blog
```

The passwords above (`blog`, `root`) are examples for a throwaway container on your machine. Do not reuse them anywhere else.

### 3. Mail server (Mailpit)

In the local profile, password-reset mail goes to `localhost:1025` (no auth, no STARTTLS).

```bash
docker run -d --name blog-mailpit -p 1025:1025 -p 8025:8025 axllent/mailpit
# inbox: http://localhost:8025
```

### 4. Run

```bash
./mvnw spring-boot:run          # http://localhost:8080
curl http://localhost:8080/actuator/health
```

- Links in mail (`blog.base-url`) point to `http://localhost:5173` (the front dev server). Change it with `BLOG_BASE_URL`.
- State-changing requests are accepted only from the allowed origins (`http://localhost:5173`, `http://localhost:3000`). Run blog-front to use the UI.
- File storage is set by one variable, `BLOG_DATA_DIR`. Without it, the app uses `./data` (`media/upload|temp|thumbnail`, `exports`).
- OpenAPI document: `http://localhost:8080/v3/api-docs`
- Running the jar: `./mvnw -B -DskipTests package` → `java -jar target/blog-backend-*.jar`

## Testing

```bash
./mvnw verify      # all tests + JaCoCo check (fails below 80% line coverage)
./mvnw test        # tests only
```

- Coverage report: `target/site/jacoco/index.html`
- Controllers use `@WebMvcTest`, services use Mockito unit tests, repositories use `@DataJpaTest` on H2 (MySQL mode). No external DB is needed.
- `@MySqlRepositoryTest` (MySQL-only behavior: FULLTEXT ngram, row-lock concurrency) runs only when the variables below are set and is skipped otherwise. It recreates the schema at start, so **never point it at a development or production DB.**

```bash
docker run -d --name blog-mysql-test -p 3307:3306 \
  -e MYSQL_ROOT_PASSWORD=test-only -e MYSQL_DATABASE=blog_test mysql:8.4 --ngram-token-size=2
export BLOG_TEST_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3307/blog_test?connectionTimeZone=UTC&characterEncoding=UTF-8'
export BLOG_TEST_DATASOURCE_USERNAME=root BLOG_TEST_DATASOURCE_PASSWORD=test-only BLOG_TEST_ALLOW_CLEAN=true
./mvnw verify
```

CI (`.github/workflows/ci.yml`) starts a MySQL 8.4 container and runs `./mvnw -B verify` on every PR and push to `main`, uploading the JaCoCo report as an artifact.

## Layout

```text
src/main/java/net/java21/blog/backend/
  auth/ user/ blog/ post/ category/ tag/ comment/ media/ manage/ admin/   # per-domain controller, service, repository, dto, domain
  content/   # Markdown rendering and sanitizing
  crypto/    # personal-data encryption and HMAC
  security/ config/ common/ i18n/ legal/ mail/
src/main/resources/   # application*.yml, messages_*.properties, logback-spring.xml, legal/
src/test/resources/db/schema-mysql.sql   # schema snapshot for tests and local use
docs/operations.md    # operations guide
```

API responses use the common envelope `{ header: { isSuccessful, resultCode, resultMessage, traceId }, result }` (blog-docs `api-guidelines.md`).

## Documentation

- Operations (required environment variables, logs, backups, scheduled jobs, first SUPER_ADMIN): [docs/operations.md](docs/operations.md)
- Specs: [blog-docs/specs](https://github.com/marco-blog/blog-docs/tree/main/specs) — core features in [specs/001-blog-core](https://github.com/marco-blog/blog-docs/tree/main/specs/001-blog-core) (spec, plan, contracts/api.md, quickstart.md)
- API guidelines: [blog-docs/api-guidelines.md](https://github.com/marco-blog/blog-docs/blob/main/api-guidelines.md)
- Schema and migrations: [blog-docs/db](https://github.com/marco-blog/blog-docs/tree/main/db)
- Development rules: [CLAUDE.md](CLAUDE.md); principles in blog-docs `.specify/memory/constitution.md`
