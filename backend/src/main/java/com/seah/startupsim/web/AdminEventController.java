package com.seah.startupsim.web;

import com.seah.startupsim.game.EventDefinitionRepository.Kind;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 전환점·반응·순간 정의의 목록, 활성 토글, JSON 편집을 PRG 방식으로 제공한다. 삭제는 없다. */
@Controller
public class AdminEventController {
    private static final Map<String, Kind> KINDS = Map.of("turning-points", Kind.TURNING_POINT,
            "reactions", Kind.REACTION, "moments", Kind.MOMENT);
    private static final Map<Kind, String> TITLES = Map.of(Kind.TURNING_POINT, "전환점",
            Kind.REACTION, "동네 반응", Kind.MOMENT, "이번 분기의 순간");

    private final AdminEventService service;

    public AdminEventController(AdminEventService service) {
        this.service = service;
    }

    @GetMapping("/admin/{path:turning-points|reactions|moments}")
    public String list(@PathVariable String path, Model model) {
        Kind kind = kind(path);
        model.addAttribute("path", path);
        model.addAttribute("title", TITLES.get(kind));
        model.addAttribute("turningPoints", kind == Kind.TURNING_POINT);
        model.addAttribute("rows", service.rows(kind));
        return "admin/events";
    }

    @PostMapping("/admin/{path:turning-points|reactions|moments}/{id}/toggle")
    public String toggle(@PathVariable String path, @PathVariable long id, RedirectAttributes flash) {
        service.toggle(kind(path), id);
        flash.addFlashAttribute("message", "활성 여부를 바꿨습니다. 다음 분기 판정부터 적용됩니다.");
        return "redirect:/admin/" + path;
    }

    @GetMapping("/admin/{path:turning-points|reactions|moments}/{id}")
    public String edit(@PathVariable String path, @PathVariable long id, Model model) {
        Kind kind = kind(path);
        return form(model, path, kind, id, service.editable(kind, id), null);
    }

    @PostMapping("/admin/{path:turning-points|reactions|moments}/{id}")
    public String save(@PathVariable String path, @PathVariable long id, @RequestParam String definition,
            Model model, RedirectAttributes flash, HttpServletResponse response) {
        Kind kind = kind(path);
        try {
            service.save(kind, id, definition);
        } catch (AdminEventService.InvalidDefinition exception) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            return form(model, path, kind, id, definition, exception.getMessage());
        }
        flash.addFlashAttribute("message", "저장했습니다. 대기 중인 전환점은 발생 시점 스냅샷을 그대로 씁니다.");
        return "redirect:/admin/" + path + "/" + id;
    }

    private String form(Model model, String path, Kind kind, long id, String definition, String error) {
        model.addAttribute("path", path);
        model.addAttribute("title", TITLES.get(kind));
        model.addAttribute("id", id);
        model.addAttribute("definition", definition);
        model.addAttribute("error", error);
        return "admin/event-edit";
    }

    private static Kind kind(String path) {
        Kind kind = KINDS.get(path);
        if (kind == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return kind;
    }
}
