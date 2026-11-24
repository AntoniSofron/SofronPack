package pl.sofron.terralab.villages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import pl.sofron.terralab.soil.SoilBlocks;

/** A level strip, landing or foundation. The bounding box includes cut and fill. */
final class VillageGround extends StructurePiece {
    static final StructurePieceType TYPE = VillageGround::new;
    enum Surface { STONE, PATH, SLAB, STAIR, FOUNDATION }
    private final Surface surface;
    private final Direction uphill;

    VillageGround(BlockPos a, BlockPos b, Surface surface, Direction uphill) {
        super(TYPE, 0, BoundingBox.fromCorners(a.below(6), b.above(6)));
        this.surface = surface;
        this.uphill = uphill;
    }

    private VillageGround(StructurePieceSerializationContext context, CompoundTag tag) {
        super(TYPE, tag);
        surface = Surface.valueOf(tag.getStringOr("surface", "PATH"));
        uphill = Direction.valueOf(tag.getStringOr("uphill", "NORTH"));
    }

    @Override protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putString("surface", surface.name());
        tag.putString("uphill", uphill.name());
    }

    @Override public void postProcess(WorldGenLevel world, StructureManager structures, ChunkGenerator generator,
            RandomSource random, BoundingBox clip, ChunkPos chunk, BlockPos pivot) {
        int y = boundingBox.minY() + 6;
        BlockState top = switch (surface) {
            case STONE -> Blocks.STONE_BRICKS.defaultBlockState();
            case FOUNDATION -> Blocks.DIRT.defaultBlockState();
            case PATH -> Blocks.DIRT_PATH.defaultBlockState();
            case SLAB -> SoilBlocks.slab(Blocks.DIRT_PATH).defaultBlockState();
            case STAIR -> SoilBlocks.stairs(Blocks.DIRT_PATH).defaultBlockState().setValue(StairBlock.FACING, uphill);
        };
        var pos = new BlockPos.MutableBlockPos();
        for (int x = Math.max(clip.minX(), boundingBox.minX()); x <= Math.min(clip.maxX(), boundingBox.maxX()); x++) {
            for (int z = Math.max(clip.minZ(), boundingBox.minZ()); z <= Math.min(clip.maxZ(), boundingBox.maxZ()); z++) {
                for (int j = y - 1; j >= boundingBox.minY(); j--) {
                    pos.set(x,j,z);
                    if (world.getBlockState(pos).isSolidRender()) break;
                    world.setBlock(pos, Blocks.DIRT.defaultBlockState(), 2);
                }
                for (int j = y + 1; j <= boundingBox.maxY(); j++) {
                    pos.set(x,j,z);
                    if (!world.isEmptyBlock(pos)) world.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                }
                world.setBlock(pos.set(x,y,z), top, 2);
            }
        }
    }
}
