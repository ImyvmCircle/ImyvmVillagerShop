package com.imyvm.villagerShop.mixin;

import net.minecraft.world.entity.ai.behavior.YieldJobSite;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(YieldJobSite.class)
public class VillagerKeepJobMixin {
    @Inject(method = "canReachPos", at = @At("HEAD"), cancellable = true)
    private static void keepJob(PathfinderMob nearbyVillager, BlockPos poiPos, PoiType type, CallbackInfoReturnable<Boolean> cir) {
        if (nearbyVillager.entityTags().contains("VillagerShop")) {
            cir.setReturnValue(false);
        }
    }
}
