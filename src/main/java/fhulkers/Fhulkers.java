package fhulkers;

import io.papermc.paper.event.block.BlockBreakBlockEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.UUID;

/**
 * Fhulker: a shulker box surrounded by 8 copper ingots, holding 54 slots.
 * Regular shulker boxes are left completely vanilla.
 *
 * A Fhulker is an ordinary shulker box item/block carrying a "fhulker" marker
 * in its PersistentDataContainer. Slots 0-26 live in the vanilla inventory
 * (hoppers, comparators, the item tooltip and dyeing all work on those);
 * slots 27+ are stored as bytes next to the marker. The marker and the extra
 * slots move from block to item when broken and back when placed.
 */
public final class Fhulkers extends JavaPlugin implements Listener {

    private static final int VANILLA = 27;
    private static final int SIZE = 54;

    private record BlockKey(UUID world, int x, int y, int z) {
        static BlockKey of(Block b) {
            return new BlockKey(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        }

        static BlockKey of(Location l) {
            return new BlockKey(l.getWorld().getUID(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
        }
    }

    private record Pending(Location loc, ItemStack[] extra) {}

    /** One shared GUI per open Fhulker, so two players viewing it can't dupe. */
    private static final class BoxHolder implements InventoryHolder {
        final Block block;
        final BlockKey key;
        Inventory inv;
        boolean savePending;

        BoxHolder(Block block) {
            this.block = block;
            this.key = BlockKey.of(block);
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private NamespacedKey markerKey;
    private NamespacedKey extraKey;
    private NamespacedKey recipeKey;
    private final Map<BlockKey, BoxHolder> sessions = new HashMap<>();
    private final Map<BlockKey, Pending> pendingDrops = new HashMap<>();

    @Override
    public void onEnable() {
        markerKey = new NamespacedKey(this, "fhulker");
        extraKey = new NamespacedKey(this, "extra");
        recipeKey = new NamespacedKey(this, "fhulker");

        // Result is a placeholder; PrepareItemCraftEvent swaps in the real
        // input box (keeping its color, name and contents) with the marker added.
        ItemStack template = new ItemStack(Material.SHULKER_BOX);
        markItem(template, null);
        ShapedRecipe r = new ShapedRecipe(recipeKey, template);
        r.shape("CCC", "CSC", "CCC");
        r.setIngredient('C', Material.COPPER_INGOT);
        r.setIngredient('S', new RecipeChoice.MaterialChoice(Tag.SHULKER_BOXES));
        Bukkit.removeRecipe(recipeKey); // survives /reload cleanly
        Bukkit.addRecipe(r);

        getServer().getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) p.discoverRecipe(recipeKey);
    }

    @Override
    public void onDisable() {
        List<BoxHolder> open = new ArrayList<>(sessions.values());
        sessions.clear();
        for (BoxHolder h : open) {
            save(h);
            for (HumanEntity v : new ArrayList<>(h.inv.getViewers())) v.closeInventory();
        }
        for (Pending p : pendingDrops.values()) spill(p.loc(), p.extra());
        pendingDrops.clear();
        Bukkit.removeRecipe(recipeKey);
    }

    // ---------------------------------------------------------------- crafting

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        e.getPlayer().discoverRecipe(recipeKey);
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (!isOurRecipe(e.getRecipe())) return;
        CraftingInventory inv = e.getInventory();
        ItemStack box = null;
        for (ItemStack i : inv.getMatrix()) {
            if (isShulker(i)) { box = i; break; }
        }
        if (box == null || isFhulkerItem(box)) { // already a Fhulker: don't eat the copper
            inv.setResult(null);
            return;
        }
        ItemStack result = box.clone();
        result.setAmount(1);
        markItem(result, null);
        inv.setResult(result);
    }

    /** The crafter block skips PrepareItemCraftEvent and would output an empty box. */
    @EventHandler(ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent e) {
        if (isOurRecipe(e.getRecipe())) e.setCancelled(true);
    }

    private boolean isOurRecipe(Recipe r) {
        return r instanceof Keyed k && k.getKey().equals(recipeKey);
    }

    // ---------------------------------------------------------------- opening

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.useInteractedBlock() == Event.Result.DENY) return; // protection plugins
        Block b = e.getClickedBlock();
        if (b == null || !Tag.SHULKER_BOXES.isTagged(b.getType())) return;
        if (!(b.getState() instanceof ShulkerBox box) || !isFhulkerBlock(box)) return;
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        PlayerInventory pi = p.getInventory();
        // Sneak + item in hand = vanilla places the item instead of opening.
        if (p.isSneaking() && (!isEmpty(pi.getItemInMainHand()) || !isEmpty(pi.getItemInOffHand()))) return;

        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) open(p, b, box);
        // Off-hand: just cancel so vanilla doesn't open the 27-slot view behind ours.
    }

