package local.bpfix.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.p3pp3rf1y.sophisticatedcore.common.gui.IFilterSlot;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import net.p3pp3rf1y.sophisticatedcore.compat.recipeviewers.common.SetGhostSlotPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Older Sophisticated Core builds wrote the incoming stack into any menu slot with a bare
 * {@code Slot.set}. Newer builds route through a validated {@code setGhostSlot}. This guard makes
 * the old builds behave like the new one, so servers that are pinned to 1.4.x for addon reasons are
 * covered as well. On 1.5.x and later the original code validates again - this is a no-op there.
 */
@Mixin(value = SetGhostSlotPayload.class, remap = false)
public abstract class SetGhostSlotPayloadMixin {

    @Inject(method = "handlePayload", at = @At("HEAD"), cancellable = true, require = 0)
    private static void bpfix$validateGhostSlotWrite(SetGhostSlotPayload payload, IPayloadContext context,
                                                     CallbackInfo ci) {
        if (context == null) {
            return;
        }
        Player player = context.player();
        if (player == null || !(player.containerMenu instanceof StorageContainerMenuBase<?> menu)) {
            return;
        }
        int slotNumber = payload.slotNumber();
        if (slotNumber < 0 || slotNumber >= menu.getTotalSlotsNumber()) {
            ci.cancel();
            return;
        }
        Slot slot = menu.getSlot(slotNumber);
        ItemStack stack = payload.stack();
        if (!(slot instanceof IFilterSlot) || !slot.isActive() || !slot.mayPlace(stack)) {
            ci.cancel();
        }
    }
}
