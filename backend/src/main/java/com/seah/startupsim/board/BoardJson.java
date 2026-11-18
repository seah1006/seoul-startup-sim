package com.seah.startupsim.board;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Restrict JSON coercion here without changing the shared API mapper. */
final class BoardJson {
    private BoardJson() {}

    static final class Text extends ValueDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return context.reportInputMismatch(String.class, "텍스트가 필요합니다.");
            }
            return parser.getString();
        }
    }

    static final class IntegerValue extends ValueDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Long.class, "정수가 필요합니다.");
            }
            return parser.getLongValue();
        }
    }

    static final class Category extends ValueDeserializer<PostCategory> {
        @Override
        public PostCategory deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.hasToken(JsonToken.VALUE_STRING)) {
                try {
                    return PostCategory.valueOf(parser.getString());
                } catch (IllegalArgumentException ignored) {
                    // Below: keep a fixed validation message rather than echoing input.
                }
            }
            return context.reportInputMismatch(PostCategory.class, "지원하는 게시판 카테고리가 필요합니다.");
        }
    }
}
