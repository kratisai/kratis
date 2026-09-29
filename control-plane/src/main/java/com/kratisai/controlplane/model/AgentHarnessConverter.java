package com.kratisai.controlplane.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class AgentHarnessConverter implements AttributeConverter<AgentHarness, String> {

    @Override
    public String convertToDatabaseColumn(AgentHarness harness) {
        return harness == null ? null : harness.name();
    }

    @Override
    public AgentHarness convertToEntityAttribute(String dbData) {
        return dbData == null || dbData.isBlank() ? null : AgentHarness.valueOf(dbData);
    }
}
