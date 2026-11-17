package com.luminaread.service;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Prevents two app instances from writing the same profile at once. */
public final class ProfileLock implements AutoCloseable {
    private static final java.util.Set<Path> OPEN = new java.util.HashSet<>();
    private final Path profile;
    private final FileChannel channel;

    private ProfileLock(Path profile, FileChannel channel) {
        this.profile = profile;
        this.channel = channel;
    }

    public static synchronized ProfileLock acquire(Path profile) throws IOException {
        Files.createDirectories(profile);
        Path canonical = profile.toRealPath();
        // On POSIX closing another descriptor for this file can release this JVM's existing lock.
        if (OPEN.contains(canonical)) throw busy();
        FileChannel channel = FileChannel.open(canonical.resolve("library.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw busy();
            OPEN.add(canonical);
            return new ProfileLock(canonical, channel);
        } catch (OverlappingFileLockException ex) {
            channel.close();
            throw busy();
        } catch (IOException | RuntimeException ex) {
            try {
                channel.close();
            } catch (IOException closeError) {
                ex.addSuppressed(closeError);
            }
            throw ex;
        }
    }

    private static IOException busy() {
        return new IOException("LuminaRead уже использует эту библиотеку. Закройте другое окно приложения.");
    }

    @Override public void close() throws IOException {
        synchronized (ProfileLock.class) {
            if (!channel.isOpen()) return;
            try {
                channel.close();
            } finally {
                OPEN.remove(profile);
            }
        }
    }
}
