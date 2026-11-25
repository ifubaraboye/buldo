package dev.buldo.site;

public enum SiteState {
	/** Created but the plan has not been built yet. */
	EMPTY,
	/** Plan is loaded and the site is ready to be started. */
	READY,
	/** Started, but one or more materials are missing. */
	WAITING_MATERIALS,
	BUILDING,
	PAUSED,
	/** Ran into missing materials while placing. Resumes automatically. */
	BLOCKED,
	DONE,
	/** The last build was undone; the site can be started again. */
	UNDONE,
	/** The schematic file it depends on is gone or unreadable. */
	MISSING_SCHEMATIC,
	ERROR;

	public boolean active() {
		return this == BUILDING || this == PAUSED || this == BLOCKED || this == WAITING_MATERIALS;
	}

	public String label() {
		return switch (this) {
			case EMPTY -> "empty";
			case READY -> "ready";
			case WAITING_MATERIALS -> "waiting for materials";
			case BUILDING -> "building";
			case PAUSED -> "paused";
			case BLOCKED -> "blocked";
			case DONE -> "done";
			case UNDONE -> "undone";
			case MISSING_SCHEMATIC -> "schematic missing";
			case ERROR -> "error";
		};
	}
}
