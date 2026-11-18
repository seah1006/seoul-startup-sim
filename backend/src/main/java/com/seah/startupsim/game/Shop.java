package com.seah.startupsim.game;

/** 결산에 필요한 점포 위치·고정 상권 스냅샷. */
public record Shop(String areaCode, String areaName, String districtCode, String serviceCode,
        String serviceName, String shopName, double lng, double lat, long rivalCount, double areaM2) {}
