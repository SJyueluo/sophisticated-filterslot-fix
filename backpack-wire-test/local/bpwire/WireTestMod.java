package local.bpwire;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContext;
import net.p3pp3rf1y.sophisticatedcore.common.gui.FilterSlotItemHandler;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import net.p3pp3rf1y.sophisticatedcore.network.TransferFullSlotPayload;

/**
 * Packet-path harness: builds the exact packet a client would send and feeds it through NeoForge's
 * real serverbound dispatch (mock connection + ServerGamePacketListenerImpl), instead of calling the
 * mod handler directly. No graphical client involved.
 */
@Mod(WireTestMod.MODID)
public final class WireTestMod {
    public static final String MODID = "backpack_wire_test";

    private static final String BACKPACK_ID = "sophisticatedbackpacks:netherite_backpack";
    private static final String PICKUP_ID = "sophisticatedbackpacks:pickup_upgrade";
    private static final String PROBE_ID = System.getProperty("backpack.wire.item", "minecraft:dirt");

    private static int ticks;
    private static boolean dispatched;
    private static boolean finished;
    private static FakePlayer player;
    private static IBackpackWrapper wrapper;
    private static StorageContainerMenuBase<?> menu;
    private static int filterIndex = -1;
    private static int beforeStorage;
    private static final JsonObject REPORT = new JsonObject();

    public WireTestMod(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(this::onStarted);
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    private void onStarted(ServerStartedEvent event) {
        System.out.println("[wire-test] loaded; packet-path test will run in a few ticks");
    }

    private void onTick(ServerTickEvent.Post event) {
        if (finished || !event.getServer().isRunning()) {
            return;
        }
        MinecraftServer server = event.getServer();
        ticks++;
        try {
            if (!dispatched && ticks >= 20) {
                dispatched = true;
                prepare(server);
                dispatch(server);
            } else if (dispatched && ticks >= 40) {
                finished = true;
                verify(server);
            }
        } catch (Throwable t) {
            finished = true;
            REPORT.addProperty("error", t.toString());
            t.printStackTrace(System.out);
            write();
            server.halt(false);
        }
    }

    private static void prepare(MinecraftServer server) throws Exception {
        REPORT.addProperty("startedAt", OffsetDateTime.now().toString());
        REPORT.addProperty("backpacks", modVersion("sophisticatedbackpacks"));
        REPORT.addProperty("core", modVersion("sophisticatedcore"));
        REPORT.addProperty("neoforge", modVersion("neoforge"));
        REPORT.addProperty("probeItem", PROBE_ID);
        REPORT.addProperty("hasFixMod", ModList.get().isLoaded("backpack_transfer_fix"));

        ServerLevel level = server.overworld();
        player = new FakePlayer(level, new GameProfile(
                UUID.nameUUIDFromBytes("wire-test".getBytes(StandardCharsets.UTF_8)), "WireTest"));
        player.setPos(0, level.getMinBuildHeight() + 100, 0);

        ItemStack backpack = stack(BACKPACK_ID);
        IBackpackWrapper wrapper = BackpackWrapper.fromStack(backpack);
        if (wrapper.getContentsUuid().isEmpty()) {
            wrapper.setContentsUuid(UUID.randomUUID());
        }
        WireTestMod.wrapper = wrapper;
        wrapper.getUpgradeHandler().setStackInSlot(0, stack(PICKUP_ID));
        player.getInventory().items.set(0, backpack);

        BackpackContainer container = new BackpackContainer(1, player, new BackpackContext.Item("main", 0));
        menu = container;
        player.containerMenu = container;
        container.setOpenTabId(0);

        List<Slot> filters = new ArrayList<>();
        for (Slot slot : container.upgradeSlots) {
            if (slot instanceof FilterSlotItemHandler) {
                filters.add(slot);
            }
        }
        if (filters.isEmpty()) {
            throw new IllegalStateException("no filter slot");
        }
        filterIndex = filters.get(0).index;
        REPORT.addProperty("filterSlotIndex", filterIndex);

        // Place the sample through the normal click path (what a player does legitimately).
        menu.setCarried(stack(PROBE_ID));
        menu.clicked(filterIndex, 0, ClickType.PICKUP, player);
        menu.setCarried(ItemStack.EMPTY);
        REPORT.addProperty("samplePlaced", !menu.getSlot(filterIndex).getItem().isEmpty());

        beforeStorage = countIn(wrapper.getInventoryHandler());
        REPORT.addProperty("storageBefore", beforeStorage);
    }

    private static void dispatch(MinecraftServer server) throws Exception {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        // A detached Connection has no netty channel; NeoForge's channel bookkeeping needs one.
        java.lang.reflect.Field channelField = Connection.class.getDeclaredField("channel");
        channelField.setAccessible(true);
        channelField.set(connection, new io.netty.channel.embedded.EmbeddedChannel());
        NetworkRegistry.configureMockConnection(connection);
        ServerGamePacketListenerImpl listener = new ServerGamePacketListenerImpl(
                server, connection, player, CommonListenerCookie.createInitial(player.getGameProfile(), false));

        ServerboundCustomPayloadPacket packet =
                new ServerboundCustomPayloadPacket(new TransferFullSlotPayload(filterIndex));
        REPORT.addProperty("packetType", packet.type().toString());
        NetworkRegistry.handleModdedPayload(listener, packet);
        REPORT.addProperty("dispatchReturned", true);
    }

    private static void verify(MinecraftServer server) {
        int after = countIn(wrapper.getInventoryHandler());
        int net = after - beforeStorage;
        REPORT.addProperty("storageAfter", after);
        REPORT.addProperty("netGain", net);
        REPORT.addProperty("verdict", net > 0 ? "DUPLICATED" : "BLOCKED");
        REPORT.addProperty("finishedAt", OffsetDateTime.now().toString());
        write();
        System.out.println("[wire-test] probe=" + PROBE_ID + " storage " + beforeStorage + " -> " + after
                + " net=" + net + " => " + REPORT.get("verdict").getAsString());
        try {
            wrapper.removeContentsUuid();
        } catch (Throwable ignored) {
            // best effort
        }
        server.halt(false);
    }

    private static ItemStack stack(String id) {
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            throw new IllegalStateException("missing item " + id);
        }
        return new ItemStack(item, 1);
    }

    private static int countIn(net.neoforged.neoforge.items.IItemHandler handler) {
        ResourceLocation rl = ResourceLocation.parse(PROBE_ID);
        int total = 0;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack s = handler.getStackInSlot(i);
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).equals(rl)) {
                total += s.getCount();
            }
        }
        return total;
    }

    private static String modVersion(String modId) {
        return ModList.get().getModContainerById(modId).map(c -> c.getModInfo().getVersion().toString()).orElse("<absent>");
    }

    private static void write() {
        try {
            Path dir = Path.of("wire-test");
            Files.createDirectories(dir);
            String stamp = OffsetDateTime.now().toString().replace(':', '-').replace('.', '-');
            Files.writeString(dir.resolve("result-" + stamp + ".json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(REPORT), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[wire-test] write failed: " + e);
        }
    }
}
