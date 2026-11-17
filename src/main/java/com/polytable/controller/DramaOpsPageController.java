package com.polytable.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@ConditionalOnProperty(name = "ops.enabled", havingValue = "true", matchIfMissing = true)
public class DramaOpsPageController {

    @GetMapping({"/ops", "/ops/"})
    public void opsPage(HttpServletResponse response) {
        // 中文备注：必须发相对 Location，避免 Cloudflare/域名 https 被改写成 http://ip。
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", "index.html");
    }

    @GetMapping({"/ops/login", "/ops/login/"})
    public void opsLoginPage(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", "login.html");
    }
}
