package dev.cloudlite.s3.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.StreamUtils;

class DiskBlobStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void putThenGetReturnsTheSameBytes() throws Exception {
        DiskBlobStore store = new DiskBlobStore(tempDir.toString());
        UUID id = UUID.randomUUID();

        store.put(id, new ByteArrayInputStream("hello".getBytes()));

        byte[] read = StreamUtils.copyToByteArray(store.get(id));
        assertThat(new String(read)).isEqualTo("hello");
    }

    @Test
    void getOnAMissingIdThrowsBlobNotFoundException() {
        DiskBlobStore store = new DiskBlobStore(tempDir.toString());

        assertThatThrownBy(() -> store.get(UUID.randomUUID()))
            .isInstanceOf(BlobNotFoundException.class);
    }

    @Test
    void deleteOnAMissingIdThrowsBlobNotFoundException() {
        DiskBlobStore store = new DiskBlobStore(tempDir.toString());

        assertThatThrownBy(() -> store.delete(UUID.randomUUID()))
            .isInstanceOf(BlobNotFoundException.class);
    }

    @Test
    void putOverwritesAnExistingBlob() throws Exception {
        DiskBlobStore store = new DiskBlobStore(tempDir.toString());
        UUID id = UUID.randomUUID();

        store.put(id, new ByteArrayInputStream("first".getBytes()));
        store.put(id, new ByteArrayInputStream("second".getBytes()));

        byte[] read = StreamUtils.copyToByteArray(store.get(id));
        assertThat(new String(read)).isEqualTo("second");
    }

    @Test
    void deleteRemovesTheBlobSoASubsequentGetFails() throws Exception {
        DiskBlobStore store = new DiskBlobStore(tempDir.toString());
        UUID id = UUID.randomUUID();
        store.put(id, new ByteArrayInputStream("hello".getBytes()));

        store.delete(id);

        assertThatThrownBy(() -> store.get(id)).isInstanceOf(BlobNotFoundException.class);
    }

    @Test
    void entriesClassifyBlobsTempFilesAndStrangers() throws Exception {
        DiskBlobStore store = new DiskBlobStore(tempDir.toString());
        UUID id = UUID.randomUUID();
        store.put(id, new java.io.ByteArrayInputStream("x".getBytes()));
        java.nio.file.Files.writeString(tempDir.resolve(UUID.randomUUID() + "." + UUID.randomUUID() + ".tmp"), "t");
        java.nio.file.Files.writeString(tempDir.resolve("README"), "r");

        var entries = store.entries();

        assertThat(entries).hasSize(3);
        assertThat(entries).filteredOn(e -> id.equals(e.blobId())).singleElement()
            .satisfies(e -> assertThat(e.temp()).isFalse());
        assertThat(entries).filteredOn(BlobEntry::temp).singleElement()
            .satisfies(e -> assertThat(e.blobId()).isNull());
        assertThat(entries).filteredOn(e -> e.fileName().equals("README")).singleElement()
            .satisfies(e -> assertThat(e.blobId()).isNull());
    }

    @Test
    void deleteEntryRemovesTheFileAndRefusesPathsOutsideTheDataDir() throws Exception {
        DiskBlobStore store = new DiskBlobStore(tempDir.toString());
        java.nio.file.Files.writeString(tempDir.resolve("a.tmp"), "t");

        store.deleteEntry("a.tmp");

        assertThat(java.nio.file.Files.exists(tempDir.resolve("a.tmp"))).isFalse();
        assertThatThrownBy(() -> store.deleteEntry("../escape"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
