package com.example.demo.repository;

import com.example.demo.model.Answer;
import com.example.demo.model.Question;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class QaRepository {
    private final JdbcTemplate jdbc;

    public QaRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private final RowMapper<Question> qMap = (rs, n) -> {
        Question q = new Question();
        q.setId(rs.getLong("id"));
        q.setUserId(rs.getLong("user_id"));
        q.setUsername(rs.getString("username"));
        q.setTitle(rs.getString("title"));
        q.setContent(rs.getString("content"));
        q.setTags(rs.getString("tags"));
        q.setStatus(rs.getString("status"));
        q.setSelectedAnswerId((Long)rs.getObject("selected_answer_id"));
        q.setViews(rs.getInt("views"));
        q.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        q.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        return q;
    };

    private final RowMapper<Answer> aMap = (rs, n) -> {
        Answer a = new Answer();
        a.setId(rs.getLong("id"));
        a.setQuestionId(rs.getLong("question_id"));
        a.setUserId(rs.getLong("user_id"));
        a.setUsername(rs.getString("username"));
        a.setContent(rs.getString("content"));
        a.setSelected(rs.getBoolean("selected"));
        a.setUpvotes(rs.getInt("upvotes"));
        a.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        a.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        return a;
    };

    public Question saveQuestion(Question q){
        if(q.getId()==null){
            String sql = "INSERT INTO questions(user_id,username,title,content,tags,status,created_at,updated_at) VALUES(?,?,?,?,?,? ,?,?)";
            KeyHolder kh = new GeneratedKeyHolder();
            LocalDateTime now = LocalDateTime.now();
            jdbc.update(conn->{
                PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, q.getUserId());
                ps.setString(2, q.getUsername());
                ps.setString(3, q.getTitle());
                ps.setString(4, q.getContent());
                ps.setString(5, q.getTags());
                ps.setString(6, q.getStatus()==null?"OPEN":q.getStatus());
                ps.setTimestamp(7, Timestamp.valueOf(now));
                ps.setTimestamp(8, Timestamp.valueOf(now));
                return ps;
            }, kh);
            Map<String,Object> keys = kh.getKeys();
            q.setId(((Number)keys.getOrDefault("ID", keys.get("id"))).longValue());
            q.setCreatedAt(now); q.setUpdatedAt(now);
            return q;
        } else {
            String sql = "UPDATE questions SET title=?,content=?,tags=?,status=?,updated_at=? WHERE id=?";
            LocalDateTime now = LocalDateTime.now();
            jdbc.update(sql, q.getTitle(), q.getContent(), q.getTags(), q.getStatus(), Timestamp.valueOf(now), q.getId());
            q.setUpdatedAt(now); return q;
        }
    }

    public Optional<Question> findQuestion(Long id){
        List<Question> list = jdbc.query("SELECT * FROM questions WHERE id=?", qMap, id);
        return list.isEmpty()? Optional.empty(): Optional.of(list.get(0));
    }

    public List<Question> search(String keyword, String tag, String sort){
        String base = "SELECT * FROM questions";
        String where = "";
        if(keyword!=null && !keyword.isBlank()) where += (where.isEmpty()?" WHERE ":" AND ") + "(title LIKE ? OR content LIKE ?)";
        if(tag!=null && !tag.isBlank()) where += (where.isEmpty()?" WHERE ":" AND ") + "(tags LIKE ?)";
        String order = switch(String.valueOf(sort)){
            case "views" -> " ORDER BY views DESC, created_at DESC";
            case "recent" -> " ORDER BY created_at DESC";
            default -> " ORDER BY created_at DESC";
        };
        String sql = base + where + order;
        return jdbc.query(sql, ps -> {
            int i=1;
            if(keyword!=null && !keyword.isBlank()){ ps.setString(i++, "%"+keyword+"%"); ps.setString(i++, "%"+keyword+"%"); }
            if(tag!=null && !tag.isBlank()){ ps.setString(i++, "%"+tag+"%"); }
        }, qMap);
    }

    public Answer saveAnswer(Answer a){
        if(a.getId()==null){
            String sql = "INSERT INTO answers(question_id,user_id,username,content,selected,upvotes,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)";
            KeyHolder kh = new GeneratedKeyHolder();
            LocalDateTime now = LocalDateTime.now();
            jdbc.update(conn->{
                PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, a.getQuestionId());
                ps.setLong(2, a.getUserId());
                ps.setString(3, a.getUsername());
                ps.setString(4, a.getContent());
                ps.setBoolean(5, a.isSelected());
                ps.setInt(6, a.getUpvotes());
                ps.setTimestamp(7, Timestamp.valueOf(now));
                ps.setTimestamp(8, Timestamp.valueOf(now));
                return ps;
            }, kh);
            Map<String,Object> keys = kh.getKeys();
            a.setId(((Number)keys.getOrDefault("ID", keys.get("id"))).longValue());
            a.setCreatedAt(now); a.setUpdatedAt(now);
            return a;
        } else {
            String sql = "UPDATE answers SET content=?, selected=?, upvotes=?, updated_at=? WHERE id=?";
            LocalDateTime now = LocalDateTime.now();
            jdbc.update(sql, a.getContent(), a.isSelected(), a.getUpvotes(), Timestamp.valueOf(now), a.getId());
            a.setUpdatedAt(now); return a;
        }
    }

    public List<Answer> findAnswers(Long qid){
        return jdbc.query("SELECT * FROM answers WHERE question_id=? ORDER BY selected DESC, upvotes DESC, created_at ASC", aMap, qid);
    }

    public void selectAnswer(Long qid, Long aid){
        jdbc.update("UPDATE answers SET selected=FALSE WHERE question_id=?", qid);
        jdbc.update("UPDATE answers SET selected=TRUE WHERE id=?", aid);
        jdbc.update("UPDATE questions SET status='RESOLVED', selected_answer_id=?, updated_at=CURRENT_TIMESTAMP WHERE id=?", aid, qid);
    }

    public void deleteQuestion(Long id){ jdbc.update("DELETE FROM questions WHERE id=?", id); }
    public void deleteAnswer(Long id){ jdbc.update("DELETE FROM answers WHERE id=?", id); }
}

