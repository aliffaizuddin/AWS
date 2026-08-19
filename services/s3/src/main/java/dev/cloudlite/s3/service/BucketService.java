package dev.cloudlite.s3.service;

import dev.cloudlite.s3.domain.Bucket;
import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.repository.BucketRepository;
import dev.cloudlite.s3.repository.ObjectRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class BucketService {

    private final BucketRepository buckets;
    private final ObjectRepository objects;

    public BucketService(BucketRepository buckets, ObjectRepository objects) {
        this.buckets = buckets;
        this.objects = objects;
    }

    public void create(String name) {
        if (name == null || name.isEmpty() || name.equals("healthz")) {
            throw new S3ApiException(S3ErrorCode.INVALID_BUCKET_NAME, name);
        }
        if (buckets.existsById(name)) {
            throw new S3ApiException(S3ErrorCode.BUCKET_ALREADY_EXISTS, name);
        }
        buckets.save(new Bucket(name));
    }

    public List<Bucket> list() {
        return buckets.findAllByOrderByNameAsc();
    }

    public boolean exists(String name) {
        return buckets.existsById(name);
    }

    public void delete(String name) {
        if (objects.existsByIdBucketName(name)) {
            throw new S3ApiException(S3ErrorCode.BUCKET_NOT_EMPTY, name);
        }
        if (!buckets.existsById(name)) {
            throw new S3ApiException(S3ErrorCode.NO_SUCH_BUCKET, name);
        }
        buckets.deleteById(name);
    }
}