    private void open(Player p, Block b, ShulkerBox box) {
        BlockKey k = BlockKey.of(b);
        BoxHolder h = sessions.get(k);
        if (h == null) {
            h = new BoxHolder(b);
            Component title = box.customName() != null ? box.customName() : Component.text("Fhulker");
            h.inv = Bukkit.createInventory(h, SIZE, title);

            ItemStack[] contents = new ItemStack[SIZE];
            ItemStack[] vanilla = box.getSnapshotInventory().getContents();
            System.arraycopy(vanilla, 0, contents, 0, Math.min(VANILLA, vanilla.length));

            ItemStack[] extra = readExtra(box.getPersistentDataContainer());
            for (int i = 0; i < extra.length && VANILLA + i < SIZE; i++) {
                if (!isEmpty(extra[i])) contents[VANILLA + i] = extra[i];
            }
            h.inv.setContents(contents);
            sessions.put(k, h);
            box.open(); // lid animation + sound
        }
        p.openInventory(h.inv);
    }

    // ------------------------------------------------------- editing / saving

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof BoxHolder h)) return;

        Inventory clicked = e.getClickedInventory();
        if (clicked == top) {
            ItemStack incoming = switch (e.getClick()) {
                case NUMBER_KEY -> e.getWhoClicked().getInventory().getItem(e.getHotbarButton());
                case SWAP_OFFHAND -> e.getWhoClicked().getInventory().getItemInOffHand();
                default -> e.getCursor();
            };
            if (isShulker(incoming)) { e.setCancelled(true); return; }
        } else if (clicked != null && e.isShiftClick() && isShulker(e.getCurrentItem())) {
            e.setCancelled(true);
            return;
        }
        scheduleSave(h);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof BoxHolder h)) return;
        if (isShulker(e.getOldCursor()) && e.getRawSlots().stream().anyMatch(s -> s < top.getSize())) {
            e.setCancelled(true);
            return;
        }
        scheduleSave(h);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof BoxHolder h) || sessions.get(h.key) != h) return;
        // Viewer list still contains the closing player until after this event.
        getServer().getScheduler().runTask(this, () -> {
            if (sessions.get(h.key) != h || !h.inv.getViewers().isEmpty()) return;
            save(h);
            sessions.remove(h.key);
            if (h.block.getState() instanceof ShulkerBox box) box.close();
        });
    }

    /** Hoppers/hopper minecarts can't touch a Fhulker while someone has it open. */
    @EventHandler(ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent e) {
        if (sessions.isEmpty()) return;
        if (isOpenBox(e.getSource()) || isOpenBox(e.getDestination())) e.setCancelled(true);
    }

    private boolean isOpenBox(Inventory inv) {
        if (inv.getType() != InventoryType.SHULKER_BOX) return false;
        Location l = inv.getLocation();
        return l != null && sessions.containsKey(BlockKey.of(l));
    }

    /** Persist after every click so block state and player inventories match at each autosave. */
    private void scheduleSave(BoxHolder h) {
        if (h.savePending) return;
        h.savePending = true;
        getServer().getScheduler().runTask(this, () -> {
            h.savePending = false;
            if (sessions.get(h.key) == h) save(h);
        });
    }

    private void save(BoxHolder h) {
        if (!(h.block.getState() instanceof ShulkerBox box)) return;
        ItemStack[] c = h.inv.getContents();
        box.getSnapshotInventory().setContents(Arrays.copyOf(c, VANILLA));
        writeExtra(box.getPersistentDataContainer(), Arrays.copyOfRange(c, VANILLA, c.length));
        box.update(true, false);
    }

    // ------------------------------------------------- placing

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        ItemStack hand = e.getItemInHand();
        if (!isFhulkerItem(hand)) return;
        ItemStack[] extra = readExtra(hand.getItemMeta().getPersistentDataContainer());
        Block b = e.getBlockPlaced();
        // Next tick: vanilla has fully applied the item's contents to the block by then.
        getServer().getScheduler().runTask(this, () -> attach(b, extra));
    }

    private void attach(Block b, ItemStack[] extra) {
        if (!(b.getState() instanceof ShulkerBox box)) { // gone already
            spill(b.getLocation(), extra);
            return;
        }
        PersistentDataContainer pdc = box.getPersistentDataContainer();
        pdc.set(markerKey, PersistentDataType.BYTE, (byte) 1);
        writeExtra(pdc, extra);
        box.update(true, false);
    }

    /** Dispensers place boxes without a BlockPlaceEvent, which would strip the Fhulker. */
    @EventHandler(ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        if (isFhulkerItem(e.getItem())) e.setCancelled(true);
    }

    // ------------------------------------------------- breaking

    /** Player breaks: the marker/extras ride on the dropped item via BlockDropItemEvent. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!(b.getState() instanceof ShulkerBox box) || !isFhulkerBlock(box)) return;
        ItemStack[] extra = detach(b);

        // Creative: vanilla spawns the box item directly (skipping BlockDropItemEvent),
        // and only when it has contents. Empty the block so vanilla drops nothing,
        // then drop the complete Fhulker ourselves under the same rule.
        if (e.getPlayer().getGameMode() == GameMode.CREATIVE) {
            if (!(b.getState() instanceof ShulkerBox clean)) return;
            boolean hasVanilla = Arrays.stream(clean.getSnapshotInventory().getContents()).anyMatch(i -> !isEmpty(i));
            if (!hasVanilla && !hasAny(extra)) return; // vanilla drops nothing for an empty box
            ItemStack drop = itemFromBlock(clean);
            markItem(drop, extra);
            clean.getSnapshotInventory().clear();
            clean.update(true, false);
            spill(b.getLocation(), new ItemStack[]{drop});
            return;
        }

        BlockKey k = BlockKey.of(b);
        pendingDrops.put(k, new Pending(b.getLocation(), extra));
        // If no box item dropped (creative, doTileDrops off), spill the extras next tick.
        getServer().getScheduler().runTask(this, () -> {
            Pending p = pendingDrops.remove(k);
            if (p != null) spill(p.loc(), p.extra());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent e) {
        BlockKey k = BlockKey.of(e.getBlock());
        Pending p = pendingDrops.get(k);
        if (p == null) return;
        for (Item item : e.getItems()) {
            ItemStack s = item.getItemStack();
            if (!isShulker(s)) continue;
            markItem(s, p.extra());
            item.setItemStack(s);
            pendingDrops.remove(k);
            return;
        }
    }

    /** Pistons and liquids (Paper event): rewrite the drop in place. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreakBlock(BlockBreakBlockEvent e) {
        Block b = e.getBlock();
        if (!(b.getState() instanceof ShulkerBox box) || !isFhulkerBlock(box)) return;
        ItemStack[] extra = detach(b);
        ListIterator<ItemStack> it = e.getDrops().listIterator();
        while (it.hasNext()) {
            ItemStack s = it.next();
            if (!isShulker(s)) continue;
            markItem(s, extra);
            it.set(s);
            return;
        }
        spill(b.getLocation(), extra);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        exploded(e.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        exploded(e.blockList());
    }

    /** Take Fhulkers out of the explosion and drop them ourselves, fully intact. */
    private void exploded(List<Block> blocks) {
        Iterator<Block> it = blocks.iterator();
        while (it.hasNext()) {
            Block b = it.next();
            if (!(b.getState() instanceof ShulkerBox box) || !isFhulkerBlock(box)) continue;
            it.remove();
            ItemStack[] extra = detach(b);
            if (!(b.getState() instanceof ShulkerBox clean)) continue;
            ItemStack drop = itemFromBlock(clean);
            markItem(drop, extra);
            Location loc = b.getLocation();
            b.setType(Material.AIR, false);
            getServer().getScheduler().runTask(this, () -> spill(loc, new ItemStack[]{drop}));
        }
    }

    /** Ends any open session, then strips the marker and extras from the block and returns the extras. */
    private ItemStack[] detach(Block b) {
        BoxHolder h = sessions.remove(BlockKey.of(b));
        if (h != null) {
            save(h);
            for (HumanEntity v : new ArrayList<>(h.inv.getViewers())) v.closeInventory();
        }
        if (!(b.getState() instanceof ShulkerBox box)) return new ItemStack[0];
        PersistentDataContainer pdc = box.getPersistentDataContainer();
        ItemStack[] extra = readExtra(pdc);
        pdc.remove(extraKey);
        pdc.remove(markerKey);
        box.update(true, false); // cleared so nothing downstream can handle it twice
        return extra; // keep empty entries so slot positions survive the round trip
    }

    private static boolean hasAny(ItemStack[] items) {
        for (ItemStack i : items) if (!isEmpty(i)) return true;
        return false;
    }

    // ------------------------------------------------------------- helpers

    /** Turns a shulker box item into a Fhulker, optionally storing extra slots. */
    private void markItem(ItemStack s, ItemStack[] extra) {
        ItemMeta m = s.getItemMeta();
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        pdc.set(markerKey, PersistentDataType.BYTE, (byte) 1);
        if (extra != null) writeExtra(pdc, extra);

        int used = 0;
        if (m instanceof BlockStateMeta bsm && bsm.hasBlockState()
                && bsm.getBlockState() instanceof ShulkerBox box) {
            for (ItemStack i : box.getInventory().getContents()) if (!isEmpty(i)) used++;
        }
        ItemStack[] stored = readExtra(pdc);
        for (int i = 0; i < stored.length && VANILLA + i < SIZE; i++) if (!isEmpty(stored[i])) used++;
        int free = Math.max(0, SIZE - used);

        m.itemName(Component.text("Fhulker"));
        m.lore(List.of(Component.text(SIZE + " slots, " + free + " free", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        s.setItemMeta(m);
    }

    private static ItemStack itemFromBlock(ShulkerBox box) {
        ItemStack it = new ItemStack(box.getType());
        BlockStateMeta m = (BlockStateMeta) it.getItemMeta();
        m.setBlockState(box);
        if (box.customName() != null) m.customName(box.customName());
        it.setItemMeta(m);
        return it;
    }

    private boolean isFhulkerBlock(ShulkerBox box) {
        return box.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    private boolean isFhulkerItem(ItemStack i) {
        return isShulker(i) && i.hasItemMeta()
                && i.getItemMeta().getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    private ItemStack[] readExtra(PersistentDataContainer pdc) {
        byte[] data = pdc.get(extraKey, PersistentDataType.BYTE_ARRAY);
        if (data == null) return new ItemStack[0];
        return ItemStack.deserializeItemsFromBytes(data);
    }

    private void writeExtra(PersistentDataContainer pdc, ItemStack[] items) {
        ItemStack[] out = new ItemStack[items.length];
        boolean any = false;
        for (int i = 0; i < items.length; i++) {
            boolean empty = isEmpty(items[i]);
            out[i] = empty ? ItemStack.empty() : items[i];
            any |= !empty;
        }
        if (any) pdc.set(extraKey, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(out));
        else pdc.remove(extraKey);
    }

    private static void spill(Location loc, ItemStack[] items) {
        Location c = loc.clone().add(0.5, 0.5, 0.5);
        for (ItemStack i : items) {
            if (!isEmpty(i)) loc.getWorld().dropItemNaturally(c, i);
        }
    }

    private static boolean isEmpty(ItemStack i) {
        return i == null || i.getType().isAir() || i.getAmount() <= 0;
    }

    private static boolean isShulker(ItemStack i) {
        return i != null && Tag.SHULKER_BOXES.isTagged(i.getType());
    }
}

