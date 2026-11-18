# TASK-004B 게임 콘텐츠 초안 (CONTENT_DRAFT)

> 작성: 게임 콘텐츠 설계자 (Gemini)
> 기준 문서: `docs/DESIGN.md` 3~8장, `AGENTS.md`, `QuarterCalculator.java`, 엔진 담당(Claude) `ENGINE_DRAFT.md` / `V8__events.sql` (DSL v1.1)
> 참고 수치: 초기 자본금 `50,000,000원`, 기본 분기 임대료 `9,000,000원`, 직원 1인당 분기 인건비 `6,000,000원`, 능력치 6종(`cooking`, `service`, `management`, `marketing`, `stamina`, `network`, 범위 `0~100`).
> 주의: 모든 가상 이벤트·반응·순간 문구에는 실제 상호·매체·플랫폼 이름을 사용하지 않고 가상 이름만 사용한다.

---

## 1. 전환점 이벤트 (12개)

### 1.1 기존 설계 예시 기반 (8개)

#### 1) `FRANCHISE_OFFER` — 프랜차이즈 가맹 제안
- **적용 업종**: `FOOD`, `SERVICE`, `RETAIL` (전체)
- **의도**: 상권 상위권에 안착한 중후반 매장에 주어지는 성장/독립 기로. 가맹비와 원가율 상승을 감수하고 브랜드 매출 버프를 받을지, 독립 브랜드의 자부심과 단골 충성도를 지킬지 선택한다.

```json
{
  "code": "FRANCHISE_OFFER",
  "title": "프랜차이즈 가맹 제안",
  "description": "서울 전역으로 매장을 늘리고 있는 신흥 브랜드 '하루한끼 본사'에서 가맹 전환을 제안해 왔다. 본사의 물류망과 인지도를 얻는 대신 가맹비와 원가 부담을 안게 되는데, 독립 매장으로 남을지 간판을 바꿔 달지 결정해야 한다.",
  "conditionExpr": {
    "all": [
      { "var": "quartersOperated", "op": ">=", "value": 8 },
      { "var": "areaRankPct", "op": "<=", "value": 0.3 },
      { "var": "cash", "op": ">=", "value": 25000000 },
      { "var": "flag.FRANCHISE_CONTRACT", "op": "!=", "value": true }
    ]
  },
  "baseProbability": 0.25,
  "cooldownQuarters": 12,
  "maxFires": 1,
  "priority": 60,
  "enabled": true,
  "options": [
    {
      "label": "가맹 계약 체결",
      "orderNo": 1,
      "successFormula": {
        "base": 0.55,
        "statBonus": [
          { "stat": "network", "per10": 0.05 },
          { "stat": "management", "per10": 0.03 }
        ],
        "min": 0.1,
        "max": 0.9
      },
      "onSuccess": [
        { "type": "CASH", "amount": -10000000, "memo": "프랜차이즈 가맹비" },
        { "type": "MODIFIER", "target": "revenue", "mode": "MULTIPLY", "value": 1.25, "quarters": 8, "label": "가맹 브랜드 매출 +25%" },
        { "type": "MODIFIER", "target": "costRate", "mode": "ADD", "value": 0.04, "quarters": 8, "label": "본사 물류 원가 +4%p" },
        { "type": "FLAG", "flag": "FRANCHISE_CONTRACT", "value": true },
        { "type": "STAT", "stat": "management", "delta": 3 },
        { "type": "STAT", "stat": "network", "delta": 2 }
      ],
      "onFail": [
        { "type": "CASH", "amount": -6000000, "memo": "가맹 인테리어 사전 실사 및 위약금" },
        { "type": "STAT", "stat": "stamina", "delta": -3 }
      ],
      "successText": "본사 물류와 브랜드 효과가 바로 붙으며 손님이 눈에 띄게 늘었습니다.",
      "failText": "리뉴얼 비용만 쓰고 본사와 조건이 어긋나 계약이 무산됐습니다."
    },
    {
      "label": "거절하고 독립 브랜드 유지",
      "orderNo": 2,
      "successFormula": { "base": 1.0, "min": 1.0, "max": 1.0 },
      "onSuccess": [
        { "type": "MODIFIER", "target": "visitors", "mode": "MULTIPLY", "value": 1.08, "quarters": 4, "label": "독립 매장 단골 호응 +8%" },
        { "type": "STAT", "stat": "service", "delta": 3 },
        { "type": "STAT", "stat": "cooking", "delta": 2 }
      ],
      "onFail": [],
      "successText": "우리 가게만의 색깔을 지키기로 했습니다. 단골들이 오히려 더 반깁니다.",
      "failText": "-"
    }
  ]
}
```

#### 2) `SECOND_BRANCH` — 2호점 확장
- **적용 업종**: `FOOD`, `SERVICE`, `RETAIL` (전체)
- **의도**: 자금과 수익이 충분히 쌓였을 때 매장 수(`SHOP_COUNT`)를 늘리는 핵심 확장 이벤트. 같은 상권(안정적/카니발라이제이션 위험) vs 인접 상권(고수익/상권 리스크) vs 보류 중 선택한다.

```json
{
  "code": "SECOND_BRANCH",
  "title": "2호점 확장",
  "description": "장사가 안정되면서 단골들 사이에서 분점 하나 더 내도 되겠다는 이야기가 나온다. 마침 근처에 조건이 괜찮은 빈 점포가 나왔다는 소식이 들렸는데, 지금 기세를 몰아 2호점을 열지 내실을 다질지 결정해야 한다.",
  "conditionExpr": {
    "all": [
      { "var": "quartersOperated", "op": ">=", "value": 6 },
      { "var": "cumulativeProfit", "op": ">=", "value": 30000000 },
      { "var": "cash", "op": ">=", "value": 40000000 },
      { "var": "shopCount", "op": "==", "value": 1 }
    ]
  },
  "baseProbability": 0.25,
  "cooldownQuarters": 8,
  "maxFires": 2,
  "priority": 70,
  "enabled": true,
  "options": [
    {
      "label": "같은 상권 내 2호점 오픈",
      "orderNo": 1,
      "successFormula": {
        "base": 0.6,
        "statBonus": [{ "stat": "management", "per10": 0.06 }],
        "min": 0.1,
        "max": 0.9
      },
      "onSuccess": [
        { "type": "SHOP_COUNT", "delta": 1 },
        { "type": "CASH", "amount": -15000000, "memo": "같은 상권 2호점 보증금·설비" },
        { "type": "MODIFIER", "target": "revenue", "mode": "MULTIPLY", "value": 1.35, "quarters": 8, "label": "2호점 합산 매출 +35%" },
        { "type": "MODIFIER", "target": "rent", "mode": "ADD", "value": 5000000, "quarters": 8, "label": "2호점 임대료 +500만" },
        { "type": "STAT", "stat": "management", "delta": 4 }
      ],
      "onFail": [
        { "type": "CASH", "amount": -10000000, "memo": "2호점 준비 무산 및 계약금 손실" },
        { "type": "STAT", "stat": "stamina", "delta": -4 }
      ],
      "successText": "같은 동네에 2호점을 열었습니다. 대기 줄이 분산되며 전체 매출이 크게 뛰었습니다.",
      "failText": "기존 손님만 나눠 먹는 꼴이 되어 초기 투자금을 회수하지 못했습니다."
    },
    {
      "label": "인접 상권에 2호점 오픈",
      "orderNo": 2,
      "successFormula": {
        "base": 0.45,
        "statBonus": [
          { "stat": "marketing", "per10": 0.04 },
          { "stat": "network", "per10": 0.04 }
        ],
        "min": 0.1,
        "max": 0.9
      },
      "onSuccess": [
        { "type": "SHOP_COUNT", "delta": 1 },
        { "type": "CASH", "amount": -18000000, "memo": "인접 상권 2호점 보증금·설비" },
        { "type": "MODIFIER", "target": "revenue", "mode": "MULTIPLY", "value": 1.5, "quarters": 8, "label": "인접 상권 2호점 매출 +50%" },
        { "type": "MODIFIER", "target": "rent", "mode": "ADD", "value": 6000000, "quarters": 8, "label": "2호점 임대료 +600만" },
        { "type": "STAT", "stat": "marketing", "delta": 4 },
        { "type": "STAT", "stat": "network", "delta": 2 }
      ],
      "onFail": [
        { "type": "CASH", "amount": -12000000, "memo": "신규 상권 2호점 철수 손실" },
        { "type": "STAT", "stat": "management", "delta": -2 },
        { "type": "STAT", "stat": "stamina", "delta": -4 }
      ],
      "successText": "새 동네에서도 입소문이 빠르게 퍼지며 2호점이 성공적으로 자리 잡았습니다.",
      "failText": "새 상권의 손님 취향을 맞추지 못해 큰 손실만 남겼습니다."
    },
    {
      "label": "내실을 다지며 보류",
      "orderNo": 3,
      "successFormula": { "base": 1.0, "min": 1.0, "max": 1.0 },
      "onSuccess": [
        { "type": "STAT", "stat": "management", "delta": 2 },
        { "type": "STAT", "stat": "stamina", "delta": 2 }
      ],
      "onFail": [],
      "successText": "무리한 확장 대신 지금 매장의 내실을 다지기로 했습니다.",
      "failText": "-"
    }
  ]
}
```

