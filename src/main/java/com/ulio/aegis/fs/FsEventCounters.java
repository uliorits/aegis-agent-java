package com.ulio.aegis.fs;

import java.util.concurrent.atomic.LongAdder;

public class FsEventCounters {
    private final LongAdder modified = new LongAdder();
    private final LongAdder created = new LongAdder();
    private final LongAdder deleted = new LongAdder();
    private final LongAdder approxRenamed = new LongAdder();

    public void incrementModified() {
        modified.increment();
    }

    public void incrementCreated() {
        created.increment();
    }

    public void incrementDeleted() {
        deleted.increment();
    }

    public void incrementApproxRenamed() {
        approxRenamed.increment();
    }

    public Snapshot snapshotAndReset() {
        return new Snapshot(
                modified.sumThenReset(),
                created.sumThenReset(),
                deleted.sumThenReset(),
                approxRenamed.sumThenReset()
        );
    }

    public static final class Snapshot {
        private final long modified;
        private final long created;
        private final long deleted;
        private final long approxRenamed;

        public Snapshot(long modified, long created, long deleted, long approxRenamed) {
            this.modified = Math.max(0L, modified);
            this.created = Math.max(0L, created);
            this.deleted = Math.max(0L, deleted);
            this.approxRenamed = Math.max(0L, approxRenamed);
        }

        public long getModified() {
            return modified;
        }

        public long getCreated() {
            return created;
        }

        public long getDeleted() {
            return deleted;
        }

        public long getApproxRenamed() {
            return approxRenamed;
        }
    }
}
