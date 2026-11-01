package kz.tiyn.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import kz.tiyn.support.TestImages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

class S3StorageTests {

    private static final String BUCKET = "proofs-test";
    private static GenericContainer<?> seaweed;
    private static String endpoint;

    @BeforeAll
    static void start() {
        seaweed = new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.48"))
                .withCopyFileToContainer(
                        MountableFile.forHostPath("docker/seaweedfs-s3.json"), "/etc/seaweedfs/s3.json")
                .withCommand("server", "-dir=/data", "-s3", "-s3.port=8333", "-s3.config=/etc/seaweedfs/s3.json")
                .withExposedPorts(8333)
                .waitingFor(Wait.forListeningPort());
        seaweed.start();
        endpoint = "http://" + seaweed.getHost() + ":" + seaweed.getMappedPort(8333);
        try (S3Client admin = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create("tiyn", "tiyn-secret")))
                .forcePathStyle(true)
                .build()) {
            admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
    }

    @AfterAll
    static void stop() {
        seaweed.stop();
    }

    private ApplicationContextRunner runner(String bucket) {
        return new ApplicationContextRunner()
                .withUserConfiguration(S3Config.class, S3Storage.class)
                .withPropertyValues(
                        "tiyn.media.storage=s3",
                        "tiyn.media.s3.endpoint=" + endpoint,
                        "tiyn.media.s3.region=us-east-1",
                        "tiyn.media.s3.bucket=" + bucket,
                        "tiyn.media.s3.access-key=tiyn",
                        "tiyn.media.s3.secret-key=tiyn-secret",
                        "tiyn.media.s3.view-url-ttl=PT5M");
    }

    @Test
    void storedImageIsReadableThroughThePresignedUrl() {
        runner(BUCKET).run(context -> {
            Storage storage = context.getBean(Storage.class);

            String key = storage.put("task-1", "image/png", new ByteArrayInputStream(TestImages.png(10, 10)));

            assertThat(key).startsWith("proofs/task-1/");
            HttpResponse<byte[]> response = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(storage.presignView(key)).build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).hasValue("image/png");
            assertThat(response.body()).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        });
    }

    @Test
    void invalidImagesNeverReachTheBucket() {
        runner(BUCKET).run(context -> {
            Storage storage = context.getBean(Storage.class);

            assertThatThrownBy(() -> storage.put("t", "image/png", new ByteArrayInputStream(new byte[] {1, 2})))
                    .isInstanceOf(InvalidMediaException.class);
            assertThatThrownBy(() -> storage.put("t", "image/webp", new ByteArrayInputStream(new byte[] {1})))
                    .isInstanceOf(InvalidMediaException.class);
        });
    }

    @Test
    void aMissingBucketStopsStartup() {
        runner("does-not-exist").run(context -> assertThat(context).hasFailed());
    }
}
