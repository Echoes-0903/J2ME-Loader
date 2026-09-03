/*
 *  Copyright 2020 Yury Kharchenko
 *  Copyright 2022-2023 Arman Jussupgaliyev
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package javax.microedition.shell;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.util.Log;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;
import javax.microedition.util.ContextHolder;

import androidx.annotation.NonNull;

import ru.playsoftware.j2meloader.config.Config;

public class MidletThread extends HandlerThread implements Handler.Callback {
	private static final String TAG = MidletThread.class.getName();
	private static final UncaughtExceptionHandler uncaughtExceptionHandler = (t, e) ->
			Log.e(TAG, "Error in thread: \"" + t + "\" after destroy app called", e);

	private static final int INIT = 0;
	private static final int START = 1;
	private static final int PAUSE = 2;
	private static final int DESTROY = 3;
	private static final int UNINITIALIZED = 0;
	private static final int STARTED = 1;
	private static final int PAUSED = 2;
	private static final int DESTROYED = 3;
	public static String[] startAfterDestroy;
	private static volatile MidletThread instance;
	private final MicroLoader microLoader;
	private final String mainClass;
	private MIDlet midlet;
	private final Handler handler;
	private volatile int state;

	private MidletThread(MicroLoader microLoader, String mainClass) {
		super("MidletMain");
		this.microLoader = microLoader;
		this.mainClass = mainClass;
		start();
		handler = new Handler(getLooper(), this);
		handler.obtainMessage(INIT).sendToTarget();
	}

	static void create(MicroLoader microLoader, String mainClass) {
		instance = new MidletThread(microLoader, mainClass);
	}

	public static void notifyDestroyed() {
		Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
		MidletThread thread = instance;
		instance = null;
		if (thread != null) {
			thread.state = DESTROYED;
			thread.quitSafely();
		}
		J2meHost host = ContextHolder.getHost();
		if (host != null) {
			host.finishSession();
		}
		String[] next = startAfterDestroy;
		startAfterDestroy = null;
		if (next != null && host != null) {
			Config.startApp(host.getActivity(), next[0], next[1], false, next[2]);
		}
		if (host == null || host.terminateProcessOnExit()) {
			Process.killProcess(Process.myPid());
		}
	}

	public static void notifyPaused() {
		if (instance != null) {
			instance.state = PAUSED;
			J2meHost host = ContextHolder.getHost();
			if (host != null) host.onMidletPaused();
		}
	}

	static void pauseApp() {
		if (instance != null)
			instance.handler.obtainMessage(PAUSE).sendToTarget();
	}

	public static void resumeApp() {
		J2meHost host = ContextHolder.getHost();
		if (host != null && host.requestMidletResume()) return;
		if (instance != null && host != null && host.isVisible())
			instance.handler.obtainMessage(START).sendToTarget();
	}

	/** Embedded hosts receive completion after the game callback, not after message enqueue. */
	interface LifecycleCallback {
		void complete(boolean paused, Throwable error);
	}

	static void requestLifecycle(J2meHost owner, boolean paused, LifecycleCallback completion) {
		MidletThread thread = instance;
		if (thread == null) {
			completion.complete(true, new IllegalStateException("MIDlet is not initialized"));
			return;
		}
		if (!thread.handler.post(() -> {
			if (instance != thread || ContextHolder.getHost() != owner) {
				completion.complete(true, new IllegalStateException("MIDlet session is no longer active"));
				return;
			}
			try {
				thread.handleMessage(thread.handler.obtainMessage(paused ? PAUSE : START));
				completion.complete(thread.state != STARTED, null);
			} catch (Throwable error) {
				completion.complete(true, error);
			}
		})) {
			completion.complete(true, new IllegalStateException("MIDlet lifecycle thread has stopped"));
		}
	}

	static void destroyApp() {
		Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
		J2meHost host = ContextHolder.getHost();
		if (host == null || host.terminateProcessOnExit()) {
			new Thread(() -> {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				Process.killProcess(Process.myPid());
			}, "ForceDestroyTimer").start();
		}
		if (host != null) {
			Displayable current = host.getCurrent();
			if (current instanceof Canvas) {
				Canvas canvas = (Canvas) current;
				canvas.postKeyPressed(Canvas.KEY_END);
				canvas.postKeyReleased(Canvas.KEY_END);
			}
		}
		MidletThread thread = instance;
		if (thread != null) {
			if (!thread.handler.sendMessage(thread.handler.obtainMessage(DESTROY))) {
				notifyDestroyed();
			}
		} else if (host != null) {
			host.finishSession();
		}
	}

	@Override
	public boolean handleMessage(@NonNull Message msg) {
		switch (msg.what) {
			case INIT:
				if (state != UNINITIALIZED) {
					break;
				}
				try {
					midlet = microLoader.loadMIDlet(this.mainClass);
					state = PAUSED;
				} catch (Throwable t) {
					throw new RuntimeException("Init midlet failed", t);
				}
				break;
			case START:
				if (state != PAUSED) {
					break;
				}
				try {
					state = STARTED;
					midlet.startApp();
				} catch (MIDletStateChangeException e) {
					state = PAUSED;
					Log.w(TAG, "Midlet doesn't want to start!", e);
				} catch (Throwable t) {
					state = DESTROYED;
					throw new RuntimeException("Failed startApp", t);
				}
				break;
			case PAUSE:
				if (state != STARTED) {
					break;
				}
				try {
					midlet.pauseApp();
					state = PAUSED;
				} catch (Throwable t) {
					state = DESTROYED;
					try {
						midlet.destroyApp(true);
					} catch (MIDletStateChangeException ignored) {}
					throw new RuntimeException("Filed pauseApp", t);
				}
				break;
			case DESTROY:
				if (state == DESTROYED) {
					notifyDestroyed();
					break;
				}
				state = DESTROYED;
				try {
					midlet.destroyApp(true);
				} catch (MIDletStateChangeException e) {
					Log.w(TAG, "Midlet didn't want to die!", e);
				} catch (Throwable t) {
					Log.e(TAG, "Filed destroyApp:", t);
				}
				notifyDestroyed();
				break;
		}
		return true;
	}
}
