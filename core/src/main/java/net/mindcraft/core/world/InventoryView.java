package net.mindcraft.core.world;

import java.util.List;

/** Snapshot of the companion's inventory. */
public record InventoryView(List<ItemCount> items, List<String> equipped) {

    public InventoryView {
        items = items == null ? List.of() : List.copyOf(items);
        equipped = equipped == null ? List.of() : List.copyOf(equipped);
    }
}
