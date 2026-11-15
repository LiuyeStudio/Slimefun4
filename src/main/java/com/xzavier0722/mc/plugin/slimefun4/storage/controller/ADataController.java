package com.xzavier0722.mc.plugin.slimefun4.storage.controller;

import city.norain.slimefun4.utils.SlimefunPoolExecutor;
import city.norain.slimefun4.utils.TaskTimer;
import com.xzavier0722.mc.plugin.slimefun4.storage.adapter.IDataSourceAdapter;
import com.xzavier0722.mc.plugin.slimefun4.storage.callback.IAsyncReadCallback;
import com.xzavier0722.mc.plugin.slimefun4.storage.common.DataType;
import com.xzavier0722.mc.plugin.slimefun4.storage.common.RecordKey;
import com.xzavier0722.mc.plugin.slimefun4.storage.common.RecordSet;
import com.xzavier0722.mc.plugin.slimefun4.storage.common.ScopeKey;
import com.xzavier0722.mc.plugin.slimefun4.storage.task.DatabaseThreadFactory;
import com.xzavier0722.mc.plugin.slimefun4.storage.task.QueuedWriteTask;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.OverridingMethodsMustInvokeSuper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * {@link ADataController} 是 Slimefun 数据库控制器的抽象类，
 * 提供了对数据源适配器的访问和数据操作的基本方法。
 * <br/>
 * 该类提供了对数据库的增删查改操作以及异步读写的支持。
 */
@Slf4j
public abstract class ADataController {
    private final DataType dataType;
    private final Map<ScopeKey, QueuedWriteTask> scheduledWriteTasks;
    private final ScopedLock lock;

    private volatile IDataSourceAdapter<?> dataAdapter;
    /**
     * 数据库读取调度器
     */
    protected ExecutorService readExecutor;
    /**
     * 数据库写入调度器
     */
    protected ExecutorService writeExecutor;

    protected ExecutorService serialWriteExecutor;

    /**
     * 数据库回调调度器
     */
    @Getter
    protected ExecutorService callbackExecutor;
    /**
     * 标记当前控制器是否已被关闭
     */
    private volatile boolean destroyed = false;

    /**
     * The logger for this data controller.
     */
    protected final Logger logger;

    /**
     * Constructs a new ADataController.
     *
     * @param dataType The data type this controller manages
     */
    protected ADataController(DataType dataType) {
        this.dataType = dataType;
        scheduledWriteTasks = new ConcurrentHashMap<>();
        lock = new ScopedLock();
        logger = Logger.getLogger("SF-" + dataType.name() + "-Controller");
    }

    /**
     * 初始化 {@link ADataController}
     *
     * @param dataAdapter   The data source adapter
     * @param maxReadThread Maximum number of read threads
     * @param maxWriteThread Maximum number of write threads
     */
    @OverridingMethodsMustInvokeSuper
    public void init(IDataSourceAdapter<?> dataAdapter, int maxReadThread, int maxWriteThread) {
        this.dataAdapter = dataAdapter;
        dataAdapter.initStorage(dataType);
        dataAdapter.patch();
        readExecutor = new SlimefunPoolExecutor(
                "SF-" + dataType.name() + "-Read-Executor",
                maxReadThread,
                maxReadThread,
                10,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new DatabaseThreadFactory("SF-" + dataType.name() + "-Read-Thread #"));

        writeExecutor = new SlimefunPoolExecutor(
                "SF-" + dataType.name() + "-Write-Executor",
                Math.max(maxWriteThread - 1, 1),
                Math.max(maxWriteThread - 1, 1),
                10,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new DatabaseThreadFactory("SF-" + dataType.name() + "-Write-Thread #"));

        if (maxWriteThread > 1) {
            serialWriteExecutor = new SlimefunPoolExecutor(
                    "SF-" + dataType.name() + "-SerialWrite-Executor",
                    1,
                    1,
                    10,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(),
                    new DatabaseThreadFactory("SF-" + dataType.name() + "-SerialWrite-Thread #"));
        }

        callbackExecutor = new SlimefunPoolExecutor(
                "SF-" + dataType.name() + "-Callback-Executor",
                1,
                Math.max(1, Runtime.getRuntime().availableProcessors() / 2),
                10,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new DatabaseThreadFactory("SF-" + dataType.name() + "-Callback-Thread #"));
    }

    /**
     * 正常关闭 {@link ADataController}
     */
    @OverridingMethodsMustInvokeSuper
    public void shutdown() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        readExecutor.shutdownNow();
        callbackExecutor.shutdownNow();

