package city.norain.slimefun4.utils;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

/**
 * A minimal, Folia-safe replacement for dough's {@code TaskQueue}.
 * <p>
 * dough's {@code TaskQueue} is backed by the legacy {@code BukkitScheduler} and therefore throws
 * {@code UnsupportedOperationException} on Folia (and its forks such as Canvas). This queue instead
 * dispatches every step to the region that owns its {@link Location}, so each step is guaranteed
 * to run on the thread that owns that position.
 * <p>
 * The semantics match dough's queue: the delay of a step is relative to the previous step, so all
 * delays are cumulative.
 * <p>
 * Both signatures are supported: the CN form {@code thenRun(delay, action)} combined with
 * {@code execute(defaultLocation)}, and the dev form {@code thenRun(location, delay, action)}
 * combined with {@code execute(plugin)}. When a step carries its own location it takes precedence
 * over the default, which keeps piston animations correct even when they span region borders.
 *
 * <p>
 * Folia 适配实现源自 <a href="https://github.com/LiuyeStudio/Slimefun4-Folia">LiuyeStudio/Slimefun4-Folia</a>
 * (GNU GPLv3)。原作者：核动豆 (NukeBean) — Folia 区域化调度适配、任务模型重构；
 * LiuyeStudio (流叶科技工作室) — 项目维护与发布。
 * 本文件由 cyantangerine (Elvin) 移植到 Slimefun4-CN / folia-integration 分支并作适配修改。
 *
 * @author NukeBean (Folia 适配)
 * @author cyantangerine (Elvin) (CN 整合)
 */
public final class FoliaTaskQueue {

    private final List<Step> steps = new ArrayList<>();
    private long cursor = 0L;

    /**
     * Appends a step which runs without any additional delay.
     *
     * @param action
     *            The {@link Runnable} to run
     *
     * @return This {@link FoliaTaskQueue}
     */
    @Nonnull
    public FoliaTaskQueue thenRun(@Nonnull Runnable action) {
        return thenRun(null, 0L, action);
    }

    /**
     * Appends a step which runs {@code delayTicks} after the previous step.
     *
     * @param delayTicks
     *            The delay in ticks, relative to the previous step
     * @param action
     *            The {@link Runnable} to run
     *
     * @return This {@link FoliaTaskQueue}
     */
    @Nonnull
    public FoliaTaskQueue thenRun(long delayTicks, @Nonnull Runnable action) {
        return thenRun(null, delayTicks, action);
    }

    /**
     * Appends a step bound to a specific region, without any additional delay.
     *
     * @param location
     *            The {@link Location} owning this step
     * @param action
     *            The {@link Runnable} to run
     *
     * @return This {@link FoliaTaskQueue}
     */
    @Nonnull
    public FoliaTaskQueue thenRun(@Nonnull Location location, @Nonnull Runnable action) {
        return thenRun(location, 0L, action);
    }

    /**
     * Appends a step bound to a specific region, {@code delayTicks} after the previous step.
     *
     * @param location
     *            The {@link Location} owning this step
     * @param delayTicks
     *            The delay in ticks, relative to the previous step
     * @param action
     *            The {@link Runnable} to run
     *
     * @return This {@link FoliaTaskQueue}
     */
    @Nonnull
    public FoliaTaskQueue thenRun(@Nullable Location location, long delayTicks, @Nonnull Runnable action) {
        cursor += Math.max(0L, delayTicks);
        steps.add(new Step(cursor, action, location));
        return this;
    }

    /**
     * Appends {@code times} steps, each one {@code periodTicks} after the previous step.
     *
     * @param periodTicks
     *            The delay in ticks between two executions
     * @param times
     *            How many times the action should run
     * @param action
     *            The {@link Runnable} to run
     *
     * @return This {@link FoliaTaskQueue}
     */
    @Nonnull
    public FoliaTaskQueue thenRepeatEvery(long periodTicks, int times, @Nonnull Runnable action) {
        return thenRepeatEvery(null, periodTicks, times, action);
    }

    /**
     * Dev-branch form: repeats a step bound to a specific region.
     *
     * @param location
     *            The {@link Location} owning these steps
     * @param periodTicks
     *            The delay in ticks between two executions
     * @param times
     *            How many times the action should run
     * @param action
     *            The {@link Runnable} to run
     *
     * @return This {@link FoliaTaskQueue}
     */
    @Nonnull
    public FoliaTaskQueue thenRepeatEvery(
            @Nullable Location location, long periodTicks, int times, @Nonnull Runnable action) {
        long period = Math.max(1L, periodTicks);

        for (int i = 0; i < times; i++) {
            cursor += period;
            steps.add(new Step(cursor, action, location));
        }

        return this;
    }

    /**
     * Schedules every step on the region owning the given {@link Location}.
     * Steps carrying their own location keep it (dev-style queues).
     *
     * @param location
     *            The default {@link Location} whose region should run these steps
     */
    public void execute(@Nonnull Location location) {
        executeInternal(location);
    }

    /**
     * Schedules every step on the region it declares. This overload exists for
     * compatibility with the dev branch, which passed the plugin instance here.
     *
     * @param plugin
     *            Unused, every step is bound to its own {@link Location}
     */
    public void execute(@Nonnull Plugin plugin) {
        executeInternal(null);
    }

    private void executeInternal(@Nullable Location defaultLocation) {
        for (Step step : steps) {
            Location target = step.location() != null ? step.location() : defaultLocation;

            if (target == null) {
                // No owning region available, fall back to the global sync scheduler.
                Slimefun.runSync(step.action(), step.delay());
            } else {
                Slimefun.runAtRegion(target, step.action(), step.delay());
            }
        }
    }

    private record Step(long delay, Runnable action, @Nullable Location location) {}
}
