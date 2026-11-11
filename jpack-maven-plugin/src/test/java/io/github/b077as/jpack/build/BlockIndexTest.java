package io.github.b077as.jpack.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockIndexTest {

    @TempDir
    Path dir;

    /** The same input as the stub's test `the_index_of_known_data_is_stable`, whose index hashes to the value below. */
    @Test
    void theIndexOfKnownDataIsByteForByteWhatTheStubExpects() throws Exception {
        byte[] data = new byte[5000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ((i * 31 + 7) % 251);
        }
        Path file = dir.resolve("data.bin");
        Files.write(file, data);

        byte[] index = BlockIndex.build(file, 1024);

        assertEquals("8753fdd0036bce58444b35969e120bb4f5adf44e5920920f81c63fe4e29cfe5d",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(index)));
    }

    @Test
    void theHeaderDescribesTheFileAndEveryBlockHasItsEntry() throws Exception {
        Path file = dir.resolve("data.bin");
        Files.write(file, new byte[10_000]);

        ByteBuffer index = ByteBuffer.wrap(BlockIndex.build(file, 4096)).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(0x5842504A, index.getInt(0), "JPBX");
        assertEquals(1, index.get(4));
        assertEquals(4096, index.getInt(8));
        assertEquals(10_000, index.getLong(12));
        assertEquals(3, index.getInt(20));
        assertEquals(24 + 3 * 20, index.capacity());
    }

    @Test
    void anEmptyFileHasNoBlocksAndABadBlockSizeIsRefused() throws Exception {
        Path file = dir.resolve("empty.bin");
        Files.write(file, new byte[0]);
        assertEquals(24, BlockIndex.build(file, 1024).length);
        assertThrows(IllegalArgumentException.class, () -> BlockIndex.build(file, 100));
    }
}