#### 3) `RENT_HIKE` — 임대료 인상 통보
- **적용 업종**: `FOOD`, `SERVICE`, `RETAIL` (전체)
- **의도**: 상권 매출이 오르거나 영업이 지속될 때 찾아오는 고정비 압박. 수용/협상(인맥)/이전(마케팅) 3지선다로 방어 전략을 시험한다.

```json
{
  "code": "RENT_HIKE",
  "title": "임대료 인상 통보",
  "description": "최근 상권 유동인구와 매출이 오르자 건물주 박 사장이 재계약 조건으로 월세 인상을 통보해 왔다. 그대로 받아들이자니 고정비 압박이 심하고 당장 가게를 옮기자니 단골 이탈이 걱정된다.",
  "conditionExpr": {
    "all": [
      { "var": "quartersOperated", "op": ">=", "value": 4 },
      {
        "any": [
          { "var": "area.salesTrendPct", "op": ">=", "value": 0.05 },
          { "var": "lastProfit", "op": ">", "value": 5000000 }
        ]
      }
    ]
  },
  "baseProbability": 0.25,
  "cooldownQuarters": 8,
  "maxFires": 2,
  "priority": 55,
  "enabled": true,
  "options": [
    {
      "label": "인상안 수용",
      "orderNo": 1,
      "successFormula": { "base": 1.0, "min": 1.0, "max": 1.0 },
      "onSuccess": [
        { "type": "MODIFIER", "target": "rent", "mode": "ADD", "value": 1500000, "quarters": 8, "label": "임대료 +150만" },
        { "type": "STAT", "stat": "stamina", "delta": -2 }
      ],
      "onFail": [],
      "successText": "아쉽지만 자리를 지키기 위해 인상된 임대료를 받아들였습니다.",
      "failText": "-"
    },
    {
      "label": "건물주와 임대료 협상",
      "orderNo": 2,
      "successFormula": {
        "base": 0.45,
        "statBonus": [
          { "stat": "network", "per10": 0.07 },
          { "stat": "management", "per10": 0.03 }
        ],
        "min": 0.1,
        "max": 0.9
      },
      "onSuccess": [
        { "type": "STAT", "stat": "network", "delta": 4 },
        { "type": "STAT", "stat": "management", "delta": 2 }
      ],
      "onFail": [
        { "type": "MODIFIER", "target": "rent", "mode": "ADD", "value": 2200000, "quarters": 8, "label": "협상 결렬 임대료 +220만" },
        { "type": "CASH", "amount": -3000000, "memo": "재계약 중개·조정 비용" },
        { "type": "STAT", "stat": "network", "delta": -2 }
      ],
      "successText": "그동안의 성실한 영업과 동네 평판 덕분에 임대료를 동결했습니다.",
      "failText": "협상이 틀어지며 더 불리한 조건으로 재계약을 맺었습니다."
    },
    {
      "label": "인근 골목으로 이전",
      "orderNo": 3,
      "successFormula": {
        "base": 0.65,
        "statBonus": [{ "stat": "marketing", "per10": 0.05 }],
        "min": 0.1,
        "max": 0.9
      },
      "onSuccess": [
        { "type": "CASH", "amount": -8000000, "memo": "매장 이전 및 간판 설치비" },
        { "type": "MODIFIER", "target": "rent", "mode": "ADD", "value": -1200000, "quarters": 8, "label": "이전 후 임대료 -120만" },
        { "type": "STAT", "stat": "marketing", "delta": 3 }
      ],
      "onFail": [
        { "type": "CASH", "amount": -10000000, "memo": "매장 이전 및 공백 손실" },
        { "type": "MODIFIER", "target": "visitors", "mode": "MULTIPLY", "value": 0.85, "quarters": 2, "label": "이전 초기 방문객 -15%" },
        { "type": "STAT", "stat": "stamina", "delta": -4 }
      ],
      "successText": "임대료가 저렴한 골목으로 깔끔하게 이전했고, 단골들도 금세 찾아왔습니다.",
      "failText": "이사 후 홍보가 부족해 한동안 손님 발길이 뚝 끊겼습니다."
    }
  ]
}
```

#### 4) `DELIVERY_APP` — 배달앱 입점
- **적용 업종**: `FOOD` (외식 업종 전용)
- **의도**: 초반(2분기 이후) 외식 업종 플레이어가 매출 볼륨을 키울지(수수료/원가 부담), 홀 품질에 집중할지 선택하는 분기점.

```json
{
  "code": "DELIVERY_APP",
  "title": "배달앱 입점",
  "description": "동네 주민들이 자주 쓰는 배달 플랫폼 '달려라배달' 영업 담당자가 수수료 할인 혜택을 앞세워 입점을 권유했다. 비 오는 날이나 늦은 밤에도 주문을 받을 수 있지만 수수료와 포장 비용, 리뷰 관리 부담이 뒤따른다.",
  "conditionExpr": {
    "all": [
      { "var": "industryCategory", "op": "==", "value": "FOOD" },
      { "var": "quartersOperated", "op": ">=", "value": 2 }
    ]
  },
  "baseProbability": 0.3,
  "cooldownQuarters": 10,
  "maxFires": 1,
  "priority": 45,
  "enabled": true,
  "options": [
    {
      "label": "배달 플랫폼 입점",
      "orderNo": 1,
      "successFormula": {
        "base": 0.65,
        "statBonus": [{ "stat": "marketing", "per10": 0.05 }],
        "min": 0.1,
        "max": 0.9
      },
      "onSuccess": [
        { "type": "CASH", "amount": -2000000, "memo": "배달 포장재 및 초기 광고비" },
        { "type": "MODIFIER", "target": "revenue", "mode": "MULTIPLY", "value": 1.18, "quarters": 8, "label": "배달 주문 매출 +18%" },
        { "type": "MODIFIER", "target": "costRate", "mode": "ADD", "value": 0.03, "quarters": 8, "label": "배달 수수료·포장비 +3%p" },
        { "type": "FLAG", "flag": "DELIVERY_ENABLED", "value": true },
        { "type": "STAT", "stat": "marketing", "delta": 3 }
      ],
      "onFail": [
        { "type": "CASH", "amount": -4000000, "memo": "배달 광고비 및 수수료 손실" },
        { "type": "STAT", "stat": "service", "delta": -2 },
        { "type": "STAT", "stat": "stamina", "delta": -3 }
      ],
      "successText": "배달 주문이 꾸준히 들어오며 비수기 매출을 든든하게 받쳐 줍니다.",
      "failText": "배달 수수료와 광고비만 빠져나가고 남는 이익이 거의 없었습니다."
    },
    {
      "label": "홀 영업과 방문 손님에 집중",
      "orderNo": 2,
      "successFormula": { "base": 1.0, "min": 1.0, "max": 1.0 },
      "onSuccess": [
        { "type": "MODIFIER", "target": "costRate", "mode": "ADD", "value": -0.02, "quarters": 4, "label": "홀 집중 원가 절감 -2%p" },
        { "type": "STAT", "stat": "cooking", "delta": 2 },
        { "type": "STAT", "stat": "service", "delta": 2 }
      ],
      "onFail": [],
      "successText": "수수료 부담 없이 매장을 찾은 손님들에게 최상의 음식을 내어주기로 했습니다.",
      "failText": "-"
    }
  ]
}
```

#### 5) `CREATOR_COLLAB` — 맛집 크리에이터 섭외
- **적용 업종**: `FOOD`, `SERVICE`, `RETAIL` (전체)
- **의도**: 상품력이나 서비스가 일정 수준 이상일 때 찾아오는 바이럴 기회. 준비가 부족하면 손님 폭주 역풍을 맞을 수 있다.

```json
{
  "code": "CREATOR_COLLAB",
  "title": "맛집 크리에이터 섭외",
  "description": "구독자 30만 명을 보유한 동네 탐방 영상 크리에이터 '맛따라길따라' 팀에서 촬영 협조 요청이 왔다. 영상이 화제가 되면 대박이지만, 갑작스러운 손님 폭주에 응대 품질이 무너지면 오히려 역효과가 날 수 있다.",
  "conditionExpr": {
    "all": [
      { "var": "quartersOperated", "op": ">=", "value": 3 },
      {
        "any": [
          { "var": "stat.cooking", "op": ">=", "value": 50 },
          { "var": "stat.service", "op": ">=", "value": 50 },
          { "var": "stat.marketing", "op": ">=", "value": 50 }
        ]
      }
    ]
  },
  "baseProbability": 0.22,
  "cooldownQuarters": 6,
  "maxFires": 2,
  "priority": 50,
  "enabled": true,
  "options": [
    {
      "label": "촬영 수락 및 적극 협조",
      "orderNo": 1,
      "successFormula": {
        "base": 0.5,
        "statBonus": [
          { "stat": "cooking", "per10": 0.04 },
          { "stat": "service", "per10": 0.04 }
        ],
        "min": 0.1,
        "max": 0.9
      },
      "onSuccess": [
        { "type": "MODIFIER", "target": "visitors", "mode": "MULTIPLY", "value": 1.35, "quarters": 2, "label": "크리에이터 영상 효과 +35%" },
        { "type": "FLAG", "flag": "CREATOR_HIT", "value": true },
        { "type": "STAT", "stat": "marketing", "delta": 5 },
        { "type": "STAT", "stat": "network", "delta": 2 }
      ],
      "onFail": [
        { "type": "MODIFIER", "target": "visitors", "mode": "MULTIPLY", "value": 0.9, "quarters": 1, "label": "응대 미숙 역풍 -10%" },
        { "type": "STAT", "stat": "service", "delta": -3 },
        { "type": "STAT", "stat": "stamina", "delta": -5 }
      ],
      "successText": "영상이 화제가 되며 주말마다 가게 앞에 긴 대기 줄이 생겼습니다.",
      "failText": "몰려든 손님을 감당하지 못해 불친절 후기가 쏟아졌습니다."
    },
    {
      "label": "정중히 거절",
      "orderNo": 2,
      "successFormula": { "base": 1.0, "min": 1.0, "max": 1.0 },
      "onSuccess": [
        { "type": "STAT", "stat": "service", "delta": 2 },
        { "type": "STAT", "stat": "stamina", "delta": 2 }
      ],
      "onFail": [],
      "successText": "조용히 내실을 다지며 단골 손님들을 더 정성껏 맞이했습니다.",
      "failText": "-"
    }
  ]
}
```

