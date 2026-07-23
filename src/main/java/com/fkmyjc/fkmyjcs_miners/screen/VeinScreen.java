package com.fkmyjc.fkmyjcs_miners.screen;

import com.fkmyjc.fkmyjcs_miners.menu.VeinData;
import com.fkmyjc.fkmyjcs_miners.menu.VeinMenu;
import com.fkmyjc.fkmyjcs_miners.vein.VeinOreRegistry;
import com.fkmyjc.fkmyjcs_miners.vein.VeinType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Vein info GUI (client-side rendering).
 * Shows: dimension, type, params a/b, main/side/rock ores with weights, and a stacked bar chart.
 *
 * <p>Uses {@link GuiGraphics} instance methods (1.20.1 API).
 * Panel is sized to avoid overlap with the parent's inventory label.
 */
public class VeinScreen extends AbstractContainerScreen<VeinMenu> {

    private static final int PANEL_W = 208;
    private static final int PANEL_H = 178;

    public VeinScreen(VeinMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = PANEL_W;
        this.imageHeight = PANEL_H;
        // Disable default inventory label — we draw everything ourselves
        this.inventoryLabelX = Integer.MAX_VALUE; // off-screen
        this.titleLabelX = Integer.MAX_VALUE;     // off-screen
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // Background handled entirely in render()
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        int x = (this.width - PANEL_W) / 2;
        int y = (this.height - PANEL_H) / 2;
        this.leftPos = x;
        this.topPos = y;

        // Semi-transparent panel + green border
        graphics.fill(x, y, x + PANEL_W, y + PANEL_H, 0xC0101010);
        // Top border (green)
        graphics.fill(x, y, x + PANEL_W, y + 2, 0xFF4C7A34);
        // Left border
        graphics.fill(x, y, x + 2, y + PANEL_H, 0xFF4C7A34);
        // Right border
        graphics.fill(x + PANEL_W - 2, y, x + PANEL_W, y + PANEL_H, 0xFF4C7A34);
        // Bottom border
        graphics.fill(x, y + PANEL_H - 2, x + PANEL_W, y + PANEL_H, 0xFF4C7A34);

        VeinData d = menu.data;

        int tx = x + 14;
        int ty = y + 12;

        // Title
        graphics.drawString(this.font, Component.literal("Vein Info"), tx, ty, 0xFFFFFF, false);
        ty += 18;

        // Dimension
        graphics.drawString(this.font, Component.literal("Dim: " + d.dimension), tx, ty, 0xBFBFBF, false);
        ty += 14;

        // Type
        graphics.drawString(this.font, Component.literal("Type: " + typeName(d.type)), tx, ty, 0xBFBFBF, false);
        ty += 18; // gap before ore list

        // Ore entries
        drawOre(graphics, d.mainOre, tx, ty, "Main", d.wMain, VeinOreRegistry.getOreColor(d.mainOre));
        ty += 20;
        drawOre(graphics, d.secondaryOre, tx, ty, "Side", d.wSec, VeinOreRegistry.getOreColor(d.secondaryOre));
        ty += 20;
        drawOre(graphics, d.rock, tx, ty, "Rock", d.wRock, VeinOreRegistry.getOreColor(d.rock));

        // Reserves
        ty += 24;
        graphics.drawString(this.font,
                Component.literal("Reserves: " + String.format("%,d", d.reserves)),
                tx, ty, 0xF1C40F, false);

        // Stacked weight bar at bottom
        int barY = y + PANEL_H - 22;
        int barW = PANEL_W - 28;
        int barX = x + 14;
        int total = Math.max(1, d.wMain + d.wSec + d.wRock);
        int segMain = barW * d.wMain / total;
        int segSec = barW * d.wSec / total;
        int barH = 10;
        graphics.fill(barX, barY, barX + segMain, barY + barH, VeinOreRegistry.getOreColor(d.mainOre));
        graphics.fill(barX + segMain, barY, barX + segMain + segSec, barY + barH, VeinOreRegistry.getOreColor(d.secondaryOre));
        graphics.fill(barX + segMain + segSec, barY, barX + barW, barY + barH, VeinOreRegistry.getOreColor(d.rock));

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawOre(GuiGraphics graphics, ResourceLocation id, int x, int y,
                         String label, int weight, int color) {
        ItemStack stack = (id == null) ? ItemStack.EMPTY : new ItemStack(ForgeRegistries.ITEMS.getValue(id));
        graphics.renderItem(stack, x, y);
        String name = (id == null || stack.isEmpty()) ? "None" : stack.getHoverName().getString();
        graphics.drawString(this.font,
                Component.literal(label + ": " + name + "  (" + weight + "%)"),
                x + 20, y + 4, color, false);
    }

    private static String typeName(VeinType t) {
        return switch (t) {
            case SINGLE -> "Single Ore (20%)";
            case DOUBLE -> "Double Ore (60%)";
            case POVERTY -> "Poverty (20%)";
        };
    }
}
