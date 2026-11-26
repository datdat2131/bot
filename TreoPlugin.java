package vn.treo;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class TreoPlugin extends JavaPlugin implements Listener {

    /** Dữ liệu một người đang treo. */
    private static class Afk {
        String name;
        Location loc;
        String skinValue, skinSig;
    }

    private final Map<UUID, Afk> afk = new HashMap<>();
    private final Map<UUID, ServerPlayer> bots = new HashMap<>();   // UUID thật -> bot
    private final Map<UUID, UUID> botIds = new HashMap<>();         // UUID của bot -> UUID thật
    private final Map<UUID, FakeConnection> conns = new HashMap<>();
    private final Set<UUID> failed = new HashSet<>();
    private File dataFile;

    @Override
    public void onEnable() {
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();
        getServer().getPluginManager().registerEvents(this, this);
        // Sau khi server khởi động xong: tạo lại bot cho những người đang treo
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (UUID id : new ArrayList<>(afk.keySet())) {
                if (Bukkit.getPlayer(id) == null && !failed.contains(id)) spawnBot(id);
            }
        }, 20L);
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(bots.keySet())) {
            try { removeBot(id); } catch (Throwable ignored) { }
        }
        saveData();
    }

    // ------------------------------------------------ Lệnh
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Chỉ người chơi mới dùng được lệnh này.");
            return true;
        }
        UUID id = p.getUniqueId();
        if (cmd.getName().equalsIgnoreCase("treo")) {
            if (afk.containsKey(id)) {
                p.sendMessage(Component.text("Bạn đang treo rồi. Dùng /huytreo để hủy.", NamedTextColor.YELLOW));
                return true;
            }
            Afk a = new Afk();
            a.name = p.getName();
            a.loc = p.getLocation().clone();
            for (ProfileProperty pp : p.getPlayerProfile().getProperties()) {
                if (pp.getName().equals("textures")) {
                    a.skinValue = pp.getValue();
                    a.skinSig = pp.getSignature();
                }
            }
            afk.put(id, a);
            p.setInvulnerable(true);
            saveData();
            p.sendMessage(Component.text("Đã vào trạng thái treo. Bạn có thể thoát game, nhân vật vẫn online.", NamedTextColor.GREEN));
        } else {
            if (!afk.containsKey(id)) {
                p.sendMessage(Component.text("Bạn không ở trạng thái treo.", NamedTextColor.YELLOW));
                return true;
            }
            afk.remove(id);
            p.setInvulnerable(false);
            saveData();
            p.sendMessage(Component.text("Đã hủy treo. Bạn trở lại bình thường.", NamedTextColor.GREEN));
        }
        return true;
    }

    // ------------------------------------------------ Bot (người chơi giả)
    // Bot dùng UUID và tên RIÊNG (tên có đuôi _AFK) để không đụng độ với nLogin / PacketEvents
    // khi người thật vào lại. Dữ liệu người chơi (túi đồ, vị trí...) được sao chép qua lại.

    private boolean isBot(UUID uuid) {
        return botIds.containsKey(uuid);
    }

    private static UUID botUuidOf(UUID realId) {
        return UUID.nameUUIDFromBytes(("TreoBot:" + realId).getBytes(StandardCharsets.UTF_8));
    }

    private static String botNameOf(String realName) {
        String base = realName.length() > 12 ? realName.substring(0, 12) : realName;
        return base + "_AFK";
    }

    private File playerDataDir() {
        return new File(Bukkit.getWorlds().get(0).getWorldFolder(), "playerdata");
    }

    private void copyData(UUID from, UUID to) {
        try {
            File dir = playerDataDir();
            File src = new File(dir, from + ".dat");
            if (!src.exists()) return;
            Files.copy(src.toPath(), new File(dir, to + ".dat").toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            getLogger().warning("Không sao chép được dữ liệu người chơi: " + t);
        }
    }

    private void deleteData(UUID uuid) {
        try {
            File dir = playerDataDir();
            new File(dir, uuid + ".dat").delete();
            new File(dir, uuid + ".dat_old").delete();
        } catch (Throwable ignored) {
        }
    }

    /** Lưu dữ liệu bot rồi chép về file của người thật (phải gọi ở luồng chính). */
    private void syncBotToReal(UUID realId) {
        ServerPlayer bot = bots.get(realId);
        if (bot == null) return;
        try {
            bot.getBukkitEntity().saveData();
            copyData(bot.getUUID(), realId);
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Không lưu được dữ liệu bot: " + t, t);
        }
    }

    private void spawnBot(UUID id) {
        Afk a = afk.get(id);
        if (a == null || a.loc.getWorld() == null || bots.containsKey(id)) return;
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        ServerPlayer bot = null;
        UUID botId = botUuidOf(id);
        try {
            ServerLevel level = ((CraftWorld) a.loc.getWorld()).getHandle();
            String botName = botNameOf(a.name);

            copyData(id, botId); // bot bắt đầu với đúng túi đồ/vị trí của người thật

            GameProfile profile = new GameProfile(botId, botName);
            if (a.skinValue != null) {
                profile.getProperties().put("textures", new Property("textures", a.skinValue, a.skinSig));
            }

            bot = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
            FakeConnection conn = new FakeConnection();
            CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);

            bots.put(id, bot);
            botIds.put(botId, id);
            conns.put(id, conn);

            nLoginForce(botName);
            server.getPlayerList().placeNewPlayer(conn, bot, cookie);

            Player bp = bot.getBukkitEntity();
            bp.teleport(a.loc);
            bp.setInvulnerable(true);
            bp.setCollidable(false);

            Bukkit.getScheduler().runTaskLater(this, () -> nLoginForce(botName), 2L);
            Bukkit.getScheduler().runTaskLater(this, () -> nLoginForce(botName), 40L);
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "Không tạo được bot cho " + a.name + ": " + t, t);
            failed.add(id);
            try {
                if (bot != null && server.getPlayerList().getPlayer(botId) != null) {
                    server.getPlayerList().remove(bot);
                }
            } catch (Throwable ignored) {
            }
            FakeConnection c = conns.remove(id);
            if (c != null) c.closeQuietly();
            bots.remove(id);
            botIds.remove(botId);
            deleteData(botId);
        }
    }

    private void removeBot(UUID id) {
        ServerPlayer bot = bots.get(id);
        if (bot == null) return;
        UUID botId = bot.getUUID();
        String name = afk.containsKey(id) ? afk.get(id).name : id.toString();
        try {
            // Nếu người thật không online thì chép dữ liệu bot về cho họ
            if (Bukkit.getPlayer(id) == null) {
                syncBotToReal(id);
            }
            ((CraftServer) Bukkit.getServer()).getServer().getPlayerList().remove(bot);
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "Lỗi khi gỡ bot " + name + ": " + t, t);
        } finally {
            bots.remove(id);
            botIds.remove(botId);
            FakeConnection c = conns.remove(id);
            if (c != null) c.closeQuietly();
        }
        deleteData(botId);
        if (Bukkit.getPlayer(botId) != null) {
            getLogger().severe("Bot " + name + " vẫn còn trong danh sách người chơi sau khi gỡ!");
        } else {
            getLogger().info("Đã gỡ bot " + name + ".");
        }
    }

    /** Gọi API nLogin (nếu có) để đăng nhập hộ bot. Dùng reflection nên không bắt buộc có nLogin. */
    private void nLoginForce(String name) {
        try {
            Class<?> c = Class.forName("com.nickuc.login.api.nLoginAPI");
            Object api = c.getMethod("getApi").invoke(null);
            c.getMethod("forceLogin", String.class).invoke(api, name);
        } catch (ClassNotFoundException ignored) {
            // không cài nLogin
        } catch (Throwable t) {
            getLogger().fine("nLogin forceLogin không chạy được: " + t);
        }
    }

    /** Không cho plugin khác (vd. nLogin) kick bot. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onKick(PlayerKickEvent e) {
        if (isBot(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------ Vào / thoát game
    /**
     * Người thật sắp vào lại: lưu dữ liệu bot và chép về file của người thật TRƯỚC khi server nạp dữ liệu.
     * Bot chưa bị gỡ ở bước này (khác tên, khác UUID nên không đụng độ), sẽ gỡ sau khi người thật đã vào.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        UUID id = e.getUniqueId();
        if (!bots.containsKey(id)) return;
        try {
            Bukkit.getScheduler().callSyncMethod(this, () -> { syncBotToReal(id); return null; })
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            getLogger().warning("Không đồng bộ được dữ liệu bot của " + e.getName() + ": " + ex);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (isBot(id)) {                 // bot bị gỡ
            e.quitMessage(null);
            return;
        }
        if (afk.containsKey(id)) {       // người thật thoát khi đang treo -> tạo bot ở tick sau
            e.quitMessage(null);
            Bukkit.getScheduler().runTask(this, () -> spawnBot(id));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (isBot(id)) {                 // bot join: không thông báo
            e.joinMessage(null);
            return;
        }
        Afk a = afk.get(id);
        if (a != null) {
            p.teleport(a.loc);
            p.setInvulnerable(true);
            p.sendMessage(Component.text("Bạn đang ở trạng thái treo. Dùng /huytreo để hoạt động lại.", NamedTextColor.YELLOW));
        }
        // Người thật đã vào xong: gỡ bot (nếu còn)
        if (bots.containsKey(id)) {
            Bukkit.getScheduler().runTask(this, () -> {
                removeBot(id);
                // Nếu người thật lỡ thoát ngay trong lúc đó và vẫn đang treo thì tạo lại bot
                if (afk.containsKey(id) && Bukkit.getPlayer(id) == null) spawnBot(id);
            });
        }
    }

    // ------------------------------------------------ Đóng băng người chơi thật đang treo
    private boolean frozen(Player p) {
        return afk.containsKey(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!frozen(e.getPlayer())) return;
        Location from = e.getFrom(), to = e.getTo();
        if (to == null) return;
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location fixed = from.clone();
            fixed.setYaw(to.getYaw());
            fixed.setPitch(to.getPitch());
            e.setTo(fixed);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { if (frozen(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (frozen(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) { if (frozen(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) { if (frozen(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) { if (frozen(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && frozen(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && frozen(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!frozen(e.getPlayer())) return;
        String msg = e.getMessage().toLowerCase();
        if (msg.equals("/huytreo") || msg.startsWith("/huytreo ")) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(Component.text("Đang treo: chỉ dùng được /huytreo.", NamedTextColor.RED));
    }

    // ------------------------------------------------ Lưu / đọc dữ liệu
    private void saveData() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Afk> en : afk.entrySet()) {
            String path = "afk." + en.getKey();
            Afk a = en.getValue();
            cfg.set(path + ".name", a.name);
            cfg.set(path + ".world", a.loc.getWorld().getName());
            cfg.set(path + ".x", a.loc.getX());
            cfg.set(path + ".y", a.loc.getY());
            cfg.set(path + ".z", a.loc.getZ());
            cfg.set(path + ".yaw", a.loc.getYaw());
            cfg.set(path + ".pitch", a.loc.getPitch());
            cfg.set(path + ".skinValue", a.skinValue);
            cfg.set(path + ".skinSig", a.skinSig);
        }
        try {
            getDataFolder().mkdirs();
            cfg.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Không lưu được data.yml: " + ex.getMessage());
        }
    }

    private void loadData() {
        if (!dataFile.exists()) return;
        ConfigurationSection sec = YamlConfiguration.loadConfiguration(dataFile).getConfigurationSection("afk");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(key);
            if (s == null) continue;
            World w = Bukkit.getWorld(s.getString("world", ""));
            if (w == null) continue;
            Afk a = new Afk();
            a.name = s.getString("name");
            a.loc = new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                    (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
            a.skinValue = s.getString("skinValue");
            a.skinSig = s.getString("skinSig");
            afk.put(UUID.fromString(key), a);
        }
    }
}
