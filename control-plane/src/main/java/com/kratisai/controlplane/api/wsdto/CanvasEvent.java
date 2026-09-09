package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kratisai.controlplane.model.CanvasEntity;
import com.kratisai.controlplane.model.CanvasType;
import java.util.UUID;

public sealed interface CanvasEvent {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Create(
            String documentId,
            UUID chatId,
            String title,
            String content,
            CanvasType canvasType,
            String repoLabel,
            boolean isNewRepo)
            implements CanvasEvent {
        public static Create of(CanvasEntity entity) {
            return new Create(
                    entity.getDocumentId(),
                    entity.getChatId(),
                    entity.getTitle(),
                    entity.getContent(),
                    entity.getCanvasType(),
                    entity.getRepoLabel(),
                    entity.isNewRepo());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Update(
            String documentId,
            UUID chatId,
            String title,
            String content,
            int version,
            CanvasType canvasType,
            String repoLabel,
            boolean isNewRepo)
            implements CanvasEvent {
        public static Update of(CanvasEntity entity, int version) {
            return new Update(
                    entity.getDocumentId(),
                    entity.getChatId(),
                    entity.getTitle(),
                    entity.getContent(),
                    version,
                    entity.getCanvasType(),
                    entity.getRepoLabel(),
                    entity.isNewRepo());
        }
    }

    record Commit(String documentId, UUID chatId, int version) implements CanvasEvent {}

    record Delete(String documentId, UUID chatId) implements CanvasEvent {}

    record Error(String documentId, UUID chatId, String errorMessage) implements CanvasEvent {}
}
