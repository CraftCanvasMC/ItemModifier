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
import net.minecraft.sounds.*;
import net.minecraft.world.item.component.*;
import org.jspecify.annotations.*;

import java.util.*;
import java.util.concurrent.*;

public class KineticWeaponComponent extends ComponentType<KineticWeapon> {
    @Override
    public CompletableFuture<Suggestions> suggestions(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return jsonSuggestions(context, builder);
    }

    @Override
    public Map<String, FieldInfo> jsonFields() {
        return Map.of(
                "contact_cooldown_ticks", FieldInfo.intField("10"),
                "delay_ticks", FieldInfo.intField("0", "20"),
                "damage_conditions", FieldInfo.objectField(kineticEffectConditionFields()),
                "knockback_conditions", FieldInfo.objectField(kineticEffectConditionFields()),
                "dismount_conditions", FieldInfo.objectField(kineticEffectConditionFields()),
                "forward_movement", FieldInfo.floatField("0.0F", "1.0F"),
                "damage_multiplier", FieldInfo.floatField("1.0F"),
                "sound", FieldInfo.identifierField(context ->
                        MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.SOUND_EVENT).keySet()
                ),
                "hit_sound", FieldInfo.identifierField(context ->
                        MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.SOUND_EVENT).keySet()
                )
        );
    }

    private static Map<String, FieldInfo> kineticEffectConditionFields() {
        return Map.of(
                "max_duration_ticks", FieldInfo.intField("-1", "20", "40", "60"),
                "min_speed", FieldInfo.floatField("0.0F"),
                "min_relative_speed", FieldInfo.floatField("0.0F")
        );
    }

    @Override
    public KineticWeapon parse(@NonNull String raw) throws CommandSyntaxException {
        try {
            String fixed = raw.replaceAll(
                    "([\\[,{:])\\s*([#]?[a-z0-9_.-]+:[a-z0-9_./-]+)(?=[\\s,}\\]])",
                    "$1\"$2\""
            );

            JsonObject json = GSON.fromJson(fixed, JsonObject.class);

            int contactCooldownTicks = json.has("contact_cooldown_ticks")
                    ? json.get("contact_cooldown_ticks").getAsInt()
                    : 10;

            int delayTicks = json.has("delay_ticks")
                    ? json.get("delay_ticks").getAsInt()
                    : 0;

            Optional<KineticWeapon.Condition> damageConditions = json.has("damage_conditions")
                    ? Optional.of(parseKineticEffectConditions(json.getAsJsonObject("damage_conditions")))
                    : Optional.empty();

            Optional<KineticWeapon.Condition> knockbackConditions = json.has("knockback_conditions")
                    ? Optional.of(parseKineticEffectConditions(json.getAsJsonObject("knockback_conditions")))
                    : Optional.empty();

            Optional<KineticWeapon.Condition> dismountConditions = json.has("dismount_conditions")
                    ? Optional.of(parseKineticEffectConditions(json.getAsJsonObject("dismount_conditions")))
                    : Optional.empty();

            float forwardMovement = json.has("forward_movement")
                    ? json.get("forward_movement").getAsFloat()
                    : 0.0F;

            float damageMultiplier = json.has("damage_multiplier")
                    ? json.get("damage_multiplier").getAsFloat()
                    : 1.0F;

            Optional<Holder<SoundEvent>> sound = json.has("sound")
                    ? Optional.of(
                    MinecraftServer.getServer().registryAccess()
                            .lookupOrThrow(Registries.SOUND_EVENT)
                            .get(ResourceKey.create(
                                    Registries.SOUND_EVENT,
                                    Identifier.parse(json.get("sound").getAsString())
                            ))
                            .orElseThrow()
            )
                    : Optional.empty();

            Optional<Holder<SoundEvent>> hitSound = json.has("hit_sound")
                    ? Optional.of(
                    MinecraftServer.getServer().registryAccess()
                            .lookupOrThrow(Registries.SOUND_EVENT)
                            .get(ResourceKey.create(
                                    Registries.SOUND_EVENT,
                                    Identifier.parse(json.get("hit_sound").getAsString())
                            ))
                            .orElseThrow()
            )
                    : Optional.empty();

            return new KineticWeapon(
                    contactCooldownTicks,
                    delayTicks,
                    damageConditions,
                    knockbackConditions,
                    dismountConditions,
                    forwardMovement,
                    damageMultiplier,
                    sound,
                    hitSound
            );
        } catch (Throwable thrown) {
            throw new DynamicCommandExceptionType(
                    obj -> Component.literal(obj.toString())
            ).create(thrown.getMessage());
        }
    }

    private static KineticWeapon.Condition parseKineticEffectConditions(JsonObject json) {
        int maxDurationTicks = json.has("max_duration_ticks")
                ? json.get("max_duration_ticks").getAsInt()
                : -1;

        float minSpeed = json.has("min_speed")
                ? json.get("min_speed").getAsFloat()
                : 0.0F;

        float minRelativeSpeed = json.has("min_relative_speed")
                ? json.get("min_relative_speed").getAsFloat()
                : 0.0F;

        return new KineticWeapon.Condition(
                maxDurationTicks,
                minSpeed,
                minRelativeSpeed
        );
    }

    @Override
    public DataComponentType<KineticWeapon> nms() {
        return DataComponents.KINETIC_WEAPON;
    }
}
