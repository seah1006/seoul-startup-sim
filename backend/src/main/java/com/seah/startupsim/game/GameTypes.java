package com.seah.startupsim.game;

import java.util.List;
import java.util.Map;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;

/** 게임 API의 입력과 순수 계산 결과 계약. */
public final class GameTypes {
    private GameTypes() {}
    public enum Background { OFFICE_WORKER, CULINARY, PART_TIMER, LOCAL }
    public enum Price { LOW, NORMAL, HIGH }
    public enum Activity { MENU_DEV, REGULARS, STUDY, REST }
    public record Create(@NotBlank @Size(max=40) String ownerName, @NotNull Background background,
            @NotBlank String candidateId, long candidateSeed, @NotNull @Size(max=2) List<String> focusStats,
            String startQuarter) {}
    public record Shop(@NotBlank String areaCode, @NotBlank String serviceCode,
            double lng, double lat, @NotBlank @Size(max=80) String shopName) {}
    public record Decision(@NotNull Price priceLevel, @Min(6) @Max(16) int openHours,
            @Min(0) @Max(10) int staff, @Min(0) long marketingBudget, @NotNull Activity ownerActivity) {}
    public record Advance(@Min(0) long expectedVersion) {}
    public record Candidate(String candidateId, Map<String,Integer> stats, int total) {}
    public record Costs(long rent, long ingredients, long labor, long marketing, long total) {}
    public record StatChange(String stat, int before, int after) {}
    public record Result(int turn, String quarter, Map<String,Object> headline, long revenue,
            long visitors, long avgTicket, Costs costs, long profit, long cashAfter,
            Map<String,Integer> areaRank, List<StatChange> statChanges, Map<String,Object> areaNews,
            Object moment, List<Object> reactions, List<String> dataNotes) {}
}
