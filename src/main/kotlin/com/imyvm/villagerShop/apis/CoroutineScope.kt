package com.imyvm.villagerShop.apis

import com.imyvm.villagerShop.VillagerShopMain
import com.imyvm.villagerShop.apis.Translator.tr
import com.imyvm.villagerShop.commands.pendingOperations
import com.mojang.brigadier.context.CommandContext
import kotlinx.coroutines.*
import net.minecraft.server.command.ServerCommandSource
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

val coroutineContext = SupervisorJob() + Dispatchers.Default
val customScope = CoroutineScope(coroutineContext)

// Tracks the auto-cancel timer Job for each player's pending operation
val pendingOperationJobs = ConcurrentHashMap<UUID, Job>()

fun coroutineScope(context: CommandContext<ServerCommandSource>, duration: Long = 61) {
    val playerUUID = context.source.player!!.uuid
    val job = customScope.launch {
        val result = withTimeoutOrNull(TimeUnit.SECONDS.toMillis(duration)) {
            delay(TimeUnit.SECONDS.toMillis(duration - 1))
            pendingOperations.remove(playerUUID)
            "Cancel ok"
        }
        if (result == null) {
            pendingOperations.remove(playerUUID)
            VillagerShopMain.LOGGER.warn("Operation auto cancel failed！")
        }
        context.source.player?.let { player ->
            player.sendMessage(tr("commands.confirm.autocancel"))
            player.server.execute { player.server.playerManager.sendCommandTree(player) }
        }
    }
    pendingOperationJobs[playerUUID] = job
    job.invokeOnCompletion { pendingOperationJobs.remove(playerUUID) }
}

/** Cancels only the timer job for a specific player, leaving the global scope intact. */
fun cancelPendingOperationJob(playerUUID: UUID) {
    pendingOperationJobs.remove(playerUUID)?.cancel()
}