package dev.cloudlite.s3.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cloudlite.s3.domain.MultipartUpload;
import dev.cloudlite.s3.domain.UploadStatus;
import dev.cloudlite.s3.repository.MultipartUploadRepository;
import dev.cloudlite.s3.repository.ObjectRepository;
import dev.cloudlite.s3.repository.UploadPartRepository;
import dev.cloudlite.s3.service.MultipartService;
import dev.cloudlite.s3.service.UploadCleaner;
import dev.cloudlite.s3.storage.BlobEntry;
import dev.cloudlite.s3.storage.BlobStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MultipartReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    private MultipartUploadRepository uploads;
    private UploadPartRepository parts;
    private ObjectRepository objects;
    private MultipartService multipart;
    private UploadCleaner cleaner;
    private BlobStore store;
    private SimpleMeterRegistry registry;
    private MultipartReconciler reconciler;

    @BeforeEach
    void setUp() {
        uploads = mock(MultipartUploadRepository.class);
        parts = mock(UploadPartRepository.class);
        objects = mock(ObjectRepository.class);
        multipart = mock(MultipartService.class);
        cleaner = mock(UploadCleaner.class);
        store = mock(BlobStore.class);
        registry = new SimpleMeterRegistry();
        reconciler = new MultipartReconciler(uploads, parts, objects, multipart, cleaner, store, registry,
            Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(24), Duration.ofHours(1));
    }

    @Test
    void metricsAreRegisteredAtZeroBeforeAnyRun() {
        assertThat(registry.get("s3.reconciler.uploads.expired").counter().count()).isZero();
        assertThat(registry.get("s3.reconciler.blobs.deleted").counter().count()).isZero();
        assertThat(registry.get("s3.reconciler.runs").tag("outcome", "ok").counter().count()).isZero();
        assertThat(registry.get("s3.reconciler.runs").tag("outcome", "partial").counter().count()).isZero();
        assertThat(registry.find("s3.multipart.uploads.in_progress").gauge()).isNotNull();
    }

    @Test
    void expiresStaleInProgressUploadsWithTheTtlCutoff() {
        MultipartUpload stale = new MultipartUpload(UUID.randomUUID(), "photos", "a", "text/plain");
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusHours(24);
        when(uploads.findByStatusAndUpdatedAtBefore(UploadStatus.IN_PROGRESS, cutoff)).thenReturn(List.of(stale));
        when(multipart.expire(stale.getUploadId(), cutoff)).thenReturn(true);

        ReconcileReport report = reconciler.runOnce();

        assertThat(report.expired()).isEqualTo(1);
        assertThat(registry.get("s3.reconciler.uploads.expired").counter().count()).isEqualTo(1);
    }

    @Test
    void prunesUnreferencedCompletedAndOldAbortedUploads() {
        UUID completed = UUID.randomUUID();
        MultipartUpload aborted = new MultipartUpload(UUID.randomUUID(), "photos", "b", "text/plain");
        when(uploads.findUnreferencedCompletedIds()).thenReturn(List.of(completed));
        when(uploads.findByStatusAndUpdatedAtBefore(eq(UploadStatus.ABORTED), any())).thenReturn(List.of(aborted));

        ReconcileReport report = reconciler.runOnce();

        assertThat(report.pruned()).isEqualTo(2);
        verify(cleaner).discardQuietly(completed);
        verify(cleaner).discardQuietly(aborted.getUploadId());
    }

    @Test
    void garbageCollectsOnlyOldUnreferencedBlobsAndOldTempFiles() {
        UUID liveObject = UUID.randomUUID();
        UUID livePart = UUID.randomUUID();
        UUID oldOrphan = UUID.randomUUID();
        UUID freshOrphan = UUID.randomUUID();
        Instant old = NOW.minus(Duration.ofHours(2));
        Instant fresh = NOW.minus(Duration.ofMinutes(5));
        when(objects.findAllStorageIds()).thenReturn(List.of(liveObject));
        when(parts.findAllStorageIds()).thenReturn(List.of(livePart));
        when(store.entries()).thenReturn(List.of(
            new BlobEntry(liveObject.toString(), liveObject, false, old),
            new BlobEntry(livePart.toString(), livePart, false, old),
            new BlobEntry(oldOrphan.toString(), oldOrphan, false, old),
            new BlobEntry(freshOrphan.toString(), freshOrphan, false, fresh),
            new BlobEntry("x.y.tmp", null, true, old),
            new BlobEntry("z.w.tmp", null, true, fresh),
            new BlobEntry("README", null, false, old)));

        ReconcileReport report = reconciler.runOnce();

        assertThat(report.blobsDeleted()).isEqualTo(2);
        verify(store).deleteEntry(oldOrphan.toString());
        verify(store).deleteEntry("x.y.tmp");
        verify(store, never()).deleteEntry(liveObject.toString());
        verify(store, never()).deleteEntry(livePart.toString());
        verify(store, never()).deleteEntry(freshOrphan.toString());
        verify(store, never()).deleteEntry("z.w.tmp");
        verify(store, never()).deleteEntry("README");
    }

    @Test
    void aFailingStepDoesNotStopTheOthers() {
        when(uploads.findByStatusAndUpdatedAtBefore(eq(UploadStatus.IN_PROGRESS), any()))
            .thenThrow(new RuntimeException("db hiccup"));
        UUID orphan = UUID.randomUUID();
        when(store.entries()).thenReturn(List.of(
            new BlobEntry(orphan.toString(), orphan, false, NOW.minus(Duration.ofHours(2)))));

        ReconcileReport report = reconciler.runOnce();

        assertThat(report.failedSteps()).containsExactly("expire");
        verify(store).deleteEntry(orphan.toString());
        assertThat(registry.get("s3.reconciler.runs").tag("outcome", "partial").counter().count()).isEqualTo(1);
    }
}
