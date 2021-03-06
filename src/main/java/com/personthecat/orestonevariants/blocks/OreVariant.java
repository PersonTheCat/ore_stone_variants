package com.personthecat.orestonevariants.blocks;

import com.personthecat.orestonevariants.config.Cfg;
import com.personthecat.orestonevariants.init.LazyRegistries;
import com.personthecat.orestonevariants.item.DenseVariantItem;
import com.personthecat.orestonevariants.properties.BlockPropertiesHelper;
import com.personthecat.orestonevariants.properties.OreProperties;
import com.personthecat.orestonevariants.util.CommonMethods;
import com.personthecat.orestonevariants.util.Lazy;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.block.*;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.RenderTypeLookup;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItemUseContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.LootContext;
import net.minecraft.loot.LootParameterSets;
import net.minecraft.loot.LootParameters;
import net.minecraft.state.BooleanProperty;
import net.minecraft.state.StateContainer;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.shapes.VoxelShape;
import net.minecraft.world.IBlockReader;
import net.minecraft.world.IWorldReader;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.extensions.IForgeBlock;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import static com.personthecat.orestonevariants.util.CommonMethods.find;
import static com.personthecat.orestonevariants.util.CommonMethods.runExF;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class OreVariant extends SharedStateBlock implements IForgeBlock {

    /** Contains the standard block properties and any additional values, if necessary. */
    public final OreProperties properties;

    /** A reference to the background block represented by this variant. */
    public final BlockState bgState;

    /** A reference to the foreground block represented by this variant. */
    public final BlockState fgState;

    /** The item representing the normal state of this block. */
    public final Lazy<Item> normalItem;

    /** The item representing the dense state of this block. */
    public final Lazy<Item> denseItem;

    /** BlockState properties used by all ore variants. */
    public static final BooleanProperty DENSE = BooleanProperty.create("dense");

    public OreVariant(OreProperties osvProps, BlockState bgState) {
        this(osvProps, createProperties(osvProps.block, bgState.getBlock()), bgState);
    }

    private OreVariant(OreProperties osvProps, Properties mcProps, BlockState bgState) {
        super(mcProps, createBackground(osvProps, bgState.getBlock()), osvProps.ore.get().getBlock());
        this.properties = osvProps;
        this.bgState = bgState;
        this.fgState = osvProps.ore.get();
        this.normalItem = new Lazy<>(this::initNormalItem);
        this.denseItem = new Lazy<>(this::initDenseItem);
        setDefaultState(createDefaultState());
        setRegistryName(CommonMethods.createName(osvProps, bgState.getBlock()));
        mapInfestedVariants(osvProps, this, bgState.getBlock());
    }

    /** Decides whether to merge block properties for this ore. */
    private static Block.Properties createProperties(Block.Properties ore, Block bgBlock) {
        return Cfg.bgImitation.get() ? BlockPropertiesHelper.merge(ore, bgBlock) : ore;
    }

    /** Determines which block we are imitating, if any. */
    private static Block createBackground(OreProperties properties, Block bg) {
        return Cfg.bgImitation.get() ? bg : new Block(properties.block);
    }

    /** Conditionally generates the default state for this ore. */
    protected BlockState createDefaultState() {
        return getDefaultState().with(DENSE, false);
    }

    @Override
    protected void fillStateContainer(StateContainer.Builder<Block, BlockState> builder) {
        builder.add(DENSE);
        super.fillStateContainer(builder);
    }

    /** Locates the item representing the normal variant of this block. */
    private Item initNormalItem() {
        return find(LazyRegistries.ITEMS, i -> !i.isDense() && i.getBlock().equals(this))
            .orElseThrow(() -> runExF("Item for {} was not registered correctly.", this));
    }

    /** Locates the item representing the dense variant of this block.  */
    private Item initDenseItem() {
        return find(LazyRegistries.ITEMS, i -> i.isDense() && i.getBlock().equals(this))
            .orElseThrow(() -> runExF("Dense item for {} was not registered correctly.", this));
    }

    /** Registers this block as a potentially infested block. */
    private static void mapInfestedVariants(OreProperties props, Block infested, Block bg) {
        if (Cfg.mapInfestedVariants.get() && props.ore.get().getBlock() instanceof SilverfishBlock) {
            SilverfishBlock.normalToInfectedMap.put(bg, infested);
        }
    }

    private ItemStack getStack(BlockState state) {
        return new ItemStack(state.get(DENSE) ? denseItem.get() : normalItem.get());
    }

    /** Returns a stack containing the foreground ore block represented by this block. */
    public ItemStack getOreStack() {
        return new ItemStack(fgState.getBlock());
    }

    @Override
    public BlockState getStateForPlacement(BlockItemUseContext context) {
        BlockState state = super.getStateForPlacement(context);
        Objects.requireNonNull(state, "SharedStateBlock returned null.");
        if (context.getItem().getItem() instanceof DenseVariantItem) {
            state = state.with(DENSE, true);
        }
        return state;
    }

    @Override
    public boolean canHarvestBlock(BlockState state, IBlockReader world, BlockPos pos, PlayerEntity player) {
        if (bgState.getMaterial() != Material.ROCK && Cfg.bgImitation.get()) {
            return bgState.canHarvestBlock(world, pos, player);
        }
        // This must be the regular functionality, else we have to reimplement it.
        return super.canHarvestBlock(state, world, pos, player);
    }

    @Override
    public String getTranslationKey() {
        return properties.translationKey.orElse(fgState.getBlock().getTranslationKey());
    }

    @Override
    public ItemStack getPickBlock(BlockState state, RayTraceResult target, IBlockReader world, BlockPos pos, PlayerEntity player) {
        final ItemStack actual = new ItemStack(state.get(DENSE) ? denseItem.get() : normalItem.get());
        // Return the actual if in creative mode or if they have it at all.
        if (player.abilities.isCreativeMode || player.inventory.hasItemStack(actual)) {
            return actual;
        }
        // Return the original if they only have that.
        final ItemStack ore = new ItemStack(fgState.getBlock());
        return player.inventory.hasItemStack(ore) ? ore : actual;
    }

    @Override
    public Item asItem() {
        return normalItem.get();
    }

    @OnlyIn(Dist.CLIENT)
    public boolean canRenderInLayer(RenderType layer) {
        return layer == getBgLayer() || layer == getFgLayer();
    }

    @OnlyIn(Dist.CLIENT)
    public RenderType getBgLayer() {
        return RenderTypeLookup.func_239221_b_(bgState);
    }

    @OnlyIn(Dist.CLIENT)
    public static RenderType getFgLayer() {
        return Cfg.translucentTextures.get() ? RenderType.getTranslucent() : RenderType.getCutoutMipped();
    }

    @Override
    @Deprecated
    @OnlyIn(Dist.CLIENT)
    @SuppressWarnings("deprecation")
    public VoxelShape getRenderShape(BlockState state, IBlockReader worldIn, BlockPos pos) {
        return bgState.getShape(worldIn, pos);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    @SuppressWarnings("deprecation")
    public boolean isSideInvisible(BlockState state, BlockState next, Direction dir) {
        return bgState.isSideInvisible(next, dir);
    }

    // Todo: All of the drop logic should be copied from 1.12, which is superior.
    @Override
    @Deprecated
    @SuppressWarnings("deprecation")
    public List<ItemStack> getDrops(BlockState state, LootContext.Builder builder) {
        final List<ItemStack> items = getBaseDrops(state, builder);
        handleDense(items, state, builder);
        return handleSelfDrops(items, state, hasSilkTouch(builder));
    }

    /** Substitutes drops from the lookup loot table with those of a raw table, if applicable. */
    @Deprecated
    private List<ItemStack> getBaseDrops(BlockState state, LootContext.Builder builder) {
        final LootContext ctx = builder
            .withParameter(LootParameters.BLOCK_STATE, state)
            .build(LootParameterSets.BLOCK);
        return properties.drops.get()
            .map(loot -> loot.generate(ctx))
            .orElseGet(() -> super.getDrops(state, builder));
    }

    /** Generates additional loot, if applicable */
    private void handleDense(List<ItemStack> items, BlockState state, LootContext.Builder builder) {
        if (state.get(DENSE)) {
            int count = Cfg.denseDropMultiplier.get();
            if (Cfg.randomDropCount.get()) {
                count = RANDOM.nextInt(count + 1);
            }
            if (count < Cfg.denseDropMultiplierMin.get()) {
                count = Cfg.denseDropMultiplierMin.get();
            }
            for (int i = 0; i < count; i++) {
                items.addAll(getBaseDrops(state, builder));
            }
        }
    }

    /** Replaces the original background ore drop with this block, if applicable. */
    private List<ItemStack> handleSelfDrops(List<ItemStack> items, BlockState state, boolean silkTouch) {
        if (Cfg.variantsDrop.get() || (silkTouch && Cfg.variantsSilkTouch.get())) {
            items.replaceAll(item -> {
                if (item.isItemEqual(getOreStack())) {
                    return getStack(state);
                } else {
                    return item;
                }
            });
        }
        // Correcting the duplicate dense drops. Better algorithm?
        return state.get(DENSE) ? removeDuplicateDense(items) : items;
    }

    /** Removes any duplicate dense variants from the input stack in a new list. */
    private List<ItemStack> removeDuplicateDense(List<ItemStack> items) {
        final List<ItemStack> newList = new ArrayList<>();
        boolean denseFound = false;
        for (ItemStack item : items) {
            final boolean dense = item.getItem() instanceof DenseVariantItem;
            if (!(dense && denseFound)) {
                newList.add(item);
            }
            denseFound = denseFound || dense;
        }
        return newList;
    }

    /** Determines whether silk touch is used in the current context. */
    private boolean hasSilkTouch(LootContext.Builder builder) {
        final ItemStack tool = builder.get(LootParameters.TOOL);
        if (tool == null) {
            return false;
        }
        return EnchantmentHelper.getEnchantments(tool).containsKey(Enchantments.SILK_TOUCH);
    }

    @Override
    public int getExpDrop(BlockState state, IWorldReader reader, BlockPos pos,
              int fortune, int silkTouch) {
        final Random rand = reader instanceof World ? ((World) reader).rand : RANDOM;
        final int xp = properties.xp.map(range -> range.rand(rand))
            .orElseGet(() -> fgState.getExpDrop(reader, pos, fortune, silkTouch));
        return state.get(DENSE) ? xp * 2 : xp;
    }
}