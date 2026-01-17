package com.gillodaby.bettertorch;

import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.event.EventBus;
import com.hypixel.hytale.protocol.ColorLight;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.blocktype.BlockTypePacketGenerator;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.modules.item.ItemPacketGenerator;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.AllWorldsLoadedEvent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

public final class BetterTorch {
    public static final int MIN_MULTIPLIER = 5;
    public static final int MAX_MULTIPLIER = 5;
    private static final String TORCH_KEYWORD = "torch";
    private static final Object LOCK = new Object();
    private static final Map<String, ColorLight> baseTorchLights = new HashMap<>();
    private static final Map<String, ColorLight> baseTorchItemLights = new HashMap<>();
    private static int multiplier = 5;
    private static int lastAppliedMultiplier = -1;
    private static Field lightField;
    private static Field itemLightField;

    private BetterTorch() {
    }

    public static void init() {
        registerWorldLoadListener();
        applyTorchLightMultiplier();
    }

    public static int getMultiplier() {
        synchronized (LOCK) {
            return multiplier;
        }
    }

    public static void setMultiplier(int value) {
        int clamped = clampMultiplier(value);
        boolean changed;
        synchronized (LOCK) {
            changed = multiplier != clamped;
            multiplier = clamped;
        }
        if (changed) {
            applyTorchLightMultiplier();
        }
    }

