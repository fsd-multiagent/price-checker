package ro.pricechecker.lookup;

import java.util.List;

/** The outcome of one Lookup: the EAN and the ordered rows. */
public record LookupResult(String ean, List<LookupRow> rows) {}
