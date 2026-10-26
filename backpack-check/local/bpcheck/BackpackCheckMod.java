package local.bpcheck;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContext;
import net.p3pp3rf1y.sophisticatedcore.common.gui.FilterSlotItemHandler;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import net.p3pp3rf1y.sophisticatedcore.network.TransferFullSlotPayload;

/**
 * Server-side self-check for the Sophisticated Backpacks filter-slot transfer defect.
 *
 * <p>Drop into a server's mods folder and start once. It builds a throwaway backpack belonging to a
 * fake player, exercises the real server code paths, prints a verdict for both exposure tiers, then
 * removes the throwaway backpack storage again. It never touches real players, blocks or worlds and
 * does not leave items behind.
 */
@Mod(BackpackCheckMod.MODID)
public final class BackpackCheckMod {
    public static final String MODID = "backpack_check";

    private static final String BACKPACK_ID = "sophisticatedbackpacks:netherite_backpack";
    private static final String PICKUP_ID = "sophisticatedbackpacks:pickup_upgrade";
    private static final String MARKER_ID = System.getProperty("backpack.check.item", "minecraft:diamond");
    private static final String CONTROL_ID = "minecraft:dirt";
    private static final String UNOWNED_ID = "sophisticatedbackpacks:survival_infinity_upgrade";

    private static int ticks;
    private static boolean done;

    public BackpackCheckMod(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(BackpackCheckMod::onServerStarted);
        NeoForge.EVENT_BUS.addListener(BackpackCheckMod::onTick);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        log("loaded; check will run shortly");
    }

    private static void onTick(ServerTickEvent.Post event) {
        if (done || !event.getServer().isRunning()) {
            return;
        }
        if (++ticks < 20) {
            return;
        }
        done = true;
        MinecraftServer server = event.getServer();
        JsonObject report = new JsonObject();
        report.addProperty("checkedAt", OffsetDateTime.now().toString());
        try {
            runCheck(server, report);
        } catch (Throwable t) {
            report.addProperty("error", t.toString());
            log("check failed: " + t);
            t.printStackTrace(System.out);
        }
        writeReport(report);
        printSummary(report);
        if (Boolean.getBoolean("backpack.check.exit")) {
            server.halt(false);
        }
    }

    // ------------------------------------------------------------------ check

