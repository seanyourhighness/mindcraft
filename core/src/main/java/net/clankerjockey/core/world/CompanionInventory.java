package net.clankerjockey.core.world;

import net.clankerjockey.core.engine.MiniJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Virtual companion inventory persisted per world as JSON. The companion is
 * an NPC-style body (no real inventory slots), so item tools operate on this
 * ledger. Loader-neutral; each game loader points it at its memory directory.
 */
public final class CompanionInventory {

    private final Path file;
    private final Map<String, Integer> items = new LinkedHashMap<>();
    private final List<String> equipped = new ArrayList<>();

    public CompanionInventory(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        load();
    }

    public static CompanionInventory forWorld(Path memoryDir, String worldId) throws IOException {
        String safe = worldId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return new CompanionInventory(memoryDir.resolve(safe).resolve("companion_inventory.json"));
    }

    public synchronized int count(String item) {
        return items.getOrDefault(item, 0);
    }

    public synchronized void add(String item, int amount) throws IOException {
        if (item == null || item.isBlank() || amount <= 0) return;
        items.merge(item, amount, Integer::sum);
        save();
    }

    /** Remove up to {@code amount}; returns how many were actually removed. */
    public synchronized int remove(String item, int amount) throws IOException {
        if (item == null || amount <= 0) return 0;
        Integer current = items.get(item);
        if (current == null || current <= 0) return 0;
        int removed = Math.min(current, amount);
        if (removed >= current) {
            items.remove(item);
        } else {
            items.put(item, current - removed);
        }
        save();
        return removed;
    }

    /** Equip an item (replaces the current equipped item; null/blank clears). */
    public synchronized void equip(String item) throws IOException {
        equipped.clear();
        if (item != null && !item.isBlank()) {
            equipped.add(item);
        }
        save();
    }

    public synchronized List<ItemCount> snapshot() {
        List<ItemCount> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : items.entrySet()) {
            out.add(new ItemCount(e.getKey(), e.getValue(), -1));
        }
        return List.copyOf(out);
    }

    public synchronized List<String> equipped() {
        return List.copyOf(equipped);
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            Object tree = MiniJson.parse(Files.readString(file, StandardCharsets.UTF_8));
            Object raw = MiniJson.at(tree, "items");
            if (raw instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getKey() instanceof String k && e.getValue() instanceof Number n && n.intValue() > 0) {
                        items.put(k, n.intValue());
                    }
                }
            }
            Object eq = MiniJson.at(tree, "equipped");
            if (eq instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof String s && !s.isBlank()) equipped.add(s);
                }
            }
        } catch (Exception e) {
            // Corrupt inventory file: start fresh rather than breaking the mod.
        }
    }

    private void save() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("items", items);
        root.put("equipped", equipped);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, MiniJson.stringify(root), StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
