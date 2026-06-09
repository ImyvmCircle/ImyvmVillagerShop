package com.imyvm.villagerShop.mixin;

import com.imyvm.villagerShop.events.PlayerConnectCallback;
import net.minecraft.server.notifications.NotificationManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NotificationManager.class)
public class PlayerManagerMixin {
    @Inject(
            method = "playerJoined",
            at = @At("HEAD")
    )
    private void playerJoined(ServerPlayer player, CallbackInfo ci) {
        PlayerConnectCallback.EVENT.invoker().onPlayerConnect(player);
    }
}
