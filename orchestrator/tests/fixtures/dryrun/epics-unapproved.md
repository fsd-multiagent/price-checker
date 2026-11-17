# Epics and stories (one story needs an unapproved source)

```yaml
key: 1-1-ean-input
title: Enter and validate an EAN
depends_on: []
touches: [web/src/ean/]
sources: []
```

```yaml
key: 1-2-cel-adapter
title: Look up prices at a retailer that is only Proposed
depends_on: []
touches: [backend/src/main/java/retailer/cel/]
sources: [cel.ro]
```

```yaml
key: 1-3-compare-page
title: Show the price comparison
depends_on: [1-1-ean-input, 1-2-cel-adapter]
touches: [web/src/compare/]
sources: []
```

```yaml
key: 1-4-approved-adapter
title: Look up prices at an approved retailer
depends_on: []
touches: [backend/src/main/java/retailer/example/]
sources: [shop.example]
```
