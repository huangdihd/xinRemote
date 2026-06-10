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

package xin.bbtt.remote.endPoints;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.ItemEnchantments;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;
import xin.bbtt.MovementSync;
import xin.bbtt.inventory.EnchantmentRegistry;
import xin.bbtt.inventory.ItemRegistry;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.remote.websocket.WsWorldSession;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class Inventory implements HttpHandler {
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        // Reading the request body (handlePost) requires blocking IO, which is
        // illegal on Undertow's IO thread — dispatch to a worker thread and
        // enable blocking first, otherwise getInputStream() throws and the POST
        // (e.g. /inventory/heldSlot) fails with a 400.
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }
        exchange.startBlocking();

        if (!WsWorldSession.isMovementSyncAvailable()) {
            exchange.setStatusCode(503);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseSender().send("{\"error\":\"MovementSync not loaded\"}");
            return;
        }

        if (Methods.GET.equals(exchange.getRequestMethod())) {
            handleGet(exchange);
        } else if (Methods.POST.equals(exchange.getRequestMethod())) {
            handlePost(exchange);
        } else {
            exchange.setStatusCode(405);
            exchange.endExchange();
        }
    }

    private void handleGet(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");

        xin.bbtt.inventory.InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
        ItemStack[] items = inv.getInventory();
        int heldSlot = inv.getHeldSlot();

        List<Object> slotList = new ArrayList<>();
        if (items != null) {
            for (int i = 0; i < items.length; i++) {
                ItemStack item = items[i];
                if (item == null || item.getId() == 0) {
                    slotList.add(null);
                    continue;
                }
                slotList.add(serializeItem(item, i));
            }
        }

        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("items", slotList);
            result.put("heldSlot", heldSlot);
            exchange.getResponseSender().send(mapper.writeValueAsString(result));
        } catch (Exception e) {
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("{\"error\":\"Failed to serialize inventory\"}");
        }
    }

    private Map<String, Object> serializeItem(ItemStack item, int slot) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("slot", slot);
        map.put("id", item.getId());
        map.put("count", item.getAmount());

        ItemRegistry.ItemEntry entry = ItemRegistry.Instance.getItem(item.getId());
        if (entry != null) {
            map.put("name", entry.getName());
            map.put("displayName", entry.getDisplayName());
        } else {
            map.put("name", "unknown_" + item.getId());
            map.put("displayName", "Unknown (" + item.getId() + ")");
        }

        // Enchantments
        List<Map<String, Object>> enchants = getEnchantments(item);
        map.put("enchants", enchants);

        return map;
    }

    private List<Map<String, Object>> getEnchantments(ItemStack item) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            DataComponents components = item.getDataComponentsPatch();
            if (components == null) return result;

            ItemEnchantments enchantmentsObj = components.get(DataComponentTypes.ENCHANTMENTS);
            if (enchantmentsObj == null) return result;

            Map<Integer, Integer> enchantments = enchantmentsObj.getEnchantments();
            for (Map.Entry<Integer, Integer> ench : enchantments.entrySet()) {
                EnchantmentRegistry.EnchantmentEntry eEntry = EnchantmentRegistry.Instance.getByNetworkId(ench.getKey());
                Map<String, Object> eMap = new LinkedHashMap<>();
                eMap.put("id", ench.getKey());
                eMap.put("name", eEntry != null ? eEntry.getName() : "unknown");
                eMap.put("displayName", eEntry != null ? eEntry.getDisplayName() : "Ench#" + ench.getKey());
                eMap.put("level", ench.getValue());
                result.add(eMap);
            }
        } catch (Exception ignored) { }
        return result;
    }

    private void handlePost(HttpServerExchange exchange) {
        try {
            String body = exchange.getInputStream() != null
                    ? new String(exchange.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    : "{}";

            @SuppressWarnings("unchecked")
            Map<String, Object> data = body.isEmpty() ? new HashMap<>() : mapper.readValue(body, Map.class);

            String path = exchange.getRelativePath();

            if ("/inventory/heldSlot".equals(path)) {
                handleHeldSlot(data);
            } else if ("/inventory/drop".equals(path)) {
                handleDrop(data);
            } else if ("/inventory/swapHands".equals(path)) {
                handleSwapHands();
            } else {
                exchange.setStatusCode(404);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                exchange.getResponseSender().send("{\"error\":\"Unknown inventory action\"}");
                return;
            }

            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseSender().send("{\"ok\":true}");
        } catch (Exception e) {
            exchange.setStatusCode(400);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseSender().send("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private void handleHeldSlot(Map<String, Object> data) {
        int slot = ((Number) data.get("slot")).intValue();
        if (slot < 0 || slot > 8) return;

        Bot.INSTANCE.getSession().send(new ServerboundSetCarriedItemPacket(slot));
        MovementSync.INSTANCE.getInventoryManager().setHeldSlot(slot);
    }

    private void handleDrop(Map<String, Object> data) {
        int slot = ((Number) data.get("slot")).intValue();
        boolean ctrlDrop = data.get("ctrlDrop") != null && (Boolean) data.get("ctrlDrop");

        int heldSlot = MovementSync.INSTANCE.getInventoryManager().getHeldSlot();

        // If dropping from hotbar (slots 0-8), switch to that slot and drop
        if (slot >= 0 && slot <= 8) {
            if (heldSlot != slot) {
                Bot.INSTANCE.getSession().send(new ServerboundSetCarriedItemPacket(slot));
                MovementSync.INSTANCE.getInventoryManager().setHeldSlot(slot);
            }
            PlayerAction action = ctrlDrop ? PlayerAction.DROP_ITEM_STACK : PlayerAction.DROP_ITEM;
            Bot.INSTANCE.getSession().send(new ServerboundPlayerActionPacket(
                    action, Vector3i.ZERO, Direction.DOWN, Bot.INSTANCE.getAndIncreaseSequence()));
        } else if (slot >= 9 && slot <= 44) {
            // Non-hotbar slots: move item to hotbar first via switch-to-slot,
            // then drop. For simplicity, switch to slot 0 of hotbar.
            // Full container click would require the player inventory to be open.
            xin.bbtt.MovementSync.getLogger().warn(
                    "Drop from non-hotbar slot {} requires open container; not implemented yet", slot);
        }
    }

    private void handleSwapHands() {
        // In Minecraft 1.21+, swapping hands cannot be done via PlayerCommandPacket
        // as PlayerState doesn't have SWAP_HAND_ITEMS anymore.
        // The F key swap is handled client-side and sent to the server differently.
        // For bot-side swap, this would require a different approach.
        xin.bbtt.MovementSync.getLogger().warn(
                "Swap hands not available in this protocol version via simple packet");
    }
}
