package net.java21.blog.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

class TestSchemaInitializerTest {

    @Test
    void readsTableNamesFromCreateStatements() {
        String ddl = """
                -- CREATE TABLE in_comment (
                CREATE TABLE users (
                    id BIGINT
                );
                create table `blogs` (id BIGINT);
                ALTER TABLE posts ADD CONSTRAINT fk FOREIGN KEY (blog_id) REFERENCES blogs (id);
                """;
        assertThat(TestSchemaInitializer.tableNames(ddl)).containsExactly("users", "blogs");
    }

    @Test
    void snapshotHas40Tables() throws IOException {
        String ddl = StreamUtils.copyToString(
                new ClassPathResource(TestSchemaInitializer.SNAPSHOT).getInputStream(), StandardCharsets.UTF_8);
        assertThat(TestSchemaInitializer.tableNames(ddl)).hasSize(40).contains("users", "blogs", "posts");
    }
}
