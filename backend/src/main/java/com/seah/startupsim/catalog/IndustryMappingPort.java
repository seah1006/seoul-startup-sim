package com.seah.startupsim.catalog;

import java.util.List;
import java.util.Optional;

/** 검증된 소분류와 서비스 업종 대응을 제공한다. */
public interface IndustryMappingPort {
    Optional<String> toServiceCode(String sbizSmallCode);
    List<String> smallCodes(String serviceCode);
}

