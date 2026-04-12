package io.canvasmc.itemmodifer.components;

import com.google.gson.*;
import com.mojang.brigadier.context.*;
import com.mojang.brigadier.exceptions.*;
import com.mojang.brigadier.suggestion.*;
import io.canvasmc.itemmodifer.*;
import net.minecraft.commands.*;
import net.minecraft.core.*;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.*;
import net.minecraft.network.chat.*;
import net.minecraft.resources.*;
import net.minecraft.server.*;
import net.minecraft.tags.*;
import net.minecraft.world.item.component.*;
import net.minecraft.world.level.block.*;
import org.jspecify.annotations.*;

import java.util.*;
import java.util.concurrent.*;

public class ToolComponent extends ComponentType<Tool> {
    @Override
    public CompletableFuture<Suggestions> suggestions(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return jsonSuggestions(context, builder);
    }

    @Override
    public Tool parse(@NonNull String raw) throws CommandSyntaxException {
        try {
            String fixed = raw.replaceAll(
                    "([\\[,{:])\\s*([#]?[a-z0-9_.-]+:[a-z0-9_./-]+)(?=[\\s,}\\]])",
                    "$1\"$2\""
            );

            JsonObject json = GSON.fromJson(fixed, JsonObject.class);

            List<Tool.Rule> rules = new ArrayList<>();
            if (json.has("rules")) {
                JsonElement rulesElement = json.get("rules");
                if (!rulesElement.isJsonArray()) {
                    throw new IllegalArgumentException("rules must be an array");
                }

                HolderLookup.RegistryLookup<Block> blockRegistry = MinecraftServer.getServer().registryAccess()
                        .lookupOrThrow(Registries.BLOCK);

                for (JsonElement element : rulesElement.getAsJsonArray()) {
                    if (!element.isJsonObject()) {
                        throw new IllegalArgumentException("rules entries must be objects");
                    }

                    JsonObject ruleJson = element.getAsJsonObject();

                    if (!ruleJson.has("blocks")) {
                        throw new IllegalArgumentException("tool rule is missing required field 'blocks'");
                    }

                    HolderSet<Block> blocks;
                    JsonElement blocksElement = ruleJson.get("blocks");

                    if (blocksElement.isJsonPrimitive()) {
                        String value = blocksElement.getAsString();

                        if (value.startsWith("#")) {
                            TagKey<Block> tagKey = TagKey.create(
                                    Registries.BLOCK,
                                    Identifier.parse(value.substring(1))
                            );
                            blocks = blockRegistry.get(tagKey).orElseThrow();
                        } else {
                            Holder<Block> holder = blockRegistry.get(
                                    ResourceKey.create(Registries.BLOCK, Identifier.parse(value))
                            ).orElseThrow();
                            blocks = HolderSet.direct(holder);
                        }
                    } else if (blocksElement.isJsonArray()) {
                        List<Holder<Block>> holders = new ArrayList<>();

                        for (JsonElement blockElement : blocksElement.getAsJsonArray()) {
                            if (!blockElement.isJsonPrimitive()) {
                                throw new IllegalArgumentException("rules.blocks array entries must be strings");
                            }

                            String value = blockElement.getAsString();
                            if (value.startsWith("#")) {
                                throw new IllegalArgumentException("rules.blocks array cannot contain tags");
                            }

                            holders.add(
                                    blockRegistry.get(
                                            ResourceKey.create(Registries.BLOCK, Identifier.parse(value))
                                    ).orElseThrow()
                            );
                        }

                        blocks = HolderSet.direct(holders);
                    } else {
                        throw new IllegalArgumentException("rules.blocks must be a string or array");
                    }

                    Optional<Float> speed = ruleJson.has("speed")
                            ? Optional.of(ruleJson.get("speed").getAsFloat())
                            : Optional.empty();

                    Optional<Boolean> correctForDrops = ruleJson.has("correct_for_drops")
                            ? Optional.of(ruleJson.get("correct_for_drops").getAsBoolean())
                            : Optional.empty();

                    rules.add(new Tool.Rule(blocks, speed, correctForDrops));
                }
            }

            float defaultMiningSpeed = json.has("default_mining_speed")
                    ? json.get("default_mining_speed").getAsFloat()
                    : 1.0F;

            int damagePerBlock = json.has("damage_per_block")
                    ? json.get("damage_per_block").getAsInt()
                    : 1;

            boolean canDestroyBlocksInCreative = json.has("can_destroy_blocks_in_creative")
                    && json.get("can_destroy_blocks_in_creative").getAsBoolean();

            return new Tool(
                    rules,
                    defaultMiningSpeed,
                    damagePerBlock,
                    canDestroyBlocksInCreative
            );
        } catch (Throwable thrown) {
            throw new DynamicCommandExceptionType(
                    obj -> Component.literal(obj.toString())
            ).create(thrown.getMessage());
        }
    }

    @Override
    public Map<String, FieldInfo> jsonFields() {
        return Map.of(
                "rules", FieldInfo.objectListField(Map.of(
                        "blocks", FieldInfo.dynamicStringField(context -> {
                            List<String> out = new ArrayList<>();
                            for (Identifier id : MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.BLOCK).keySet()) {
                                out.add(id.toString());
                                out.add("#" + id);
                            }
                            return out;
                        }),
                        "speed", FieldInfo.floatField("1.0F", "4.0F", "8.0F"),
                        "correct_for_drops", FieldInfo.bool()
                )),
                "default_mining_speed", FieldInfo.floatField("1.0F"),
                "damage_per_block", FieldInfo.intField("1"),
                "can_destroy_blocks_in_creative", FieldInfo.bool()
        );
    }


    @Override
    public DataComponentType<Tool> nms() {
        return DataComponents.TOOL;
    }
}
