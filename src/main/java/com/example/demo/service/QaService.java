package com.example.demo.service;

import com.example.demo.model.Answer;
import com.example.demo.model.Question;
import com.example.demo.repository.QaRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class QaService {
    private final QaRepository repo;
    public QaService(QaRepository repo){ this.repo = repo; }

    public Question saveQuestion(Question q){ return repo.saveQuestion(q); }
    public Optional<Question> findQuestion(Long id){ return repo.findQuestion(id); }
    public List<Question> search(String keyword, String tag, String sort){ return repo.search(keyword, tag, sort); }

    public Answer saveAnswer(Answer a){ return repo.saveAnswer(a); }
    public List<Answer> findAnswers(Long qid){ return repo.findAnswers(qid); }
    public void selectAnswer(Long qid, Long aid){ repo.selectAnswer(qid, aid); }
    public void deleteQuestion(Long id){ repo.deleteQuestion(id); }
    public void deleteAnswer(Long id){ repo.deleteAnswer(id); }
}

