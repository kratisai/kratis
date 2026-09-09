package com.example;

import com.kratisai.controlplane.model.NodeType;
import com.kratisai.controlplane.model.RelationType;

import com.example.service.UserService;

public class UserController extends BaseController implements UserServiceInterface {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Override
    public String getUser(String id) {
        return userService.getUser(id);
    }

    public String listUsers() {
        return handleRequest("/users");
    }
}
