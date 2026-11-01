package kz.tiyn.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class StorageSelectionTests {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(InMemoryStorage.class);

    @Test
    void withoutThePropertyThereIsNoStorageBean() {
        runner.run(context -> assertThat(context).doesNotHaveBean(Storage.class));
    }

    @Test
    void memoryIsSelectedOnlyWhenAskedFor() {
        runner.withPropertyValues("tiyn.media.storage=memory")
                .run(context -> assertThat(context).hasSingleBean(Storage.class));
        runner.withPropertyValues("tiyn.media.storage=s3")
                .run(context -> assertThat(context).doesNotHaveBean(Storage.class));
    }
}
