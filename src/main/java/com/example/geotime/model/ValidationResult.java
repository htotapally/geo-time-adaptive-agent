package com.example.geotime.model;

import java.util.List;

public record ValidationResult(
    boolean valid,
    List<String> errors
) {}
