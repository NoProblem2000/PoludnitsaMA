package com.poludnitsa.inspector.api.rest;

import com.poludnitsa.inspector.api.dto.InspectionRuleDto;
import com.poludnitsa.inspector.inspection.LeakHeuristicsEngine;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Registered leak inspection rules. Findings themselves belong to snapshots ({@code /api/snapshots/{id}/findings}). */
@RestController
@RequestMapping("/api/inspections")
public class InspectionController {

    private final LeakHeuristicsEngine engine;

    public InspectionController(LeakHeuristicsEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/rules")
    public List<InspectionRuleDto> rules() {
        return engine.describeRules();
    }
}
