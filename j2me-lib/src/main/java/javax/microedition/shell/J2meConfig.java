/*
 * Copyright 2026
 * Licensed under the Apache License, Version 2.0.
 */
package javax.microedition.shell;

import android.util.SparseIntArray;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.SerializedName;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import ru.playsoftware.j2meloader.config.ProfileModel;

/**
 * Per-session configuration expressed using the same keys as J2ME Loader profile JSON files.
 * Canonical serialized names, Java field names, and case-insensitive forms are accepted.
 */
public final class J2meConfig {
	private static final Gson GSON = new GsonBuilder().serializeNulls().create();
	private static final Map<String, FieldBinding> BINDINGS;
	private static final Map<String, FieldBinding> ALIASES;
	private static final Set<String> SUPPORTED_KEYS;
	private static final Set<String> HOST_CONTROLLED_KEYS = Collections.unmodifiableSet(
			new LinkedHashSet<>(Arrays.asList(
					Keys.GRAPHICS_MODE,
					Keys.SHADER,
					Keys.PARALLEL_REDRAW_SCREEN,
					Keys.SHOW_FPS)));

	static {
		LinkedHashMap<String, FieldBinding> bindings = new LinkedHashMap<>();
		LinkedHashMap<String, FieldBinding> aliases = new LinkedHashMap<>();
		for (Field field : ProfileModel.class.getFields()) {
			SerializedName serializedName = field.getAnnotation(SerializedName.class);
			if (serializedName == null || Modifier.isStatic(field.getModifiers())) {
				continue;
			}
			FieldBinding binding = new FieldBinding(serializedName.value(), field);
			bindings.put(binding.key, binding);
			registerAlias(aliases, binding.key, binding);
			registerAlias(aliases, field.getName(), binding);
			for (String alternate : serializedName.alternate()) {
				registerAlias(aliases, alternate, binding);
			}
		}
		BINDINGS = Collections.unmodifiableMap(bindings);
		ALIASES = Collections.unmodifiableMap(aliases);
		SUPPORTED_KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(bindings.keySet()));
	}

	private final LinkedHashMap<String, JsonElement> values = new LinkedHashMap<>();

	public J2meConfig() {
		// Preserve the defaults of the original embedding facade.
		setScreenSize(240, 320);
		setTouchInput(true);
		setShowKeyboard(false);
		setFpsLimit(0);
	}

	/** Sets one profile value. Values may be typed objects or strings from a properties parser. */
	public J2meConfig set(String key, Object value) {
		LinkedHashMap<String, JsonElement> staged = new LinkedHashMap<>();
		stageValue(staged, key, value);
		values.putAll(staged);
		return this;
	}

	/**
	 * Applies a group of profile values atomically. If any key or value is invalid, no values from
	 * this call are applied. This also accepts {@link java.util.Properties}.
	 */
	public J2meConfig setAll(Map<?, ?> config) {
		if (config == null) {
			throw new NullPointerException("J2ME configuration map is required");
		}
		LinkedHashMap<String, JsonElement> staged = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : config.entrySet()) {
			if (!(entry.getKey() instanceof String)) {
				throw new IllegalArgumentException("J2ME configuration keys must be strings");
			}
			stageValue(staged, (String) entry.getKey(), entry.getValue());
		}
		values.putAll(staged);
		return this;
	}

	/** Applies a JSON object using the standard J2ME Loader profile keys. */
	public J2meConfig setJson(String json) {
		if (json == null) {
			throw new NullPointerException("J2ME configuration JSON is required");
		}
		JsonElement root;
		try {
			root = JsonParser.parseString(json);
		} catch (JsonParseException error) {
			throw new IllegalArgumentException("Invalid J2ME configuration JSON", error);
		}
		if (!root.isJsonObject()) {
			throw new IllegalArgumentException("J2ME configuration JSON must be an object");
		}
		LinkedHashMap<String, JsonElement> staged = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
			stageValue(staged, entry.getKey(), entry.getValue());
		}
		values.putAll(staged);
		return this;
	}

	/** Returns the typed value of an explicitly configured key, or {@code null} if absent. */
	public Object get(String key) {
		FieldBinding binding = resolveBinding(key);
		JsonElement value = values.get(binding.key);
		return value == null ? null : deserializeValue(binding, value);
	}

	public boolean contains(String key) {
		return values.containsKey(resolveBinding(key).key);
	}

	public J2meConfig remove(String key) {
		values.remove(resolveBinding(key).key);
		return this;
	}

	public J2meConfig clear() {
		values.clear();
		return this;
	}

	/** Returns a detached map with canonical profile keys and typed values. */
	public Map<String, Object> asMap() {
		LinkedHashMap<String, Object> result = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
			result.put(entry.getKey(), deserializeValue(BINDINGS.get(entry.getKey()), entry.getValue()));
		}
		return Collections.unmodifiableMap(result);
	}

	/** Serializes only values held by this override object. */
	public String toJson() {
		return GSON.toJson(toJsonObject(values));
	}

	/** Returns all keys currently exposed by the bundled upstream {@link ProfileModel}. */
	public static Set<String> getSupportedKeys() {
		return SUPPORTED_KEYS;
	}

	/**
	 * Keys accepted for profile compatibility but overridden by the external-video host contract.
	 */
	public static Set<String> getHostControlledKeys() {
		return HOST_CONTROLLED_KEYS;
	}

	public J2meConfig setScreenSize(int width, int height) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("Screen dimensions must be positive");
		}
		set(Keys.SCREEN_WIDTH, width);
		set(Keys.SCREEN_HEIGHT, height);
		return this;
	}

	public J2meConfig setTouchInput(boolean enabled) {
		return set(Keys.TOUCH_INPUT, enabled);
	}

	public J2meConfig setShowKeyboard(boolean enabled) {
		return set(Keys.SHOW_KEYBOARD, enabled);
	}

	public J2meConfig setFpsLimit(int fps) {
		if (fps < 0) {
			throw new IllegalArgumentException("FPS limit must be zero or positive");
		}
		return set(Keys.FPS_LIMIT, fps);
	}

	void applyTo(ProfileModel profile) {
		ProfileModel patch = deserializePatch(values);
		for (String key : values.keySet()) {
			Field field = BINDINGS.get(key).field;
			try {
				field.set(profile, field.get(patch));
			} catch (IllegalAccessException error) {
				throw new IllegalStateException("Unable to apply J2ME configuration key " + key, error);
			}
		}

		// External output consumes the software framebuffer; the host owns GL/Vulkan and FPS UI.
		profile.graphicsMode = 0;
		profile.parallelRedrawScreen = false;
		profile.shader = null;
		profile.showFps = false;
	}

	private static void stageValue(Map<String, JsonElement> staged, String key, Object value) {
		FieldBinding binding = resolveBinding(key);
		JsonElement normalized = normalizeValue(binding, value);
		Object parsed = deserializeValue(binding, normalized);
		validateValue(binding.key, parsed);
		staged.put(binding.key, normalized);
	}

	private static JsonElement normalizeValue(FieldBinding binding, Object value) {
		Class<?> type = binding.field.getType();
		if (value == null || value instanceof JsonElement && ((JsonElement) value).isJsonNull()) {
			if (type.isPrimitive()) {
				throw new IllegalArgumentException(binding.key + " cannot be null");
			}
			return com.google.gson.JsonNull.INSTANCE;
		}
		if (value instanceof JsonElement) {
			JsonElement jsonValue = (JsonElement) value;
			if (jsonValue.isJsonPrimitive() && jsonValue.getAsJsonPrimitive().isString()) {
				value = jsonValue.getAsString();
			} else {
				return JsonParser.parseString(jsonValue.toString());
			}
		}
		if (value instanceof SparseIntArray) {
			SparseIntArray array = (SparseIntArray) value;
			JsonObject object = new JsonObject();
			for (int i = 0; i < array.size(); i++) {
				object.addProperty(Integer.toString(array.keyAt(i)), array.valueAt(i));
			}
			return object;
		}
		if (!(value instanceof String)) {
			return GSON.toJsonTree(value);
		}

		String string = (String) value;
		String trimmed = string.trim();
		try {
			if (type == int.class || type == Integer.class) {
				return new JsonPrimitive(parseInteger(trimmed));
			}
			if (type == long.class || type == Long.class) {
				return new JsonPrimitive(parseLong(trimmed));
			}
			if (type == float.class || type == Float.class) {
				return new JsonPrimitive(Float.parseFloat(trimmed));
			}
			if (type == double.class || type == Double.class) {
				return new JsonPrimitive(Double.parseDouble(trimmed));
			}
		} catch (NumberFormatException error) {
			throw new IllegalArgumentException("Invalid numeric value for " + binding.key + ": " + string,
					error);
		}
		if (type == boolean.class || type == Boolean.class) {
			if ("true".equalsIgnoreCase(trimmed) || "1".equals(trimmed)) {
				return new JsonPrimitive(true);
			}
			if ("false".equalsIgnoreCase(trimmed) || "0".equals(trimmed)) {
				return new JsonPrimitive(false);
			}
			throw new IllegalArgumentException("Invalid boolean value for " + binding.key + ": " + string);
		}
		if (type != String.class && (trimmed.startsWith("{") || "null".equals(trimmed))) {
			try {
				return JsonParser.parseString(trimmed);
			} catch (JsonParseException error) {
				throw new IllegalArgumentException("Invalid JSON value for " + binding.key, error);
			}
		}
		// SparseIntArrayAdapter deliberately accepts the upstream legacy JSON-array string format.
		return new JsonPrimitive(string);
	}

	private static int parseInteger(String value) {
		return hasExplicitHexPrefix(value) ? Integer.decode(value) : Integer.parseInt(value, 10);
	}

	private static long parseLong(String value) {
		return hasExplicitHexPrefix(value) ? Long.decode(value) : Long.parseLong(value, 10);
	}

	private static boolean hasExplicitHexPrefix(String value) {
		int offset = value.startsWith("+") || value.startsWith("-") ? 1 : 0;
		return value.regionMatches(true, offset, "0x", 0, 2)
				|| value.regionMatches(offset, "#", 0, 1);
	}

	private static Object deserializeValue(FieldBinding binding, JsonElement value) {
		JsonObject object = new JsonObject();
		object.add(binding.key, value);
		ProfileModel parsed;
		try {
			parsed = GSON.fromJson(object, ProfileModel.class);
		} catch (RuntimeException error) {
			throw new IllegalArgumentException("Invalid value for J2ME configuration key " + binding.key,
					error);
		}
		try {
			return binding.field.get(parsed);
		} catch (IllegalAccessException error) {
			throw new IllegalStateException("Unable to read J2ME configuration key " + binding.key, error);
		}
	}

	private static ProfileModel deserializePatch(Map<String, JsonElement> values) {
		try {
			return GSON.fromJson(toJsonObject(values), ProfileModel.class);
		} catch (RuntimeException error) {
			throw new IllegalArgumentException("Invalid J2ME configuration", error);
		}
	}

	private static JsonObject toJsonObject(Map<String, JsonElement> values) {
		JsonObject object = new JsonObject();
		for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
			object.add(entry.getKey(), entry.getValue());
		}
		return object;
	}

	private static void validateValue(String key, Object value) {
		if ((Keys.SCREEN_WIDTH.equals(key) || Keys.SCREEN_HEIGHT.equals(key))
				&& (Integer) value <= 0) {
			throw new IllegalArgumentException(key + " must be positive");
		}
		if (Keys.FPS_LIMIT.equals(key) && (Integer) value < 0) {
			throw new IllegalArgumentException(Keys.FPS_LIMIT + " must be zero or positive");
		}
	}

	private static FieldBinding resolveBinding(String key) {
		if (key == null) {
			throw new NullPointerException("J2ME configuration key is required");
		}
		String normalized = key.trim();
		FieldBinding binding = ALIASES.get(normalized);
		if (binding == null) {
			binding = ALIASES.get(normalized.toLowerCase(Locale.ROOT));
		}
		if (binding == null) {
			throw new IllegalArgumentException("Unknown J2ME configuration key: " + key);
		}
		return binding;
	}

	private static void registerAlias(Map<String, FieldBinding> aliases, String alias,
			FieldBinding binding) {
		aliases.put(alias, binding);
		aliases.put(alias.toLowerCase(Locale.ROOT), binding);
	}

	private static final class FieldBinding {
		final String key;
		final Field field;

		FieldBinding(String key, Field field) {
			this.key = key;
			this.field = field;
		}
	}

	/** Canonical keys used by upstream profile JSON files. */
	public static final class Keys {
		public static final String VERSION = "Version";
		public static final String SCREEN_WIDTH = "ScreenWidth";
		public static final String SCREEN_HEIGHT = "ScreenHeight";
		public static final String SCREEN_BACKGROUND_COLOR = "ScreenBackgroundColor";
		public static final String SCREEN_SCALE_RATIO = "ScreenScaleRatio";
		public static final String ORIENTATION = "Orientation";
		public static final String SCREEN_SCALE_TO_FIT = "ScreenScaleToFit";
		public static final String SCREEN_KEEP_ASPECT_RATIO = "ScreenKeepAspectRatio";
		public static final String SCREEN_SCALE_TYPE = "ScreenScaleType";
		public static final String SCREEN_GRAVITY = "ScreenGravity";
		public static final String SCREEN_FILTER = "ScreenFilter";
		public static final String IMMEDIATE_MODE = "ImmediateMode";
		public static final String HW_ACCELERATION = "HwAcceleration";
		public static final String GRAPHICS_MODE = "GraphicsMode";
		public static final String SHADER = "Shader";
		public static final String PARALLEL_REDRAW_SCREEN = "ParallelRedrawScreen";
		public static final String SHOW_FPS = "ShowFps";
		public static final String FPS_LIMIT = "FpsLimit";
		public static final String FORCE_FULLSCREEN = "ForceFullscreen";
		public static final String FONT_SIZE_SMALL = "FontSizeSmall";
		public static final String FONT_SIZE_MEDIUM = "FontSizeMedium";
		public static final String FONT_SIZE_LARGE = "FontSizeLarge";
		public static final String FONT_APPLY_DIMENSIONS = "FontApplyDimensions";
		public static final String FONT_ANTI_ALIAS = "FontAntiAlias";
		public static final String TOUCH_INPUT = "TouchInput";
		public static final String SHOW_KEYBOARD = "ShowKeyboard";
		public static final String VIRTUAL_KEYBOARD_TYPE = "VirtualKeyboardType";
		public static final String BUTTON_SHAPE = "ButtonShape";
		public static final String VIRTUAL_KEYBOARD_ALPHA = "VirtualKeyboardAlpha";
		public static final String VIRTUAL_KEYBOARD_FORCE_OPACITY = "VirtualKeyboardForceOpacity";
		public static final String VIRTUAL_KEYBOARD_FEEDBACK = "VirtualKeyboardFeedback";
		public static final String VIRTUAL_KEYBOARD_DELAY = "VirtualKeyboardDelay";
		public static final String VIRTUAL_KEYBOARD_COLOR_BACKGROUND =
				"VirtualKeyboardColorBackground";
		public static final String VIRTUAL_KEYBOARD_COLOR_BACKGROUND_SELECTED =
				"VirtualKeyboardColorBackgroundSelected";
		public static final String VIRTUAL_KEYBOARD_COLOR_FOREGROUND =
				"VirtualKeyboardColorForeground";
		public static final String VIRTUAL_KEYBOARD_COLOR_FOREGROUND_SELECTED =
				"VirtualKeyboardColorForegroundSelected";
		public static final String VIRTUAL_KEYBOARD_COLOR_OUTLINE = "VirtualKeyboardColorOutline";
		public static final String KEY_CODE_LAYOUT = "Layout";
		public static final String KEY_CODE_MAP = "KeyCodeMap";
		public static final String KEY_MAPPINGS = "KeyMappings";
		public static final String SYSTEM_PROPERTIES = "SystemProperties";

		private Keys() {
		}
	}
}
