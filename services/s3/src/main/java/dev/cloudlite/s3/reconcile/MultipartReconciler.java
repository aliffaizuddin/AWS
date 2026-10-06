package dev.cloudlite.s3.reconcile;

import dev.cloudlite.s3.domain.MultipartUpload;
import dev.cloudlite.s3.domain.UploadStatus;
import dev.cloudlite.s3.repository.MultipartUploadRepository;
import dev.cloudlite.s3.repository.ObjectRepository;
import dev.cloudlite.s3.repository.UploadPartRepository;
import dev.cloudlite.s3.service.MultipartService;
import dev.cloudlite.s3.service.UploadCleaner;
import dev.cloudlite.s3.storage.BlobEntry;
import dev.cloudlite.s3.storage.BlobStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// Sweeps what best-effort cleanup misses: abandoned uploads, upload rows
// nothing references, and blob files no row points at. Assumes one S3 replica.
@Component
public class MultipartReconciler {

    private static final Logger log = LoggerFactory.getLogger(MultipartReconciler.class);

    private final MultipartUploadRepository uploads;
    private final UploadPartRepository parts;
    private final ObjectRepository objects;
    private final MultipartService multipart;
    private final UploadCleaner cleaner;
    private final BlobStore store;
    private final Clock clock;
    private final Duration ttl;
    private final Duration grace;
    private final Counter expiredCounter;
    private final Counter blobsDeletedCounter;
    private final Counter runsOk;
    private final Counter runsPartial;

    public MultipartReconciler(MultipartUploadRepository uploads, UploadPartRepository parts, ObjectRepository objects,
                               MultipartService multipart, UploadCleaner cleaner, BlobStore store,
                               MeterRegistry registry, Clock clock,
                               @Value("${s3.multipart.ttl:24h}") Duration ttl,
                               @Value("${s3.reconcile.grace:1h}") Duration grace) {
        this.uploads = uploads;
        this.parts = parts;
        this.objects = objects;
        this.multipart = multipart;
        this.cleaner = cleaner;
        this.store = store;
        this.clock = clock;
        this.ttl = ttl;
        this.grace = grace;
        // Registered up front so Prometheus sees a 0 sample before the first increment.
        this.expiredCounter = Counter.builder("s3.reconciler.uploads.expired").register(registry);
        this.blobsDeletedCounter = Counter.builder("s3.reconciler.blobs.deleted").register(registry);
        this.runsOk = Counter.builder("s3.reconciler.runs").tag("outcome", "ok").register(registry);
        this.runsPartial = Counter.builder("s3.reconciler.runs").tag("outcome", "partial").register(registry);
        Gauge.builder("s3.multipart.uploads.in_progress", uploads, r -> r.countByStatus(UploadStatus.IN_PROGRESS))
            .register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        runOnce();
    }

    public ReconcileReport runOnce() {
        List<String> failed = new ArrayList<>();
        int expired = step("expire", this::expireAbandoned, failed);
        int pruned = step("prune", this::pruneUploadRows, failed);
        int deleted = step("gc", this::collectGarbage, failed);
        (failed.isEmpty() ? runsOk : runsPartial).increment();
        log.info("s3: reconciler run: expired={} pruned={} blobsDeleted={} failedSteps={}", expired, pruned, deleted, failed);
        return new ReconcileReport(expired, pruned, deleted, List.copyOf(failed));
    }

    private int step(String name, IntSupplier body, List<String> failed) {
        try {
            return body.getAsInt();
        } catch (RuntimeException e) {
            log.warn("s3: reconciler step {} failed", name, e);
            failed.add(name);
            return 0;
        }
    }

    private int expireAbandoned() {
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(clock.instant(), clock.getZone()).minus(ttl);
        int n = 0;
        for (MultipartUpload u : uploads.findByStatusAndUpdatedAtBefore(UploadStatus.IN_PROGRESS, cutoff)) {
            if (multipart.expire(u.getUploadId(), cutoff)) {
                log.debug("s3: reconciler expired upload {}", u.getUploadId());
                n++;
            }
        }
        expiredCounter.increment(n);
        return n;
    }

    private int pruneUploadRows() {
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(clock.instant(), clock.getZone()).minus(ttl);
        List<UUID> ids = new ArrayList<>(uploads.findUnreferencedCompletedIds());
        uploads.findByStatusAndUpdatedAtBefore(UploadStatus.ABORTED, cutoff).forEach(u -> ids.add(u.getUploadId()));
        ids.forEach(cleaner::discardQuietly);
        return ids.size();
    }

    private int collectGarbage() {
        // Live set is read before listing files; anything written after is
        // younger than the grace period and left alone.
        Set<UUID> live = new HashSet<>(objects.findAllStorageIds());
        live.addAll(parts.findAllStorageIds());
        Instant cutoff = clock.instant().minus(grace);
        int n = 0;
        for (BlobEntry e : store.entries()) {
            if (!e.lastModified().isBefore(cutoff)) {
                continue;
            }
            boolean garbage = e.temp() || (e.blobId() != null && !live.contains(e.blobId()));
            if (!garbage) {
                continue;
            }
            try {
                store.deleteEntry(e.fileName());
                log.debug("s3: reconciler deleted {}", e.fileName());
                n++;
            } catch (RuntimeException ex) {
                log.warn("s3: reconciler could not delete {}", e.fileName(), ex);
            }
        }
        blobsDeletedCounter.increment(n);
        return n;
    }
}
