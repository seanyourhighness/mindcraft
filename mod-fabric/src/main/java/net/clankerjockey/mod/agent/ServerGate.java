package net.clankerjockey.mod.agent;

import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Bridges the agent worker thread to the integrated server thread so world
 * and entity access stays on the game thread where Minecraft expects it.
 */
final class ServerGate {

    private final MinecraftClient mc;

    ServerGate(MinecraftClient mc) {
        this.mc = mc;
    }

    MinecraftServer server() {
        return mc.getServer();
    }

    <T> T onServer(Duration timeout, Supplier<T> task) {
        MinecraftServer server = server();
        if (server == null) {
            throw new IllegalStateException(
                    "Clanker Jockey companion needs a single-player world (integrated server); "
                            + "multiplayer support is a later milestone.");
        }
        if (server.isOnThread()) {
            return task.get();
        }
        try {
            return server.submit(task::get).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("game server did not respond within "
                    + timeout.toMillis() + "ms", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("game server task failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the game server", e);
        }
    }
}
