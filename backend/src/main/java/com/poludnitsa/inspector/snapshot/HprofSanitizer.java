package com.poludnitsa.inspector.snapshot;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Repairs HPROF dumps that the NetBeans profiler lib cannot analyse.
 *
 * <p>Recent HotSpot versions write {@code ROOT_STICKY_CLASS} records for a few classes that have no
 * {@code CLASS_DUMP} in the file. NetBeans (RELEASE210 up to at least RELEASE310) then fails with a
 * {@code NullPointerException} in {@code HprofGCRoots.getGCRoot} while computing nearest GC roots and retained sizes.
 * This class scans the dump once, sequentially, and overwrites the object id of every such dangling root
 * <em>in place</em> with the id of an existing class, which is a sticky root already: no copy of the multi-GB file,
 * no extra disk space, and the object graph is unchanged.
 *
 * <p>If the file contains a record this scanner does not know, nothing is modified.
 */
final class HprofSanitizer {

    private static final int TAG_HEAP_DUMP = 0x0C;
    private static final int TAG_HEAP_DUMP_SEGMENT = 0x1C;
    private static final int ROOT_UNKNOWN = 0xFF;
    private static final int ROOT_JNI_GLOBAL = 0x01;
    private static final int ROOT_JNI_LOCAL = 0x02;
    private static final int ROOT_JAVA_FRAME = 0x03;
    private static final int ROOT_NATIVE_STACK = 0x04;
    private static final int ROOT_STICKY_CLASS = 0x05;
    private static final int ROOT_THREAD_BLOCK = 0x06;
    private static final int ROOT_MONITOR_USED = 0x07;
    private static final int ROOT_THREAD_OBJECT = 0x08;
    private static final int CLASS_DUMP = 0x20;
    private static final int INSTANCE_DUMP = 0x21;
    private static final int OBJECT_ARRAY_DUMP = 0x22;
    private static final int PRIMITIVE_ARRAY_DUMP = 0x23;

    /** Result of a repair: how many dangling sticky-class roots were rewritten. */
    record Result(int stickyClassRoots, int danglingRepaired, boolean scanned) {
    }

    private HprofSanitizer() {
    }

    static Result repairDanglingStickyClassRoots(Path hprof) throws IOException {
        Scan scan;
        try (InputStream in = Files.newInputStream(hprof)) {
            scan = scan(new CountingInput(new BufferedInputStream(in, 1 << 20)));
        } catch (UnsupportedRecordException e) {
            return new Result(0, 0, false);
        }
        if (scan.firstClassId == null) {
            return new Result(scan.stickyCount, 0, true);
        }
        int repaired = 0;
        ByteBuffer replacement = ByteBuffer.allocate(scan.idSize);
        if (scan.idSize == 8) {
            replacement.putLong(scan.firstClassId);
        } else {
            replacement.putInt((int) (long) scan.firstClassId);
        }
        try (FileChannel channel = FileChannel.open(hprof, StandardOpenOption.WRITE)) {
            for (int i = 0; i < scan.stickyCount; i++) {
                if (!scan.classIds.contains(scan.stickyIds[i])) {
                    replacement.rewind();
                    long position = scan.stickyPositions[i];
                    while (replacement.hasRemaining()) {
                        position += channel.write(replacement, position);
                    }
                    repaired++;
                }
            }
            if (repaired > 0) {
                channel.force(false);
            }
        }
        return new Result(scan.stickyCount, repaired, true);
    }

    private static Scan scan(CountingInput in) throws IOException {
        Scan scan = new Scan();
        int b;
        while ((b = in.readUnsignedByte()) != 0) {
            // "JAVA PROFILE 1.0.x" header, NUL-terminated
            if (in.position() > 64) {
                throw new UnsupportedRecordException();
            }
        }
        scan.idSize = in.readInt();
        if (scan.idSize != 4 && scan.idSize != 8) {
            throw new UnsupportedRecordException();
        }
        in.skip(8);
        while (true) {
            int tag;
            try {
                tag = in.readUnsignedByte();
            } catch (EOFException e) {
                return scan;
            }
            in.skip(4);
            long length = Integer.toUnsignedLong(in.readInt());
            if (tag == TAG_HEAP_DUMP || tag == TAG_HEAP_DUMP_SEGMENT) {
                scanHeapDump(in, in.position() + length, scan);
            } else {
                in.skip(length);
            }
        }
    }

