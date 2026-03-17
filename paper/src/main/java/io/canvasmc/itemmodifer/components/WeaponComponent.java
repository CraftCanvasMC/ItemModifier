package io.canvasmc.itemmodifer.components;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.canvasmc.itemmodifer.ComponentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.Weapon;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class WeaponComponent extends ComponentType<Weapon> {
    @Override
    public CompletableFuture<Suggestions> suggestions(final CommandContext<CommandSourceStack> context, final SuggestionsBuilder builder) {
        return jsonSuggestions(context, builder);
    }

    @Override
    public Map<String, FieldInfo> jsonFields() {
        return Map.of(
            "item_damage_per_attack", FieldInfo.intField("1"),
            "disable_blocking_for_seconds", FieldInfo.floatField("0.0F")
        );
    }

    @Override
    public DataComponentType<Weapon> nms() {
        return DataComponents.WEAPON;
    }
}
