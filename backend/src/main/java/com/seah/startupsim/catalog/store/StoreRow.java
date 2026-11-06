package com.seah.startupsim.catalog.store;

public record StoreRow(String id, String name, String branch, String large, String middle,
        String small, String smallName, String industry, String district, String dong,
        String address, double lng, double lat) {
    public Object[] parameters(String snapshot, Long version) {
        return new Object[]{id, name, branch, large, middle, small, smallName, industry,
                district, dong, address, lng, lat, snapshot, version};
    }
}