#### 6) `REDEVELOPMENT_RUMOR` — 재개발 소문
- **적용 업종**: `FOOD`, `SERVICE`, `RETAIL` (전체)
- **의도**: 실제 상권 데이터(`area.closingCount` 또는 `area.closingRateDelta`)가 악화될 때 발생하는 위기형 전환점. 버티기(경쟁점 이탈 반사이익 노림) vs 이전(권리금 확보) vs 자산 정리 중 고른다.

```json
{
  "code": "REDEVELOPMENT_RUMOR",
  "title": "재개발 소문",
  "description": "요즘 상권 내 오래된 건물 몇 곳이 비더니 구청에 재개발 추진위원회가 들어섰다는 소문이 돌기 시작했다. 실제로 주변 점포들이 하나둘 문을 닫으며 골목 분위기가 어수선한데, 끝까지 버틸지 자리를 옮길지 정해야 한다.",
  "conditionExpr": {
    "all": [
      { "var": "quartersOperated", "op": ">=", "value": 5 },
      {
        "any": [
          { "var": "area.closingCount", "op": ">=", "value": 3 },
          { "var": "area.closingRateDelta", "op": ">", "value": 0.5 }
        ]
      }
    ]
  },
  "baseProbability": 0.25,
  "cooldownQuarters": 10,
  "maxFires": 2,
  "priority": 65,
  "enabled": true,
  "options": [
    {
      "label": "끝까지 버티며 상권 지키기",
      "orderNo": 1,
      "successFormula": {
        "base": 0.5,
        "statBonus": [
          { "stat": "management", "per10": 0.05 },
          { "stat": "network", "per10": 0.04 }
        ],
        "min": 0.1,
        "max": 0.9
      },
      "onSuccess": [
        { "type": "MODIFIER", "target": "revenue", "mode": "MULTIPLY", "value": 1.15, "quarters": 3, "label": "빈 상권 생존 반사이익 +15%" },
        { "type": "STAT", "stat": "management", "delta": 3 },
        { "type": "STAT", "stat": "stamina", "delta": -2 }
      ],
      "onFail": [
        { "type": "CASH", "amount": -8000000, "memo": "상권 침체 및 유동인구 감소 손실" },
        { "type": "MODIFIER", "target": "visitors", "mode": "MULTIPLY", "value": 0.85, "quarters": 2, "label": "유동인구 급감 -15%" }
      ],
      "successText": "소문이 잠잠해지자 빈자리를 채운 건 우리 가게를 찾은 손님들이었습니다.",
      "failText": "어수선한 분위기 속에 발길이 줄어 적지 않은 손실을 입었습니다."
    },
    {
      "label": "권리금 받고 인근으로 이전",
      "orderNo": 2,
      "successFormula": {
        "base": 0.7,
        "statBonus": [{ "stat": "network", "per10": 0.04 }],
        "min": 0.1,
        "max": 0.9
      },
      "onSuccess": [
        { "type": "CASH", "amount": 5000000, "memo": "권리금 정산 및 이전 차익" },
        { "type": "STAT", "stat": "network", "delta": 2 }
      ],
      "onFail": [
        { "type": "CASH", "amount": -6000000, "memo": "급매 이전 손실" },
        { "type": "STAT", "stat": "stamina", "delta": -3 }
      ],
      "successText": "제때 발을 빼고 새 자리에 무사히 둥지를 틀었습니다.",
      "failText": "급하게 점포를 넘기느라 인테리어 비용도 건지지 못했습니다."
    },
    {
      "label": "집기 일부 정리하며 현금 확보",
      "orderNo": 3,
      "successFormula": { "base": 1.0, "min": 1.0, "max": 1.0 },
      "onSuccess": [
        { "type": "CASH", "amount": 3000000, "memo": "유휴 장비·집기 매각" },
        { "type": "STAT", "stat": "management", "delta": 2 }
      ],
      "onFail": [],
      "successText": "장비와 집기를 일부 정리해 현금을 확보하고 다음 기회를 준비합니다.",
      "failText": "-"
    }
  ]
}
```

#### 7) `STAFF_TURNOVER` — 직원 이탈
- **적용 업종**: `FOOD`, `SERVICE`, `RETAIL` (전체)
- **의도**: 직원을 2명 이상 고용했는데 경영/서비스 관리가 소홀할 때 발생. 인센티브로 잡을지, 재채용할지, 혼자 버틸지 선택한다.

```json
{
  "code": "STAFF_TURNOVER",
  "title": "직원 이탈",
  "description": "가게의 핵심 역할을 하던 경력 직원 김 대리가 개인 사정과 처우 불만을 이유로 이번 달까지만 일하겠다고 통보했다. 당장 다음 분기부터 피크 타임 인력이 구멍 날 위기인데 어떻게 대처할지 정해야 한다.",
  "conditionExpr": {
    "all": [
      { "var": "staff", "op": ">=", "value": 2 },
      {
        "any": [
          { "var": "stat.management", "op": "<=", "value": 50 },
          { "var": "stat.service", "op": "<=", "value": 45 }
        ]
      }
    ]
  },
  "baseProbability": 0.28,
  "cooldownQuarters": 6,
  "maxFires": 3,
  "priority": 60,
  "enabled": true,
  "options": [
    {
      "label": "연봉 인상 및 처우 개선으로 만류",
      "orderNo": 1,
      "successFormula": {
        "base": 0.6,
        "statBonus": [
          { "stat": "service", "per10": 0.04 },
          { "stat": "network", "per10": 0.04 }
        ],
        "min": 0.1,
        "max": 0.9
      },
      "onSuccess": [
        { "type": "CASH", "amount": -3000000, "memo": "핵심 직원 인센티브 지급" },
        { "type": "STAT", "stat": "service", "delta": 3 },
        { "type": "STAT", "stat": "management", "delta": 2 }
      ],
      "onFail": [
        { "type": "CASH", "amount": -2000000, "memo": "퇴직 정산 및 공백 손실" },
        { "type": "MODIFIER", "target": "visitors", "mode": "MULTIPLY", "value": 0.9, "quarters": 1, "label": "인력 공백 처리량 저하 -10%" },
        { "type": "STAT", "stat": "stamina", "delta": -3 }
      ],
      "successText": "진심 어린 대우에 마음을 돌린 직원이 전보다 더 열심히 일합니다.",
      "failText": "처우 개선에도 불구하고 끝내 퇴사를 막지 못했습니다."
    },
    {
      "label": "급히 신규 직원 채용",
      "orderNo": 2,
      "successFormula": {
        "base": 0.55,
        "statBonus": [{ "stat": "management", "per10": 0.05 }],
        "min": 0.1,
        "max": 0.9
      },
      "onSuccess": [
        { "type": "CASH", "amount": -2500000, "memo": "신규 채용 및 교육비" },
        { "type": "STAT", "stat": "management", "delta": 2 }
      ],
      "onFail": [
        { "type": "CASH", "amount": -4500000, "memo": "수습 이탈 및 재채용 비용" },
        { "type": "STAT", "stat": "service", "delta": -2 },
        { "type": "STAT", "stat": "stamina", "delta": -3 }
      ],
      "successText": "다행히 손발이 잘 맞는 신입을 뽑아 공백 없이 운영을 이어갔습니다.",
      "failText": "신입 교육에 시간과 비용만 쏟고 다시 구인 공고를 올려야 했습니다."
    },
    {
      "label": "당분간 사장님이 직접 커버",
      "orderNo": 3,
      "successFormula": { "base": 1.0, "min": 1.0, "max": 1.0 },
      "onSuccess": [
        { "type": "MODIFIER", "target": "visitors", "mode": "MULTIPLY", "value": 0.92, "quarters": 1, "label": "1인 커버 회전율 저하 -8%" },
        { "type": "STAT", "stat": "stamina", "delta": -6 },
        { "type": "STAT", "stat": "cooking", "delta": 1 }
      ],
      "onFail": [],
      "successText": "인건비 부담은 줄였지만 몸이 열 개라도 모자란 한 분기를 보냈습니다.",
      "failText": "-"
    }
  ]
}
```

