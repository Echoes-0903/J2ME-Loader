/*
 * Copyright 2026
 * Licensed under the Apache License, Version 2.0.
 */
package javax.microedition.shell;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.ChoiceGroup;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.DateField;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Gauge;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.ImageItem;
import javax.microedition.lcdui.Item;
import javax.microedition.lcdui.Spacer;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;
import javax.microedition.lcdui.Ticker;

/** Converts the mutable LCDUI object graph to immutable host-facing snapshots. */
final class LcdUiBridge {
	static final class CommandTarget {
		final Object owner;
		final Command command;

		CommandTarget(Object owner, Command command) {
			this.owner = owner;
			this.command = command;
		}
	}

	static final class Capture {
		final Displayable displayable;
		final LcdUiState state;
		final Map<Long, CommandTarget> commands;
		final Map<Long, Item> items;

		Capture(Displayable displayable, LcdUiState state,
				Map<Long, CommandTarget> commands, Map<Long, Item> items) {
			this.displayable = displayable;
			this.state = state;
			this.commands = commands;
			this.items = items;
		}
	}

	private LcdUiBridge() {
	}

	static Capture capture(Displayable displayable, long revision) {
		long screenId = objectId(displayable);
		Map<Long, CommandTarget> commandTargets = new LinkedHashMap<>();
		Map<Long, Item> itemTargets = new LinkedHashMap<>();
		ArrayList<LcdUiState.Command> commands = captureCommands(
				displayable, displayable.getCommands(), null, commandTargets);
		ArrayList<LcdUiState.Item> items = new ArrayList<>();
		ArrayList<LcdUiState.Option> options = new ArrayList<>();

		LcdUiState.ScreenType screenType = LcdUiState.ScreenType.UNSUPPORTED;
		int choiceType = -1;
		String text = null;
		int constraints = 0;
		int maxSize = 0;
		Bitmap image = null;
		int timeout = Alert.FOREVER;
		LcdUiState.Item alertIndicator = null;
		boolean dismissible = false;

		if (displayable instanceof Form) {
			screenType = LcdUiState.ScreenType.FORM;
			Form form = (Form) displayable;
			int size = form.size();
			for (int index = 0; index < size; index++) {
				items.add(captureItem(form.get(index), commandTargets, itemTargets));
			}
		} else if (displayable instanceof javax.microedition.lcdui.List) {
			screenType = LcdUiState.ScreenType.LIST;
			javax.microedition.lcdui.List list = (javax.microedition.lcdui.List) displayable;
			choiceType = list.getListType();
			int size = list.size();
			for (int index = 0; index < size; index++) {
				options.add(captureOption(list.getString(index), list.getImage(index),
						list.isSelected(index)));
			}
		} else if (displayable instanceof TextBox) {
			screenType = LcdUiState.ScreenType.TEXT_BOX;
			TextBox textBox = (TextBox) displayable;
			text = textBox.getString();
			constraints = textBox.getConstraints();
			maxSize = textBox.getMaxSize();
		} else if (displayable instanceof Alert) {
			screenType = LcdUiState.ScreenType.ALERT;
			Alert alert = (Alert) displayable;
			text = alert.getString();
			image = bitmap(alert.getImage());
			timeout = alert.getTimeout();
			Gauge indicator = alert.getIndicator();
			if (indicator != null) {
				alertIndicator = captureItem(indicator, commandTargets, itemTargets);
			}
			for (Command command : displayable.getCommands()) {
				int type = command.getCommandType();
				if (command == Alert.DISMISS_COMMAND || type == Command.BACK
						|| type == Command.CANCEL) {
					dismissible = true;
					break;
				}
			}
		}

		Ticker ticker = displayable.getTicker();
		LcdUiState draft = new LcdUiState(screenId, revision, screenType,
				displayable.getClass().getName(), displayable.getTitle(),
				ticker == null ? null : ticker.getString(), commands, items, options,
				choiceType, text, constraints, maxSize, image, timeout, alertIndicator,
				dismissible, "");
		LcdUiState state = copyWithFingerprint(draft, fingerprint(draft));
		return new Capture(displayable, state, commandTargets, itemTargets);
	}

	static boolean dispatch(Capture capture, LcdUiAction action) {
		if (capture == null || action == null
				|| action.getScreenId() != capture.state.getScreenId()) {
			return false;
		}
		switch (action.getType()) {
			case COMMAND: {
				CommandTarget target = capture.commands.get(action.getTargetId());
				if (target == null) return false;
				if (target.owner instanceof Displayable) {
					((Displayable) target.owner).fireCommandAction(target.command);
				} else if (target.owner instanceof Item) {
					((Item) target.owner).fireCommandAction(target.command);
				}
				return true;
			}
			case ITEM_DEFAULT: {
				Item item = capture.items.get(action.getTargetId());
				if (item == null) return false;
				item.fireDefaultCommandAction();
				return true;
			}
			case SELECT:
				return select(capture, action);
			case SET_TEXT:
				return setText(capture, action);
			case SET_GAUGE: {
				Item item = capture.items.get(action.getTargetId());
				if (!(item instanceof Gauge)) return false;
				Gauge gauge = (Gauge) item;
				gauge.setValue(action.getIntValue());
				gauge.notifyStateChanged();
				return true;
			}
			case SET_DATE: {
				Item item = capture.items.get(action.getTargetId());
				if (!(item instanceof DateField)) return false;
				DateField date = (DateField) item;
				date.setDate(new Date(action.getLongValue()));
				date.notifyStateChanged();
				return true;
			}
			case DISMISS:
				return dismiss(capture.displayable);
			default:
				return false;
		}
	}

