package ru.autoboat;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.LinkedHashMap;
import java.util.Map;

public class AutoBoatMod implements ClientModInitializer {

    // Режимы
    private static boolean active = false;
    private static Double targetX = null, targetZ = null; // режим "к точке"
    private static Float fixedYaw = null;                 // режим "по стороне света"
    private static boolean keysHeld = false;

    // Настройки
    private static final double LOOK_AHEAD = 8.0;   // на сколько блоков вперёд смотрим
    private static final double BOAT_HALF_WIDTH = 1.0;
    private static final float TURN_DEADZONE = 4f;  // градусов, чтобы не дёргалась

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
                    ctx.getSource().sendFeedback(Text.literal("AutoBoat: еду к " + targetX + ", " + targetZ));
                    return 1;
                }))));

            // /autoboat dir <north|south|east|west|ne|nw|se|sw>
            // В Minecraft: юг = yaw 0, запад = 90, север = 180, восток = -90
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
                    ctx.getSource().sendFeedback(Text.literal("AutoBoat: курс " + e.getKey()));
                    return 1;
                }));
            }
            root.then(dir);

            // /autoboat stop
            root.then(ClientCommandManager.literal("stop").executes(ctx -> {
                stop(MinecraftClient.getInstance());
                ctx.getSource().sendFeedback(Text.literal("AutoBoat: выключен"));
                return 1;
            }));

            dispatcher.register(root);
        });
    }

    private static void stop(MinecraftClient mc) {
        active = false;
        targetX = targetZ = null;
        fixedYaw = null;
        releaseKeys(mc);
    }

    private static void releaseKeys(MinecraftClient mc) {
        if (!keysHeld || mc.options == null) return;
        GameOptions o = mc.options;
        o.forwardKey.setPressed(false);
        o.backKey.setPressed(false);
        o.leftKey.setPressed(false);
        o.rightKey.setPressed(false);
        keysHeld = false;
    }

    private static void tick(MinecraftClient mc) {
        if (!active || mc.player == null || mc.world == null) return;

        if (!(mc.player.getVehicle() instanceof BoatEntity boat)) {
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
                mc.player.sendMessage(Text.literal("AutoBoat: приехали!"), false);
                return;
            }
            desired = (float) (MathHelper.atan2(-dx, dz) * 180.0 / Math.PI);
        } else {
            desired = fixedYaw;
        }

        // Ищем ближайший к желаемому курсу свободный угол: 0, ±15, ±30 ... ±135
        Float chosen = null;
        for (int off = 0; off <= 135; off += 15) {
            if (isClear(mc.world, boat, desired + off)) { chosen = desired + off; break; }
            if (off != 0 && isClear(mc.world, boat, desired - off)) { chosen = desired - off; break; }
        }

        GameOptions o = mc.options;
        keysHeld = true;
        o.backKey.setPressed(false);

        if (chosen == null) {
            // Всё перекрыто — крутимся на месте без газа
            o.forwardKey.setPressed(false);
            o.leftKey.setPressed(false);
            o.rightKey.setPressed(true);
            return;
        }

        float diff = MathHelper.wrapDegrees(chosen - boat.getYaw());
        o.rightKey.setPressed(diff > TURN_DEADZONE);
        o.leftKey.setPressed(diff < -TURN_DEADZONE);
        // Если надо сильно развернуться — не газуем, чтобы не вылететь на берег
        o.forwardKey.setPressed(Math.abs(diff) < 60f);
    }

    /** Свободен ли путь по этому углу (проверяем 3 луча: центр и оба борта). */
    private static boolean isClear(World world, BoatEntity boat, float yawDeg) {
        double rad = Math.toRadians(yawDeg);
        double fx = -Math.sin(rad), fz = Math.cos(rad);   // вперёд
        double sx = Math.cos(rad),  sz = Math.sin(rad);   // вбок
        for (double lateral : new double[]{0, -BOAT_HALF_WIDTH, BOAT_HALF_WIDTH}) {
            for (double d = 1; d <= LOOK_AHEAD; d += 1) {
                double x = boat.getX() + fx * d + sx * lateral;
                double z = boat.getZ() + fz * d + sz * lateral;
                if (blocked(world, x, boat.getY(), z)) return false;
            }
        }
        return true;
    }

    /** Преграда = твёрдый блок на уровне лодки/выше, либо нет воды под лодкой (берег). */
    private static boolean blocked(World world, double x, double y, double z) {
        BlockPos p = BlockPos.ofFloored(x, y, z);
        BlockState s = world.getBlockState(p);
        BlockState up = world.getBlockState(p.up());
        if (!s.getCollisionShape(world, p).isEmpty()) return true;
        if (!up.getCollisionShape(world, p.up()).isEmpty()) return true;
        boolean water = world.getFluidState(p).isIn(FluidTags.WATER)
                     || world.getFluidState(p.down()).isIn(FluidTags.WATER);
        return !water;
    }
}
