package kz.tiyn;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kz.tiyn.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class PaginationIndexTests {

    @Autowired
    JdbcTemplate jdbc;

    private List<String> indexNames() {
        return jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname IN ('task', 'ledger')", String.class);
    }

    private String definition(String index) {
        return jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = ?", String.class, index);
    }

    @Test
    void everyPaginatedAccessPathHasAnOwnerSortKeyIdIndex() {
        assertThat(definition("tasks_teen_created_idx")).contains("(teen_id, created_at DESC, id DESC)");
        assertThat(definition("tasks_parent_created_idx")).contains("(parent_id, created_at DESC, id DESC)");
        assertThat(definition("payouts_teen_requested_idx")).contains("(teen_id, requested_at DESC, id DESC)");
        assertThat(definition("payouts_parent_requested_idx")).contains("(parent_id, requested_at DESC, id DESC)");
        assertThat(definition("savings_goals_teen_created_idx")).contains("(teen_id, created_at DESC, id DESC)");
        assertThat(definition("ledger_entries_teen_created_id_idx")).contains("(teen_id, created_at DESC, id DESC)");
        assertThat(definition("task_series_parent_created_id_idx")).contains("(parent_id, created_at DESC, id DESC)");
    }

    @Test
    void theParentTaskListKnowsThatParentAndTeenAreCorrelated() {
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_statistic_ext WHERE stxname = 'tasks_parent_teen_stats'",
                        Integer.class))
                .isEqualTo(1);
    }

    @Test
    void indexesThatTheKeysetIndexesSupersededAreGone() {
        assertThat(indexNames())
                .doesNotContain(
                        "savings_goals_teen_idx", "ledger_entries_teen_created_idx", "task_series_parent_created_idx");
    }

    @Test
    void indexesThatOtherQueriesStillNeedAreKept() {
        assertThat(indexNames())
                .contains(
                        "tasks_teen_state_idx",
                        "tasks_parent_submitted_idx",
                        "tasks_assigned_deadline_idx",
                        "payouts_stale_idx",
                        "payouts_parent_open_idx",
                        "payouts_one_open_per_teen_idx");
    }
}
