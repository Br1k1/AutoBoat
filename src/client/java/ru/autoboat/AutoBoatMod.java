package ru.autoboat;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;

public class AutoBoatMod implements ClientModInitializer {

    private static boolean active = false;
    private static boolean avoid = true;                  // объезжать преграды
    private static Double targetX = null, targetZ = null; // режим "к точке"
    private static Float fixedYaw = null;                 // режим "курс" (сторона света / прямо)
    private static boolean keysHeld = false;
    private static float lastYaw = 0f;

    private static final double LOOK_AHEAD = 8.0;
    private static final double BOAT_HALF_WIDTH = 1.0;
    // Подкрути, если виляет (больше) или не доворачивает (меньше)
    private static final float INERTIA = 6f;
    private static final float DEADZONE = 3f;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(AutoBoatMod::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommandManager.literal("autoboat");

            // /autoboat goto <x> <z>
            root.then(ClientCommandManager.literal("goto")
                .then(ClientCommandManager.argument("x", DoubleArgumentType.doubleArg())
                .then(ClientCommandManager.argument("z", DoubleArgumentType.doubleArg())
                .executes(ctx -> {
                    targetX = DoubleArgumentType.getDouble(ctx, "x");
                    targetZ = DoubleArgumentType.getDouble(ctx, "z");
                    fixedYaw = null;
                    active = true;
                    ctx.getSource().sendFeedback(Component.literal("AutoBoat: еду к " + targetX + ", " + targetZ));
                    return 1;
                }))));

            // /autoboat dir <north|south|east|west|ne|nw|se|sw>
            // Юг = yaw 0, запад = 90, север = 180, восток = -90
            Map<String, Float> dirs = new LinkedHashMap<>();
            dirs.put("north", 180f); dirs.put("south", 0f);
            dirs.put("east", -90f);  dirs.put("west", 90f);
            dirs.put("ne", -135f);   dirs.put("nw", 135f);
            dirs.put("se", -45f);    dirs.put("sw", 45f);
            LiteralArgumentBuilder<FabricClientCommandSource> dir = ClientCommandManager.literal("dir");
            for (Map.Entry<String, Float> e : dirs.entrySet()) {
                dir.then(ClientCommandManager.literal(e.getKey()).executes(ctx -> {
                    fixedYaw = e.getValue();
                    targetX = targetZ = null;
                    active = true;
                    ctx.getSource().sendFeedback(Component.literal("AutoBoat: курс " + e.getKey()));
                    return 1;
                }));
            }
            root.then(dir);

            // /autoboat straight — ехать прямо по текущему курсу лодки
            root.then(ClientCommandManager.literal("straight").executes(ctx -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && mc.player.getVehicle() instanceof Boat b) {
                    fixedYaw = b.getYRot();
                    targetX = targetZ = null;
                    active = true;
                    ctx.getSource().sendFeedback(Component.literal("AutoBoat: еду прямо"));
                } else {
                    ctx.getSource().sendFeedback(Component.literal("AutoBoat: сначала сядь в лодку"));
                }
                return 1;
            }));

            // /autoboat avoid <on|off>
            root.then(ClientCommandManager.literal("avoid")
                .then(ClientCommandManager.literal("on").executes(ctx -> {
                    avoid = true;
                    ctx.getSource().sendFeedback(Component.literal("AutoBoat: объезд включён"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("off").executes(ctx -> {
                    avoid = false;
                    ctx.getSource().sendFeedback(Component.literal("AutoBoat: объезд выключен"));
                    return 1;
                })));

            // /autoboat stop
            root.then(ClientCommandManager.literal("stop").executes(ctx -> {
                stop(Minecraft.getInstance());
                ctx.getSource().sendFeedback(Component.literal("AutoBoat: выключен"));
                return 1;
            }));

            dispatcher.register(root);
        });
    }

    private static void stop(Minecraft mc) {
        active = false;
        targetX = targetZ = null;
        fixedYaw = null;
        releaseKeys(mc);
    }

    private static void releaseKeys(Minecraft mc) {
        if (!keysHeld || mc.options == null) return;
        Options o = mc.options;
        o.keyUp.setDown(false);
        o.keyDown.setDown(false);
        o.keyLeft.setDown(false);
        o.keyRight.setDown(false);
        keysHeld = false;
    }

    private static void tick(Minecraft mc) {
        if (!active || mc.player == null || mc.level == null) return;

        if (!(mc.player.getVehicle() instanceof Boat boat)) {
            releaseKeys(mc);
            return; // не в лодке — ждём
        }

        // Желаемое направление
        float desired;
        if (targetX != null) {
            double dx = targetX - boat.getX();
            double dz = targetZ - boat.getZ();
            if (Math.sqrt(dx * dx + dz * dz) < 3.0) {
                stop(mc);
                mc.player.displayClientMessage(Component.literal("AutoBoat: приехали!"), false);
                return;
            }
            desired = (float) (Mth.atan2(-dx, dz) * 180.0 / Math.PI);
        } else {
            desired = fixedYaw;
        }

        // Выбор курса: если объезд выключен — строго по желаемому
        Float chosen = null;
        if (!avoid) {
            chosen = desired;
        } else {
            // Ближайший к желаемому курсу свободный угол: 0, ±15, ±30 ... ±135
            for (int off = 0; off <= 135; off += 15) {
                if (isClear(mc.level, boat, desired + off)) { chosen = desired + off; break; }
                if (off != 0 && isClear(mc.level, boat, desired - off)) { chosen = desired - off; break; }
            }
        }

        Options o = mc.options;
        float yaw = boat.getYRot();
        if (!keysHeld) lastYaw = yaw;
        keysHeld = true;
        o.keyDown.setDown(false);

        // Скорость вращения лодки (градусов за тик) — чтобы не проскакивать курс
        float rate = Mth.wrapDegrees(yaw - lastYaw);
        lastYaw = yaw;

        if (chosen == null) {
            // Всё перекрыто — крутимся на месте без газа
            o.keyUp.setDown(false);
            o.keyLeft.setDown(false);
            o.keyRight.setDown(true);
            return;
        }

        float diff = Mth.wrapDegrees(chosen - yaw);
        // Ориентируемся на то, куда лодка докрутится по инерции
        float predicted = diff - rate * INERTIA;
        o.keyRight.setDown(predicted > DEADZONE);
        o.keyLeft.setDown(predicted < -DEADZONE);
        // Если надо сильно развернуться — не газуем
        o.keyUp.setDown(Math.abs(diff) < 60f);
    }

    /** Свободен ли путь по этому углу (3 луча: центр и оба борта). */
    private static boolean isClear(Level level, Boat boat, float yawDeg) {
        double rad = Math.toRadians(yawDeg);
        double fx = -Math.sin(rad), fz = Math.cos(rad);   // вперёд
        double sx = Math.cos(rad),  sz = Math.sin(rad);   // вбок
        for (double lateral : new double[]{0, -BOAT_HALF_WIDTH, BOAT_HALF_WIDTH}) {
            for (double d = 1; d <= LOOK_AHEAD; d += 1) {
                double x = boat.getX() + fx * d + sx * lateral;
                double z = boat.getZ() + fz * d + sz * lateral;
                if (blocked(level, x, boat.getY(), z)) return false;
            }
        }
        return true;
    }

    /** Преграда = твёрдый блок на уровне лодки/выше, либо нет воды под лодкой (берег). */
    private static boolean blocked(Level level, double x, double y, double z) {
        BlockPos p = BlockPos.containing(x, y, z);
        BlockState s = level.getBlockState(p);
        BlockState up = level.getBlockState(p.above());
        if (!s.getCollisionShape(level, p).isEmpty()) return true;
        if (!up.getCollisionShape(level, p.above()).isEmpty()) return true;
        boolean water = level.getFluidState(p).is(FluidTags.WATER)
                     || level.getFluidState(p.below()).is(FluidTags.WATER);
        return !water;
    }
}
