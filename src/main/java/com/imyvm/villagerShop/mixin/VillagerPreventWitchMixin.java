package com.imyvm.villagerShop.mixin;

import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Villager.class)
public class VillagerPreventWitchMixin {

    @Inject(method = "thunderHit", at = @At("HEAD"), cancellable = true)
    private void preventWitchTransformation(CallbackInfo ci) {
        Villager villager = (Villager) (Object) this;
        if (villager.entityTags().contains("VillagerShop")) {
            ci.cancel();
        }
    }
}
