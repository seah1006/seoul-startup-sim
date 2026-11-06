package com.seah.startupsim.catalog.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

/** 문자열 자연키와 WGS84 좌표를 사용하는 JPA 비교용 점포 엔티티다. */
@Entity
@Table(name = "stores")
public class StoreEntity {
    @Id
    @Column(name = "store_id")
    String id;
    @Column(name = "name")
    String name;
    @Column(name = "branch_name")
    String branch;
    @Column(name = "large_code")
    String large;
    @Column(name = "middle_code")
    String middle;
    @Column(name = "small_code")
    String small;
    @Column(name = "small_name")
    String smallName;
    @Column(name = "std_industry_code")
    String industry;
    @Column(name = "district_code")
    String district;
    @Column(name = "dong_code")
    String dong;
    @Column(name = "road_address")
    String address;

    @Column(columnDefinition = "geometry(Point,4326)")
    Point geom;
    String snapshot;
    @Column(name = "version_id")
    Long versionId;
    protected StoreEntity() {}
    StoreEntity(StoreRow row, String snapshot, Long versionId) {
        this.id = row.id();
        this.name = row.name();
        this.branch = row.branch();
        this.large = row.large();
        this.middle = row.middle();
        this.small = row.small();
        this.smallName = row.smallName();
        this.industry = row.industry();
        this.district = row.district();
        this.dong = row.dong();
        this.address = row.address();

        this.geom = new GeometryFactory(new PrecisionModel(), 4326)
                .createPoint(new Coordinate(row.lng(), row.lat()));
        this.snapshot = snapshot;
        this.versionId = versionId;
    }
}
