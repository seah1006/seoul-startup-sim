package com.seah.startupsim.web;

import com.seah.startupsim.board.BoardRecordService;
import com.seah.startupsim.common.MoneyFormat;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** 로그인 없이 보는 엔딩 공유 페이지. game 패키지 밖에 두어 인증글 링크를 붙일 때 게임 코드를 건드리지 않는다. */
@Controller
public class ShareController {
    private static final Map<String, String> ENDINGS = Map.of("CLOSED", "폐업", "SOLD", "매각",
            "LONG_RUNNING", "장수 가게");

    public record SharePage(long gameId, String endingLabel, String shopName, String ownerName, String nickname,
            String areaName, String serviceName, int survivedQuarters, long totalProfit, long bestQuarterProfit,
            int shopCount, String title, String description) {}

    private final ShareRepository repository;
    private final BoardRecordService boardRecords;

    public ShareController(ShareRepository repository, BoardRecordService boardRecords) {
        this.repository = repository;
        this.boardRecords = boardRecords;
    }

    @GetMapping("/share/{gameId}")
    public String share(@PathVariable String gameId, Model model, HttpServletResponse response) {
        ShareRepository.Ending ending = gameId.matches("[1-9][0-9]{0,17}") ? repository.ending(Long.parseLong(gameId)) : null;
        if (ending == null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return "share/not-found";
        }
        model.addAttribute("page", page(ending));
        // 공개(ACTIVE) 인증글이 있을 때만 섹션을 그린다. 템플릿은 null이면 생략한다.
        model.addAttribute("recordLink", boardRecords.publicLink(ending.gameId()).orElse(null));
        var base = ServletUriComponentsBuilder.fromCurrentContextPath();
        model.addAttribute("pageUrl", base.cloneBuilder().path("/share/{id}").buildAndExpand(ending.gameId()).toUriString());
        model.addAttribute("ogImage", base.cloneBuilder().path("/og/share-default.jpg").toUriString());
        return "share/game";
    }

    static SharePage page(ShareRepository.Ending ending) {
        String label = ENDINGS.getOrDefault(ending.endingType(), ending.endingType());
        // 금액 칸은 템플릿이 ${@money.won(...)}로, OG 설명은 같은 규칙으로 여기서 만든다.
        return new SharePage(ending.gameId(), label, ending.shopName(), ending.ownerName(), ending.nickname(),
                ending.areaName(), ending.serviceName(), ending.survivedQuarters(), ending.totalProfit(),
                ending.bestQuarterProfit(), ending.shopCount(), ending.shopName() + " · " + label,
                ending.survivedQuarters() + "분기 생존 · 누적 순이익 " + MoneyFormat.won(ending.totalProfit()));
    }
}

