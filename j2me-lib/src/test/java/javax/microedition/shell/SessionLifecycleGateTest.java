package javax.microedition.shell;

import static org.junit.Assert.*;
import org.junit.Test;

public class SessionLifecycleGateTest {
	@Test public void resumeRequiresCompletion() {
		SessionLifecycleGate gate = new SessionLifecycleGate();
		long request = gate.request(true);
		assertFalse(gate.acceptsOutput());
		assertTrue(gate.complete(request, true));
		assertTrue(gate.acceptsOutput());
	}
	@Test public void pauseClosesOutputImmediately() {
		SessionLifecycleGate gate = new SessionLifecycleGate();
		gate.complete(gate.request(true), true);
		gate.request(false);
		assertFalse(gate.acceptsOutput());
	}
	@Test public void staleResumeCannotUndoPause() {
		SessionLifecycleGate gate = new SessionLifecycleGate();
		long resume = gate.request(true);
		long pause = gate.request(false);
		assertFalse(gate.complete(resume, true));
		assertTrue(gate.complete(pause, false));
		assertFalse(gate.acceptsOutput());
	}
	@Test public void rapidPauseResumeKeepsLatestIntent() {
		SessionLifecycleGate gate = new SessionLifecycleGate();
		long first = gate.request(true);
		long pause = gate.request(false);
		long last = gate.request(true);
		assertFalse(gate.complete(first, true));
		assertFalse(gate.complete(pause, false));
		assertTrue(gate.complete(last, true));
		assertTrue(gate.acceptsOutput());
	}
	@Test public void rejectedResumeAndCloseNeverOpenOutput() {
		SessionLifecycleGate gate = new SessionLifecycleGate();
		assertTrue(gate.complete(gate.request(true), false));
		assertFalse(gate.acceptsOutput());
		long request = gate.request(true);
		gate.close();
		assertFalse(gate.complete(request, true));
		assertEquals(-1, gate.request(true));
		assertFalse(gate.acceptsOutput());
	}
}
