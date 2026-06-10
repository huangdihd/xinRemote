/*
 *   Copyright (C) 2025 huangdihd
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package xin.bbtt.remote.websocket;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHeldSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundForgetLevelChunkPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSectionBlocksUpdatePacket;
import org.joml.Vector3d;
import xin.bbtt.Block.BlockStateParser;
import xin.bbtt.Entity.Entity;
import xin.bbtt.MovementSync;
import xin.bbtt.listeners.RegistryDataListener;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.remote.XinRemote;
import xin.bbtt.world.World;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages all connected world-viewer WebSocket sessions.
 * <p>
 * There is exactly one bot packet listener (shared across all viewers),
 * and one periodic ticker that sends bot-position updates.
 * On first viewer-connect the shared infrastructure is wired;
 * on last viewer-disconnect everything tears down.
 */
public class WsWorldSession {

    // ------------------------------------------------------------------
    //  MovementSync availability
    // ------------------------------------------------------------------

    static final boolean MOVEMENT_SYNC_AVAILABLE;
    static {
        boolean available = false;
        try {
            Class.forName("xin.bbtt.MovementSync");
            available = true;
        } catch (ClassNotFoundException e) {
            XinRemote.getLog().warn("MovementSync not loaded — world viewer will be inactive");
        }
        MOVEMENT_SYNC_AVAILABLE = available;
    }

    public static boolean isMovementSyncAvailable() {
        return MOVEMENT_SYNC_AVAILABLE;
    }

    // ------------------------------------------------------------------
    //  session registry
    // ------------------------------------------------------------------

    private static final Set<WebSocketChannel> channels =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static ScheduledExecutorService ticker;
    private static SessionAdapter worldPacketListener;

    // ------------------------------------------------------------------
    //  per-connection lifecycle
    // ------------------------------------------------------------------

    private final WebSocketChannel channel;

    WsWorldSession(WebSocketChannel channel) {
        this.channel = channel;
    }

    void start() {
        channels.add(channel);

        if (!MOVEMENT_SYNC_AVAILABLE) return;

        // wire the shared bot listener on first connection
        if (worldPacketListener == null) {
            worldPacketListener = new WorldPacketAdapter();
            Bot.INSTANCE.addPacketListener(worldPacketListener,
                    XinRemote.getInstance());
        }

        // wire the shared ticker on first connection
        if (ticker == null) {
            ticker = Executors.newScheduledThreadPool(1);
            ticker.scheduleAtFixedRate(WsWorldSession::broadcastPosition,
                    0, 50, TimeUnit.MILLISECONDS);
        }

        // send world dimensions so the viewer can configure chunk meshing
        WebSockets.sendText(String.format(java.util.Locale.US,
                "{\"type\":\"world_info\",\"minY\":%d,\"maxY\":%d}",
                RegistryDataListener.getMinWorldY(),
                RegistryDataListener.getMaxWorldY()),
                channel, null);

        // push all currently loaded chunks + entities to the new viewer
        syncExistingChunks(channel);
        syncExistingEntities(channel);
    }

    static void close(WebSocketChannel channel) {
        channels.remove(channel);

        if (channels.isEmpty()) {
            if (worldPacketListener != null) {
                Bot.INSTANCE.removePacketListener(worldPacketListener,
                        XinRemote.getInstance());
                worldPacketListener = null;
            }
            if (ticker != null) {
                ticker.shutdown();
                ticker = null;
            }
        }
    }

    // ------------------------------------------------------------------
    //  packet listener  (one per bot, shared across all viewers)
    // ------------------------------------------------------------------

