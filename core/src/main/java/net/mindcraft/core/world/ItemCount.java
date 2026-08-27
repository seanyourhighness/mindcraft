package net.mindcraft.core.world;

/** One item stack in the companion's inventory. */
public record ItemCount(String item, int count, int slot) {

    public ItemCount {
        item = item == null ? "unknown" : item;
    }
}
