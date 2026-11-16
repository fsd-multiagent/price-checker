# Epics and stories (dry-run fixture)

## Epic 1: Walking skeleton

### Story 1-1: EAN input

```yaml
key: 1-1-ean-input
title: Enter and validate an EAN
depends_on: []
touches: [web/src/ean/, backend/src/main/java/ean/]
sources: []
```

### Story 1-2: Retailer config

```yaml
key: 1-2-retailer-config
title: Load the retailer list from configuration
depends_on: []
touches: [backend/src/main/java/retailer/]
sources: []
```

### Story 1-3: Comparison page

Depends on both stories above, so it starts only once they are merged.

```yaml
key: 1-3-compare-page
title: Show the price comparison for an EAN
depends_on: [1-1-ean-input, 1-2-retailer-config]
touches: [web/src/compare/]
sources: []
```
