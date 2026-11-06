package com.seah.startupsim.game;
import java.util.List;
/** 정확히 대응하는 소상공인 업종 코드를 공급한다. */
public interface IndustryMappingPort { List<String> smallCodes(String serviceCode); }