        try {
            float totalTask = scheduledWriteTasks.size();
            var pendingTask = scheduledWriteTasks.size();
            var timer = new TaskTimer();

            while (pendingTask > 0) {
                var doneTaskPercent = String.format("%.1f", (totalTask - pendingTask) / totalTask * 100);
                logger.log(Level.INFO, "数据保存中，请稍候... 剩余 {0} 个任务 ({1}%)", new Object[] {pendingTask, doneTaskPercent});
                TimeUnit.SECONDS.sleep(1);
                var currentTask = scheduledWriteTasks.size();

                if (pendingTask == currentTask) {
                    if (timer.peek() / 1000 > 10) {
                        Slimefun.logger().log(Level.WARNING, "检测到耗时保存任务, 请将下面的线程堆栈 完整 发送给开发者以便定位问题: ");
                        Slimefun.logger()
                                .log(Level.WARNING, Slimefun.getProfiler().snapshotThreads());
                    }
                } else {
                    timer.reset();
                }

                pendingTask = scheduledWriteTasks.size();
            }

            logger.info("数据保存完成.");
        } catch (InterruptedException e) {
            logger.log(Level.WARNING, "Exception thrown while saving data: ", e);
        }
        writeExecutor.shutdownNow();
        dataAdapter = null;
    }

    protected void scheduleDeleteTask(ScopeKey scopeKey, RecordKey key, boolean forceScopeKey) {
        scheduleWriteTask(
                scopeKey,
                key,
                () -> {
                    dataAdapter.deleteData(key);
                },
                forceScopeKey);
    }

    protected void scheduleWriteTask(ScopeKey scopeKey, RecordKey key, RecordSet data, boolean forceScopeKey) {
        scheduleWriteTask(scopeKey, key, () -> dataAdapter.setData(key, data), forceScopeKey);
    }

    protected void scheduleWriteTask(ScopeKey scopeKey, RecordKey key, Runnable task, boolean forceScopeKey) {
        lock.lock(scopeKey);

        // log.info("schedule write scope [{}], key [{}]", scopeKey, key);

        try {
            var scopeToUse = forceScopeKey ? scopeKey : key;
            var queuedTask = scheduledWriteTasks.get(scopeKey);
            if (queuedTask == null && scopeKey != scopeToUse) {
                queuedTask = scheduledWriteTasks.get(scopeToUse);
            }

            if (queuedTask != null && queuedTask.queue(key, task)) {
                return;
            }

            queuedTask = new QueuedWriteTask() {
                @Override
                protected void onSuccess() {
                    scheduledWriteTasks.remove(scopeToUse);
                }

                @Override
                protected void onError(Throwable e) {
                    Slimefun.logger()
                            .log(
                                    Level.SEVERE,
                                    "[" + Thread.currentThread().getName()
                                            + "] Exception thrown while executing write task: ",
                                    e);
                }
            };
            queuedTask.queue(key, task);
            scheduledWriteTasks.put(scopeToUse, queuedTask);

            if (serialWriteExecutor != null && key.getScope().isSerial()) {
                serialWriteExecutor.submit(queuedTask);
            } else {
                writeExecutor.submit(queuedTask);
            }
        } finally {
            lock.unlock(scopeKey);
        }
    }

    protected void checkDestroy() {
        if (destroyed) {
            throw new IllegalStateException("Controller cannot be accessed after destroyed.");
        }
    }

    protected <T> void invokeCallback(IAsyncReadCallback<T> callback, T result) {
        invokeCallback(callback, result, callback == null ? null : callback.getRegionAnchor());
    }

    /**
     * 派发一个异步读取回调。
     * <p>
     * Folia 的全局区域线程不拥有任何区块或实体，{@link Slimefun#runSync(Runnable)} 会把回调放到
     * 该线程上；一旦回调里出现 {@code Location#getChunk()}、方块读写或玩家操作就会抛
     * {@code IllegalStateException}（例如破坏尚未加载完数据的 Slimefun 方块）。因此 Folia 下
     * 优先按实体锚点、其次按位置锚点派发到真正拥有该对象的线程。
     *
     * @param callback     回调
     * @param result       读取结果
     * @param regionAnchor 回调将要访问的位置锚点（由数据控制器或回调自身提供）
     */
    protected <T> void invokeCallback(
            IAsyncReadCallback<T> callback, T result, @Nullable Location regionAnchor) {
        if (callback == null) {
            return;
        }

        Runnable cb;
        if (result == null) {
            cb = callback::onResultNotFound;
        } else {
            cb = () -> callback.onResult(result);
        }

        if (callback.runOnMainThread()) {
            boolean folia = Slimefun.getFoliaLib() != null && Slimefun.getFoliaLib().isFolia();
            Entity entityAnchor = folia ? callback.getEntityAnchor() : null;
            Location anchor = regionAnchor != null ? regionAnchor : callback.getRegionAnchor();

            if (entityAnchor != null) {
                Slimefun.runAtEntity(entityAnchor, cb);
            } else if (anchor != null && anchor.getWorld() != null) {
                // 非 Folia 平台同样保持原来的 runSync 语义，避免改变 Paper 上的行为。
                if (folia) {
                    Slimefun.runAtRegion(anchor, cb);
                } else {
                    Slimefun.runSync(cb);
                }
            } else {
                Slimefun.runSync(cb);
            }
        } else {
            callbackExecutor.submit(cb);
        }
    }

    protected void scheduleReadTask(Runnable run) {
        checkDestroy();
        readExecutor.submit(run);
    }

    protected void scheduleWriteTask(Runnable run) {
        checkDestroy();
        writeExecutor.submit(run);
    }

    protected List<RecordSet> getData(RecordKey key) {
        return getData(key, false);
    }

    protected List<RecordSet> getData(RecordKey key, boolean distinct) {
        return dataAdapter.getData(key, distinct);
    }

    protected void setData(RecordKey key, RecordSet data) {
        dataAdapter.setData(key, data);
    }

    protected void deleteData(RecordKey key) {
        dataAdapter.deleteData(key);
    }

    protected void abortScopeTask(ScopeKey key) {
        var task = scheduledWriteTasks.remove(key);
        if (task != null) {
            task.abort();
        }
    }

    public final DataType getDataType() {
        return dataType;
    }
}
