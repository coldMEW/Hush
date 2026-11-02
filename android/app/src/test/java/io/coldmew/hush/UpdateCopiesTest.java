package io.coldmew.hush;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;

// Only a copy that came with an install for every user is removed (real dumpsys lines from an
// Android 17 phone with a clone profile, 2026-11-01).
public class UpdateCopiesTest {
    private static final String UPDATED = "    lastUpdateTime=2026-10-31 11:00:12\n";
    private static final String OWNER = "    User 0: ceDataInode=1 deDataInode=2 installed=true hidden=false suspended=false\n"
            + "      firstInstallTime=2026-10-29 00:16:59\n";

    @Test public void copyFromUpdateIsFound() {
        String dump = UPDATED + OWNER
                + "    User 95: ceDataInode=3 installed=true hidden=false\n      firstInstallTime=2026-10-31 11:00:13\n";
        assertEquals(Collections.singletonList(95), UpdateCopies.fromUpdate(dump, 0));
    }

    @Test public void copyWithoutInstallTimeIsFound() {   // exactly what Android 17 printed for the copy
        String dump = "    lastUpdateTime=2026-11-01 16:59:46\n"
                + "    User 0: ceDataInode=1 installed=true hidden=false\n      firstInstallTime=2026-11-01 15:46:15\n"
                + "    User 10: ceDataInode=0 installed=true hidden=false\n      firstInstallTime=1969-12-31 18:00:00\n";
        assertEquals(Collections.singletonList(10), UpdateCopies.fromUpdate(dump, 0));
    }

    @Test public void cloneMadeSoonAfterTheUpdateIsKept() {   // install-existing 40 s after the update
        String dump = "    lastUpdateTime=2026-11-01 16:59:46\n"
                + "    User 0: ceDataInode=1 installed=true hidden=false\n      firstInstallTime=2026-11-01 15:46:15\n"
                + "    User 10: ceDataInode=5 installed=true hidden=false\n      firstInstallTime=2026-11-01 17:00:26\n";
        assertTrue(UpdateCopies.fromUpdate(dump, 0).isEmpty());
    }

    @Test public void cloneMadeEarlierIsKept() {
        String dump = UPDATED + OWNER
                + "    User 95: ceDataInode=3 installed=true hidden=false\n      firstInstallTime=2026-10-20 09:00:00\n";
        assertTrue(UpdateCopies.fromUpdate(dump, 0).isEmpty());
    }

    @Test public void ownUserAndUninstalledUsersAreNeverRemoved() {
        String dump = UPDATED
                + "    User 0: ceDataInode=1 installed=true\n      firstInstallTime=1969-12-31 18:00:00\n"
                + "    User 10: ceDataInode=0 installed=false\n      firstInstallTime=1969-12-31 18:00:00\n";
        assertTrue(UpdateCopies.fromUpdate(dump, 0).isEmpty());
    }

    @Test public void userInAnotherSpaceRunningHush() {   // Hush itself runs in user 10: user 0's copy is not "ours"
        String dump = UPDATED
                + "    User 0: ceDataInode=1 installed=true\n      firstInstallTime=2026-10-31 11:00:12\n"
                + "    User 10: ceDataInode=2 installed=true\n      firstInstallTime=2026-10-01 08:00:00\n";
        assertEquals(Collections.singletonList(0), UpdateCopies.fromUpdate(dump, 10));
    }

    @Test public void unclearOutputRemovesNothing() {
        assertTrue(UpdateCopies.fromUpdate(null, 0).isEmpty());
        assertTrue(UpdateCopies.fromUpdate("", 0).isEmpty());
        assertTrue(UpdateCopies.fromUpdate(OWNER + "    User 95: installed=true\n      firstInstallTime=2026-10-31 11:00:13\n", 0).isEmpty());
        assertTrue(UpdateCopies.fromUpdate("    lastUpdateTime=garbage\n" + OWNER
                + "    User 95: installed=true\n      firstInstallTime=2026-10-31 11:00:13\n", 0).isEmpty());
        assertTrue(UpdateCopies.fromUpdate(UPDATED + OWNER
                + "    User 95: installed=true\n      firstInstallTime=not a date\n", 0).isEmpty());
    }

    @Test public void blankUserHeaderIsIgnored() {   // dumpsys also prints a bare "User 0:" in another section
        String dump = UPDATED + OWNER + "    User 0:\n      firstInstallTime=1969-12-31 18:00:00\n";
        assertTrue(UpdateCopies.fromUpdate(dump, 0).isEmpty());
    }
}
