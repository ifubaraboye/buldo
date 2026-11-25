package dev.buldo.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public final class BuldoClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("buldo", "main"));

	private static KeyMapping overviewKey;

	@Override
	public void onInitializeClient() {
		overviewKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.buldo.overview", InputConstants.Type.KEYBOARD, InputConstants.KEY_F6, CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(BuldoClient::tick);
		HudElementRegistry.addLast(OverviewHud.ID, new OverviewHud());
	}

	private static void tick(Minecraft minecraft) {
		if (minecraft.player == null || minecraft.level == null) {
			return;
		}
		// Do not steal the key while a chat screen or menu is open.
		if (minecraft.gui.screen() != null) {
			return;
		}
		while (overviewKey.consumeClick()) {
			OverviewCamera.cycle(minecraft);
		}
		OverviewCamera.tick(minecraft);
	}
}
