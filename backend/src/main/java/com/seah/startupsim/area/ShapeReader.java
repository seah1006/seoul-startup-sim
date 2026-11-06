package com.seah.startupsim.area;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKBWriter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 원본 Polygon(5) SHP와 .cpg 인코딩의 DBF를 읽는다. 지원하지 않는 형식은 즉시 거절한다. */
public final class ShapeReader {
    public record Row(Map<String, String> fields, byte[] wkb) {}

    public List<Row> read(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("상권 SHP 파일이 없습니다: " + path.toAbsolutePath());
        }
        String stem = path.toString().replaceFirst("(?i)\\.shp$", "");
        ByteBuffer shp = ByteBuffer.wrap(Files.readAllBytes(path));
        ByteBuffer dbf = ByteBuffer.wrap(Files.readAllBytes(Path.of(stem + ".dbf"))).order(ByteOrder.LITTLE_ENDIAN);
        if (shp.getInt(0) != 9994 || shp.order(ByteOrder.LITTLE_ENDIAN).getInt(32) != 5) {
            throw new IllegalArgumentException("Polygon(5) SHP만 지원합니다.");
        }
        String cpg = Files.exists(Path.of(stem + ".cpg")) ? Files.readString(Path.of(stem + ".cpg")).trim() : "MS949";
        java.nio.charset.Charset charset = java.nio.charset.Charset.forName(cpg.equals("65001") ? "UTF-8" : cpg);
        int count = dbf.getInt(4);
        int header = Short.toUnsignedInt(dbf.getShort(8));
        int size = Short.toUnsignedInt(dbf.getShort(10));
        List<String> names = new ArrayList<>();
        List<Integer> lengths = new ArrayList<>();
        for (int p = 32; p < header - 1; p += 32) {
            byte[] name = new byte[11];
            dbf.position(p); dbf.get(name);
            names.add(new String(name, StandardCharsets.US_ASCII).replace("\0", "").trim());
            lengths.add(Byte.toUnsignedInt(dbf.get(p + 16)));
        }
        List<Row> rows = new ArrayList<>();
        GeometryFactory factory = new GeometryFactory();
        shp.position(100);
        for (int row = 0; row < count; row++) {
            shp.order(ByteOrder.BIG_ENDIAN);
            shp.getInt();
            int end = shp.position() + 4 + shp.getInt() * 2;
            shp.order(ByteOrder.LITTLE_ENDIAN);
            if (shp.getInt() != 5) throw new IllegalArgumentException("비 Polygon 레코드입니다.");
            shp.position(shp.position() + 32);
            int parts = shp.getInt(); int points = shp.getInt();
            int[] starts = new int[parts + 1];
            for (int i = 0; i < parts; i++) starts[i] = shp.getInt();
            starts[parts] = points;
            Coordinate[] coords = new Coordinate[points];
            for (int i = 0; i < points; i++) coords[i] = new Coordinate(shp.getDouble(), shp.getDouble());
            List<LinearRing> shells = new ArrayList<>();
            List<LinearRing> holes = new ArrayList<>();
            for (int i = 0; i < parts; i++) {
                Coordinate[] ring = java.util.Arrays.copyOfRange(coords, starts[i], starts[i + 1]);
                LinearRing linearRing = factory.createLinearRing(ring);
                // ESRI Shapefile: 외곽 링은 시계 방향, 구멍은 반시계 방향이다.
                if (Orientation.isCCW(ring)) holes.add(linearRing); else shells.add(linearRing);
            }
            List<Polygon> polygons = new ArrayList<>();
            Map<LinearRing, List<LinearRing>> assigned = new LinkedHashMap<>();
            for (LinearRing shell : shells) assigned.put(shell, new ArrayList<>());
            for (LinearRing hole : holes) {
                LinearRing owner = null;
                double smallest = Double.POSITIVE_INFINITY;
                for (LinearRing shell : shells) {
                    Polygon polygon = factory.createPolygon(shell);
                    if (polygon.covers(factory.createPolygon(hole).getInteriorPoint()) && polygon.getArea() < smallest) {
                        owner = shell; smallest = polygon.getArea();
                    }
                }
                if (owner == null) throw new IllegalArgumentException("외곽 링 없는 구멍입니다.");
                assigned.get(owner).add(hole);
            }
            for (var entry : assigned.entrySet()) polygons.add(factory.createPolygon(entry.getKey(), entry.getValue().toArray(LinearRing[]::new)));
            Geometry geometry = factory.createMultiPolygon(polygons.toArray(Polygon[]::new));
            if (geometry.isEmpty()) throw new IllegalArgumentException("빈 상권 폴리곤입니다.");
            shp.position(end);
            dbf.position(header + row * size);
            boolean deleted = dbf.get() == '*';
            Map<String, String> fields = new LinkedHashMap<>();
            for (int i = 0; i < names.size(); i++) {
                byte[] bytes = new byte[lengths.get(i)]; dbf.get(bytes);
                fields.put(names.get(i), new String(bytes, charset).trim());
            }
            if (!deleted) rows.add(new Row(fields, new WKBWriter().write(geometry)));
        }
        return rows;
    }
}
