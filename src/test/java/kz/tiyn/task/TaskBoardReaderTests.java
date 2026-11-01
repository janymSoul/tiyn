package kz.tiyn.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.task.TaskFixtures.Family;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
class TaskBoardReaderTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    TaskBoardReader board;

    @Autowired
    JdbcTemplate jdbc;

    TaskFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new TaskFixtures(mvc, json);
    }

    private Instant weekAgo() {
        return Instant.now().minus(Duration.ofDays(7));
    }

    private void approve(Family f, UUID id) throws Exception {
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"done\"}", f.teen().bearer(), "Idempotency-Key", "k-" + id)
                .andExpect(status().isOk());
        fx.send("/tasks/" + id + "/approve", "", f.parent().bearer()).andExpect(status().isOk());
    }

    private List<UUID> ids(List<BoardTask> tasks) {
        return tasks.stream().map(BoardTask::id).toList();
    }

    @Test
    void aTeenWithNoTasksGetsAnEmptyBoard() throws Exception {
        Family f = fx.linkedFamily();

        assertThat(board.board(f.teen().id(), weekAgo(), 50)).isEmpty();
    }

    @Test
    void openWorkComesFirstByDeadlineThenRecentHistoryNewestFirst() throws Exception {
        Family f = fx.linkedFamily();
        UUID later = fx.createItem(f, "TASK", null, 7200);
        UUID sooner = fx.createItem(f, "TASK", null, 3600);
        UUID doneFirst = fx.createItem(f, "TASK", null, 5000);
        UUID doneSecond = fx.createItem(f, "TASK", null, 5000);
        approve(f, doneFirst);
        approve(f, doneSecond);
        jdbc.update("update task.tasks set decided_at = now() - interval '1 hour' where id = ?", doneFirst);

        List<BoardTask> result = board.board(f.teen().id(), weekAgo(), 50);

        assertThat(ids(result)).containsExactly(sooner, later, doneSecond, doneFirst);
    }

    @Test
    void historyOlderThanTheWindowIsHidden() throws Exception {
        Family f = fx.linkedFamily();
        UUID old = fx.createItem(f, "TASK", null, 3600);
        UUID fresh = fx.createItem(f, "TASK", null, 3600);
        approve(f, old);
        approve(f, fresh);
        jdbc.update("update task.tasks set decided_at = now() - interval '30 days' where id = ?", old);

        assertThat(ids(board.board(f.teen().id(), weekAgo(), 50))).containsExactly(fresh);
    }

    @Test
    void theCapKeepsOpenWorkAheadOfHistory() throws Exception {
        Family f = fx.linkedFamily();
        UUID done = fx.createItem(f, "TASK", null, 3600);
        approve(f, done);
        UUID open1 = fx.createItem(f, "TASK", null, 3600);
        UUID open2 = fx.createItem(f, "TASK", null, 7200);

        assertThat(ids(board.board(f.teen().id(), weekAgo(), 2))).containsExactly(open1, open2);
    }

    @Test
    void milestonesCarryProgressAndTheirChildrenPointAtThem() throws Exception {
        Family f = fx.linkedFamily();
        UUID milestone = fx.createItem(f, "MILESTONE", null, 86_400);
        UUID first = fx.createItem(f, "TASK", milestone, 3600);
        fx.createItem(f, "TASK", milestone, 3600);
        approve(f, first);

        List<BoardTask> result = board.board(f.teen().id(), weekAgo(), 50);

        BoardTask m = result.stream()
                .filter(t -> t.id().equals(milestone))
                .findFirst()
                .orElseThrow();
        assertThat(m.approvedTasks()).isEqualTo(1);
        assertThat(m.totalTasks()).isEqualTo(2);
        assertThat(result.stream()
                        .filter(t -> milestone.equals(t.milestoneId()))
                        .count())
                .isEqualTo(2);
        assertThat(result.stream().filter(t -> !t.id().equals(milestone)))
                .allSatisfy(t -> assertThat(t.approvedTasks()).isNull());
    }

    @Test
    void otherTeensTasksNeverAppear() throws Exception {
        Family mine = fx.linkedFamily();
        Family other = fx.linkedFamily();
        fx.createItem(other, "TASK", null, 3600);

        assertThat(board.board(mine.teen().id(), weekAgo(), 50)).isEmpty();
    }
}
