package io.github.b077as.jpack.build;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * The block index published next to an AppImage, which lets an installed AppImage fetch only the blocks that changed.
 * The format is described in the stub's {@code delta} module; this writer and that reader must agree byte for byte.
 *
 * <p>Per block of the file: the rsync checksum ({@code a} = sum of the bytes, {@code b} = sum of byte times distance to the
 * end of the block, both modulo 65536, as {@code a | b << 16}) and the first 16 bytes of the block's SHA-256. The last block
 * is padded with zeros to a full block for both.
 */
public final class BlockIndex {

    /** Small enough that a changed jar leaves most blocks reusable, large enough for an index of about 1% of the file. */
    public static final int DEFAULT_BLOCK_SIZE = 16 * 1024;

    private BlockIndex() {
    }

    public static byte[] build(Path file, int blockSize) throws IOException {
        if (blockSize < 512 || blockSize > (1 << 22)) {
            throw new IllegalArgumentException("block size out of range: " + blockSize);
        }
        long size = Files.size(file);
        long count = (size + blockSize - 1) / blockSize;
        if (count > Integer.MAX_VALUE) {
            throw new IOException("file too large for a block index");
        }
        ByteBuffer out = ByteBuffer.allocate(24 + 20 * (int) count).order(ByteOrder.LITTLE_ENDIAN);
        out.put("JPBX".getBytes(StandardCharsets.US_ASCII)).put((byte) 1).put(new byte[3]);
        out.putInt(blockSize).putLong(size).putInt((int) count);

        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] block = new byte[blockSize];
        try (InputStream in = Files.newInputStream(file)) {
            for (long i = 0; i < count; i++) {
                int filled = in.readNBytes(block, 0, blockSize);
                if (filled < blockSize) {
                    Arrays.fill(block, filled, blockSize, (byte) 0);
                }
                out.putInt(weak(block));
                out.put(sha.digest(block), 0, 16);
            }
        }
        return out.array();
    }

    static int weak(byte[] block) {
        int a = 0;
        int b = 0;
        int length = block.length;
        for (int i = 0; i < length; i++) {
            int x = block[i] & 0xFF;
            a += x;
            b += (length - i) * x;
        }
        return (a & 0xFFFF) | ((b & 0xFFFF) << 16);
    }
}
