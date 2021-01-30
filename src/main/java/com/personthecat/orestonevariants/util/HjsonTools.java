package com.personthecat.orestonevariants.util;

import com.google.common.base.Predicates;
import com.google.gson.Gson;
import com.mojang.datafixers.util.Either;
import com.personthecat.orestonevariants.commands.PathArgument;
import com.personthecat.orestonevariants.util.unsafe.ReflectionTools;
import net.minecraft.block.BlockState;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.LootPredicateManager;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.LootTableManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.GenerationStage.Decoration;
import net.minecraftforge.common.ForgeHooks;
import org.hjson.*;
import personthecat.fresult.Protocol;
import personthecat.fresult.Result;
import personthecat.fresult.Void;

import javax.annotation.CheckReturnValue;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.personthecat.orestonevariants.io.SafeFileIO.*;
import static com.personthecat.orestonevariants.util.CommonMethods.*;

/**
 * A set of tools for interacting with Hjson objects unique to this name.
 * See github.com/PersonTheCat/hjson-java to find the specific build of
 * hjson used for this mod.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class HjsonTools {

    /** Necessary for deserializing standard / external loot tables. */
    private static final Gson LOOT_TABLE_CTX = ReflectionTools
        .getValue(LootTableManager.class, "GSON_INSTANCE", 1, new LootTableManager(new LootPredicateManager()));

    /** The settings to be used when outputting JsonObjects to the disk. */
    public static final HjsonOptions FORMATTER = new HjsonOptions()
        .setAllowCondense(true)
        .setAllowMultiVal(true)
        .setCommentSpace(1)
        .setSpace(2)
        .setBracesSameLine(true)
        .setOutputComments(true);

    public static Optional<JsonObject> readJson(File file) {
        return new Protocol()
            .define(FileNotFoundException.class, Result::WARN)
            .define(ParseException.class, Result::THROW)
            .of(() -> JsonObject.readHjson(new FileReader(file), FORMATTER).asObject())
            .get();
    }

    public static Optional<JsonObject> readJson(InputStream is) {
        return new Protocol()
            .define(IOException.class, Result::WARN)
            .define(ParseException.class, Result::THROW)
            .of(() -> JsonObject.readHjson(new InputStreamReader(is), FORMATTER).asObject())
            .get();
    }

    /** Writes the JsonObject to the disk. */
    @CheckReturnValue
    public static Result<Void, IOException> writeJson(JsonObject json, File file) {
        return Result.with(() -> new FileWriter(file), tw -> {
            if (extension(file).equals("json")) { // Write as json.
                json.writeTo(tw, Stringify.FORMATTED);
            } else { // Write as hjson.
                json.writeTo(tw, FORMATTER);
            }
        });
    }

    /** Reads a file from the disk <em>and</em> updates it. */
    @CheckReturnValue
    public static Result<Void, IOException> updateJson(File file, Consumer<JsonObject> f) {
        // If #readJson returned empty, it's because the file didn't exist.
        final JsonObject json = readJson(file).orElseGet(JsonObject::new);
        f.accept(json);
        return writeJson(json, file);
    }

    /** Variant of setOrAdd() used for boolean values. */
    public static JsonObject setOrAdd(JsonObject json, String field, boolean value) {
        return setOrAdd(json, field, JsonValue.valueOf(value));
    }

    /** Variant of setOrAdd() used for integer values. */
    public static JsonObject setOrAdd(JsonObject json, String field, int value) {
        return setOrAdd(json, field, JsonValue.valueOf(value));
    }

    /** Variant of setOrAdd() used for floating point values. */
    public static JsonObject setOrAdd(JsonObject json, String field, float value) {
        return setOrAdd(json, field, JsonValue.valueOf(value));
    }

    /** Variant of setOrAdd() used for string values. */
    public static JsonObject setOrAdd(JsonObject json, String field, String value) {
        return setOrAdd(json, field, JsonValue.valueOf(value));
    }

    /** Modifies or adds a field with the input value. Avoids duplicate fields. */
    public static JsonObject setOrAdd(JsonObject json, String field, JsonValue value) {
        // Don't ignore JSON null.
        if (json.get(field) != null) {
            return json.set(field, value);
        } else {
            return json.add(field, value);
        }
    }

    /** Updates a single value in a json based on a full, dotted path.  */
    public static void setValueFromPath(JsonObject json, PathArgument.Result path, JsonValue value) {
        if (path.path.isEmpty()) {
            return;
        }
        final Either<String, Integer> lastVal = path.path.get(path.path.size() - 1);
        setEither(getLastContainer(json, path), lastVal, value);
    }

    /** Attempts to retrieve the value referenced by `path`. */
    public static Optional<JsonValue> getValueFromPath(JsonObject json, PathArgument.Result path) {
        if (path.path.isEmpty()) {
            return empty();
        }
        final Either<String, Integer> lastVal = path.path.get(path.path.size() - 1);
        return getEither(getLastContainer(json, path), lastVal);
    }

    /** Retrieves the last JsonObject or JsonArray represented by the path. */
    public static JsonValue getLastContainer(JsonObject json, PathArgument.Result path) {
        if (path.path.isEmpty()) {
            return json;
        }
        JsonValue current = json;
        for (int i = 0; i < path.path.size() - 1; i++) {
            final Either<String, Integer> val = path.path.get(i);
            final Either<String, Integer> peek = path.path.get(i + 1);

            if (val.right().isPresent()) { // Index
                current = getOrTryNew(current.asArray(), val.right().get(), peek);
            } else if (peek.left().isPresent()) { // Key -> key -> object
                current = getObjectOrNew(current.asObject(), val.left()
                    .orElseThrow(() -> runEx("Unreachable.")));
            } else { // Key -> index -> array
                current = getArrayOrNew(current.asObject(), val.left()
                    .orElseThrow(() -> runEx("Unreachable.")));
            }
        }
        return current;
    }

    /** Retrieves a list of paths adjacent to `path`. */
    public static List<String> getPaths(JsonObject json, PathArgument.Result path) {
        final JsonValue container = Result.of(() -> getLastContainer(json, path))
            .get(Result::WARN)
            .orElse(json);
        int end = path.path.size() - 1;
        if (end < 0) {
            return getNeighbors("", container);
        }
        final Optional<JsonValue> v = getEither(container, path.path.get(end))
            .filter(value -> value.isObject() || value.isArray());
        if (v.isPresent()) {
            end++; // The full path is a valid container -> use it.
        }
        final String dir = PathArgument.serialize(path.path.subList(0, end));
        return getNeighbors(dir, v.orElse(container));
    }

    /** Retrieves a list of paths in `container`. */
    private static List<String> getNeighbors(String dir, JsonValue container) {
        final List<String> neighbors = new ArrayList<>();
        if (container.isObject()) {
            for (JsonObject.Member member : container.asObject()) {
                final String name = member.getName();
                neighbors.add(dir.isEmpty() ? name : f("{}.{}", dir, name));
            }
        } else if (container.isArray()) {
            for (int i = 0; i < container.asArray().size(); i++) {
                neighbors.add(f("{}[{}]", dir, i));
            }
        }
        return neighbors;
    }

    /** Attempts to retrieve an object or an array. Creates a new one, if absent. */
    private static JsonValue getOrTryNew(JsonArray array, int index, Either<String, Integer> type) {
        if (index == array.size()) { // The value must be added.
            type.ifLeft(s -> array.add(new JsonObject()))
                .ifRight(i -> array.add(new JsonArray()));
        } // if index >= newSize -> index out of bounds
        return array.get(index);
    }

    /** Attempts to retrieve either an object or an array from an object. */
    private static Optional<JsonValue> getEither(JsonValue container, Either<String, Integer> either) {
        if (either.left().isPresent()) {
            return nullable(container.asObject().get(either.left().get()));
        } else if (either.right().isPresent()) {
            final JsonArray array = container.asArray();
            final int index = either.right().get();
            return index < array.size() ? full(array.get(index)) : empty();
        }
        throw runEx("Unreachable");
    }

    /** Attempts to set a value in a container which may either be an object or an array. */
    private static void setEither(JsonValue container, Either<String, Integer> either, JsonValue value) {
        if (either.left().isPresent()) {
            container.asObject().set(either.left().get(), value);
        } else if (either.right().isPresent()) { // Just to stop the linting.
            container.asArray().set(either.right().get(), value);
        }
    }

    /** Safely retrieves a boolean from the input object. */
    public static Optional<Boolean> getBool(JsonObject json, String field) {
        return getValue(json, field).map(JsonValue::asBoolean);
    }

    /** Shorthand for getBool(json, field).ifPresent(ifPresent). */
    public static void getBool(JsonObject json, String field, Consumer<Boolean> ifPresent) {
        getBool(json, field).ifPresent(ifPresent);
    }

    /** Retrieves a boolean from the input object. Returns `or` if nothing is found. */
    public static boolean getBoolOr(JsonObject json, String field, boolean orElse) {
        return getBool(json, field).orElse(orElse);
    }

    /** Safely retrieves an integer from the input json. */
    public static Optional<Integer> getInt(JsonObject json, String field) {
        return getValue(json, field).map(JsonValue::asInt);
    }

    /** Shorthand for getInt(). */
    public static void getInt(JsonObject json, String field, Consumer<Integer> ifPresent) {
        getInt(json, field).ifPresent(ifPresent);
    }

    /** Retrieves an integer from the input object. Returns `or` if nothing is found. */
    public static Integer getIntOr(JsonObject json, String field, Integer orElse) {
        return getInt(json, field).orElse(orElse);
    }

    /** Retrieves a range of integers from the input object. */
    public static Optional<Range> getRange(JsonObject json, String field) {
        return getValue(json, field)
            .map(HjsonTools::asOrToArray)
            .map(HjsonTools::toIntArray)
            .map(CommonMethods::sort)
            .map(HjsonTools::toRange);
    }

    public static void getRange(JsonObject json, String field, Consumer<Range> ifPresent) {
        getRange(json, field).ifPresent(ifPresent);
    }

    /** Retrieves a range of integers from the input object. Returns `orElse` if nothing is found. */
    public static Range getRangeOr(JsonObject json, String field, Range orElse) {
        return getRange(json, field).orElse(orElse);
    }

    private static Range toRange(int[] range) {
        return range.length == 0
            ? new Range(0)
            : range.length == 1
            ? new Range(range[0])
            : new Range(range[0], range[range.length - 1]);
    }

    /** Safely retrieves a boolean from the input json. */
    public static Optional<Float> getFloat(JsonObject json, String field) {
        return getValue(json, field).map(JsonValue::asFloat);
    }

    /** Shorthand for getFloat(). */
    public static void getFloat(JsonObject json, String field, Consumer<Float> ifPresent) {
        getFloat(json, field).ifPresent(ifPresent);
    }

    /** Retrieves a float from the input object. Returns `or` if nothing is found. */
    public static Float getFloatOr(JsonObject json, String field, Float orElse) {
        return getFloat(json, field).orElse(orElse);
    }

    /** Safely retrieves a string from the input json. */
    public static Optional<String> getString(JsonObject json, String field) {
        return getValue(json, field).map(JsonValue::asString);
    }

    /** Shorthand for getString(). */
    public static void getString(JsonObject json, String field, Consumer<String> ifPresent) {
        getString(json, field).ifPresent(ifPresent);
    }

    /** Retrieves a string from the input object. Returns `or` if nothing is found. */
    public static String getStringOr(JsonObject json, String field, String orElse) {
        return getString(json, field).orElse(orElse);
    }

    public static String getGuaranteedString(JsonObject json, String field) {
        return getString(json, field).orElseThrow(() -> runExF("Missing field: {}", field));
    }

    /** Safely retrieves a JsonArray from the input json. */
    public static Optional<JsonArray> getArray(JsonObject json, String field) {
        return getValue(json, field).map(HjsonTools::asOrToArray);
    }

    /** Retrieves an array from the input object. Returns `or` if nothing is found. */
    public static JsonArray getArrayOr(JsonObject json, String field, JsonArray orElse) {
        return getArray(json, field).orElse(orElse);
    }

    /** Retrieves an object from the input object. Returns an empty array, if nothing is found. */
    public static JsonArray getArrayOrNew(JsonObject json, String field) {
        if (!json.has(field)) {
            json.set(field, new JsonArray());
        }
        return getArray(json, field).orElseThrow(() -> runEx("Unreachable."));
    }

    /** Casts or converts a JsonValue to a JsonArray.*/
    private static JsonArray asOrToArray(JsonValue value) {
        return value.isArray() ? value.asArray() : new JsonArray().add(value);
    }

    /** Shorthand for getArray().*/
    public static void getArray(JsonObject json, String field, Consumer<JsonArray> ifPresent) {
        getArray(json, field).ifPresent(ifPresent);
    }

    /** Safely retrieves a boolean from the input json. */
    public static Optional<JsonObject> getObject(JsonObject json, String field) {
        return getValue(json, field).map(JsonValue::asObject);
    }

    /** Retrieves an object from the input object. Returns `or` if nothing is found. */
    public static JsonObject getObjectOr(JsonObject json, String field, JsonObject orElse) {
        return getObject(json, field).orElse(orElse);
    }

    /** Retrieves an object from the input object. Returns an empty object, if nothing is found. */
    public static JsonObject getObjectOrNew(JsonObject json, String field) {
        if (!json.has(field)) {
            json.set(field, new JsonObject());
        }
        return getObject(json, field).orElseThrow(() -> runEx("Unreachable."));
    }

    /** Shorthand for getObject(). */
    public static void getObject(JsonObject json, String field, Consumer<JsonObject> ifPresent) {
        getObject(json, field).ifPresent(ifPresent);
    }

    /** Safely retrieves a JsonValue from the input object. */
    public static Optional<JsonValue> getValue(JsonObject json, String field) {
        return Optional.ofNullable(json.get(field)).filter(Predicates.not(JsonValue::isNull));
    }

    public static void getValue(JsonObject json, String field, Consumer<JsonValue> ifPresent) {
        getValue(json, field).ifPresent(ifPresent);
    }

    /**
     * Safely retrieves an array of JsonObjects from the input json.
     * To-do: Be more consistent and use Optional, instead.
     */
    public static List<JsonObject> getObjectArray(JsonObject json, String field) {
        List<JsonObject> array = new ArrayList<>();
        getValue(json, field).map(HjsonTools::asOrToArray)
            .ifPresent(a -> a.forEach(e -> {
                // This is assumed to be an object. If it isn't,
                // The user should be informed (i.e. crash).
                array.add(e.asObject());
            }));
        return array;
    }

    /** Safely retrieves an int array from the input json. */
    public static Optional<int[]> getIntArray(JsonObject json, String field) {
        return getArray(json, field).map(HjsonTools::toIntArray);
    }

    /** Shorthand for getIntArray */
    public static void getIntArray(JsonObject json, String field, Consumer<int[]> ifPresent) {
        getIntArray(json, field).ifPresent(ifPresent);
    }

    /** Retrieves an array of integers from the input object. Returns `or` if nothing is found. */
    public static int[] getIntArrayOr(JsonObject json, String field, int[] orElse) {
        return getIntArray(json, field).orElse(orElse);
    }

    public static int[] getIntArrayOrEmpty(JsonObject json, String field) {
        return getIntArrayOr(json, field, new int[0]);
    }

    /** Converts a JsonArray into an array of ints. */
    public static int[] toIntArray(JsonArray array) {
        // Create a List of Integer objects.
        List<Integer> ints = new ArrayList<>();
        // Iterate through the array, adding to the list.
        for (JsonValue value : array) {
            ints.add(value.asInt());
        }
        // Convert the Integer objects into
        // their primitive counterparts.
        return ints.stream()
            .mapToInt((i) -> i)
            .toArray();
    }

    /** Safely retrieves a List of Strings from the input json. */
    public static Optional<List<String>> getStringArray(JsonObject json, String field) {
        return getValue(json, field)
            .map((v) -> toStringArray(asOrToArray(v)));
    }

    /** Retrieves either an array from the input json or an empty list, if absent. */
    public static List<String> getStringArrayOrEmpty(JsonObject json, String field) {
        return getStringArray(json, field).orElse(Collections.emptyList());
    }

    /** Shorthand for getStringArray(). */
    public static void getStringArray(JsonObject json, String field, Consumer<List<String>> ifPresent) {
        getValue(json, field).map(HjsonTools::asOrToArray)
            .map(HjsonTools::toStringArray)
            .ifPresent(ifPresent);
    }

    /** Converts a JsonArray into a List of Strings. */
    public static List<String> toStringArray(JsonArray array) {
        List<String> strings = new ArrayList<>();
        for (JsonValue value : array) {
            strings.add(value.asString());
        }
        return strings;
    }

    /**
     * Gets the required "state" field which must exist in many objects.
     * Throws an exception when no block is found with the input name.
     */
    public static BlockState getGuranteedState(JsonObject json, String requiredFor) {
        String stateName = getString(json, "state")
            .orElseThrow(() -> runExF("Each %s object must contain the field \"state.\"", requiredFor));
        return getBlockState(stateName)
            .orElseThrow(() -> noBlockNamed(stateName));
    }

    /**
     * Gets the required "states" field which must exist in many objects.
     * Throws an exception when any block cannot be found.
     */
    public static BlockState[] getGuranteedStates(JsonObject json, String requiredFor) {
        JsonArray stateNames = getArray(json, "states")
            .orElseThrow(() -> runExF("Each %s object must contain the field \"states.\"", requiredFor));
        // Handles crashing when no block is found.
        return toBlocks(stateNames);
    }

    /** Retrieves a single BlockState from the input json. */
    public static Optional<BlockState> getBlock(JsonObject json, String field) {
        return getString(json, field)
            .map(s -> getBlockState(s)
                .orElseThrow(() -> noBlockNamed(s)));
    }

    /**
     * Retrives an BlockState from the input json, returning `orElse`
     * if no object is found.
     */
    public static BlockState getBlockOr(JsonObject json, String field, BlockState orElse) {
        return getBlock(json, field).orElse(orElse);
    }

    /** Safely retrieves an array of blocks from the input json. */
    public static Optional<BlockState[]> getBlocks(JsonObject json, String field) {
        return getArray(json, field).map(HjsonTools::toBlocks);
    }

    /** Shorthand for getBlocks(). */
    public static void getBlocks(JsonObject json, String field, Consumer<BlockState[]> ifPresent) {
        getBlocks(json, field).ifPresent(ifPresent);
    }

    /** Converts each element in the array into an BlockState. */
    public static BlockState[] toBlocks(JsonArray array) {
        List<BlockState> blocks = new ArrayList<>();
        for (String s : toStringArray(array)) {
            BlockState state = getBlockState(s).orElseThrow(() -> noBlockNamed(s));
            blocks.add(state);
        }
        return blocks.toArray(new BlockState[0]);
    }

    /**
     * Retrieves an array of BlockStates from the input json, substituting
     * `orElse` if no object is found.
     */
    public static BlockState[] getBlocksOr(JsonObject json, String field, BlockState... orElse) {
        return getBlocks(json, field).orElse(orElse);
    }

    /** Safely retrieves an Item from the input object. */
    public static Optional<Item> getItem(JsonObject json, String field) {
        return getString(json, field).map(s -> CommonMethods.getItem(s)
            .orElseThrow(() -> noItemNamed(s)));
    }

    /**
     * Retrieves an Item from the input object, substituting `orElse` if
     * no object is found.
     */
    public static Item getItemOr(JsonObject json, String field, Item orElse) {
        return getItem(json, field).orElse(orElse);
    }

    public static Item getGuaranteedItem(JsonObject json, String field) {
        return getItem(json, field)
            .orElseThrow(() -> runExF("The field {} must be defined.", field));
    }

    public static Optional<ItemStack> getStack(JsonObject json, String field) {
        return getString(json, field).map(s -> CommonMethods.getStack(s)
             .orElseThrow(() -> noItemNamed(s)));
    }

    public static ItemStack getStackOr(JsonObject json, String field, ItemStack orElse) {
        return getStack(json, field).orElse(orElse);
    }

    public static ItemStack getGuaranteedStack(JsonObject json, String field) {
        return getStack(json, field)
            .orElseThrow(() -> runExF("The field {} must be defined.", field));
    }

    /** Safely retrieves a BlockPos from the input object. */
    public static Optional<BlockPos> getPosition(JsonObject json, String field) {
        return getArray(json, field).map(HjsonTools::toPosition);
    }

    /**
     * Retrieves a BlockPos from the input json, returning `orElse`
     * if no object can be found.
     */
    public static BlockPos getPositionOr(JsonObject json, String field, BlockPos orElse) {
        return getPosition(json, field).orElse(orElse);
    }

    /** Safely retrieves an array of type BlockPos from the input json. */
    public static Optional<BlockPos[]> getPositions(JsonObject json, String field) {
        return getArray(json, field).map(a -> {
            List<BlockPos> positions = new ArrayList<>();
            for (JsonValue v : a) {
                positions.add(toPosition(v.asArray()));
            }
            return toArray(positions, BlockPos.class);
        });
    }

    /** Shorthand for getPositions(). */
    public static void getPositions(JsonObject json, String field, Consumer<BlockPos[]> ifPresent) {
        getPositions(json, field).ifPresent(ifPresent);
    }

    /**
     * Retrieves an array of type `BlockPos` from the input object,
     * returning `orElse` if no object is found.
     */
    public static BlockPos[] getPositionsOr(JsonObject json, String field, BlockPos... orElse) {
        return getPositions(json, field).orElse(orElse);
    }

    /** Converts the input JsonArray into a BlockPos object. */
    public static BlockPos toPosition(JsonArray coordinates) {
        // Expect exactly 3 elements.
        if (coordinates.size() != 3) {
            throw runEx("Relative coordinates must be specified in an array of 3 elements, e.g. [0, 0, 0].");
        }
        // Convert the array into a BlockPos object.
        return new BlockPos(
            coordinates.get(0).asInt(),
            coordinates.get(1).asInt(),
            coordinates.get(2).asInt()
        );
    }

    public static Optional<Decoration> getStage(JsonObject json, String field) {
        return getValue(json, field).map(HjsonTools::asDecorationStage);
    }

    private static Decoration asDecorationStage(JsonValue value) {
        return Stream.of(Decoration.values())
            .filter(d -> d.name().equalsIgnoreCase(value.asString()))
            .findFirst()
            .orElseThrow(() -> noStageNamed(value.asString()));
    }

    public static void getStage(JsonObject json, String field, Consumer<Decoration> ifPresent) {
        getStage(json, field).ifPresent(ifPresent);
    }

    /** For the biome object at the top level. */
    public static Biome[] getAllBiomes(JsonObject json) {
        List<Biome> biomes = new ArrayList<>();
        // Get biomes by registry name.
        getStringArray(json, "names").ifPresent((a) -> {
            for (String s : a) {
                Biome biome = getBiome(s).orElseThrow(() -> noBiomeNamed(s));
                biomes.add(biome);
            }
        });
        // Get biomes by type.
        getBiomeTypes(json, "types").ifPresent((a) -> {
            for (Biome.Category c : a) {
                Collections.addAll(biomes, getBiomes(c));
            }
        });
        return toArray(biomes, Biome.class);
    }

    /** Safely retrieves a List of BiomeTypes from the input json. */
    public static Optional<List<Biome.Category>> getBiomeTypes(JsonObject json, String field) {
        return getValue(json, field).map(v -> toBiomeTypes(v.asArray()));
    }

    /** Converts a JsonArray in to a list of BiomeTypes. */
    public static List<Biome.Category> toBiomeTypes(JsonArray array) {
        List<Biome.Category> types = new ArrayList<>();
        for (JsonValue value : array) {
            types.add(getBiomeType(value.asString()));
        }
        return types;
    }

    /** Safely retrieves a material from the input json. */
    public static Optional<Material> getMaterial(JsonObject json, String field) {
        return getString(json, field).flatMap(ValueLookup::getMaterial);
    }

    /**
     * Retrieves a material from the input json, returning `orElse`
     * if no object can be found.
     */
    public static Material getMaterialOr(JsonObject json, String field, Material orElse) {
        return getMaterial(json, field).orElse(orElse);
    }

    /** Retrieves a resource location from the input json. */
    public static Optional<ResourceLocation> getLocation(JsonObject json, String field) {
        return getString(json, field).map(ResourceLocation::new);
    }

    /**
     * Retrieves a resource location from the input json, returning
     * `orElse` if no value is found.
     */
    public static ResourceLocation getLocationOr(JsonObject json, String field, ResourceLocation orElse) {
        return getLocation(json, field).orElse(orElse);
    }

    /** Retrieves a sound type from the input json. */
    public static Optional<SoundType> getSoundType(JsonObject json, String field) {
        return getString(json, field).flatMap(ValueLookup::getSoundType);
    }

    /**
     * Retrieves a sound type from the input json, returning
     * `orElse` if no value is found.
     */
    public static SoundType getSoundTypeOr(JsonObject json, String field, SoundType orElse) {
        return getSoundType(json, field).orElse(orElse);
    }

    /**
     * Attempts to retrieve a loot table from the specified field.
     * Accepts either a resource location or a raw loot table object.
     */
    public static Optional<LootTable> getLootTable(JsonObject json, String field) {
        final Optional<JsonValue> value = getValue(json, field);
        if (!value.isPresent()) {
            return empty();
        }
        final ResourceLocation location;
        final com.google.gson.JsonObject gson;
        if (value.get().isString()) {
            final String name = value.get().asString();
            location = new ResourceLocation(name);
            gson = gsonFromLocation(location, name)
                .orElseThrow(() -> runExF("\"{}\" points to an invalid Json object (syntax error).", name));
        } else if (value.get().isObject()) {
            final JsonObject object = value.get().asObject();
            location = osvLocation("dynamic_loot/");
            gson = parseGson(new StringReader(object.toString(Stringify.PLAIN)))
                .orElseThrow(() -> runExF("The object named \"{}\" is an invalid loot table."));
        } else {
            return empty();
        }
        return full(ForgeHooks.loadLootTable(LOOT_TABLE_CTX, location, gson, true, new LootTableManager(new LootPredicateManager())));
    }

    /** Parses a Gson json object from a ResourceLocation. */
    private static Optional<com.google.gson.JsonObject> gsonFromLocation(ResourceLocation location, String name) {
        final String path = f("/data/{}/loot_tables/{}.json", location.getNamespace(), location.getPath());
        final InputStream stream = getResource(path)
            .orElseThrow(() -> noTableNamed(name));
        return parseGson(new InputStreamReader(stream));
    }

    /** Reads a Gson json object neatly, using Result#get. */
    private static Optional<com.google.gson.JsonObject> parseGson(Reader reader) {
        return Result.of(() -> new com.google.gson.JsonParser().parse(reader).getAsJsonObject()).get(Result::WARN);
    }

    /** Informs the user that they have entered an invalid biome name. */
    public static RuntimeException noBiomeNamed(String name) {
        return runExF("There is no biome named \"{}.\"", name);
    }

    /** Informs the user that they have entered an invalid dimension name. */
    public static RuntimeException noDimensionNamed(String name) {
        return runExF("There is no dimension named \"{}.\"", name);
    }

    /** Informs the user that they have entered an invalid block name. */
    public static RuntimeException noBlockNamed(String name) {
        return runExF("There is no block named \"{}.\"", name);
    }

    /** Informs the user that they have entered an invalid item name. */
    public static RuntimeException noItemNamed(String name) {
        return runExF("There is no item named \"{}.\"", name);
    }

    /** Informs the user that they have entered an invalid setMaterial name. */
    public static RuntimeException noMaterialNamed(String name) {
        return runExF("There is no (supported) setMaterial named \"{}.\"", name);
    }

    /** Informs the user that they have entered an invalid loot table location. */
    public static RuntimeException noTableNamed(String name) {
        return runExF("There is no loot table located at \"{}.\"", name);
    }

    public static RuntimeException noStageNamed(String name) {
        return runExF("{} is not a stage. The following are valid: {}", name, Arrays.toString(Decoration.values()));
    }
}