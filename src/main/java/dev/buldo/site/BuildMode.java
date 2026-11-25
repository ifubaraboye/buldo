package dev.buldo.site;

/** How a site puts its blocks into the world. */
public enum BuildMode {
	/** Blocks appear one by one, paid for straight from the linked containers. */
	INSTANT("instant"),
	/** Villagers walk to the site and place the blocks by hand. */
	WORKERS("workers");

	private final String id;

	BuildMode(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public String label() {
		return this == WORKERS ? "workers" : "instant";
	}

	public static BuildMode parse(String value) {
		if (value != null && value.equalsIgnoreCase("instant")) {
			return INSTANT;
		}
		return WORKERS;
	}
}
