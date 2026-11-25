package dev.buldo;

import dev.buldo.command.BuldoCommand;
import dev.buldo.site.SiteManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Buldo: places schematics block by block, paid for with exact items taken
 * from containers the player explicitly links to a build site.
 *
 * Everything is server authoritative. There is no client side rendering code:
 * the preview is drawn with particles so it works on vanilla clients and does
 * not fight Iris/ Sodium style render mods.
 */
public final class Buldo implements ModInitializer {
	public static final String MOD_ID = "buldo";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(SiteManager::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(SiteManager::onServerStopping);
		ServerLifecycleEvents.BEFORE_SAVE.register((server, flush, force) -> SiteManager.onBeforeSave(server));
		ServerTickEvents.END_SERVER_TICK.register(SiteManager::onServerTick);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> BuldoCommand.register(dispatcher));

		LOGGER.info("Buldo loaded (block by block, exact materials)");
	}
}
