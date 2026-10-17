package ru.autoboat;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.glfw.GLFW;

import java.util.LinkedHashMap;
import java.util.Map;

public class AutoBoatMod implements ClientModInitializer {

    private static boolean active = false;
    private static boolean avoid = true;                  // объезжать преграды
    private static boolean straightMode = false;          // режим "прямо"
    private static Double targetX = null, targetZ = null; // режим "к точке"
    private static Float fixedYaw = null;                 // режим "курс"
    private static boolean keysHeld = false;
    private static boolean wasPaused = false;
    private static boolean tracking = false;
    private static float lastYaw = 0f;

    // Запоминание выбранного обхода (чтобы не дёргаться)
    private static float lastOffset = 0f;
    private static int holdTicks = 0;

    private static KeyMapping pauseKey;     // X — зажать для ручной коррекции
    private static KeyMapping straightKey;  // R — ехать прямо / выключить
    private static KeyMapping stopKey;      // C — выключить автопилот
    private static boolean prevStraight = false;
    private static boolean prevStop = false;

    // ===== НАСТРОЙКИ (резкий поворот) =====
    private static final double LOOK_AHEAD = 16.0;   // на сколько блоков вперёд смотрим
    private static final double BOAT_HALF_WIDTH = 1.0;
    // INERTIA меньше = руль держится дольше, поворот резче (но может проскакивать курс)
    // DEADZONE меньше = точнее курс (но руль чаще подёргивается)
    private static final float INERTIA = 5f;
    private static final float DEADZONE = 3f;
    private static final int HOLD_AVOID_TICKS = 20;  // сколько тиков держать выбранный обход
    private static final float GAS_CUTOFF = 100f;    // при большем угле газ сбрасывается

    @Override
    public void onInitializeClient() {
        // Названия без двоеточий (двоеточие ломает options.txt)
        pauseKey = KeyBindingHelper.registerKeyBinding(
            new KeyMapping("AutoBoat Pause (hold)", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, "AutoBoat"));
        straightKey = KeyBindingHelper.registerKeyBinding(
            new KeyMapping("AutoBoat Straight", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "AutoBoat"));
        stopKey = KeyBindingHelper.registerKeyBinding(
            new KeyMapping("AutoBoat Stop", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, "AutoBoat"));

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
                    straightMode = false;
                    resetAvoid();
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
                    straightMode = false;
                    resetAvoid();
                    active = true;
                    ctx.getSource().sendFeedback(Component.literal("AutoBoat: курс " + e.getKey()));
                    return 1;
                }));
            }
            root.then(dir);

            // /autoboat straight — ехать прямо по текущему курсу лодки
            root.then(ClientCommandManager.literal("straight").executes(ctx -> {
                startStraight(Minecraft.getInstance());
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

    /** Нажата ли клавиша, привязанная к этому действию (читаем напрямую, без конфликтов). */
    private static boolean isDown(long window, KeyMapping km) {
        InputConstants.Key k = KeyBindingHelper.getBoundKeyOf(km);
        if (k.getType() != InputConstants.Type.KEYSYM || k.getValue() == InputConstants.UNKNOWN.getValue()) {
            return false;
        }
        return InputConstants.isKeyDown(window, k.getValue());
    }

    private static void startStraight(Minecraft mc) {
        if (mc.player != null && mc.player.getVehicle() instanceof Boat b) {
            fixedYaw = b.getYRot();
            targetX = targetZ = null;
            straightMode = true;
            resetAvoid();
            active = true;
            mc.player.displayClientMessage(Component.literal("AutoBoat: еду прямо"), true);
        } else if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("AutoBoat: сначала сядь в лодку"), true);
        }
    }

    private static void resetAvoid() {
        lastOffset = 0f;
        holdTicks = 0;
    }

    private static void stop(Minecraft mc) {
        active = false;
        straightMode = false;
        wasPaused = false;
        tracking = false;
        targetX = targetZ = null;
        fixedYaw = null;
        resetAvoid();
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
        if (mc.player == null || mc.level == null) {
            tracking = false;
            return;
        }

        // Читаем клавиши напрямую; в меню/чате не реагируем
        long win = mc.getWindow().getWindow();
        boolean inGame = mc.screen == null;
        boolean straightDown = inGame && isDown(win, straightKey);
        boolean stopDown = inGame && isDown(win, stopKey);
        boolean pauseDown = inGame && isDown(win, pauseKey);

        // R — по нажатию (фронт)
        if (straightDown && !prevStraight) {
            if (active && straightMode) {
                stop(mc);
                mc.player.displayClientMessage(Component.literal("AutoBoat: выключен"), true);
            } else {
                startStraight(mc);
            }
        }
        // C — по нажатию (фронт)
        if (stopDown && !prevStop) {
            if (active) {
                stop(mc);
                mc.player.displayClientMessage(Component.literal("AutoBoat: выключен"), true);
            }
        }
        prevStraight = straightDown;
        prevStop = stopDown;

        if (!active) {
            tracking = false;
            return;
        }

        if (!(mc.player.getVehicle() instanceof Boat boat)) {
            releaseKeys(mc);
            tracking = false;
            wasPaused = false;
            return; // не в лодке — ждём
        }

        // Текущий угол и скорость вращения лодки (градусов за тик)
        float yaw = boat.getYRot();
        if (!tracking) { lastYaw = yaw; tracking = true; }
        float rate = Mth.wrapDegrees(yaw - lastYaw);
        lastYaw = yaw;

        // Пауза: пока зажата клавиша — рулишь сам
        if (pauseDown) {
            releaseKeys(mc);
            wasPaused = true;
            resetAvoid();
            return;
        }
        if (wasPaused) {
            wasPaused = false;
            if (straightMode) {
                // Новый курс = куда лодка смотрит (с учётом инерции поворота)
                fixedYaw = Mth.wrapDegrees(yaw + rate * INERTIA);
                mc.player.displayClientMessage(Component.literal("AutoBoat: курс обновлён"), true);
            }
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

        // Выбор курса
        Float chosen = null;
        if (!avoid) {
            chosen = desired;
        } else {
            Float off = null;
            // Держим ранее выбранный обход, пока он свободен (чтобы не метаться)
            if (holdTicks > 0 && isClear(mc.level, boat, desired + lastOffset)) {
                off = lastOffset;
                holdTicks--;
            } else {
                // Ближайший к желаемому курсу свободный угол: 0, ±15, ±30 ... ±135
                for (int a = 0; a <= 135; a += 15) {
                    if (isClear(mc.level, boat, desired + a)) { off = (float) a; break; }
                    if (a != 0 && isClear(mc.level, boat, desired - a)) { off = (float) -a; break; }
                }
                if (off != null) {
                    lastOffset = off;
                    holdTicks = (off == 0f) ? 0 : HOLD_AVOID_TICKS;
                }
            }
            if (off != null) chosen = desired + off;
        }

        Options o = mc.options;
        keysHeld = true;
        o.keyDown.setDown(false);

        if (chosen == null) {
            // Всё перекрыто — крутимся на месте без газа
            o.keyUp.setDown(false);
            o.keyLeft.setDown(false);
            o.keyRight.setDown(true);
            return;
        }

        float diff = Mth.wrapDegrees(chosen - yaw);
        // Куда лодка докрутится по инерции, если сейчас отпустить руль
        float predicted = diff - rate * INERTIA;
        o.keyRight.setDown(predicted > DEADZONE);
        o.keyLeft.setDown(predicted < -DEADZONE);
        // Не сбрасываем газ на дуге, только на очень больших углах
        o.keyUp.setDown(Math.abs(diff) < GAS_CUTOFF);
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
