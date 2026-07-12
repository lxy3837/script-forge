package com.erchuang.scriptforge.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Thymeleaf 页面路由控制器.
 *
 * @author ScriptForge Team
 */
@Controller
public class PageController {

    /**
     * 首页——项目列表.
     */
    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("pageTitle", "ScriptForge - 首页");
        return "index";
    }

    /**
     * 项目工作台.
     */
    @GetMapping("/projects/{id}")
    public String projectView(@PathVariable Long id, Model model) {
        model.addAttribute("projectId", id);
        model.addAttribute("pageTitle", "项目工作台");
        return "project";
    }

    /**
     * 剧本阅览页.
     */
    @GetMapping("/projects/{id}/script-viewer")
    public String scriptViewerView(@PathVariable Long id, Model model) {
        model.addAttribute("projectId", id);
        model.addAttribute("pageTitle", "剧本阅览");
        return "script-viewer";
    }

    /**
     * 审核报告页.
     */
    @GetMapping("/projects/{id}/review-report")
    public String reviewReportView(@PathVariable Long id, Model model) {
        model.addAttribute("projectId", id);
        model.addAttribute("pageTitle", "审核报告");
        return "review-report";
    }

    /**
     * 知识库管理页.
     */
    @GetMapping("/knowledge")
    public String knowledgeView(Model model) {
        model.addAttribute("pageTitle", "知识库管理");
        return "knowledge";
    }

    /**
     * 系统配置管理页.
     */
    @GetMapping("/config")
    public String configView(Model model) {
        model.addAttribute("pageTitle", "系统配置");
        return "config";
    }
}
