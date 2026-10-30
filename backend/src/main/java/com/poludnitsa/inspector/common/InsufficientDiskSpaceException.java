package com.poludnitsa.inspector.common;

import com.poludnitsa.inspector.api.dto.DiskSpaceCheckDto;

/** Not enough free space for a heap dump; mapped to 507 Insufficient Storage. */
public class InsufficientDiskSpaceException extends InspectorException {

    private final DiskSpaceCheckDto check;

    public InsufficientDiskSpaceException(DiskSpaceCheckDto check) {
        super(507, "INSUFFICIENT_DISK_SPACE", String.format(
                "Not enough disk space for a heap dump of PID %d in %s: %s required (%.1f x %s used heap), only %s"
                        + " available. Free up space or point inspector.snapshots.work-dir to a larger volume.",
                check.pid(), check.directory(), Bytes.format(check.requiredBytes()), check.factor(),
                Bytes.format(check.heapUsedBytes()), Bytes.format(check.usableBytes())));
        this.check = check;
    }

    public DiskSpaceCheckDto check() {
        return check;
    }
}
