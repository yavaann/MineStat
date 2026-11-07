package fr.statspanel.mixin;

import fr.statspanel.XpTracker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Niveaux dépensés à l'enclume (réparation, combinaison, renommage). */
@Mixin(AnvilMenu.class)
abstract class AnvilMenuMixin {
    @Unique
    private int statspanel$levelsBefore;

    @Inject(method = "onTake", at = @At("HEAD"))
    private void statspanel$beforeTake(Player player, ItemStack stack, CallbackInfo ci) {
        statspanel$levelsBefore = player.experienceLevel;
    }

    @Inject(method = "onTake", at = @At("RETURN"))
    private void statspanel$afterTake(Player player, ItemStack stack, CallbackInfo ci) {
        if (player instanceof ServerPlayer) {
            XpTracker.record(player.getUUID(), statspanel$levelsBefore - player.experienceLevel);
        }
    }
}
