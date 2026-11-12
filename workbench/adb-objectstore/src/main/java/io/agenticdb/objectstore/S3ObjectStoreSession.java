package io.agenticdb.objectstore;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * AWS S3 及 S3 兼容端点（MinIO / LocalStack）适配器 —— AWS SDK v2。
 *
 * <p>endpoint 非空时走 {@code endpointOverride}，并默认开 path-style 访问
 * （MinIO / LocalStack 都不支持 virtual-host 风格）。region 缺省 {@code us-east-1}。
 * 有 sessionToken 时用 {@link AwsSessionCredentials}（STS 临时凭据）。
 *
 * <p>⚠️ 真实云端连通性属于规划 P4-3 的 <b>R2 环境阻塞项</b>：本类在编译期由 SDK 类型钉住，
 * 行为门禁跑在 {@link InMemoryObjectStoreSession} 上，不会假装连过云账号。
 */
public final class S3ObjectStoreSession extends AbstractObjectStoreSession {

    private final S3Client client;
    private final StaticCredentialsProvider credentialsProvider;
    private final Region region;
    private final URI endpoint;
    private final boolean pathStyle;

    public S3ObjectStoreSession(ObjectStoreCredentials c) {
        super(c);
        if (!c.complete()) {
            throw new IllegalArgumentException("S3 requires accessKey and secretKey");
        }
        AwsCredentials creds = c.sessionToken().isBlank()
                ? AwsBasicCredentials.create(c.accessKey(), c.secretKey())
                : AwsSessionCredentials.create(c.accessKey(), c.secretKey(), c.sessionToken());
        this.credentialsProvider = StaticCredentialsProvider.create(creds);
        this.region = Region.of(c.region().isBlank() ? "us-east-1" : c.region());
        this.endpoint = c.endpoint().isBlank() ? null : URI.create(c.endpoint());
        this.pathStyle = c.pathStyle();

        S3ClientBuilder builder = S3Client.builder()
                .credentialsProvider(credentialsProvider)
                .region(region)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyle).build());
        if (endpoint != null) {
            builder.endpointOverride(endpoint);
        }
        this.client = builder.build();
    }

    @Override
    public List<Bucket> listBuckets() {
        List<Bucket> out = new ArrayList<>();
        for (software.amazon.awssdk.services.s3.model.Bucket b : client.listBuckets().buckets()) {
            Instant created = b.creationDate();
            out.add(new Bucket(b.name(), created == null ? Instant.EPOCH : created));
        }
        return out;
    }

    @Override
    protected List<ObjectMeta> flatList(String bucket, String prefix) {
        List<ObjectMeta> out = new ArrayList<>();
        String token = null;
        do {
            ListObjectsV2Request.Builder req = ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix);
            if (token != null) {
                req.continuationToken(token);
            }
            ListObjectsV2Response page = client.listObjectsV2(req.build());
            for (S3Object o : page.contents()) {
                out.add(new ObjectMeta(o.key(), o.size() == null ? 0L : o.size(),
                        o.lastModified(), SdkSupport.safe(o.eTag()),
                        SdkSupport.safe(o.storageClassAsString())));
            }
            token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
        } while (token != null && !token.isBlank());
        return out;
    }

    @Override
    public byte[] getObject(String bucket, String key) {
        return client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    @Override
    public ObjectMeta putObject(String bucket, String key, byte[] data, String contentType) {
        PutObjectRequest.Builder req = PutObjectRequest.builder().bucket(bucket).key(key);
        if (contentType != null && !contentType.isBlank()) {
            req.contentType(contentType);
        }
        client.putObject(req.build(), RequestBody.fromBytes(data));
        return metadata(bucket, key);
    }

    @Override
    public void deleteObject(String bucket, String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public ObjectMeta metadata(String bucket, String key) {
        HeadObjectResponse h = client.headObject(
                HeadObjectRequest.builder().bucket(bucket).key(key).build());
        return new ObjectMeta(key, h.contentLength() == null ? 0L : h.contentLength(),
                h.lastModified(), SdkSupport.safe(h.eTag()),
                SdkSupport.safe(h.storageClassAsString()));
    }

    @Override
    public String presignUrl(String bucket, String key, long ttlSeconds) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .credentialsProvider(credentialsProvider)
                .region(region)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyle).build());
        if (endpoint != null) {
            builder.endpointOverride(endpoint);
        }
        try (S3Presigner presigner = builder.build()) {
            return presigner.presignGetObject(GetObjectPresignRequest.builder()
                            .signatureDuration(Duration.ofSeconds(Math.max(1L, ttlSeconds)))
                            .getObjectRequest(GetObjectRequest.builder()
                                    .bucket(bucket).key(key).build())
                            .build())
                    .url().toString();
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
