# blog-backend

[한국어](README.md) | [English](README.en.md) | **日本語** | [简体中文](README.zh-CN.md)

[blog.java21.net](https://blog.java21.net) で運用するマルチユーザーブログプラットフォーム（Tistory のようなサービス）の REST API サーバーです。
会員登録・ログイン、会員ごとの複数ブログ、記事の作成・公開（Markdown）、カテゴリ・タグ、コメント、画像アップロード・サムネイル、4 言語（ko・en・ja・zh-CN）を提供します。
画面は兄弟リポジトリ [blog-front](https://github.com/marco-blog/blog-front)（React SSR）が担当し、仕様は [blog-docs](https://github.com/marco-blog/blog-docs) にあります。

## 技術スタック

- Java 21、Maven（ラッパー `./mvnw` 同梱）、Spring Boot 4.1
- Spring Web MVC、Spring Security、Spring Data JPA + QueryDSL（OpenFeign）、Bean Validation、Spring Mail、Actuator
- 認証: JWT アクセストークン（30 分、HS256）+ リフレッシュトークン（使用時に交換、アイドル 4 時間・絶対 7 日）、いずれも HttpOnly Cookie
- DB: MySQL 8.4（FULLTEXT ngram、`--ngram-token-size=2`）。スキーマの正は Crowfoot ERD ドキュメントで、Flyway は使いません（`ddl-auto=validate`）
- 本文: commonmark（GFM の表・取り消し線）で HTML を生成し、OWASP Java HTML Sanitizer で無害化（コードのハイライトは front の SSR）
- 画像: Thumbnailator、TwelveMonkeys（WebP）。閲覧数の重複排除・サムネイルのロックに Caffeine
- API ドキュメント: springdoc-openapi（`/v3/api-docs`、prod では無効）
- テスト: JUnit 5、Mockito、Spring スライステスト、H2（MySQL モード）、JaCoCo（ライン 80% 以上）

## 必要なもの

- JDK 21
- Docker（ローカルの MySQL 8.4 と開発用メールサーバー Mailpit を起動する場合）
- 3 つのリポジトリを兄弟ディレクトリに置くと、ドキュメントの相対パスが合います: `blog/blog-docs`、`blog/blog-backend`、`blog/blog-front`

## プロファイル

| プロファイル | ファイル | 用途 |
|---|---|---|
| `local`（既定） | `src/main/resources/application-local.yml` | 開発用。リポジトリ直下の `.env` を読みます。プロファイル未指定時はこれで起動します |
| `prod` | `src/main/resources/application-prod.yml` | 本番用。接続情報・秘密値はすべて環境変数からのみ受け取ります（[運用ドキュメント](docs/operations.md)） |
| `test` | `src/test/resources/application-test.yml` | テスト専用（surefire が有効化）。テスト用の固定キーのみで、開発 DB は使いません |

## ローカルで動かす

### 1. 秘密値の準備（`.env`）

```bash
cp .env.example .env
```

`.env` に次の値を設定します。`.env` は `.gitignore` 対象で、絶対にコミットしません。

| 名前 | 作り方 |
|---|---|
| `DB_PASSWORD` | 開発 DB（Crowfoot `cf_u2_d2`）を使う場合のみ。Crowfoot 画面の「データベース」タブで確認 |
| `BLOG_CRYPTO_KEY_V1` | `openssl rand -base64 32`（個人情報の暗号化キー） |
| `BLOG_CRYPTO_HASH_KEY` | `openssl rand -base64 32`（メール検索用 HMAC キー。一度決めたら変えない） |
| `BLOG_AUTH_JWT_SECRET` | `openssl rand -base64 48`（アクセストークンの署名キー） |

任意の値（メール、最初の SUPER_ADMIN のメール、画像ディレクトリなど）は `.env.example` のコメントを参照してください。同名の環境変数を与えると `.env` より優先されます。

### 2. DB を選ぶ

**A. 開発 DB（Crowfoot `cf_u2_d2`）**: `.env` の `DB_PASSWORD` だけで動きます。次の手順へ進みます。

**B. 自分の MySQL（Docker）**: 空の DB では `ddl-auto=validate` で起動に失敗するため、先にスキーマを作成します。
スキーマファイルは [blog-docs `db/schema-mysql.sql`](https://github.com/marco-blog/blog-docs/blob/main/db/schema-mysql.sql) で、同じスナップショットがこのリポジトリの `src/test/resources/db/schema-mysql.sql` にあります。

```bash
docker run -d --name blog-mysql -p 3306:3306 \
  -e MYSQL_DATABASE=blog -e MYSQL_USER=blog -e MYSQL_PASSWORD=blog -e MYSQL_ROOT_PASSWORD=root \
  mysql:8.4 --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci --ngram-token-size=2
# 数秒後にサーバーが起動したら
docker exec -i blog-mysql mysql -uroot -proot blog < src/test/resources/db/schema-mysql.sql

# local プロファイルの接続情報を環境変数で上書き（この場合 DB_PASSWORD は使われません）
export SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/blog?connectionTimeZone=UTC&characterEncoding=UTF-8'
export SPRING_DATASOURCE_USERNAME=blog SPRING_DATASOURCE_PASSWORD=blog
```

上記のパスワード（`blog`、`root`）は手元の使い捨てコンテナ用の例です。ほかでは使わないでください。

### 3. メールサーバー（Mailpit）

local プロファイルでは、パスワード再設定メールを `localhost:1025`（認証・STARTTLS なし）へ送ります。

```bash
docker run -d --name blog-mailpit -p 1025:1025 -p 8025:8025 axllent/mailpit
# 受信メール: http://localhost:8025
```

### 4. 起動

```bash
./mvnw spring-boot:run          # http://localhost:8080
curl http://localhost:8080/actuator/health
```

- メール内リンクのアドレス（`blog.base-url`）は `http://localhost:5173`（front 開発サーバー）です。変更は `BLOG_BASE_URL` で。
- 状態を変更するリクエストは許可 Origin（`http://localhost:5173`、`http://localhost:3000`）からのみ受け付けます。画面は blog-front を起動して確認します。
- ファイルの保存先は `BLOG_DATA_DIR` ひとつで指定します。指定しない場合は `./data` 配下（`media/upload|temp|thumbnail`、`exports`）を使います。
- OpenAPI ドキュメント: `http://localhost:8080/v3/api-docs`
- jar で起動: `./mvnw -B -DskipTests package` → `java -jar target/blog-backend-*.jar`

## テスト

```bash
./mvnw verify      # 全テスト + JaCoCo チェック（ラインカバレッジ 80% 未満で失敗）
./mvnw test        # テストのみ
```

- カバレッジレポート: `target/site/jacoco/index.html`
- Controller は `@WebMvcTest`、Service は Mockito の単体テスト、Repository は H2（MySQL モード）の `@DataJpaTest` で動きます。外部 DB は不要です。
- MySQL 固有の動作（FULLTEXT ngram、行ロックの並行性）を確認する `@MySqlRepositoryTest` は、下記の環境変数があるときだけ実行され、なければスキップされます。テスト開始時にスキーマを作り直すため、**開発・本番 DB を指してはいけません。**

```bash
docker run -d --name blog-mysql-test -p 3307:3306 \
  -e MYSQL_ROOT_PASSWORD=test-only -e MYSQL_DATABASE=blog_test mysql:8.4 --ngram-token-size=2
export BLOG_TEST_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3307/blog_test?connectionTimeZone=UTC&characterEncoding=UTF-8'
export BLOG_TEST_DATASOURCE_USERNAME=root BLOG_TEST_DATASOURCE_PASSWORD=test-only BLOG_TEST_ALLOW_CLEAN=true
./mvnw verify
```

CI（`.github/workflows/ci.yml`）は PR と `main` への push ごとに MySQL 8.4 コンテナを起動して `./mvnw -B verify` を実行し、JaCoCo レポートをアーティファクトとしてアップロードします。

## 構成

```text
src/main/java/net/java21/blog/backend/
  auth/ user/ blog/ post/ category/ tag/ comment/ media/ manage/ admin/   # ドメインごとの controller・service・repository・dto・domain
  content/   # Markdown のレンダリング・無害化
  crypto/    # 個人情報の暗号化・HMAC
  security/ config/ common/ i18n/ legal/ mail/
src/main/resources/   # application*.yml、messages_*.properties、logback-spring.xml、legal/
src/test/resources/db/schema-mysql.sql   # テスト・ローカル用スキーマのスナップショット
docs/operations.md    # 運用ドキュメント
```

API レスポンスは共通形式 `{ header: { isSuccessful, resultCode, resultMessage, traceId }, result }` を使います（blog-docs `api-guidelines.md`）。

## ドキュメント

- 運用（必須の環境変数、ログ、バックアップ、定期ジョブ、最初の SUPER_ADMIN）: [docs/operations.md](docs/operations.md)
- 仕様: [blog-docs/specs](https://github.com/marco-blog/blog-docs/tree/main/specs) — コア機能は [specs/001-blog-core](https://github.com/marco-blog/blog-docs/tree/main/specs/001-blog-core)（spec、plan、contracts/api.md、quickstart.md）
- API 規約: [blog-docs/api-guidelines.md](https://github.com/marco-blog/blog-docs/blob/main/api-guidelines.md)
- スキーマ・マイグレーション: [blog-docs/db](https://github.com/marco-blog/blog-docs/tree/main/db)
- 開発ルール: [CLAUDE.md](CLAUDE.md)、原則は blog-docs `.specify/memory/constitution.md`
