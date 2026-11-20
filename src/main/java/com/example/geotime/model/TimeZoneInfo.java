package com.example.geotime.model;

public record TimeZoneInfo(
    String state,
    String county,
    String zoneId,
    String abbreviation
) {}
