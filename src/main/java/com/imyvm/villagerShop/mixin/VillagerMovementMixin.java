package com.imyvm.villagerShop.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LivingEntity.class)
public class VillagerMovementMixin {

        @Redirect(method = "aiStep", at = @At(value = "INVOKE",target = "Lnet/minecraft/world/entity/LivingEntity;setDeltaMovement(DDD)V"))
        private void modifySetVelocity(LivingEntity entity, double x, double y, double z) {
                if (entity instanceof Villager && entity.entityTags().contains("VillagerShop")) {
                        entity.setDeltaMovement(0.0, y, 0.0);
                } else {
                        entity.setDeltaMovement(x, y, z);
                }
        }

        @Redirect(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;travel(Lnet/minecraft/world/phys/Vec3;)V"))
        private void modifyTravel(LivingEntity entity, Vec3 input) {
                if (entity instanceof Villager && entity.entityTags().contains("VillagerShop")) {
                        entity.travel(new Vec3(0.0, input.y, 0.0));
                } else {
                        entity.travel(input);
                }
        }
}



