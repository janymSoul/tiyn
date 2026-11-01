package kz.tiyn.media;

import java.io.InputStream;
import java.net.URI;
import java.util.UUID;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Component
@ConditionalOnProperty(name = "tiyn.media.storage", havingValue = "s3")
class S3Storage implements Storage, InitializingBean {

    private final S3Client client;
    private final S3Presigner presigner;
    private final S3Properties properties;

    S3Storage(S3Client client, S3Presigner presigner, S3Properties properties) {
        this.client = client;
        this.presigner = presigner;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        client.headBucket(
                HeadBucketRequest.builder().bucket(properties.bucket()).build());
    }

    @Override
    public String put(String scope, String contentType, InputStream body) {
        String type = MediaRules.requireAllowedType(contentType);
        byte[] clean = ImageSanitizer.strip(type, MediaRules.readLimited(body));
        String objectKey = "proofs/" + scope + "/" + UUID.randomUUID();
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(objectKey)
                            .contentType(type)
                            .build(),
                    RequestBody.fromBytes(clean));
        } catch (SdkException e) {
            throw new StorageUnavailableException("object store unavailable", e);
        }
        return objectKey;
    }

    @Override
    public URI presignView(String objectKey) {
        try {
            var request = GetObjectPresignRequest.builder()
                    .signatureDuration(properties.viewUrlTtl())
                    .getObjectRequest(GetObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(objectKey)
                            .build())
                    .build();
            return URI.create(presigner.presignGetObject(request).url().toString());
        } catch (SdkException e) {
            throw new StorageUnavailableException("object store unavailable", e);
        }
    }
}