	private static boolean select(Capture capture, LcdUiAction action) {
		int index = action.getIndex();
		if (action.getTargetId() == capture.state.getScreenId()
				&& capture.displayable instanceof javax.microedition.lcdui.List) {
			javax.microedition.lcdui.List list =
					(javax.microedition.lcdui.List) capture.displayable;
			if (index < 0 || index >= list.size()) return false;
			int type = list.getListType();
			list.setSelectedIndex(index, type == Choice.MULTIPLE
					? action.isSelected() : true);
			if (type == Choice.IMPLICIT) {
				list.fireSelectCommand();
			}
			return true;
		}
		Item item = capture.items.get(action.getTargetId());
		if (!(item instanceof ChoiceGroup)) return false;
		ChoiceGroup choice = (ChoiceGroup) item;
		if (index < 0 || index >= choice.size()) return false;
		choice.setSelectedIndex(index, choice.getChoiceType() == Choice.MULTIPLE
				? action.isSelected() : true);
		choice.notifyStateChanged();
		return true;
	}

	private static boolean setText(Capture capture, LcdUiAction action) {
		if (action.getTargetId() == capture.state.getScreenId()
				&& capture.displayable instanceof TextBox) {
			((TextBox) capture.displayable).setString(action.getText());
			return true;
		}
		Item item = capture.items.get(action.getTargetId());
		if (!(item instanceof TextField)) return false;
		TextField field = (TextField) item;
		field.setString(action.getText());
		field.notifyStateChanged();
		return true;
	}

	private static boolean dismiss(Displayable displayable) {
		if (!(displayable instanceof Alert)) return false;
		for (Command command : displayable.getCommands()) {
			int type = command.getCommandType();
			if (command == Alert.DISMISS_COMMAND || type == Command.BACK
					|| type == Command.CANCEL) {
				displayable.fireCommandAction(command);
				return true;
			}
		}
		return false;
	}

	private static LcdUiState.Item captureItem(Item item,
			Map<Long, CommandTarget> commandTargets, Map<Long, Item> itemTargets) {
		long itemId = objectId(item);
		itemTargets.put(itemId, item);
		ArrayList<LcdUiState.Command> commands = captureCommands(item,
				item.getCommands(), item.getDefaultCommand(), commandTargets);
		LcdUiState.ItemType type = LcdUiState.ItemType.UNSUPPORTED;
		String text = null;
		Bitmap image = null;
		ArrayList<LcdUiState.Option> options = new ArrayList<>();
		int appearance = Item.PLAIN;
		int constraints = 0;
		int maxSize = 0;
		int choiceType = -1;
		int value = 0;
		int maxValue = 0;
		boolean interactive = false;
		long dateMillis = 0;
		int dateMode = 0;
		int width = item.getMinimumWidth();
		int height = item.getMinimumHeight();

		if (item instanceof StringItem) {
			type = LcdUiState.ItemType.STRING;
			StringItem stringItem = (StringItem) item;
			text = stringItem.getText();
			appearance = stringItem.getAppearanceMode();
		} else if (item instanceof TextField) {
			type = LcdUiState.ItemType.TEXT_FIELD;
			TextField field = (TextField) item;
			text = field.getString();
			constraints = field.getConstraints();
			maxSize = field.getMaxSize();
		} else if (item instanceof ChoiceGroup) {
			type = LcdUiState.ItemType.CHOICE;
			ChoiceGroup choice = (ChoiceGroup) item;
			choiceType = choice.getChoiceType();
			int size = choice.size();
			for (int index = 0; index < size; index++) {
				options.add(captureOption(choice.getString(index), choice.getImage(index),
						choice.isSelected(index)));
			}
		} else if (item instanceof Gauge) {
			type = LcdUiState.ItemType.GAUGE;
			Gauge gauge = (Gauge) item;
			value = gauge.getValue();
			maxValue = gauge.getMaxValue();
			interactive = gauge.isInteractive();
		} else if (item instanceof ImageItem) {
			type = LcdUiState.ItemType.IMAGE;
			ImageItem imageItem = (ImageItem) item;
			image = bitmap(imageItem.getImage());
			text = imageItem.getAltText();
			appearance = imageItem.getAppearanceMode();
		} else if (item instanceof DateField) {
			type = LcdUiState.ItemType.DATE;
			DateField date = (DateField) item;
			dateMillis = date.getDate().getTime();
			dateMode = date.getInputMode();
		} else if (item instanceof Spacer) {
			type = LcdUiState.ItemType.SPACER;
		}

		return new LcdUiState.Item(itemId, type, item.getClass().getName(),
				item.getLabel(), text, image, options, commands, appearance, constraints,
				maxSize, choiceType, value, maxValue, interactive, dateMillis, dateMode,
				width, height);
	}

