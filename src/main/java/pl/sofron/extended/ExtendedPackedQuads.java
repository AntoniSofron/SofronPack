package pl.sofron.extended;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * RenderPearl-portable, GPU-instanced quad payload. One 48-byte instance
 * replaces four 24-byte vertices (96 bytes) in the original distant renderer.
 *
 * Position is signed fixed-point in 1/32 blocks, preserving exact block Y
 * and 1/16-pixel vanilla model coordinates within ±1024 local blocks.
 * Atlas UVs use U16 normalized values for ordinary model faces, or the
 * original pair of bit-perfect packed atlas ranges for repeating scan faces.
 * Every RGB/alpha/emission bit is preserved.
 *
 * No global arrays, material-id lookups, GL-specific SSBOs or texel buffers.
 * This layout is consumed by the 26.3 RenderPearl instanced vertex binding
 * on both OpenGL and Vulkan.
 */
public final class ExtendedPackedQuads {
    public static final int QUAD_BYTES = 48;
    private static final int OLD_VERTEX_BYTES = ExtendedVoxelMesher.VERTEX_BYTES;
    private static final int OLD_QUAD_BYTES = 4 * OLD_VERTEX_BYTES;
    private static final float POSITION_SCALE = 32.0f;
    private static final float UV_SCALE = 65535f;

    private ExtendedPackedQuads() {}

    public static ByteBuffer pack(ByteBuffer source) {return pack(source,null);}

    public static ByteBuffer pack(ByteBuffer source,LodRgbLighting lighting) {
        int start = source.position(), length = source.remaining();
        if (length % OLD_QUAD_BYTES != 0)
            throw new IllegalArgumentException("Unaligned legacy quad payload");
        int quads = length / OLD_QUAD_BYTES;
        ByteBuffer output = ByteBuffer.allocateDirect(Math.multiplyExact(quads, QUAD_BYTES))
                .order(ByteOrder.nativeOrder());
        for (int quad = 0; quad < quads; quad++) {
            int base = start + quad * OLD_QUAD_BYTES;
            int alpha = source.get(base + 20 + 3) & 255;
            boolean repeating = alpha < 255 && !(alpha >= 128 && alpha <= 143);
            for (int corner = 0; corner < 4; corner++) {
                int p = base + corner * OLD_VERTEX_BYTES;
                output.putShort(fixed(source.getFloat(p)));
                output.putShort(fixed(source.getFloat(p + 4)));
                output.putShort(fixed(source.getFloat(p + 8)));
            }
            if (repeating) {
                // Preserve all 32 bits of the encoded atlas endpoints,
                // including the special 15+15-bit packed-UV representation.
                output.putInt(source.getInt(base + 12));
                output.putInt(source.getInt(base + 16));
                output.putLong(0L);
            } else {
                for (int corner = 0; corner < 4; corner++) {
                    int p = base + corner * OLD_VERTEX_BYTES;
                    output.putShort(unorm(source.getFloat(p + 12)));
                    output.putShort(unorm(source.getFloat(p + 16)));
                }
            }
            int r = source.get(base + 20) & 255;
            int g = source.get(base + 21) & 255;
            int b = source.get(base + 22) & 255;
            output.putInt(r | g << 8 | b << 16 | alpha << 24);
            int rgb=0;
            if(lighting!=null){
                float x=0,y=0,z=0;
                for(int corner=0;corner<4;corner++){
                    int p=base+corner*OLD_VERTEX_BYTES;
                    x+=source.getFloat(p);y+=source.getFloat(p+4);z+=source.getFloat(p+8);
                }
                rgb=lighting.packed(x*.25f,y*.25f,z*.25f);
            }
            output.putInt(rgb | (repeating ? 1 : 0));
        }
        return output.flip();
    }

    private static short fixed(float coordinate) {
        int bits = Math.round(coordinate * POSITION_SCALE);
        if (bits < Short.MIN_VALUE || bits > Short.MAX_VALUE)
            throw new IllegalArgumentException("LOD vertex outside ±1024-block tile coordinate space: " + coordinate);
        return (short) bits;
    }

    private static short unorm(float value) {
        return (short) Math.round(Math.clamp(value, 0f, 1f) * UV_SCALE);
    }
}
