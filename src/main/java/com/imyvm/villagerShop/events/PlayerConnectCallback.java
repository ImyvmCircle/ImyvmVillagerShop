package com.imyvm.villagerShop.events;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

public interface PlayerConnectCallback {
    Event<@NotNull PlayerConnectCallback> EVENT = EventFactory.createArrayBacked(
            PlayerConnectCallback.class,
            (listeners) -> (player) -> {
                for (PlayerConnectCallback event : listeners) {
                    event.onPlayerConnect(player);
                }
            });

    void onPlayerConnect(ServerPlayer player);
}
