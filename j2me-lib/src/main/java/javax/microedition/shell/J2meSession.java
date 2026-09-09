/*
 * Copyright 2026
 * Licensed under the Apache License, Version 2.0.
 */
package javax.microedition.shell;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.event.RunnableEvent;
import javax.microedition.lcdui.keyboard.KeyMapper;
import javax.microedition.media.MediaRuntimeAudio;
import javax.microedition.util.ContextHolder;

/** A single embedded MIDlet session owned by an ordinary Android Activity. */
public final class J2meSession implements J2meHost, AutoCloseable {
	private static final long LCDUI_REFRESH_INTERVAL_MS = 100;
	private static final long LIFECYCLE_TIMEOUT_SECONDS = 5;

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

		/**
		 * Main-thread vibration request: positive milliseconds replace the current effect;
		 * zero cancels it. The default is a no-op, never an Android vibrator fallback.
		 */
		default void onVibrationRequested(int durationMillis) {
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

		/**
		 * Runs synchronously after one visible Canvas bitmap is complete and before it is forwarded to
		 * the external video output. Implementations may update input for the next frame, but must not
		 * wait for the Android main thread or perform lifecycle work.
		 */
		default void onFrameCallback(long sequence) {
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
	private final ExecutorService lifecycleWorker = Executors.newSingleThreadExecutor(r ->
			new Thread(r, "J2meLifecycle"));
	private final SessionLifecycleGate lifecycleGate = new SessionLifecycleGate();
	private final AtomicLong frameCallbackSequence = new AtomicLong();
	private volatile boolean frameCallbackEnabled;
	private volatile boolean midletReady;
	// Only the serial lifecycle worker changes the externally shown Canvas.
	private Canvas shownCanvas;
	// These maps are accessed only inside MIDP events (also serialized in ImmediateMode).
	private final Map<Integer, Canvas> pressedKeys = new LinkedHashMap<>();
	private final Map<Integer, PointerPress> pressedPointers = new LinkedHashMap<>();
	private final ExternalVideoOutput gatedVideoOutput = new ExternalVideoOutput() {
		@Override public void onFrame(Bitmap bitmap) {
			ExternalVideoOutput output = videoOutput;
			// Never wait here: Canvas calls this while holding its buffer lock.
			if (!visible || !lifecycleGate.acceptsOutput()) return;
			if (frameCallbackEnabled) {
				try {
					callbacks.onFrameCallback(frameCallbackSequence.incrementAndGet());
				} catch (Throwable error) {
					frameCallbackEnabled = false;
					mainHandler.post(() -> callbacks.onError(error));
				}
			}
			if (output != null) output.onFrame(bitmap);
		}
		@Override public void onVideoSizeChanged(int width, int height) {
			ExternalVideoOutput output = videoOutput;
			if (output != null && !closed && !finished) output.onVideoSizeChanged(width, height);
		}
	};

	private volatile State state = State.IDLE;
	private volatile boolean visible;
	private volatile boolean closed;
	private volatile boolean finished;
	private volatile Displayable current;
	private LcdUiBridge.Capture currentUiCapture;
	private String currentUiFingerprint;
	private long uiRevision;
	private volatile ExternalVideoOutput videoOutput;
	private String appName = "J2ME";
	private final Runnable uiRefresh = new Runnable() {
		@Override
		public void run() {
			Displayable displayable = current;
			if (!lifecycleGate.acceptsOutput() || displayable == null || displayable instanceof Canvas) {
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
			ContextHolder.setExternalVideoOutput(gatedVideoOutput);
		}
	}

	/** Enables the optional completed-Canvas frame callback. Disabled by default. */
	public void setFrameCallbackEnable(boolean enabled) {
		frameCallbackEnabled = enabled;
	}

	/** Enables or mutes all media players owned by the embedded MIDlet process. */
	public void setAudioEnabled(boolean enabled) {
		MediaRuntimeAudio.setHostMuted(!enabled);
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
		frameCallbackSequence.set(0L);

		Application application = activity.getApplication();
		ContextHolder.setApplication(application);
		ContextHolder.attachHost(activity, this, gatedVideoOutput);
		MediaRuntimeAudio.setHostPaused(true);
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
		if (closed || finished || state != State.PREPARING) {
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
			midletReady = true;
			requestLifecycle();
		} catch (Throwable error) {
			fail(error);
		}
	}

	public synchronized void resume() {
		if (isTerminal()) return;
		visible = true;
		requestLifecycle();
	}

	public synchronized void pause() {
		if (isTerminal()) return;
		visible = false;
		requestLifecycle();
	}

	private synchronized void requestLifecycle() {
		requestLifecycle(visible);
	}

	private synchronized void requestLifecycle(boolean resume) {
		long request = lifecycleGate.request(resume);
		if (request < 0) return;
		if (!resume) cancelVibration();
		if (!midletReady) return;
		executeLifecycle(() -> {
			if (!lifecycleGate.isCurrent(request)) return;
			try {
				// Keep audio held until both MIDlet and Canvas callbacks have completed.
				MediaRuntimeAudio.setHostPaused(true);
				if (!resume) {
					releaseInputs();
					reconcileCanvas(false);
				}
				CountDownLatch completed = new CountDownLatch(1);
				boolean[] paused = {true};
				Throwable[] failure = {null};
				MidletThread.requestLifecycle(this, !resume, (actualPaused, error) -> {
					paused[0] = actualPaused;
					failure[0] = error;
					completed.countDown();
				});
				awaitCallback(completed);
				if (!lifecycleGate.isCurrent(request)) return;
				if (failure[0] != null) throw new IllegalStateException("MIDlet lifecycle failed", failure[0]);
				boolean resumed = resume && visible && !paused[0];
				reconcileCanvas(resumed);
				if (!lifecycleGate.complete(request, resumed)) return;
				MediaRuntimeAudio.setHostPaused(!resumed);
				State settled = resumed ? State.RUNNING : State.PAUSED;
				state = settled;
				mainHandler.post(() -> {
					if (lifecycleGate.isCurrent(request) && state == settled) {
						callbacks.onStateChanged(settled);
					}
				});
				if (resumed) {
					Canvas canvas = currentCanvas();
					if (canvas != null) canvas.repaint();
					mainHandler.post(() -> {
						mainHandler.removeCallbacks(uiRefresh);
						uiRefresh.run();
					});
				}
			} catch (Exception error) {
				if (error instanceof InterruptedException) Thread.currentThread().interrupt();
				if (lifecycleGate.isCurrent(request)) mainHandler.post(() -> {
					if (lifecycleGate.isCurrent(request)) fail(error);
				});
			}
		});
	}

	private void reconcileCanvas(boolean active) throws InterruptedException, TimeoutException {
		Canvas next = active && visible ? currentCanvas() : null;
		if (shownCanvas == next) return;
		releaseInputs();
		if (shownCanvas != null) shownCanvas.hideExternal();
		shownCanvas = next;
		if (next != null) next.showExternal();
		CountDownLatch completed = new CountDownLatch(1);
		Display.postEvent(RunnableEvent.getInstance(completed::countDown));
		awaitCallback(completed);
	}

	private static void awaitCallback(CountDownLatch completed)
			throws InterruptedException, TimeoutException {
		if (!completed.await(LIFECYCLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
			throw new TimeoutException("J2ME lifecycle callback did not complete within 5 seconds");
		}
	}

	private void executeLifecycle(Runnable operation) {
		try {
			lifecycleWorker.execute(operation);
		} catch (RejectedExecutionException error) {
			if (!isTerminal()) throw error;
		}
	}

	private boolean isTerminal() {
		return closed || finished || state == State.STOPPING || state == State.STOPPED
				|| state == State.FAILED;
	}

	public synchronized void stop() {
		if (state == State.STOPPED || state == State.STOPPING) {
			return;
		}
		visible = false;
		lifecycleGate.close();
		cancelVibration();
		setState(State.STOPPING);
		if (!midletReady) {
			finishSession();
			return;
		}
		stopMidlet();
	}

	private void stopMidlet() {
		executeLifecycle(() -> {
			try {
				MediaRuntimeAudio.setHostPaused(true);
				reconcileCanvas(false);
			} catch (Exception ignored) {
				// Teardown must still reach destroyApp if a visibility callback failed.
			} finally {
				MidletThread.destroyApp();
			}
		});
	}

	public boolean dispatchKeyEvent(KeyEvent event) {
		Canvas canvas = currentCanvas();
		if (canvas == null || event == null || !lifecycleGate.acceptsOutput()) {
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
					keyDown(keyCode);
				} else {
					postInput(() -> {
						if (pressedKeys.get(keyCode) == canvas) canvas.postKeyRepeated(keyCode);
					});
				}
				return true;
			case KeyEvent.ACTION_UP:
				keyUp(keyCode);
				return true;
			default:
				return false;
		}
	}

	public void keyDown(int midpKeyCode) {
		postInput(() -> {
			Canvas canvas = currentCanvas();
			if (canvas != null && !pressedKeys.containsKey(midpKeyCode)) {
				pressedKeys.put(midpKeyCode, canvas);
				canvas.postKeyPressed(midpKeyCode);
			}
		});
	}

	public void keyUp(int midpKeyCode) {
		Display.postEvent(RunnableEvent.getInstance(() -> {
			Canvas canvas = pressedKeys.remove(midpKeyCode);
			if (canvas != null) canvas.postKeyReleased(midpKeyCode);
		}));
	}

	public void pointerDown(int pointer, int x, int y) {
		postInput(() -> {
			Canvas canvas = currentCanvas();
			if (canvas != null && !pressedPointers.containsKey(pointer)) {
				pressedPointers.put(pointer, new PointerPress(canvas, x, y));
				canvas.postPointerPressed(pointer, x, y);
			}
		});
	}

	public void pointerMove(int pointer, int x, int y) {
		postInput(() -> {
			PointerPress press = pressedPointers.get(pointer);
			if (press != null) {
				press.x = x;
				press.y = y;
				press.canvas.postPointerDragged(pointer, x, y);
			}
		});
	}

	public void pointerUp(int pointer, int x, int y) {
		Display.postEvent(RunnableEvent.getInstance(() -> {
			PointerPress press = pressedPointers.remove(pointer);
			if (press != null) press.canvas.postPointerReleased(pointer, x, y);
		}));
	}

	private void postInput(Runnable input) {
		if (!lifecycleGate.acceptsOutput()) return;
		long revision = lifecycleGate.revision();
		Displayable target = current;
		Display.postEvent(RunnableEvent.getInstance(() -> {
			if (lifecycleGate.isCurrent(revision) && lifecycleGate.acceptsOutput()
					&& current == target) input.run();
		}));
	}

	private void releaseInputs() throws InterruptedException, TimeoutException {
		CountDownLatch completed = new CountDownLatch(1);
		Display.postEvent(RunnableEvent.getInstance(() -> {
			for (Map.Entry<Integer, Canvas> key : pressedKeys.entrySet()) {
				key.getValue().postKeyReleased(key.getKey());
			}
			pressedKeys.clear();
			for (Map.Entry<Integer, PointerPress> pointer : pressedPointers.entrySet()) {
				PointerPress press = pointer.getValue();
				press.canvas.postPointerReleased(pointer.getKey(), press.x, press.y);
			}
			pressedPointers.clear();
			Display.postEvent(RunnableEvent.getInstance(completed::countDown));
		}));
		awaitCallback(completed);
	}

	private static final class PointerPress {
		final Canvas canvas;
		int x, y;
		PointerPress(Canvas canvas, int x, int y) {
			this.canvas = canvas;
			this.x = x;
			this.y = y;
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
		long revision = lifecycleGate.revision();
		mainHandler.post(() -> {
			if (!lifecycleGate.isCurrent(revision) || !lifecycleGate.acceptsOutput()) return;
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
		if (isTerminal()) return;
		Displayable previous = current;
		current = displayable;
		mainHandler.post(() -> {
			if (isTerminal() || current != displayable) return;
			closeLcdUi();
			if (previous != null) {
				previous.clearDisplayableView();
			}
			String title = displayable == null || displayable.getTitle() == null
					? appName : displayable.getTitle();
			if (displayable instanceof Canvas && videoOutput != null) {
				Canvas canvas = (Canvas) displayable;
				videoOutput.onVideoSizeChanged(canvas.getWidth(), canvas.getHeight());
			} else if (displayable != null) {
				emitLcdUi(displayable, true);
				mainHandler.postDelayed(uiRefresh, LCDUI_REFRESH_INTERVAL_MS);
			}
			activity.setTitle(title);
			callbacks.onTitleChanged(title);
		});
		executeLifecycle(() -> {
			if (isTerminal()) return;
			try {
				reconcileCanvas(lifecycleGate.acceptsOutput());
			} catch (Exception error) {
				if (!isTerminal()) mainHandler.post(() -> fail(error));
			}
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
	public synchronized void onMidletPaused() {
		if (!isTerminal()) requestLifecycle(false);
	}

	@Override
	public synchronized boolean requestMidletResume() {
		if (visible && !isTerminal()) requestLifecycle(true);
		return true;
	}

	@Override
	public synchronized boolean requestVibration(int durationMillis) {
		if (durationMillis < 0) throw new IllegalStateException("Negative vibration duration");
		// Always consume embedded requests, even when inactive or using the empty callback.
		if (isTerminal()) return true;
		if (durationMillis == 0) {
			cancelVibration();
			return true;
		}
		if (!visible || !lifecycleGate.acceptsOutput()) return true;
		long revision = lifecycleGate.revision();
		mainHandler.post(() -> {
			if (lifecycleGate.isCurrent(revision) && visible && lifecycleGate.acceptsOutput()) {
				callbacks.onVibrationRequested(durationMillis);
			}
		});
		return true;
	}

	private void cancelVibration() {
		mainHandler.post(() -> {
			// finishSession sends a final cancellation before releasing this session.
			if (!finished) callbacks.onVibrationRequested(0);
		});
	}

	@Override
	public void finishSession() {
		mainHandler.post(() -> {
			if (finished) {
				return;
			}
			finished = true;
			visible = false;
			lifecycleGate.close();
			callbacks.onVibrationRequested(0);
			lifecycleWorker.shutdownNow();
			worker.shutdownNow();
			Displayable old = current;
			current = null;
			closeLcdUi();
			if (old != null) {
				old.clearDisplayableView();
			}
			ContextHolder.detachHost(this);
			MediaRuntimeAudio.closeHostPlayers();
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
	public synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		visible = false;
		lifecycleGate.close();
		cancelVibration();
		worker.shutdownNow();
		if (midletReady && state != State.STOPPED) {
			setState(State.STOPPING);
			stopMidlet();
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
		if (isTerminal()) {
			return;
		}
		lifecycleGate.close();
		visible = false;
		cancelVibration();
		MediaRuntimeAudio.setHostPaused(true);
		setState(State.FAILED);
		callbacks.onError(error);
	}

	private void setState(State newState) {
		state = newState;
		mainHandler.post(() -> {
			if (state == newState) callbacks.onStateChanged(newState);
		});
	}
}
