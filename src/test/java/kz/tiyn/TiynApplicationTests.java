package kz.tiyn;

import static org.assertj.core.api.Assertions.assertThat;

import kz.tiyn.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class TiynApplicationTests {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void contextLoadsWithFlywayManagedTables() {
        Integer tables = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables"
                        + " WHERE table_name IN ('event_publication', 'shedlock')",
                Integer.class);

        assertThat(tables).isEqualTo(2);
    }
}
