package io.lattice.tessellation;

public record Point2(double x, double z) {
    public Point2 {
        if (!Double.isFinite(x) || !Double.isFinite(z)) throw new IllegalArgumentException("Coordinates must be finite");
        if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) throw new IllegalArgumentException("Coordinate exceeds the supported Minecraft world border");
    }
}
