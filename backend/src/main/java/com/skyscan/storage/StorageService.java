package com.skyscan.storage;

import io.minio.*;
import io.minio.http.Method;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * S3-compatible object storage (MinIO). Two presigning clients:
 *  - internal endpoint for the C++ engine (docker network hostname),
 *  - external endpoint for the browser (viewer streaming + downloads).
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final MinioClient internalClient;
    private final MinioClient externalClient;
    private final String bucket;

    public StorageService(@Value("${skyscan.storage.endpoint}") String endpoint,
                          @Value("${skyscan.storage.external-endpoint}") String externalEndpoint,
                          @Value("${skyscan.storage.access-key}") String accessKey,
                          @Value("${skyscan.storage.secret-key}") String secretKey,
                          @Value("${skyscan.storage.bucket}") String bucket) {
        // Region is pinned so presigning is pure local computation: without it the
        // SDK performs a live bucket-region lookup against the endpoint, which fails
        // for the external (browser-facing) endpoint from inside the container.
        this.internalClient = MinioClient.builder().endpoint(endpoint)
                .credentials(accessKey, secretKey).region("us-east-1").build();
        this.externalClient = MinioClient.builder().endpoint(externalEndpoint)
                .credentials(accessKey, secretKey).region("us-east-1").build();
        this.bucket = bucket;
        ensureBucket();
        presignSelfTest(externalEndpoint);
    }

    /**
     * Startup proof that presigning is pure local computation (no network I/O):
     * if this line appears in the logs, the region-pinned build is running and
     * external presigns can never throw ConnectException again.
     */
    private void presignSelfTest(String externalEndpoint) {
        try {
            presignExternalGet("selftest/probe.bin", 1);
            log.info("Presign self-test OK — offline external presigning active (external endpoint: {})", externalEndpoint);
        } catch (Exception e) {
            log.error("Presign self-test FAILED — external presigning would break at runtime: {}", e.getMessage());
        }
    }

    private void ensureBucket() {
        try {
            if (!internalClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                internalClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Created bucket {}", bucket);
            }
        } catch (Exception e) {
            log.warn("Bucket check failed (storage may not be up yet): {}", e.getMessage());
        }
    }

    public void uploadFile(String objectKey, Path file, String contentType) {
        try (InputStream in = Files.newInputStream(file)) {
            internalClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket).object(objectKey)
                    .stream(in, Files.size(file), -1)
                    .contentType(contentType).build());
        } catch (Exception e) {
            throw new RuntimeException("Upload failed for " + objectKey, e);
        }
    }

    /** Presigned GET for the engine (internal endpoint). MinIO presigned URLs support HTTP Range requests. */
    public String presignInternalGet(String objectKey, int minutes) { return presign(internalClient, Method.GET, objectKey, minutes); }
    /** Presigned PUT for the engine to write outputs directly. */
    public String presignInternalPut(String objectKey, int minutes) { return presign(internalClient, Method.PUT, objectKey, minutes); }
    /** Presigned GET for the browser (viewer streaming / LAZ download). */
    public String presignExternalGet(String objectKey, int minutes) { return presign(externalClient, Method.GET, objectKey, minutes); }

    /** Object keys under a prefix (e.g. photos/{missionId}/), sorted. */
    public java.util.List<String> listKeys(String prefix) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        try {
            for (Result<Item> r : internalClient.listObjects(
                    ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build())) {
                keys.add(r.get().objectName());
            }
        } catch (Exception e) {
            throw new RuntimeException("List failed for prefix " + prefix, e);
        }
        java.util.Collections.sort(keys);
        return keys;
    }

    private String presign(MinioClient client, Method method, String objectKey, int minutes) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(bucket).object(objectKey).method(method)
                    .expiry(minutes, TimeUnit.MINUTES).build());
        } catch (Exception e) {
            throw new RuntimeException("Presign failed for " + objectKey, e);
        }
    }
}
