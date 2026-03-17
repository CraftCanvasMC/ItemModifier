package io.canvasmc.itemmodifer.components;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.canvasmc.itemmodifer.ComponentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.UseCooldown;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class UseCooldownComponent extends ComponentType<UseCooldown> {
    @Override
    public CompletableFuture<Suggestions> suggestions(final CommandContext<CommandSourceStack> context, final SuggestionsBuilder builder) {
        return jsonSuggestions(context, builder);
    }

    @Override
    public Map<String, FieldInfo> jsonFields() {
        return Map.of(
            "seconds", FieldInfo.floatField(),
            "cooldown_group", FieldInfo.stringField()
        );
    }

    @Override
    public DataComponentType<UseCooldown> nms() {
        return DataComponents.USE_COOLDOWN;
    }
}
