package local.bpfix.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.p3pp3rf1y.sophisticatedcore.common.gui.IFilterSlot;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import net.p3pp3rf1y.sophisticatedcore.network.TransferFullSlotPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rejects the client-driven "transfer this whole slot" request when the addressed slot is a filter
 * sample slot or an upgrade settings slot.
 *
 * <p>The upstream handler validates only the menu type and then trusts the client supplied slot id,
 * so without this guard a filter slot's sample can be merged into real storage.
 */
@Mixin(value = TransferFullSlotPayload.class, remap = false)
public class TransferFullSlotPayloadMixin {

    @Inject(method = "handlePayload", at = @At("HEAD"), cancellable = true)
    private static void bpfix$rejectFilterSlotTransfer(TransferFullSlotPayload payload, IPayloadContext context,
                                                       CallbackInfo ci) {
        Player player = context.player();
        if (!(player.containerMenu instanceof StorageContainerMenuBase<?> menu)) {
            return;
        }
        int slotId = payload.slotId();
        if (slotId < 0 || slotId >= menu.getTotalSlotsNumber()) {
            ci.cancel();
            return;
        }
        Slot slot = menu.getSlot(slotId);
        if (slot instanceof IFilterSlot || menu.getSlotUpgradeContainer(slot).isPresent() || !slot.isActive()) {
            ci.cancel();
        }
    }
}
