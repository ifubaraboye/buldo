package dev.buldo.client.mixin;

import dev.buldo.client.OverviewCamera;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mouse look, scroll and clicks while the overview camera is up. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow
	private double accumulatedDX;

	@Shadow
	private double accumulatedDY;

	@Inject(method = "turnPlayer(D)V", at = @At("HEAD"), cancellable = true)
	private void buldo$turn(double frameTime, CallbackInfo ci) {
		if (OverviewCamera.onMouseTurn(accumulatedDX, accumulatedDY)) {
			accumulatedDX = 0.0;
			accumulatedDY = 0.0;
			ci.cancel();
		}
	}

	@Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
	private void buldo$scroll(long window, double xoffset, double yoffset, CallbackInfo ci) {
		if (OverviewCamera.onScroll(yoffset)) {
			ci.cancel();
		}
	}

	@Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V",
			at = @At("HEAD"), cancellable = true)
	private void buldo$button(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
		if (OverviewCamera.blocksClicks()) {
			ci.cancel();
		}
	}
}
