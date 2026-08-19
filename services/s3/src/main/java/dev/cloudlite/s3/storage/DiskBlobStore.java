package dev.cloudlite.s3.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DiskBlobStore implements BlobStore {

    private final Path dataDir;

    public DiskBlobStore(@Value("${s3.data-dir}") String dataDir) {
        this.dataDir = Path.of(dataDir);
        try {
            Files.createDirectories(this.dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException("storage: create data dir " + dataDir, e);
        }
    }

    private Path pathFor(UUID id) {
        return dataDir.resolve(id.toString());
    }

    @Override
    public void put(UUID id, InputStream in) {
        Path finalPath = pathFor(id);
        Path tmpPath = dataDir.resolve(id + ".tmp");
        try (FileChannel channel = FileChannel.open(tmpPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            in.transferTo(Channels.newOutputStream(channel));
            channel.force(true);
        } catch (IOException e) {
            deleteQuietly(tmpPath);
            throw new UncheckedIOException("storage: write " + id, e);
        }
        try {
            Files.move(tmpPath, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(tmpPath);
            throw new UncheckedIOException("storage: rename " + id, e);
        }
    }

    @Override
    public InputStream get(UUID id) {
        try {
            return Files.newInputStream(pathFor(id));
        } catch (NoSuchFileException e) {
            throw new BlobNotFoundException("storage: blob not found: " + id);
        } catch (IOException e) {
            throw new UncheckedIOException("storage: open " + id, e);
        }
    }

    @Override
    public void delete(UUID id) {
        try {
            Files.delete(pathFor(id));
        } catch (NoSuchFileException e) {
            throw new BlobNotFoundException("storage: blob not found: " + id);
        } catch (IOException e) {
            throw new UncheckedIOException("storage: remove " + id, e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup of a partially written temp file
        }
    }
}
