package io.github.thebusybiscuit.slimefun4.implementation.tasks.player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * This registry keeps track of the {@link AbstractPlayerTask} that is currently active
 * for a {@link org.bukkit.entity.Player} and a given {@link AbstractPlayerTask} type.
 * <p>
 * A repeating task only notices that it is no longer needed when it runs. If a {@link org.bukkit.entity.Player}
 * releases and presses shift (or toggles gliding) faster than the interval of the task, the old task never
 * observes that it should have been cancelled and a second task is started. Both tasks would then drain
 * charge and apply their effect at the same time, which doubles the fuel consumption and can even stack
 * the resulting velocity.
 * <p>
 * Registering a task through this registry replaces (and cancels) the previous task of the same type, so
 * at most one task of each type can be active per {@link org.bukkit.entity.Player}.
 *
 *
 * <p>
 * Folia 适配实现源自 <a href="https://github.com/LiuyeStudio/Slimefun4-Folia">LiuyeStudio/Slimefun4-Folia</a>
 * (GNU GPLv3)。原作者：核动豆 (NukeBean) — Folia 区域化调度适配、任务模型重构；
 * LiuyeStudio (流叶科技工作室) — 项目维护与发布。
 * 本文件由 cyantangerine (Elvin) 移植到 Slimefun4-CN / folia-integration 分支并作适配修改。
 *
 * @author NukeBean (Folia 适配)
 * @author cyantangerine (Elvin) (CN 整合)
 * @see AbstractPlayerTask
 */
final class PlayerTaskRegistry {

    private final Map<UUID, Map<Class<?>, Runnable>> tasks = new ConcurrentHashMap<>();

    /**
     * This registers the given task as the active task of its type for the given {@link org.bukkit.entity.Player}.
     *
     * @param uuid
     *            The {@link UUID} of the {@link org.bukkit.entity.Player}
     * @param taskType
     *            The type of the task, used to distinguish different gadgets
     * @param task
     *            The task to register
     *
     * @return The task that was previously active for this type, if any
     */
    @Nullable Runnable replace(@Nonnull UUID uuid, @Nonnull Class<?> taskType, @Nonnull Runnable task) {
        Map<Class<?>, Runnable> playerTasks = tasks.computeIfAbsent(uuid, key -> new ConcurrentHashMap<>());
        return playerTasks.put(taskType, task);
    }

    /**
     * This removes the given task from the registry, but only if it is still the active task.
     * This prevents an already replaced task from unregistering its successor.
     *
     * @param uuid
     *            The {@link UUID} of the {@link org.bukkit.entity.Player}
     * @param taskType
     *            The type of the task
     * @param task
     *            The task to remove
     */
    void remove(@Nonnull UUID uuid, @Nonnull Class<?> taskType, @Nonnull Runnable task) {
        tasks.computeIfPresent(uuid, (key, playerTasks) -> {
            playerTasks.remove(taskType, task);
            return playerTasks.isEmpty() ? null : playerTasks;
        });
    }

    /**
     * This returns the number of {@link org.bukkit.entity.Player Players} that currently have
     * at least one active task.
     *
     * @return The amount of tracked {@link org.bukkit.entity.Player Players}
     */
    int size() {
        return tasks.size();
    }

    /**
     * This checks whether the given task is the active task for the given type.
     *
     * @param uuid
     *            The {@link UUID} of the {@link org.bukkit.entity.Player}
     * @param taskType
     *            The type of the task
     * @param task
     *            The task to check
     *
     * @return Whether the given task is currently active
     */
    boolean contains(@Nonnull UUID uuid, @Nonnull Class<?> taskType, @Nonnull Runnable task) {
        Map<Class<?>, Runnable> playerTasks = tasks.get(uuid);
        return playerTasks != null && playerTasks.get(taskType) == task;
    }
}
