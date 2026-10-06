package net.java21.blog.backend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TestSchemaGuardTest {

    private static final String DEV_URL = "jdbc:mysql://db.example:3306/cf_u2_d2?connectionTimeZone=UTC";

    @Test
    void allowsCleanOnSeparateTestSchema() {
        assertThatCode(() -> TestSchemaGuard.check("cf_u2_d3", "true", DEV_URL)).doesNotThrowAnyException();
        assertThatCode(() -> TestSchemaGuard.check("cf_u2_d3", "true", null)).doesNotThrowAnyException();
    }

    @Test
    void refusesWithoutAllowFlag() {
        assertThatThrownBy(() -> TestSchemaGuard.check("cf_u2_d3", null, DEV_URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BLOG_TEST_ALLOW_CLEAN");
        assertThatThrownBy(() -> TestSchemaGuard.check("cf_u2_d3", "yes", DEV_URL))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesDevSchema() {
        assertThatThrownBy(() -> TestSchemaGuard.check("cf_u2_d2", "true", DEV_URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPRING_DATASOURCE_URL");
    }

    @Test
    void refusesMissingSchema() {
        assertThatThrownBy(() -> TestSchemaGuard.check(" ", "true", DEV_URL))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parsesSchemaFromJdbcUrl() {
        assertThat(TestSchemaGuard.schemaOf(DEV_URL)).isEqualTo("cf_u2_d2");
        assertThat(TestSchemaGuard.schemaOf("jdbc:mysql://localhost/blog")).isEqualTo("blog");
        assertThat(TestSchemaGuard.schemaOf("jdbc:mysql://localhost:3306/")).isNull();
        assertThat(TestSchemaGuard.schemaOf("jdbc:mysql://localhost:3306")).isNull();
        assertThat(TestSchemaGuard.schemaOf("mysql://localhost/blog")).isNull();
        assertThat(TestSchemaGuard.schemaOf(null)).isNull();
        assertThat(TestSchemaGuard.schemaOf("jdbc:mysql://bad host/x")).isNull();
    }
}
