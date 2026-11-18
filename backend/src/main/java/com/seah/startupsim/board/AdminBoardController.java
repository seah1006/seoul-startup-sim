package com.seah.startupsim.board;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import static com.seah.startupsim.board.BoardModerationRepository.*;

@Controller
@RequestMapping("/admin/board")
public class AdminBoardController {
    private static final String BOARD = "/admin/board/";
    private final BoardModerationRepository reads;
    private final BoardModerationService moderation;

    public AdminBoardController(BoardModerationRepository reads, BoardModerationService moderation) {
        this.reads = reads;
        this.moderation = moderation;
    }

    @GetMapping({"", "/posts"})
    String posts(@RequestParam(required = false) State status, @RequestParam(required = false) PostCategory category,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("states", State.values()).addAttribute("categories", PostCategory.values());
        model.addAttribute("selectedStatus", status).addAttribute("selectedCategory", category).addAttribute("q", q);
        page(model, "posts", reads.posts(status, category, q.strip(), page), page);
        return "admin/board/posts";
    }

    @GetMapping("/posts/{id}")
    String post(@PathVariable long id, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("post", reads.post(id));
        page(model, "comments", reads.comments(id, page), page);
        return "admin/board/post";
    }

    @GetMapping("/reports")
    String reports(@RequestParam(defaultValue = "PENDING") ReportStatus status, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("statuses", ReportStatus.values()).addAttribute("selectedStatus", status);
        page(model, "reports", reads.reports(status, page), page);
        return "admin/board/reports";
    }

    @GetMapping("/reports/{id}")
    String report(@PathVariable long id, Model model) {
        var report = reads.report(id);
        long postId = report.type() == TargetType.POST ? report.targetId() : reads.commentPostId(report.targetId());
        model.addAttribute("report", report).addAttribute("postId", postId);
        model.addAttribute("target", report.type() == TargetType.POST ? reads.post(postId).content()
                : reads.comment(report.targetId()).content());
        return "admin/board/report";
    }

    @PostMapping("/reports/{id}/{action:resolve|dismiss}")
    Object handle(@PathVariable long id, @PathVariable String action, Principal principal, Model model,
            RedirectAttributes flash, HttpServletResponse response) {
        try {
            moderation.handle(id, action.equals("resolve") ? ReportStatus.RESOLVED : ReportStatus.DISMISSED, principal.getName());
            return redirect("reports/" + id, flash);
        } catch (BoardException e) {
            if (e.status() != HttpStatus.CONFLICT) throw e;
            error(e, model, response);
            return report(id, model);
        }
    }

    @PostMapping("/posts/{id}/{action:hide|restore|delete}")
    Object changePost(@PathVariable long id, @PathVariable String action, Model model,
            RedirectAttributes flash, HttpServletResponse response) {
        return change(TargetType.POST, id, id, action, model, flash, response);
    }

    @PostMapping("/comments/{id}/{action:hide|restore|delete}")
    Object changeComment(@PathVariable long id, @PathVariable String action, Model model,
            RedirectAttributes flash, HttpServletResponse response) {
        return change(TargetType.COMMENT, id, reads.commentPostId(id), action, model, flash, response);
    }

    private Object change(TargetType type, long id, long postId, String action, Model model,
            RedirectAttributes flash, HttpServletResponse response) {
        State next = switch (action) {
            case "hide" -> State.HIDDEN;
            case "restore" -> State.ACTIVE;
            case "delete" -> State.DELETED;
            default -> throw BoardException.invalid();
        };
        try {
            moderation.change(type, id, next);
            return redirect("posts/" + postId, flash);
        } catch (BoardException e) {
            if (e.status() != HttpStatus.CONFLICT) throw e;
            error(e, model, response);
            return post(postId, 0, model);
        }
    }

    public record PinForm(@NotNull @Pattern(regexp = "true|false") String pinned) {}

    @PostMapping("/posts/{id}/pin")
    Object pin(@PathVariable long id, @Valid @ModelAttribute PinForm form, BindingResult binding,
            Model model, RedirectAttributes flash, HttpServletResponse response) {
        if (binding.hasErrors()) {
            error(BoardException.invalid("고정 여부는 true 또는 false로 입력해 주세요."), model, response);
            return post(id, 0, model);
        }
        try {
            moderation.pin(id, Boolean.parseBoolean(form.pinned()));
            return redirect("posts/" + id, flash);
        } catch (BoardException e) {
            if (e.status() != HttpStatus.CONFLICT) throw e;
            error(e, model, response);
            return post(id, 0, model);
        }
    }

    private <T> void page(Model model, String name, java.util.List<T> rows, int page) {
        model.addAttribute(name, rows.stream().limit(20).toList());
        model.addAttribute("page", page).addAttribute("hasNext", rows.size() > 20);
    }

    private void error(BoardException e, Model model, HttpServletResponse response) {
        response.setStatus(e.status().value());
        model.addAttribute("error", e.getMessage());
    }

    @ExceptionHandler(BoardException.class)
    ModelAndView boardError(BoardException e) {
        var view = new ModelAndView("admin/board/error");
        view.setStatus(e.status());
        view.addObject("error", e.getMessage());
        return view;
    }

    private RedirectView redirect(String path, RedirectAttributes flash) {
        flash.addFlashAttribute("message", "게시판 관리 조치를 저장했습니다.");
        var view = new RedirectView(BOARD + path, true);
        view.setStatusCode(HttpStatus.SEE_OTHER);
        return view;
    }
}
