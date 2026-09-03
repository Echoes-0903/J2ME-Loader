package javax.microedition.shell;

/** Separates host intent from completed MIDlet callbacks; owns no Android thread or lock. */
final class SessionLifecycleGate {
	private long revision;
	private boolean runningRequested;
	private boolean running;
	private boolean terminal;

	synchronized long request(boolean resume) {
		if (terminal) return -1;
		runningRequested = resume;
		running = false;
		return ++revision;
	}

	synchronized boolean isCurrent(long request) {
		return !terminal && request == revision;
	}

	synchronized long revision() {
		return revision;
	}

	synchronized boolean complete(long request, boolean resumed) {
		if (!isCurrent(request)) return false;
		running = resumed && runningRequested;
		return true;
	}

	synchronized boolean acceptsOutput() {
		return !terminal && runningRequested && running;
	}

	synchronized void close() {
		terminal = true;
		running = false;
		++revision;
	}
}
