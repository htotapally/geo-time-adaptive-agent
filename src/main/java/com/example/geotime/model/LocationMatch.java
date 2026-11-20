package com.example.geotime.model;

public record LocationMatch(
    String city,
    String county,
    String state,
    double latitude,
    double longitude,
    boolean isSplitTimezoneState
) {}
