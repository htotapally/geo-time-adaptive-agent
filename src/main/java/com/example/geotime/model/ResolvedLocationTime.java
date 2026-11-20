package com.example.geotime.model;

public record ResolvedLocationTime(
    String city,
    String county,
    String state,
    String zoneId,
    String localTimeIso
) {}