    private static void runCheck(MinecraftServer server, JsonObject report) throws IOException {
        report.addProperty("backpacks", modVersion("sophisticatedbackpacks"));
        report.addProperty("core", modVersion("sophisticatedcore"));
        report.addProperty("neoforge", modVersion("neoforge"));
        report.addProperty("probeItem", MARKER_ID);
        if (!ModList.get().isLoaded("sophisticatedbackpacks") || !ModList.get().isLoaded("sophisticatedcore")) {
            report.addProperty("skipped", "sophisticatedbackpacks/sophisticatedcore not installed");
            return;
        }

        report.addProperty("registered_transfer_full_slot", isServerboundRegistered("sophisticatedcore:transfer_full_slot"));
        report.addProperty("registered_set_ghost_slot", isServerboundRegistered("sophisticatedcore:set_ghost_slot"));

        ServerLevel level = server.overworld();
        FakePlayer player = new FakePlayer(level, new GameProfile(
                UUID.nameUUIDFromBytes("backpack-check".getBytes(StandardCharsets.UTF_8)), "BackpackCheck"));
        player.setPos(0, level.getMinBuildHeight() + 100, 0);

        ItemStack backpack = stack(BACKPACK_ID, 1);
        IBackpackWrapper wrapper = BackpackWrapper.fromStack(backpack);
        UUID throwaway = wrapper.getContentsUuid().orElseGet(() -> {
            UUID created = UUID.randomUUID();
            wrapper.setContentsUuid(created);
            return created;
        });
        report.addProperty("throwawayUuid", throwaway.toString());
        if (wrapper.getUpgradeHandler().getSlots() < 1) {
            report.addProperty("skipped", "backpack exposes no upgrade slot");
            return;
        }
        wrapper.getUpgradeHandler().setStackInSlot(0, stack(PICKUP_ID, 1));
        player.getInventory().items.set(0, backpack);

        BackpackContainer menu = new BackpackContainer(1, player, new BackpackContext.Item("main", 0));
        player.containerMenu = menu;
        report.addProperty("menuClass", menu.getClass().getName());
        menu.setOpenTabId(0);

        List<Slot> filterSlots = new ArrayList<>();
        for (Slot slot : menu.upgradeSlots) {
            if (slot instanceof FilterSlotItemHandler) {
                filterSlots.add(slot);
            }
        }
        report.addProperty("filterSlotCount", filterSlots.size());
        if (filterSlots.isEmpty()) {
            report.addProperty("skipped", "no filter slot (is a filter upgrade installed?)");
            return;
        }
        Slot filter = filterSlots.get(0);
        int filterIndex = filter.index;
        report.addProperty("filterSlotIndex", filterIndex);
        report.addProperty("filterMayPickup", filter.mayPickup(player));

        // The player legitimately owns one marker item.
        player.getInventory().items.set(5, stack(MARKER_ID, 1));
        IItemHandlerModifiable storage = (IItemHandlerModifiable) wrapper.getInventoryHandler();

        // --- negative control: a normal click must not hand out the sample.
        placeSample(menu, player, filter, filterIndex, stack(MARKER_ID, 1));
        report.addProperty("ghostSampleAccepted", !filter.getItem().isEmpty());
        int markerBeforeClick = countItems(player.getInventory().items, MARKER_ID);
        menu.setCarried(ItemStack.EMPTY);
        boolean clickThrew = false;
        try {
            menu.clicked(filterIndex, 0, ClickType.PICKUP, player);
        } catch (Throwable t) {
            clickThrew = true;
        }
        report.addProperty("normalClickThrew", clickThrew);
        report.addProperty("normalClickGain", countItems(player.getInventory().items, MARKER_ID) - markerBeforeClick);

        // --- Tier 1: duplicate an item the player already owns.
        placeSample(menu, player, filter, filterIndex, stack(MARKER_ID, 1));
        int playerBefore = countItems(player.getInventory().items, MARKER_ID);
        int storageBefore = countInHandler(storage, MARKER_ID);
        TransferFullSlotPayload.handlePayload(new TransferFullSlotPayload(filterIndex), contextOf(player));
        int playerAfter = countItems(player.getInventory().items, MARKER_ID);
        int storageAfter = countInHandler(storage, MARKER_ID);
        int tier1Gain = (playerAfter + storageAfter) - (playerBefore + storageBefore);
        report.addProperty("tier1Gain", tier1Gain);

        int repeatGain = 0;
        for (int i = 0; i < 5; i++) {
            int before = countInHandler(storage, MARKER_ID);
            placeSample(menu, player, filter, filterIndex, stack(MARKER_ID, 1));
            TransferFullSlotPayload.handlePayload(new TransferFullSlotPayload(filterIndex), contextOf(player));
            repeatGain += countInHandler(storage, MARKER_ID) - before;
        }
        report.addProperty("tier1RepeatGain", repeatGain);

        // --- Tier 2: create an item the player never owned.
        int unownedBefore = countInHandler(storage, UNOWNED_ID);
        boolean ghostAccepted = placeUnowned(menu, player, filter, filterIndex, stack(UNOWNED_ID, 1));
        report.addProperty("setGhostSlotAvailable", ghostAccepted || hasSetGhostSlot(menu));
        TransferFullSlotPayload.handlePayload(new TransferFullSlotPayload(filterIndex), contextOf(player));
        int tier2Gain = countInHandler(storage, UNOWNED_ID) - unownedBefore;
        report.addProperty("tier2GhostAccepted", ghostAccepted);
        report.addProperty("tier2Gain", tier2Gain);

        // --- extra: some builds let the ghost-slot message write a real storage slot directly.
        int directBefore = countInHandler(storage, MARKER_ID);
        boolean directAccepted = false;
        try {
            Class<?> payloadClass = Class.forName(
                    "net.p3pp3rf1y.sophisticatedcore.compat.recipeviewers.common.SetGhostSlotPayload");
            Object payload = payloadClass.getConstructor(ItemStack.class, int.class)
                    .newInstance(stack(MARKER_ID, 64), 0);
            java.lang.reflect.Method handle = payloadClass.getMethod("handlePayload", payloadClass, IPayloadContext.class);
            handle.invoke(null, payload, contextOf(player));
            ItemStack written = storage.getStackInSlot(0);
            directAccepted = !written.isEmpty()
                    && BuiltInRegistries.ITEM.getKey(written.getItem()).toString().equals(MARKER_ID)
                    && written.getCount() == 64;
        } catch (Throwable t) {
            report.addProperty("directWriteError", t.toString());
        }
        report.addProperty("directStorageWriteAccepted", directAccepted);
        report.addProperty("directStorageWriteDelta", countInHandler(storage, MARKER_ID) - directBefore);
        storage.setStackInSlot(0, ItemStack.EMPTY);

        // --- negative control: bogus slot id.
        int allBefore = totalStacks(storage);
        boolean bogusThrew = false;
        try {
            TransferFullSlotPayload.handlePayload(new TransferFullSlotPayload(9999), contextOf(player));
            TransferFullSlotPayload.handlePayload(new TransferFullSlotPayload(-1), contextOf(player));
        } catch (Throwable t) {
            bogusThrew = true;
            report.addProperty("bogusSlotError", t.toString());
        }
        report.addProperty("bogusSlotThrew", bogusThrew);
        report.addProperty("bogusSlotDelta", totalStacks(storage) - allBefore);

        // --- regression control: normal storage slot shift-click must keep working.
        storage.setStackInSlot(0, stack(CONTROL_ID, 3));
        int controlBefore = countItems(player.getInventory().items, CONTROL_ID);
        try {
            menu.quickMoveStack(player, 0);
        } catch (Throwable t) {
            report.addProperty("regressionControlError", t.toString());
        }
        report.addProperty("regressionControlMoved", countItems(player.getInventory().items, CONTROL_ID) - controlBefore);

        report.addProperty("hasFixMod", ModList.get().isLoaded("backpack_transfer_fix"));

        // --- verdicts.
        boolean fixedTier1 = tier1Gain <= 0 && repeatGain <= 0;
        boolean fixPresenceMatches = !report.get("hasFixMod").getAsBoolean() || fixedTier1;
        report.addProperty("verdictTier1", fixedTier1 ? "BLOCKED" : "VULNERABLE");
        report.addProperty("verdictTier2", !isServerboundRegistered("sophisticatedcore:set_ghost_slot")
                ? "NOT_REACHABLE" : (tier2Gain > 0 ? "REACHABLE" : "BLOCKED"));
        report.addProperty("regressionOk", report.get("regressionControlMoved").getAsInt() > 0);
        report.addProperty("verdictOverall",
                fixedTier1 ? (report.get("regressionOk").getAsBoolean() ? "FIXED" : "FIXED_BUT_REGRESSION")
                        : (fixPresenceMatches ? "VULNERABLE" : "FIX_INEFFECTIVE"));

        // --- cleanup: the throwaway backpack storage must not be left behind.
        try {
            wrapper.removeContentsUuid();
        } catch (Throwable ignored) {
            // best effort
        }
        player.containerMenu = player.inventoryMenu;
        report.addProperty("cleanedUp", true);
    }

