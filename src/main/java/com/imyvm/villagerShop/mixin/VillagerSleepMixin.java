package com.imyvm.villagerShop.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class VillagerSleepMixin {
    @Inject(method = "startSleeping", at = @At(value = "HEAD"), cancellable = true)
    private void canNotSleep(BlockPos bedPosition, CallbackInfo ci) {
        LivingEntity villager = (LivingEntity) (Object) this;
        if (villager.entityTags().contains("VillagerShop")) {
            ci.cancel();
        }
    }
}
