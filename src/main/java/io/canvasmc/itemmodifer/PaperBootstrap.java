package io.canvasmc.itemmodifer;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.canvasmc.itemmodifer.util.NbtTextFormatter;
import io.papermc.paper.command.brigadier.ApiMirrorRootNode;
import io.papermc.paper.command.brigadier.PaperCommands;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.SlotArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.NonNull;

import java.lang.reflect.Field;
import java.util.Objects;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

@SuppressWarnings("UnstableApiUsage")
public class PaperBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(final @NonNull BootstrapContext bootstrapContext) {
        bootstrapContext.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
            try {
                PaperCommands paperCommands = (PaperCommands) commands.registrar();
                final Field field = paperCommands.getClass()
                    .getDeclaredField("buildContext");
                field.setAccessible(true);
                CommandBuildContext buildContext = (CommandBuildContext) field.get(paperCommands);
                CommandDispatcher<CommandSourceStack> nmsDispatcher = ((ApiMirrorRootNode) paperCommands.getDispatcherInternal().getRoot()).getDispatcher();
                ComponentType.buildRegistry(buildContext);
                // registries built, now register command
                nmsDispatcher.register(
                    literal("itemmodify").requires((stack) -> stack.getSender().hasPermission("canvasmc.itemmodify.command") || stack.getSender().isOp())
                        .then(argument("players", EntityArgument.players())
                            .then(argument("slot", SlotArgument.slot())
                                .then(literal("remove").then(argument("component", IdentifierArgument.id())
                                    .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(ComponentType.ids(), builder))
                                    .executes(context -> {
                                        final Identifier identifier = context.getArgument("component", Identifier.class);
                                        final ComponentType type = ComponentType.get(BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(identifier));
                                        final int slot = SlotArgument.getSlot(context, "slot");
                                        Objects.requireNonNull(type, "Unregistered component type " + identifier);
                                        for (final ServerPlayer player : context.getArgument("players", EntitySelector.class).findPlayers(context.getSource())) {
                                            scheduleToOrRun(player, () -> {
                                                SlotAccess slotAccess = player.getSlot(slot);
                                                if (slotAccess == null) return;
                                                ItemStack stack = slotAccess.get();
                                                if (stack.isEmpty()) return;

                                                stack.remove(type.nms());
                                                slotAccess.set(stack);
                                                context.getSource().sendSuccess(() -> Component.literal("Successfully removed component \"" + type.identifier() + "\" on slot " + slot + " for player " + player.getPlainTextName()), true);
                                            });
                                        }
                                        return 0;
                                    })
                                ))
                                .then(literal("set")
                                    .then(argument("component", IdentifierArgument.id())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(ComponentType.ids(), builder))
                                        .then(argument("value", StringArgumentType.greedyString())
                                            .suggests((context, builder) -> {
                                                final Identifier identifier = context.getArgument("component", Identifier.class);
                                                ComponentType<?> type = ComponentType.get(BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(identifier));
                                                return Objects.requireNonNull(type, "Unregistered component type " + identifier).suggestions(context, builder);
                                            }).executes(context -> {
                                                final ComponentType type = ComponentType.get(BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(context.getArgument("component", Identifier.class)));
                                                final Object value = type.parse(context.getArgument("value", String.class));
                                                final int slot = SlotArgument.getSlot(context, "slot");
                                                for (final ServerPlayer player : context.getArgument("players", EntitySelector.class).findPlayers(context.getSource())) {
                                                    scheduleToOrRun(player, () -> {
                                                        SlotAccess slotAccess = player.getSlot(slot);
                                                        if (slotAccess == null) return;
                                                        ItemStack stack = slotAccess.get();
                                                        if (stack.isEmpty()) return;

                                                        type.apply(stack, value);
                                                        slotAccess.set(stack);
                                                        context.getSource().sendSuccess(() -> Component.literal("Successfully modified component \"" + type.identifier() + "\" on slot " + slot + " for player " + player.getPlainTextName()), true);
                                                    });
                                                }
                                                return 0;
                                            })))
                                )
                                .then(literal("dump")
                                    .executes((context) -> {
                                        final int slot = SlotArgument.getSlot(context, "slot");
                                        for (final ServerPlayer player : context.getArgument("players", EntitySelector.class).findPlayers(context.getSource())) {
                                            scheduleToOrRun(player, () -> {
                                                SlotAccess access = player.getSlot(slot);
                                                if (access == null) return;
                                                ItemStack item = access.get();
                                                CompoundTag tag = (CompoundTag) ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, item).result().orElseThrow(
                                                    () -> new RuntimeException("Unable to encode")
                                                );
                                                context.getSource().sendSystemMessage(new NbtTextFormatter(3).apply(tag));
                                            });
                                        }
                                        return 0;
                                    })
                                )
                            ))
                );
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Illegal access", e);
            } catch (NoSuchFieldException e) {
                throw new RuntimeException("Couldn't find build context field in PaperCommands class", e);
            }
        });
    }

    private static void scheduleToOrRun(ServerPlayer player, Runnable task) {
        if (TickThread.isTickThreadFor(player)) {
            task.run();
        } else player.getBukkitEntity().taskScheduler.schedule((entity) -> task.run(), null, 1L);
    }
}
