package net.java21.blog.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import net.java21.blog.backend.support.MySqlRepositoryTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 스냅숏(db/schema-mysql.sql = Crowfoot "blog 1.0" export)이 테스트 MySQL 스키마에 그대로 만들어지는지 확인한다. */
@MySqlRepositoryTest
class SchemaSnapshotTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void creates40Tables() {
        Integer tables = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
                  AND table_name NOT LIKE 'zz\\_%'
                """, Integer.class);
        assertThat(tables).isEqualTo(40);
    }

    @Test
    void creates74ForeignKeys() {
        Integer fks = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.referential_constraints
                WHERE constraint_schema = DATABASE() AND table_name NOT LIKE 'zz\\_%'
                """, Integer.class);
        assertThat(fks).isEqualTo(74);
    }

    @Test
    void createsKeyIndexes() {
        assertThat(indexExists("users", "uk_users_email_hash", "BTREE")).isTrue();
        assertThat(indexExists("blogs", "uk_blogs_handle", "BTREE")).isTrue();
        assertThat(indexExists("posts", "ft_posts_title_content", "FULLTEXT")).isTrue();
    }

    private boolean indexExists(String table, String index, String type) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ? AND index_type = ?
                """, Integer.class, table, index, type);
        return count != null && count > 0;
    }
}
