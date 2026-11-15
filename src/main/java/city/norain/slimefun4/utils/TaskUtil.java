package city.norain.slimefun4.utils;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import javax.annotation.Nullable;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import org.bukkit.Bukkit;
import org.bukkit.Location;

@UtilityClass
public class TaskUtil {

    /**
     * Folia 的全局区域线程也会被 {@link Bukkit#isPrimaryThread()} 判定为主线程，
     * 但它不拥有任何区块。涉及方块 / 区块的同步方法在异步线程上执行时必须显式给出位置锚点。
     */
    private static boolean isFolia() {
        return Slimefun.getFoliaLib() != null && Slimefun.getFoliaLib().isFolia();
    }

    private static boolean hasOwningRegion(@Nullable Location location) {
        return isFolia() && location != null && location.getWorld() != null;
    }

    @SneakyThrows
    public void runSyncMethod(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            Slimefun.runSync(runnable);
        }
    }

    /**
     * 在拥有 {@code location} 的区域线程上执行同步方法（Folia 安全）。
     */
    public void runSyncMethod(Runnable runnable, @Nullable Location location) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else if (hasOwningRegion(location)) {
            Slimefun.runAtRegion(location, runnable);
        } else {
            Slimefun.runSync(runnable);
        }
    }

    @SneakyThrows
    public <T> T runSyncMethod(Callable<T> callable) {
        return runSyncMethod(callable, null);
    }

    /**
     * Folia: callSyncMethod 在 Folia 上不可用。只有从真正的异步线程调用时才投递到
     * 拥有该位置的区域线程并阻塞等待；已在 tick 线程上则直接执行。
     * <p>
     * 特别注意：Folia 的方块 / 交互事件本身就运行在拥有该位置的区域线程上。若此时还把
     * 任务排到同一区域的下一次 tick 并阻塞等待，区域线程会被自己卡住，任务永远得不到
     * 执行，最终超时返回 null（曾导致 PlayerInteractEvent 的 NPE）。
     */
    @SneakyThrows
    public <T> T runSyncMethod(Callable<T> callable, @Nullable Location location) {
        if (Bukkit.isPrimaryThread()) {
            return callable.call();
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                future.complete(callable.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        };

        if (hasOwningRegion(location)) {
            Slimefun.runAtRegion(location, task);
        } else {
            Slimefun.runSync(task);
        }

        try {
            return future.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            Slimefun.logger().log(Level.WARNING, "Timeout when executing sync method", e);
            return null;
        }
    }
}
