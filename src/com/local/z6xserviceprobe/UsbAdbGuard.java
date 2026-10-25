package com.local.z6xserviceprobe;

/** Pure decision policy for the one verified Android 8 firmware. No Android calls or writes. */
final class UsbAdbGuard {
    private static final String VERIFIED_FINGERPRINT = "Xgimi/ironman/ironman:8.0.0/OPR5.170623.014/v12.15.180:user/dev-keys";
    interface BackupCommitter { boolean commit(Backup backup); }
    static final class Backup {
        final String value, fingerprint, board, incremental, savedAt;
        Backup(String value, String fingerprint, String board, String incremental, String savedAt) {
            this.value = value; this.fingerprint = fingerprint; this.board = board;
            this.incremental = incremental; this.savedAt = savedAt;
        }
        boolean valid(String expectedFingerprint) {
            return "none".equals(value) && "ironman".equals(board) && "v12.15.180".equals(incremental)
                && VERIFIED_FINGERPRINT.equals(expectedFingerprint)
                && expectedFingerprint.equals(fingerprint) && savedAt != null && savedAt.length() > 0;
        }
    }
    static final class Plan {
        final boolean restore, saveBackup, write;
        final String current, sysCurrent, target, fingerprint, message;
        Plan(boolean restore, boolean saveBackup, boolean write, String current, String sysCurrent,
             String target, String fingerprint, String message) {
            this.restore = restore; this.saveBackup = saveBackup; this.write = write;
            this.current = current; this.sysCurrent = sysCurrent; this.target = target;
            this.fingerprint = fingerprint; this.message = message;
        }
    }
    static void requireDevice(String board, String incremental, String fingerprint) {
        if (!"ironman".equals(board) || !"v12.15.180".equals(incremental)
                || !VERIFIED_FINGERPRINT.equals(fingerprint)) {
            throw new IllegalStateException("仅允许本次已核实的 ironman / v12.15.180 / 完整 fingerprint 固件");
        }
    }
    static Plan plan(boolean restore, String current, String sysCurrent, Backup backup,
                     String board, String incremental, String fingerprint) {
        requireDevice(board, incremental, fingerprint);
        if (!("none".equals(current) || "adb".equals(current))) {
            throw new IllegalStateException("persist.sys.usb.config 当前值不是精确 none 或 adb，拒绝操作");
        }
        if (!("none".equals(sysCurrent) || "adb".equals(sysCurrent) || "mtp,adb".equals(sysCurrent))) {
            throw new IllegalStateException("sys.usb.config 当前值不是精确 none、adb 或 mtp,adb，拒绝操作");
        }
        if (backup != null && !backup.valid(fingerprint)) {
            throw new IllegalStateException("已有备份与当前设备/固件不匹配或内容无效，拒绝覆盖备份或写入配置");
        }
        if (restore) {
            if (backup == null) throw new IllegalStateException("没有持久保存的原始配置备份，禁止恢复写入");
            return new Plan(true, false, !"none".equals(current), current, sysCurrent, "none", fingerprint,
                "none".equals(current) ? "当前已经是已备份的 none，未写入" : "仅恢复同设备备份中的 none");
        }
        if ("adb".equals(current)) {
            return new Plan(false, false, false, current, sysCurrent, "adb", fingerprint,
                backup == null ? "当前已配置 adb 且无原始备份：不伪造基线，不写入" : "当前已经是 adb，保留原始 none 备份，不写入");
        }
        return new Plan(false, backup == null, true, current, sysCurrent, "adb", fingerprint,
            backup == null ? "确认后先同步保存当前 none 基线，保存成功才写入" : "沿用已有的原始 none 备份，不覆盖");
    }
    static void requireUnchanged(Plan plan, String current, String sysCurrent, String fingerprint) {
        if (!plan.fingerprint.equals(fingerprint) || !plan.current.equals(current)
                || (plan.sysCurrent == null ? sysCurrent != null : !plan.sysCurrent.equals(sysCurrent))) {
            throw new IllegalStateException("确认前后设备或 USB 配置已变化，取消本次写入；请重新读取确认");
        }
    }
    static void requireDurableBackup(Backup backup, String fingerprint, BackupCommitter committer) {
        if (backup == null || !backup.valid(fingerprint)) {
            throw new IllegalStateException("缺少有效的同设备 none 基线，禁止写入");
        }
        if (!committer.commit(backup)) {
            throw new IllegalStateException("本次基线强制同步 commit=false，禁止属性写入；重试必须再次同步");
        }
    }
}
