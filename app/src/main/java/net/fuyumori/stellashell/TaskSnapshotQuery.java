package net.fuyumori.stellashell;

import java.util.ArrayList;
import java.util.List;

/** Main-thread single-flight read. Completed snapshots are never cached. */
final class TaskSnapshotQuery {
    interface Reply { void done(String result, String error); }
    interface Source { void read(Reply reply); }

    private final Source source;
    private Request pending;

    TaskSnapshotQuery(Source source) { this.source = source; }

    void read(Reply reply) {
        if (pending != null) {
            pending.replies.add(reply);
            return;
        }
        Request request = new Request();
        request.replies.add(reply);
        pending = request;
        try {
            source.read((result, error) -> complete(request, result, error));
        } catch (RuntimeException failure) {
            if (request.completed) throw failure;
            complete(request, null, failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage());
        }
    }

    // A later invalidation/connection/lifecycle must not join an older read.
    // Existing waiters still receive its result and apply their own generation
    // and awake guards; dropping callbacks would leave a feed permanently busy.
    void invalidate() { pending = null; }

    private void complete(Request request, String result, String error) {
        if (request.completed) return;
        request.completed = true;
        if (pending == request) pending = null;
        List<Reply> replies = new ArrayList<>(request.replies);
        request.replies.clear();
        RuntimeException callbackFailure = null;
        for (Reply reply : replies) {
            try { reply.done(result, error); }
            catch (RuntimeException failure) { if (callbackFailure == null) callbackFailure = failure; }
        }
        if (callbackFailure != null) throw callbackFailure;
    }

    private static final class Request {
        final List<Reply> replies = new ArrayList<>();
        boolean completed;
    }
}
