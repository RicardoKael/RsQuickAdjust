package org.Ricardo.rs_quick_adjust;

import com.google.gson.Gson;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class RsQuickAdjust implements ModInitializer {

    private static final long DOUBLE_CLICK_MS = 500;
    private final Map<UUID, ClickEntry> clicks = new HashMap<>();
    private final Set<Block> redstoneBlocks = new HashSet<>();

    private static final Identifier ADVANCEMENT_ID = Identifier.of("rs_quick_adjust", "redstone_hand");

    private record ClickEntry(net.minecraft.util.math.BlockPos pos, long time) {}
    private record BlocksData(String description, List<String> blocks) {}

    private static final String BLOCKS_JSON = "/data/rs_quick_adjust/rs_quick_adjust_config/blocks.json";

    @Override
    public void onInitialize() {
        loadBlockList();
        registerDoubleClickHandler();
        System.out.println("[红石之手] 已加载 " + redstoneBlocks.size() + " 种红石方块");
    }

    private void loadBlockList() {
        try (var reader = new InputStreamReader(
                getClass().getResourceAsStream(BLOCKS_JSON),
                StandardCharsets.UTF_8
        )) {
            BlocksData data = new Gson().fromJson(reader, BlocksData.class);
            if (data == null || data.blocks() == null) return;

            for (String id : data.blocks()) {
                Block block = Registries.BLOCK.get(Identifier.of(id));
                if (block != Blocks.AIR) {
                    redstoneBlocks.add(block);
                } else {
                    System.out.println("[红石之手] 未知方块: " + id);
                }
            }
        } catch (Exception e) {
            System.out.println("[红石之手] 加载 blocks.json 失败: " + e.getMessage());
        }
    }

    private void registerDoubleClickHandler() {
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClient) return ActionResult.PASS;
            if (player.isCreative()) return ActionResult.PASS;
            if (!redstoneBlocks.contains(world.getBlockState(pos).getBlock())) return ActionResult.PASS;

            long now = System.currentTimeMillis();
            UUID uid = player.getUuid();
            ClickEntry prev = clicks.get(uid);

            if (prev != null && prev.pos().equals(pos) && (now - prev.time()) < DOUBLE_CLICK_MS) {
                return handleDoubleClick((ServerPlayerEntity) player, (ServerWorld) world, pos);
            }

            clicks.put(uid, new ClickEntry(pos, now));
            return ActionResult.PASS;
        });
    }

    private ActionResult handleDoubleClick(ServerPlayerEntity player, ServerWorld world, net.minecraft.util.math.BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        List<ItemStack> drops = Block.getDroppedStacks(state, world, pos, null, player, ItemStack.EMPTY);

        // null 表示不排除任何玩家——所有附近的玩家都能听到该音效
        ServerPlayerEntity noExcludedPlayer = null;

        if (!canFitAll(player, drops)) {
            player.sendMessage(Text.literal("§c背包空间不足，无法破坏！"), true);
            player.getWorld().playSound(noExcludedPlayer, pos, SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.PLAYERS, 1.0f, 1.0f);
            return ActionResult.FAIL;
        }

        for (ItemStack drop : drops) {
            if (!drop.isEmpty()) {
                player.getInventory().offerOrDrop(drop);
            }
        }

        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
        clicks.remove(player.getUuid());

        // 音效：末影珍珠传送
        player.getWorld().playSound(noExcludedPlayer, pos, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 1.0f);

        // 授予成就
        grantAdvancement(player);

        return ActionResult.SUCCESS;
    }

    private boolean canFitAll(ServerPlayerEntity player, List<ItemStack> drops) {
        var main = player.getInventory().main;
        // 统计可用空槽位数量（一次性计算，避免多个掉落物重复使用同一空槽）
        int emptySlots = 0;
        for (ItemStack slot : main) {
            if (slot.isEmpty()) emptySlots++;
        }

        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            int remaining = drop.getCount();
            for (ItemStack existing : main) {
                if (remaining <= 0) break;
                if (!existing.isEmpty() && existing.isOf(drop.getItem())
                        && existing.getCount() < existing.getMaxCount()) {
                    int space = existing.getMaxCount() - existing.getCount();
                    remaining -= Math.min(space, remaining);
                }
            }
            if (remaining > 0) {
                emptySlots--;
                if (emptySlots < 0) return false;
            }
        }
        return true;
    }

    private void grantAdvancement(ServerPlayerEntity player) {
        var adv = player.getServer().getAdvancementLoader().get(ADVANCEMENT_ID);
        if (adv != null) {
            var progress = player.getAdvancementTracker().getProgress(adv);
            if (!progress.isDone()) {
                player.getAdvancementTracker().grantCriterion(adv, "double_click");
            }
        }
    }
}
