package pl.sofron.light.runtime;

import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DynamicVoxelFieldTest {
    @Test
    void overlappingSourcesKeepStrongestChannelsAndEarlierSnapshot() {
        var red = new DynamicVoxelField.Emitter(0, 0, 0, 4, 255, 0, 0, 3);
        var blue = new DynamicVoxelField.Emitter(2, 0, 0, 4, 0, 0, 255, 3);
        var field = DynamicVoxelField.propagate(0, 0, 0, List.of(red, blue), (x,y,z) -> false);
        assertEquals(0x03bf00bf, field.sample(1, 0, 0));
        var blocked = DynamicVoxelField.propagate(0, 0, 0, List.of(red, blue), (x,y,z) -> x == 1);
        assertEquals(0, blocked.sample(1, 0, 0));
        assertEquals(0x03bf00bf, field.sample(1, 0, 0));
        ByteBuffer bytes = ByteBuffer.allocate(24 + DynamicVoxelField.CELLS * 4);
        bytes.position(8);
        DynamicVoxelField.put(bytes, field);
        assertEquals(bytes.capacity(), bytes.position());
        assertEquals(field.x0(), bytes.getInt(8));
        for (int i=0; i<field.voxels().length; i++) assertEquals(field.voxels()[i], bytes.getInt(24+i*4));
    }
}
