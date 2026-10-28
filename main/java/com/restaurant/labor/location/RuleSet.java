package com.restaurant.labor.location;

/**
 * Overtime rules by jurisdiction. Kept as data on the location, so a law change is a config change,
 * not an API change.
 */
public enum RuleSet {
    /** US federal (FLSA): overtime after 40 hours in a workweek. */
    US_FEDERAL,
    /** California: daily overtime after 8h, double time after 12h, plus weekly overtime after 40h. */
    US_CALIFORNIA
}
