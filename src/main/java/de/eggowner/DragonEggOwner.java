package de.eggowner;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Drachenei mit Eigentümer:
 * - Wer den Drachen tötet, ist Eigentümer des Eis.
 * - Das Ei darf platziert werden, aber nur der Eigentümer kann es abbauen / aufheben.
 * - Wer den Eigentümer tötet, wird neuer Eigentümer.
 */
public final class DragonEggOwner extends JavaPlugin implements Listener {

    private static final String PERM_ADMIN = "dragoneggowner.admin";
    private static final String PERM_BYPASS = "dragoneggowner.bypass";

    private File dataFile;
    private UUID owner;
    private String ownerName;

    @Override
    public void onEnable() {
        dataFile = new File(getDataFolder(), "data.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
    }

    // ------------------------------------------------------------------
    // Daten
    // ------------------------------------------------------------------

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        String s = y.getString("owner");
        if (s != null) {
            try {
                owner = UUID.fromString(s);
            } catch (IllegalArgumentException ignored) {
                owner = null;
            }
        }
        ownerName = y.getString("ownerName");
    }

    private void save() {
        getDataFolder().mkdirs();
        YamlConfiguration y = new YamlConfiguration();
        y.set("owner", owner == null ? null : owner.toString());
        y.set("ownerName", ownerName);
        try {
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Konnte data.yml nicht speichern: " + ex.getMessage());
        }
    }

    private void setOwner(Player p) {
        owner = p.getUniqueId();
        ownerName = p.getName();
        save();
    }

    private void clearOwner() {
        owner = null;
        ownerName = null;
        save();
    }

    // ------------------------------------------------------------------
    // Helfer
    // ------------------------------------------------------------------

    private static boolean isEgg(ItemStack s) {
        return s != null && s.getType() == Material.DRAGON_EGG;
    }

    private boolean isOwner(Player p) {
        return owner != null && owner.equals(p.getUniqueId());
    }

    private boolean mayTouch(Player p) {
        return isOwner(p) || p.hasPermission(PERM_BYPASS);
    }

    private static Component red(String s) {
        return Component.text(s, NamedTextColor.RED);
    }

    private String ownerDisplay() {
        if (owner == null) return "niemand";
        return ownerName != null ? ownerName : owner.toString();
    }

    private void denyMessage(Player p) {
        p.sendMessage(red("Das Drachenei gehört " + ownerDisplay()
                + ". Du musst den Eigentümer besiegen!"));
    }

    private static boolean containsEgg(List<Block> blocks) {
        for (Block b : blocks) {
            if (b.getType() == Material.DRAGON_EGG) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Eigentümer festlegen / wechseln
    // ------------------------------------------------------------------

    @EventHandler
    public void onDragonDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof EnderDragon dragon)) return;
        if (owner != null) return; // es gibt schon einen Eigentümer
        Player killer = dragon.getKiller();
        if (killer == null) return;
        setOwner(killer);
        Bukkit.broadcast(Component.text(killer.getName()
                + " hat den Enderdrachen besiegt und ist jetzt Eigentümer des Drachenei!",
                NamedTextColor.LIGHT_PURPLE));
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        if (owner == null || !owner.equals(victim.getUniqueId())) return;
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;

        setOwner(killer);

        // Bei keepInventory würde das Ei sonst beim alten Eigentümer bleiben
        if (e.getKeepInventory()) {
            PlayerInventory inv = victim.getInventory();
            int amount = 0;
            for (ItemStack it : inv.getContents()) {
                if (isEgg(it)) amount += it.getAmount();
            }
            if (amount > 0) {
                inv.remove(Material.DRAGON_EGG);
                victim.getWorld().dropItemNaturally(victim.getLocation(),
                        new ItemStack(Material.DRAGON_EGG, amount));
            }
        }

