# blog-backend

[한국어](README.md) | [English](README.en.md) | [日本語](README.ja.md) | **简体中文**

运行在 [blog.java21.net](https://blog.java21.net) 的多用户博客平台（类似 Tistory 的服务）的 REST API 服务器。
提供注册与登录、每位会员多个博客、文章撰写与发布（Markdown）、分类与标签、评论、图片上传与缩略图，以及四种语言（ko、en、ja、zh-CN）。
界面由同级仓库 [blog-front](https://github.com/marco-blog/blog-front)（React SSR）负责，规格文档位于 [blog-docs](https://github.com/marco-blog/blog-docs)。

## 技术栈

- Java 21、Maven（附带包装器 `./mvnw`）、Spring Boot 4.1
- Spring Web MVC、Spring Security、Spring Data JPA + QueryDSL（OpenFeign）、Bean Validation、Spring Mail、Actuator
- 认证：JWT 访问令牌（30 分钟，HS256）+ 刷新令牌（使用时轮换，空闲 4 小时、绝对 7 天），均存放在 HttpOnly Cookie 中
- 数据库：MySQL 8.4（FULLTEXT ngram，`--ngram-token-size=2`）。表结构以 Crowfoot ERD 文档为准，不使用 Flyway（`ddl-auto=validate`）
- 正文：用 commonmark（GFM 表格、删除线）生成 HTML，再用 OWASP Java HTML Sanitizer 清理（代码高亮在 front 的 SSR 中完成）
- 图片：Thumbnailator、TwelveMonkeys（WebP）。浏览数去重和缩略图锁使用 Caffeine
- API 文档：springdoc-openapi（`/v3/api-docs`，prod 中关闭）
- 测试：JUnit 5、Mockito、Spring 切片测试、H2（MySQL 模式）、JaCoCo（行覆盖率 ≥ 80%）

## 环境要求

- JDK 21
- Docker（用于在本地运行 MySQL 8.4 和开发用邮件服务器 Mailpit）
- 将三个仓库放在同级目录下，文档中的相对路径才能对应：`blog/blog-docs`、`blog/blog-backend`、`blog/blog-front`

## Profile

| Profile | 文件 | 用途 |
|---|---|---|
| `local`（默认） | `src/main/resources/application-local.yml` | 开发。读取仓库根目录的 `.env`。未指定 profile 时使用 |
| `prod` | `src/main/resources/application-prod.yml` | 生产。所有连接信息和密钥只通过环境变量传入（[运维文档](docs/operations.md)） |
| `test` | `src/test/resources/application-test.yml` | 仅用于测试（由 surefire 启用）。只包含测试用固定密钥，绝不使用开发数据库 |

## 本地运行

### 1. 准备密钥（`.env`）

```bash
cp .env.example .env
```

在 `.env` 中填写以下值。`.env` 已列入 `.gitignore`，绝不要提交。

| 名称 | 生成方式 |
|---|---|
| `DB_PASSWORD` | 仅在使用开发数据库（Crowfoot `cf_u2_d2`）时需要。在 Crowfoot 界面的“数据库”标签页查看 |
| `BLOG_CRYPTO_KEY_V1` | `openssl rand -base64 32`（个人信息加密密钥） |
| `BLOG_CRYPTO_HASH_KEY` | `openssl rand -base64 32`（用于按邮箱查找的 HMAC 密钥，一旦确定不要更改） |
| `BLOG_AUTH_JWT_SECRET` | `openssl rand -base64 48`（访问令牌签名密钥） |

可选值（邮件、第一个 SUPER_ADMIN 的邮箱、图片目录等）见 `.env.example` 中的注释。同名环境变量优先于 `.env`。

### 2. 选择数据库

**A. 开发数据库（Crowfoot `cf_u2_d2`）**：只需 `.env` 中的 `DB_PASSWORD`，直接进入下一步。

**B. 自己的 MySQL（Docker）**：由于 `ddl-auto=validate`，空数据库无法启动，需先创建表结构。
表结构文件是 [blog-docs `db/schema-mysql.sql`](https://github.com/marco-blog/blog-docs/blob/main/db/schema-mysql.sql)，本仓库的 `src/test/resources/db/schema-mysql.sql` 中有相同的快照。

```bash
docker run -d --name blog-mysql -p 3306:3306 \
  -e MYSQL_DATABASE=blog -e MYSQL_USER=blog -e MYSQL_PASSWORD=blog -e MYSQL_ROOT_PASSWORD=root \
  mysql:8.4 --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci --ngram-token-size=2
# 几秒后服务器启动完成
docker exec -i blog-mysql mysql -uroot -proot blog < src/test/resources/db/schema-mysql.sql

# 用环境变量覆盖 local profile 的连接信息（此时不使用 DB_PASSWORD）
export SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/blog?connectionTimeZone=UTC&characterEncoding=UTF-8'
export SPRING_DATASOURCE_USERNAME=blog SPRING_DATASOURCE_PASSWORD=blog
```

上面的密码（`blog`、`root`）只是本机一次性容器的示例，不要在其他地方使用。

### 3. 邮件服务器（Mailpit）

local profile 下，密码重置邮件发送到 `localhost:1025`（无认证、无 STARTTLS）。

```bash
docker run -d --name blog-mailpit -p 1025:1025 -p 8025:8025 axllent/mailpit
# 收件箱：http://localhost:8025
```

### 4. 启动

```bash
./mvnw spring-boot:run          # http://localhost:8080
curl http://localhost:8080/actuator/health
```

- 邮件中的链接地址（`blog.base-url`）为 `http://localhost:5173`（front 开发服务器）。可用 `BLOG_BASE_URL` 修改。
- 修改状态的请求只接受来自允许的 Origin（`http://localhost:5173`、`http://localhost:3000`）。界面请启动 blog-front 查看。
- 文件存储根目录由 `BLOG_DATA_DIR` 一个变量指定。未指定时使用 `./data` 下的 `media/upload|temp|thumbnail` 和 `exports`。
- OpenAPI 文档：`http://localhost:8080/v3/api-docs`
- 以 jar 运行：`./mvnw -B -DskipTests package` → `java -jar target/blog-backend-*.jar`

## 测试

```bash
./mvnw verify      # 全部测试 + JaCoCo 检查（行覆盖率低于 80% 时失败）
./mvnw test        # 仅运行测试
```

- 覆盖率报告：`target/site/jacoco/index.html`
- Controller 用 `@WebMvcTest`，Service 用 Mockito 单元测试，Repository 用 H2（MySQL 模式）的 `@DataJpaTest`，不需要外部数据库。
- 验证 MySQL 专有行为（FULLTEXT ngram、行锁并发）的 `@MySqlRepositoryTest` 仅在设置了以下环境变量时运行，否则跳过。测试开始时会重建表结构，因此**绝不能指向开发或生产数据库。**

```bash
docker run -d --name blog-mysql-test -p 3307:3306 \
  -e MYSQL_ROOT_PASSWORD=test-only -e MYSQL_DATABASE=blog_test mysql:8.4 --ngram-token-size=2
export BLOG_TEST_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3307/blog_test?connectionTimeZone=UTC&characterEncoding=UTF-8'
export BLOG_TEST_DATASOURCE_USERNAME=root BLOG_TEST_DATASOURCE_PASSWORD=test-only BLOG_TEST_ALLOW_CLEAN=true
./mvnw verify
```

CI（`.github/workflows/ci.yml`）在每个 PR 和推送到 `main` 时启动 MySQL 8.4 容器运行 `./mvnw -B verify`，并将 JaCoCo 报告作为构件上传。

## 目录结构

```text
src/main/java/net/java21/blog/backend/
  auth/ user/ blog/ post/ category/ tag/ comment/ media/ manage/ admin/   # 按领域划分的 controller、service、repository、dto、domain
  content/   # Markdown 渲染与清理
  crypto/    # 个人信息加密与 HMAC
  security/ config/ common/ i18n/ legal/ mail/
src/main/resources/   # application*.yml、messages_*.properties、logback-spring.xml、legal/
src/test/resources/db/schema-mysql.sql   # 测试与本地使用的表结构快照
docs/operations.md    # 运维文档
```

API 响应使用统一格式 `{ header: { isSuccessful, resultCode, resultMessage, traceId }, result }`（blog-docs `api-guidelines.md`）。

## 文档

- 运维（必需的环境变量、日志、备份、定时任务、第一个 SUPER_ADMIN）：[docs/operations.md](docs/operations.md)
- 规格：[blog-docs/specs](https://github.com/marco-blog/blog-docs/tree/main/specs) —— 核心功能见 [specs/001-blog-core](https://github.com/marco-blog/blog-docs/tree/main/specs/001-blog-core)（spec、plan、contracts/api.md、quickstart.md）
- API 规范：[blog-docs/api-guidelines.md](https://github.com/marco-blog/blog-docs/blob/main/api-guidelines.md)
- 表结构与迁移：[blog-docs/db](https://github.com/marco-blog/blog-docs/tree/main/db)
- 开发规则：[CLAUDE.md](CLAUDE.md)，原则见 blog-docs `.specify/memory/constitution.md`
