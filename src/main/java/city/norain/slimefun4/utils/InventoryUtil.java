package city.norain.slimefun4.utils;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.util.LinkedList;
import lombok.experimental.UtilityClass;
import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

@UtilityClass
public class InventoryUtil {

    /**
     * Folia 的全局区域线程同样会被 {@link Bukkit#isPrimaryThread()} 判定为 "主线程"，但它并不拥有
     * 任何玩家实体。因此 Folia 下不能靠该判断直接执行，必须把背包操作交给玩家所属的实体调度器。
     */
    private static boolean isFolia() {
        return Slimefun.getFoliaLib() != null && Slimefun.getFoliaLib().isFolia();
    }

    public void openInventory(Player p, Inventory inventory) {
        if (p == null || inventory == null) {
            return;
        }

        if (isFolia()) {
            Slimefun.runSyncAtEntity(() -> p.openInventory(inventory), p);
        } else if (Bukkit.isPrimaryThread()) {
            p.openInventory(inventory);
        } else {
            Slimefun.runSync(() -> p.openInventory(inventory));
        }
    }

    /**
     * Close inventory for all viewers.
     *
     * @param inventory {@link Inventory}
     */
    public void closeInventory(Inventory inventory) {
        if (inventory == null) {
            return;
        }

        if (isFolia()) {
            // 每个 viewer 都必须在自己所属的实体线程上关闭界面。
            for (HumanEntity viewer : new LinkedList<>(inventory.getViewers())) {
                try {
                    Slimefun.runSyncAtEntity(viewer::closeInventory, viewer);
                } catch (RuntimeException e) {
                    // 实体可能已经离线，FoliaLib 无法为它创建 EntityScheduler，此时无需再关闭界面。
                }
            }
        } else if (Bukkit.isPrimaryThread()) {
            new LinkedList<>(inventory.getViewers()).forEach(HumanEntity::closeInventory);
        } else {
            Slimefun.runSync(() -> new LinkedList<>(inventory.getViewers()).forEach(HumanEntity::closeInventory));
        }
    }

    public void closeInventory(Inventory inventory, Runnable callback) {
        closeInventory(inventory);

        if (Bukkit.isPrimaryThread() && !isFolia()) {
            callback.run();
        } else {
            Slimefun.runSync(callback);
        }
    }
}