        Bukkit.broadcast(Component.text(killer.getName() + " hat " + victim.getName()
                + " besiegt und ist jetzt Eigentümer des Drachenei!", NamedTextColor.LIGHT_PURPLE));
    }

    // ------------------------------------------------------------------
    // Ei als Block schützen
    // ------------------------------------------------------------------

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (e.getBlock().getType() != Material.DRAGON_EGG) return;
        Player p = e.getPlayer();
        if (!mayTouch(p)) {
            e.setCancelled(true);
            denyMessage(p);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.DRAGON_EGG) return;
        Action a = e.getAction();
        if (a != Action.LEFT_CLICK_BLOCK && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        boolean mainHand = e.getHand() == null || e.getHand() == EquipmentSlot.HAND;

        // Eigentümer: Schleichen + Rechtsklick mit leerer Hand = Ei aufnehmen
        if (mayTouch(p) && a == Action.RIGHT_CLICK_BLOCK && p.isSneaking() && mainHand
                && p.getInventory().getItemInMainHand().getType().isAir()) {
            e.setCancelled(true);
            b.setType(Material.AIR);
            var leftover = p.getInventory().addItem(new ItemStack(Material.DRAGON_EGG));
            for (ItemStack rest : leftover.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }
            return;
        }

        // Sonst: Klicks auf das Ei verhindern (sonst teleportiert es sich weg)
        e.setCancelled(true);
        if (mainHand) {
            if (mayTouch(p)) {
                p.sendMessage(Component.text(
                        "Zum Aufnehmen: Schleichen + Rechtsklick mit leerer Hand.",
                        NamedTextColor.GRAY));
            } else {
                denyMessage(p);
            }
        }
    }

    @EventHandler
    public void onEggTeleport(BlockFromToEvent e) {
        if (e.getBlock().getType() == Material.DRAGON_EGG) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (containsEgg(e.getBlocks())
                || e.getBlock().getRelative(e.getDirection()).getType() == Material.DRAGON_EGG) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (containsEgg(e.getBlocks())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> b.getType() == Material.DRAGON_EGG);
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> b.getType() == Material.DRAGON_EGG);
    }

    // ------------------------------------------------------------------
    // Ei als Item schützen
    // ------------------------------------------------------------------

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent e) {
        Item item = e.getEntity();
        if (isEgg(item.getItemStack())) {
            item.setInvulnerable(true);
            item.setUnlimitedLifetime(true);
        }
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (!isEgg(e.getItem().getItemStack())) return;
        if (e.getEntity() instanceof Player p && mayTouch(p)) return;
        e.setCancelled(true);
    }

    @EventHandler
    public void onHopperPickup(InventoryPickupItemEvent e) {
        if (isEgg(e.getItem().getItemStack())) {
            e.setCancelled(true);
        }
    }

    // Ei darf nirgends eingelagert werden (Kisten, Enderchest, Trichter, Rahmen ...)

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        InventoryType type = top.getType();
        if (type == InventoryType.CRAFTING || type == InventoryType.CREATIVE) return;

        boolean clickedTop = e.getClickedInventory() != null && e.getClickedInventory().equals(top);

        if (clickedTop && isEgg(e.getCursor())) {
            e.setCancelled(true);
            return;
        }
        if (!clickedTop && e.isShiftClick() && isEgg(e.getCurrentItem())) {
            e.setCancelled(true);
            return;
        }
        if (clickedTop && e.getClick() == ClickType.NUMBER_KEY) {
            int slot = e.getHotbarButton();
            if (slot >= 0 && isEgg(e.getWhoClicked().getInventory().getItem(slot))) {
                e.setCancelled(true);
                return;
            }
        }
        if (clickedTop && e.getClick() == ClickType.SWAP_OFFHAND
                && isEgg(e.getWhoClicked().getInventory().getItemInOffHand())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!isEgg(e.getOldCursor())) return;
        int topSize = e.getView().getTopInventory().getSize();
        for (int raw : e.getRawSlots()) {
            if (raw < topSize) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryMove(InventoryMoveItemEvent e) {
        if (isEgg(e.getItem())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        ItemStack inHand = e.getPlayer().getInventory().getItem(e.getHand());
        if (isEgg(inHand)) {
            e.setCancelled(true); // Rahmen, Allay, Rüstungsständer ...
        }
    }

    @EventHandler
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (isEgg(e.getPlayerItem())) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    // Befehle
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            if (owner == null) {
                sender.sendMessage(Component.text("Das Drachenei hat noch keinen Eigentümer.",
                        NamedTextColor.GRAY));
            } else {
                sender.sendMessage(Component.text("Eigentümer des Drachenei: " + ownerDisplay(),
                        NamedTextColor.LIGHT_PURPLE));
            }
            return true;
        }

        if (!sender.hasPermission(PERM_ADMIN)) {
            sender.sendMessage(red("Dafür hast du keine Berechtigung."));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "set" -> {
                if (args.length < 2) {
                    sender.sendMessage(red("Benutzung: /" + label + " set <Spieler>"));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(red("Spieler nicht online."));
                    return true;
                }
                setOwner(target);
                sender.sendMessage(Component.text(target.getName() + " ist jetzt Eigentümer des Drachenei.",
                        NamedTextColor.GREEN));
            }
            case "clear" -> {
                clearOwner();
                sender.sendMessage(Component.text("Eigentümer entfernt.", NamedTextColor.GREEN));
            }
            case "give" -> {
                if (sender instanceof Player p) {
                    p.getInventory().addItem(new ItemStack(Material.DRAGON_EGG));
                    p.sendMessage(Component.text("Drachenei erhalten.", NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(red("Nur für Spieler."));
                }
            }
            default -> sender.sendMessage(red("Benutzung: /" + label + " [set <Spieler>|clear|give]"));
        }
        return true;
    }
}
