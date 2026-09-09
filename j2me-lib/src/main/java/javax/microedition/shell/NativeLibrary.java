/*
 * Copyright 2026
 * Licensed under the Apache License, Version 2.0.
 */
package javax.microedition.shell;

import android.app.Activity;
import android.app.Application;
import android.view.KeyEvent;

import java.io.File;
import java.util.Map;

/**
 * Small facade intended for app hosts. It keeps the public embedding surface in one class while
 * the runtime/session implementation remains independently testable.
 */
public final class NativeLibrary implements AutoCloseable {
	private final J2meSession session;
	private J2meConfig config = new J2meConfig();

	public static void initialize(Application application) {
		J2meRuntime.initialize(application);
	}

	public NativeLibrary(Activity activity, J2meSession.Callbacks callbacks) {
		session = J2meRuntime.createSession(activity, callbacks);
	}

	public NativeLibrary setExternalOutput(ExternalVideoOutput output) {
		session.setVideoOutput(output);
		return this;
	}

	/** Enables the optional completed-Canvas frame callback for frame-driven host input. */
	public NativeLibrary setFrameCallbackEnable(boolean enabled) {
		session.setFrameCallbackEnable(enabled);
		return this;
	}

	/** Enables or mutes audio output for the active embedded session. */
	public NativeLibrary setAudioEnabled(boolean enabled) {
		session.setAudioEnabled(enabled);
		return this;
	}

	public NativeLibrary overrideConfig(J2meConfig config) {
		if (config == null) {
			throw new NullPointerException("J2ME configuration is required");
		}
		this.config = config;
		return this;
	}

	/** Sets one value on the current per-session configuration. */
	public NativeLibrary setConfig(String key, Object value) {
		config.set(key, value);
		return this;
	}

	/** Sets several values on the current per-session configuration. */
	public NativeLibrary setConfig(Map<?, ?> values) {
		config.setAll(values);
		return this;
	}

	/** Applies a JSON profile fragment to the current per-session configuration. */
	public NativeLibrary setConfigJson(String json) {
		config.setJson(json);
		return this;
	}

	public void startGame(File midletJar, File conversionDirectory) {
		session.start(midletJar, conversionDirectory, config);
	}

	/** Starts a game from host-provided absolute JAR and conversion directory paths. */
	public void startGame(String midletJarPath, String conversionDirectoryPath) {
		if (midletJarPath == null || midletJarPath.trim().isEmpty()) {
			throw new IllegalArgumentException("MIDlet JAR path is required");
		}
		if (conversionDirectoryPath == null || conversionDirectoryPath.trim().isEmpty()) {
			throw new IllegalArgumentException("Conversion directory path is required");
		}
		startGame(new File(midletJarPath), new File(conversionDirectoryPath));
	}

	/** Requests cooperative pause; onStateChanged(PAUSED) acknowledges completed callbacks. */
	public void pause() {
		session.pause();
	}

	/** Requests startApp/showNotify asynchronously. A game may reject resumption and stay PAUSED. */
	public void resume() {
		session.resume();
	}

	public void stop() {
		session.stop();
	}

	public J2meSession.State getState() {
		return session.getState();
	}

	public boolean dispatchKeyEvent(KeyEvent event) {
		return session.dispatchKeyEvent(event);
	}

	public void keyDown(int midpKeyCode) {
		session.keyDown(midpKeyCode);
	}

	public void keyUp(int midpKeyCode) {
		session.keyUp(midpKeyCode);
	}

	public void pointerDown(int pointer, int x, int y) {
		session.pointerDown(pointer, x, y);
	}

	public void pointerMove(int pointer, int x, int y) {
		session.pointerMove(pointer, x, y);
	}

	public void pointerUp(int pointer, int x, int y) {
		session.pointerUp(pointer, x, y);
	}

	public void dispatchActivityResult(int requestCode, int resultCode, android.content.Intent data) {
		session.dispatchActivityResult(requestCode, resultCode, data);
	}

	public void dispatchUiAction(LcdUiAction action) {
		session.dispatchUiAction(action);
	}

	@Override
	public void close() {
		session.close();
	}
}
