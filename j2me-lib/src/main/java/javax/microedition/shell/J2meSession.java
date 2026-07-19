/*
 * Copyright 2026
 * Licensed under the Apache License, Version 2.0.
 */
package javax.microedition.shell;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.keyboard.KeyMapper;
import javax.microedition.util.ContextHolder;

/** A single embedded MIDlet session owned by an ordinary Android Activity. */
public final class J2meSession implements J2meHost, AutoCloseable {
	private static final long LCDUI_REFRESH_INTERVAL_MS = 100;

	public enum State {
		IDLE,
		PREPARING,
		RUNNING,
		PAUSED,
		STOPPING,
		STOPPED,
		FAILED
	}

	public interface Callbacks {
		default void onStateChanged(State state) {
		}

		default void onTitleChanged(String title) {
		}

		default void onError(Throwable error) {
		}

		default void onOptionsMenuRequested() {
		}

		/** Delivers an immutable semantic snapshot of the current non-Canvas LCDUI screen. */
		default void onLcdUiStateChanged(LcdUiState state) {
		}

		/** Tells the host to remove a semantic LCDUI screen. */
		default void onLcdUiClosed(long screenId) {
		}

		default void onExitRequested(J2meSession session) {
			session.stop();
		}

		default void onSessionFinished() {
		}
	}