#### 8) `BURNOUT` — 번아웃
- **적용 업종**: `FOOD`, `SERVICE`, `RETAIL` (전체)
- **의도**: 체력이 낮은 상태에서 장시간 영업을 무리하게 이어갈 때 발생하는 경고성 전환점.

```json
{
  "code": "BURNOUT",
  "title": "번아웃",
  "description": "매일 새벽부터 마감까지 쉬는 날 없이 매장을 지키다 보니 어느 날 아침 몸이 천근만근 무겁다. 손님 응대도 예전처럼 웃으며 하기 힘들고 실수가 잦아졌는데, 잠시 쉬어 갈지 끝까지 강행할지 결정해야 한다.",
  "conditionExpr": {
    "all": [
      { "var": "stat.stamina", "op": "<=", "value": 35 },
      { "var": "openHours", "op": ">=", "value": 14 }
    ]
  },
  "baseProbability": 0.35,
  "cooldownQuarters": 5,
  "maxFires": 3,
  "priority": 80,
  "enabled": true,
  "options": [
    {
      "label": "단축 영업 및 충분한 휴식 선언",
      "orderNo": 1,
      "successFormula": { "base": 1.0, "min": 1.0, "max": 1.0 },
      "onSuccess": [
        { "type": "MODIFIER", "target": "revenue", "mode": "MULTIPLY", "value": 0.85, "quarters": 1, "label": "휴식 단축 영업 -15%" },
        { "type": "STAT", "stat": "stamina", "delta": 10 },
        { "type": "STAT", "stat": "service", "delta": 1 }
      ],
      "onFail": [],
      "successText": "잠시 속도를 늦추고 체력을 회복했습니다. 다시 달릴 힘이 생겼습니다.",
      "failText": "-"
    },
    {
      "label": "약 먹으며 영업 강행",
      "orderNo": 2,
      "successFormula": {
        "base": 0.35,
        "statBonus": [{ "stat": "stamina", "per10": 0.08 }],
        "min": 0.1,
        "max": 0.9
      },
      "onSuccess": [
        { "type": "STAT", "stat": "stamina", "delta": -4 },
        { "type": "STAT", "stat": "management", "delta": 2 }
      ],
      "onFail": [
        { "type": "CASH", "amount": -5000000, "memo": "병원 치료비 및 임시 휴업 손실" },
        { "type": "STAT", "stat": "stamina", "delta": -10 },
        { "type": "STAT", "stat": "cooking", "delta": -2 },
        { "type": "STAT", "stat": "service", "delta": -2 }
      ],
      "successText": "악착같이 버텨내며 매출 구멍을 막았습니다. 다만 피로는 누적됐습니다.",
      "failText": "무리한 강행 끝에 몸져누워 치료비와 휴업 손실이 겹쳤습니다."
    }
  ]
}
```

---

### 1.2 신규 추가 전환점 (4개)

#### 9) `HYGIENE_INSPECTION` — 위생·안전 불시 점검
- **적용 업종**: `FOOD` (외식 업종 전용)
- **의도**: 경영·조리 관리가 잘된 외식 매장은 우수 업소 인증 보너스를 받고, 준비가 안 된 매장은 비용을 들여 수습하거나 과태료 위험을 감수한다.

```json
{
  "code": "HYGIENE_INSPECTION",
  "title": "위생·안전 불시 점검",
  "description": "구청 위생과에서 상권 내 음식점을 대상으로 불시 위생·원산지 점검에 나섰다. 평소 주방 청결과 식자재 보관에 신경 써 왔다면 '위생 우수 업소' 인증을 받을 기회지만, 관리 소홀이 적발되면 과태료를 피할 수 없다.",
  "conditionExpr": {
    "all": [
      { "var": "industryCategory", "op": "==", "value": "FOOD" },
      { "var": "quartersOperated", "op": ">=", "value": 3 }
    ]
  },
  "baseProbability": 0.22,
  "cooldownQuarters": 8,
  "maxFires": 2,
  "priority": 40,
  "enabled": true,
  "options": [
    {
      "label": "정면으로 점검 수검 (우수 업소 도전)",
      "orderNo": 1,
      "successFormula": {
        "base": 0.55,
        "statBonus": [
          { "stat": "management", "per10": 0.06 },
          { "stat": "cooking", "per10": 0.03 }
        ],
        "min": 0.1,
        "max": 0.9
      },
      "onSuccess": [
        { "type": "MODIFIER", "target": "visitors", "mode": "MULTIPLY", "value": 1.12, "quarters": 3, "label": "위생 우수 업소 신뢰 +12%" },
        { "type": "FLAG", "flag": "HYGIENE_CERTIFIED", "value": true },
        { "type": "STAT", "stat": "service", "delta": 3 },
        { "type": "STAT", "stat": "management", "delta": 2 }
      ],
      "onFail": [
        { "type": "CASH", "amount": -4000000, "memo": "위생 시정 명령 및 과태료" },
        { "type": "STAT", "stat": "service", "delta": -3 }
      ],
      "successText": "모범 위생 업소 표지판을 달았습니다. 가족 단위 손님이 부쩍 늘었습니다.",
      "failText": "식자재 라벨링 미비로 과태료를 물고 동네에 소문이 돌았습니다."
    },
    {
      "label": "점검 전 긴급 대청소 및 설비 보수",
      "orderNo": 2,
      "successFormula": { "base": 1.0, "min": 1.0, "max": 1.0 },
      "onSuccess": [
        { "type": "CASH", "amount": -2000000, "memo": "긴급 방역 및 주방 설비 보수" },
        { "type": "STAT", "stat": "management", "delta": 2 },
        { "type": "STAT", "stat": "stamina", "delta": -2 }
      ],
      "onFail": [],
      "successText": "비용을 들여 주방을 말끔히 정비한 덕분에 무탈하게 점검을 넘겼습니다.",
      "failText": "-"
    }
  ]
}
```

#### 10) `LOCAL_FESTIVAL` — 지역 골목 축제 부스 참여
- **적용 업종**: `FOOD`, `RETAIL` (외식·소매 업종)
- **의도**: 초반(2분기 이후)에도 발생 가능한 지역 이벤트. 목돈을 들여 큰 홍보·수익을 노릴지, 소액 판촉으로 안전하게 갈지 선택한다.

```json
{
  "code": "LOCAL_FESTIVAL",
  "title": "지역 골목 축제 부스 참여",
  "description": "상인회에서 주말마다 열리는 '별빛 골목 마켓' 참여 매장을 모집하고 있다. 유동인구가 평소보다 크게 몰리는 대목이지만 부스 참가비와 추가 물량 준비 부담이 적지 않다.",
  "conditionExpr": {
    "all": [
      { "var": "industryCategory", "op": "in", "value": ["FOOD", "RETAIL"] },
      { "var": "quartersOperated", "op": ">=", "value": 2 }
    ]
  },
  "baseProbability": 0.25,
  "cooldownQuarters": 6,
  "maxFires": 2,
  "priority": 40,
  "enabled": true,
  "options": [
    {
      "label": "대형 부스 참여 및 이벤트 진행",
      "orderNo": 1,
      "successFormula": {
        "base": 0.55,
        "statBonus": [
          { "stat": "marketing", "per10": 0.05 },
          { "stat": "stamina", "per10": 0.03 }
        ],
        "min": 0.1,
        "max": 0.9
      },
      "onSuccess": [
        { "type": "CASH", "amount": 6000000, "memo": "별빛 골목 마켓 부스 순수익" },
        { "type": "FLAG", "flag": "FESTIVAL_STAR", "value": true },
        { "type": "STAT", "stat": "marketing", "delta": 4 },
        { "type": "STAT", "stat": "network", "delta": 3 }
      ],
      "onFail": [
        { "type": "CASH", "amount": -5000000, "memo": "우천 행사 부진 및 재고 손실" },
        { "type": "STAT", "stat": "stamina", "delta": -4 }
      ],
      "successText": "준비한 물량이 조기 완판되며 동네 주민들에게 확실히 눈도장을 찍었습니다.",
      "failText": "궂은 날씨에 발길이 끊겨 준비한 재료와 참가비만 날렸습니다."
    },
    {
      "label": "매장 앞 소규모 시식·할인만 운영",
      "orderNo": 2,
      "successFormula": { "base": 1.0, "min": 1.0, "max": 1.0 },
      "onSuccess": [
        { "type": "CASH", "amount": -1000000, "memo": "소규모 판촉 행사비" },
        { "type": "STAT", "stat": "marketing", "delta": 2 },
        { "type": "STAT", "stat": "service", "delta": 1 }
      ],
      "onFail": [],
      "successText": "큰 부담 없이 축제 인파 일부를 매장으로 끌어들이는 데 성공했습니다.",
      "failText": "-"
    }
  ]
}
```

