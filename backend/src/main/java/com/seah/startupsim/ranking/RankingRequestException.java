package com.seah.startupsim.ranking;

/** 랭킹 조회 범위 위반. 사유별 한국어 문구를 그대로 400 VALIDATION_FAILED 응답에 싣는다. */
public class RankingRequestException extends RuntimeException {
    public static final String DATE_RANGE = "최근 7일만 조회할 수 있어요.";
    public static final String DATE_FORMAT = "날짜는 2026-11-07 형식으로 보내 주세요.";
    public static final String KEY_REQUIRED = "업종/상권 랭킹에는 key가 필요해요.";
    public static final String KEY_LENGTH = "key는 32자 이하로 보내 주세요.";
    public static final String KIND = "랭킹 종류는 ALL, INDUSTRY, AREA 중 하나예요.";
    public static final String LIMIT = "limit은 1~100 사이여야 해요.";

    public RankingRequestException(String message) {
        super(message);
    }
}