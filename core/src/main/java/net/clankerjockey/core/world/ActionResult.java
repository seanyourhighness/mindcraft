package net.clankerjockey.core.world;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outcome of a world action. Mirrors the tool lifecycle so tools can surface
 * rich, recoverable failures to the model instead of bare booleans.
 *
 * @param status  one of success/failed/blocked/interrupted/cancelled/timed_out
 * @param message short model-facing summary
 * @param data    optional structured detail (e.g. distance searched)
 */
public record ActionResult(String status, String message, Map<String, Object> data) {

    public ActionResult {
        status = status == null ? "failed" : status.toLowerCase();
        message = message == null ? "" : message;
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static ActionResult success(String message) {
        return new ActionResult("success", message, Map.of());
    }

    public static ActionResult blocked(String message) {
        return new ActionResult("blocked", message, Map.of());
    }

    public static ActionResult failed(String message) {
        return new ActionResult("failed", message, Map.of());
    }

    public static ActionResult success(String message, Map<String, Object> data) {
        Map<String, Object> all = new LinkedHashMap<>(data);
        return new ActionResult("success", message, all);
    }

    public boolean ok() {
        return "success".equals(status);
    }
}
