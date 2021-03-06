package com.personthecat.orestonevariants.world;

import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.ListNBT;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MutableBoundingBox;
import net.minecraft.world.ITickList;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.TickPriority;
import net.minecraft.world.server.ServerTickList;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.registries.ForgeRegistryEntry;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Collections;
import java.util.List;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class TickInterceptor extends ServerTickList<Block> {

    private ITickList<Block> wrapped;
    private Block from;
    private Block to;
    private BlockPos pos;

    public TickInterceptor(ServerWorld world) {
        super(world, b -> true, ForgeRegistryEntry::getRegistryName, t -> {});
    }

    void wrapping(ITickList<Block> ticks) {
        this.wrapped = ticks;
    }

    void listenFor(Block from, Block to) {
        this.from = from;
        this.to = to;
    }

    void onlyAt(BlockPos pos) {
        this.pos = pos;
    }

    void reset() {
        this.wrapped = null;
        this.from = Blocks.AIR;
        this.to = Blocks.AIR;
        this.pos = null;
    }

    // Intercept only if this is the correct position or the position is unspecified.
    private boolean checkPos(BlockPos pos) {
        return this.pos == null || this.pos == pos;
    }

    @Override
    public void tick() {
        if (wrapped instanceof ServerTickList) {
            ((ServerTickList<Block>) wrapped).tick();
        }
    }

    @Override
    public boolean isTickPending(BlockPos pos, Block block) {
        if (checkPos(pos) && block.equals(from)) {
            block = to;
        }
        return wrapped.isTickPending(pos, block);
    }

    @Override
    public List<NextTickListEntry<Block>> getPending(MutableBoundingBox bb, boolean remove, boolean skipCompleted) {
        if (wrapped instanceof ServerTickList) {
            return ((ServerTickList<Block>) wrapped).getPending(bb, remove, skipCompleted);
        }
        return Collections.emptyList();
    }

    @Override
    public boolean isTickScheduled(BlockPos pos, Block block) {
        return wrapped.isTickScheduled(pos, block);
    }

    @Override
    public void scheduleTick(BlockPos pos, Block block, int scheduledTime, TickPriority priority) {
        if (checkPos(pos) && block.equals(from)) {
            block = to;
        }
        wrapped.scheduleTick(pos, block, scheduledTime, priority);
    }

    @Override
    public int func_225420_a() {
        if (wrapped instanceof ServerTickList) {
            return ((ServerTickList<Block>) wrapped).func_225420_a();
        }
        return 0;
    }

    @Override
    public void copyTicks(MutableBoundingBox area, BlockPos offset) {
        if (wrapped instanceof ServerTickList) {
            ((ServerTickList<Block>) wrapped).copyTicks(area, offset);
        }
    }

    @Override
    public ListNBT func_219503_a(ChunkPos chunk) {
        if (wrapped instanceof ServerTickList) {
            return ((ServerTickList<Block>) wrapped).func_219503_a(chunk);
        }
        return new ListNBT();
    }

    @Override
    public String toString() {
        return "TickInterceptor[" + wrapped.toString() + "]";
    }
}
