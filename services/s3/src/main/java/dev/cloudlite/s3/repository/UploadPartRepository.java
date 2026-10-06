package dev.cloudlite.s3.repository;

import dev.cloudlite.s3.domain.UploadPart;
import dev.cloudlite.s3.domain.UploadPartId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UploadPartRepository extends JpaRepository<UploadPart, UploadPartId> {

    List<UploadPart> findByIdUploadIdOrderByIdPartNumberAsc(UUID uploadId);

    @Query("select p.storageId from UploadPart p")
    List<UUID> findAllStorageIds();
}
