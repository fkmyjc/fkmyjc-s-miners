package com.fkmyjc.fkmyjcs_miners.vein;

/**
 * {@link VeinManager#modifyVeinAt} 的执行结果。
 *
 * <p>{@code success=false} 时 {@code report} 为 null（如传入非服务端维度）；
 * {@code success=true} 时 {@code report} 为修改后该区块的矿脉快照。</p>
 */
public final class ModifyResult {

    public final boolean success;
    public final String message;
    public final VeinReport report;

    public ModifyResult(boolean success, String message, VeinReport report) {
        this.success = success;
        this.message = message;
        this.report = report;
    }

    public static ModifyResult ok(VeinReport report) {
        return new ModifyResult(true, "ok", report);
    }

    public static ModifyResult fail(String msg) {
        return new ModifyResult(false, msg, null);
    }
}
