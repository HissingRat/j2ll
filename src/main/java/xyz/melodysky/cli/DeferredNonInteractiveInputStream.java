package xyz.melodysky.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

/**
 * Defers the non-interactive stdin decision until a prompt actually reads.
 *
 * <p>This gives a shell pipe a bounded window to deliver its first byte while
 * still turning an unattended open stdin into EOF instead of blocking the
 * build indefinitely.</p>
 */
final class DeferredNonInteractiveInputStream extends InputStream {
    private static final long FIRST_BYTE_WAIT_NANOS =
            java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
    private static final long POLL_NANOS =
            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10);

    private final InputStream delegate;
    private boolean decided;
    private boolean delegateSelected;

    DeferredNonInteractiveInputStream(InputStream delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public int read() throws IOException {
        return selectDelegate() ? delegate.read() : -1;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) {
            return 0;
        }
        return selectDelegate()
                ? delegate.read(bytes, offset, length)
                : -1;
    }

    @Override
    public int available() throws IOException {
        return delegateSelected ? delegate.available() : 0;
    }

    private boolean selectDelegate() throws IOException {
        if (decided) {
            return delegateSelected;
        }
        long deadline = System.nanoTime() + FIRST_BYTE_WAIT_NANOS;
        while (true) {
            if (delegate.available() > 0) {
                delegateSelected = true;
                decided = true;
                return true;
            }
            if (System.nanoTime() >= deadline) {
                decided = true;
                return false;
            }
            LockSupport.parkNanos(POLL_NANOS);
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                decided = true;
                return false;
            }
        }
    }
}
