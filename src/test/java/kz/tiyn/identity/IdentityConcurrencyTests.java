package kz.tiyn.identity;

import static kz.tiyn.support.FakeGoogleConfig.tokenFor;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import kz.tiyn.identity.app.IdentityService;
import kz.tiyn.identity.app.SignInResult;
import kz.tiyn.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class IdentityConcurrencyTests {

    private static final int THREADS = 8;

    @Autowired
    IdentityService service;

    @Autowired
    FamilyDirectory directory;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void concurrentFirstSignInsOfTheSameGoogleAccountCreateOneUser() throws Exception {
        String sub = "sub-" + UUID.randomUUID();
        String token = tokenFor(sub, "Aigul");

        List<SignInResult> results = runConcurrently(() -> service.signIn(token, "PARENT", "Aigul"));

        assertThat(results.stream().map(r -> r.user().id()).collect(Collectors.toSet()))
                .hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.users WHERE idp_sub = ?", Integer.class, sub))
                .isEqualTo(1);
    }

    @Test
    void concurrentJoinsOfTheSamePairCreateOneLinkAndOneEvent() throws Exception {
        SignInResult parent = service.signIn(tokenFor("sub-" + UUID.randomUUID(), "P"), "PARENT", "P");
        SignInResult teen = service.signIn(tokenFor("sub-" + UUID.randomUUID(), "T"), "TEEN", "T");

        runConcurrently(() -> service.join(teen.user().id(), parent.user().joinCode()));

        assertThat(directory.areLinked(parent.user().id(), teen.user().id())).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM identity.family_links WHERE parent_id = ? AND teen_id = ?",
                        Integer.class,
                        parent.user().id(),
                        teen.user().id()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT generation FROM identity.family_links WHERE teen_id = ?",
                        Integer.class,
                        teen.user().id()))
                .isEqualTo(1);
    }

    private <T> List<T> runConcurrently(java.util.concurrent.Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
