package com.personthecat.orestonevariants.properties;

import com.personthecat.orestonevariants.Main;
import com.personthecat.orestonevariants.util.Lazy;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import org.hjson.JsonArray;
import org.hjson.JsonObject;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.personthecat.orestonevariants.io.SafeFileIO.safeListFiles;
import static com.personthecat.orestonevariants.util.CommonMethods.*;
import static com.personthecat.orestonevariants.util.HjsonTools.*;

/**
 * The primary data holder containing all of the information needed for
 * multiple ores to share properties.
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class OreProperties {
    /** An identifier for these properties. */
    public final ResourceLocation location;
    /** A reference to the original BlockState represented by these properties. */
    public final Lazy<IBlockState> ore;
    /** Standard block properties to be applied when creating new variants. */
    public final BlockPropertiesHelper block;
    /** Information regarding this ore's texture sprites. */
    public final TextureProperties texture;
    /** Information regarding this ore's world generation variables. */
    public final List<WorldGenProperties> gen;
    /** Information regarding this ore's drop overrides, if any. */
    public final Optional<List<DropProperties>> drops;
    /** Information regarding this ore's advancement, if any. */
    public final Optional<ResourceLocation> advancement;
    /** Information regarding this ore's smelting recipe. Generated later.*/
    public final JsonObject recipe;

    /** The name of the directory containing all of the presets. */
    private static final String FOLDER = Main.MODID + "/ores/";
    /** The path leading to the folder. */
    public static final File DIR = new File(getConfigDir(), FOLDER);

    /** Helps organize the categories inside of the root object. Needs work? */
    private OreProperties(
        ResourceLocation location,
        JsonObject root,
        JsonObject block,
        JsonObject texture,
        JsonArray gen,
        Optional<JsonArray> drop
    ) {
        this(
            location,
            getStringOr(block, "location", "air"),
            BlockPropertiesHelper.from(block),
            TextureProperties.from(location, texture),
            WorldGenProperties.list(gen),
            drop.map(DropProperties::list),
            getLocation(block, "advancement"),
            getObjectOrNew(root, "recipe")
        );
    }

    /** Primary constructor */
    public OreProperties(
        ResourceLocation location,
        String oreLookup,
        BlockPropertiesHelper block,
        TextureProperties texture,
        List<WorldGenProperties> gen,
        Optional<List<DropProperties>> drops,
        Optional<ResourceLocation> advancement,
        JsonObject recipe
    ) {
        this.location = location;
        this.ore = new Lazy<>(() -> getBlockState(oreLookup).orElseThrow(() -> noBlockNamed(oreLookup)));
        this.block = block;
        this.texture = texture;
        this.gen = gen;
        this.drops = drops;
        this.advancement = advancement;
        this.recipe = recipe;
    }

    /** Generates a new OreProperties object from the input file. */
    private static OreProperties fromFile(File f) {
        final JsonObject root = readJson(f).orElseThrow(() -> runExF("Invalid hjson file: {}.", f.getPath()));
        final String mod = getStringOr(root, "mod", "custom");
        final String name = getString(root, "name")
            .orElseGet(() -> noExtension(f))
            .toLowerCase();
        final ResourceLocation location = new ResourceLocation(mod, name);
        final JsonObject block = getObjectOrNew(root, "block");
        final JsonObject texture = getObjectOrNew(root, "texture");
        final JsonArray gen = getArrayOrNew(root, "gen");
        final Optional<JsonArray> drop = getArray(root, "loot");
        return new OreProperties(location, root, block, texture, gen, drop);
    }

    /** Generates properties for all of the presets inside of the directory. */
    public static Set<OreProperties> setupOreProperties() {
        final Set<OreProperties> properties = new HashSet<>();
        for (File f : safeListFiles(DIR)) {
            if (!f.getName().equals("TUTORIAL.hjson")) {
                properties.add(fromFile(f));
            }
        }
        return properties;
    }

    /** Locates the OreProperties corresponding to `name`. */
    public static Optional<OreProperties> of(String name) {
        return find(Main.ORE_PROPERTIES, props -> props.location.equals(new ResourceLocation(name)));
    }

    /** Locates the OreProperties corresponding to each entry in the list. */
    public static Set<OreProperties> of(String... names) {
        return Stream.of(names)
            .map(name -> of(name)
                .orElseThrow(() -> runExF("There are no properties named \"{}.\" Fix your property group.", name)))
            .collect(Collectors.toSet());
    }
}