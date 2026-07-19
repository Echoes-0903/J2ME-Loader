/*
 * Copyright 2026
 * Licensed under the Apache License, Version 2.0.
 */
package javax.microedition.shell;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable semantic snapshot of a non-Canvas MIDP LCDUI screen. */
public final class LcdUiState {
	public enum ScreenType {
		FORM,
		LIST,
		TEXT_BOX,
		ALERT,
		UNSUPPORTED
	}

	public enum ItemType {
		STRING,
		TEXT_FIELD,
		CHOICE,
		GAUGE,
		IMAGE,
		DATE,
		SPACER,
		UNSUPPORTED
	}

	public static final class Command {
		private final long id;
		private final String label;
		private final String longLabel;
		private final int commandType;
		private final int priority;
		private final boolean defaultCommand;

		Command(long id, String label, String longLabel, int commandType,
				int priority, boolean defaultCommand) {
			this.id = id;
			this.label = label;
			this.longLabel = longLabel;
			this.commandType = commandType;
			this.priority = priority;
			this.defaultCommand = defaultCommand;
		}

		public long getId() { return id; }
		public String getLabel() { return label; }
		public String getLongLabel() { return longLabel; }
		public int getCommandType() { return commandType; }
		public int getPriority() { return priority; }
		public boolean isDefaultCommand() { return defaultCommand; }
	}

	public static final class Option {
		private final String text;
		private final Bitmap image;
		private final boolean selected;

		Option(String text, Bitmap image, boolean selected) {
			this.text = text;
			this.image = image;
			this.selected = selected;
		}

		public String getText() { return text; }
		public Bitmap getImage() { return image; }
		public boolean isSelected() { return selected; }
	}

	public static final class Item {
		private final long id;
		private final ItemType type;
		private final String className;
		private final String label;
		private final String text;
		private final Bitmap image;
		private final List<Option> options;
		private final List<Command> commands;
		private final int appearanceMode;
		private final int constraints;
		private final int maxSize;
		private final int choiceType;
		private final int value;
		private final int maxValue;
		private final boolean interactive;
		private final long dateMillis;
		private final int dateMode;
		private final int width;
		private final int height;

		Item(long id, ItemType type, String className, String label, String text,
				Bitmap image, List<Option> options, List<Command> commands,
				int appearanceMode, int constraints, int maxSize, int choiceType,
				int value, int maxValue, boolean interactive, long dateMillis,
				int dateMode, int width, int height) {
			this.id = id;
			this.type = type;
			this.className = className;
			this.label = label;
			this.text = text;
			this.image = image;
			this.options = immutable(options);
			this.commands = immutable(commands);
			this.appearanceMode = appearanceMode;
			this.constraints = constraints;
			this.maxSize = maxSize;
			this.choiceType = choiceType;
			this.value = value;
			this.maxValue = maxValue;
			this.interactive = interactive;
			this.dateMillis = dateMillis;
			this.dateMode = dateMode;
			this.width = width;
			this.height = height;
		}

		public long getId() { return id; }
		public ItemType getType() { return type; }
		public String getClassName() { return className; }
		public String getLabel() { return label; }
		public String getText() { return text; }
		public Bitmap getImage() { return image; }
		public List<Option> getOptions() { return options; }
		public List<Command> getCommands() { return commands; }
		public int getAppearanceMode() { return appearanceMode; }
		public int getConstraints() { return constraints; }
		public int getMaxSize() { return maxSize; }
		public int getChoiceType() { return choiceType; }
		public int getValue() { return value; }
		public int getMaxValue() { return maxValue; }
		public boolean isInteractive() { return interactive; }
		public long getDateMillis() { return dateMillis; }
		public int getDateMode() { return dateMode; }
		public int getWidth() { return width; }
		public int getHeight() { return height; }
	}

	private final long screenId;
	private final long revision;
	private final ScreenType screenType;
	private final String className;
	private final String title;
	private final String ticker;
	private final List<Command> commands;
	private final List<Item> items;
	private final List<Option> options;
	private final int choiceType;
	private final String text;
	private final int constraints;
	private final int maxSize;
	private final Bitmap image;
	private final int timeout;
	private final Item alertIndicator;
	private final boolean dismissible;
	private final String contentFingerprint;

	LcdUiState(long screenId, long revision, ScreenType screenType, String className,
			String title, String ticker, List<Command> commands, List<Item> items,
			List<Option> options, int choiceType, String text, int constraints,
			int maxSize, Bitmap image, int timeout, Item alertIndicator,
			boolean dismissible, String contentFingerprint) {
		this.screenId = screenId;
		this.revision = revision;
		this.screenType = screenType;
		this.className = className;
		this.title = title;
		this.ticker = ticker;
		this.commands = immutable(commands);
		this.items = immutable(items);
		this.options = immutable(options);
		this.choiceType = choiceType;
		this.text = text;
		this.constraints = constraints;
		this.maxSize = maxSize;
		this.image = image;
		this.timeout = timeout;
		this.alertIndicator = alertIndicator;
		this.dismissible = dismissible;
		this.contentFingerprint = contentFingerprint;
	}

	private static <T> List<T> immutable(List<T> source) {
		return Collections.unmodifiableList(new ArrayList<>(source));
	}

	public long getScreenId() { return screenId; }
	public long getRevision() { return revision; }
	public ScreenType getScreenType() { return screenType; }
	public String getClassName() { return className; }
	public String getTitle() { return title; }
	public String getTicker() { return ticker; }
	public List<Command> getCommands() { return commands; }
	public List<Item> getItems() { return items; }
	public List<Option> getOptions() { return options; }
	public int getChoiceType() { return choiceType; }
	public String getText() { return text; }
	public int getConstraints() { return constraints; }
	public int getMaxSize() { return maxSize; }
	public Bitmap getImage() { return image; }
	public int getTimeout() { return timeout; }
	public Item getAlertIndicator() { return alertIndicator; }
	public boolean isDismissible() { return dismissible; }

	String contentFingerprint() { return contentFingerprint; }
}
