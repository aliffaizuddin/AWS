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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
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
        Path tmpPath = dataDir.resolve(id + "." + java.util.UUID.randomUUID() + ".tmp");
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

    @Override
    public List<BlobEntry> entries() {
        try (Stream<Path> files = Files.list(dataDir)) {
            return files.filter(Files::isRegularFile).map(this::entryFor).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("storage: list " + dataDir, e);
        }
    }

    private BlobEntry entryFor(Path file) {
        String name = file.getFileName().toString();
        Instant modified;
        try {
            modified = Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            modified = Instant.now(); // unreadable mtime: treat as fresh so GC never deletes it
        }
        boolean temp = name.endsWith(".tmp");
        UUID id = null;
        if (!temp) {
            try {
                UUID parsed = UUID.fromString(name);
                id = parsed.toString().equals(name) ? parsed : null;
            } catch (IllegalArgumentException e) {
                id = null;
            }
        }
        return new BlobEntry(name, id, temp, modified);
    }

    @Override
    public void deleteEntry(String fileName) {
        Path target = dataDir.resolve(fileName).normalize();
        if (!dataDir.toAbsolutePath().normalize().equals(target.toAbsolutePath().getParent())) {
            throw new IllegalArgumentException("storage: refusing to delete outside the data dir: " + fileName);
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new UncheckedIOException("storage: delete entry " + fileName, e);
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
