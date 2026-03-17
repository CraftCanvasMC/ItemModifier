package io.canvasmc.itemmodifer.components;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.canvasmc.itemmodifer.ComponentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.component.MapDecorations;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class MapDecorationsComponent extends ComponentType<MapDecorations> {
    @Override
    public CompletableFuture<Suggestions> suggestions(final CommandContext<CommandSourceStack> context, final SuggestionsBuilder builder) {
        return jsonSuggestions(context, builder);
    }

    @Override
    public Map<String, FieldInfo> jsonFields() {
        return Map.of(
            "entries", FieldInfo.objectListField(Map.of(
                "key", FieldInfo.stringField(),
                "entry", FieldInfo.objectField(Map.of(
                    "type", FieldInfo.identifierField((context) -> MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.MAP_DECORATION_TYPE).keySet()),
                    "x", FieldInfo.floatField(),
                    "z", FieldInfo.floatField(),
                    "rotation", FieldInfo.floatField()
                ))
            ))
        );
    }

    @Override
    public MapDecorations parse(@NonNull final String raw) throws CommandSyntaxException {
        try {
            String fixed = raw.replaceAll(
                "([\\[,{:])\\s*([a-z0-9_.-]+:[a-z0-9_./-]+)(?=[\\s,}\\]])",
                "$1\"$2\""
            );
            JsonObject json = GSON.fromJson(fixed, JsonObject.class);
            Map<String, MapDecorations.Entry> decorations = new HashMap<>();
            for (final JsonElement mapEntryE : json.getAsJsonArray("entries")) {
                JsonObject mapEntry = mapEntryE.getAsJsonObject();
                if (!mapEntry.has("key")) throw new IllegalArgumentException("Missing 'key' value");
                if (!mapEntry.has("entry")) throw new IllegalArgumentException("Missing 'entry' value");
                String key = mapEntry.get("key").getAsString();
                JsonObject entryObj = mapEntry.getAsJsonObject("entry");
                MapDecorations.Entry entry = new MapDecorations.Entry(
                    MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.MAP_DECORATION_TYPE)
                        .get(Identifier.parse(entryObj.get("type").getAsString())).orElseThrow(() -> new IllegalArgumentException("Unknown type")),
                    entryObj.get("x").getAsDouble(),
                    entryObj.get("z").getAsDouble(),
                    entryObj.get("rotation").getAsFloat()
                );
                decorations.put(key, entry);
            }
            return new MapDecorations(decorations);
        } catch (Throwable thrown) {
            throw new DynamicCommandExceptionType(
                obj -> Component.literal(obj.toString())
            ).create(thrown.getMessage());
        }
    }

    @Override
    public DataComponentType<MapDecorations> nms() {
        return DataComponents.MAP_DECORATIONS;
    }
}
