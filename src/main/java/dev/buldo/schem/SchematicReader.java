package dev.buldo.schem;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Detects and reads the supported schematic formats. */
public final class SchematicReader {
	private SchematicReader() {
	}

	public static Blueprint read(Path path, String name) throws IOException {
		String hash = sha1(path);
		boolean gzip = isGzip(path);
		CompoundTag root;
		try {
			root = gzip
					? NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap())
					: NbtIo.read(path);
		} catch (Exception e) {
			throw new IOException("could not parse NBT: " + e.getMessage(), e);
		}
		String fileName = path.getFileName().toString();

		if (root.contains("Schematic")) {
			return SpongeReader.read(root, name, fileName, hash);
		}
		if (root.contains("Regions")) {
			return LitematicReader.read(root, name, fileName, hash);
		}
		if (root.contains("palette") && root.contains("blocks") && root.contains("size")) {
			return StructureReader.read(root, name, fileName, hash);
		}
		throw new IOException("unrecognised format (expected a Sponge .schem, a vanilla structure .nbt or a .litematic)");
	}

	public static String sha1(Path path) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			try (InputStream in = Files.newInputStream(path)) {
				byte[] buffer = new byte[8192];
				int read;
				while ((read = in.read(buffer)) > 0) {
					digest.update(buffer, 0, read);
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (Exception e) {
			return "";
		}
	}

	private static boolean isGzip(Path path) throws IOException {
		try (InputStream in = Files.newInputStream(path)) {
			byte[] head = in.readNBytes(2);
			return head.length == 2 && (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B;
		}
	}
}
