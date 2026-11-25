package pl.sofron.extended;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.DataFormatException;

/** Lossless full-resolution voxel codec. Legacy SFV4 zlib streams remain readable. */
public final class ExtendedVoxelCodec {
    private static final int GRID_AREA = 256;
    private static final int BITS = 1, COLUMN_RUNS = 2;
    private static final int HEADER = 6;
    private ExtendedVoxelCodec() {}

    public static byte[] encode(short[] cells, int paletteSize) {
        if (paletteSize < 1 || paletteSize > 32767 || cells.length % GRID_AREA != 0)
            throw new IllegalArgumentException("Invalid voxel volume");
        int bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
        byte[] legacy = new byte[cells.length * 2];
        byte[] packed = new byte[(cells.length * bits + 7) / 8];
        // Both target arrays are zero-initialized. Air above the surface does
        // not need conversion to bytes or bit-packing, but remains in the stream.
        int used = cells.length;
        while (used > 0 && cells[used - 1] == 0) used--;
        int accumulator = 0, count = 0, pos = 0;
        for (int i = 0; i < used; i++) {
            int value = cells[i] & 65535;
            if (value >= paletteSize) throw new IllegalArgumentException("Invalid palette index");
            legacy[i * 2] = (byte)value;
            legacy[i * 2 + 1] = (byte)(value >>> 8);
            accumulator |= value << count;
            count += bits;
            while (count >= 8) {
                packed[pos++] = (byte)accumulator;
                accumulator >>>= 8;
                count -= 8;
            }
        }
        if (count != 0) packed[pos] = (byte)accumulator;
        // Every candidate is exact; never store a larger stream than the
        // original fast zlib representation. No downsampling or eviction.
        byte[] best = deflate(legacy, Deflater.BEST_SPEED);
        byte[] bitstream = tagged(BITS, packed);
        if (bitstream.length < best.length) best = bitstream;
        ByteArrayOutputStream runs = new ByteArrayOutputStream();
        int height = cells.length / GRID_AREA;
        int activeHeight = (used + GRID_AREA - 1) / GRID_AREA;
        for (int column = 0; column < GRID_AREA; column++) {
            int y = 0;
            while (y < height) {
                int value = cells[y * GRID_AREA + column] & 65535;
                int end = y + 1;
                while (end < activeHeight && cells[end * GRID_AREA + column] == value) end++;
                if (end >= activeHeight && value == 0) end = height;
                varint(runs, end - y);
                varint(runs, value);
                y = end;
            }
            // Abort this candidate on very noisy volumes, not the chunk.
            if (runs.size() > legacy.length) return best;
        }
        byte[] columns = tagged(COLUMN_RUNS, runs.toByteArray());
        return columns.length < best.length ? columns : best;
    }

    public static boolean legacy(byte[] encoded) {
        return encoded.length != 0 && encoded[0] != 0;
    }

    public static short[] decode(byte[] encoded, int cellCount, int paletteSize) {
        if (encoded.length == 0 || cellCount < 1 || cellCount > 256 * 1024
                || cellCount % GRID_AREA != 0 || paletteSize < 1 || paletteSize > 32767)
            throw new IllegalArgumentException("Invalid voxel stream");
        short[] result = new short[cellCount];
        if (legacy(encoded)) {
            byte[] raw = inflate(encoded, 0, cellCount * 2);
            for (int i = 0; i < cellCount; i++) {
                int v = (raw[i * 2] & 255) | ((raw[i * 2 + 1] & 255) << 8);
                if (v >= paletteSize) throw new IllegalArgumentException("Invalid legacy palette index");
                result[i] = (short)v;
            }
            return result;
        }
        if (encoded.length <= HEADER) throw new IllegalArgumentException("Truncated voxel stream");
        int mode = encoded[1] & 255;
        int length = (encoded[2] & 255) << 24 | (encoded[3] & 255) << 16
                | (encoded[4] & 255) << 8 | (encoded[5] & 255);
        int bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
        if (mode == BITS) {
            if (length != (cellCount * bits + 7) / 8)
                throw new IllegalArgumentException("Invalid packed length");
            byte[] raw = inflate(encoded, HEADER, length);
            int accumulator = 0, count = 0, pos = 0, mask = (1 << bits) - 1;
            for (int i = 0; i < cellCount; i++) {
                while (count < bits) {
                    accumulator |= (raw[pos++] & 255) << count;
                    count += 8;
                }
                int v = accumulator & mask;
                if (v >= paletteSize) throw new IllegalArgumentException("Invalid packed palette index");
                result[i] = (short)v;
                accumulator >>>= bits;
                count -= bits;
            }
        } else if (mode == COLUMN_RUNS) {
            if (length < 1 || length > cellCount * 2)
                throw new IllegalArgumentException("Invalid run length");
            byte[] raw = inflate(encoded, HEADER, length);
            int[] offset = {0};
            int height = cellCount / GRID_AREA;
            for (int column = 0; column < GRID_AREA; column++) {
                int y = 0;
                while (y < height) {
                    int run = readVarint(raw, offset), v = readVarint(raw, offset);
                    if (run < 1 || run > height - y || v >= paletteSize)
                        throw new IllegalArgumentException("Invalid column run");
                    for (int end = y + run; y < end; y++) result[y * GRID_AREA + column] = (short)v;
                }
            }
            if (offset[0] != raw.length) throw new IllegalArgumentException("Trailing column data");
        } else throw new IllegalArgumentException("Unknown voxel codec");
        return result;
    }

    private static byte[] tagged(int mode, byte[] raw) {
        byte[] data = deflate(raw, 3);
        byte[] result = new byte[data.length + HEADER];
        result[1] = (byte)mode;
        for (int i = 0; i < 4; i++) result[2 + i] = (byte)(raw.length >>> (24 - i * 8));
        System.arraycopy(data, 0, result, HEADER, data.length);
        return result;
    }

    private static void varint(ByteArrayOutputStream out, int value) {
        while (value >= 128) { out.write((value & 127) | 128); value >>>= 7; }
        out.write(value);
    }

    private static int readVarint(byte[] raw, int[] pos) {
        int value = 0;
        for (int shift = 0; shift <= 14; shift += 7) {
            if (pos[0] >= raw.length) throw new IllegalArgumentException("Truncated column run");
            int b = raw[pos[0]++] & 255;
            value |= (b & 127) << shift;
            if ((b & 128) == 0) return value;
        }
        throw new IllegalArgumentException("Oversized column run");
    }

    private static byte[] deflate(byte[] raw, int level) {
        Deflater d = new Deflater(level);
        try {
            d.setInput(raw);
            d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!d.finished()) {
                int n = d.deflate(buffer);
                if (n == 0 && !d.finished()) throw new IllegalStateException("Zlib made no progress");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally { d.end(); }
    }

    private static byte[] inflate(byte[] encoded, int offset, int length) {
        byte[] raw = new byte[length];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(encoded, offset, encoded.length - offset);
            int pos = 0;
            while (pos < raw.length) {
                int n = inflater.inflate(raw, pos, raw.length - pos);
                if (n == 0) throw new IllegalArgumentException("Incomplete voxel stream");
                pos += n;
            }
            // Finish checksum processing even when the last output exactly
            // fills the buffer. Reject surplus bytes and corrupt streams.
            if (!inflater.finished() && inflater.inflate(new byte[1]) != 0)
                throw new IllegalArgumentException("Oversized voxel stream");
            if (!inflater.finished() || inflater.getRemaining() != 0)
                throw new IllegalArgumentException("Corrupt voxel stream");
            return raw;
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("Corrupt voxel stream", e);
        } finally { inflater.end(); }
    }
}
