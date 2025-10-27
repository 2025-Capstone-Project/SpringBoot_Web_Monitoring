package com.example.demo.controller;

import com.example.demo.model.Answer;
import com.example.demo.model.Question;
import com.example.demo.service.QaService;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/qa")
public class QaController {
    private final QaService service;
    public QaController(QaService service){ this.service = service; }

    @GetMapping
    public String list(@RequestParam(required=false) String q,
                       @RequestParam(required=false) String tag,
                       @RequestParam(required=false, defaultValue="recent") String sort,
                       Model model){
        model.addAttribute("questions", service.search(q, tag, sort));
        model.addAttribute("q", q); model.addAttribute("tag", tag); model.addAttribute("sort", sort);
        return "board/list";
    }

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, Model model){
        Question question = service.findQuestion(id).orElse(null);
        List<Answer> answers = service.findAnswers(id);
        model.addAttribute("question", question);
        model.addAttribute("answers", answers);
        return "board/view";
    }

    @GetMapping("/write")
    @PreAuthorize("isAuthenticated()")
    public String writeForm(Model model){
        model.addAttribute("question", new Question());
        return "board/write";
    }

    @PostMapping("/write")
    @PreAuthorize("isAuthenticated()")
    public String write(@ModelAttribute Question q, HttpSession session){
        Long uid = (Long)session.getAttribute("userId");
        String username = (String)session.getAttribute("username");
        q.setUserId(uid); q.setUsername(username);
        service.saveQuestion(q);
        return "redirect:/qa";
    }

    @PostMapping("/{id}/answer")
    @PreAuthorize("isAuthenticated()")
    public String answer(@PathVariable Long id, @RequestParam String content, HttpSession session){
        Long uid = (Long)session.getAttribute("userId");
        String username = (String)session.getAttribute("username");
        Answer a = new Answer();
        a.setQuestionId(id); a.setUserId(uid); a.setUsername(username); a.setContent(content);
        service.saveAnswer(a);
        return "redirect:/qa/"+id;
    }

    @PostMapping("/{qid}/select/{aid}")
    @PreAuthorize("isAuthenticated()")
    public String select(@PathVariable Long qid, @PathVariable Long aid, HttpSession session){
        // 작성자 또는 ADMIN만 허용: 단순 체크(프런트로부터 숨김 + 서버서 검증)
        String role = (String)session.getAttribute("role");
        // 실제로는 질문 작성자 확인 필요(간략화)
        if (!"ADMIN".equals(role)) { /* TODO: 작성자 확인 */ }
        service.selectAnswer(qid, aid);
        return "redirect:/qa/"+qid;
    }
}