	private static ArrayList<LcdUiState.Command> captureCommands(Object owner,
			Command[] commands, Command defaultCommand,
			Map<Long, CommandTarget> targets) {
		ArrayList<LcdUiState.Command> result = new ArrayList<>();
		for (Command command : commands) {
			long id = commandId(owner, command);
			targets.put(id, new CommandTarget(owner, command));
			result.add(new LcdUiState.Command(id, command.getAndroidLabel(),
					command.getLongLabel(), command.getCommandType(), command.getPriority(),
					command == defaultCommand));
		}
		return result;
	}

	private static LcdUiState.Option captureOption(String text, Image image,
			boolean selected) {
		return new LcdUiState.Option(text, bitmap(image), selected);
	}

	private static Bitmap bitmap(Image image) {
		return image == null ? null : image.getBitmap();
	}

	private static long objectId(Object object) {
		return Integer.toUnsignedLong(System.identityHashCode(object));
	}

	private static long commandId(Object owner, Command command) {
		return (objectId(owner) << 32) ^ objectId(command);
	}

	private static LcdUiState copyWithFingerprint(LcdUiState state, String fingerprint) {
		return new LcdUiState(state.getScreenId(), state.getRevision(),
				state.getScreenType(), state.getClassName(), state.getTitle(),
				state.getTicker(), state.getCommands(), state.getItems(), state.getOptions(),
				state.getChoiceType(), state.getText(), state.getConstraints(),
				state.getMaxSize(), state.getImage(), state.getTimeout(),
				state.getAlertIndicator(), state.isDismissible(), fingerprint);
	}

	private static String fingerprint(LcdUiState state) {
		StringBuilder out = new StringBuilder(512);
		out.append(state.getScreenId()).append('|').append(state.getScreenType())
				.append('|').append(state.getClassName()).append('|').append(state.getTitle())
				.append('|').append(state.getTicker()).append('|').append(state.getChoiceType())
				.append('|').append(state.getText()).append('|').append(state.getConstraints())
				.append('|').append(state.getMaxSize()).append('|').append(state.getTimeout())
				.append('|').append(state.isDismissible());
		appendBitmap(out, state.getImage());
		appendCommands(out, state.getCommands());
		appendOptions(out, state.getOptions());
		for (LcdUiState.Item item : state.getItems()) appendItem(out, item);
		if (state.getAlertIndicator() != null) appendItem(out, state.getAlertIndicator());
		return out.toString();
	}

	private static void appendItem(StringBuilder out, LcdUiState.Item item) {
		out.append("#i").append(item.getId()).append('|').append(item.getType())
				.append('|').append(item.getClassName()).append('|').append(item.getLabel())
				.append('|').append(item.getText()).append('|').append(item.getAppearanceMode())
				.append('|').append(item.getConstraints()).append('|').append(item.getMaxSize())
				.append('|').append(item.getChoiceType()).append('|').append(item.getValue())
				.append('|').append(item.getMaxValue()).append('|').append(item.isInteractive())
				.append('|').append(item.getDateMillis()).append('|').append(item.getDateMode())
				.append('|').append(item.getWidth()).append('|').append(item.getHeight());
		appendBitmap(out, item.getImage());
		appendOptions(out, item.getOptions());
		appendCommands(out, item.getCommands());
	}

	private static void appendCommands(StringBuilder out,
			java.util.List<LcdUiState.Command> commands) {
		for (LcdUiState.Command command : commands) {
			out.append("#c").append(command.getId()).append('|').append(command.getLabel())
					.append('|').append(command.getLongLabel()).append('|')
					.append(command.getCommandType()).append('|').append(command.getPriority())
					.append('|').append(command.isDefaultCommand());
		}
	}

	private static void appendOptions(StringBuilder out,
			java.util.List<LcdUiState.Option> options) {
		for (LcdUiState.Option option : options) {
			out.append("#o").append(option.getText()).append('|').append(option.isSelected());
			appendBitmap(out, option.getImage());
		}
	}

	private static void appendBitmap(StringBuilder out, Bitmap bitmap) {
		if (bitmap == null) {
			out.append("#b0");
		} else {
			out.append("#b").append(System.identityHashCode(bitmap)).append('|')
					.append(bitmap.getGenerationId()).append('|').append(bitmap.getWidth())
					.append('|').append(bitmap.getHeight());
		}
	}
}
