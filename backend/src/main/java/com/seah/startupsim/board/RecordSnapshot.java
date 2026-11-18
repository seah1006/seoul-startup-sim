package com.seah.startupsim.board;

public record RecordSnapshot(long gameId, String endingType, String serviceCode, String serviceName,
        String areaCode, String areaName, int survivedQuarters, long totalProfit,
        String shopName, String ownerName, String shareUrl) {}
