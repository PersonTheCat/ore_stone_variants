package com.personthecat.orestonevariants.properties;

import com.personthecat.orestonevariants.Main;
import com.personthecat.orestonevariants.config.Cfg;
import com.personthecat.orestonevariants.util.Lazy;
import com.personthecat.orestonevariants.util.Reference;
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
import static com.personthecat.orestonevariants.util.CommonMethods.empty;
import static com.personthecat.orestonevariants.util.CommonMethods.extension;
import static com.personthecat.orestonevariants.util.CommonMethods.find;
import static com.personthecat.orestonevariants.util.CommonMethods.full;
import static com.personthecat.orestonevariants.util.CommonMethods.getBlockState;
import static com.personthecat.orestonevariants.util.CommonMethods.getConfigDir;
import static com.personthecat.orestonevariants.util.CommonMethods.info;
import static com.personthecat.orestonevariants.util.CommonMethods.noExtension;
import static com.personthecat.orestonevariants.util.CommonMethods.runEx;
import static com.personthecat.orestonevariants.util.CommonMethods.runExF;
import static com.personthecat.orestonevariants.util.HjsonTools.getLocation;
import static com.personthecat.orestonevariants.util.HjsonTools.getArray;
import static com.personthecat.orestonevariants.util.HjsonTools.getArrayOrNew;
import static com.personthecat.orestonevariants.util.HjsonTools.getObjectOrNew;
import static com.personthecat.orestonevariants.util.HjsonTools.getString;
import static com.personthecat.orestonevariants.util.HjsonTools.getStringOr;
import static com.personthecat.orestonevariants.util.HjsonTools.noBlockNamed;
import static com.personthecat.orestonevariants.util.HjsonTools.readJson;

/**
 * The primary data holder containing all of the information needed for
 * multiple ores to share properties.
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class OreProperties {
    /** An identifier for these properties. */
    public final String name;
    /** Stores the actual lookup to defer ores being loaded. */
    public final String oreLookup;
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
        String oreLookup,
        JsonObject root,
        JsonObject block,
        JsonObject texture,
        JsonArray gen,
        Optional<JsonArray> drop
    ) {
        this(
            location.getPath(),
            oreLookup,
            BlockPropertiesHelper.from(block),
            TextureProperties.from(location, texture),
            WorldGenProperties.list(oreLookup, gen),
            drop.map(DropProperties::list),
            getLocation(block, "advancement"),
            getObjectOrNew(root, "recipe")
        );
    }

    /** Primary constructor */
    private OreProperties(
        String name,
        String oreLookup,
        BlockPropertiesHelper block,
        TextureProperties texture,
        List<WorldGenProperties> gen,
        Optional<List<DropProperties>> drops,
        Optional<ResourceLocation> advancement,
        JsonObject recipe
    ) {
        this.name = name;
        this.oreLookup = oreLookup;
        this.ore = new Lazy<>(() -> getBlockState(oreLookup).orElseThrow(() -> noBlockNamed(oreLookup)));
        this.block = block;
        this.texture = texture;
        this.gen = gen;
        this.drops = drops;
        this.advancement = advancement;
        this.recipe = recipe;
    }

    /** Create a new OreProperties from this JSON object. */
    public static OreProperties fromJson(JsonObject json) {
        final String name = getString(json, "name").orElseThrow(() -> runEx("missing name"));
        final String mod = getString(json, "mod").orElseThrow(() -> runEx("missing mod"));
        final ResourceLocation location = new ResourceLocation(mod, name);
        final JsonObject block = getObjectOrNew(json, "block");
        final JsonObject texture = getObjectOrNew(json, "texture");
        final JsonArray gen = getArrayOrNew(json, "gen");
        final Optional<JsonArray> drop = getArray(json, "loot");
        final String lookup = getStringOr(block, "location", "air");
        return new OreProperties(location, lookup, json, block, texture, gen, drop);
    }

    /** Checks whether this file should be loaded for properties. */
    private static Optional<JsonObject> tryLoadFile(File f) {
        info("Checking: {}", f.getName());
        final JsonObject root = readJson(f).orElseThrow(() -> runExF("Invalid hjson file: {}.", f.getPath()));
        final String mod = getStringOr(root, "mod", "custom");
        final String name = getString(root, "name")
            .orElseGet(() -> noExtension(f))
            .toLowerCase();
        if (!Cfg.oreEnabled(name) || Cfg.modFamiliar(mod) && !Cfg.modEnabled(mod)) {
            info("Skipping {}. It is supported, but not enabled", name);
            return empty();
        } else {
            info("Loading new ore properties: {}", name);
        }
        root.set("name", name).set("mod", mod);
        return full(root);
    }

    /** Generates properties for all of the presets inside of the directory. */
    public static Set<OreProperties> setupOreProperties() {
        final Set<OreProperties> properties = new HashSet<>();
        for (File f : safeListFiles(DIR)) {
            if (Reference.VALID_EXTENSIONS.contains(extension(f))) {
                if (!f.getName().equals("TUTORIAL.hjson")) {
                    tryLoadFile(f).map(OreProperties::fromJson).ifPresent(properties::add);
                }
            }
        }
        return properties;
    }

    /** Locates the OreProperties corresponding to `name`. */
    public static Optional<OreProperties> of(String name) {
        return find(Main.ORE_PROPERTIES, props -> props.name.equals(name));
    }

    /** Locates the OreProperties corresponding to each entry in the list. */
    public static Set<OreProperties> of(String... names) {
        return Stream.of(names)
            .map(name -> of(name)
                .orElseThrow(() -> runExF("There are no properties named \"{}.\" Fix your property group.", name)))
            .collect(Collectors.toSet());
    }
}