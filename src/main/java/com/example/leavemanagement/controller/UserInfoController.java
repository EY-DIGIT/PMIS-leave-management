package com.example.leavemanagement.controller;

import com.example.leavemanagement.security.CurrentUser;
import com.example.leavemanagement.security.CurrentUserContext;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/userinfo")
@Tag(name = "User Info", description = "The authenticated caller's identity, resolved from the bearer token")
public class UserInfoController {

    @GetMapping
    public CurrentUser userInfo() {
        return CurrentUserContext.get();
    }
}
