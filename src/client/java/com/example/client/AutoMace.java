package net.fabricmc.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

public class AutoMace {
    public enum Stage { IDLE, SLAM }

    public static boolean enabled = false;
    private static Stage stage = Stage.IDLE;
    private static long lastAttackTime = 0;
    private static int originalSlot = -1;
    private static boolean isSwapped = false;
    private static int comboDelayTimer = 0;
    private static LivingEntity activeTarget = null;

    private static float smoothYaw = 0.0f;
    private static float smoothPitch = 0.0f;

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null) {
            if (isSwapped) resetState(client);
            return;
        }

        if (comboDelayTimer > 0) {
            comboDelayTimer--;
            return;
        }

        activeTarget = client.level.getEntitiesOfClass(
            LivingEntity.class, 
            client.player.getBoundingBox().inflate(6.0, 350.0, 6.0),
            e -> e != client.player && e.isAlive() && !e.isDeadOrDying() && client.player.getY() > e.getY()
        ).stream().min(java.util.Comparator.comparingDouble(e -> client.player.distanceToSqr(e))).orElse(null);

        if (activeTarget == null) {
            resetState(client);
            return;
        }

        boolean isFalling = client.player.fallDistance >= 3.0f && !client.player.onGround() && !client.player.isInWater();

        if (isFalling) {
            applyProgressiveGcdAim(client, activeTarget);

            if (client.player.distanceTo(activeTarget) <= 2.95) {
                if (System.currentTimeMillis() - lastAttackTime < 35) return;

                boolean isShielding = activeTarget instanceof Player p && p.isUsingItem() && p.getUseItem().getItem() instanceof ShieldItem;

                if (isShielding && stage == Stage.IDLE) {
                    int axeSlot = findAxeSlot(client);
                    if (axeSlot != -1) {
                        if (originalSlot == -1) originalSlot = client.player.getInventory().getSelectedSlot();
                        client.player.getInventory().setSelectedSlot(axeSlot);
                        client.gameMode.attack(client.player, activeTarget);
                        client.player.swing(InteractionHand.MAIN_HAND);
                        isSwapped = true;
                        stage = Stage.SLAM;
                        comboDelayTimer = 2;
                        return;
                    }
                }

                if (stage == Stage.SLAM || stage == Stage.IDLE) {
                    boolean preferDensity = client.player.fallDistance > 7.0;
                    int maceSlot = findBestMaceSlot(client, preferDensity);
                    if (maceSlot != -1) {
                        if (originalSlot == -1) originalSlot = client.player.getInventory().getSelectedSlot();
                        client.player.getInventory().setSelectedSlot(maceSlot);
                        isSwapped = true;
                    }

                    client.gameMode.attack(client.player, activeTarget);
                    client.player.swing(InteractionHand.MAIN_HAND);
                    lastAttackTime = System.currentTimeMillis();

                    resetState(client);
                }
            }
        } else if (client.player.onGround()) {
            resetState(client);
        }
    }

    private static void applyProgressiveGcdAim(Minecraft client, LivingEntity target) {
        Vec3 eyePos = client.player.getEyePosition();
        
        double jitterX = (Math.random() - 0.5) * 0.04;
        double jitterY = (Math.random() - 0.5) * 0.03;
        double jitterZ = (Math.random() - 0.5) * 0.04;

        Vec3 targetPoint = new Vec3(
            target.getX() + jitterX,
            target.getY() + (target.getBbHeight() * 0.55) + jitterY,
            target.getZ() + jitterZ
        );

        double dx = targetPoint.x - eyePos.x;
        double dy = targetPoint.y - eyePos.y;
        double dz = targetPoint.z - eyePos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));

        if (smoothYaw == 0.0f && smoothPitch == 0.0f) {
            smoothYaw = client.player.getYRot();
            smoothPitch = client.player.getXRot();
        }

        float yawDiff = wrapAngle(targetYaw - smoothYaw);
        float pitchDiff = targetPitch - smoothPitch;

        float progress = Math.min(1.0f, (float)(1.25 / dist));
        float factor = 0.5f + (progress * 0.45f);

        float stepYaw = yawDiff * factor;
        float stepPitch = pitchDiff * factor;

        float maxStep = 120.0f;
        stepYaw = Math.max(-maxStep, Math.min(maxStep, stepYaw));
        stepPitch = Math.max(-maxStep, Math.min(maxStep, stepPitch));

        float finalYaw = smoothYaw + stepYaw;
        float finalPitch = Math.max(-90.0f, Math.min(90.0f, smoothPitch + stepPitch));

        double sensValue = client.options.sensitivity().get();
        double sens = sensValue * 0.6 + 0.2;
        double gcd = sens * sens * sens * 1.2;

        float deltaYaw = finalYaw - client.player.getYRot();
        float deltaPitch = finalPitch - client.player.getXRot();

        deltaYaw = (float) (Math.round(deltaYaw / gcd) * gcd);
        deltaPitch = (float) (Math.round(deltaPitch / gcd) * gcd);

        smoothYaw = client.player.getYRot() + deltaYaw;
        smoothPitch = client.player.getXRot() + deltaPitch;

        client.player.setYRot(smoothYaw);
        client.player.setXRot(smoothPitch);
        client.player.yRotO = smoothYaw;
        client.player.xRotO = smoothPitch;
        client.player.yHeadRot = smoothYaw;
        client.player.yHeadRotO = smoothYaw;
    }

    private static float wrapAngle(float angle) {
        float wrapped = angle % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }

    private static int findAxeSlot(Minecraft client) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.getItem() instanceof AxeItem) return i;
        }
        return -1;
    }

    private static int findBestMaceSlot(Minecraft client, boolean preferDensity) {
        int bestSlot = -1;
        int maxLevel = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.isEmpty() || !stack.is(Items.MACE)) continue;

            var registry = client.level.registryAccess();
            var enchant = preferDensity ? registry.get(Enchantments.DENSITY) : registry.get(Enchantments.BREACH);

            if (enchant.isPresent()) {
                int level = EnchantmentHelper.getItemEnchantmentLevel(enchant.get(), stack);
                if (level > maxLevel) {
                    maxLevel = level;
                    bestSlot = i;
                }
            } else if (bestSlot == -1) {
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private static void resetState(Minecraft client) {
        if (isSwapped && originalSlot != -1 && client.player != null) {
            client.player.getInventory().setSelectedSlot(originalSlot);
        }
        originalSlot = -1;
        isSwapped = false;
        stage = Stage.IDLE;
        smoothYaw = 0.0f;
        smoothPitch = 0.0f;
    }
						  }
