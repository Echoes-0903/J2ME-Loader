/*
 * Copyright 2026
 * Licensed under the Apache License, Version 2.0.
 */
package javax.microedition.shell;

import android.graphics.Bitmap;

/**
 * Receives complete LCD frames when J2ME Loader is embedded in another renderer.
 *
 * <p>The bitmap is owned by J2ME Loader and is only guaranteed to remain unchanged for the
 * duration of {@link #onFrame(Bitmap)}. Implementations that retain a frame must copy it.</p>
 */
public interface ExternalVideoOutput {
	void onFrame(Bitmap bitmap);

	default void onVideoSizeChanged(int width, int height) {
	}
}
