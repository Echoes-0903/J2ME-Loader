/*
 * Copyright 2026
 * Licensed under the Apache License, Version 2.0.
 */
package javax.microedition.shell;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;

import javax.microedition.util.ContextHolder;

/** Entry point for embedding J2ME Loader in an ordinary Activity. */
public final class J2meRuntime {
	@SuppressLint("StaticFieldLeak") // Enforces one session per process; close() releases it.
	private static J2meSession activeSession;

	private J2meRuntime() {
	}

	/** Must be called before creating an embedded J2ME session. */
	public static void initialize(Application application) {
		if (application == null) {
			throw new NullPointerException("Application is required");
		}
		ContextHolder.setApplication(application);
	}

	public static synchronized J2meSession createSession(Activity activity,
			J2meSession.Callbacks callbacks) {
		if (activeSession != null && !activeSession.isClosed()) {
			throw new IllegalStateException("Only one J2ME session may be active in a process");
		}
		activeSession = new J2meSession(activity, callbacks);
		return activeSession;
	}

	static synchronized void release(J2meSession session) {
		if (activeSession == session) {
			activeSession = null;
		}
	}
}
