package dev.cloudlite.s3.repository;

import dev.cloudlite.s3.domain.MultipartUpload;
import dev.cloudlite.s3.domain.UploadStatus;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface MultipartUploadRepository extends JpaRepository<MultipartUpload, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from MultipartUpload u where u.uploadId = :uploadId")
    Optional<MultipartUpload> findForUpdate(@Param("uploadId") UUID uploadId);

    List<MultipartUpload> findByBucketNameAndStatusOrderByObjectKeyAscInitiatedAtAsc(String bucketName, UploadStatus status);

    List<MultipartUpload> findByStatusAndUpdatedAtBefore(UploadStatus status, OffsetDateTime cutoff);

    boolean existsByBucketNameAndStatus(String bucketName, UploadStatus status);

    long countByStatus(UploadStatus status);

    @Query("select u.uploadId from MultipartUpload u"
        + " where u.status = dev.cloudlite.s3.domain.UploadStatus.COMPLETED"
        + " and not exists (select 1 from ObjectMetadata o where o.uploadId = u.uploadId)")
    List<UUID> findUnreferencedCompletedIds();

    // upload_parts rows go with ON DELETE CASCADE.
    @Transactional
    @Modifying
    @Query("delete from MultipartUpload u where u.bucketName = :bucketName"
        + " and u.status <> dev.cloudlite.s3.domain.UploadStatus.IN_PROGRESS")
    int deleteFinishedByBucketName(@Param("bucketName") String bucketName);
}
