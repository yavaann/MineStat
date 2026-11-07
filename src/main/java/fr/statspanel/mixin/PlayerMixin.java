package fr.statspanel.mixin;

import fr.statspanel.XpTracker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Niveaux dépensés à la table d'enchantement. */
@Mixin(Player.class)
abstract class PlayerMixin {
    @Unique
    private int statspanel$levelsBefore;

    @Inject(method = "onEnchantmentPerformed", at = @At("HEAD"))
    private void statspanel$beforeEnchant(ItemStack stack, int cost, CallbackInfo ci) {
        statspanel$levelsBefore = ((Player) (Object) this).experienceLevel;
    }

    @Inject(method = "onEnchantmentPerformed", at = @At("RETURN"))
    private void statspanel$afterEnchant(ItemStack stack, int cost, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (self instanceof ServerPlayer) {
            XpTracker.record(self.getUUID(), statspanel$levelsBefore - self.experienceLevel);
        }
    }
}
