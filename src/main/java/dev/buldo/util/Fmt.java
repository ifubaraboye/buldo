package dev.buldo.util;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

public final class Fmt {
	private Fmt() {
	}

	public static MutableComponent pre() {
		return Component.literal("[Buldo] ").withStyle(ChatFormatting.AQUA);
	}

	public static MutableComponent line(String text) {
		return pre().append(Component.literal(text).withStyle(ChatFormatting.GRAY));
	}

	public static MutableComponent ok(String text) {
		return pre().append(Component.literal(text).withStyle(ChatFormatting.GREEN));
	}

	public static MutableComponent warn(String text) {
		return pre().append(Component.literal(text).withStyle(ChatFormatting.YELLOW));
	}

	public static MutableComponent err(String text) {
		return pre().append(Component.literal(text).withStyle(ChatFormatting.RED));
	}

	public static void send(CommandSourceStack src, Component c) {
		src.sendSuccess(() -> c, false);
	}

	public static void send(CommandSourceStack src, String text) {
		send(src, line(text));
	}

	public static void actionBar(ServerPlayer player, Component c) {
		player.sendSystemMessage(c, true);
	}

	public static String item(Item item) {
		var id = BuiltInRegistries.ITEM.getKey(item);
		String path = id == null ? "unknown" : id.getPath();
		return path.replace('_', ' ');
	}

	public static String itemStack(ItemStack stack) {
		return item(stack.getItem());
	}

	public static String num(long n) {
		return String.format(Locale.ROOT, "%,d", n);
	}

	public static String pct(double done, double total) {
		if (total <= 0) return "100%";
		return String.format(Locale.ROOT, "%.1f%%", 100.0 * done / total);
	}

	public static String secs(long ticks) {
		long s = Math.max(0, ticks / 20);
		if (s < 60) return s + "s";
		long m = s / 60;
		return m + "m " + (s % 60) + "s";
	}
}
