package dev.cloudlite.s3.repository;

import dev.cloudlite.s3.domain.ObjectMetadata;
import dev.cloudlite.s3.domain.ObjectMetadataId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ObjectRepository extends JpaRepository<ObjectMetadata, ObjectMetadataId> {

    boolean existsByIdBucketName(String bucketName);

    @Query("select o.storageId from ObjectMetadata o where o.storageId is not null")
    List<UUID> findAllStorageIds();
}
