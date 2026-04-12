package io.canvasmc.itemmodifer.components;

import com.mojang.brigadier.context.*;
import com.mojang.brigadier.suggestion.*;
import io.canvasmc.itemmodifer.*;
import net.minecraft.commands.*;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.item.component.*;

import java.util.*;
import java.util.concurrent.*;

public class DeathProtectionComponent extends ComponentType<DeathProtection> {

    private static final Map<String, FieldInfo> ENTRY_FIELDS = Map.of(
            "type", FieldInfo.identifierField(ctx -> BuiltInRegistries.ATTRIBUTE.keySet()),
            "effects", FieldInfo.floatField(),
            "probability", FieldInfo.stringField(parseFromEnum(AttributeModifier.Operation.class)),
            "slot_group", FieldInfo.stringField(parseFromEnum(EquipmentSlotGroup.class))
    );

    @Override
    public Map<String, FieldInfo> jsonFields() {
        return Map.of(
                "death_effects", FieldInfo.objectListField(ENTRY_FIELDS)
        );
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
