package io.canvasmc.itemmodifer.components;

import com.google.gson.*;
import com.mojang.brigadier.context.*;
import com.mojang.brigadier.exceptions.*;
import com.mojang.brigadier.suggestion.*;
import io.canvasmc.itemmodifer.*;
import net.minecraft.commands.*;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.*;
import net.minecraft.network.chat.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.item.component.*;
import net.minecraft.world.item.consume_effects.*;
import org.jspecify.annotations.*;

import java.util.*;
import java.util.concurrent.*;

public class DeathProtectionComponent extends ComponentType<DeathProtection> {

    @Override
    public Map<String, FieldInfo> jsonFields() {
        return Map.of(
                "death_effects", FieldInfo.taggedObjectListField("type", ConsumableComponent.consumeEffectVariants())
        );
    }


    @Override
    public DeathProtection parse(@NonNull String raw) throws CommandSyntaxException {
        try {
            String fixed = raw.replaceAll(
                    "([\\[,{:])\\s*([a-z0-9_.-]+:[a-z0-9_./-]+)(?=[\\s,}\\]])",
                    "$1\"$2\""
            );

            JsonObject json = GSON.fromJson(fixed, JsonObject.class);
            List<ConsumeEffect> effects = new ArrayList<>();

            if (json.has("death_effects") && json.get("death_effects").isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray("death_effects")) {
                    if (!element.isJsonObject()) {
                        throw new IllegalArgumentException("Each death_effects entry must be an object");
                    }
                    effects.add(ConsumableComponent.parseConsumeEffect(element.getAsJsonObject()));
                }
            }

            return new DeathProtection(effects);
        } catch (Throwable thrown) {
            throw new DynamicCommandExceptionType(obj -> Component.literal(obj.toString()))
                    .create(thrown.getMessage());
        }
    }

    @Override
    public CompletableFuture<Suggestions> suggestions(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return jsonSuggestions(context, builder);
    }

    @Override
    public DataComponentType<DeathProtection> nms() {
        return DataComponents.DEATH_PROTECTION;
    }
}