    // ------------------------------------------------------------------ summary

    private static void printSummary(JsonObject r) {
        log("================ result ================");
        log("versions: backpacks=" + str(r, "backpacks") + " core=" + str(r, "core") + " neoforge=" + str(r, "neoforge"));
        log("tier1 (duplicate owned item) : " + str(r, "verdictTier1")
                + "  gain=" + str(r, "tier1Gain") + " repeat5=" + str(r, "tier1RepeatGain"));
        log("tier2 (create unowned item)  : " + str(r, "verdictTier2")
                + "  set_ghost_slot_registered=" + str(r, "registered_set_ghost_slot") + " gain=" + str(r, "tier2Gain"));
        log("negative controls            : click_gain=" + str(r, "normalClickGain")
                + " bogus_slot_threw=" + str(r, "bogusSlotThrew") + " bogus_delta=" + str(r, "bogusSlotDelta"));
        log("regression (normal transfer) : moved=" + str(r, "regressionControlMoved"));
        log("fix mod installed            : " + str(r, "hasFixMod"));
        log("OVERALL                      : " + str(r, "verdictOverall"));
        log("========================================");
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsString() : "-";
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Places a filter sample the way a server processes a real player click: cursor holds the item,
     * then a pickup click on the filter slot. Works across mod versions (no setGhostSlot needed).
     */
    private static boolean placeSample(StorageContainerMenuBase<?> menu,
                                       net.minecraft.world.entity.player.Player player,
                                       Slot filter, int index, ItemStack sample) {
        menu.setCarried(sample.copy());
        try {
            menu.clicked(index, 0, ClickType.PICKUP, player);
        } catch (Throwable t) {
            menu.setCarried(ItemStack.EMPTY);
            return false;
        }
        menu.setCarried(ItemStack.EMPTY);
        return !filter.getItem().isEmpty();
    }

    /**
     * Places an unowned sample the way the recipe-viewer message does it, using the real handler from
     * whichever version is installed. Older builds write the slot directly; newer builds validate it.
     * Falls back to the menu API when the message class is absent.
     */
    private static boolean placeUnowned(StorageContainerMenuBase<?> menu, net.minecraft.world.entity.player.Player player,
                                        Slot filter, int index, ItemStack sample) {
        try {
            Class<?> payloadClass = Class.forName(
                    "net.p3pp3rf1y.sophisticatedcore.compat.recipeviewers.common.SetGhostSlotPayload");
            Object payload = payloadClass.getConstructor(ItemStack.class, int.class).newInstance(sample.copy(), index);
            java.lang.reflect.Method handle = payloadClass.getMethod("handlePayload", payloadClass, IPayloadContext.class);
            handle.invoke(null, payload, contextOf(player));
            if (!filter.getItem().isEmpty()) {
                return true;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            java.lang.reflect.Method method = menu.getClass().getMethod("setGhostSlot", int.class, ItemStack.class);
            method.invoke(menu, index, sample.copy());
        } catch (Throwable t) {
            return false;
        }
        return !filter.getItem().isEmpty();
    }

    private static boolean hasSetGhostSlot(StorageContainerMenuBase<?> menu) {
        try {
            menu.getClass().getMethod("setGhostSlot", int.class, ItemStack.class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isServerboundRegistered(String channel) {
        return NetworkRegistry.getCodec(ResourceLocation.parse(channel),
                net.minecraft.network.ConnectionProtocol.PLAY, PacketFlow.SERVERBOUND) != null;
    }

    private static IPayloadContext contextOf(net.minecraft.world.entity.player.Player player) {
        return new IPayloadContext() {
            @Override
            public net.minecraft.world.entity.player.Player player() {
                return player;
            }

            @Override
            public net.neoforged.neoforge.common.extensions.ICommonPacketListener listener() {
                return null;
            }

            @Override
            public CompletableFuture<Void> enqueueWork(Runnable runnable) {
                runnable.run();
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public <T> CompletableFuture<T> enqueueWork(Supplier<T> supplier) {
                return CompletableFuture.completedFuture(supplier.get());
            }

            @Override
            public PacketFlow flow() {
                return PacketFlow.SERVERBOUND;
            }

            @Override
            public void handle(CustomPacketPayload payload) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void finishCurrentTask(net.minecraft.server.network.ConfigurationTask.Type type) {
            }
        };
    }

    private static ItemStack stack(String id, int count) {
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            throw new IllegalStateException("missing item " + id);
        }
        return new ItemStack(item, count);
    }

    private static int countItems(Iterable<ItemStack> stacks, String id) {
        ResourceLocation rl = ResourceLocation.parse(id);
        int total = 0;
        for (ItemStack s : stacks) {
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).equals(rl)) {
                total += s.getCount();
            }
        }
        return total;
    }

    private static int countInHandler(IItemHandler handler, String id) {
        ResourceLocation rl = ResourceLocation.parse(id);
        int total = 0;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack s = handler.getStackInSlot(i);
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).equals(rl)) {
                total += s.getCount();
            }
        }
        return total;
    }

    private static int totalStacks(IItemHandler handler) {
        int total = 0;
        for (int i = 0; i < handler.getSlots(); i++) {
            total += handler.getStackInSlot(i).getCount();
        }
        return total;
    }

    private static String modVersion(String modId) {
        return ModList.get().getModContainerById(modId).map(c -> c.getModInfo().getVersion().toString()).orElse("<absent>");
    }

    private static void writeReport(JsonObject report) {
        report.addProperty("writtenAt", OffsetDateTime.now().toString());
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(report);
        String stamp = OffsetDateTime.now().toString().replace(':', '-').replace('.', '-');
        try {
            Path dir = Path.of("backpack-check");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("result-" + stamp + ".json"), json, StandardCharsets.UTF_8);
            log("detailed result: " + dir.resolve("result-" + stamp + ".json").toAbsolutePath());
        } catch (IOException e) {
            log("could not write result file: " + e);
        }
    }

    private static void log(String message) {
        System.out.println("[backpack-check] " + message);
    }
}
