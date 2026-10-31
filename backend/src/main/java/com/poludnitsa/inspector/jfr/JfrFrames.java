package com.poludnitsa.inspector.jfr;

import com.poludnitsa.inspector.api.dto.AllocationFrameDto;
import com.poludnitsa.inspector.api.dto.PackageCategory;
import com.poludnitsa.inspector.common.JvmTypeNames;
import com.poludnitsa.inspector.common.PackageCategorizer;
import java.util.ArrayList;
import java.util.List;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;

/** JFR stack traces (of events parsed from files transferred from the target) as allocation frames. */
final class JfrFrames {

    private JfrFrames() {
    }

    /** Top first, at most {@code depth} frames; hidden frames (lambda forms, reflection) are skipped. */
    static List<AllocationFrameDto> frames(RecordedStackTrace stack, int depth) {
        if (stack == null) {
            return List.of();
        }
        List<AllocationFrameDto> frames = new ArrayList<>(Math.min(depth, stack.getFrames().size()));
        for (RecordedFrame frame : stack.getFrames()) {
            if (frames.size() >= depth) {
                break;
            }
            RecordedMethod method = frame.getMethod();
            if (method == null || method.isHidden() || method.getType() == null) {
                continue;
            }
            String className = JvmTypeNames.toJavaName(method.getType().getName());
            frames.add(new AllocationFrameDto(className, method.getName(), frame.getLineNumber(),
                    PackageCategorizer.categorize(className) == PackageCategory.APPLICATION));
        }
        return frames;
    }

    static boolean isJdk(String className) {
        PackageCategory category = PackageCategorizer.categorize(className);
        return category == PackageCategory.JDK || category == PackageCategory.PRIMITIVE_OR_ARRAY;
    }
}
