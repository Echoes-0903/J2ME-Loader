/*
 * Copyright 2026
 * Licensed under the Apache License, Version 2.0.
 */
package javax.microedition.shell;

import android.app.Activity;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.overlay.OverlayView;

/** Host services needed by the MIDP runtime. The host does not have to extend MicroActivity. */
public interface J2meHost {
	Activity getActivity();

	void setCurrent(Displayable displayable);

	Displayable getCurrent();

	boolean isVisible();

	void finishSession();

	void showExitConfirmation();

	void openOptionsMenu();

	/** Standalone UI hosts may expose an overlay. Pure external-output hosts return null. */
	default OverlayView getOverlayView() {
		return null;
	}

	/** Lets embedded hosts receive an Alert dialog instead of having the runtime show it directly. */
	default boolean requestAlert(Alert alert) {
		return false;
	}

	String getAppName();

	/** Optional crash-report metadata used by the standalone launcher. */
	default String getCrashReportData() {
		return null;
	}

	/** Embedded hosts may leave crash-report metadata unsupported. */
	default void setCrashReportData(String data) {
	}

	/** Legacy standalone hosts may opt into their historical process-per-game shutdown. */
	default boolean terminateProcessOnExit() {
		return false;
	}

	/** Resolves launcher-managed cross-MIDlet requests. Embedded hosts may leave this unsupported. */
	default boolean requestMidletStart(String name, String vendor, String uid, String arguments)
			throws Exception {
		return false;
	}
}
