package com.open.spring.mvc.S3uploads;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

// TODO: Change the BUCKET NAME from pages-assignments to something else

@Service
@Slf4j
// @ConditionalOnProperty(name = "file.storage-type", havingValue = "prod")
public class S3FileHandler implements FileHandler {

    @Value("${aws.s3.bucket-name}")
    private String bucketName;

    @Value("${aws.s3.access-key-id}")
    private String accessKey;

    @Value("${aws.s3.secret-access-key}")
    private String secretKey;

    @Value("${aws.s3.region}")
    private String region;

    // Local development usually has no AWS keys. Without this fallback every call
    // below fails, so group chat history, edits and uploads can't be tried locally.
    // Set it to an empty value to turn the fallback off.
    @Value("${file.local-fallback-dir:volumes/uploads}")
    private String localFallbackDir;

    private S3Client s3Client;

    // Set only when S3 isn't configured and the local fallback is on.
    private Path localRoot;

    @PostConstruct
    public void init() {
        if (isBlank(accessKey) || isBlank(secretKey) || isBlank(region) || isBlank(bucketName)) {
            if (isBlank(localFallbackDir)) {
                log.warn("S3 is disabled: missing AWS credentials/region/bucket. Upload API will return errors until configured.");
            } else {
                localRoot = Paths.get(localFallbackDir.trim()).toAbsolutePath().normalize();
                log.warn("S3 is not configured (missing AWS credentials/region/bucket): storing files on local disk under {}. Local development only.", localRoot);
            }
            return;
        }

        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey.trim(), secretKey.trim());

        this.s3Client = S3Client.builder()
                .region(Region.of(region.trim()))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .build();
    }

    @Override
    public String uploadFile(String base64Data, String filename, String uid) {
        if (s3Client == null) {
            if (localRoot != null) {
                return localUpload(generateKey(uid, filename), base64Data) ? filename : null;
            }
            log.warn("S3 upload attempted but S3 client is not configured.");
            return null;
        }
        String key = generateKey(uid, filename);
        System.out.println("S3 Upload: " + key);

        try {
            byte[] fileData = Base64.getDecoder().decode(base64Data);

            PutObjectRequest putOb = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            s3Client.putObject(putOb, RequestBody.fromBytes(fileData));

            return filename;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public String decodeFile(String uid, String filename) {
        if (s3Client == null) {
            if (localRoot != null) {
                return localDecode(generateKey(uid, filename));
            }
            log.warn("S3 download attempted but S3 client is not configured.");
            return null;
        }
        String key = generateKey(uid, filename);
        System.out.println("S3 Download: " + key);

        try {
            GetObjectRequest getOb = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(getOb);
            byte[] data = objectBytes.asByteArray();

            return Base64.getEncoder().encodeToString(data);
        } catch (Exception e) {
            System.err.println("S3 Download Error for key: " + key);
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public boolean deleteFiles(String uid) {
        if (s3Client == null) {
            if (localRoot != null) {
                return localDelete(uid + "/");
            }
            log.warn("S3 delete attempted but S3 client is not configured.");
            return false;
        }
        String prefix = uid + "/";
        System.out.println("S3 Delete Prefix: " + prefix);

        try {
            // 1. List objects to delete
            ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix)
                    .build();

            ListObjectsV2Response listRes = s3Client.listObjectsV2(listReq);

            if (listRes.contents().isEmpty()) {
                return true;
            }

            // 2. Prepare batch delete
            List<ObjectIdentifier> objectsToDelete = listRes.contents().stream()
                    .map(s3Object -> ObjectIdentifier.builder().key(s3Object.key()).build())
                    .collect(Collectors.toList());

            DeleteObjectsRequest deleteReq = DeleteObjectsRequest.builder()
                    .bucket(bucketName)
                    .delete(Delete.builder().objects(objectsToDelete).build())
                    .build();

            s3Client.deleteObjects(deleteReq);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public List<String> listFiles(String prefix) {
        if (s3Client == null) {
            if (localRoot != null) {
                return localList(prefix);
            }
            log.warn("S3 list attempted but S3 client is not configured.");
            return new ArrayList<>();
        }

        try {
            ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix)
                    .build();

            ListObjectsV2Response listRes = s3Client.listObjectsV2(listReq);

            return listRes.contents().stream()
                    .map(s3Object -> s3Object.key())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    @Override
    public boolean fileExists(String uid, String filename) {
        if (s3Client == null) {
            if (localRoot != null) {
                return localExists(generateKey(uid, filename));
            }
            log.warn("S3 fileExists check attempted but S3 client is not configured.");
            return false;
        }
        String key = generateKey(uid, filename);
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String generateKey(String uid, String filename) {
        return uid + "/" + filename;
    }

    // --- Local disk fallback: same keys as S3, stored as files under localRoot ---

    // S3 keys are flat strings, so a key could hold "../". Refuse anything that
    // would land outside the storage folder.
    private Path localPath(String key) {
        Path path = localRoot.resolve(key).normalize();
        if (!path.startsWith(localRoot)) {
            throw new IllegalArgumentException("Key leaves the local storage folder: " + key);
        }
        return path;
    }

    private boolean localUpload(String key, String base64Data) {
        try {
            Path path = localPath(key);
            // A key ending in "/" is S3's empty-folder placeholder.
            if (key.endsWith("/")) {
                Files.createDirectories(path);
            } else {
                Files.createDirectories(path.getParent());
                Files.write(path, Base64.getDecoder().decode(base64Data));
            }
            return true;
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Local upload failed for key {}: {}", key, e.getMessage());
            return false;
        }
    }

    private String localDecode(String key) {
        try {
            Path path = localPath(key);
            if (!Files.isRegularFile(path)) {
                return null;
            }
            return Base64.getEncoder().encodeToString(Files.readAllBytes(path));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Local download failed for key {}: {}", key, e.getMessage());
            return null;
        }
    }

    private boolean localDelete(String prefix) {
        try {
            Path dir = localPath(prefix);
            if (dir.equals(localRoot)) {
                throw new IllegalArgumentException("Refusing to delete the whole storage folder");
            }
            if (!Files.exists(dir)) {
                return true;
            }
            try (Stream<Path> paths = Files.walk(dir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
            return true;
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Local delete failed for prefix {}: {}", prefix, e.getMessage());
            return false;
        }
    }

    // Lists files only, like S3 lists objects; folder placeholders are skipped
    // (callers already filter those out of S3 results).
    private List<String> localList(String prefix) {
        if (!Files.isDirectory(localRoot)) {
            return new ArrayList<>();
        }
        try (Stream<Path> paths = Files.walk(localRoot)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> localRoot.relativize(path).toString().replace('\\', '/'))
                    .filter(key -> key.startsWith(prefix))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Local list failed for prefix {}: {}", prefix, e.getMessage());
            return new ArrayList<>();
        }
    }

    private boolean localExists(String key) {
        try {
            Path path = localPath(key);
            return key.endsWith("/") ? Files.isDirectory(path) : Files.isRegularFile(path);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
