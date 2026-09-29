package com.example.service;

import com.kratisai.controlplane.model.NodeType;
import com.kratisai.controlplane.model.RelationType;

public class UserService {
    public String getUser(String id) {
        return "User-" + id;
    }
}