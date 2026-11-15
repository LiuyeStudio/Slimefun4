package com.xzavier0722.mc.plugin.slimefun4.storage.callback;

import io.github.thebusybiscuit.slimefun4.utils.ThreadUtils;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * This interface is deprecated and related methods will be replaced by methods using {@link java.util.concurrent.CompletableFuture} in the future.
 * Related methods will be kept and mark deprecated.
 * You can run your original {@link IAsyncReadCallback} callbacks using {@link java.util.concurrent.CompletableFuture#thenAcceptAsync(Consumer, java.util.concurrent.Executor)}, don't forget to check whether the accepted value is null or not.
 * If you want to invoke callback not on the main thread, use the callback executor from the data controller.
 * If you want to invoke callback on the main thread, see {@link ThreadUtils#getMainThreadExecutor()} or {@link ThreadUtils#getMainDelayedExecutor()}.
 * On Folia these two executors run on the global region scheduler; callbacks that touch players or blocks
 * must instead be scheduled with {@code Slimefun#runSyncAtEntity} / {@code Slimefun#runSyncAtLocation}.
 * <p>
 * Folia 26.3 起，全局区域线程（region={null}）不拥有任何区块或实体。在该线程上调用
 * {@code Location#getChunk()}、{@code Block} 读写或 {@code Player}/{@code Entity} 状态读写会抛出
 * {@code IllegalStateException}。{@link #runOnMainThread()} 为 true 的回调因此必须声明一个
 * 「区域锚点」：{@link #getEntityAnchor()} 优先，其次 {@link #getRegionAnchor()}。
 * 数据控制器会把回调派发到锚点所属的线程；没有锚点时退化为全局同步调度（仅适用于纯内存操作）。
 *
 * @param <T> the type of result returned by the callback
 */
public interface IAsyncReadCallback<T> {
    default boolean runOnMainThread() {
        return false;
    }

    /**
     * Folia: 回调会读写方块 / 区块 / 世界时，返回目标方块所在的 {@link Location}。
     * 返回 null 表示回调不涉及区域拥有的状态。
     */
    default @Nullable Location getRegionAnchor() {
        return null;
    }

    /**
     * Folia: 回调会读写玩家 / 实体时，返回目标 {@link Entity}。
     * 优先级高于 {@link #getRegionAnchor()}。
     */
    default @Nullable Entity getEntityAnchor() {
        return null;
    }

    default void onResult(T result) {}

    default void onResultNotFound() {}
}
