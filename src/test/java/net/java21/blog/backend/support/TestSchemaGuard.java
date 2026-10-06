package net.java21.blog.backend.support;

import java.net.URI;

/**
 * 테스트가 스키마를 다시 만들기(테이블 삭제) 전에 확인하는 안전장치.
 * <ol>
 *   <li>환경 변수 {@code BLOG_TEST_ALLOW_CLEAN=true}가 있어야 한다.</li>
 *   <li>테스트 스키마 이름이 개발 DB({@code SPRING_DATASOURCE_URL})의 스키마 이름과 달라야 한다.</li>
 * </ol>
 */
public final class TestSchemaGuard {

    private TestSchemaGuard() {
    }

    public static void check(String testSchema, String allowClean, String devDatasourceUrl) {
        if (!"true".equals(allowClean)) {
            throw new IllegalStateException(
                    "테스트 DB clean 거부: BLOG_TEST_ALLOW_CLEAN=true 가 설정되지 않았습니다 (schema=" + testSchema + ")");
        }
        if (testSchema == null || testSchema.isBlank()) {
            throw new IllegalStateException("테스트 DB clean 거부: JDBC URL에 스키마 이름이 없습니다");
        }
        String devSchema = schemaOf(devDatasourceUrl);
        if (devSchema != null && devSchema.equalsIgnoreCase(testSchema)) {
            throw new IllegalStateException(
                    "테스트 DB clean 거부: 테스트 스키마(" + testSchema + ")가 SPRING_DATASOURCE_URL의 개발 DB 스키마와 같습니다");
        }
    }

    /** {@code jdbc:mysql://host:port/schema?params} 에서 schema를 꺼낸다. 없거나 해석할 수 없으면 null. */
    static String schemaOf(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            return null;
        }
        try {
            String path = URI.create(jdbcUrl.substring("jdbc:".length())).getPath();
            if (path == null || path.length() <= 1) {
                return null;
            }
            return path.substring(1);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
