package dev.buldo.client.mixin;

import dev.buldo.client.OverviewCamera;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets the overview camera take over the camera after vanilla has placed it. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V", at = @At("TAIL"))
	private void buldo$overview(DeltaTracker delta, CallbackInfo ci) {
		OverviewCamera.Frame frame = OverviewCamera.frame();
		if (frame != null) {
			setPosition(frame.position());
			setRotation(frame.yaw(), frame.pitch());
		}
	}
}
