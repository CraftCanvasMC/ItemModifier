package io.canvasmc.itemmodifer.components;

import com.google.gson.*;
import com.mojang.brigadier.context.*;
import com.mojang.brigadier.exceptions.*;
import com.mojang.brigadier.suggestion.*;
import com.mojang.datafixers.util.*;
import io.canvasmc.itemmodifer.*;
import net.minecraft.commands.*;
import net.minecraft.core.*;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.*;
import net.minecraft.network.chat.*;
import net.minecraft.resources.*;
import net.minecraft.server.*;
import net.minecraft.sounds.*;
import net.minecraft.tags.*;
import net.minecraft.world.damagesource.*;
import net.minecraft.world.item.component.*;
import org.jspecify.annotations.*;

import java.util.*;
import java.util.concurrent.*;

public class BlocksAttacksComponent extends ComponentType<BlocksAttacks> {
    @Override
    public CompletableFuture<Suggestions> suggestions(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return jsonSuggestions(context, builder);
    }

    @Override
    public Map<String, FieldInfo> jsonFields() {
        return Map.of(
                "block_delay_seconds", FieldInfo.floatField("0.0F"),
                "disable_cooldown_scale", FieldInfo.floatField("1.0F"),
                "damage_reductions", FieldInfo.objectListField(Map.of(
                        "horizontal_blocking_angle", FieldInfo.floatField("90.0F"),
                        "type", FieldInfo.dynamicStringField(context -> {
                            List<String> out = new ArrayList<>();
                            MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).keySet().forEach(id -> {
                                out.add(id.toString());
                                out.add("#" + id);
                            });
                            return out;
                        }),
                        "base", FieldInfo.floatField("0.0F"),
                        "factor", FieldInfo.floatField("1.0F")
                )),
                "item_damage", FieldInfo.objectField(Map.of(
                        "threshold", FieldInfo.floatField("0.0F"),
                        "base", FieldInfo.floatField("0.0F"),
                        "factor", FieldInfo.floatField("1.0F")
                )),
                "bypassed_by", FieldInfo.dynamicStringField(context -> {
                    List<String> out = new ArrayList<>();
                    MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).keySet().forEach(id ->
                            out.add("#" + id)
                    );
                    return out;
                }),
                "block_sound", FieldInfo.identifierField(context ->
                        MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.SOUND_EVENT).keySet()
                ),
                "disabled_sound", FieldInfo.identifierField(context ->
                        MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.SOUND_EVENT).keySet()
                )
        );
    }

    @Override
    public BlocksAttacks parse(@NonNull String raw) throws CommandSyntaxException {
        try {
            String fixed = raw.replaceAll(
                    "([\\[,{:])\\s*([#]?[a-z0-9_.-]+:[a-z0-9_./-]+)(?=[\\s,}\\]])",
                    "$1\"$2\""
            );

            JsonObject json = GSON.fromJson(fixed, JsonObject.class);

            float blockDelaySeconds = json.has("block_delay_seconds")
                    ? json.get("block_delay_seconds").getAsFloat()
                    : 0.0F;

            float disableCooldownScale = json.has("disable_cooldown_scale")
                    ? json.get("disable_cooldown_scale").getAsFloat()
                    : 1.0F;

            List<BlocksAttacks.DamageReduction> damageReductions = new ArrayList<>();
            if (json.has("damage_reductions")) {
                JsonElement reductionsElement = json.get("damage_reductions");
                if (!reductionsElement.isJsonArray()) {
                    throw new IllegalArgumentException("damage_reductions must be an array");
                }

                HolderLookup.RegistryLookup<DamageType> damageTypeRegistry = MinecraftServer.getServer().registryAccess()
                        .lookupOrThrow(Registries.DAMAGE_TYPE);

                for (JsonElement element : reductionsElement.getAsJsonArray()) {
                    if (!element.isJsonObject()) {
                        throw new IllegalArgumentException("damage_reductions entries must be objects");
                    }

                    JsonObject reductionJson = element.getAsJsonObject();

                    float horizontalBlockingAngle = reductionJson.has("horizontal_blocking_angle")
                            ? reductionJson.get("horizontal_blocking_angle").getAsFloat()
                            : 90.0F;

                    Optional<HolderSet<DamageType>> type = Optional.empty();
                    if (reductionJson.has("type")) {
                        JsonElement typeElement = reductionJson.get("type");

                        if (typeElement.isJsonPrimitive()) {
                            String value = typeElement.getAsString();

                            if (value.startsWith("#")) {
                                TagKey<DamageType> tagKey = TagKey.create(
                                        Registries.DAMAGE_TYPE,
                                        Identifier.parse(value.substring(1))
                                );
                                type = Optional.of(damageTypeRegistry.get(tagKey).orElseThrow());
                            } else {
                                Holder<DamageType> holder = damageTypeRegistry
                                        .get(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(value)))
                                        .orElseThrow();
                                type = Optional.of(HolderSet.direct(holder));
                            }
                        } else if (typeElement.isJsonArray()) {
                            List<Holder<DamageType>> holders = new ArrayList<>();

                            for (JsonElement entry : typeElement.getAsJsonArray()) {
                                if (!entry.isJsonPrimitive()) {
                                    throw new IllegalArgumentException("damage_reductions.type array entries must be strings");
                                }

                                String value = entry.getAsString();
                                if (value.startsWith("#")) {
                                    throw new IllegalArgumentException("damage_reductions.type array cannot contain tags");
                                }

                                holders.add(
                                        damageTypeRegistry.get(
                                                ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(value))
                                        ).orElseThrow()
                                );
                            }

                            type = Optional.of(HolderSet.direct(holders));
                        } else {
                            throw new IllegalArgumentException("damage_reductions.type must be a string or array");
                        }
                    }

                    float base = reductionJson.has("base")
                            ? reductionJson.get("base").getAsFloat()
                            : 0.0F;

                    float factor = reductionJson.has("factor")
                            ? reductionJson.get("factor").getAsFloat()
                            : 1.0F;

                    damageReductions.add(new BlocksAttacks.DamageReduction(
                            horizontalBlockingAngle,
                            type,
                            base,
                            factor
                    ));
                }
            }

            BlocksAttacks.ItemDamageFunction itemDamage = json.has("item_damage")
                    ? parseItemDamageFunction(json.getAsJsonObject("item_damage"))
                    : new BlocksAttacks.ItemDamageFunction(0.0F, 0.0F, 1.0F);

            Optional<TagKey<DamageType>> bypassedBy = Optional.empty();
            if (json.has("bypassed_by")) {
                String value = json.get("bypassed_by").getAsString();
                if (!value.startsWith("#")) {
                    throw new IllegalArgumentException("bypassed_by must be a hash-prefixed damage type tag");
                }
                bypassedBy = Optional.of(
                        TagKey.create(Registries.DAMAGE_TYPE, Identifier.parse(value.substring(1)))
                );
            }

            Optional<Holder<SoundEvent>> blockSound = Optional.empty();
            if (json.has("block_sound")) {
                blockSound = Optional.of(
                        MinecraftServer.getServer().registryAccess()
                                .lookupOrThrow(Registries.SOUND_EVENT)
                                .get(ResourceKey.create(Registries.SOUND_EVENT, Identifier.parse(json.get("block_sound").getAsString())))
                                .orElseThrow()
                );
            }

            Optional<Holder<SoundEvent>> disabledSound = Optional.empty();
            if (json.has("disabled_sound")) {
                disabledSound = Optional.of(
                        MinecraftServer.getServer().registryAccess()
                                .lookupOrThrow(Registries.SOUND_EVENT)
                                .get(ResourceKey.create(Registries.SOUND_EVENT, Identifier.parse(json.get("disabled_sound").getAsString())))
                                .orElseThrow()
                );
            }

            return new BlocksAttacks(
                    blockDelaySeconds,
                    disableCooldownScale,
                    damageReductions,
                    itemDamage,
                    bypassedBy,
                    blockSound,
                    disabledSound
            );
        } catch (Throwable thrown) {
            throw new DynamicCommandExceptionType(
                    obj -> Component.literal(obj.toString())
            ).create(thrown.getMessage());
        }
    }

    private static BlocksAttacks.ItemDamageFunction parseItemDamageFunction(JsonObject json) {
        float threshold = json.has("threshold")
                ? json.get("threshold").getAsFloat()
                : 0.0F;

        float base = json.has("base")
                ? json.get("base").getAsFloat()
                : 0.0F;

        float factor = json.has("factor")
                ? json.get("factor").getAsFloat()
                : 1.0F;

        return new BlocksAttacks.ItemDamageFunction(threshold, base, factor);
    }

    @Override
    public DataComponentType<BlocksAttacks> nms() {
        return DataComponents.BLOCKS_ATTACKS;
    }
}
