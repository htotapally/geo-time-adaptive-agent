package com.example.geotime.model;

import java.util.List;

public record LocationTimeReport(
    String queryCity,
    boolean isAmbiguous,
    String checkpointId,
    String statusMessage,
    List<ResolvedLocationTime> results
) {}
