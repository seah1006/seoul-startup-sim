package com.seah.startupsim.game;

/** 커밋 이후 부가 작업은 수신 측 TransactionalEventListener가 맡는다. */
public record GameEndedEvent(long gameId, String endingType) {}
