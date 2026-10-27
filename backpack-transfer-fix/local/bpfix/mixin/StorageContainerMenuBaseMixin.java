package local.bpfix.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.common.gui.IFilterSlot;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Second line of defence: the shared quick-move implementation itself refuses to treat a filter
 * sample slot as a transfer source, no matter which caller reaches it. Only filter slots are
 * rejected - crafting ingredients/results and other upgrade settings slots keep their behaviour.
 */
@Mixin(value = StorageContainerMenuBase.class, remap = false)
public abstract class StorageContainerMenuBaseMixin {

    @Inject(
            method = "quickMoveStack(Lnet/minecraft/world/entity/player/Player;I)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void bpfix$refuseFilterSource(Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
        StorageContainerMenuBase<?> menu = (StorageContainerMenuBase<?>) (Object) this;
        if (index < 0 || index >= menu.getTotalSlotsNumber()) {
            cir.setReturnValue(ItemStack.EMPTY);
            return;
        }
        Slot slot = menu.getSlot(index);
        if (slot instanceof IFilterSlot) {
            cir.setReturnValue(ItemStack.EMPTY);
        }
    }
}
