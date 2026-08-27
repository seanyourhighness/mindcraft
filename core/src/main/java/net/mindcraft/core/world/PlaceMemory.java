package net.mindcraft.core.world;

import net.mindcraft.core.engine.MiniJson;

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
 * Persistent spatial memory for the companion: named places (home, village,
 * cave, mine, ...) stored per world as JSON. Loader-neutral; each game loader
 * points it at its memory directory. This is what lets the companion answer
 * "go back home" without the player giving coordinates.
 */
public final class PlaceMemory {

    /** One remembered place. */
    public record Place(String name, double x, double y, double z, long rememberedAt) {
    }

    private final Path file;
    private final Map<String, Place> places = new LinkedHashMap<>();

    public PlaceMemory(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        load();
    }

    public static PlaceMemory forWorld(Path memoryDir, String worldId) throws IOException {
        String safe = worldId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return new PlaceMemory(memoryDir.resolve(safe).resolve("places.json"));
    }

    /** Remember/replace a place by name. */
    public synchronized void remember(String name, double x, double y, double z) throws IOException {
        if (name == null || name.isBlank()) return;
        places.put(name.trim().toLowerCase(), new Place(name.trim(), x, y, z, System.currentTimeMillis()));
        save();
    }

    public synchronized Optional<Place> recall(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(places.get(name.trim().toLowerCase()));
    }

    public synchronized boolean forget(String name) throws IOException {
        if (name == null) return false;
        boolean removed = places.remove(name.trim().toLowerCase()) != null;
        if (removed) save();
        return removed;
    }

    public synchronized List<Place> all() {
        return List.copyOf(places.values());
    }

    public synchronized List<String> names() {
        List<String> out = new ArrayList<>();
        for (Place p : places.values()) out.add(p.name());
        return List.copyOf(out);
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            Object tree = MiniJson.parse(Files.readString(file, StandardCharsets.UTF_8));
            Object raw = MiniJson.at(tree, "places");
            if (raw instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!(e.getKey() instanceof String name) || !(e.getValue() instanceof Map<?, ?> p)) continue;
                    Object px = p.get("x");
                    Object py = p.get("y");
                    Object pz = p.get("z");
                    if (px instanceof Number nx && py instanceof Number ny && pz instanceof Number nz) {
                        long at = p.get("remembered_at") instanceof Number n ? n.longValue() : 0;
                        places.put(name.trim().toLowerCase(), new Place(name, nx.doubleValue(), ny.doubleValue(), nz.doubleValue(), at));
                    }
                }
            }
        } catch (Exception e) {
            // Corrupt places file: start fresh rather than breaking the mod.
        }
    }

    private void save() throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> list = new LinkedHashMap<>();
        for (Place p : places.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("x", p.x());
            entry.put("y", p.y());
            entry.put("z", p.z());
            entry.put("remembered_at", p.rememberedAt());
            list.put(p.name(), entry);
        }
        out.put("places", list);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, MiniJson.stringify(out), StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
