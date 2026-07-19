package javax.microedition.shell;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.microedition.midlet.MIDlet;

import ru.playsoftware.j2meloader.config.Config;
import ru.woesss.j2me.jar.Descriptor;

/** Embedded loader variant that does not require the launcher application's ACRA setup. */
final class EmbeddedMicroLoader extends MicroLoader {
	private final File appDirectory;

	EmbeddedMicroLoader(Context context, String appPath) {
		super(context, appPath);
		appDirectory = new File(appPath);
	}

	@Override
	LinkedHashMap<String, String> loadMIDletList() throws IOException {
		Descriptor descriptor = new Descriptor(
				new File(appDirectory, Config.MIDLET_MANIFEST_FILE), false);
		Map<String, String> attributes = descriptor.getAttrs();
		MIDlet.initProps(attributes);
		LinkedHashMap<String, String> midlets = new LinkedHashMap<>();
		for (int index = 1; ; index++) {
			String value = attributes.get("MIDlet-" + index);
			if (value == null) {
				break;
			}
			String className = value.substring(value.lastIndexOf(',') + 1).trim();
			String title = value.substring(0, value.indexOf(',')).trim();
			midlets.put(className, title);
		}
		return midlets;
	}
}
