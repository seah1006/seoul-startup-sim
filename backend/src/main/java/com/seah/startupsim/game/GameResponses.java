package com.seah.startupsim.game;

import java.util.List;
import com.seah.startupsim.game.GameTypes.Background;
import com.seah.startupsim.game.GameTypes.Candidate;

/** 직렬화 경계에서도 임의 키 맵을 만들지 않는 게임 응답 계약. */
public final class GameResponses {
    private GameResponses() {}

    public record Guest(String guestToken, long userId, String nickname) {}
    public record Candidates(Background background, List<Candidate> candidates) {}
    public record DecisionSaved(int turn, boolean saved) {}
    public record Advanced(QuarterOutcome result, State game) {}
    public record ShopState(String areaCode, String areaName, String serviceCode,
            String serviceName, String shopName, double lng, double lat, long rivalCount) {}
    public record State(long gameId, long version, String status, String endingType, int currentTurn,
            String startQuarter, String lastQuarter, String currentQuarter, long cash, Owner owner,
            ShopState shop, boolean pendingDecision, boolean pendingTurningPoint) {
        public State withPendingTurningPoint(boolean pending) {
            return new State(gameId, version, status, endingType, currentTurn, startQuarter,
                    lastQuarter, currentQuarter, cash, owner, shop, pendingDecision, pending);
        }
    }
}
