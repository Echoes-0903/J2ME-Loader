/*
 * Copyright 2026
 * Licensed under the Apache License, Version 2.0.
 */
package javax.microedition.media;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Process-local host audio gate for the single embedded MIDlet session. */
public final class MediaRuntimeAudio {
	private static final Object LOCK = new Object();
	private static final Set<BasePlayer> PLAYERS = Collections.newSetFromMap(new WeakHashMap<>());
	private static boolean hostMuted;
	private static volatile boolean hostPaused;

	private MediaRuntimeAudio() {
	}

	static boolean register(BasePlayer player) {
		synchronized (LOCK) {
			PLAYERS.add(player);
			return hostMuted;
		}
	}

	static void unregister(BasePlayer player) {
		synchronized (LOCK) {
			PLAYERS.remove(player);
		}
	}

	/** Mutes existing players and becomes the inherited state for players created later. */
	public static void setHostMuted(boolean muted) {
		ArrayList<BasePlayer> snapshot;
		synchronized (LOCK) {
			hostMuted = muted;
			snapshot = new ArrayList<>(PLAYERS);
		}
		MidiInterface.setHostMuted(muted);
		for (BasePlayer player : snapshot) {
			player.setHostMuted(muted);
		}
	}

	static boolean isHostPaused() {
		return hostPaused;
	}

	/** Holds playback without changing the MIDlet's requested Player state or mute setting. */
	public static void setHostPaused(boolean paused) {
		ArrayList<BasePlayer> snapshot;
		synchronized (LOCK) {
			hostPaused = paused;
			snapshot = new ArrayList<>(PLAYERS);
		}
		MidiInterface.setHostPaused(paused);
		// Never hold the registry lock while entering a Player monitor.
		for (BasePlayer player : snapshot) player.updateHostPause();
	}

	/** Retire old-session players before clearing the process-wide gates. */
	public static void closeHostPlayers() {
		ArrayList<BasePlayer> snapshot;
		synchronized (LOCK) {
			snapshot = new ArrayList<>(PLAYERS);
		}
		for (BasePlayer player : snapshot) {
			try {
				player.close();
			} catch (RuntimeException ignored) {
				unregister(player);
			}
		}
		MidiInterface.closeHostDriver();
		setHostPaused(false);
		setHostMuted(false);
	}
}
