package com.personthecat.orestonevariants.blocks;

import com.personthecat.orestonevariants.world.WorldInterceptor;
import lombok.extern.log4j.Log4j2;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.block.*;
import net.minecraft.block.material.PushReaction;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntitySpawnPlacementRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItemUseContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.Property;
import net.minecraft.state.StateContainer;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.*;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.IPlantable;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * This class is designed to circumvent the requirement that block state properties be defined at
 * compile time. Creating these properties dynamically, without changing the source code in
 * {@link Block}, requires that some fields be initialized before calling super. We work around
 * this by keeping pre-init data inside of thread-local storage. This guarantees that our data can
 * function safely in multiple threads at a time, if necessary. Once the pre-init phase has completed,
 * we delete any references out of the storage and initialize fields as normal.
 *
 * This is definitely a hack and I don't like it. If you know or can think of a better way to do this,
 * please create an issue on <a href="https://github.com/PersonTheCat/ore_stone_variants/issues">GitHub</a>.
 */
@Log4j2
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class SharedStateBlock extends OreBlock {

    /** Stores all of the data needed before calling <code>super</code>. */
    private static final ThreadLocal<Block[]> cache = new ThreadLocal<>();

    /** The background block being emulated by this one. */
    private final Block bg;

    /** The foreground block being emulated by this one. */
    private final Block fg;

    /** Creates a new block which copies states from <em>at least one</em> other block. */
    SharedStateBlock(Properties properties, Block bg, Block fg) {
        super(preInit(properties, bg, fg));
        this.bg = bg;
        this.fg = fg;
        setDefaultState(imitate(getDefaultState(), bg.getDefaultState(), fg.getDefaultState()));
        // We are done with these data and can clear them from the cache.
        cache.remove();
    }

    /**
     * Here, we move any data that we need into thread local storage so that it can be use
     * during the init phase for this block only.
     */
    private static Properties preInit(Properties properties, Block bg, Block fg) {
        cache.set(new Block[] { bg, fg });
        return properties;
    }

    /**
     * This function copies block state properties from an array of blocks in the cache. It is
     * safe to call this, as duplicate properties happen to be ignored by the underlying map
     * used by this builder.
     */
    @Override
    protected void fillStateContainer(StateContainer.Builder<Block, BlockState> builder) {
        for (Block b : cache.get()) {
            for (Property<?> property : b.getStateContainer().getProperties()) {
                // Ignore duplicate properties, but not keys.
                if (!builder.properties.containsValue(property)) {
                    builder.add(property);
                }
            }
        }
    }

    /**
     * Generates a map of all of the background blockstates to their equivalents for this block.
     * This will be used in world generation to make sure the correct variants are spawned.
     *
     * @return A map of background -> ore variant
     */
    public Map<BlockState, BlockState> getBackgroundMap() {
        final Map<BlockState, BlockState> map = new HashMap<>();

        for (BlockState bgState : this.bg.getStateContainer().getValidStates()) {
            map.put(bgState, imitate(getDefaultState(), bgState));
        }
        return map;
    }

    /**
     * In contrast, this method enables us to set the state of <em>another block</em> to emulate
     * <code>this</code>.
     *
     * @param myself the current state of this block.
     * @return A state of the wrapped block copying <code>this</code>.
     */
    private BlockState bgImitateThis(BlockState myself) {
        return imitate(bg.getDefaultState(), myself);
    }

    /**
     * This method is a variant of {@link #bgImitateThis} in which the foreground imitates this.
     *
     * @param myself the current state of this block.
     * @return A state of the wrapped block copying <code>this</code>.
     */
    private BlockState fgImitateThis(BlockState myself) {
        return imitate(fg.getDefaultState(), myself);
    }

    /**
     * This operation needs to be unchecked because each property wraps a different type. It is
     * still type safe because we're copying properties from another block, where types were
     * already accounted for.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState imitate(BlockState base, BlockState... copy) {
        final Collection<Property<?>> validProperties = base.getProperties();
        for (BlockState state : copy) {
            if (state != null) {
                for (Property prop : state.getValues().keySet()) {
                    if (validProperties.contains(prop)) {
                        base = base.with(prop, state.get(prop));
                    }
                }
            }
        }
        return base;
    }

    /**
     * This function will prepare the {@link WorldInterceptor} to handle incoming data for this
     * block and the block(s) being wrapped by it. It should be used as a sort of spy anytime
     * the background block might call on a {@link World} object of some kind to schedule block
     * ticks, query block states, or update block states in the world.
     *
     * @param wrapped The specific block being imitated in this context.
     * @param actual The actual block state at this location.
     * @param world Any kind of reader that a block would normally have access to.
     * @return A mocked world object wrapping this world.
     */
    private WorldInterceptor interceptAnywhere(Block wrapped, BlockState actual, IBlockReader world) {
        return primeInterceptor(wrapped, actual, world).getWorld();
    }

    /**
     * This function is a variant of {@link #interceptAnywhere} which specifically tells the interceptor
     * to only intercept block updates at the current location.
     *
     * This is useful for preventing the background block from duplicating ore variants, which breaks
     * gameplay balance. For example, a block such as grass or another spreadable item might duplicate
     * the ore inside of it. In this context, we only want to duplicate the background block and not
     * the ore itself.
     *
     * @param wrapped The specific block being imitated in this context.
     * @param actual The actual block state at this location.
     * @param world Any kind of reader that a block would normally have access to.
     * @param pos The current position being intercepted.
     * @return A mocked world object wrapping this world.
     */
    private WorldInterceptor interceptPosition(Block wrapped, BlockState actual, IBlockReader world, BlockPos pos) {
        return primeInterceptor(wrapped, actual, world).onlyAt(pos).getWorld();
    }

    /**
     * This function generates an ongoing stub operation which primes {@link WorldInterceptor} to
     * perform transformations between <code>wrapped</code> and <code>actual</code>.
     *
     * @param wrapped The specific block being imitated in this context.
     * @param actual The actual block state at this location.
     * @param world Any kind of reader that a block would normally have access to.
     * @return A mocked world object wrapping this world.
     */
    private WorldInterceptor.Data primeInterceptor(Block wrapped, BlockState actual, IBlockReader world) {
        return WorldInterceptor.inWorld(world)
            .intercepting(wrapped, this)
            .mappingTo(s -> imitate(wrapped.getDefaultState(), s))
            .mappingFrom(s -> imitate(actual, s));
    }

    /**
     * Experimentally returning the background block so that this block will be treated the
     * same in other contexts. This may change at some point, but currently it seems to have
     * no noticeable effects overall.
     */
    @Override
    public Block getBlock() {
        return bg;
    }

    @Override
    public boolean matchesBlock(Block block) {
        return block == fg || block == bg || block == this;
    }

    @Override
    public boolean isLadder(BlockState state, IWorldReader world, BlockPos pos, LivingEntity entity) {
        return bg.isLadder(bgImitateThis(state), world, pos, entity);
    }

    @Override
    public boolean isBurning(BlockState state, IBlockReader world, BlockPos pos) {
        return bg.isBurning(bgImitateThis(state), world, pos);
    }

    @Override
    public boolean canCreatureSpawn(BlockState state, IBlockReader world, BlockPos pos,
            EntitySpawnPlacementRegistry.PlacementType placement, @Nullable EntityType<?> type) {
        return bg.canCreatureSpawn(bgImitateThis(state), world, pos, placement, type);
    }

    @Override
    public boolean canConnectRedstone(BlockState state, IBlockReader world, BlockPos pos, @Nullable Direction side) {
        return bg.canConnectRedstone(bgImitateThis(state), world, pos, side);
    }

    @Override
    public boolean addLandingEffects(BlockState state1, ServerWorld server, BlockPos pos,
             BlockState state2, LivingEntity entity, int particles) {
        final boolean bgEffects =
            bg.addLandingEffects(bgImitateThis(state1), server, pos, bgImitateThis(state2), entity, particles);
        final boolean fgEffects =
            fg.addLandingEffects(fgImitateThis(state1), server, pos, fgImitateThis(state2), entity, particles);
        return bgEffects || fgEffects;
    }

    @Override
    public boolean addRunningEffects(BlockState state, World world, BlockPos pos, Entity entity) {
        final boolean bgEffects = bg.addRunningEffects(bgImitateThis(state), world, pos, entity);
        final boolean fgEffects = fg.addRunningEffects(fgImitateThis(state), world, pos, entity);
        return bgEffects || fgEffects;
    }

    @Override
    public boolean addHitEffects(BlockState state, World world, RayTraceResult target, ParticleManager manager) {
        final boolean bgEffects = bg.addHitEffects(bgImitateThis(state), world, target, manager);
        final boolean fgEffects = fg.addHitEffects(fgImitateThis(state), world, target, manager);
        return bgEffects || fgEffects;
    }

    @Override
    public boolean addDestroyEffects(BlockState state, World world, BlockPos pos, ParticleManager manager) {
        final boolean bgEffects = bg.addDestroyEffects(bgImitateThis(state), world, pos, manager);
        final boolean fgEffects = fg.addDestroyEffects(fgImitateThis(state), world, pos, manager);
        return bgEffects || fgEffects;
    }

    @Override
    public boolean canSustainPlant(BlockState state, IBlockReader world, BlockPos pos, Direction facing,
            IPlantable plant) {
        return bg.canSustainPlant(bgImitateThis(state), world, pos, facing, plant);
    }

    @Override
    public boolean isStickyBlock(BlockState state) {
        return bg.isStickyBlock(bgImitateThis(state));
    }

    @Override
    public int getFlammability(BlockState state, IBlockReader world, BlockPos pos, Direction side) {
        return bg.getFlammability(bgImitateThis(state), world, pos, side);
    }

    @Override
    public boolean isFlammable(BlockState state, IBlockReader world, BlockPos pos, Direction side) {
        return bg.isFlammable(bgImitateThis(state), world, pos, side);
    }

    @Override
    public int getFireSpreadSpeed(BlockState state, IBlockReader world, BlockPos pos, Direction side) {
        return bg.getFireSpreadSpeed(bgImitateThis(state), world, pos, side);
    }

    @Override
    @Deprecated
    @SuppressWarnings("deprecation")
    public PushReaction getPushReaction(BlockState state) {
        // There's a special exemption in PistonBlock.
        if (bg.equals(Blocks.OBSIDIAN)) {
            return PushReaction.BLOCK;
        }
        return bg.getPushReaction(bgImitateThis(state));
    }

    @Override
    @Deprecated
    @SuppressWarnings("deprecation")
    public int getOpacity(BlockState state, IBlockReader world, BlockPos pos) {
        return bg.getOpacity(bgImitateThis(state), world, pos);
    }

    @Override
    @Deprecated
    @SuppressWarnings("deprecation")
    public boolean canProvidePower(BlockState state) {
        return bg.canProvidePower(bgImitateThis(state)) || fg.canProvidePower(fgImitateThis(state));
    }

    @Override
    public BlockState getStateForPlacement(BlockItemUseContext context) {
        final BlockState bgState = bg.getStateForPlacement(context);
        final BlockState fgState = fg.getStateForPlacement(context);
        return imitate(getDefaultState(), bgState, fgState);
    }

    @Override
    public boolean ticksRandomly(BlockState state) {
        return bg.ticksRandomly(bgImitateThis(state)) || fg.ticksRandomly(fgImitateThis(state));
    }

    @Override
    @Deprecated
    public void spawnAdditionalDrops(BlockState state, ServerWorld world, BlockPos pos, ItemStack stack) {
        WorldInterceptor interceptor = interceptPosition(bg, state, world, pos);
        try {
            bg.spawnAdditionalDrops(bgImitateThis(state), interceptor, pos, stack);

            interceptor = interceptAnywhere(fg, state, world);
            fg.spawnAdditionalDrops(fgImitateThis(state), interceptor, pos, stack);
        } finally {
            WorldInterceptor.resetThread();
        }
    }

    @Override
    public void onExplosionDestroy(World world, BlockPos pos, Explosion explosion) {
        if (world.isRemote()) { // Don't explode twice
            final BlockState actualState = world.getBlockState(pos);
            WorldInterceptor interceptor = interceptPosition(bg, actualState, world, pos);
            try {
                bg.onExplosionDestroy(interceptor, pos, explosion);

                interceptor = interceptAnywhere(fg, actualState, world);
                fg.onExplosionDestroy(interceptor, pos, explosion);
            } finally {
                WorldInterceptor.resetThread();
            }
        }
    }

    @Override
    @Deprecated
    @SuppressWarnings("deprecation")
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean moving) {
        WorldInterceptor interceptor = interceptPosition(bg, state, world, pos);
        try {
            bg.onBlockAdded(bgImitateThis(state), interceptor, pos, bgImitateThis(oldState), moving);

            interceptor = interceptAnywhere(fg, state, world);
            fg.onBlockAdded(fgImitateThis(state), interceptor, pos, fgImitateThis(oldState), moving);
        } finally {
            WorldInterceptor.resetThread();
        }
    }

    @Override
    @Deprecated
    @SuppressWarnings("deprecation")
    public void onBlockClicked(BlockState state, World world, BlockPos pos, PlayerEntity player) {
        WorldInterceptor interceptor = interceptPosition(bg, state, world, pos);
        try {
            bg.onBlockClicked(bgImitateThis(state), interceptor, pos, player);

            interceptor = interceptAnywhere(fg, state, world);
            fg.onBlockClicked(fgImitateThis(state), interceptor, pos, player);
        } finally {
            WorldInterceptor.resetThread();
        }
    }

    @Override
    public void onEntityWalk(World world, BlockPos pos, Entity entity) {
        final BlockState actualState = world.getBlockState(pos);
        WorldInterceptor interceptor = interceptPosition(bg, actualState, world, pos);
        try {
            bg.onEntityWalk(interceptor, pos, entity);

            interceptor = interceptAnywhere(fg, actualState, world);
            fg.onEntityWalk(interceptor, pos, entity);
        } finally {
            WorldInterceptor.resetThread();
        }
    }

    @Override
    @Deprecated
    @SuppressWarnings("deprecation")
    public BlockState updatePostPlacement(BlockState state, Direction dir, BlockState facingState, IWorld world,
              BlockPos pos, BlockPos facingPos) {
        WorldInterceptor interceptor = interceptPosition(bg, state, world, pos);
        try {
            final BlockState bgState =
                bg.updatePostPlacement(bgImitateThis(state), dir, facingState, interceptor, pos, facingPos);

            interceptor = interceptAnywhere(fg, state, world);
            final BlockState fgState =
                fg.updatePostPlacement(fgImitateThis(state), dir, facingState, interceptor, pos, facingPos);

            return imitate(state, bgState, fgState);
        } finally {
            WorldInterceptor.resetThread();
        }
    }

    @Override
    @Deprecated
    @SuppressWarnings("deprecation")
    public ActionResultType onBlockActivated(BlockState state, World world, BlockPos pos, PlayerEntity player,
             Hand hand, BlockRayTraceResult hit) {
        WorldInterceptor interceptor = interceptPosition(bg, state, world, pos);
        try {
            final ActionResultType bgResult =
                bg.onBlockActivated(bgImitateThis(state), interceptor, pos, player, hand, hit);

            interceptor = interceptAnywhere(fg, state, world);
            final ActionResultType fgResult =
                fg.onBlockActivated(fgImitateThis(state), interceptor, pos, player, hand, hit);

            return bgResult == ActionResultType.FAIL ? bgResult : fgResult;
        } finally {
            WorldInterceptor.resetThread();
        }
    }

    @Deprecated
    @SuppressWarnings("deprecation")
    public void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random rand) {
        WorldInterceptor interceptor = interceptPosition(bg, state, world, pos);
        try {
            bg.randomTick(bgImitateThis(state), interceptor, pos, rand);

            interceptor = interceptAnywhere(fg, state, world);
            fg.randomTick(fgImitateThis(state), interceptor, pos, rand);
        } catch (NullPointerException e) {
            log.error("Interceptor returned null for {} on random tick. Skipping.", this);
        } finally {
            WorldInterceptor.resetThread();
        }
    }

    @Override
    @Deprecated
    @SuppressWarnings("deprecation")
    public void tick(BlockState state, ServerWorld world, BlockPos pos, Random rand) {
        WorldInterceptor interceptor = interceptPosition(bg, state, world, pos);
        try {
            bg.tick(bgImitateThis(state), interceptor, pos, rand);

            interceptor = interceptAnywhere(fg, state, world);
            fg.tick(fgImitateThis(state), interceptor, pos, rand);
        } finally {
            WorldInterceptor.resetThread();
        }
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void animateTick(BlockState state, World world, BlockPos pos, Random rand) {
        WorldInterceptor interceptor = interceptPosition(bg, state, world, pos);
        try {
            bg.animateTick(bgImitateThis(state), interceptor, pos, rand);

            interceptor = interceptAnywhere(fg, state, world);
            fg.animateTick(fgImitateThis(state), interceptor, pos, rand);
        } finally {
            WorldInterceptor.resetThread();
        }
    }
}