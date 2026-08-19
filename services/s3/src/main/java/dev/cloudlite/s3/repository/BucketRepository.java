package dev.cloudlite.s3.repository;

import dev.cloudlite.s3.domain.Bucket;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BucketRepository extends JpaRepository<Bucket, String> {

    List<Bucket> findAllByOrderByNameAsc();
}
