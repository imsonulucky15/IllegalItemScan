package me.imsonulucky.illegalitemscan;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import javax.net.ssl.HttpsURLConnection;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class IllegalItemScan extends JavaPlugin implements Listener, TabExecutor {

    private long invScanInterval;
    private long worldScanInterval;
    private Map<String, String> worlds;
    private List<Material> illegalItems;
    private List<Material> extraClearItems;
    private String webhookUrl;
    private String prefix;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("reloadillegalitemscan").setExecutor(this);
        getCommand("scanillegal").setExecutor(this);

        prefix = ChatColor.DARK_GRAY + "[" + ChatColor.GREEN + "IllegalScan" + ChatColor.DARK_GRAY + "] " + ChatColor.RESET;

        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::scanInventories, 0L, invScanInterval * 20);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::scanWorlds, 0L, worldScanInterval * 20);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::scanStorageInventories, 0L, worldScanInterval * 20);

        sendWebhook("Plugin IllegalItemScan started!");
        getLogger().info("IllegalItemScan enabled.");
    }

    private void loadConfig() {
        invScanInterval = getConfig().getLong("inventoryScanInterval", 900);
        worldScanInterval = getConfig().getLong("worldScanInterval", 1800);

        worlds = new HashMap<>();
        worlds.put("overworld", getConfig().getString("worlds.overworld", "world"));
        worlds.put("nether", getConfig().getString("worlds.nether", "world_nether"));
        worlds.put("end", getConfig().getString("worlds.end", "world_end"));

        illegalItems = new ArrayList<>(Arrays.asList(
                Material.STRUCTURE_BLOCK,
                Material.COMMAND_BLOCK,
                Material.REPEATING_COMMAND_BLOCK,
                Material.CHAIN_COMMAND_BLOCK
        ));

        extraClearItems = new ArrayList<>();
        List<String> extra = getConfig().getStringList("extraClearItems");
        for (String matName : extra) {
            try {
                extraClearItems.add(Material.valueOf(matName.toUpperCase()));
            } catch (IllegalArgumentException ignored) {
            }
        }

        for (Material mat : Material.values()) {
            if (mat.name().endsWith("_SPAWN_EGG") && !mat.name().equals("VILLAGER_SPAWN_EGG")) {
                illegalItems.add(mat);
            }
        }

        webhookUrl = getConfig().getString("webhook", "");
    }

    private void scanInventories() {
        Bukkit.getScheduler().runTask(this, () -> {
            int clearedCount = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                clearedCount += checkAndClearInventory(player.getInventory(), player, "inventory");
                clearedCount += checkAndClearInventory(player.getEnderChest(), player, "ender chest");
            }
            if (clearedCount > 0) {
                sendWebhook("Inventory scan completed. Items cleared: " + clearedCount);
            }
        });
    }

    private void scanWorlds() {
        Bukkit.getScheduler().runTask(this, () -> {
            int clearedCount = 0;
            for (Map.Entry<String, String> entry : worlds.entrySet()) {
                World world = Bukkit.getWorld(entry.getValue());
                if (world == null) continue;

                for (Entity entity : world.getEntities()) {
                    if (entity instanceof org.bukkit.entity.Item) {
                        ItemStack item = ((org.bukkit.entity.Item) entity).getItemStack();
                        String reason = isIllegal(item);
                        if (reason != null) {
                            int x = entity.getLocation().getBlockX();
                            int y = entity.getLocation().getBlockY();
                            int z = entity.getLocation().getBlockZ();

                            String msg = prefix + ChatColor.WHITE + "Cleared illegal item from world " + entry.getKey()
                                    + " at coords (" + x + " " + y + " " + z + "): " + item.getType()
                                    + " | Reason: " + reason;

                            broadcastAlert(msg);
                            sendWebhook(msg.replace(prefix, "") + " by scanner");
                            entity.remove();
                            clearedCount++;
                        }
                    }
                }
            }
            if (clearedCount > 0) {
                sendWebhook("World scan completed. Items cleared: " + clearedCount);
            }
        });
    }

    private void scanStorageInventories() {
        Bukkit.getScheduler().runTask(this, () -> {
            int clearedCount = 0;
            for (World world : Bukkit.getWorlds()) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    for (BlockState state : chunk.getTileEntities()) {
                        if (state instanceof InventoryHolder) {
                            InventoryHolder holder = (InventoryHolder) state;
                            Inventory inv = holder.getInventory();
                            for (int i = 0; i < inv.getSize(); i++) {
                                ItemStack item = inv.getItem(i);
                                if (item == null) continue;
                                String reason = isIllegal(item);
                                if (reason != null) {
                                    inv.clear(i);
                                    clearedCount++;

                                    int x = state.getLocation().getBlockX();
                                    int y = state.getLocation().getBlockY();
                                    int z = state.getLocation().getBlockZ();

                                    String msg = prefix + ChatColor.WHITE + "Cleared illegal item from storage at coords ("
                                            + x + " " + y + " " + z + "): " + item.getType()
                                            + " | Reason: " + reason;

                                    broadcastAlert(msg);
                                    sendWebhook(msg.replace(prefix, "") + " by scanner");
                                }
                            }
                        }
                    }
                }
            }
            if (clearedCount > 0) {
                sendWebhook("Storage inventory scan completed. Items cleared: " + clearedCount);
            }
        });
    }

    private int checkAndClearInventory(Inventory inventory, Player player, String invType) {
        int cleared = 0;
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack item = inventory.getItem(i);
            if (item == null) continue;

            String reason = isIllegal(item);
            if (reason != null) {
                inventory.clear(i);
                cleared++;
                String msg = prefix + ChatColor.WHITE + "Cleared illegal item from " + invType + " of player "
                        + player.getName() + ": " + item.getType() + " | Reason: " + reason;
                broadcastAlert(msg);
                sendWebhook(msg.replace(prefix, "") + " by scanner");
            }
        }
        return cleared;
    }

    private void broadcastAlert(String message) {
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.isOp() || p.hasPermission("illegalitemscan.alerts"))
                .forEach(p -> p.sendMessage(message));
    }

    private String isIllegal(ItemStack item) {
        if (item == null) return null;

        Material type = item.getType();

        if (illegalItems.contains(type) || extraClearItems.contains(type)) {
            return "illegal item type";
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            for (Map.Entry<Enchantment, Integer> e : item.getEnchantments().entrySet()) {
                if (e.getValue() > 7) {
                    return "enchantment " + e.getKey().getKey().getKey() + " level " + e.getValue();
                }
            }
        }

        return null;
    }

    private void sendWebhook(String content) {
        if (webhookUrl == null || webhookUrl.isEmpty()) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(webhookUrl);
                    HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);

                    String jsonPayload = "{\"content\":\"" + content.replace("\"", "\\\"") + "\"}";

                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
                    }

                    int responseCode = conn.getResponseCode();
                    if (responseCode != 204 && responseCode != 200) {
                        getLogger().warning("Webhook responded with code: " + responseCode);
                    }

                    conn.disconnect();
                } catch (Exception e) {
                    getLogger().warning("Failed to send webhook: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("reloadillegalitemscan")) {
            reloadConfig();
            loadConfig();
            sender.sendMessage(prefix + ChatColor.GREEN + "Config reloaded.");
            return true;
        } else if (command.getName().equalsIgnoreCase("scanillegal")) {
            sender.sendMessage(prefix + ChatColor.YELLOW + "Starting manual scan...");
            scanInventories();
            scanWorlds();
            scanStorageInventories();
            sender.sendMessage(prefix + ChatColor.GREEN + "Scan complete.");
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