    private static final class WorldPacketAdapter extends SessionAdapter {
        @Override
        public void packetReceived(Session session, Packet packet) {
            if (packet instanceof ClientboundLevelChunkWithLightPacket p) {
                processAndBroadcastChunk(p);
                return;
            }
            if (packet instanceof ClientboundForgetLevelChunkPacket p) {
                broadcast(String.format(java.util.Locale.US,
                        "{\"type\":\"unload\", \"x\":%d, \"z\":%d}",
                        p.getX(), p.getZ()));
                return;
            }
            // ---- block updates ----
            if (packet instanceof ClientboundBlockUpdatePacket p) {
                broadcast(String.format(java.util.Locale.US,
                        "{\"type\":\"block_update\",\"x\":%d,\"y\":%d,\"z\":%d,\"stateId\":%d}",
                        p.getEntry().getPosition().getX(),
                        p.getEntry().getPosition().getY(),
                        p.getEntry().getPosition().getZ(),
                        p.getEntry().getBlock()));
                return;
            }
            if (packet instanceof ClientboundSectionBlocksUpdatePacket p) {
                for (var entry : p.getEntries()) {
                    broadcast(String.format(java.util.Locale.US,
                            "{\"type\":\"block_update\",\"x\":%d,\"y\":%d,\"z\":%d,\"stateId\":%d}",
                            entry.getPosition().getX(),
                            entry.getPosition().getY(),
                            entry.getPosition().getZ(),
                            entry.getBlock()));
                }
                return;
            }
            // ---- entity packets ----
            if (packet instanceof ClientboundAddEntityPacket p) {
                // Read straight from the packet — do NOT look the entity up in
                // MovementSync's world map here. That map is populated by a
                // separate listener (EntityPacketListener), and depending on
                // listener order / async dispatch it may not contain this
                // entity yet, which would silently drop the entity_add.
                broadcast(String.format(java.util.Locale.US,
                        "{\"type\":\"entity_add\",\"id\":%d,\"x\":%.2f,\"y\":%.2f,\"z\":%.2f,\"yaw\":%.2f,\"pitch\":%.2f,\"headYaw\":%.2f,\"name\":\"%s\"}",
                        p.getEntityId(),
                        p.getX(), p.getY(), p.getZ(),
                        p.getYaw(), p.getPitch(), p.getHeadYaw(),
                        p.getType().name()));
                return;
            }
            if (packet instanceof ClientboundTeleportEntityPacket p) {
                broadcastEntityMove(p.getId());
                return;
            }
            if (packet instanceof ClientboundMoveEntityPosPacket p) {
                broadcastEntityMove(p.getEntityId());
                return;
            }
            if (packet instanceof ClientboundMoveEntityPosRotPacket p) {
                broadcastEntityMove(p.getEntityId());
                return;
            }
            if (packet instanceof ClientboundRotateHeadPacket p) {
                broadcast(String.format(java.util.Locale.US,
                        "{\"type\":\"entity_rotate\",\"id\":%d,\"headYaw\":%.2f}",
                        p.getEntityId(), p.getHeadYaw()));
                return;
            }
            if (packet instanceof ClientboundRemoveEntitiesPacket p) {
                for (int id : p.getEntityIds()) {
                    broadcast(String.format(java.util.Locale.US,
                            "{\"type\":\"entity_remove\",\"id\":%d}", id));
                }
                return;
            }
            // ---- inventory packets ----
            if (packet instanceof ClientboundContainerSetContentPacket
                    || packet instanceof ClientboundContainerSetSlotPacket
                    || packet instanceof ClientboundSetHeldSlotPacket
                    || packet instanceof ClientboundOpenScreenPacket
                    || packet instanceof ClientboundContainerClosePacket) {
                broadcast("{\"type\":\"inventory_update\"}");
            }
        }
    }

    // ------------------------------------------------------------------
    //  initial sync  (chunk sync uses reflection; entities use getEntities)
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void syncExistingChunks(WebSocketChannel ch) {
        try {
            World world = MovementSync.INSTANCE.world;
            Field f = world.getClass().getDeclaredField("chunks");
            f.setAccessible(true);
            Map<Integer, Map<Integer, Map<Integer, ChunkSection>>> chunks =
                    (Map<Integer, Map<Integer, Map<Integer, ChunkSection>>>) f.get(world);
            for (var xE : chunks.entrySet()) {
                for (var zE : xE.getValue().entrySet()) {
                    if (ch.isOpen()) {
                        WebSockets.sendText(
                                formatChunkJson(xE.getKey(), zE.getKey(), zE.getValue()),
                                ch, null);
                    }
                }
            }
        } catch (Exception ignored) { /* chunks will stream in live */ }
    }

