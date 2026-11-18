package com.seah.startupsim.game;

import java.util.List;

/** 결산 입력과 저장에 필요한 시장 규모를 같은 스냅샷으로 유지한다. */
public record QuarterSnapshot(QuarterCalculator.Input input, List<String> dataNotes) {
    public long marketSize() {
        return input.marketSales() == null ? input.districtPerStore() * input.stores() : input.marketSales();
    }
}
