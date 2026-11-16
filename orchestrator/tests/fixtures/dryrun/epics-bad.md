# Epics and stories (bad fixture)

```yaml
key: 1-1-ean-input
title: Enter and validate an EAN
depends_on: []
touches: [web/src/ean/]
```

```yaml
key: 1-2-retailer-config
title: Load the retailer list
depends_on: [9-9-missing-story]
touches: [backend/]
sources: []
```
