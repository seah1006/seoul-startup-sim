package com.seah.startupsim.web;

import com.seah.startupsim.game.EventDefinitionRepository;
import com.seah.startupsim.game.EventDefinitionRepository.Kind;
import com.seah.startupsim.game.EventDefinitionValidator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 관리자 저장은 부팅·사용 시와 같은 검증기를 통과해야 하며 통과하지 못하면 아무것도 저장하지 않는다. */
@Service
public class AdminEventService {
    /** 목록 한 줄. 이름 칸은 전환점·순간은 제목, 반응은 작성자다. */
    public record Row(long id, String code, String name, boolean enabled, int rev, long recentFires, String tier, String source) {}

    /** 저장 거부 사유. 메시지에는 검증기가 만든 JSON 경로가 들어 있다. */
    public static class InvalidDefinition extends RuntimeException {
        public InvalidDefinition(String message) {
            super(message);
        }
    }

    private final EventDefinitionRepository definitions;
    private final EventDefinitionValidator validator;
    private final ObjectMapper json;

    public AdminEventService(EventDefinitionRepository definitions, EventDefinitionValidator validator, ObjectMapper json) {
        this.definitions = definitions;
        this.validator = validator;
        this.json = json;
    }

    public List<Row> rows(Kind kind) {
        Map<Long, Long> fires = kind == Kind.TURNING_POINT ? definitions.recentFires() : Map.of();
        return definitions.raw(kind).stream().map(definition -> {
            JsonNode data = definition.data();
            String name = data.has("title") ? data.get("title").asString() : data.get("author").asString();
            return new Row(definition.id(), definition.code(), name, data.get("enabled").booleanValue(),
                    definition.rev(), fires.getOrDefault(definition.id(), 0L),
                    data.has("tier") ? data.get("tier").asString() : null, data.has("source") ? data.get("source").asString() : null);
        }).toList();
    }

    public String editable(Kind kind, long id) {
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(existing(kind, id).data());
    }

    @Transactional
    public void toggle(Kind kind, long id) {
        existing(kind, id);
        definitions.toggle(kind, id);
    }

    private EventDefinitionRepository.Definition existing(Kind kind, long id) {
        try {
            return definitions.raw(kind, id);
        } catch (com.seah.startupsim.game.GameException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    @Transactional
    public void save(Kind kind, long id, String text) {
        existing(kind, id);
        JsonNode data;
        try {
            data = json.readTree(text);
        } catch (JacksonException exception) {
            throw new InvalidDefinition("JSON 형식이 올바르지 않습니다.");
        }
        if (data == null || !data.isObject()) {
            throw new InvalidDefinition("정의는 JSON 객체여야 합니다.");
        }
        try {
            switch (kind) {
                case TURNING_POINT -> validator.turningPoint(data);
                case REACTION -> validator.reaction(data);
                case MOMENT -> validator.moment(data);
            }
            definitions.update(kind, id, data);
        } catch (com.seah.startupsim.game.GameException exception) {
            throw new InvalidDefinition(exception.getMessage());
        }
    }
}
