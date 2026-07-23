package com.fkmyjc.fkmyjcs_miners.vein;

/**
 * 矿脉类型。每区块生成矿脉时按概率抽取：
 * <ul>
 *   <li>{@link #SINGLE}   单矿石矿脉 20%</li>
 *   <li>{@link #DOUBLE}   双矿石矿脉 60%</li>
 *   <li>{@link #POVERTY}  贫穷矿脉（单矿石，a 为负）20%</li>
 * </ul>
 */
public enum VeinType {
    SINGLE,
    DOUBLE,
    POVERTY
}
