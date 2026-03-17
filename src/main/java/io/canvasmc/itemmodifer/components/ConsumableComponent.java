package io.canvasmc.itemmodifer.components;

import com.google.gson.JsonObject;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.canvasmc.itemmodifer.ComponentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.Consumable;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Map;
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
            "sound", FieldInfo.identifierField((context) -> MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.SOUND_EVENT).keySet()),
            "has_consume_particles", FieldInfo.bool()
            // on consume effects is a tad... complex to implement. should we implement this in the future?
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
            float consumeSeconds = json.has("consume_seconds") ? json.get("consume_seconds").getAsFloat() : 1.6F;
            ItemUseAnimation itemUseAnimation = json.has("animation") ? ItemUseAnimation.valueOf(json.get("animation").getAsString().toUpperCase()) : ItemUseAnimation.EAT;
            Holder<SoundEvent> sound = json.has("sound") ? MinecraftServer.getServer().registryAccess()
                .lookupOrThrow(Registries.SOUND_EVENT)
                .get(Identifier.parse(json.get("sound").getAsString())).orElseThrow() : SoundEvents.GENERIC_EAT;
            boolean hasConsumeParticles = !json.has("has_consume_particles") || json.get("has_consume_particles").getAsBoolean();
            return new Consumable(consumeSeconds, itemUseAnimation, sound, hasConsumeParticles, new ArrayList<>());
        } catch (Throwable thrown) {
            throw new DynamicCommandExceptionType(
                obj -> Component.literal(obj.toString())
            ).create(thrown.getMessage());
        }
    }

    @Override
    public DataComponentType<Consumable> nms() {
        return DataComponents.CONSUMABLE;
    }
}
