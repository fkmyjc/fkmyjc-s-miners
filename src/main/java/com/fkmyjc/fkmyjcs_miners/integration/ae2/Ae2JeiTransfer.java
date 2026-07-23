package com.fkmyjc.fkmyjcs_miners.integration.ae2;

import com.fkmyjc.fkmyjcs_miners.integration.jei.MultiblockPreviewCategory;
import mezz.jei.api.registration.IRecipeTransferRegistration;

/**
 * AE2 转移处理器的注册入口，被 {@code FkmyjcsJeiPlugin} 在
 * {@code ModList.isLoaded("ae2")} 守卫后调用。
 *
 * <p>把 AE2 相关类的<b>首次加载</b>都推迟到这里：{@code FkmyjcsJeiPlugin} 自身不 import 任何
 * AE2 / {@link Ae2PatternTransfer} 类型，因此当 AE2 缺失、守卫为假时，这些类永不被加载，杜绝
 * {@code NoClassDefFoundError}。</p>
 */
public final class Ae2JeiTransfer {

    private Ae2JeiTransfer() {
    }

    public static void register(IRecipeTransferRegistration registration) {
        registration.addRecipeTransferHandler(
                new Ae2PatternTransfer(registration.getTransferHelper()),
                MultiblockPreviewCategory.TYPE);
    }
}
