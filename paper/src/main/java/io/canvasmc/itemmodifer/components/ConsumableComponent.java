package io.canvasmc.itemmodifer.components;

import com.google.gson.*;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.canvasmc.itemmodifer.ComponentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.*;
import net.minecraft.world.effect.*;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.consume_effects.*;
import org.jspecify.annotations.NonNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ConsumableComponent extends ComponentType<Consumable> {
    @Override
    public CompletableFuture<Suggestions> suggestions(final CommandContext<CommandSourceStack> context, final SuggestionsBuilder builder) {
        return jsonSuggestions(context, builder);
    }

    @Override
    public Map<String, FieldInfo> jsonFields() {
        return Map.of(
                "consume_seconds", FieldInfo.floatField("1.6F"),
                "animation", FieldInfo.stringField(parseFromEnum(ItemUseAnimation.class)),
                "sound", FieldInfo.identifierField(context ->
                        MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.SOUND_EVENT).keySet()
                ),
                "has_consume_particles", FieldInfo.bool(),
                "on_consume_effects", FieldInfo.taggedObjectListField("type", consumeEffectVariants())
        );
    }

    // static helper so multiple components can re-use. should probably move this somewhere else?
    public static Map<String, Map<String, FieldInfo>> consumeEffectVariants() {
        return Map.of(
                "minecraft:apply_effects", Map.of(
                        "type", FieldInfo.stringField("minecraft:apply_effects"),
                        "effects", FieldInfo.objectListField(Map.of(
                                "id", FieldInfo.identifierField(context ->
                                        MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.MOB_EFFECT).keySet()
                                ),
                                "amplifier", FieldInfo.intField("0"),
                                "duration", FieldInfo.intField("1", "-1"),
                                "ambient", FieldInfo.bool(),
                                "show_particles", FieldInfo.bool(),
                                "show_icon", FieldInfo.bool()
                        )),
                        "probability", FieldInfo.floatField("1.0F")
                ),
                "minecraft:remove_effects", Map.of(
                        "type", FieldInfo.stringField("minecraft:remove_effects"),
                        "effects", FieldInfo.dynamicStringField(context -> {
                            List<String> out = new ArrayList<>();
                            for (Identifier id : MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.MOB_EFFECT).keySet()) {
                                out.add(id.toString());
                                out.add("#" + id);
                            }
                            return out;
                        })
                ),
                "minecraft:clear_all_effects", Map.of(
                        "type", FieldInfo.stringField("minecraft:clear_all_effects")
                ),
                "minecraft:teleport_randomly", Map.of(
                        "type", FieldInfo.stringField("minecraft:teleport_randomly"),
                        "diameter", FieldInfo.floatField("16.0F")
                ),
                "minecraft:play_sound", Map.of(
                        "type", FieldInfo.stringField("minecraft:play_sound"),
                        "sound", FieldInfo.identifierField(context ->
                                MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.SOUND_EVENT).keySet()
                        )
                )
        );
    }

    @Override
    public Consumable parse(@NonNull final String raw) throws CommandSyntaxException {
        try {
            String fixed = raw.replaceAll(
                    "([\\[,{:])\\s*([a-z0-9_.-]+:[a-z0-9_./-]+)(?=[\\s,}\\]])",
                    "$1\"$2\""
            );

            JsonObject json = GSON.fromJson(fixed, JsonObject.class);

            float consumeSeconds = json.has("consume_seconds")
                    ? json.get("consume_seconds").getAsFloat()
                    : 1.6F;

            ItemUseAnimation itemUseAnimation = json.has("animation")
                    ? ItemUseAnimation.valueOf(json.get("animation").getAsString().toUpperCase(Locale.ROOT))
                    : ItemUseAnimation.EAT;

            Holder<SoundEvent> sound = json.has("sound")
                    ? MinecraftServer.getServer().registryAccess()
                    .lookupOrThrow(Registries.SOUND_EVENT)
                    .get(Identifier.parse(json.get("sound").getAsString()))
                    .orElseThrow()
                    : SoundEvents.GENERIC_EAT;

            boolean hasConsumeParticles = !json.has("has_consume_particles")
                    || json.get("has_consume_particles").getAsBoolean();

            List<ConsumeEffect> effects = new ArrayList<>();
            if (json.has("on_consume_effects") && json.get("on_consume_effects").isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray("on_consume_effects")) {
                    if (!element.isJsonObject()) {
                        throw new IllegalArgumentException("Each on_consume_effects entry must be an object");
                    }
                    effects.add(parseConsumeEffect(element.getAsJsonObject()));
                }
            }

            return new Consumable(consumeSeconds, itemUseAnimation, sound, hasConsumeParticles, effects);
        } catch (Throwable thrown) {
            throw new DynamicCommandExceptionType(obj -> Component.literal(obj.toString()))
                    .create(thrown.getMessage());
        }
    }

    public static ConsumeEffect parseConsumeEffect(JsonObject json) {
        if (!json.has("type")) {
            throw new IllegalArgumentException("Consume effect is missing required field 'type'");
        }

        String type = json.get("type").getAsString();

        return switch (type) {
            case "minecraft:apply_effects" -> parseApplyEffects(json);
            case "minecraft:remove_effects" -> parseRemoveEffects(json);
            case "minecraft:clear_all_effects" -> parseClearAllEffects(json);
            case "minecraft:teleport_randomly" -> parseTeleportRandomly(json);
            case "minecraft:play_sound" -> parsePlaySound(json);
            default -> throw new IllegalArgumentException("Unknown consume effect type: " + type);
        };
    }

    private static ConsumeEffect parseApplyEffects(JsonObject json) {
        if (!json.has("effects") || !json.get("effects").isJsonArray()) {
            throw new IllegalArgumentException("minecraft:apply_effects requires an 'effects' array");
        }

        List<MobEffectInstance> effects = new ArrayList<>();
        for (JsonElement effectElement : json.getAsJsonArray("effects")) {
            if (!effectElement.isJsonObject()) {
                throw new IllegalArgumentException("Each apply_effects entry must be an object");
            }
            effects.add(parseMobEffectInstance(effectElement.getAsJsonObject()));
        }

        float probability = json.has("probability")
                ? json.get("probability").getAsFloat()
                : 1.0F;

        return new ApplyStatusEffectsConsumeEffect(effects, probability);
    }

    private static MobEffectInstance parseMobEffectInstance(JsonObject json) {
        if (!json.has("id")) {
            throw new IllegalArgumentException("Mob effect entry is missing required field 'id'");
        }

        Holder<MobEffect> effect = MinecraftServer.getServer().registryAccess()
                .lookupOrThrow(Registries.MOB_EFFECT)
                .get(Identifier.parse(json.get("id").getAsString()))
                .orElseThrow();

        int amplifier = json.has("amplifier") ? json.get("amplifier").getAsInt() : 0;
        int duration = json.has("duration") ? json.get("duration").getAsInt() : 1;
        boolean ambient = json.has("ambient") && json.get("ambient").getAsBoolean();
        boolean showParticles = !json.has("show_particles") || json.get("show_particles").getAsBoolean();
        boolean showIcon = !json.has("show_icon") || json.get("show_icon").getAsBoolean();

        return new MobEffectInstance(effect, duration, amplifier, ambient, showParticles, showIcon);
    }

    private static ConsumeEffect parseRemoveEffects(JsonObject json) {
        if (!json.has("effects")) {
            throw new IllegalArgumentException("minecraft:remove_effects requires an 'effects' field");
        }

        HolderLookup.RegistryLookup<MobEffect> registry = MinecraftServer.getServer().registryAccess()
                .lookupOrThrow(Registries.MOB_EFFECT);

        JsonElement effectsElement = json.get("effects");

        if (effectsElement.isJsonPrimitive()) {
            String value = effectsElement.getAsString();

            if (value.startsWith("#")) {
                TagKey<MobEffect> tagKey = TagKey.create(Registries.MOB_EFFECT, Identifier.parse(value.substring(1)));
                HolderSet.Named<MobEffect> named = registry.get(tagKey).orElseThrow();
                return new RemoveStatusEffectsConsumeEffect(named);
            }

            Holder<MobEffect> holder = registry.get(ResourceKey.create(Registries.MOB_EFFECT, Identifier.parse(value))).orElseThrow();
            return new RemoveStatusEffectsConsumeEffect(HolderSet.direct(holder));
        }

        if (effectsElement.isJsonArray()) {
            List<Holder<MobEffect>> holders = new ArrayList<>();

            for (JsonElement element : effectsElement.getAsJsonArray()) {
                if (!element.isJsonPrimitive()) {
                    throw new IllegalArgumentException("remove_effects array entries must be strings");
                }

                String value = element.getAsString();
                if (value.startsWith("#")) {
                    throw new IllegalArgumentException("Tags inside remove_effects arrays are not supported here; use a single '#namespace:path' string");
                }

                holders.add(
                        registry.get(ResourceKey.create(Registries.MOB_EFFECT, Identifier.parse(value))).orElseThrow()
                );
            }

            return new RemoveStatusEffectsConsumeEffect(HolderSet.direct(holders));
        }

        throw new IllegalArgumentException("minecraft:remove_effects.effects must be a string or array");
    }

    private static ConsumeEffect parseClearAllEffects(JsonObject json) {
        return new ClearAllStatusEffectsConsumeEffect();
    }

    private static ConsumeEffect parseTeleportRandomly(JsonObject json) {
        float diameter = json.has("diameter")
                ? json.get("diameter").getAsFloat()
                : 16.0F;

        return new TeleportRandomlyConsumeEffect(diameter);
    }

    private static ConsumeEffect parsePlaySound(JsonObject json) {
        if (!json.has("sound")) {
            throw new IllegalArgumentException("minecraft:play_sound requires a 'sound' field");
        }

        Holder<SoundEvent> sound = MinecraftServer.getServer().registryAccess()
                .lookupOrThrow(Registries.SOUND_EVENT)
                .get(Identifier.parse(json.get("sound").getAsString()))
                .orElseThrow();

        return new PlaySoundConsumeEffect(sound);
    }

    @Override
    public DataComponentType<Consumable> nms() {
        return DataComponents.CONSUMABLE;
    }
}
