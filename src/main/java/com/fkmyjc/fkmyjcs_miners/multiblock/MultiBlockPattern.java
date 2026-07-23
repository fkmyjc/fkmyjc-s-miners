package com.fkmyjc.fkmyjcs_miners.multiblock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多方块结构定义 + 校验。
 *
 * <p>结构以「层(layers, 由下到上) → 行(row, z) → 字符(col, x)」的三维字符画表示，
 * 每个非空格字符在 mapping 中映射到<b>一个或多个</b>方块（候选组）：</p>
 * <ul>
 *   <li>单值写法（向后兼容）：{@code "X": "#forge:storage_blocks/iron"}</li>
 *   <li>候选组写法：{@code "B": ["fkmyjcs_miners:input_bus", "fkmyjcs_miners:output_bus", "#forge:chests"]}</li>
 *   <li>带 NBT 的方块（SNBT 字符串）：{@code "D": {"id":"minecraft:chest","nbt":"{Lock:\"secret\"}","writeNbt":true}}</li>
 * </ul>
 *
 * <p>候选组中<b>任意一个</b>匹配世界方块即通过（即「可放其中任一方块」）。
 * 若某候选带 {@code nbt}，校验时会额外比对世界方块实体的 NBT（子集匹配）。
 * {@code writeNbt} 为 true 时，自动建造会把该 NBT 写入方块实体；为 false（默认）则不写入，仅用于校验匹配。</p>
 *
 * <p>校验时以 controller 字符所在格为「世界锚点」，按方块朝向(forward)旋转其余格子。
 * 旋转约定：pattern 的局部坐标系为 右(+x) / 上(+y) / 前(+z)，其中「前」等于方块朝向。</p>
 *
 * <p>方向绑定：{@code bindDirection}（默认 true）控制校验是否只认控制器当前朝向。
 * 为 false 时，{@link #matchesAnyDirection} 会遍历 NORTH/EAST/SOUTH/WEST 四个水平朝向，
 * 只要有一个朝向成形即认为结构成立。</p>
 */
public class MultiBlockPattern {

    public static final char SKIP = ' ';                 // 空格 = 不校验
    public static final char CONTROLLER_DEFAULT = 'C';

    /** 一个候选方块：方块 id（或 "#tag"）+ 可选 NBT（SNBT 解析）+ 是否在建时写入 NBT。 */
    public record BlockSpec(String id, @Nullable CompoundTag nbt, boolean writeNbt) {
    }

    private final String controllerChar;
    private final List<List<String>> layers;             // [layer][row] = 该行的字符列
    private final Map<Character, List<BlockSpec>> mapping; // 字符 -> 候选列表（≥1 项）
    private final int cLayer, cRow, cCol;                // controller 在 pattern 中的坐标
    private final String id;                             // 结构稳定标识（JSON 的 id 字段，缺省为空串）
    private final String name;                           // 结构显示名（JSON 的 name 字段，供 JEI 等展示）
    private final boolean bindDirection;                // 是否绑定控制器朝向（默认 true）

    private MultiBlockPattern(String controllerChar, List<List<String>> layers,
                              Map<Character, List<BlockSpec>> mapping, int cLayer, int cRow, int cCol,
                              String id, String name, boolean bindDirection) {
        this.controllerChar = controllerChar;
        this.layers = layers;
        this.mapping = mapping;
        this.cLayer = cLayer;
        this.cRow = cRow;
        this.cCol = cCol;
        this.id = id;
        this.name = name;
        this.bindDirection = bindDirection;
    }

    public static MultiBlockPattern fromJson(JsonObject json) {
        String controllerChar = json.has("controller")
                ? json.get("controller").getAsString()
                : String.valueOf(CONTROLLER_DEFAULT);
        if (controllerChar.length() != 1)
            throw new IllegalArgumentException("controller 必须是单个字符");

        String id = json.has("id") ? json.get("id").getAsString() : "";
        String name = json.has("name") ? json.get("name").getAsString() : "";
        // 方向绑定：默认 true（只认控制器当前朝向）；显式 false 时才允许四方向任意成形
        boolean bindDirection = json.has("bindDirection") ? json.get("bindDirection").getAsBoolean() : true;

        JsonArray layersArr = json.getAsJsonArray("layers");
        List<List<String>> layers = new ArrayList<>();
        int cLayer = -1, cRow = -1, cCol = -1;
        for (int y = 0; y < layersArr.size(); y++) {
            JsonArray rows = layersArr.get(y).getAsJsonArray();
            List<String> rowList = new ArrayList<>();
            for (int z = 0; z < rows.size(); z++) {
                String row = rows.get(z).getAsString();
                rowList.add(row);
                for (int x = 0; x < row.length(); x++) {
                    if (row.charAt(x) == controllerChar.charAt(0)) {
                        cLayer = y;
                        cRow = z;
                        cCol = x;
                    }
                }
            }
            layers.add(rowList);
        }
        if (cLayer < 0)
            throw new IllegalArgumentException("pattern 中找不到 controller 字符 '" + controllerChar + "'");

        Map<Character, List<BlockSpec>> mapping = new HashMap<>();
        JsonObject mapObj = json.getAsJsonObject("mapping");
        for (Map.Entry<String, JsonElement> e : mapObj.entrySet()) {
            if (e.getKey().length() != 1) continue;
            mapping.put(e.getKey().charAt(0), parseExpected(e.getValue()));
        }

        return new MultiBlockPattern(controllerChar, layers, mapping, cLayer, cRow, cCol, id, name, bindDirection);
    }

    /**
     * mapping 的单值 / 数组 / 对象都解析为候选列表。
     * 单值字符串 = 长度为 1 的候选组（无 NBT）；对象 = 带 NBT 的候选；数组 = 候选组。
     */
    private static List<BlockSpec> parseExpected(JsonElement el) {
        List<BlockSpec> list = new ArrayList<>();
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) list.add(parseSpec(e));
        } else {
            list.add(parseSpec(el));
        }
        return list;
    }

    /** 解析单个候选：字符串 → 无 NBT；对象 → id + 可选 nbt(SNBT) + 可选 writeNbt。 */
    private static BlockSpec parseSpec(JsonElement el) {
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            String specId = obj.has("id") ? obj.get("id").getAsString()
                                          : obj.get("block").getAsString();
            CompoundTag nbt = null;
            if (obj.has("nbt")) {
                JsonElement nbtEl = obj.get("nbt");
                if (!nbtEl.isJsonPrimitive() || !nbtEl.getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("结构 JSON 中 " + specId + " 的 nbt 必须是 SNBT 字符串");
                try {
                    nbt = TagParser.parseTag(nbtEl.getAsString());
                } catch (CommandSyntaxException e) {
                    throw new IllegalArgumentException("结构 JSON 中 " + specId + " 的 nbt 不是合法 SNBT: " + e.getMessage(), e);
                }
            }
            boolean writeNbt = obj.has("writeNbt") && obj.get("writeNbt").getAsBoolean();
            return new BlockSpec(specId, nbt, writeNbt);
        }
        return new BlockSpec(el.getAsString(), null, false);
    }

    /**
     * 校验以 controllerPos 为 controller 格、按 facing 旋转的多方块结构是否完整。
     * 仅支持水平朝向（矿机 FACING 只有水平方向）。
     * 任一格的候选组只要有一个匹配世界方块即通过（「可放其中任一方块」）；
     * 候选带 nbt 时另需世界方块实体 NBT 满足子集匹配。
     */
    public boolean matches(Level level, BlockPos controllerPos, Direction facing) {
        int fx = facing.getNormal().getX();   // forward.x
        int fz = facing.getNormal().getZ();   // forward.z
        int rx = -fz;                          // right = (-forward.z, forward.x)
        int rz = fx;

        for (int y = 0; y < layers.size(); y++) {
            List<String> rows = layers.get(y);
            for (int z = 0; z < rows.size(); z++) {
                String row = rows.get(z);
                for (int x = 0; x < row.length(); x++) {
                    char c = row.charAt(x);
                    if (c == SKIP) continue;
                    List<BlockSpec> cand = mapping.get(c);
                    if (cand == null || cand.isEmpty()) continue;   // 未映射字符视为不校验

                    int lx = x - cCol;
                    int ly = y - cLayer;
                    int lz = z - cRow;

                    int wx = rx * lx + fx * lz;
                    int wz = rz * lx + fz * lz;
                    BlockPos worldPos = controllerPos.offset(wx, ly, wz);

                    BlockState state = level.getBlockState(worldPos);
                    if (!blockMatches(level, worldPos, state.getBlock(), cand)) return false;
                }
            }
        }
        return true;
    }

    /**
     * 遍历四个水平朝向校验，只要有一个朝向成形即返回 true。
     * 用于 bindDirection=false 的结构（不绑定控制器朝向）。
     */
    public boolean matchesAnyDirection(Level level, BlockPos controllerPos) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (matches(level, controllerPos, dir)) return true;
        }
        return false;
    }

    /** 候选组中任意一个匹配即通过（含可选的 NBT 比对）。 */
    private boolean blockMatches(Level level, BlockPos worldPos, Block block, List<BlockSpec> candidates) {
        for (BlockSpec spec : candidates) {
            if (!matchesBlock(block, spec.id)) continue;
            if (spec.nbt != null) {
                BlockEntity be = level.getBlockEntity(worldPos);
                if (be == null) continue;
                if (!nbtMatches(spec.nbt, be.saveWithFullMetadata())) continue;
            }
            return true;
        }
        return false;
    }

    /** 单条候选（id 或 "#tag"）是否匹配该方块。 */
    private boolean matchesBlock(Block block, String expected) {
        if (expected.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(expected.substring(1));
            if (tagId == null) return false;
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, tagId);
            return BuiltInRegistries.BLOCK.getTag(tag)
                    .map(holders -> holders.stream().anyMatch(h -> h.value() == block))
                    .orElse(false);
        }
        ResourceLocation id = ResourceLocation.tryParse(expected);
        if (id == null) return false;
        ResourceLocation actual = ForgeRegistries.BLOCKS.getKey(block);
        return id.equals(actual);
    }

    /** expected 是 actual 的 NBT 子集（actual 必须包含 expected 的每个键且值相等）。 */
    private static boolean nbtMatches(CompoundTag expected, CompoundTag actual) {
        for (String key : expected.getAllKeys()) {
            Tag ev = expected.get(key);
            Tag av = actual.get(key);
            if (av == null) return false;
            if (!ev.equals(av)) return false;
        }
        return true;
    }

    /** 返回该 pattern 的 controller 方块（取候选组里第一个非 tag 的有效方块），用于按方块反查 pattern。 */
    @Nullable
    public Block getControllerBlock() {
        List<BlockSpec> specs = mapping.get(controllerChar.charAt(0));
        if (specs == null) return null;
        for (BlockSpec spec : specs) {
            if (spec.id.startsWith("#")) continue;
            ResourceLocation rl = ResourceLocation.tryParse(spec.id);
            Block b = rl == null ? null : ForgeRegistries.BLOCKS.getValue(rl);
            if (b != null && b != Blocks.AIR) return b;
        }
        return null;
    }

    /** 一个待渲染的方块：相对 controller 的局部坐标 + 方块状态（代表方块 = 候选组第一个）。 */
    public record RenderBlock(BlockPos pos, BlockState state) {
    }

    /**
     * 一个材料组：结构里某字符(候选组)出现的总次数 + 该字符<b>全部</b>候选方块对应的物品(均已带上数量)。
     * 供预览插件的「总材料列表」使用——把 alternatives 整体放进<b>一个</b>原生槽位，
     * 由 JEI/REI/EMI 在原生槽位内按 ~1s 周期轮播显示，实现「每 1 秒刷新一次」候选项。
     */
    public record MaterialGroup(char ch, int count, List<ItemStack> alternatives) {
    }

    /**
     * 遍历整个结构，返回所有需要渲染的方块（相对 controller 的局部坐标），每个格子取候选组第一个作为代表。
     * tag 映射（'#' 前缀）取该 tag 下第一个方块作为代表。NBT 不影响基础图标，渲染取默认状态。
     */
    public List<RenderBlock> getRenderBlocks() {
        List<RenderBlock> result = new ArrayList<>();
        for (int y = 0; y < layers.size(); y++) {
            List<String> rows = layers.get(y);
            for (int z = 0; z < rows.size(); z++) {
                String row = rows.get(z);
                for (int x = 0; x < row.length(); x++) {
                    char c = row.charAt(x);
                    if (c == SKIP) continue;
                    List<BlockSpec> cand = mapping.get(c);
                    if (cand == null || cand.isEmpty()) continue;
                    BlockState state = resolveState(cand.get(0).id);
                    if (state == null || state.isAir()) continue;
                    result.add(new RenderBlock(
                            new BlockPos(x - cCol, y - cLayer, z - cRow), state));
                }
            }
        }
        return result;
    }

    /**
     * 返回结构所需材料组：按字符(候选组)去重，每组给出出现总次数 + 全部候选方块物品(均带数量)。
     * 与 {@link #getRenderBlocks()} 不同，这里<b>不</b>只取代表方块，而是把候选组里每个可解析方块都列出，
     * 以便预览插件的材料槽能原生轮播展示「可放其中任一方块」。
     */
    public List<MaterialGroup> getMaterialGroups() {
        Map<Character, Integer> counts = new LinkedHashMap<>();
        for (List<String> rows : layers) {
            for (String row : rows) {
                for (int i = 0; i < row.length(); i++) {
                    char c = row.charAt(i);
                    if (c == SKIP) continue;
                    List<BlockSpec> cand = mapping.get(c);
                    if (cand == null || cand.isEmpty()) continue;
                    counts.merge(c, 1, Integer::sum);
                }
            }
        }
        List<MaterialGroup> out = new ArrayList<>();
        for (Map.Entry<Character, Integer> e : counts.entrySet()) {
            List<BlockSpec> cand = mapping.get(e.getKey());
            List<ItemStack> alts = new ArrayList<>();
            for (BlockSpec spec : cand) {
                BlockState s = resolveState(spec.id);
                if (s == null || s.isAir()) continue;
                Item item = s.getBlock().asItem();
                if (item == Items.AIR) continue;
                alts.add(new ItemStack(item, e.getValue()));
            }
            if (!alts.isEmpty()) out.add(new MaterialGroup(e.getKey(), e.getValue(), alts));
        }
        return out;
    }

    @Nullable
    private BlockState resolveState(String id) {
        if (id.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(id.substring(1));
            if (tagId == null) return null;
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, tagId);
            return BuiltInRegistries.BLOCK.getTag(tag)
                    .flatMap(holders -> holders.stream().findFirst())
                    .map(holder -> holder.value().defaultBlockState())
                    .orElse(null);
        }
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        Block block = ForgeRegistries.BLOCKS.getValue(rl);
        return block == null ? null : block.defaultBlockState();
    }

    // === 渲染访问器（供 2D 示意图读取结构） ===
    public int getLayerCount() {
        return layers.size();
    }

    /**
     * 结构在局部坐标系下的包围盒尺寸（含控制器），用于物品提示显示 X×Y×Z。
     * 返回 {宽, 高, 深}：宽=列方向(X)跨度、高=层数(Y)、深=行方向(Z)跨度。
     */
    public int[] getSize() {
        int minX = 0, maxX = 0, minY = 0, maxY = 0, minZ = 0, maxZ = 0;
        for (int y = 0; y < layers.size(); y++) {
            List<String> rows = layers.get(y);
            for (int z = 0; z < rows.size(); z++) {
                String row = rows.get(z);
                for (int x = 0; x < row.length(); x++) {
                    char c = row.charAt(x);
                    if (c == SKIP) continue;
                    List<BlockSpec> cand = mapping.get(c);
                    if (cand == null || cand.isEmpty()) continue;
                    int lx = x - cCol, ly = y - cLayer, lz = z - cRow;
                    minX = Math.min(minX, lx); maxX = Math.max(maxX, lx);
                    minY = Math.min(minY, ly); maxY = Math.max(maxY, ly);
                    minZ = Math.min(minZ, lz); maxZ = Math.max(maxZ, lz);
                }
            }
        }
        return new int[]{maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1};
    }

    /** 结构稳定标识（来自 JSON 的 id 字段；缺省为空串）。供后续 JEI 显示 / 外部反查使用。 */
    public String getId() {
        return id;
    }

    /** 结构显示名（来自 JSON 的 name 字段；缺省为空串）。供后续 JEI 等展示场景使用。 */
    public String getName() {
        return name;
    }

    /**
     * 展示用名称：优先返回 {@link #getName()}，为空时回退到 {@link #getId()}。
     * 用于 JEI 等需要「一个可读标题」的位置。
     */
    public String getDisplayName() {
        return (name != null && !name.isEmpty()) ? name : (id != null ? id : "");
    }

    /** 是否绑定控制器朝向（来自 JSON 的 bindDirection，默认 true）。false 时允许四方向任意成形。 */
    public boolean isBindDirection() {
        return bindDirection;
    }

    public int getRowCount(int layer) {
        return (layer >= 0 && layer < layers.size()) ? layers.get(layer).size() : 0;
    }

    public int getColCount(int layer, int row) {
        if (layer < 0 || layer >= layers.size()) return 0;
        List<String> rows = layers.get(layer);
        if (row < 0 || row >= rows.size()) return 0;
        return rows.get(row).length();
    }

    /** 返回结构某格的<b>代表</b>方块状态（候选组第一个；空格/未映射/空气返回 null），供 2D 示意图绘制。 */
    @Nullable
    public BlockState blockStateAt(int layer, int row, int col) {
        if (layer < 0 || layer >= layers.size()) return null;
        List<String> rows = layers.get(layer);
        if (row < 0 || row >= rows.size()) return null;
        String r = rows.get(row);
        if (col < 0 || col >= r.length()) return null;
        char c = r.charAt(col);
        if (c == SKIP) return null;
        List<BlockSpec> cand = mapping.get(c);
        if (cand == null || cand.isEmpty()) return null;
        return resolveState(cand.get(0).id);
    }

    public boolean isController(int layer, int row, int col) {
        return layer == cLayer && row == cRow && col == cCol;
    }

    /**
     * 返回某格的候选方块数量（0 表示空格 / 未映射 / 空气或无法解析）。
     * 用于 2D 示意图判断是否「多选格」。
     */
    public int candidateCountAt(int layer, int row, int col) {
        if (layer < 0 || layer >= layers.size()) return 0;
        List<String> rows = layers.get(layer);
        if (row < 0 || row >= rows.size()) return 0;
        String r = rows.get(row);
        if (col < 0 || col >= r.length()) return 0;
        char c = r.charAt(col);
        if (c == SKIP) return 0;
        List<BlockSpec> cand = mapping.get(c);
        if (cand == null) return 0;
        return cand.size();
    }

    /**
     * 返回某格所有可解析且非空气的候选方块状态（按 mapping 顺序）。
     * 供 2D 示意图轮播显示与悬停清单使用。
     */
    public List<BlockState> candidateStatesAt(int layer, int row, int col) {
        List<BlockState> out = new ArrayList<>();
        if (layer < 0 || layer >= layers.size()) return out;
        List<String> rows = layers.get(layer);
        if (row < 0 || row >= rows.size()) return out;
        String r = rows.get(row);
        if (col < 0 || col >= r.length()) return out;
        char c = r.charAt(col);
        if (c == SKIP) return out;
        List<BlockSpec> cand = mapping.get(c);
        if (cand == null) return out;
        for (BlockSpec spec : cand) {
            BlockState s = resolveState(spec.id);
            if (s != null && !s.isAir()) out.add(s);
        }
        return out;
    }

    /**
     * 一个待放置的方块：世界坐标 + 目标方块状态 + 所需材料（数量 1）+ 原始映射串 + NBT + 是否写入 NBT。
     * <ul>
     *   <li>{@code state}：实际要放置的方块状态（取候选组第一个作为代表，与预览一致）。</li>
     *   <li>{@code cost}：默认材料（代表方块对应的物品，数量 1）。</li>
     *   <li>{@code nbt}：若候选带 NBT 且 {@code writeNbt} 为 true，自动建造时写入方块实体；否则为 null。</li>
     *   <li>{@code source}：候选组第一个原始映射串（id 或 "#tag"），用于在生存模式扣除时判断背包是否满足。</li>
     * </ul>
     */
    public record BlockPlacement(BlockPos worldPos, BlockState state, ItemStack cost,
                                 @Nullable CompoundTag nbt, boolean writeNbt, String source) {
    }

    /**
     * 计算以 controllerPos 为控制器、按 facing 旋转后，整个结构所有「需要放置」的方块
     * 在世界中的坐标与对应方块状态、材料。跳过控制器自身（玩家已经放好）。
     * 每个格子取候选组第一个作为实际放置目标（与 2D 预览代表一致）。NBT 随候选组第一个携带。
     */
    public List<BlockPlacement> computeWorldPlacements(BlockPos controllerPos, Direction facing) {
        int fx = facing.getNormal().getX();   // forward.x
        int fz = facing.getNormal().getZ();   // forward.z
        int rx = -fz;                          // right = (-forward.z, forward.x)
        int rz = fx;

        List<BlockPlacement> out = new ArrayList<>();
        for (int y = 0; y < layers.size(); y++) {
            List<String> rows = layers.get(y);
            for (int z = 0; z < rows.size(); z++) {
                String row = rows.get(z);
                for (int x = 0; x < row.length(); x++) {
                    char c = row.charAt(x);
                    if (c == SKIP) continue;
                    List<BlockSpec> cand = mapping.get(c);
                    if (cand == null || cand.isEmpty()) continue;        // 未映射字符：跳过
                    if (c == controllerChar.charAt(0)) continue;          // 控制器已存在，跳过

                    BlockSpec rep = cand.get(0);
                    BlockState state = resolveState(rep.id);
                    if (state == null || state.isAir()) continue;

                    int lx = x - cCol;
                    int ly = y - cLayer;
                    int lz = z - cRow;
                    int wx = rx * lx + fx * lz;
                    int wz = rz * lx + fz * lz;
                    BlockPos wp = controllerPos.offset(wx, ly, wz);

                    Item item = state.getBlock().asItem();
                    ItemStack cost = (item == Items.AIR) ? ItemStack.EMPTY : new ItemStack(item, 1);
                    out.add(new BlockPlacement(wp, state, cost, rep.nbt, rep.writeNbt, rep.id));
                }
            }
        }
        return out;
    }
}
