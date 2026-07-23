package com.fkmyjc.fkmyjcs_miners.vein;

import net.minecraft.world.item.Item;

/**
 * 一个区块的矿脉数据。一旦生成即固定（见 {@link VeinManager} 的确定性缓存）。
 *
 * <p>组成权重（三种方块：主矿 / 次矿 / 岩石），保证三者之和恒为 100：
 * <ul>
 *   <li>主矿权重 mainW（单/贫穷矿脉即矿物总占比；双矿脉约 45%）</li>
 *   <li>次矿权重 secW（仅 {@link VeinType#DOUBLE} 有效；单/贫穷/纯岩石矿脉为 0）</li>
 *   <li>岩石权重 rockW</li>
 * </ul>
 * 概率模型见 {@link VeinManager#generate}：
 * <ul>
 *   <li>普通单矿脉：主矿 65±10 / 岩石 35∓10；</li>
 *   <li>普通双矿脉：主矿 45±10 / 次矿 20±10 / 岩石补足至 100（次矿必与主矿不同）；</li>
 *   <li>贫穷矿脉：主矿 30±10 / 岩石 70∓10；</li>
 *   <li>无矿（或末地且未开矿物）时：纯岩石矿脉 100%（主矿为 null）。</li>
 * </ul>
 *
 * <p><b>关于储量（reserves）</b>：它不是常量而是<b>可变变量</b>。{@link VeinManager} 的
 * 确定性随机只为它生成<b>初始值</b>；真正的“当前储量”由
 * {@code com.fkmyjc.fkmyjcs_miners.chunkdata.ChunkVeinData} 持有并可被开采逻辑修改并落盘。
 * 因此本类中的 {@code reserves} 字段为可变（非 final），提供 {@link #setReserves(int)} 便于
 * 在需要时就地调整（注意它被 {@link VeinManager} 缓存共享，若要变更请以 ChunkVeinData 为权威）。
 */
public class Vein {

    public final VeinType type;
    public final Item mainOre;          // 主矿石（绝不可能是稀有矿）；纯岩石矿脉为 null
    public final Item secondaryOre;     // 次矿石（仅双矿石矿脉，可为稀有矿）；单/贫穷/纯岩石为 null
    public final Item rock;             // 岩石（石头或深板岩等）
    public final int mainW;             // 主矿权重（%）
    public final int secW;             // 次矿权重（%）；单/贫穷/纯岩石为 0
    public final int rockW;             // 岩石权重（%），三者恒为 100
    public int reserves;                 // 储量（可变变量；随机仅给出初始值；纯岩石矿脉为 0）

    public Vein(VeinType type, Item mainOre, Item secondaryOre, Item rock,
                int mainW, int secW, int rockW, int reserves) {
        this.type = type;
        this.mainOre = mainOre;
        this.secondaryOre = secondaryOre;
        this.rock = rock;
        this.mainW = mainW;
        this.secW = secW;
        this.rockW = rockW;
        this.reserves = reserves;
    }

    /** 修改储量（随机仅给出初始值，后续可由开采逻辑在此就地调整）。 */
    public void setReserves(int reserves) {
        this.reserves = reserves;
    }

    /** 主矿权重（%）。 */
    public int mainWeight() { return mainW; }

    /** 次矿权重（%）：仅双矿石矿脉有效；单/贫穷/纯岩石矿脉为 0。三者和恒为 100。 */
    public int secondaryWeight() { return secW; }

    /** 岩石权重（%）。三者和恒为 100。 */
    public int rockWeight() { return rockW; }
}