#### 11) `CORPORATE_CATERING` — 인근 오피스 단체·정기 납품 제안
- **적용 업종**: `FOOD`, `SERVICE`, `RETAIL` (전체)
- **의도**: 경영이나 인맥이 높은 사장님에게 주어지는 B2B 고정 매출 기회. 장기 정기 계약(고수익/위약금 리스크) vs 단발성 납품(안전) 중 선택한다.

```json
{
  "code": "CORPORATE_CATERING",
  "title": "인근 오피스 단체·정기 납품 제안",
  "description": "가까운 업무지구에 있는 중견기업 '서연테크' 총무팀에서 직원 복지용 정기 납품·제휴 계약을 제안했다. 고정 매출처가 생기면 실적 안정성이 높아지지만 납기 준수와 단가 할인 요구를 감당할 여력이 있는지 따져봐야 한다.",
  "conditionExpr": {
    "all": [
      { "var": "quartersOperated", "op": ">=", "value": 4 },
      {
        "any": [
          { "var": "stat.management", "op": ">=", "value": 45 },
          { "var": "stat.network", "op": ">=", "value": 45 }
        ]
      }
    ]
  },
  "baseProbability": 0.22,
  "cooldownQuarters": 8,
  "maxFires": 2,
  "priority": 45,
  "enabled": true,
  "options": [
    {
      "label": "정기 제휴 계약 체결",
      "orderNo": 1,
      "successFormula": {
        "base": 0.55,
        "statBonus": [
          { "stat": "management", "per10": 0.05 },
          { "stat": "network", "per10": 0.04 }
        ],
        "min": 0.1,
        "max": 0.9
      },
      "onSuccess": [
        { "type": "MODIFIER", "target": "revenue", "mode": "MULTIPLY", "value": 1.15, "quarters": 4, "label": "기업 정기 납품 매출 +15%" },
        { "type": "STAT", "stat": "management", "delta": 3 },
        { "type": "STAT", "stat": "network", "delta": 3 }
      ],
      "onFail": [
        { "type": "CASH", "amount": -5000000, "memo": "납기 클레임 및 계약 해지 위약금" },
        { "type": "STAT", "stat": "management", "delta": -2 },
        { "type": "STAT", "stat": "stamina", "delta": -3 }
      ],
      "successText": "매달 안정적인 법인 매출이 들어오며 가게 운영에 여유가 생겼습니다.",
      "failText": "단체 물량을 맞추다 품질이 흔들려 결국 계약을 중도 해지했습니다."
    },
    {
      "label": "단발성 체험 납품으로 간 보기",
      "orderNo": 2,
      "successFormula": {
        "base": 0.8,
        "statBonus": [{ "stat": "service", "per10": 0.02 }],
        "min": 0.1,
        "max": 0.9
      },
      "onSuccess": [
        { "type": "CASH", "amount": 2500000, "memo": "단발성 기업 단체 주문 수익" },
        { "type": "STAT", "stat": "network", "delta": 2 }
      ],
      "onFail": [
        { "type": "CASH", "amount": -1000000, "memo": "단체 주문 준비 비용 초과" }
      ],
      "successText": "무리하지 않는 선에서 단체 주문을 소화하며 좋은 인상을 남겼습니다.",
      "failText": "생각보다 손이 많이 가 인건비만 겨우 건졌습니다."
    }
  ]
}
```

#### 12) `SIGNATURE_VIRAL` — 시그니처 상품/서비스 입소문 급증
- **적용 업종**: `FOOD`, `SERVICE`, `RETAIL` (전체)
- **의도**: 상품력·서비스가 높은 매장이 동네 커뮤니티에서 화제가 됐을 때, 품질 유지(한정판) vs 물량 확대(대량 공급) 전략을 고른다.

```json
{
  "code": "SIGNATURE_VIRAL",
  "title": "시그니처 입소문 급증",
  "description": "동네 커뮤니티 '우리동네 이야기'에 올라온 손님 후기 한 편이 순식간에 추천을 수백 개 받으며 가게가 화제에 올랐다. 오픈 전부터 대기 줄이 생기고 문의가 빗발치는데, 물량을 공격적으로 늘릴지 품질을 지키며 한정 수량으로 갈지 결정해야 한다.",
  "conditionExpr": {
    "all": [
      { "var": "quartersOperated", "op": ">=", "value": 3 },
      {
        "any": [
          { "var": "stat.cooking", "op": ">=", "value": 55 },
          { "var": "stat.service", "op": ">=", "value": 55 }
        ]
      }
    ]
  },
  "baseProbability": 0.24,
  "cooldownQuarters": 7,
  "maxFires": 2,
  "priority": 50,
  "enabled": true,
  "options": [
    {
      "label": "한정판 프리미엄 전략 (품질 유지)",
      "orderNo": 1,
      "successFormula": { "base": 1.0, "min": 1.0, "max": 1.0 },
      "onSuccess": [
        { "type": "MODIFIER", "target": "revenue", "mode": "MULTIPLY", "value": 1.1, "quarters": 2, "label": "한정판 프리미엄 +10%" },
        { "type": "STAT", "stat": "cooking", "delta": 3 },
        { "type": "STAT", "stat": "service", "delta": 2 }
      ],
      "onFail": [],
      "successText": "품질을 타협하지 않는 모습에 오히려 '진짜 맛집'이라는 평가가 굳어졌습니다.",
      "failText": "-"
    },
    {
      "label": "대량 공급 확대 (매출 극대화)",
      "orderNo": 2,
      "successFormula": {
        "base": 0.5,
        "statBonus": [
          { "stat": "management", "per10": 0.05 },
          { "stat": "stamina", "per10": 0.04 }
        ],
        "min": 0.1,
        "max": 0.9
      },
      "onSuccess": [
        { "type": "MODIFIER", "target": "revenue", "mode": "MULTIPLY", "value": 1.3, "quarters": 2, "label": "입소문 물량 확대 +30%" },
        { "type": "CASH", "amount": 5000000, "memo": "입소문 특수 추가 수익" },
        { "type": "FLAG", "flag": "VIRAL_HIT", "value": true },
        { "type": "STAT", "stat": "marketing", "delta": 3 },
        { "type": "STAT", "stat": "stamina", "delta": -3 }
      ],
      "onFail": [
        { "type": "CASH", "amount": -5000000, "memo": "급조 설비 및 폐기 재고 손실" },
        { "type": "STAT", "stat": "service", "delta": -4 },
        { "type": "STAT", "stat": "cooking", "delta": -2 }
      ],
      "successText": "발 빠르게 물량을 늘려 입소문 특수를 온전히 수익으로 연결했습니다.",
      "failText": "급하게 물량을 늘리다 품질이 떨어졌다는 혹평이 쏟아졌습니다."
    }
  ]
}
```

---

## 2. 밸런스 근거

1. **파산 방지 (최대 손실 제한)**:
   - 초기 자본금이 `50,000,000원`이고 기본 분기 임대료가 `9,000,000원` 수준이다.
   - 모든 전환점의 단일 실패 손실은 최대 `10,000,000 ~ 12,000,000원` 선으로 설계했고, 투자 규모가 큰 `SECOND_BRANCH`(성공 시 `-15,000,000 ~ -18,000,000원`, 실패 시 `-10,000,000 ~ -12,000,000원`)는 보유 현금 `40,000,000원` 이상일 때만 등장한다. 따라서 어떤 경우에도 한 번의 실패로 잔액의 절반 이상을 잃어 즉사(`CLOSED`)하지 않는다.
2. **성공 확률 10% ~ 90% 범위 준수**:
   - 확정 안전 선택지(`{"base":1.0,"min":1.0,"max":1.0}`)를 제외한 모든 도전형 선택지는 기본 확률 `35~80%`에 능력치 보정을 더하고 최종적으로 `min: 0.1, max: 0.9`로 클램핑한다. 능력치가 낮아도 최소 10%의 역전 기회가 있고, 높아도 10%의 리스크가 남아 긴장감을 유지한다.
3. **선택지 간 트레이드오프**:
   - 모든 전환점은 **[고위험·고수익(현금·매출 배율·매장 수)]** vs **[안전·내실(능력치·체력·리스크 회피)]** 구조로 설계했다.
   - 예: `RENT_HIKE`는 고정비 수용(안전) vs 협상(인맥 판정, 성공 시 동결/실패 시 더 인상) vs 이전(목돈 지출 후 임대료 절감) 3파전으로 현재 현금과 능력치에 따라 최적 선택이 달라진다.
4. **초반(1~4분기) 발생 보장**:
   - `DELIVERY_APP`(2분기~), `LOCAL_FESTIVAL`(2분기~), `HYGIENE_INSPECTION`(3분기~), `CREATOR_COLLAB`(3분기~), `SIGNATURE_VIRAL`(3분기~), `RENT_HIKE`(4분기~), `CORPORATE_CATERING`(4분기~), `STAFF_TURNOVER`(조건 충족 시), `BURNOUT`(조건 충족 시) 등 9개가 초반 1~4분기에 발생 가능하다.

---

## 3. 동네 반응 템플릿 (36개)

