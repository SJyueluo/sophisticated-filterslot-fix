package local.bpclient;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import net.p3pp3rf1y.sophisticatedcore.common.gui.IFilterSlot;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import net.p3pp3rf1y.sophisticatedcore.network.TransferFullSlotPayload;

/**
 * Reproduction-only client probe for the filter-slot whole-slot-transfer defect.
 *
 * <p>Deliberately limited so it cannot be used as a cheat on someone else's server:
 * <ul>
 *   <li>refuses to act unless the session is single-player or a loopback address;</li>
 *   <li>never takes a slot id from the user ?it only addresses filter slots that are currently
 *       open in the menu;</li>
 *   <li>does nothing unless the user presses the (unbound by default) key;</li>
 *   <li>only reports what happened; it does not repeat, loop or script anything.</li>
 * </ul>
 * Intended use: reproduce and document the defect on your own machine.
 */
@Mod(value = BackpackClientDemo.MODID, dist = Dist.CLIENT)
public final class BackpackClientDemo {
    public static final String MODID = "backpack_client_demo";
    private static final String CATEGORY = "Backpack Demo (local only)";

    private static final int GLFW_KEY = 75;
    private static boolean probeKeyWasDown;
    private static int pendingTicks = -1;
    private static int pendingSlot = -1;
    private static Item pendingItem;
    private static int beforeStorage;
    private static int beforeInventory;
    private static String pendingAddress = "";
    private static String refusal = "";

    public BackpackClientDemo(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
    }

    private void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        // Raw polling: works with any screen open, which event-based key handling does not.
        boolean typing = minecraft.screen != null && !(minecraft.screen instanceof AbstractContainerScreen<?>);
        boolean down = !typing && isProbeKeyDown(minecraft);
        if (down && !probeKeyWasDown) {
            attempt(minecraft);
        }
        probeKeyWasDown = down;

