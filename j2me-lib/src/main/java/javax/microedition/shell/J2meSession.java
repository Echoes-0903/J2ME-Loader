/*
 * Copyright 2026
 * Licensed under the Apache License, Version 2.0.
 */
package javax.microedition.shell;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;

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

		/** Gives a view-backed LCDUI screen to the host without attaching it to a container. */
		default void onShowView(String className, String title, View view) {
		}

		/** Tells the host to detach a previously supplied LCDUI view. */
		default void onHideView(View view) {
		}

		/** Gives a prepared MIDP alert dialog to the host; the host decides when to show it. */
		default void onShowDialog(Dialog dialog) {
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
	private View currentView;
	private ExternalVideoOutput videoOutput;
	private String appName = "J2ME";

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
			View previousView = currentView;
			currentView = null;
			if (previous != null) {
				if (previous instanceof Canvas && videoOutput != null) {
					((Canvas) previous).hideExternal();
				}
				if (previousView != null) {
					callbacks.onHideView(previousView);
				}
				previous.clearDisplayableView();
			}
			String title = displayable == null || displayable.getTitle() == null
					? appName : displayable.getTitle();
			if (displayable instanceof Canvas && videoOutput != null) {
				Canvas canvas = (Canvas) displayable;
				canvas.showExternal();
				videoOutput.onVideoSizeChanged(canvas.getWidth(), canvas.getHeight());
			} else if (displayable != null && !(displayable instanceof Alert)) {
				View view = displayable.getDisplayableView();
				currentView = view;
				callbacks.onShowView(displayable.getClass().getName(), title, view);
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
			View oldView = currentView;
			currentView = null;
			if (oldView != null) {
				callbacks.onHideView(oldView);
			}
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
		if (alert == null) {
			return false;
		}
		mainHandler.post(() -> callbacks.onShowDialog(alert.prepareDialog()));
		return true;
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
