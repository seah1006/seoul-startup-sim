package com.seah.startupsim.web;

import com.seah.startupsim.common.SecretMasker;
import com.seah.startupsim.ingest.IngestService;
import com.seah.startupsim.ingest.QuarterBackfillService;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 관리자 조회와 수집 실행을 PRG 방식으로 연결한다. */
@Controller
public class AdminController {
    private final AdminService service;
    private final IngestService ingest;
    private final QuarterBackfillService backfill;
    private final SecretMasker masker;
    public AdminController(AdminService service, IngestService ingest, QuarterBackfillService backfill, SecretMasker masker) {
        this.service = service; this.ingest = ingest; this.backfill = backfill; this.masker = masker;
    }
    @GetMapping("/admin/login")
    public String login() { return "admin/login"; }
    @GetMapping({"/admin", "/admin/ingest"})
    public String dashboard(Model model) {
        model.addAttribute("cards", service.dashboard()); model.addAttribute("circuit", service.circuit());
        return "admin/dashboard";
    }
    @GetMapping("/admin/ingest/runs")
    public String runs(@RequestParam(defaultValue = "") String source, @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "0") int page, Model model) {
        List<AdminRepository.Run> runs = service.runs(source, status, page);
        model.addAttribute("runs", runs.stream().limit(20).toList()); model.addAttribute("hasNext", runs.size() > 20);
        model.addAttribute("page", page); model.addAttribute("source", source); model.addAttribute("status", status);
        model.addAttribute("sources", service.sources()); return "admin/runs";
    }
    @GetMapping("/admin/ingest/runs/{id}")
    public String detail(@PathVariable long id, @RequestParam(required = false) Long rawId, Model model) {
        model.addAttribute("run", service.run(id)); model.addAttribute("raws", service.raws(id));
        if (rawId != null) model.addAttribute("payload", service.raw(id, rawId));
        return "admin/detail";
    }
    @GetMapping({"/admin/ingest/run", "/admin/ingest/backfill"})
    public String form(Model model) { model.addAttribute("sources", service.sources()); return "admin/forms"; }
    @PostMapping("/admin/ingest/run")
    public String run(@RequestParam String source, @RequestParam(required = false) String period, RedirectAttributes flash) {
        Long[] id = new Long[1];
        try { ingest.run(source, period, value -> id[0] = value); flash.addFlashAttribute("message", "수집이 완료되었습니다."); }
        catch (RuntimeException e) { flash.addFlashAttribute("message", "수집 실패: " + masker.mask(e.getMessage())); }
        return id[0] == null ? "redirect:/admin/ingest/run" : "redirect:/admin/ingest/runs/" + id[0];
    }
    @PostMapping("/admin/ingest/backfill")
    public String backfill(@RequestParam String source, @RequestParam String from, @RequestParam String to, RedirectAttributes flash) {
        try {
            List<QuarterBackfillService.Result> results = backfill.backfill(source, from, to);
            flash.addFlashAttribute("message", "백필 완료: " + results.stream().filter(r -> "SUCCEEDED".equals(r.status())).count()
                    + "/" + results.size() + " 성공");
        } catch (RuntimeException e) { flash.addFlashAttribute("message", "백필 실패: " + masker.mask(e.getMessage())); }
        return "redirect:/admin/ingest/runs";
    }
    @GetMapping("/admin/data-versions")
    public String versions(Model model) { model.addAttribute("versions", service.versions()); return "admin/versions"; }
}
