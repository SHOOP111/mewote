package io.lattice.tessellation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Immutable spatial snapshot with world/cell/bounds fast rejects and a bounded point cache. */
public final class RegionIndex {
    private static final int DEFAULT_CELL_SIZE = 64;
    private static final int MAX_CELLS_PER_REGION = 4096;
    private static final int CACHE_LIMIT = 16_384;
    private record Cell(String worldId, long x, long z) { }
    private record CacheKey(String worldId, long xBits, long zBits) { }

    private final String snapshotId;
    private final int cellSize;
    private final Map<Cell, List<Region>> cells;
    private final Map<String, List<Region>> largeRegions;
    private final Map<String, List<WorldOverride>> worldOverrides;
    private final ConcurrentHashMap<CacheKey, List<Region>> cache = new ConcurrentHashMap<>();

    public RegionIndex(String snapshotId, List<Region> regions, List<WorldOverride> overrides) {
        this(snapshotId, regions, overrides, DEFAULT_CELL_SIZE);
    }

    public RegionIndex(String snapshotId, List<Region> regions, List<WorldOverride> overrides, int cellSize) {
        this.snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
        if (cellSize < 1) throw new IllegalArgumentException("cellSize must be positive");
        this.cellSize = cellSize;
        Map<Cell, List<Region>> cellBuilder = new HashMap<>();
        Map<String, List<Region>> largeBuilder = new HashMap<>();
        for (Region region : regions) {
            long minCellX = cell(region.minX()), maxCellX = cell(region.maxX());
            long minCellZ = cell(region.minZ()), maxCellZ = cell(region.maxZ());
            long count;
            try { count = Math.multiplyExact(maxCellX - minCellX + 1, maxCellZ - minCellZ + 1); }
            catch (ArithmeticException overflow) { count = Long.MAX_VALUE; }
            if (count > MAX_CELLS_PER_REGION) {
                largeBuilder.computeIfAbsent(region.worldId(), ignored -> new ArrayList<>()).add(region);
                continue;
            }
            for (long x = minCellX; x <= maxCellX; x++) {
                for (long z = minCellZ; z <= maxCellZ; z++) {
                    cellBuilder.computeIfAbsent(new Cell(region.worldId(), x, z), ignored -> new ArrayList<>()).add(region);
                }
            }
        }
        this.cells = immutableLists(cellBuilder);
        this.largeRegions = immutableLists(largeBuilder);
        Map<String, List<WorldOverride>> overrideBuilder = new HashMap<>();
        if (overrides != null) for (WorldOverride override : overrides) {
            overrideBuilder.computeIfAbsent(override.worldId(), ignored -> new ArrayList<>()).add(override);
        }
        this.worldOverrides = immutableLists(overrideBuilder);
    }

    public List<Region> at(String worldId, double x, double z) {
        Objects.requireNonNull(worldId, "worldId");
        if (!Double.isFinite(x) || !Double.isFinite(z)) throw new IllegalArgumentException("Coordinates must be finite");
        if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) return List.of();
        CacheKey key = new CacheKey(worldId, Double.doubleToLongBits(x), Double.doubleToLongBits(z));
        List<Region> hit = cache.get(key);
        if (hit != null) return hit;
        long cx = cell(x), cz = cell(z);
        List<Region> local = cells.getOrDefault(new Cell(worldId, cx, cz), List.of());
        List<Region> broad = largeRegions.getOrDefault(worldId, List.of());
        List<Region> result = new ArrayList<>();
        for (Region region : local) if (region.contains(x, z)) result.add(region);
        for (Region region : broad) if (region.contains(x, z)) result.add(region);
        result.sort(java.util.Comparator.comparing(Region::id));
        List<Region> immutable = List.copyOf(result);
        if (cache.size() >= CACHE_LIMIT) cache.clear();
        List<Region> prior = cache.putIfAbsent(key, immutable);
        return prior == null ? immutable : prior;
    }

    public Set<String> regionIdsAt(String worldId, double x, double z) {
        return at(worldId, x, z).stream().map(Region::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public List<WorldOverride> overridesFor(String worldId) {
        return worldOverrides.getOrDefault(worldId, List.of());
    }

    public String snapshotId() { return snapshotId; }
    public int cacheSize() { return cache.size(); }

    private long cell(double coordinate) { return Math.floorDiv((long) Math.floor(coordinate), cellSize); }

    private static <K, V> Map<K, List<V>> immutableLists(Map<K, List<V>> source) {
        Map<K, List<V>> copy = new HashMap<>();
        source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
    }
}