    private static void registerWorldLoadListener() {
        try {
            EventBus bus = HytaleServer.get().getEventBus();
            bus.registerGlobal(AllWorldsLoadedEvent.class, (AllWorldsLoadedEvent ev) -> {
                try {
                    applyTorchLightMultiplier();
                    refreshLighting();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            });
        } catch (Throwable t) {
            System.out.println("[BetterTorch] Failed to register world-load listener: " + t.getMessage());
        }
    }

    private static void applyTorchLightMultiplier() {
        BlockTypeAssetMap<String, BlockType> blockMap = BlockType.getAssetMap();
        DefaultAssetMap<String, Item> itemMap = Item.getAssetMap();

        if (blockMap == null && itemMap == null) {
            System.out.println("[BetterTorch] BlockType/Item asset maps not available yet.");
            return;
        }

        Field blockLightField = getLightField();
        Field itemLightField = getItemLightField();

        Map<String, BlockType> changedBlocks = new HashMap<>();
        Map<String, Item> changedItems = new HashMap<>();
        int scannedBlocks = 0;
        int scannedItems = 0;
        int targetMultiplier;
        Map<String, ColorLight> torchBlockLights;
        Map<String, ColorLight> torchItemLights;

        synchronized (LOCK) {
            targetMultiplier = multiplier;
            boolean hasCachedTorchAssets = !baseTorchLights.isEmpty() || !baseTorchItemLights.isEmpty();
            if (targetMultiplier == lastAppliedMultiplier && hasCachedTorchAssets) return;

            if (blockMap != null && baseTorchLights.isEmpty()) {
                for (Map.Entry<String, BlockType> entry : blockMap.getAssetMap().entrySet()) {
                    String key = entry.getKey();
                    BlockType block = entry.getValue();
                    if (key == null || block == null) continue;
                    String keyLower = key.toLowerCase(Locale.ROOT);
                    System.out.println("[BetterTorch][DEBUG] Bloc trouvé : " + key);
                    // Booster tout bloc dont le nom contient 'torch' (insensible à la casse, avec ou sans underscore)
                    if (!keyLower.contains("torch")) continue;
                    System.out.println("[BetterTorch][DEBUG] Bloc torch détecté : " + key);
                    ColorLight light = block.getLight();
                    if (light == null) continue;
                    baseTorchLights.put(key, new ColorLight(light));
                }
            }

            if (itemMap != null && baseTorchItemLights.isEmpty()) {
                for (Map.Entry<String, Item> entry : itemMap.getAssetMap().entrySet()) {
                    String key = entry.getKey();
                    Item item = entry.getValue();
                    if (key == null || item == null) continue;
                    if (!key.toLowerCase(Locale.ROOT).contains(TORCH_KEYWORD)) continue;
                    ColorLight light = item.getLight();
                    if (light == null) continue;
                    baseTorchItemLights.put(key, new ColorLight(light));
                }
            }

            torchBlockLights = new HashMap<>(baseTorchLights);
            torchItemLights = new HashMap<>(baseTorchItemLights);
        }

        if (blockMap != null && blockLightField != null) {
            for (Map.Entry<String, ColorLight> entry : torchBlockLights.entrySet()) {
                String key = entry.getKey();
                BlockType block = blockMap.getAsset(key);
                if (block == null) continue;
                scannedBlocks++;

                ColorLight original = entry.getValue();
                ColorLight boosted = boostLight(original, targetMultiplier);
                if (!boosted.equals(block.getLight())) {
                    try {
                        blockLightField.set(block, boosted);
                        changedBlocks.put(key, block);
                    } catch (IllegalAccessException e) {
                        System.out.println("[BetterTorch] Failed to update light for block " + key + ": " + e.getMessage());
                    }
                }
            }
        } else if (blockMap == null) {
            System.out.println("[BetterTorch] BlockType asset map not available yet.");
        } else {
            System.out.println("[BetterTorch] Failed to access BlockType.light field.");
        }

        if (itemMap != null && itemLightField != null) {
            for (Map.Entry<String, ColorLight> entry : torchItemLights.entrySet()) {
                String key = entry.getKey();
                Item item = itemMap.getAsset(key);
                if (item == null) continue;
                scannedItems++;

                ColorLight original = entry.getValue();
                ColorLight boosted = boostLight(original, targetMultiplier);
                if (!boosted.equals(item.getLight())) {
                    try {
                        itemLightField.set(item, boosted);
                        changedItems.put(key, item);
                    } catch (IllegalAccessException e) {
                        System.out.println("[BetterTorch] Failed to update light for item " + key + ": " + e.getMessage());
                    }
                }
            }
        } else if (itemMap == null) {
            System.out.println("[BetterTorch] Item asset map not available yet.");
        } else {
            System.out.println("[BetterTorch] Failed to access Item.light field.");
        }

        synchronized (LOCK) {
            lastAppliedMultiplier = targetMultiplier;
        }

        if (!changedBlocks.isEmpty() || !changedItems.isEmpty()) {
            int sampleLogged = 0;
            for (Map.Entry<String, BlockType> e : changedBlocks.entrySet()) {
                if (sampleLogged >= 5) break;
                ColorLight base = torchBlockLights.get(e.getKey());
                ColorLight newLight = e.getValue().getLight();
                System.out.println("[BetterTorch] Sample block change " + e.getKey() + ": " + describeLight(base) + " -> " + describeLight(newLight));
                sampleLogged++;
            }

            sampleLogged = 0;
            for (Map.Entry<String, Item> e : changedItems.entrySet()) {
                if (sampleLogged >= 5) break;
                ColorLight base = torchItemLights.get(e.getKey());
                ColorLight newLight = e.getValue().getLight();
                System.out.println("[BetterTorch] Sample item change " + e.getKey() + ": " + describeLight(base) + " -> " + describeLight(newLight));
                sampleLogged++;
            }

            if (!changedBlocks.isEmpty()) {
                sendBlockTypeUpdate(changedBlocks);
                refreshLighting();
            }
            if (!changedItems.isEmpty()) {
                sendItemUpdate(changedItems);
            }
            System.out.println("[BetterTorch] Torch light multiplier set to x" + targetMultiplier + " for " + changedBlocks.size() + " block types and " + changedItems.size() + " item(s).");
        } else {
            System.out.println("[BetterTorch] No torch block types or items found to update (scannedBlocks=" + scannedBlocks + ", scannedItems=" + scannedItems + ").");
        }
    }

    private static String describeLight(ColorLight light) {
        if (light == null) return "null";
        int radius = Byte.toUnsignedInt(light.radius);
        int red = Byte.toUnsignedInt(light.red);
        int green = Byte.toUnsignedInt(light.green);
        int blue = Byte.toUnsignedInt(light.blue);
        return "radius=" + radius + " rgb=(" + red + "," + green + "," + blue + ")";
    }

    private static Field getLightField() {
        if (lightField != null) return lightField;
        try {
            Field field = BlockType.class.getDeclaredField("light");
            field.setAccessible(true);
            lightField = field;
            return field;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field getItemLightField() {
        if (itemLightField != null) return itemLightField;
        try {
            Field field = Item.class.getDeclaredField("light");
            field.setAccessible(true);
            itemLightField = field;
            return field;
        } catch (Throwable t) {
            return null;
        }
    }

    private static ColorLight boostLight(ColorLight original, int multiplier) {
        int radius = Byte.toUnsignedInt(original.radius);
        int red = Byte.toUnsignedInt(original.red);
        int green = Byte.toUnsignedInt(original.green);
        int blue = Byte.toUnsignedInt(original.blue);

        // Some torch assets report radius=0; fall back to a visible base radius and base color
        int maxChannel = Math.max(red, Math.max(green, blue));
        if (radius == 0) {
            radius = Math.max(8, maxChannel / 3); // give a minimal radius
        }
        if (maxChannel == 0) {
            maxChannel = 16; // ensure some color intensity exists
            red = green = blue = maxChannel;
        }

        // Preserve the original torch tint; only expand brightness via radius
        int newRadius = clampByte(radius * multiplier);
        int newRed = red;
        int newGreen = green;
        int newBlue = blue;

        return new ColorLight((byte) newRadius, (byte) newRed, (byte) newGreen, (byte) newBlue);
    }

    private static int clampByte(int value) {
        if (value < 0) return 0;
        if (value > 255) return 255;
        return value;
    }

    private static int clampMultiplier(int value) {
        if (value < MIN_MULTIPLIER) return MIN_MULTIPLIER;
        if (value > MAX_MULTIPLIER) return MAX_MULTIPLIER;
        return value;
    }

    private static void sendBlockTypeUpdate(Map<String, BlockType> changed) {
        try {
            Universe universe = Universe.get();
            if (universe == null) return;

            BlockTypePacketGenerator generator = new BlockTypePacketGenerator();
            Packet packet = generator.generateUpdatePacket(BlockType.getAssetMap(), changed, AssetUpdateQuery.DEFAULT);
            if (packet != null) {
                universe.broadcastPacket(packet);
            }
        } catch (Throwable t) {
            System.out.println("[BetterTorch] Failed to broadcast block updates: " + t.getMessage());
        }
    }

    private static void refreshLighting() {
        Universe universe = Universe.get();
        if (universe == null) {
            System.out.println("[BetterTorch] Universe not ready yet, lighting refresh skipped.");
            return;
        }

        int refreshed = 0;
        for (World world : universe.getWorlds().values()) {
            if (world == null) continue;
            if (world.getChunkLighting() == null) continue;
            ChunkStore chunkStore = world.getChunkStore();
            if (chunkStore == null || chunkStore.getStore() == null) continue;
            try {
                world.getChunkLighting().invalidateLoadedChunks();
                refreshed++;
            } catch (Throwable t) {
                System.out.println("[BetterTorch] Lighting refresh skipped for " + world.getName() + ": " + t.getMessage());
            }
        }

        if (refreshed > 0) {
            System.out.println("[BetterTorch] Lighting refresh requested for " + refreshed + " world(s).");
        }
    }

    private static void sendItemUpdate(Map<String, Item> changed) {
        try {
            Universe universe = Universe.get();
            if (universe == null) return;

            ItemPacketGenerator generator = new ItemPacketGenerator();
            Packet packet = generator.generateUpdatePacket(Item.getAssetMap(), changed, AssetUpdateQuery.DEFAULT);
            if (packet != null) {
                universe.broadcastPacket(packet);
            }
        } catch (Throwable t) {
            System.out.println("[BetterTorch] Failed to broadcast item updates: " + t.getMessage());
        }
    }
}
