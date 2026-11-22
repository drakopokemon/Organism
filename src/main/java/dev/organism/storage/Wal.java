package dev.organism.storage;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.function.BiConsumer;

/**
 * Write-Ahead Log для шарда Storage.
 * Формат записи: [int len][long version][int dataLen][byte[] data]
 */
public final class Wal implements Closeable {
    private final FileChannel channel;
    private final Path path;

    public Wal(Path dir, int shard) throws IOException {
        Files.createDirectories(dir);
        this.path = dir.resolve("shard-" + shard + ".wal");
        this.channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE, StandardOpenOption.READ);
    }

    public synchronized void append(long version, byte[] data) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(4 + 8 + 4 + data.length);
        buf.putInt(8 + 4 + data.length); // total entry length
        buf.putLong(version);
        buf.putInt(data.length);
        buf.put(data);
        buf.flip();
        while (buf.hasRemaining()) channel.write(buf);
        channel.force(false);
    }

    public synchronized void replay(BiConsumer<Long, byte[]> consumer) throws IOException {
        channel.position(0);
        ByteBuffer lenBuf = ByteBuffer.allocate(4);
        while (channel.read(lenBuf) == 4) {
            lenBuf.flip();
            int entryLen = lenBuf.getInt();
            if (entryLen < 12 || entryLen > 10_000_000) break; // corruption or EOF
            
            ByteBuffer entryBuf = ByteBuffer.allocate(entryLen);
            int read = 0;
            while (read < entryLen) {
                int r = channel.read(entryBuf);
                if (r == -1) return; // unexpected EOF
                read += r;
            }
            entryBuf.flip();
            long version = entryBuf.getLong();
            int dataLen = entryBuf.getInt();
            byte[] data = new byte[dataLen];
            entryBuf.get(data);
            consumer.accept(version, data);
            lenBuf.clear();
        }
    }

    public synchronized void truncate() throws IOException {
        channel.truncate(0);
        channel.position(0);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
