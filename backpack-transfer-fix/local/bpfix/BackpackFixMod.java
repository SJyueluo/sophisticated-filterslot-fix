package local.bpfix;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * Server-side hardening for the Sophisticated Backpacks / Core filter-slot transfer defect.
 *
 * <p>Two mixins work together:
 * <ul>
 *   <li>{@code TransferFullSlotPayload.handlePayload} is cancelled when the target slot is a filter
 *       slot, belongs to an upgrade settings container, is inactive, or is out of range.</li>
 *   <li>{@code StorageContainerMenuBase.quickMoveStack} refuses filter/upgrade-settings sources as a
 *       second line of defence, so other callers cannot reach the same write path.</li>
 * </ul>
 *
 * <p>Normal storage, player-inventory and upgrade-item transfers are unaffected.
 */
@Mod(BackpackFixMod.MODID)
public final class BackpackFixMod {
    public static final String MODID = "backpack_transfer_fix";

    public BackpackFixMod(IEventBus modEventBus) {
        System.out.println("[backpack-fix] active: filter-slot / upgrade-settings transfers are rejected server-side");
    }
}