> 규칙: `kind`는 `REVIEW`(별점 `1~5` 필수) 또는 `COMMUNITY`(별점 없음, `author`는 가상 커뮤니티 이름)이다. 실제 상호·매체·플랫폼 이름은 절대 쓰지 않는다. `text`에는 `{shopName}`, `{areaName}`, `{serviceName}`만 치환 가능하며 `likes`는 서버가 결정론적으로 생성한다. 전환점 결과 참조는 `lastResolved.*` 전용 변수를 4개 전환점에 적용했고, 어떤 분기에도 최소 2개 이상 추출되도록 범용 `REVIEW`를 넉넉히 포함했다.

```json
[
  {
    "code": "REV_GENERIC_1",
    "kind": "REVIEW",
    "author": "김서윤",
    "rating": 4,
    "weight": 8,
    "enabled": true,
    "conditionExpr": {},
    "text": "{areaName} 지나갈 때마다 들르는 {shopName}이에요. 늘 한결같아서 좋습니다."
  },
  {
    "code": "REV_GENERIC_2",
    "kind": "REVIEW",
    "author": "박지성",
    "rating": 4,
    "weight": 8,
    "enabled": true,
    "conditionExpr": {},
    "text": "동네에서 무난하게 믿고 가는 {serviceName} 매장입니다. 재방문 의사 있어요."
  },
  {
    "code": "REV_GENERIC_3",
    "kind": "REVIEW",
    "author": "강하늘",
    "rating": 4,
    "weight": 7,
    "enabled": true,
    "conditionExpr": {},
    "text": "{shopName} 매장이 깔끔해서 편하게 이용하고 갑니다. 다음에도 들를게요."
  },
  {
    "code": "REV_PROFIT_UP_1",
    "kind": "REVIEW",
    "author": "이수진",
    "rating": 5,
    "weight": 10,
    "enabled": true,
    "conditionExpr": { "var": "bucket.profit", "op": "==", "value": "UP" },
    "text": "요즘 {areaName}에서 제일 기분 좋게 나오는 {shopName}이에요. 사장님 표정도 밝고 정갈해서 자주 오게 됩니다."
  },
  {
    "code": "REV_PROFIT_UP_RANK_TOP",
    "kind": "REVIEW",
    "author": "박민준",
    "rating": 5,
    "weight": 12,
    "enabled": true,
    "conditionExpr": {
      "all": [
        { "var": "bucket.profit", "op": "==", "value": "UP" },
        { "var": "areaRankPct", "op": "<=", "value": 0.3 }
      ]
    },
    "text": "{shopName}은 웨이팅이 조금 있긴 한데 회전이 빨라서 금방 들어가요. {areaName} {serviceName} 1등 할 만합니다."
  },
  {
    "code": "REV_PROFIT_UP_2",
    "kind": "REVIEW",
    "author": "이지훈",
    "rating": 4,
    "weight": 10,
    "enabled": true,
    "conditionExpr": { "var": "bucket.profit", "op": "==", "value": "UP" },
    "text": "가격 대비 만족도가 높아서 점심마다 {shopName}에 들릅니다. 직원분들도 손발이 잘 맞네요."
  },
  {
    "code": "REV_LOSS_1",
    "kind": "REVIEW",
    "author": "최수빈",
    "rating": 2,
    "weight": 10,
    "enabled": true,
    "conditionExpr": { "var": "bucket.profit", "op": "==", "value": "LOSS" },
    "text": "{shopName}이 예전만 못한 느낌이에요. 손님이 적어서인지 매장 분위기도 좀 어수선했습니다."
  },
  {
    "code": "REV_LOSS_STREAK",
    "kind": "REVIEW",
    "author": "정우성",
    "rating": 1,
    "weight": 12,
    "enabled": true,
    "conditionExpr": {
      "all": [
        { "var": "bucket.profit", "op": "==", "value": "LOSS" },
        { "var": "negativeQuarters", "op": ">=", "value": 1 }
      ]
    },
    "text": "응대도 오래 걸리고 전체적인 컨디션이 아쉬웠습니다. {areaName}에 다른 곳도 많아서 재방문은 고민되네요."
  },
  {
    "code": "REV_PROFIT_FLAT",
    "kind": "REVIEW",
    "author": "한예슬",
    "rating": 3,
    "weight": 10,
    "enabled": true,
    "conditionExpr": { "var": "bucket.profit", "op": "in", "value": ["FLAT", "DOWN"] },
    "text": "무난하게 들르기 괜찮은 {serviceName} 매장입니다. 특별하진 않아도 거슬리는 점은 없어요."
  },
  {
    "code": "REV_RANK_UP_1",
    "kind": "REVIEW",
    "author": "오현우",
    "rating": 5,
    "weight": 11,
    "enabled": true,
    "conditionExpr": { "var": "bucket.rank", "op": "==", "value": "UP" },
    "text": "지난 분기보다 확실히 {shopName}이 더 좋아졌어요! 사장님이 연구 많이 하신 티가 납니다."
  },
  {
    "code": "REV_RANK_UP_2",
    "kind": "REVIEW",
    "author": "유진아",
    "rating": 4,
    "weight": 9,
    "enabled": true,
    "conditionExpr": { "var": "bucket.rank", "op": "==", "value": "UP" },
    "text": "{areaName}에서 요즘 입소문 타고 순위가 쑥 오른 {shopName}, 직접 와보니 이유를 알겠네요."
  },
  {
    "code": "REV_RANK_DOWN_1",
    "kind": "REVIEW",
    "author": "강다은",
    "rating": 2,
    "weight": 10,
    "enabled": true,
    "conditionExpr": { "var": "bucket.rank", "op": "==", "value": "DOWN" },
    "text": "근처에 새 가게들이 생기더니 {shopName}은 살짝 힘이 빠진 느낌이에요. 초심 찾으셨으면."
  },
  {
    "code": "REV_PRICE_HIGH_GOOD",
    "kind": "REVIEW",
    "author": "윤도현",
    "rating": 4,
    "weight": 10,
    "enabled": true,
    "conditionExpr": {
      "all": [
        { "var": "bucket.price", "op": "==", "value": "HIGH" },
        { "var": "stat.cooking", "op": ">=", "value": 50 }
      ]
    },
    "text": "가격대는 좀 있는 편이지만 퀄리티를 보면 충분히 납득됩니다. 대접할 때 {shopName} 좋네요."
  },
  {
    "code": "REV_PRICE_HIGH_BAD",
    "kind": "REVIEW",
    "author": "임소라",
    "rating": 2,
    "weight": 10,
    "enabled": true,
    "conditionExpr": {
      "all": [
        { "var": "bucket.price", "op": "==", "value": "HIGH" },
        { "var": "stat.cooking", "op": "<", "value": 50 }
      ]
    },
    "text": "{areaName} 물가 감안해도 너무 비싼 것 같아요. 이 가격이면 다른 {serviceName} 갈 듯합니다."
  },
  {
    "code": "REV_PRICE_LOW_1",
    "kind": "REVIEW",
    "author": "서지민",
    "rating": 5,
    "weight": 11,
    "enabled": true,
    "conditionExpr": { "var": "bucket.price", "op": "==", "value": "LOW" },
    "text": "요즘 물가에 이 가격으로 이용할 수 있는 게 감사할 따름입니다. {shopName} 가성비 최고!"
  },
  {
    "code": "REV_PRICE_LOW_CROWDED",
    "kind": "REVIEW",
    "author": "조현석",
    "rating": 3,
    "weight": 9,
    "enabled": true,
    "conditionExpr": {
      "all": [
        { "var": "bucket.price", "op": "==", "value": "LOW" },
        { "var": "staff", "op": "<=", "value": 1 }
      ]
    },
    "text": "싸긴 한데 사람이 몰릴 때는 조금 정신이 없어요. 그래도 가격 생각하면 이해됩니다."
  },
  {
    "code": "REV_HOURS_LONG",
    "kind": "REVIEW",
    "author": "배수지",
    "rating": 5,
    "weight": 10,
    "enabled": true,
    "conditionExpr": { "var": "bucket.hours", "op": "==", "value": "LONG" },
    "text": "야근하고 늦게 끝나도 {shopName}은 문이 열려 있어서 너무 든든해요. 밤늦게 들르기 딱입니다."
  },
  {
    "code": "REV_HOURS_SHORT",
    "kind": "REVIEW",
    "author": "송민호",
    "rating": 3,
    "weight": 10,
    "enabled": true,
    "conditionExpr": { "var": "bucket.hours", "op": "==", "value": "SHORT" },
    "text": "좋긴 한데 문 닫는 시간이 너무 빨라서 퇴근 후에는 {shopName} 가기가 힘드네요."
  },
  {
    "code": "REV_FIRST_QUARTER_1",
    "kind": "REVIEW",
    "author": "신세경",
    "rating": 4,
    "weight": 12,
    "enabled": true,
    "conditionExpr": { "var": "bucket.firstQuarter", "op": "==", "value": true },
    "text": "{areaName} 골목에 {shopName}이 새로 생겼길래 가봤는데 깔끔하고 사장님이 친절하셔서 좋았습니다."
  },
  {
    "code": "REV_FIRST_QUARTER_PROFIT",
    "kind": "REVIEW",
    "author": "남주혁",
    "rating": 5,
    "weight": 12,
    "enabled": true,
    "conditionExpr": {
      "all": [
        { "var": "bucket.firstQuarter", "op": "==", "value": true },
        { "var": "bucket.profit", "op": "==", "value": "UP" }
      ]
    },
    "text": "오픈 첫 분기부터 이 정도 퀄리티면 금방 {areaName} 터줏대감 되겠는데요? {shopName} 응원합니다!"
  },
  {
    "code": "REV_TP_CREATOR_HIT",
    "kind": "REVIEW",
    "author": "김태리",
    "rating": 5,
    "weight": 15,
    "enabled": true,
    "conditionExpr": {
      "all": [
        { "var": "lastResolved.code", "op": "==", "value": "CREATOR_COLLAB" },
        { "var": "lastResolved.result", "op": "==", "value": "SUCCESS" },
        { "var": "lastResolved.quartersSince", "op": "<=", "value": 1 }
      ]
    },
    "text": "영상 보고 {shopName} 찾아왔는데 화면에서 보던 것보다 훨씬 만족스러워서 놀랐어요!"
  },
  {
    "code": "REV_TP_CREATOR_FAIL",
    "kind": "REVIEW",
    "author": "류준열",
    "rating": 1,
    "weight": 15,
    "enabled": true,
    "conditionExpr": {
      "all": [
        { "var": "lastResolved.code", "op": "==", "value": "CREATOR_COLLAB" },
        { "var": "lastResolved.result", "op": "==", "value": "FAIL" },
        { "var": "lastResolved.quartersSince", "op": "<=", "value": 1 }
      ]
    },
    "text": "소문 듣고 멀리서 {shopName} 왔는데 직원분들도 정신없고 응대가 너무 아쉬워서 실망했습니다."
  },
  {
    "code": "REV_TP_HYGIENE",
    "kind": "REVIEW",
    "author": "박보영",
    "rating": 5,
    "weight": 14,
    "enabled": true,
    "conditionExpr": {
      "all": [
        { "var": "lastResolved.code", "op": "==", "value": "HYGIENE_INSPECTION" },
        { "var": "lastResolved.result", "op": "==", "value": "SUCCESS" },
        { "var": "lastResolved.quartersSince", "op": "<=", "value": 1 }
      ]
    },
    "text": "{shopName} 매장이 정말 반짝반짝 깨끗해요. 위생 인증까지 받았다니 믿고 자주 옵니다."
  },
  {
    "code": "REV_VISITORS_UP",
    "kind": "REVIEW",
    "author": "이동욱",
    "rating": 4,
    "weight": 9,
    "enabled": true,
    "conditionExpr": { "var": "bucket.visitors", "op": "==", "value": "UP" },
    "text": "요즘 {shopName}에 손님이 부쩍 많아졌네요. 활기가 넘쳐서 덩달아 기분이 좋습니다."
  },
  {
    "code": "COM_PROFIT_TOP",
    "kind": "COMMUNITY",
    "author": "우리동네 이야기",
    "weight": 10,
    "enabled": true,
    "conditionExpr": {
      "all": [
        { "var": "bucket.profit", "op": "==", "value": "UP" },
        { "var": "areaRankPct", "op": "<=", "value": 0.3 }
      ]
    },
    "text": "{areaName} 사거리 쪽 {shopName} 요즘 줄 서는 거 보셨나요? 우리 동네 상권 살아나는 느낌이라 좋네요."
  },
  {
    "code": "COM_LOSS_WORRY",
    "kind": "COMMUNITY",
    "author": "서울골목 커뮤니티",
    "weight": 10,
    "enabled": true,
    "conditionExpr": {
      "any": [
        { "var": "bucket.profit", "op": "==", "value": "LOSS" },
        { "var": "negativeQuarters", "op": ">=", "value": 1 }
      ]
    },
    "text": "저기 {areaName} 코너 {shopName} 요즘 손님이 많이 준 것 같던데... 자영업자분들 다들 힘내셨으면 합니다."
  },
  {
    "code": "COM_RANK_UP",
    "kind": "COMMUNITY",
    "author": "점심메뉴 연구소",
    "weight": 10,
    "enabled": true,
    "conditionExpr": { "var": "bucket.rank", "op": "==", "value": "UP" },
    "text": "지난주에 {shopName} 다녀왔는데 진짜 괜찮아요. 이제 {areaName} {serviceName} 리스트 1순위입니다."
  },
  {
    "code": "COM_RANK_DOWN",
    "kind": "COMMUNITY",
    "author": "동네산책 톡방",
    "weight": 9,
    "enabled": true,
    "conditionExpr": { "var": "bucket.rank", "op": "==", "value": "DOWN" },
    "text": "요즘 {areaName} 옆 골목에 신상 가게가 많이 생겨서 그런지 {shopName}이 조금 밀리는 분위기네요."
  },
  {
    "code": "COM_PRICE_HIGH",
    "kind": "COMMUNITY",
    "author": "알뜰살림 모임",
    "weight": 9,
    "enabled": true,
    "conditionExpr": { "var": "bucket.price", "op": "==", "value": "HIGH" },
    "text": "{areaName}에 있는 {shopName} 가격표 보고 살짝 놀랐어요. 분위기는 좋은데 자주 가긴 부담스럽네요."
  },
  {
    "code": "COM_PRICE_LOW",
    "kind": "COMMUNITY",
    "author": "서울자취생 수다방",
    "weight": 10,
    "enabled": true,
    "conditionExpr": { "var": "bucket.price", "op": "==", "value": "LOW" },
    "text": "지갑 가벼울 때 믿고 가는 {areaName} 가성비 성지 {shopName}! 사장님 오래오래 장사해 주세요."
  },
  {
    "code": "COM_HOURS_LONG",
    "kind": "COMMUNITY",
    "author": "야근러 쉼터",
    "weight": 9,
    "enabled": true,
    "conditionExpr": { "var": "bucket.hours", "op": "==", "value": "LONG" },
    "text": "밤 늦게까지 불 켜져 있는 {shopName}이 {areaName}에 있어서 얼마나 다행인지 모릅니다."
  },
  {
    "code": "COM_HOURS_SHORT",
    "kind": "COMMUNITY",
    "author": "주말나들이 톡",
    "weight": 9,
    "enabled": true,
    "conditionExpr": { "var": "bucket.hours", "op": "==", "value": "SHORT" },
    "text": "평일에 {shopName} 가보려다 문 닫혀서 두 번 허탕 쳤어요. 가실 분들 영업시간 꼭 확인하세요!"
  },
  {
    "code": "COM_FIRST_QUARTER",
    "kind": "COMMUNITY",
    "author": "신상탐험대",
    "weight": 11,
    "enabled": true,
    "conditionExpr": { "var": "bucket.firstQuarter", "op": "==", "value": true },
    "text": "이번 분기에 {areaName}에 새 간판 달린 {shopName} 가보신 분 계신가요? 분위기 괜찮아 보이던데."
  },
  {
    "code": "COM_TP_SECOND_BRANCH",
    "kind": "COMMUNITY",
    "author": "우리동네 이야기",
    "weight": 15,
    "enabled": true,
    "conditionExpr": { "var": "shopCount", "op": ">=", "value": 2 },
    "text": "{shopName} 본점 장사 잘되더니 2호점까지 냈더군요. 요즘 {areaName}에서 제일 잘나가는 듯!"
  },
  {
    "code": "COM_TP_FESTIVAL",
    "kind": "COMMUNITY",
    "author": "별빛마을 주민회",
    "weight": 14,
    "enabled": true,
    "conditionExpr": {
      "all": [
        { "var": "lastResolved.code", "op": "==", "value": "LOCAL_FESTIVAL" },
        { "var": "lastResolved.result", "op": "==", "value": "SUCCESS" },
        { "var": "lastResolved.quartersSince", "op": "<=", "value": 1 }
      ]
    },
    "text": "이번 골목 축제 때 부스 줄이 끝도 없던 {shopName}, 알고 보니 우리 동네 가게였네요. 뿌듯합니다."
  },
  {
    "code": "COM_AREA_CLOSINGS",
    "kind": "COMMUNITY",
    "author": "서울골목 커뮤니티",
    "weight": 10,
    "enabled": true,
    "conditionExpr": { "var": "area.closingCount", "op": ">=", "value": 3 },
    "text": "이번 분기에만 {areaName} 골목에서 몇 곳이 간판을 내렸네요. 살아남는 {shopName}은 다 이유가 있나 봅니다."
  }
]
```

