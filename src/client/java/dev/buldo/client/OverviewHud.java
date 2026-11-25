package dev.buldo.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Status line for the overview camera, shown while it is active. */
public final class OverviewHud implements HudElement {
	public static final Identifier ID = Identifier.fromNamespaceAndPath("buldo", "overview");

	private static final int TITLE_COLOR = 0xFFFFFFFF;
	private static final int HINT_COLOR = 0xFFB9C6D4;

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (!OverviewCamera.active()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		int width = minecraft.getWindow().getGuiScaledWidth();
		int center = width / 2;

		String title = "Overview · " + OverviewCamera.mode().label()
				+ " · " + Math.round(OverviewCamera.zoom()) + "m";
		graphics.centeredText(minecraft.font, Component.literal(title), center, 8, TITLE_COLOR);

		if (OverviewCamera.showHint()) {
			String hint = "F6 next view · scroll zoom · WASD pan · Space/Shift up-down";
			graphics.centeredText(minecraft.font, Component.literal(hint), center, 20, HINT_COLOR);
		}
	}
}
