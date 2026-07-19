/*
 * Copyright 2026
 * Licensed under the Apache License, Version 2.0.
 */
package javax.microedition.shell;

/** A host-originated action for the current semantic LCDUI screen. */
public final class LcdUiAction {
	public enum Type {
		COMMAND,
		ITEM_DEFAULT,
		SELECT,
		SET_TEXT,
		SET_GAUGE,
		SET_DATE,
		DISMISS
	}

	private final Type type;
	private final long screenId;
	private final long targetId;
	private final int index;
	private final boolean selected;
	private final String text;
	private final int intValue;
	private final long longValue;

	private LcdUiAction(Type type, long screenId, long targetId, int index,
			boolean selected, String text, int intValue, long longValue) {
		this.type = type;
		this.screenId = screenId;
		this.targetId = targetId;
		this.index = index;
		this.selected = selected;
		this.text = text;
		this.intValue = intValue;
		this.longValue = longValue;
	}

	public static LcdUiAction command(long screenId, long commandId) {
		return new LcdUiAction(Type.COMMAND, screenId, commandId, -1,
				false, null, 0, 0);
	}

	public static LcdUiAction itemDefault(long screenId, long itemId) {
		return new LcdUiAction(Type.ITEM_DEFAULT, screenId, itemId, -1,
				false, null, 0, 0);
	}

	public static LcdUiAction select(long screenId, long targetId, int index,
			boolean selected) {
		return new LcdUiAction(Type.SELECT, screenId, targetId, index,
				selected, null, 0, 0);
	}

	public static LcdUiAction setText(long screenId, long targetId, String text) {
		return new LcdUiAction(Type.SET_TEXT, screenId, targetId, -1,
				false, text == null ? "" : text, 0, 0);
	}

	public static LcdUiAction setGauge(long screenId, long itemId, int value) {
		return new LcdUiAction(Type.SET_GAUGE, screenId, itemId, -1,
				false, null, value, 0);
	}

	public static LcdUiAction setDate(long screenId, long itemId, long epochMillis) {
		return new LcdUiAction(Type.SET_DATE, screenId, itemId, -1,
				false, null, 0, epochMillis);
	}

	public static LcdUiAction dismiss(long screenId) {
		return new LcdUiAction(Type.DISMISS, screenId, 0, -1,
				false, null, 0, 0);
	}

	public Type getType() {
		return type;
	}

	public long getScreenId() {
		return screenId;
	}

	public long getTargetId() {
		return targetId;
	}

	public int getIndex() {
		return index;
	}

	public boolean isSelected() {
		return selected;
	}

	public String getText() {
		return text;
	}

	public int getIntValue() {
		return intValue;
	}

	public long getLongValue() {
		return longValue;
	}
}
