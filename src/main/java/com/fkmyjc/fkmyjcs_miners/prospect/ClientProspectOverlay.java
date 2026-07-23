package com.fkmyjc.fkmyjcs_miners.prospect;

import com.fkmyjc.fkmyjcs_miners.Config;
import com.fkmyjc.fkmyjcs_miners.vein.VeinOreRegistry;
import com.fkmyjc.fkmyjcs_miners.Fkmyjcs_miners;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import com.mojang.blaze3d.platform.InputConstants;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 客户端右侧叠加层：在屏幕右上角持续绘制本区块矿脉信息（即使尚未探测，也显示“当前区块矿脉未知”）。
 * <p>
 * 显示状态随“是否持有探矿物品”联动：
 * <ul>
 *   <li>当玩家<b>手持</b>带 {@code #fkmyjcs_miners:vein_display} 标签的探矿物品时，叠加层自动显示；</li>
 *   <li>当玩家把探矿物品<b>移开</b>（切到其他物品或空手）时，叠加层自动关闭；</li>
 *   <li>快捷键 {@code \`（{@code key.fkmyjcs_miners.toggle_vein_display}）与指令 {@code /vein display}
 *       仍可在<b>未持有</b>探矿物品时手动切换显示/隐藏（持物品时由上述联动强制显示）。</li>
 * </ul>
 * <p>
 * 每个存档<b>第一次</b>显示叠加层时，会向玩家发送一条提示，告知如何关闭（按快捷键，或放下探矿物品）。
 * 提示状态按存档落盘（{@code <game>/fkmyjcs_miners_overlay_hint/<level>/fkmyjcs_miners_overlay_hint.json}），仅提示一次。
 * <p>
 * 叠加层仅在没有打开其它 GUI（如背包、{@code /vein} 面板等）时绘制；一旦打开任意界面即让位隐藏。
 * <p>
 * 位置是<b>动态调整</b>的，而非写死：玩家可在游戏中用按键（{@code [} / {@code ]}）实时上下移动面板，
 * 偏移量会持久化到 {@code config/fkmyjcs_miners-overlay.json}，重启后保留；
 * 配置项 {@code overlay.yOffset} 仅作为首次启动的默认值。
 */
public class ClientProspectOverlay {

    /** 探矿物品标签，持有其一即视为“正在使用探矿工具”。 */
    private static final ResourceLocation VEIN_DISPLAY_RL =
            new ResourceLocation(Fkmyjcs_miners.MODID + ":vein_display");

    /** 切换显示/隐藏。 */
    public static final KeyMapping KEY_TOGGLE = new KeyMapping(
            "key.fkmyjcs_miners.toggle_vein_display",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_BACKSLASH,
            "key.categories.fkmyjcs_miners"
    );

    /** 上移 / 下移面板（运行时动态微调位置，避免被其他 HUD 遮挡）。 */
    public static final KeyMapping KEY_MOVE_UP = new KeyMapping(
            "key.fkmyjcs_miners.move_overlay_up",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_BRACKET,
            "key.categories.fkmyjcs_miners"
    );
    public static final KeyMapping KEY_MOVE_DOWN = new KeyMapping(
            "key.fkmyjcs_miners.move_overlay_down",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_RIGHT_BRACKET,
            "key.categories.fkmyjcs_miners"
    );

    /** 玩家主动切换的显示开关，默认开启。 */
    private static boolean enabled = true;

    /** 当前“已显示”是否由“持有探矿物品”自动触发；仅此标志为真时，移开物品才会自动关闭。 */
    private static boolean autoShown = false;

    /** 由 {@code /vein display} 指令经网络包触发：翻转叠加层显示状态。 */
    public static void toggle() {
        enabled = !enabled;
        if (!enabled) autoShown = false; // 主动关闭后不再受“移开物品”自动关闭影响
    }

    /** 叠加层相对屏幕顶部的垂直偏移（像素）。运行时可调并持久化。 */
    private static int dynamicYOffset = Config.prospectOverlayYOffset;
    private static final int STEP = 6;
    private static final int MAX_OFFSET = 1000;
    private static final Path OFFSET_FILE =
            FMLPaths.CONFIGDIR.get().resolve("fkmyjcs_miners-overlay.json");
    private static final Gson GSON = new Gson();

    /** 当前存档目录（进入世界时由 EntityJoinLevelEvent 设定），用于存放“已提示”标记。 */
    private static Path levelDir = null;
    /** 本会话（进入当前存档后）是否已发送过首次提示，避免每帧重复。 */
    private static boolean hintSentForCurrentLevel = false;

    static {
        loadOffset();
        // 注册等级加入事件，进入/切换存档时记录目录并重置提示标志
        MinecraftForge.EVENT_BUS.register(new Object() {
            @SubscribeEvent
            public void onJoin(EntityJoinLevelEvent e) {
                Entity ent = e.getEntity();
                if (!(ent instanceof Player)) return;
                if (!e.getLevel().isClientSide()) return;
                Minecraft mc = Minecraft.getInstance();
                String rawName;
                if (mc.getCurrentServer() != null) {
                    rawName = "mp_" + mc.getCurrentServer().ip;
                } else if (mc.getSingleplayerServer() != null) {
                    rawName = "sp_" + mc.getSingleplayerServer().getServerDirectory().getName();
                } else {
                    rawName = "default";
                }
                String safe = rawName.replaceAll("[^a-zA-Z0-9._-]", "_");
                levelDir = FMLPaths.GAMEDIR.get().resolve("fkmyjcs_miners_overlay_hint").resolve(safe);
                hintSentForCurrentLevel = false;
            }
        });
    }

    private static void loadOffset() {
        try {
            if (Files.exists(OFFSET_FILE)) {
                JsonObject obj = GSON.fromJson(
                        new String(Files.readAllBytes(OFFSET_FILE), StandardCharsets.UTF_8),
                        JsonObject.class);
                if (obj != null && obj.has("yOffset")) {
                    dynamicYOffset = obj.get("yOffset").getAsInt();
                }
            }
        } catch (Exception ignored) {
            // 读取失败时退回到配置默认值，不影响游戏
        }
    }

    private static void saveOffset() {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("yOffset", dynamicYOffset);
            Files.write(OFFSET_FILE, GSON.toJson(obj).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // 保存失败不影响当前渲染
        }
    }

    /** 主手或副手是否持有带 vein_display 标签的物品。 */
    private static boolean holdsDisplayItem(Player player) {
        for (ItemStack stack : new ItemStack[]{player.getMainHandItem(), player.getOffhandItem()}) {
            if (stack.isEmpty()) continue;
            if (stack.getTags().anyMatch(t -> t.location().equals(VEIN_DISPLAY_RL))) return true;
        }
        return false;
    }

    /** 当前存档是否已发送过首次提示（落盘标记）。 */
    private static boolean hintShownForLevel() {
        if (levelDir == null) return true; // 尚未进入存档，不提示
        try {
            return Files.exists(levelDir.resolve("fkmyjcs_miners_overlay_hint.json"));
        } catch (Exception ignored) {
            return true;
        }
    }

    private static void markHintShownForLevel() {
        if (levelDir == null) return;
        try {
            Files.write(levelDir.resolve("fkmyjcs_miners_overlay_hint.json"),
                    GSON.toJson(new JsonObject()).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // 标记失败不影响游戏，下次仍会提示
        }
    }

    /** 线程安全地给玩家发系统消息（兼容渲染线程）。 */
    private static void sendHint(Player player) {
        String keyName = KEY_TOGGLE.getKey().getDisplayName().getString();
        Component msg = Component.translatable("fkmyjcs_miners.prospect.overlay_hint",
                        keyName, keyName)
                .withStyle(ChatFormatting.YELLOW);
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            player.sendSystemMessage(msg);
        } else {
            mc.tell(() -> player.sendSystemMessage(msg));
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (KEY_TOGGLE.consumeClick()) {
            enabled = !enabled;
            if (!enabled) autoShown = false;
        }
        // 位置调整由快捷键 + 开关状态控制，不依赖是否持有物品
        int before = dynamicYOffset;
        if (KEY_MOVE_UP.consumeClick()) {
            dynamicYOffset = Math.max(0, dynamicYOffset - STEP);
        }
        if (KEY_MOVE_DOWN.consumeClick()) {
            dynamicYOffset = Math.min(MAX_OFFSET, dynamicYOffset + STEP);
        }
        if (dynamicYOffset != before) {
            saveOffset();
        }
    }

    @SubscribeEvent
    public void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        // 叠加层只在「没有打开其它 GUI」时显示（如背包、/vein 面板等打开时让位）
        if (mc.screen != null) return;

        // 显示状态跟随“是否持有探矿物品”：
        // 持物品且未显示 → 自动显示（标记 autoShown）；
        // 未持物品且此前是自动显示 → 自动关闭（移开即关）。
        boolean holding = holdsDisplayItem(player);
        if (holding) {
            if (!enabled) { enabled = true; autoShown = true; }
        } else if (autoShown) {
            enabled = false;
            autoShown = false;
        }
        if (!enabled) return;

        ChunkPos chunk = player.chunkPosition();
        VeinSummary s = ClientProspectMirror.get(chunk);

        // 每个存档首次显示时，发送一次关闭提示
        if (!hintSentForCurrentLevel && !hintShownForLevel()) {
            hintSentForCurrentLevel = true;
            markHintShownForLevel();
            sendHint(player);
        }

        GuiGraphics g = event.getGuiGraphics();
        int screenW = mc.getWindow().getGuiScaledWidth();

        int pad = 6;
        int lineH = 12;
        int titleH = 14;
        int contentRows;
        if (s == null) {
            contentRows = 1;                 // “当前区块矿脉未知”
        } else {
            contentRows = 0;
            if (s.mainName != null) contentRows++;   // 主矿（纯岩石矿脉无主矿）
            if (s.secName != null) contentRows++;    // 次矿
            contentRows++;                            // 岩石（恒有）
            contentRows++;                            // 储量
        }
        int rows = 1 + contentRows;
        int panelW = 148;
        int panelH = pad * 2 + titleH + (rows - 1) * lineH;

        int x = screenW - panelW - 8;  // 右侧对齐
        int y = 8 + dynamicYOffset;    // 顶部 + 运行时可调整偏移
        float scale = (float) Config.prospectOverlayScale;

        // 以面板左上角为原点进行整体缩放
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1.0f);

        // 半透明背景面板（局部坐标：面板左上角 = 0,0）
        g.fill(-2, -2, panelW + 2, panelH + 2, 0x99000000);
        g.fill(0, 0, panelW, panelH, 0xCC0E0E0E);

        int tx = pad;
        int ty = pad;
        g.drawString(mc.font,
                Component.translatable("fkmyjcs_miners.prospect.title").withStyle(ChatFormatting.GOLD),
                tx, ty, 0xFFFFFF);
        ty += titleH;

        if (s == null) {
            g.drawString(mc.font,
                    Component.translatable("fkmyjcs_miners.prospect.unknown")
                            .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC),
                    tx, ty, 0xE0E0E0);
        } else {
            if (s.mainName != null) ty = drawRow(g, mc, tx, ty, s.mainId, s.mainName, s.mainW);
            if (s.secName != null) ty = drawRow(g, mc, tx, ty, s.secId, s.secName, s.secW);
            ty = drawRow(g, mc, tx, ty, s.rockId, s.rockName, s.rockW);
            // 储量（确定性随机，随区块固定）
            g.drawString(mc.font,
                    Component.translatable("fkmyjcs_miners.prospect.reserves", String.format("%,d", s.reserves))
                            .withStyle(ChatFormatting.AQUA),
                    tx, ty, 0xFFFFFF);
        }

        g.pose().popPose();
    }

    private int drawRow(GuiGraphics g, Minecraft mc, int x, int y, String id, String nameKey, int weight) {
        int color = (id != null) ? VeinOreRegistry.getOreColor(ResourceLocation.tryParse(id)) : 0xE0E0E0;
        g.drawString(mc.font, Component.translatable(nameKey), x, y, color);
        g.drawString(mc.font,
                Component.literal(weight + "%").withStyle(ChatFormatting.YELLOW),
                x + 100, y, 0xFFFFFF);
        return y + 12;
    }
}
