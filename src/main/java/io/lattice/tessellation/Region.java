package io.lattice.tessellation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Immutable simple polygon; boundaries are considered contained. */
public final class Region {
    private static final double EPSILON = 1.0e-9;
    private final String id;
    private final String worldId;
    private final List<Point2> polygon;
    private final Set<String> portalLinkedZones;
    private final double minX, maxX, minZ, maxZ;

    public Region(String id, String worldId, List<Point2> polygon, Set<String> portalLinkedZones) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9._-]{0,127}")) throw new IllegalArgumentException("Invalid region ID");
        if (worldId == null || worldId.isBlank()) throw new IllegalArgumentException("Stable world ID is required");
        if (polygon == null || polygon.size() < 3) throw new IllegalArgumentException("Polygon requires at least three vertices");
        this.id = id;
        this.worldId = worldId;
        this.polygon = List.copyOf(polygon);
        TreeSet<String> links = new TreeSet<>();
        if (portalLinkedZones != null) links.addAll(portalLinkedZones);
        this.portalLinkedZones = Set.copyOf(links);
        double lowX = Double.POSITIVE_INFINITY, highX = Double.NEGATIVE_INFINITY;
        double lowZ = Double.POSITIVE_INFINITY, highZ = Double.NEGATIVE_INFINITY;
        double signedArea = 0;
        for (int i = 0; i < this.polygon.size(); i++) {
            Point2 a = this.polygon.get(i);
            Point2 b = this.polygon.get((i + 1) % this.polygon.size());
            lowX = Math.min(lowX, a.x()); highX = Math.max(highX, a.x());
            lowZ = Math.min(lowZ, a.z()); highZ = Math.max(highZ, a.z());
            signedArea += a.x() * b.z() - b.x() * a.z();
        }
        if (Math.abs(signedArea) < EPSILON) throw new IllegalArgumentException("Polygon area must be non-zero");
        this.minX = lowX; this.maxX = highX; this.minZ = lowZ; this.maxZ = highZ;
    }

    public boolean contains(double x, double z) {
        if (x < minX - EPSILON || x > maxX + EPSILON || z < minZ - EPSILON || z > maxZ + EPSILON) return false;
        boolean inside = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            Point2 a = polygon.get(j), b = polygon.get(i);
            if (onSegment(x, z, a, b)) return true;
            boolean crosses = (b.z() > z) != (a.z() > z)
                    && x < (a.x() - b.x()) * (z - b.z()) / (a.z() - b.z()) + b.x();
            if (crosses) inside = !inside;
        }
        return inside;
    }

    private static boolean onSegment(double x, double z, Point2 a, Point2 b) {
        double cross = (x - a.x()) * (b.z() - a.z()) - (z - a.z()) * (b.x() - a.x());
        if (Math.abs(cross) > EPSILON) return false;
        return x >= Math.min(a.x(), b.x()) - EPSILON && x <= Math.max(a.x(), b.x()) + EPSILON
                && z >= Math.min(a.z(), b.z()) - EPSILON && z <= Math.max(a.z(), b.z()) + EPSILON;
    }

    public String id() { return id; }
    public String worldId() { return worldId; }
    public List<Point2> polygon() { return polygon; }
    public Set<String> portalLinkedZones() { return portalLinkedZones; }
    public double minX() { return minX; }
    public double maxX() { return maxX; }
    public double minZ() { return minZ; }
    public double maxZ() { return maxZ; }
}
