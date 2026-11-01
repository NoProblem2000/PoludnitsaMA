package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * A thread of a deadlock cycle: what it waits for and who holds it.
 *
 * @param waitingForLock identity of the awaited lock ("class@hash")
 * @param heldLocks locks this thread holds
 * @param stack top of the stack (where it blocks)
 */
public record DeadlockNodeDto(
        long threadId,
        String threadName,
        JvmThreadState state,
        String waitingForLock,
        String waitingForLockClass,
        LockKind lockKind,
        long heldByThreadId,
        String heldByThreadName,
        List<String> heldLocks,
        List<StackFrameDto> stack
) {
    public DeadlockNodeDto {
        heldLocks = heldLocks == null ? List.of() : List.copyOf(heldLocks);
        stack = stack == null ? List.of() : List.copyOf(stack);
    }
}
