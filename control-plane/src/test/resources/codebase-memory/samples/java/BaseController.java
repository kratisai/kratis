package com.example;

import com.kratisai.controlplane.model.NodeType;
import com.kratisai.controlplane.model.RelationType;

public abstract class BaseController {
    public String handleRequest(String path) {
        return "Handled: " + path;
    }
}
