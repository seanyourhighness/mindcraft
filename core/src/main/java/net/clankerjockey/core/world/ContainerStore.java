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
import java.util.Optional;

/**
 * Persistent virtual containers for the companion (an NPC body has no real
 * chest slots, so container tools operate on this ledger, mirroring the
 * virtual inventory). Per world, file-backed, loader-neutral.
 */
public final class ContainerStore {

    private final Path file;
    private final Map<String, Map<String, Integer>> containers = new LinkedHashMap<>();

    public ContainerStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        load();
    }

    public static ContainerStore forWorld(Path memoryDir, String worldId) throws IOException {
        String safe = worldId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return new ContainerStore(memoryDir.resolve(safe).resolve("containers.json"));
    }

    /** Ensure a container exists; true when it was created. */
    public synchronized boolean open(String name) throws IOException {
        if (name == null || name.isBlank()) return false;
        String key = normalize(name);
        if (containers.containsKey(key)) return false;
        containers.put(key, new LinkedHashMap<>());
        save();
        return true;
    }

    public synchronized List<String> names() {
        return List.copyOf(containers.keySet());
    }

    public synchronized Optional<Map<String, Integer>> view(String name) {
        if (name == null) return Optional.empty();
        Map<String, Integer> contents = containers.get(normalize(name));
        return contents == null ? Optional.empty() : Optional.of(Map.copyOf(contents));
    }

    /** Put items into a container; returns the new total for the item. */
    public synchronized int put(String name, String item, int count) throws IOException {
        if (name == null || item == null || item.isBlank() || count <= 0) return -1;
        Map<String, Integer> container = containers.get(normalize(name));
        if (container == null) return -1;
        container.merge(item, count, Integer::sum);
        save();
        return container.get(item);
    }

    /** Take items out; returns how many were actually removed (-1 if unknown container). */
    public synchronized int take(String name, String item, int count) throws IOException {
        if (name == null || item == null || count <= 0) return 0;
        Map<String, Integer> container = containers.get(normalize(name));
        if (container == null) return -1;
        Integer have = container.get(item);
        if (have == null || have <= 0) return 0;
        int removed = Math.min(have, count);
        if (removed >= have) {
            container.remove(item);
        } else {
            container.put(item, have - removed);
        }
        save();
        return removed;
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase();
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            Object tree = MiniJson.parse(Files.readString(file, StandardCharsets.UTF_8));
            Object raw = MiniJson.at(tree, "containers");
            if (raw instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!(e.getKey() instanceof String name) || !(e.getValue() instanceof Map<?, ?> contents)) continue;
                    Map<String, Integer> items = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> item : contents.entrySet()) {
                        if (item.getKey() instanceof String k && item.getValue() instanceof Number n && n.intValue() > 0) {
                            items.put(k, n.intValue());
                        }
                    }
                    containers.put(name, items);
                }
            }
        } catch (Exception e) {
            // Corrupt containers file: start fresh rather than breaking the mod.
        }
    }

    private void save() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> e : containers.entrySet()) {
            out.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        }
        root.put("containers", out);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, MiniJson.stringify(root), StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
