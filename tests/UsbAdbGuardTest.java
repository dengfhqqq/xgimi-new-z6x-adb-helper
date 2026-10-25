package com.local.z6xserviceprobe;

public final class UsbAdbGuardTest {
    private static int checks;
    private static final String FP = "Xgimi/ironman/ironman:8.0.0/OPR5.170623.014/v12.15.180:user/dev-keys";
    private static final UsbAdbGuard.Backup GOOD = new UsbAdbGuard.Backup("none", FP, "ironman", "v12.15.180", "2026-10-25");
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static void reject(Runnable action) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected);
    }
    public static void main(String[] args) {
        UsbAdbGuard.Plan enable = UsbAdbGuard.plan(false, "none", "none", null, "ironman", "v12.15.180", FP);
        check(enable.write && enable.saveBackup && "adb".equals(enable.target));
        UsbAdbGuard.Plan again = UsbAdbGuard.plan(false, "adb", "adb", GOOD, "ironman", "v12.15.180", FP);
        check(!again.write && !again.saveBackup);
        UsbAdbGuard.Plan already = UsbAdbGuard.plan(false, "adb", "none", null, "ironman", "v12.15.180", FP);
        check(!already.write && !already.saveBackup);
        UsbAdbGuard.Plan restore = UsbAdbGuard.plan(true, "adb", "adb", GOOD, "ironman", "v12.15.180", FP);
        check(restore.write && !restore.saveBackup && "none".equals(restore.target));
        UsbAdbGuard.Plan restoreMtpAdb = UsbAdbGuard.plan(true, "adb", "mtp,adb", GOOD, "ironman", "v12.15.180", FP);
        check(restoreMtpAdb.write && !restoreMtpAdb.saveBackup && "none".equals(restoreMtpAdb.target));
        UsbAdbGuard.Plan enableMtpAdb = UsbAdbGuard.plan(false, "none", "mtp,adb", null, "ironman", "v12.15.180", FP);
        check(enableMtpAdb.write && enableMtpAdb.saveBackup && "adb".equals(enableMtpAdb.target));
        check(!UsbAdbGuard.plan(true, "none", "adb", GOOD, "ironman", "v12.15.180", FP).write);
        reject(() -> UsbAdbGuard.plan(true, "adb", "adb", null, "ironman", "v12.15.180", FP));
        reject(() -> UsbAdbGuard.plan(false, "mtp,adb", "none", null, "ironman", "v12.15.180", FP));
        reject(() -> UsbAdbGuard.plan(false, "", "none", null, "ironman", "v12.15.180", FP));
        reject(() -> UsbAdbGuard.plan(false, "none", null, null, "ironman", "v12.15.180", FP));
        reject(() -> UsbAdbGuard.plan(false, "none", "", null, "ironman", "v12.15.180", FP));
        reject(() -> UsbAdbGuard.plan(false, "none", "mtp", null, "ironman", "v12.15.180", FP));
        check(UsbAdbGuard.plan(false, "none", "adb", null, "ironman", "v12.15.180", FP).write);
        check(UsbAdbGuard.plan(true, "adb", "none", GOOD, "ironman", "v12.15.180", FP).write);
        reject(() -> UsbAdbGuard.plan(false, "none", "none", GOOD, "other", "v12.15.180", FP));
        reject(() -> UsbAdbGuard.plan(false, "none", "none", GOOD, "ironman", "v12.15.181", FP));
        reject(() -> UsbAdbGuard.plan(false, "none", "none", GOOD, "ironman", "v12.15.180", "other-fingerprint"));
        reject(() -> UsbAdbGuard.plan(false, "none", "none", null, "ironman", "v12.15.180", "unknown-fingerprint"));
        reject(() -> UsbAdbGuard.plan(false, "none", "none", new UsbAdbGuard.Backup("adb", FP, "ironman", "v12.15.180", "date"), "ironman", "v12.15.180", FP));
        UsbAdbGuard.requireUnchanged(enable, "none", "none", FP); check(true);
        reject(() -> UsbAdbGuard.requireUnchanged(enable, "adb", "none", FP));
        reject(() -> UsbAdbGuard.requireUnchanged(enable, "none", "adb", FP));
        reject(() -> UsbAdbGuard.requireUnchanged(enable, "none", "none", "other"));
        final int[] syncCalls = { 0 };
        UsbAdbGuard.BackupCommitter failedThenRetry = backup -> ++syncCalls[0] > 1;
        // A valid-looking in-memory backup after commit=false must still sync on retry.
        reject(() -> UsbAdbGuard.requireDurableBackup(GOOD, FP, failedThenRetry));
        check(syncCalls[0] == 1);
        UsbAdbGuard.requireDurableBackup(GOOD, FP, failedThenRetry);
        check(syncCalls[0] == 2);
        UsbAdbGuard.requireDurableBackup(GOOD, FP, failedThenRetry);
        check(syncCalls[0] == 3); // Every later write/restore also goes through commit.
        reject(() -> UsbAdbGuard.requireDurableBackup(null, FP, failedThenRetry));
        System.out.println("PASS " + checks + " policy checks");
    }
}