---

## 4. 이번 분기의 순간 (12개)

> 규칙: 분기 결산 카드의 `이번 분기의 순간` 영역에 표시된다. 엔진 담당(Claude)의 DSL v1.2(`STAT ±2`, `MODIFIER 1~2분기`, `TEXT`만 허용; `CASH` 금지)를 준수하여 `onSuccess`/`onFail`을 작성했다.

```json
[
  {
    "code": "MOMENT_REGULARS",
    "title": "단골손님의 친구 동반 방문",
    "stat": "service",
    "difficulty": 45,
    "weight": 10,
    "enabled": true,
    "conditionExpr": {},
    "successText": "단골손님이 직장 동료들을 한 무더기 데려와 매장이 활기를 띠었습니다.",
    "failText": "단골손님이 왔지만 응대가 바빠 인사도 제대로 못 했습니다.",
    "onSuccess": [
      { "type": "STAT", "stat": "service", "delta": 1 },
      { "type": "MODIFIER", "target": "visitors", "mode": "MULTIPLY", "value": 1.05, "quarters": 1, "label": "지인 방문 유입 +5%" }
    ],
    "onFail": []
  },
  {
    "code": "MOMENT_TASTING",
    "title": "계절 한정 메뉴 시식회",
    "stat": "cooking",
    "difficulty": 50,
    "weight": 10,
    "enabled": true,
    "conditionExpr": { "var": "industryCategory", "op": "==", "value": "FOOD" },
    "successText": "즉석에서 내놓은 계절 한정 메뉴가 손님들에게 호평받았습니다.",
    "failText": "새 메뉴의 간이 조금 들쑥날쑥해 아쉬운 소리를 들었습니다.",
    "onSuccess": [
      { "type": "STAT", "stat": "cooking", "delta": 2 }
    ],
    "onFail": [
      { "type": "STAT", "stat": "cooking", "delta": 1 }
    ]
  },
  {
    "code": "MOMENT_GROUP_BUY",
    "title": "옆 가게와 식자재·비품 공동 구매",
    "stat": "network",
    "difficulty": 45,
    "weight": 10,
    "enabled": true,
    "conditionExpr": {},
    "successText": "옆 가게 사장님과 비품을 공동 구매해 원가를 쏠쏠하게 아꼈습니다.",
    "failText": "도매처 일정이 엇갈려 공동 구매가 다음 분기로 미뤄졌습니다.",
    "onSuccess": [
      { "type": "MODIFIER", "target": "costRate", "mode": "ADD", "value": -0.02, "quarters": 1, "label": "공동 구매 원가 -2%p" },
      { "type": "STAT", "stat": "network", "delta": 1 }
    ],
    "onFail": []
  },
  {
    "code": "MOMENT_WORKFLOW",
    "title": "피크 타임 동선 개선",
    "stat": "management",
    "difficulty": 50,
    "weight": 10,
    "enabled": true,
    "conditionExpr": {},
    "successText": "매장 동선을 다시 짜서 점심 회전율이 눈에 띄게 빨라졌습니다.",
    "failText": "동선을 바꿨다가 오히려 동선이 꼬여 잠시 혼선이 빚어졌습니다.",
    "onSuccess": [
      { "type": "STAT", "stat": "management", "delta": 2 }
    ],
    "onFail": [
      { "type": "STAT", "stat": "stamina", "delta": -1 }
    ]
  },
  {
    "code": "MOMENT_LOCAL_PROMO",
    "title": "동네 소식지 & 입소문 홍보",
    "stat": "marketing",
    "difficulty": 45,
    "weight": 10,
    "enabled": true,
    "conditionExpr": {},
    "successText": "직접 만든 매장 소식이 동네 주민들 사이에서 활발히 공유됐습니다.",
    "failText": "홍보 문구가 눈에 잘 띄지 않아 별다른 반응 없이 지나갔습니다.",
    "onSuccess": [
      { "type": "STAT", "stat": "marketing", "delta": 2 },
      { "type": "MODIFIER", "target": "visitors", "mode": "MULTIPLY", "value": 1.04, "quarters": 1, "label": "입소문 유입 +4%" }
    ],
    "onFail": [
      { "type": "STAT", "stat": "marketing", "delta": 1 }
    ]
  },
  {
    "code": "MOMENT_HOLIDAY_RUSH",
    "title": "연휴 주말 마라톤 영업",
    "stat": "stamina",
    "difficulty": 50,
    "weight": 10,
    "enabled": true,
    "conditionExpr": { "var": "openHours", "op": ">=", "value": 10 },
    "successText": "연휴 기간 손님이 몰렸지만 지치지 않고 끝까지 미소를 지켰습니다.",
    "failText": "연휴 막바지에 체력이 급격히 떨어져 마감을 일찍 해야 했습니다.",
    "onSuccess": [
      { "type": "STAT", "stat": "stamina", "delta": 2 },
      { "type": "MODIFIER", "target": "revenue", "mode": "MULTIPLY", "value": 1.05, "quarters": 1, "label": "연휴 호평 재방문 +5%" }
    ],
    "onFail": [
      { "type": "STAT", "stat": "stamina", "delta": -2 }
    ]
  },
  {
    "code": "MOMENT_TOUGH_CUSTOMER",
    "title": "까다로운 손님 응대",
    "stat": "service",
    "difficulty": 55,
    "weight": 10,
    "enabled": true,
    "conditionExpr": {},
    "successText": "불만을 제기한 손님을 차분하게 달래 오히려 단골로 만들었습니다.",
    "failText": "언성이 높아진 손님 때문에 매장 분위기가 잠시 얼어붙었습니다.",
    "onSuccess": [
      { "type": "STAT", "stat": "service", "delta": 2 },
      { "type": "STAT", "stat": "network", "delta": 1 }
    ],
    "onFail": [
      { "type": "STAT", "stat": "service", "delta": -1 }
    ]
  },
  {
    "code": "MOMENT_ZERO_WASTE",
    "title": "재고 로스 제로 달성",
    "stat": "management",
    "difficulty": 55,
    "weight": 10,
    "enabled": true,
    "conditionExpr": {},
    "successText": "발주량을 칼같이 맞춰 이번 분기 폐기 재고를 거의 없앴습니다.",
    "failText": "예상보다 손님이 적어 남은 재고를 일부 폐기해야 했습니다.",
    "onSuccess": [
      { "type": "MODIFIER", "target": "costRate", "mode": "ADD", "value": -0.02, "quarters": 1, "label": "재고 관리 원가 -2%p" },
      { "type": "STAT", "stat": "management", "delta": 1 }
    ],
    "onFail": [
      { "type": "MODIFIER", "target": "costRate", "mode": "ADD", "value": 0.02, "quarters": 1, "label": "폐기 로스 원가 +2%p" }
    ]
  },
  {
    "code": "MOMENT_CRITIC_VISIT",
    "title": "까다로운 미식가의 방문",
    "stat": "cooking",
    "difficulty": 60,
    "weight": 8,
    "enabled": true,
    "conditionExpr": { "var": "quartersOperated", "op": ">=", "value": 2 },
    "successText": "한 미식가 손님이 기본기가 탄탄하다며 칭찬을 남겼습니다.",
    "failText": "무난하지만 한 방이 아쉽다는 솔직한 피드백을 받았습니다.",
    "onSuccess": [
      { "type": "STAT", "stat": "cooking", "delta": 2 },
      { "type": "STAT", "stat": "marketing", "delta": 1 }
    ],
    "onFail": [
      { "type": "STAT", "stat": "cooking", "delta": 1 }
    ]
  },
  {
    "code": "MOMENT_MERCHANT_MEETING",
    "title": "상인회 친목 모임",
    "stat": "network",
    "difficulty": 50,
    "weight": 10,
    "enabled": true,
    "conditionExpr": {},
    "successText": "상인회 모임에서 동네 상권의 알짜 정보를 미리 귀띔받았습니다.",
    "failText": "모임에 얼굴만 비추고 바빠서 금방 자리에서 일어나야 했습니다.",
    "onSuccess": [
      { "type": "STAT", "stat": "network", "delta": 2 },
      { "type": "STAT", "stat": "management", "delta": 1 }
    ],
    "onFail": [
      { "type": "STAT", "stat": "network", "delta": 1 }
    ]
  },
  {
    "code": "MOMENT_RAINY_DAY",
    "title": "비 오는 날의 감성 이벤트",
    "stat": "marketing",
    "difficulty": 50,
    "weight": 10,
    "enabled": true,
    "conditionExpr": {},
    "successText": "비 오는 날 특별 이벤트를 열어 썰렁할 뻔한 매장을 손님으로 채웠습니다.",
    "failText": "비가 너무 많이 와서 이벤트를 알리기도 전에 거리가 텅 비었습니다.",
    "onSuccess": [
      { "type": "MODIFIER", "target": "visitors", "mode": "MULTIPLY", "value": 1.05, "quarters": 1, "label": "우천 이벤트 재방문 +5%" },
      { "type": "STAT", "stat": "marketing", "delta": 1 }
    ],
    "onFail": [
      { "type": "MODIFIER", "target": "revenue", "mode": "MULTIPLY", "value": 0.97, "quarters": 1, "label": "우천 홍보 손실 -3%" }
    ]
  },
  {
    "code": "MOMENT_DAWN_MARKET",
    "title": "새벽 시장 직거래 발품",
    "stat": "stamina",
    "difficulty": 55,
    "weight": 9,
    "enabled": true,
    "conditionExpr": {},
    "successText": "새벽 일찍 시장에 나가 싱싱한 물품을 저렴하게 공수해 왔습니다.",
    "failText": "새벽 강행군 여파로 오전 영업 내내 졸음과 싸워야 했습니다.",
    "onSuccess": [
      { "type": "MODIFIER", "target": "costRate", "mode": "ADD", "value": -0.02, "quarters": 1, "label": "직거래 매입 원가 -2%p" },
      { "type": "STAT", "stat": "cooking", "delta": 1 }
    ],
    "onFail": [
      { "type": "STAT", "stat": "stamina", "delta": -2 }
    ]
  }
]
```
