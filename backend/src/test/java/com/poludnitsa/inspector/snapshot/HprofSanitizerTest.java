package com.poludnitsa.inspector.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HprofSanitizerTest {

    private static final long CLASS_ID = 0x100;
    private static final long DANGLING_ID = 0x999;

    @TempDir
    Path dir;

    @Test
    void danglingStickyClassRootIsRepointedInPlace() throws IOException {
        Dump dump = dump(false);
        Path file = dir.resolve("heap.hprof");
        Files.write(file, dump.bytes);

        HprofSanitizer.Result result = HprofSanitizer.repairDanglingStickyClassRoots(file);

        assertThat(result).isEqualTo(new HprofSanitizer.Result(2, 1, true));
        byte[] repaired = Files.readAllBytes(file);
        assertThat(repaired).hasSameSizeAs(dump.bytes);
        assertThat(ByteBuffer.wrap(repaired, dump.danglingIdOffset, 8).getLong()).isEqualTo(CLASS_ID);
        // Everything except the 8 id bytes is untouched.
        System.arraycopy(dump.bytes, dump.danglingIdOffset, repaired, dump.danglingIdOffset, 0);
        ByteBuffer.wrap(repaired, dump.danglingIdOffset, 8).putLong(DANGLING_ID);
        assertThat(repaired).isEqualTo(dump.bytes);
    }

    @Test
    void unknownRecordLeavesFileUntouched() throws IOException {
        Dump dump = dump(true);
        Path file = dir.resolve("heap.hprof");
        Files.write(file, dump.bytes);

        HprofSanitizer.Result result = HprofSanitizer.repairDanglingStickyClassRoots(file);

        assertThat(result.scanned()).isFalse();
        assertThat(Files.readAllBytes(file)).isEqualTo(dump.bytes);
    }

    private record Dump(byte[] bytes, int danglingIdOffset) {
    }

    /** A minimal 8-byte-id dump covering every record type the scanner has to step over. */
    private static Dump dump(boolean withUnknownRecord) throws IOException {
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        DataOutputStream s = new DataOutputStream(segment);
        // CLASS_DUMP: id, serial, super, loader, signers, pd, 2 reserved, size, 1 constant, 1 static, 2 fields
        s.writeByte(0x20);
        s.writeLong(CLASS_ID);
        s.writeInt(1);
        for (int i = 0; i < 6; i++) {
            s.writeLong(0);
        }
        s.writeInt(16);
        s.writeShort(1);
        s.writeShort(7);
        s.writeByte(10);
        s.writeInt(42);
        s.writeShort(1);
        s.writeLong(0x500);
        s.writeByte(2);
        s.writeLong(0);
        s.writeShort(2);
        s.writeLong(0x501);
        s.writeByte(11);
        s.writeLong(0x502);
        s.writeByte(2);
        // valid and dangling sticky class roots
        s.writeByte(0x05);
        s.writeLong(CLASS_ID);
        s.writeByte(0x05);
        int danglingInSegment = s.size();
        s.writeLong(DANGLING_ID);
        // roots of other shapes
        s.writeByte(0x01);
        s.writeLong(0x200);
        s.writeLong(0x201);
        s.writeByte(0x03);
        s.writeLong(0x200);
        s.writeInt(1);
        s.writeInt(2);
        s.writeByte(0x08);
        s.writeLong(0x200);
        s.writeInt(1);
        s.writeInt(2);
        // INSTANCE_DUMP with 16 bytes of fields
        s.writeByte(0x21);
        s.writeLong(0x200);
        s.writeInt(1);
        s.writeLong(CLASS_ID);
        s.writeInt(16);
        s.write(new byte[16]);
        // OBJ_ARRAY_DUMP of 2 elements
        s.writeByte(0x22);
        s.writeLong(0x300);
        s.writeInt(1);
        s.writeInt(2);
        s.writeLong(CLASS_ID);
        s.writeLong(0x200);
        s.writeLong(0);
        // PRIM_ARRAY_DUMP: 3 chars
        s.writeByte(0x23);
        s.writeLong(0x400);
        s.writeInt(1);
        s.writeInt(3);
        s.writeByte(5);
        s.write(new byte[6]);
        if (withUnknownRecord) {
            s.writeByte(0x89);
            s.writeLong(0x200);
        }

        ByteArrayOutputStream file = new ByteArrayOutputStream();
        DataOutputStream f = new DataOutputStream(file);
        f.write("JAVA PROFILE 1.0.2".getBytes(StandardCharsets.US_ASCII));
        f.writeByte(0);
        f.writeInt(8);
        f.writeLong(System.currentTimeMillis());
        // STRING record before the heap dump
        byte[] name = "OrderService".getBytes(StandardCharsets.UTF_8);
        f.writeByte(0x01);
        f.writeInt(0);
        f.writeInt(8 + name.length);
        f.writeLong(0x700);
        f.write(name);
        // HEAP_DUMP_SEGMENT
        f.writeByte(0x1C);
        f.writeInt(0);
        f.writeInt(segment.size());
        int segmentStart = f.size();
        f.write(segment.toByteArray());
        // HEAP_DUMP_END
        f.writeByte(0x2C);
        f.writeInt(0);
        f.writeInt(0);
        return new Dump(file.toByteArray(), segmentStart + danglingInSegment);
    }
}
