package com.seah.startupsim.web;

import com.seah.startupsim.ranking.RankingBatch;
import com.seah.startupsim.ranking.RankingRepository;
import com.seah.startupsim.ranking.RankingService;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 랭킹 마지막 갱신 시각과 수동 갱신. 수동 갱신도 배치와 같은 advisory lock을 쓴다. */
@Controller
public class AdminRankingController {
    private final RankingBatch batch;
    private final RankingRepository repository;
    private final RankingService rankings;
    private final Clock clock;

    public AdminRankingController(RankingBatch batch, RankingRepository repository, RankingService rankings,
            Clock clock) {
        this.batch = batch;
        this.repository = repository;
        this.rankings = rankings;
        this.clock = clock;
    }

    @GetMapping("/admin/rankings")
    public String rankings(Model model) {
        LocalDate today = rankings.today();
        model.addAttribute("lastRefreshedAt", repository.lastRefreshedAt());
        model.addAttribute("lastRun", batch.last());
        model.addAttribute("days", repository.recent(today.minusDays(RankingService.DAYS - 1), today));
        return "admin/rankings";
    }

    @PostMapping("/admin/rankings/refresh")
    public String refresh(RedirectAttributes flash) {
        RankingBatch.Refresh result = batch.refresh(clock.instant());
        flash.addFlashAttribute("message", result.executed()
                ? "랭킹을 갱신했습니다: " + result.dates()
                : "다른 랭킹 갱신이 실행 중이라 건너뛰었습니다.");
        return "redirect:/admin/rankings";
    }
}
