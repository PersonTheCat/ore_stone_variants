package com.personthecat.orestonevariants.blocks;

import com.personthecat.orestonevariants.config.Cfg;
import com.personthecat.orestonevariants.properties.PropertyGroup;
import org.apache.commons.lang3.ArrayUtils;

import java.util.Set;
import java.util.stream.Collectors;

import static com.personthecat.orestonevariants.util.CommonMethods.*;

public class BlockEntry {
    public final BlockGroup blocks;
    public final PropertyGroup properties;

    public BlockEntry(String entry) {
        String[] split = split(entry);
        this.blocks = BlockGroup.findOrCreate(split[1]);
        this.properties = PropertyGroup.findOrCreate(split[0]);
    }

    /** Generates entries from the block list. */
    public static Set<BlockEntry> setupEntries() {
        return Cfg.blockEntries.get().stream()
            .map(BlockEntry::new)
            .collect(Collectors.toSet());
    }

    /**
     * Splits entries around either `,` or ` `, removing empty values
     * and trimming the results.
     */
    private static String[] split(String entry) {
        String[] split = entry.split("[, ]");
        ArrayUtils.removeAllOccurences(split, "");
        assert(split.length == 2);
        split[0] = split[0].trim();
        split[1] = split[1].trim();
        return split;
    }

    @Override
    public String toString() {
        return f("BlockGroup{{} ores -> {} blocks}", properties.name, blocks.name);
    }
}