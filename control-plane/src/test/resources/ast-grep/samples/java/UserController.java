package com.kratisai;

import com.kratisai.controlplane.model.NodeType;
import com.kratisai.controlplane.model.RelationType;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.kratisai.client.UserServiceClient;

@RestController
public class UserController {
    @GetMapping("/api/users/list")
    public List<String> getUsers() {
        return List.of("alice", "bob");
    }
}
