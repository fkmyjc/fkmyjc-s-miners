package com.fkmyjc.fkmyjcs_miners.vein;

import net.minecraft.resources.ResourceLocation;

/**
 * 矿脉修改参数容器（链式 builder）。所有字段均可选：未设置的字段在
 * {@link VeinManager#modifyVeinAt} 中保持原值不变。
 *
 * <p>矿石类字段（主矿 / 次矿 / 岩石）采用三态：
 * <ul>
 *   <li>不调用对应 setXxx() = 保持原值；</li>
 *   <li>调用 setXxx(id) / setXxx("minecraft:xxx") = 改为指定矿石；</li>
 *   <li>调用 clearXxx() = 清除（主矿 / 次矿清除为 null，岩石清除后回退石头）。</li>
 * </ul>
 * 便捷地，{@code setXxx("&lt;ns&gt;:none")} 或 {@code setXxx("none")}（path 段为 {@code none}）等价于 clear。</p>
 *
 * <p>权重字段（mainW / secW / rockW）任一被设置后，修改时会把三者重新归一化使和为 100
 * （对齐 {@code /vein prob} 行为）；若三者之和不为 100 则按比例缩放，保证恒为 100。</p>
 *
 * <p>典型用法：
 * <pre>{@code
 * VeinEdit edit = new VeinEdit()
 *     .type(2)                            // 改为双矿脉
 *     .mainOre("minecraft:iron_ore")      // 主矿设为铁矿
 *     .secondaryOre("minecraft:gold_ore") // 次矿设为金矿
 *     .rock("minecraft:deepslate")       // 岩石设为深板岩
 *     .weights(50, 20)                    // 主矿 50% / 次矿 20%（岩石自动补到 100）
 *     .reserves(100000)                   // 储量设为 100000
 *     .addReserves(5000);                 // 再 +5000
 * VeinManager.modifyVeinAt(serverLevel, someBlockPos, edit);
 * }</pre>
 */
public final class VeinEdit {

    // 类型：0=贫穷 / 1=单 / 2=双；null=不改
    private Integer typeCode = null;

    // 主矿三态
    private ResourceLocation mainOreId = null;
    private boolean mainOreSet = false;
    private boolean mainOreClear = false;

    // 次矿三态
    private ResourceLocation secondaryOreId = null;
    private boolean secondaryOreSet = false;
    private boolean secondaryOreClear = false;

    // 岩石三态
    private ResourceLocation rockId = null;
    private boolean rockSet = false;
    private boolean rockClear = false;

    // 权重（0~100）；null=不改；任一非 null 触发归一化
    private Integer mainW = null;
    private Integer secW = null;
    private Integer rockW = null;

    // 储量
    private Integer reserves = null;     // 设值
    private Integer addReserves = null;  // 增量

    // 重新生成
    private boolean reset = false;
    private Long genSalt = null;         // reset 时使用的盐；null=随机

    public VeinEdit() {}

    /* ===================== builder ===================== */

    public VeinEdit type(int code) { this.typeCode = code; return this; }

    public VeinEdit mainOre(String id) {
        if (isClearToken(id)) return clearMainOre();
        this.mainOreId = ResourceLocation.tryParse(id);
        this.mainOreSet = true;
        this.mainOreClear = false;
        return this;
    }

    public VeinEdit mainOre(ResourceLocation id) {
        this.mainOreId = id;
        this.mainOreSet = true;
        this.mainOreClear = false;
        return this;
    }

    public VeinEdit clearMainOre() {
        this.mainOreSet = false;
        this.mainOreClear = true;
        return this;
    }

    public VeinEdit secondaryOre(String id) {
        if (isClearToken(id)) return clearSecondaryOre();
        this.secondaryOreId = ResourceLocation.tryParse(id);
        this.secondaryOreSet = true;
        this.secondaryOreClear = false;
        return this;
    }

    public VeinEdit secondaryOre(ResourceLocation id) {
        this.secondaryOreId = id;
        this.secondaryOreSet = true;
        this.secondaryOreClear = false;
        return this;
    }

    public VeinEdit clearSecondaryOre() {
        this.secondaryOreSet = false;
        this.secondaryOreClear = true;
        return this;
    }

    public VeinEdit rock(String id) {
        if (isClearToken(id)) return clearRock();
        this.rockId = ResourceLocation.tryParse(id);
        this.rockSet = true;
        this.rockClear = false;
        return this;
    }

    public VeinEdit rock(ResourceLocation id) {
        this.rockId = id;
        this.rockSet = true;
        this.rockClear = false;
        return this;
    }

    public VeinEdit clearRock() {
        this.rockSet = false;
        this.rockClear = true;
        return this;
    }

    /** 设置主矿 / 次矿权重（岩石按 100-主-次 补足）；任一非 null 即触发归一化。 */
    public VeinEdit weights(Integer mainW, Integer secW) {
        this.mainW = mainW;
        this.secW = secW;
        return this;
    }

    /** 设置主矿 / 次矿 / 岩石权重；任一非 null 即触发归一化。 */
    public VeinEdit weights(Integer mainW, Integer secW, Integer rockW) {
        this.mainW = mainW;
        this.secW = secW;
        this.rockW = rockW;
        return this;
    }

    public VeinEdit mainWeight(int w) { this.mainW = w; return this; }
    public VeinEdit secondaryWeight(int w) { this.secW = w; return this; }
    public VeinEdit rockWeight(int w) { this.rockW = w; return this; }

    public VeinEdit reserves(int r) { this.reserves = r; return this; }
    public VeinEdit addReserves(int d) { this.addReserves = d; return this; }

    /** 重新随机生成整条矿脉（忽略其余字段；可用 {@link #genSalt(long)} 指定盐）。 */
    public VeinEdit reset() { this.reset = true; return this; }
    public VeinEdit reset(long salt) { this.reset = true; this.genSalt = salt; return this; }
    public VeinEdit genSalt(long salt) { this.genSalt = salt; return this; }

    /* ===================== 包内读取（供 VeinManager 使用） ===================== */

    Integer typeCode() { return typeCode; }

    boolean hasMainOre() { return mainOreSet; }
    ResourceLocation mainOreId() { return mainOreId; }
    boolean isMainOreClear() { return mainOreClear; }

    boolean hasSecondaryOre() { return secondaryOreSet; }
    ResourceLocation secondaryOreId() { return secondaryOreId; }
    boolean isSecondaryOreClear() { return secondaryOreClear; }

    boolean hasRock() { return rockSet; }
    ResourceLocation rockId() { return rockId; }
    boolean isRockClear() { return rockClear; }

    Integer mainW() { return mainW; }
    Integer secW() { return secW; }
    Integer rockW() { return rockW; }

    Integer reserves() { return reserves; }
    Integer addReserves() { return addReserves; }

    boolean isReset() { return reset; }
    Long genSalt() { return genSalt; }

    /* ===================== 内部工具 ===================== */

    private static boolean isClearToken(String id) {
        if (id == null) return false;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl != null && rl.getPath().equals("none");
    }
}