        if (pendingTicks > 0 && --pendingTicks == 0) {
            report(minecraft);
        }
    }

    private static boolean isProbeKeyDown(Minecraft minecraft) {
        try {
            long handle = minecraft.getWindow().getWindow();
            return InputConstants.isKeyDown(handle, GLFW_KEY);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------ send

    private static void attempt(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.getConnection() == null) {
            return;
        }
        if (!isLocalSession(minecraft)) {
            message(minecraft, "[backpack-demo] refused: this probe only runs in single-player or on a loopback address (saw: " + safeAddress(minecraft) + ").");
            return;
        }
        if (!(minecraft.player.containerMenu instanceof StorageContainerMenuBase<?> menu)) {
            message(minecraft, "[backpack-demo] open a backpack first.");
            return;
        }
        List<Slot> filterSlots = new ArrayList<>();
        for (Slot slot : menu.upgradeSlots) {
            if (slot instanceof IFilterSlot && slot.hasItem()) {
                filterSlots.add(slot);
            }
        }
        if (filterSlots.isEmpty()) {
            message(minecraft, "[backpack-demo] put an item into a filter slot first (upgrade tab).");
            return;
        }
        Slot target = filterSlots.get(0);
        ItemStack sample = target.getItem();
        pendingItem = sample.getItem();
        pendingSlot = target.index;
        pendingAddress = currentAddress(minecraft);
        beforeStorage = countStorage(menu, pendingItem);
        beforeInventory = countInventory(minecraft, pendingItem);

        // POLICY (do not remove): this probe may only ever talk to the local server. Re-checked at the
        // send site so that no future change to the key handler can turn it into a remote-capable client.
        if (!isLocalSession(minecraft)) {
            message(minecraft, "[backpack-demo] refused at send time: not a loopback session (saw: " + safeAddress(minecraft) + ").");
            pendingSlot = -1;
            pendingTicks = 0;
            return;
        }
        PacketDistributor.sendToServer(new TransferFullSlotPayload(pendingSlot));

        pendingTicks = 10;
        message(minecraft, "[backpack-demo] sent transfer_full_slot for filter slot " + pendingSlot
                + " (" + name(sample) + " x" + sample.getCount() + ")");
    }

    // ------------------------------------------------------------------ report

    private static void report(Minecraft minecraft) {
        if (minecraft.player == null || !(minecraft.player.containerMenu instanceof StorageContainerMenuBase<?> menu)) {
            pendingTicks = -1;
            return;
        }
        int afterStorage = countStorage(menu, pendingItem);
        int afterInventory = countInventory(minecraft, pendingItem);
        int storageDelta = afterStorage - beforeStorage;
        int inventoryDelta = afterInventory - beforeInventory;
        int net = storageDelta + inventoryDelta;

        String itemName = BuiltInRegistries.ITEM.getKey(pendingItem).toString();
        String verdict = net > 0 ? "DUPLICATED (+" + net + ")" : (net == 0 ? "no change" : "lost item");
        message(minecraft, "[backpack-demo] " + itemName + ": storage " + beforeStorage + " -> " + afterStorage
                + ", inventory " + beforeInventory + " -> " + afterInventory + " | net " + net + " => " + verdict);

        JsonObject report = new JsonObject();
        report.addProperty("recordedAt", OffsetDateTime.now().toString());
        report.addProperty("address", pendingAddress);
        report.addProperty("slotId", pendingSlot);
        report.addProperty("item", itemName);
        report.addProperty("storageBefore", beforeStorage);
        report.addProperty("storageAfter", afterStorage);
        report.addProperty("inventoryBefore", beforeInventory);
        report.addProperty("inventoryAfter", afterInventory);
        report.addProperty("netGain", net);
        report.addProperty("verdict", verdict);
        try {
            Path dir = minecraft.gameDirectory.toPath().resolve("backpack-demo");
            Files.createDirectories(dir);
            String stamp = OffsetDateTime.now().toString().replace(':', '-').replace('.', '-');
            Path out = dir.resolve("evidence-" + stamp + ".json");
            Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
            message(minecraft, "[backpack-demo] written to " + out.toAbsolutePath());
        } catch (Exception e) {
            message(minecraft, "[backpack-demo] could not write evidence: " + e);
        }
        pendingTicks = -1;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isLocalSession(Minecraft minecraft) {
        String address = currentAddress(minecraft);
        if (address.isEmpty()) {
            return true;
        }
        String host = stripPort(address);
        // Minecraft rewrites 127.0.0.1 to this mapped form when IPv6 is preferred; it is the local server.
        if (host.contains("fdfe:dcba:9876:")) {
            return true;
        }
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (Exception ignored) {
            return host.equals("localhost");
        }
    }

    private static String stripPort(String address) {
        String value = address;
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            return close > 0 ? value.substring(1, close) : value;
        }
        int firstColon = value.indexOf(':');
        int lastColon = value.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon) {
            return value.substring(0, firstColon);
        }
        return value;
    }

    private static String safeAddress(Minecraft minecraft) {
        String address = currentAddress(minecraft);
        return address.isEmpty() ? "<none>" : address;
    }

    private static String currentAddress(Minecraft minecraft) {
        if (minecraft.getConnection() == null) {
            return "";
        }
        ServerData data = minecraft.getConnection().getServerData();
        return data == null || data.ip == null ? "" : data.ip.trim().toLowerCase();
    }

    private static int countStorage(StorageContainerMenuBase<?> menu, Item item) {
        int total = 0;
        int limit = Math.min(menu.getNumberOfStorageInventorySlots(), menu.slots.size());
        for (int i = 0; i < limit; i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countInventory(Minecraft minecraft, Item item) {
        int total = 0;
        for (ItemStack stack : minecraft.player.getInventory().items) {
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static String name(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static void message(Minecraft minecraft, String text) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(text), false);
        }
    }
}