    private static void scanHeapDump(CountingInput in, long end, Scan scan) throws IOException {
        int idSize = scan.idSize;
        while (in.position() < end) {
            int subTag = in.readUnsignedByte();
            switch (subTag) {
                case ROOT_UNKNOWN, ROOT_MONITOR_USED -> in.skip(idSize);
                case ROOT_JNI_GLOBAL -> in.skip(2L * idSize);
                case ROOT_JNI_LOCAL, ROOT_JAVA_FRAME, ROOT_THREAD_OBJECT -> in.skip(idSize + 8L);
                case ROOT_NATIVE_STACK, ROOT_THREAD_BLOCK -> in.skip(idSize + 4L);
                case ROOT_STICKY_CLASS -> scan.addSticky(in.position(), readId(in, idSize));
                case CLASS_DUMP -> scanClassDump(in, scan);
                case INSTANCE_DUMP -> {
                    in.skip(idSize + 4L + idSize);
                    in.skip(Integer.toUnsignedLong(in.readInt()));
                }
                case OBJECT_ARRAY_DUMP -> {
                    in.skip(idSize + 4L);
                    long count = Integer.toUnsignedLong(in.readInt());
                    in.skip(idSize + count * idSize);
                }
                case PRIMITIVE_ARRAY_DUMP -> {
                    in.skip(idSize + 4L);
                    long count = Integer.toUnsignedLong(in.readInt());
                    int size = valueSize(in.readUnsignedByte(), idSize);
                    in.skip(count * size);
                }
                default -> throw new UnsupportedRecordException();
            }
        }
    }

    private static void scanClassDump(CountingInput in, Scan scan) throws IOException {
        int idSize = scan.idSize;
        long classId = readId(in, idSize);
        scan.classIds.add(classId);
        if (scan.firstClassId == null) {
            scan.firstClassId = classId;
        }
        // stack trace serial, super, loader, signers, protection domain, 2 reserved, instance size
        in.skip(4L + 6L * idSize + 4L);
        int constants = in.readUnsignedShort();
        for (int i = 0; i < constants; i++) {
            in.skip(2);
            in.skip(valueSize(in.readUnsignedByte(), idSize));
        }
        int statics = in.readUnsignedShort();
        for (int i = 0; i < statics; i++) {
            in.skip(idSize);
            in.skip(valueSize(in.readUnsignedByte(), idSize));
        }
        int fields = in.readUnsignedShort();
        in.skip(fields * (idSize + 1L));
    }

    private static int valueSize(int type, int idSize) throws UnsupportedRecordException {
        return switch (type) {
            case 2 -> idSize;
            case 4, 8 -> 1;
            case 5, 9 -> 2;
            case 6, 10 -> 4;
            case 7, 11 -> 8;
            default -> throw new UnsupportedRecordException();
        };
    }

    private static long readId(CountingInput in, int idSize) throws IOException {
        return idSize == 8 ? in.readLong() : Integer.toUnsignedLong(in.readInt());
    }

    private static final class Scan {
        int idSize;
        Long firstClassId;
        final Set<Long> classIds = new HashSet<>();
        long[] stickyPositions = new long[1024];
        long[] stickyIds = new long[1024];
        int stickyCount;

        void addSticky(long position, long id) {
            if (stickyCount == stickyIds.length) {
                stickyPositions = Arrays.copyOf(stickyPositions, stickyCount * 2);
                stickyIds = Arrays.copyOf(stickyIds, stickyCount * 2);
            }
            stickyPositions[stickyCount] = position;
            stickyIds[stickyCount] = id;
            stickyCount++;
        }
    }

    /** Big-endian reader that knows its offset in the file. */
    private static final class CountingInput {
        private final DataInputStream in;
        private long position;

        CountingInput(InputStream in) {
            this.in = new DataInputStream(in);
        }

        long position() {
            return position;
        }

        int readUnsignedByte() throws IOException {
            int value = in.readUnsignedByte();
            position++;
            return value;
        }

        int readUnsignedShort() throws IOException {
            int value = in.readUnsignedShort();
            position += 2;
            return value;
        }

        int readInt() throws IOException {
            int value = in.readInt();
            position += 4;
            return value;
        }

        long readLong() throws IOException {
            long value = in.readLong();
            position += 8;
            return value;
        }

        void skip(long bytes) throws IOException {
            long remaining = bytes;
            while (remaining > 0) {
                long skipped = in.skip(remaining);
                if (skipped <= 0) {
                    if (in.read() < 0) {
                        throw new EOFException("Truncated HPROF file");
                    }
                    skipped = 1;
                }
                remaining -= skipped;
            }
            position += bytes;
        }
    }

    private static final class UnsupportedRecordException extends IOException {
        UnsupportedRecordException() {
            super("Unsupported HPROF record");
        }
    }
}
