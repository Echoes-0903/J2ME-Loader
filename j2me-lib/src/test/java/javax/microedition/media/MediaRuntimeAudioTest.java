package javax.microedition.media;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

public final class MediaRuntimeAudioTest {
	@After
	public void resetHostMute() {
		MediaRuntimeAudio.closeHostPlayers();
		MediaRuntimeAudio.setHostMuted(false);
	}

	@Test
	public void hostMutePreservesAndRestoresPlayerVolume() {
		RecordingPlayer player = new RecordingPlayer();
		player.setLevel(50);
		float configuredVolume = player.leftVolume;

		MediaRuntimeAudio.setHostMuted(true);
		assertEquals(0.0f, player.leftVolume, 0.0f);

		MediaRuntimeAudio.setHostMuted(false);
		assertEquals(configuredVolume, player.leftVolume, 0.0f);
	}

	@Test
	public void playerCreatedWhileMutedStartsMuted() throws MediaException {
		MediaRuntimeAudio.setHostMuted(true);
		RecordingPlayer player = new RecordingPlayer();

		player.start();

		assertEquals(0.0f, player.leftVolume, 0.0f);
	}

	private static final class RecordingPlayer extends BasePlayer {
		float leftVolume = 1.0f;
		int starts, stops, startedEvents, stoppedEvents, endEvents;
		@Override public void doStart() { starts++; }
		@Override public void doStop() { stops++; }
		@Override public synchronized void postEvent(String event, Object data) {
			if (PlayerListener.STARTED.equals(event)) startedEvents++;
			if (PlayerListener.STOPPED.equals(event)) stoppedEvents++;
			if (PlayerListener.END_OF_MEDIA.equals(event)) endEvents++;
		}

		@Override
		public void doSetVolume(float left, float right) {
			leftVolume = left;
		}
	}

	@Test public void hostPausePreservesLogicalStateAndDoesNotEmitLifecycleEvents() throws Exception {
		RecordingPlayer player = new RecordingPlayer();
		player.start();
		MediaRuntimeAudio.setHostPaused(true);
		MediaRuntimeAudio.setHostPaused(true);
		assertEquals(Player.STARTED, player.getState());
		assertEquals(1, player.stops);
		assertEquals(0, player.stoppedEvents);
		MediaRuntimeAudio.setHostPaused(false);
		MediaRuntimeAudio.setHostPaused(false);
		assertEquals(2, player.starts);
		assertEquals(1, player.startedEvents);
	}

	@Test public void newPlayerAndStartWhilePausedAreDeferred() throws Exception {
		MediaRuntimeAudio.setHostPaused(true);
		RecordingPlayer player = new RecordingPlayer();
		player.start();
		assertEquals(Player.STARTED, player.getState());
		assertEquals(0, player.starts);
		MediaRuntimeAudio.setHostPaused(false);
		assertEquals(1, player.starts);
	}

	@Test public void stoppedOrClosedPlayersDoNotRestartOnResume() throws Exception {
		RecordingPlayer stopped = new RecordingPlayer();
		RecordingPlayer closed = new RecordingPlayer();
		stopped.start();
		closed.start();
		MediaRuntimeAudio.setHostPaused(true);
		stopped.stop();
		closed.close();
		MediaRuntimeAudio.setHostPaused(false);
		assertEquals(1, stopped.starts);
		assertEquals(1, closed.starts);
		assertEquals(Player.PREFETCHED, stopped.getState());
		assertEquals(Player.CLOSED, closed.getState());
	}

	@Test public void completionDuringPauseIsDeferredUntilResume() throws Exception {
		RecordingPlayer player = new RecordingPlayer();
		player.start();
		MediaRuntimeAudio.setHostPaused(true);
		player.complete();
		assertEquals(0, player.endEvents);
		assertEquals(Player.STARTED, player.getState());
		MediaRuntimeAudio.setHostPaused(false);
		assertEquals(1, player.endEvents);
		assertEquals(Player.PREFETCHED, player.getState());
		assertEquals(1, player.starts);
	}

	@Test public void resumePreservesUserMuteAndChangedVolume() throws Exception {
		RecordingPlayer player = new RecordingPlayer();
		player.start();
		MediaRuntimeAudio.setHostPaused(true);
		MediaRuntimeAudio.setHostMuted(true);
		player.setLevel(30);
		MediaRuntimeAudio.setHostPaused(false);
		assertEquals(0f, player.leftVolume, 0f);
		assertEquals(30, player.getLevel());
	}
}