	private final Activity activity;
	private final Callbacks callbacks;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "J2mePrepare");
		thread.setDaemon(true);
		return thread;
	});

	private volatile State state = State.IDLE;
	private volatile boolean visible;
	private volatile boolean closed;
	private volatile boolean finished;
	private Displayable current;
	private LcdUiBridge.Capture currentUiCapture;
	private String currentUiFingerprint;
	private long uiRevision;
	private ExternalVideoOutput videoOutput;
	private String appName = "J2ME";
	private final Runnable uiRefresh = new Runnable() {
		@Override
		public void run() {
			Displayable displayable = current;
			if (closed || finished || displayable == null || displayable instanceof Canvas) {
				return;
			}
			emitLcdUi(displayable, false);
			if (!closed && !finished && current == displayable) {
				mainHandler.postDelayed(this, LCDUI_REFRESH_INTERVAL_MS);
			}
		}
	};

	J2meSession(Activity activity, Callbacks callbacks) {
		if (activity == null) {
			throw new NullPointerException("Activity is required");
		}
		this.activity = activity;
		this.callbacks = callbacks == null ? new Callbacks() { } : callbacks;
	}

	public void setVideoOutput(ExternalVideoOutput output) {
		videoOutput = output;
		if (ContextHolder.getHost() == this) {
			ContextHolder.setExternalVideoOutput(output);
		}
	}

	public synchronized void start(File midletJar, File conversionDirectory, J2meConfig config) {
		if (closed) {
			throw new IllegalStateException("Session is closed");
		}
		if (state != State.IDLE) {
			throw new IllegalStateException("Session has already been started");
		}
		if (midletJar == null || conversionDirectory == null || config == null) {
			throw new NullPointerException(
					"MIDlet JAR, conversion directory, and configuration are required");
		}
		if (videoOutput == null) {
			throw new IllegalStateException(
					"ExternalVideoOutput must be set before starting an embedded session");
		}

		Application application = activity.getApplication();
		ContextHolder.setApplication(application);
		ContextHolder.attachHost(activity, this, videoOutput);
		setState(State.PREPARING);

		worker.execute(() -> {
			try {
				J2meInstaller.PreparedApp prepared = J2meInstaller.prepare(
						midletJar, conversionDirectory, config);
				mainHandler.post(() -> startPrepared(prepared));
			} catch (Throwable error) {
				mainHandler.post(() -> fail(error));
			}
		});
	}

	private void startPrepared(J2meInstaller.PreparedApp prepared) {
		if (closed) {
			return;
		}
		try {
			appName = prepared.name;
			MicroLoader loader = new EmbeddedMicroLoader(activity, prepared.directory.getAbsolutePath());
			loader.setConfiguration(prepared.profile);
			if (!loader.init()) {
				throw new IllegalStateException("MIDlet configuration was not created");
			}
			loader.applyConfiguration();
			LinkedHashMap<String, String> midlets = loader.loadMIDletList();
			if (midlets.isEmpty()) {
				throw new IllegalArgumentException("No MIDlet entry found in manifest");
			}
			String mainClass = midlets.keySet().iterator().next();
			MidletThread.create(loader, mainClass);
			setState(visible ? State.RUNNING : State.PAUSED);
			if (visible) {
				MidletThread.resumeApp();
			}
		} catch (Throwable error) {
			fail(error);
		}
	}

	public void resume() {
		visible = true;
		if (state == State.PAUSED || state == State.RUNNING) {
			MidletThread.resumeApp();
			setState(State.RUNNING);
		}
	}

	public void pause() {
		visible = false;
		if (state == State.RUNNING) {
			MidletThread.pauseApp();
			setState(State.PAUSED);
		}
	}

	public void stop() {
		if (state == State.STOPPED || state == State.STOPPING) {
			return;
		}
		State previousState = state;
		setState(State.STOPPING);
		if (previousState == State.PREPARING || previousState == State.IDLE
				|| previousState == State.FAILED) {
			finishSession();
			return;
		}
		MidletThread.destroyApp();
	}

	public boolean dispatchKeyEvent(KeyEvent event) {
		Canvas canvas = currentCanvas();
		if (canvas == null || event == null) {
			return false;
		}
		int keyCode = KeyMapper.convertAndroidKeyCode(event.getKeyCode(), event);
		if (keyCode == KeyMapper.KEY_OPTIONS_MENU) {
			if (event.getAction() == KeyEvent.ACTION_UP) {
				openOptionsMenu();
			}
			return true;
		}
		if (keyCode == 0) {
			return false;
		}
		switch (event.getAction()) {
			case KeyEvent.ACTION_DOWN:
				if (event.getRepeatCount() == 0) {
					canvas.postKeyPressed(keyCode);
				} else {
					canvas.postKeyRepeated(keyCode);
				}
				return true;
			case KeyEvent.ACTION_UP:
				canvas.postKeyReleased(keyCode);
				return true;
			default:
				return false;
		}
	}

	public void keyDown(int midpKeyCode) {
		Canvas canvas = currentCanvas();
		if (canvas != null) {
			canvas.postKeyPressed(midpKeyCode);
		}
	}

	public void keyUp(int midpKeyCode) {
		Canvas canvas = currentCanvas();
		if (canvas != null) {
			canvas.postKeyReleased(midpKeyCode);
		}
	}

	public void pointerDown(int pointer, int x, int y) {
		Canvas canvas = currentCanvas();
		if (canvas != null) {
			canvas.postPointerPressed(pointer, x, y);
		}
	}

	public void pointerMove(int pointer, int x, int y) {
		Canvas canvas = currentCanvas();
		if (canvas != null) {
			canvas.postPointerDragged(pointer, x, y);
		}
	}

	public void pointerUp(int pointer, int x, int y) {
		Canvas canvas = currentCanvas();
		if (canvas != null) {
			canvas.postPointerReleased(pointer, x, y);
		}
	}

	public void dispatchActivityResult(int requestCode, int resultCode, Intent data) {
		ContextHolder.notifyOnActivityResult(requestCode, resultCode, data);
	}

	/** Sends a host-rendered UI action back to the MIDP event queue. */
	public void dispatchUiAction(LcdUiAction action) {
		if (action == null) {
			throw new NullPointerException("LCDUI action is required");
		}
		mainHandler.post(() -> {
			if (LcdUiBridge.dispatch(currentUiCapture, action)) {
				Displayable displayable = current;
				LcdUiAction.Type type = action.getType();
				boolean directlyChangesState = type == LcdUiAction.Type.SELECT
						|| type == LcdUiAction.Type.SET_TEXT
						|| type == LcdUiAction.Type.SET_GAUGE
						|| type == LcdUiAction.Type.SET_DATE;
				if (directlyChangesState && displayable != null
						&& !(displayable instanceof Canvas)) {
					emitLcdUi(displayable, true);
				}
			}
		});
	}

	public State getState() {
		return state;
	}

	boolean isClosed() {
		return closed;
	}

	@Override
	public Activity getActivity() {
		return activity;
	}

	@Override
	public void setCurrent(Displayable displayable) {
		Displayable previous = current;
		current = displayable;
		mainHandler.post(() -> {
			closeLcdUi();
			if (previous != null) {
				if (previous instanceof Canvas && videoOutput != null) {
					((Canvas) previous).hideExternal();
				}
				previous.clearDisplayableView();
			}
			String title = displayable == null || displayable.getTitle() == null
					? appName : displayable.getTitle();
			if (displayable instanceof Canvas && videoOutput != null) {
				Canvas canvas = (Canvas) displayable;
				canvas.showExternal();
				videoOutput.onVideoSizeChanged(canvas.getWidth(), canvas.getHeight());
			} else if (displayable != null) {
				emitLcdUi(displayable, true);
				mainHandler.postDelayed(uiRefresh, LCDUI_REFRESH_INTERVAL_MS);
			}
			activity.setTitle(title);
			callbacks.onTitleChanged(title);
		});
	}

	@Override
	public Displayable getCurrent() {
		return current;
	}

	@Override
	public boolean isVisible() {
		return visible;
	}

	@Override
	public void finishSession() {
		mainHandler.post(() -> {
			if (finished) {
				return;
			}
			finished = true;
			visible = false;
			Displayable old = current;
			current = null;
			closeLcdUi();
			if (old != null) {
				if (old instanceof Canvas && videoOutput != null) {
					((Canvas) old).hideExternal();
				}
				old.clearDisplayableView();
			}
			ContextHolder.detachHost(this);
			setState(State.STOPPED);
			callbacks.onSessionFinished();
			J2meRuntime.release(this);
		});
	}

	@Override
	public void showExitConfirmation() {
		mainHandler.post(() -> callbacks.onExitRequested(this));
	}

	@Override
	public void openOptionsMenu() {
		mainHandler.post(callbacks::onOptionsMenuRequested);
	}

	@Override
	public String getAppName() {
		return appName;
	}

	@Override
	public boolean requestAlert(Alert alert) {
		return alert != null;
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		worker.shutdownNow();
		if (state == State.RUNNING || state == State.PAUSED) {
			setState(State.STOPPING);
			MidletThread.destroyApp();
		} else {
			finishSession();
		}
	}

	private Canvas currentCanvas() {
		Displayable displayable = current;
		return displayable instanceof Canvas ? (Canvas) displayable : null;
	}

	private void emitLcdUi(Displayable displayable, boolean force) {
		if (displayable != current || displayable instanceof Canvas) {
			return;
		}
		try {
			LcdUiBridge.Capture capture = LcdUiBridge.capture(displayable, uiRevision + 1);
			String fingerprint = capture.state.contentFingerprint();
			currentUiCapture = capture;
			if (force || !fingerprint.equals(currentUiFingerprint)) {
				uiRevision++;
				currentUiFingerprint = fingerprint;
				callbacks.onLcdUiStateChanged(capture.state);
			}
		} catch (IndexOutOfBoundsException ignored) {
			// A MIDlet mutated a Form/List while it was snapshotted; retry next tick.
		}
	}

	private void closeLcdUi() {
		mainHandler.removeCallbacks(uiRefresh);
		LcdUiBridge.Capture capture = currentUiCapture;
		currentUiCapture = null;
		currentUiFingerprint = null;
		if (capture != null) {
			callbacks.onLcdUiClosed(capture.state.getScreenId());
		}
	}

	private void fail(Throwable error) {
		if (closed) {
			return;
		}
		setState(State.FAILED);
		callbacks.onError(error);
	}

	private void setState(State newState) {
		state = newState;
		mainHandler.post(() -> callbacks.onStateChanged(newState));
	}
}
