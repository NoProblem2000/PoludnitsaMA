package com.poludnitsa.inspector.api.rest;

import com.poludnitsa.inspector.api.dto.GcRootPathDto;
import com.poludnitsa.inspector.api.dto.OqlQueryRequestDto;
import com.poludnitsa.inspector.api.dto.OqlResultDto;
import com.poludnitsa.inspector.api.dto.PathMode;
import com.poludnitsa.inspector.common.InspectorException;
import com.poludnitsa.inspector.oql.OqlExecutionService;
import com.poludnitsa.inspector.snapshot.OpenHeapRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Queries against heap dumps kept open after their analysis ({@code keepForQueries} on the deep snapshot request):
 * OQL, GC root paths of any object with a choice of reference kinds, and closing the dump.
 */
@RestController
@Validated
@RequestMapping("/api/snapshots")
public class HeapQueryController {

    private static final String ID = "^[A-Za-z0-9-]{1,64}$";

    private final OqlExecutionService oql;
    private final OpenHeapRegistry openHeaps;

    public HeapQueryController(OqlExecutionService oql, OpenHeapRegistry openHeaps) {
        this.oql = oql;
        this.openHeaps = openHeaps;
    }

    @PostMapping("/{id}/oql")
    public OqlResultDto query(@PathVariable @Pattern(regexp = ID) String id,
                              @Valid @RequestBody OqlQueryRequestDto request) {
        return oql.execute(id, request);
    }

    /** {@code objectId} as decimal or {@code 0x}-hex, as shown in the UI. */
    @GetMapping("/{id}/paths/{objectId}")
    public GcRootPathDto path(@PathVariable @Pattern(regexp = ID) String id,
                              @PathVariable @Pattern(regexp = "^(0x[0-9a-fA-F]{1,16}|[0-9]{1,19})$") String objectId,
                              @RequestParam(defaultValue = "STRONG_ONLY") PathMode mode) {
        return oql.path(id, parseId(objectId), mode);
    }

    /** Closes an open dump and deletes its files. */
    @DeleteMapping("/{id}/heap")
    public ResponseEntity<Void> closeHeap(@PathVariable @Pattern(regexp = ID) String id) {
        if (!openHeaps.close(id, "closed by user")) {
            throw new InspectorException(404, "HEAP_NOT_OPEN", "The heap dump of snapshot " + id + " is not open");
        }
        return ResponseEntity.noContent().build();
    }

    private static long parseId(String text) {
        return text.startsWith("0x") ? Long.parseUnsignedLong(text.substring(2), 16) : Long.parseLong(text);
    }
}