    private static void syncExistingEntities(WebSocketChannel ch) {
        try {
            for (Entity e : MovementSync.INSTANCE.world.getEntities().values()) {
                if (e.getPosition() != null && ch.isOpen()) {
                    WebSockets.sendText(String.format(java.util.Locale.US,
                                    "{\"type\":\"entity_add\",\"id\":%d,\"x\":%.2f,\"y\":%.2f,\"z\":%.2f,\"yaw\":%.2f,\"pitch\":%.2f,\"headYaw\":%.2f,\"name\":\"%s\"}",
                                    e.getEntityId(),
                                    e.getPosition().x, e.getPosition().y,
                                    e.getPosition().z,
                                    e.getYaw(), e.getPitch(), e.getHeadYaw(),
                                    e.getType().name()),
                            ch, null);
                }
            }
        } catch (Exception ignored) { /* fail silently */ }
    }

    // ------------------------------------------------------------------
    //  chunk serialization  (same format XinViewer uses)
    // ------------------------------------------------------------------

    private static void processAndBroadcastChunk(
            ClientboundLevelChunkWithLightPacket packet) {
        Map<Integer, ChunkSection> sections = new LinkedHashMap<>();
        ByteBuf buf = Unpooled.wrappedBuffer(packet.getChunkData());
        try {
            int biomeSize = RegistryDataListener.getBiomeRegistrySize();
            int sY = RegistryDataListener.getMinWorldY() / 16;
            while (buf.isReadable()) {
                sections.put(sY++, MinecraftTypes.readChunkSection(
                        buf, BlockStateParser.getBlockStateRegistrySize(), biomeSize));
            }
        } finally {
            buf.release();
        }
        broadcast(formatChunkJson(packet.getX(), packet.getZ(), sections));
    }

    private static String formatChunkJson(int cx, int cz,
                                          Map<Integer, ChunkSection> sections) {
        StringBuilder json = new StringBuilder(2048);
        json.append(String.format(java.util.Locale.US,
                "{\"type\":\"chunk_data\",\"x\":%d,\"z\":%d,\"data\":[", cx, cz));
        boolean first = true;
        for (Map.Entry<Integer, ChunkSection> sE : sections.entrySet()) {
            int sY = sE.getKey();
            ChunkSection s = sE.getValue();
            for (int i = 0; i < 4096; i++) {
                int bid = s.getBlock(i % 16, (i / 256), (i / 16) % 16);
                if (bid != 0) {
                    if (!first) json.append(',');
                    json.append(String.format(java.util.Locale.US,
                            "[%d,%d,%d,%d]",
                            i % 16,
                            (i / 256) + (sY * 16),
                            (i / 16) % 16,
                            bid));
                    first = false;
                }
            }
        }
        json.append("]}");
        return json.toString();
    }

    // ------------------------------------------------------------------
    //  entity helpers
    // ------------------------------------------------------------------

    private static void broadcastEntityMove(int entityId) {
        Entity e = MovementSync.INSTANCE.world.getEntity(entityId);
        if (e == null || e.getPosition() == null) return;
        broadcast(String.format(java.util.Locale.US,
                "{\"type\":\"entity_move\",\"id\":%d,\"x\":%.2f,\"y\":%.2f,\"z\":%.2f,\"yaw\":%.2f,\"pitch\":%.2f}",
                entityId,
                e.getPosition().x, e.getPosition().y, e.getPosition().z,
                e.getYaw(), e.getPitch()));
    }

    // ------------------------------------------------------------------
    //  periodic bot-position tick
    // ------------------------------------------------------------------

    private static void broadcastPosition() {
        try {
            Vector3d pos = MovementSync.INSTANCE.position.get();
            float yaw = MovementSync.INSTANCE.yaw.get();
            float pitch = MovementSync.INSTANCE.pitch.get();
            broadcast(String.format(java.util.Locale.US,
                    "{\"type\":\"pos\",\"x\":%.2f,\"y\":%.2f,\"z\":%.2f,\"yaw\":%.2f,\"pitch\":%.2f}",
                    pos.x, pos.y, pos.z, yaw, pitch));
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------
    //  util
    // ------------------------------------------------------------------

    private static void broadcast(String text) {
        for (WebSocketChannel ch : channels) {
            if (ch.isOpen()) {
                WebSockets.sendText(text, ch, null);
            }
        }
    }
}
