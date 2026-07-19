/*
 * Copyright 2026
 * Licensed under the Apache License, Version 2.0.
 */
package javax.microedition.shell;

import com.android.dx.command.dexer.Main;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.config.ProfileModel;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.woesss.j2me.jar.Descriptor;

final class J2meInstaller {
	private J2meInstaller() {
	}

	static PreparedApp prepare(File sourceJar, File conversionDirectory, J2meConfig config)
			throws Exception {
		if (!sourceJar.isFile()) {
			throw new IOException("MIDlet JAR does not exist: " + sourceJar);
		}
		if (conversionDirectory == null) {
			throw new NullPointerException("Conversion directory is required");
		}

		Descriptor descriptor = loadManifest(sourceJar);
		String appName = descriptor.getName();
		if (appName == null || appName.trim().isEmpty()) {
			appName = sourceJar.getName();
		}

		File dexFile = output(conversionDirectory, Config.MIDLET_DEX_FILE);
		File resourceJar = output(conversionDirectory, Config.MIDLET_RES_FILE);
		File manifestFile = output(conversionDirectory, Config.MIDLET_MANIFEST_FILE);

		if (!dexFile.isFile() || !resourceJar.isFile() || !manifestFile.isFile()) {
			convert(sourceJar, descriptor, conversionDirectory);
		}

		ProfileModel profile = new ProfileModel(conversionDirectory);
		config.applyTo(profile);
		return new PreparedApp(appName, conversionDirectory, profile);
	}

	private static void convert(File sourceJar, Descriptor descriptor, File conversionDirectory)
			throws Exception {
		if (!conversionDirectory.isDirectory() && !conversionDirectory.mkdirs()) {
			throw new IOException("Cannot create conversion directory: " + conversionDirectory);
		}
		File dexFile = output(conversionDirectory, Config.MIDLET_DEX_FILE);
		File resourceJar = output(conversionDirectory, Config.MIDLET_RES_FILE);
		File manifestFile = output(conversionDirectory, Config.MIDLET_MANIFEST_FILE);
		File tempDex = new File(conversionDirectory, ".converted.tmp.dex");
		File tempResource = new File(conversionDirectory, ".res.tmp.jar");
		File tempManifest = new File(conversionDirectory, ".manifest.tmp.conf");
		deleteIfExists(tempDex);
		deleteIfExists(tempResource);
		deleteIfExists(tempManifest);

		try {
			Main.main(new String[]{
					"--no-optimize",
					"--core-library",
					"--output=" + tempDex.getAbsolutePath(),
					sourceJar.getAbsolutePath()
			});
			FileUtils.copyFileUsingChannel(sourceJar, tempResource);
			descriptor.writeTo(tempManifest);
			publish(tempDex, dexFile);
			publish(tempResource, resourceJar);
			publish(tempManifest, manifestFile);
		} finally {
			deleteIfExists(tempDex);
			deleteIfExists(tempResource);
			deleteIfExists(tempManifest);
		}
	}

	private static Descriptor loadManifest(File sourceJar) throws IOException {
		try (ZipFile zipFile = new ZipFile(sourceJar)) {
			ZipEntry entry = zipFile.getEntry("META-INF/MANIFEST.MF");
			if (entry == null) {
				throw new IOException("MIDlet JAR has no META-INF/MANIFEST.MF");
			}
			try (InputStream input = zipFile.getInputStream(entry);
				 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
				byte[] buffer = new byte[4096];
				int count;
				while ((count = input.read(buffer)) != -1) {
					output.write(buffer, 0, count);
				}
				return new Descriptor(output.toString(), false);
			}
		}
	}

	private static File output(File directory, String relativePath) {
		return new File(directory, trimLeadingSlash(relativePath));
	}

	private static void publish(File source, File target) throws IOException {
		if (target.exists() && !target.delete()) {
			throw new IOException("Cannot replace conversion output: " + target);
		}
		if (!source.renameTo(target)) {
			FileUtils.copyFileUsingChannel(source, target);
		}
	}

	private static void deleteIfExists(File file) throws IOException {
		if (file.exists() && !file.delete()) {
			throw new IOException("Cannot delete temporary conversion output: " + file);
		}
	}

	private static String trimLeadingSlash(String path) {
		return path.startsWith("/") ? path.substring(1) : path;
	}

	static final class PreparedApp {
		final String name;
		final File directory;
		final ProfileModel profile;

		PreparedApp(String name, File directory, ProfileModel profile) {
			this.name = name;
			this.directory = directory;
			this.profile = profile;
		}
	}
}
