package dev.cloudlite.s3.repository;

import dev.cloudlite.s3.domain.ObjectMetadata;
import dev.cloudlite.s3.domain.ObjectMetadataId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ObjectRepository extends JpaRepository<ObjectMetadata, ObjectMetadataId> {

    boolean existsByIdBucketName(String bucketName);
}
