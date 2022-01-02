package me.steinborn.krypton.mixin.shared.network.flushconsolidation;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import me.steinborn.krypton.mod.shared.network.util.AutoFlushUtil;
import net.minecraft.network.Packet;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.server.network.DebugInfoSender;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.PlayerChunkWatchingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.WorldChunk;
import org.apache.commons.lang3.mutable.MutableObject;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixes into various methods in {@code ThreadedAnvilChunkStorage} to utilize flush consolidation for sending chunks
 * all at once to the client. Helpful for heavy server activity or flying very quickly.
 */
@Mixin(ThreadedAnvilChunkStorage.class)
public abstract class ThreadedAnvilChunkStorageMixin {
    @Shadow @Final private Int2ObjectMap<ThreadedAnvilChunkStorage.EntityTracker> entityTrackers;
    @Shadow @Final private PlayerChunkWatchingManager playerChunkWatchingManager;
    @Shadow @Final private ServerWorld world;
    @Shadow @Final private ThreadedAnvilChunkStorage.TicketManager ticketManager;
    @Shadow private int watchDistance;

    @Shadow protected abstract boolean doesNotGenerateChunks(ServerPlayerEntity player);

    @Shadow protected abstract ChunkSectionPos updateWatchedSection(ServerPlayerEntity serverPlayerEntity);

    @Shadow
    public static boolean isWithinDistance(int x1, int y1, int x2, int y2, int maxDistance) {
        // PAIL: isWithinEuclideanDistance(x1, y1, x2, y2, maxDistance)
        throw new AssertionError("pedantic");
    }

    /**
     * @author Andrew Steinborn
     * @reason Add support for flush consolidation
     */
    @Overwrite
    public void updatePosition(ServerPlayerEntity player) {
        // TODO: optimize this further by only considering entities that the player is close to.
        //       use the FastChunkEntityAccess magic to do this.
        for (ThreadedAnvilChunkStorage.EntityTracker entityTracker : this.entityTrackers.values()) {
            if (entityTracker.entity == player) {
                entityTracker.updateTrackedStatus(this.world.getPlayers());
            } else {
                entityTracker.updateTrackedStatus(player);
            }
        }

        ChunkSectionPos oldPos = player.getWatchedSection();
        ChunkSectionPos newPos = ChunkSectionPos.from(player);
        boolean isWatchingWorld = this.playerChunkWatchingManager.isWatchDisabled(player);
        boolean noChunkGen = this.doesNotGenerateChunks(player);
        boolean movedSections = !oldPos.equals(newPos);

        if (movedSections || isWatchingWorld != noChunkGen) {
            this.updateWatchedSection(player);

            if (!isWatchingWorld) {
                this.ticketManager.handleChunkLeave(oldPos, player);
            }

            if (!noChunkGen) {
                this.ticketManager.handleChunkEnter(newPos, player);
            }

            if (!isWatchingWorld && noChunkGen) {
                this.playerChunkWatchingManager.disableWatch(player);
            }

            if (isWatchingWorld && !noChunkGen) {
                this.playerChunkWatchingManager.enableWatch(player);
            }

            long oldChunkPos = ChunkPos.toLong(oldPos.getX(), oldPos.getZ());
            long newChunkPos = ChunkPos.toLong(newPos.getX(), newPos.getZ());
            this.playerChunkWatchingManager.movePlayer(oldChunkPos, newChunkPos, player);

            // If the player is in the same world as this tracker, we should send them chunks.
            if (this.world == player.world) {
                this.sendChunks(oldPos, player);
            }
        }
    }

    private void sendChunks(ChunkSectionPos oldPos, ServerPlayerEntity player) {
        AutoFlushUtil.setAutoFlush(player, false);

        try {
            int oldChunkX = oldPos.getSectionX();
            int oldChunkZ = oldPos.getSectionZ();

            int newChunkX = MathHelper.floor(player.getX()) >> 4;
            int newChunkZ = MathHelper.floor(player.getZ()) >> 4;

            if (Math.abs(oldChunkX - newChunkX) <= this.watchDistance * 2 && Math.abs(oldChunkZ - newChunkZ) <= this.watchDistance * 2) {
                int minSendChunkX = Math.min(newChunkX, oldChunkX) - this.watchDistance - 1;
                int minSendChunkZ = Math.min(newChunkZ, oldChunkZ) - this.watchDistance - 1;
                int maxSendChunkX = Math.max(newChunkX, oldChunkX) + this.watchDistance + 1;
                int maxSendChunkZ = Math.max(newChunkZ, oldChunkZ) + this.watchDistance + 1;

                for (int curX = minSendChunkX; curX <= maxSendChunkX; ++curX) {
                    for (int curZ = minSendChunkZ; curZ <= maxSendChunkZ; ++curZ) {
                        ChunkPos chunkPos = new ChunkPos(curX, curZ);
                        boolean inOld = isWithinDistance(curX, curZ, oldChunkX, oldChunkZ, this.watchDistance);
                        boolean inNew = isWithinDistance(curX, curZ, newChunkX, newChunkZ, this.watchDistance);
                        this.sendWatchPackets(player, chunkPos, new MutableObject<>(), inOld, inNew);
                    }
                }
            } else {
                for (int curX = oldChunkX - this.watchDistance - 1; curX <= oldChunkX + this.watchDistance + 1; ++curX) {
                    for (int curZ = oldChunkZ - this.watchDistance - 1; curZ <= oldChunkZ + this.watchDistance + 1; ++curZ) {
                        ChunkPos pos = new ChunkPos(curX, curZ);
                        this.sendWatchPackets(player, pos, new MutableObject<>(), true, false);
                    }
                }

                for (int curX = newChunkX - this.watchDistance - 1; curX <= newChunkX + this.watchDistance + 1; ++curX) {
                    for (int curZ = newChunkZ - this.watchDistance - 1; curZ <= newChunkZ + this.watchDistance + 1; ++curZ) {
                        ChunkPos pos = new ChunkPos(curX, curZ);
                        this.sendWatchPackets(player, pos, new MutableObject<>(), false, true);
                    }
                }
            }
        } finally {
            AutoFlushUtil.setAutoFlush(player, true);
        }
    }

    @Shadow
    protected abstract void sendWatchPackets(ServerPlayerEntity player, ChunkPos pos, MutableObject<ChunkDataS2CPacket> mutableObject, boolean withinMaxWatchDistance, boolean withinViewDistance);

    @Inject(method = "tickEntityMovement", at = @At("HEAD"))
    public void disableAutoFlushForEntityTracking(CallbackInfo info) {
        for (ServerPlayerEntity player : world.getPlayers()) {
            AutoFlushUtil.setAutoFlush(player, false);
        }
    }

    @Inject(method = "tickEntityMovement", at = @At("RETURN"))
    public void enableAutoFlushForEntityTracking(CallbackInfo info) {
        for (ServerPlayerEntity player : world.getPlayers()) {
            AutoFlushUtil.setAutoFlush(player, true);
        }
    }

}
