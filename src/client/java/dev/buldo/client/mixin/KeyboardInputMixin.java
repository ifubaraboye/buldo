package dev.buldo.client.mixin;

import dev.buldo.client.OverviewCamera;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In the overview the movement keys fly the camera, not the player, so the
 * player's own input is emptied every tick. Releasing the overview restores it
 * on the very next tick.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void buldo$freezePlayer(CallbackInfo ci) {
		if (OverviewCamera.active()) {
			((ClientInput) (Object) this).keyPresses = Input.EMPTY;
		}
	}
}
