package com.seah.startupsim.web;

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
            String areaName, String serviceName, int survivedQuarters, String totalProfit, String bestQuarterProfit,
            int shopCount, String title, String description) {}

    private final ShareRepository repository;

    public ShareController(ShareRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/share/{gameId}")
    public String share(@PathVariable String gameId, Model model, HttpServletResponse response) {
        ShareRepository.Ending ending = gameId.matches("[1-9][0-9]{0,17}") ? repository.ending(Long.parseLong(gameId)) : null;
        if (ending == null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return "share/not-found";
        }
        model.addAttribute("page", page(ending));
        var base = ServletUriComponentsBuilder.fromCurrentContextPath();
        model.addAttribute("pageUrl", base.cloneBuilder().path("/share/{id}").buildAndExpand(ending.gameId()).toUriString());
        model.addAttribute("ogImage", base.cloneBuilder().path("/og/share-default.jpg").toUriString());
        return "share/game";
    }

    static SharePage page(ShareRepository.Ending ending) {
        String label = ENDINGS.getOrDefault(ending.endingType(), ending.endingType());
        String profit = won(ending.totalProfit());
        return new SharePage(ending.gameId(), label, ending.shopName(), ending.ownerName(), ending.nickname(),
                ending.areaName(), ending.serviceName(), ending.survivedQuarters(), profit,
                won(ending.bestQuarterProfit()), ending.shopCount(), ending.shopName() + " · " + label,
                ending.survivedQuarters() + "분기 생존 · 누적 순이익 " + profit);
    }

    private static String won(long amount) {
        return "%,d원".formatted(amount);
    }
}
