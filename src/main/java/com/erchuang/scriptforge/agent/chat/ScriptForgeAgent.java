package com.erchuang.scriptforge.agent.chat;

import com.erchuang.scriptforge.agent.character.CharacterRetrievalAgent;
import com.erchuang.scriptforge.agent.document.DocumentAgent;
import com.erchuang.scriptforge.agent.orchestrator.AgentResult;
import com.erchuang.scriptforge.agent.orchestrator.WorkflowOrchestrator;
import com.erchuang.scriptforge.agent.outline.OutlineAgent;
import com.erchuang.scriptforge.agent.requirement.RequirementAgent;
import com.erchuang.scriptforge.agent.review.ReviewAgent;
import com.erchuang.scriptforge.agent.script.ScriptAgent;
import com.erchuang.scriptforge.agent.search.RealtimeSearchAgent;
import com.erchuang.scriptforge.agent.search.SearchResultStore;
import com.erchuang.scriptforge.agent.question.QuestionAgent;
import com.erchuang.scriptforge.infra.BusinessException;
import com.erchuang.scriptforge.infra.ErrorCode;
import com.erchuang.scriptforge.infra.SseEmitterService;
import com.erchuang.scriptforge.llm.DeepSeekClient;
import com.erchuang.scriptforge.llm.DeepSeekClient.ChatMessage;
import com.erchuang.scriptforge.llm.DeepSeekClient.ChatToolResponse;
import com.erchuang.scriptforge.llm.DeepSeekClient.ToolCallRequest;
import com.erchuang.scriptforge.llm.DeepSeekClient.ToolDef;
import com.erchuang.scriptforge.model.dto.ProjectDTO;
import com.erchuang.scriptforge.model.entity.Project;
import com.erchuang.scriptforge.model.enums.ProjectStatus;
import com.erchuang.scriptforge.repository.ChatMessageRepository;
import com.erchuang.scriptforge.repository.CharacterCardRepository;
import com.erchuang.scriptforge.repository.KnowledgeEntryRepository;
import com.erchuang.scriptforge.repository.OutlineRepository;
import com.erchuang.scriptforge.repository.ProjectRepository;
import com.erchuang.scriptforge.repository.RequirementRepository;
import com.erchuang.scriptforge.repository.ReviewReportRepository;
import com.erchuang.scriptforge.repository.ScriptChapterRepository;
import com.erchuang.scriptforge.repository.ScriptRepository;
import com.erchuang.scriptforge.service.ProjectService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * ScriptForge Solo Agent —— 自主推理 + 工具调用 + 任务规划.
 *
 * <p>真实 solo agent 行为：
 * <ol>
 *   <li>收到请求 → 先思考分析（流式输出思考过程）</li>
 *   <li>制定任务计划 → 分解为子任务</li>
 *   <li>自主调用子 Agent（需求调研、搜索、大纲、剧本生成等）</li>
 *   <li>流式输出最终回复</li>
 * </ol>
 * </p>
 */
@Component
public class ScriptForgeAgent {

    private static final Logger log = LoggerFactory.getLogger(ScriptForgeAgent.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_TOOL_ROUNDS = 8;

    private final DeepSeekClient deepSeekClient;
    private final ProjectService projectService;
    private final ProjectRepository projectRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final RequirementRepository requirementRepository;
    private final OutlineRepository outlineRepository;
    private final ScriptRepository scriptRepository;
    private final ScriptChapterRepository scriptChapterRepository;
    private final ReviewReportRepository reviewReportRepository;
    private final KnowledgeEntryRepository knowledgeEntryRepository;
    private final CharacterCardRepository characterCardRepository;
    private final SearchResultStore searchResultStore;
    private final WorkflowOrchestrator workflowOrchestrator;
    private final RealtimeSearchAgent searchAgent;
    private final CharacterRetrievalAgent characterAgent;
    private final RequirementAgent requirementAgent;
    private final OutlineAgent outlineAgent;
    private final ScriptAgent scriptAgent;
    private final ReviewAgent reviewAgent;
    private final DocumentAgent documentAgent;
    private final QuestionAgent questionAgent;
    private final SseEmitterService sseEmitterService;

    @Value("${app.workspace-dir:./workspaces}")
    private String workspaceDir;

    @Value("${deepseek.chat.max-tokens}")
    private int maxTokens;

    @Value("${deepseek.chat.temperature}")
    private double temperature;

    public ScriptForgeAgent(DeepSeekClient deepSeekClient,
                            ProjectService projectService,
                            ProjectRepository projectRepository,
                            ChatMessageRepository chatMessageRepository,
                            RequirementRepository requirementRepository,
                            OutlineRepository outlineRepository,
                            ScriptRepository scriptRepository,
                            ScriptChapterRepository scriptChapterRepository,
                            ReviewReportRepository reviewReportRepository,
                            KnowledgeEntryRepository knowledgeEntryRepository,
                            CharacterCardRepository characterCardRepository,
                            SearchResultStore searchResultStore,
                            WorkflowOrchestrator workflowOrchestrator,
                            RealtimeSearchAgent searchAgent,
                            CharacterRetrievalAgent characterAgent,
                            RequirementAgent requirementAgent,
                            OutlineAgent outlineAgent,
                            ScriptAgent scriptAgent,
                            ReviewAgent reviewAgent,
                            DocumentAgent documentAgent,
                            QuestionAgent questionAgent,
                            SseEmitterService sseEmitterService) {
        this.deepSeekClient = deepSeekClient;
        this.projectService = projectService;
        this.projectRepository = projectRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.requirementRepository = requirementRepository;
        this.outlineRepository = outlineRepository;
        this.scriptRepository = scriptRepository;
        this.scriptChapterRepository = scriptChapterRepository;
        this.reviewReportRepository = reviewReportRepository;
        this.knowledgeEntryRepository = knowledgeEntryRepository;
        this.characterCardRepository = characterCardRepository;
        this.searchResultStore = searchResultStore;
        this.workflowOrchestrator = workflowOrchestrator;
        this.searchAgent = searchAgent;
        this.characterAgent = characterAgent;
        this.requirementAgent = requirementAgent;
        this.outlineAgent = outlineAgent;
        this.scriptAgent = scriptAgent;
        this.reviewAgent = reviewAgent;
        this.documentAgent = documentAgent;
        this.questionAgent = questionAgent;
        this.sseEmitterService = sseEmitterService;
    }

    /** 同一项目同一时间只允许一个对话处理，防止事件交叉污染 */
    private final java.util.concurrent.ConcurrentHashMap<Long, Boolean> activeProcessing
            = new java.util.concurrent.ConcurrentHashMap<>();

    /** 排队重试次数限制（防止无界递归） */
    private final java.util.concurrent.ConcurrentHashMap<Long, Integer> retryCount
            = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_RETRY = 3;

    /** 当前线程的会话 ID（通过 ThreadLocal 在 handleChatMessage → saveChatMessage 之间传递） */
    private final ThreadLocal<Long> currentSessionId = new ThreadLocal<>();

    // ==================== 工具定义 ====================

    private static final List<ToolDef> TOOLS = List.of(
            // --- 项目管理 ---
            new ToolDef("create_project", "创建一个新的二创剧本项目",
                    Map.of("type", "object",
                            "properties", Map.of(
                                    "title", Map.of("type", "string", "description", "项目标题"),
                                    "gameName", Map.of("type", "string", "description", "游戏名称，如'原神'、'崩坏星穹铁道'、'鸣潮'")
                            ),
                            "required", List.of("title", "gameName"))),
            new ToolDef("list_projects", "列出所有已有项目，返回项目ID、标题、状态",
                    Map.of("type", "object", "properties", Map.of())),
            new ToolDef("get_project_status", "查询指定项目的详细状态",
                    Map.of("type", "object",
                            "properties", Map.of("projectId", Map.of("type", "integer", "description", "项目ID")),
                            "required", List.of("projectId"))),
            new ToolDef("start_workflow", "启动项目的完整自动化工作流（需求调研→信息检索→大纲设计→剧本生成→质量审核→导出）",
                    Map.of("type", "object",
                            "properties", Map.of("projectId", Map.of("type", "integer", "description", "项目ID")),
                            "required", List.of("projectId"))),
            new ToolDef("delete_project", "删除指定项目",
                    Map.of("type", "object",
                            "properties", Map.of("projectId", Map.of("type", "integer", "description", "项目ID")),
                            "required", List.of("projectId"))),

            // --- 子 Agent 调用 ---
            new ToolDef("call_search_agent", "调用联网搜索 Agent，搜索游戏角色、世界观、事件等实时信息",
                    Map.of("type", "object",
                            "properties", Map.of("projectId", Map.of("type", "integer", "description", "项目ID")),
                            "required", List.of("projectId"))),
            new ToolDef("call_character_agent", "调用角色检索 Agent，从知识库中检索游戏角色信息和角色卡",
                    Map.of("type", "object",
                            "properties", Map.of("projectId", Map.of("type", "integer", "description", "项目ID")),
                            "required", List.of("projectId"))),
            new ToolDef("call_requirement_agent", "调用需求调研 Agent，分析用户需求并生成结构化需求文档",
                    Map.of("type", "object",
                            "properties", Map.of(
                                    "projectId", Map.of("type", "integer", "description", "项目ID"),
                                    "supplementary", Map.of("type", "string", "description", "补充需求说明（可选）")
                            ),
                            "required", List.of("projectId"))),
            new ToolDef("call_outline_agent", "调用大纲设计 Agent，为项目生成分章大纲方案",
                    Map.of("type", "object",
                            "properties", Map.of("projectId", Map.of("type", "integer", "description", "项目ID")),
                            "required", List.of("projectId"))),
            new ToolDef("call_script_agent", "调用剧本生成 Agent，根据大纲生成完整剧本",
                    Map.of("type", "object",
                            "properties", Map.of("projectId", Map.of("type", "integer", "description", "项目ID")),
                            "required", List.of("projectId"))),
            new ToolDef("call_review_agent", "调用质量审核 Agent，检查组叔质量（OOC检测、逻辑一致性、节奏分析）",
                    Map.of("type", "object",
                            "properties", Map.of("projectId", Map.of("type", "integer", "description", "项目ID")),
                            "required", List.of("projectId"))),
            new ToolDef("call_question_agent", "向用户提问以澄清需求或获取反馈",
                    Map.of("type", "object",
                            "properties", Map.of(
                                    "projectId", Map.of("type", "integer", "description", "项目ID"),
                                    "question", Map.of("type", "string", "description", "要问用户的问题")
                            ),
                            "required", List.of("projectId", "question"))),

            // --- 文件/数据读取（读取已生成的项目内容） ---
            new ToolDef("read_requirement", "读取项目已生成的需求分析文档",
                    Map.of("type", "object",
                            "properties", Map.of("projectId", Map.of("type", "integer", "description", "项目ID")),
                            "required", List.of("projectId"))),
            new ToolDef("read_outline", "读取项目已生成的大纲方案（含选中版本的分章结构）",
                    Map.of("type", "object",
                            "properties", Map.of("projectId", Map.of("type", "integer", "description", "项目ID")),
                            "required", List.of("projectId"))),
            new ToolDef("read_script", "读取项目已生成的剧本内容（含所有章节正文）",
                    Map.of("type", "object",
                            "properties", Map.of("projectId", Map.of("type", "integer", "description", "项目ID")),
                            "required", List.of("projectId"))),
            new ToolDef("read_review", "读取项目的质量审核报告（OOC检测、逻辑一致性和节奏分析）",
                    Map.of("type", "object",
                            "properties", Map.of("projectId", Map.of("type", "integer", "description", "项目ID")),
                            "required", List.of("projectId"))),
            new ToolDef("read_search_results", "读取项目已保存的搜索结果和角色分析",
                    Map.of("type", "object",
                            "properties", Map.of("projectId", Map.of("type", "integer", "description", "项目ID")),
                            "required", List.of("projectId"))),
            new ToolDef("list_knowledge", "列出指定游戏的知识库条目（wiki、角色资料等）",
                    Map.of("type", "object",
                            "properties", Map.of("gameName", Map.of("type", "string", "description", "游戏名称")),
                            "required", List.of("gameName"))),
            new ToolDef("list_character_cards", "列出指定游戏的角色卡列表",
                    Map.of("type", "object",
                            "properties", Map.of("gameName", Map.of("type", "string", "description", "游戏名称")),
                            "required", List.of("gameName"))),

            // --- 文件操作（写入/修改项目文件） ---
            new ToolDef("write_file", "将内容写入指定文件（新建或覆盖）。写入后前端会自动导航到该文件并在文件树中高亮显示。",
                    Map.of("type", "object",
                            "properties", Map.of(
                                    "projectId", Map.of("type", "integer", "description", "项目ID"),
                                    "filePath", Map.of("type", "string", "description", "相对于项目工作空间的路径，如 chapters/chapter-1.md、script-full.md"),
                                    "content", Map.of("type", "string", "description", "要写入的完整文件内容")
                            ),
                            "required", List.of("projectId", "filePath", "content"))),
            new ToolDef("replace_in_file", "替换文件中的指定内容（精确匹配），修改后前端会自动导航到该文件。",
                    Map.of("type", "object",
                            "properties", Map.of(
                                    "projectId", Map.of("type", "integer", "description", "项目ID"),
                                    "filePath", Map.of("type", "string", "description", "要修改的文件路径"),
                                    "old_str", Map.of("type", "string", "description", "要被替换的原文（精确匹配，建议5行以上确保唯一性）"),
                                    "new_str", Map.of("type", "string", "description", "替换后的新内容")
                            ),
                            "required", List.of("projectId", "filePath", "old_str", "new_str"))),
            new ToolDef("read_file", "读取项目工作空间中指定文件的内容",
                    Map.of("type", "object",
                            "properties", Map.of(
                                    "projectId", Map.of("type", "integer", "description", "项目ID"),
                                    "filePath", Map.of("type", "string", "description", "文件路径")
                            ),
                            "required", List.of("projectId", "filePath"))),

            // --- 终端/搜索工具 ---
            new ToolDef("execute_command", "在项目工作空间中执行终端命令（用于搜索文件、列出目录等）。命令在项目工作空间目录下执行，输出最多2000字符，超时30秒。常用命令：dir / ls（列目录）、find / findstr（搜索文件内容）、type / cat（查看文件）。",
                    Map.of("type", "object",
                            "properties", Map.of(
                                    "projectId", Map.of("type", "integer", "description", "项目ID"),
                                    "command", Map.of("type", "string", "description", "要执行的终端命令，如 dir、findstr \"关键词\" *.md")
                            ),
                            "required", List.of("projectId", "command"))),
            new ToolDef("search_files", "搜索项目文件内容（类似 grep）或按文件名模糊搜索。返回匹配的文件路径和行内容。",
                    Map.of("type", "object",
                            "properties", Map.of(
                                    "projectId", Map.of("type", "integer", "description", "项目ID"),
                                    "pattern", Map.of("type", "string", "description", "搜索关键词或正则表达式"),
                                    "filePattern", Map.of("type", "string", "description", "文件名过滤，如 *.md、*.* （可选）")
                            ),
                            "required", List.of("projectId", "pattern"))),
            new ToolDef("list_files", "列出项目工作空间中的文件目录结构",
                    Map.of("type", "object",
                            "properties", Map.of(
                                    "projectId", Map.of("type", "integer", "description", "项目ID"),
                                    "dir", Map.of("type", "string", "description", "子目录路径（可选，默认为根目录）")
                            ),
                            "required", List.of("projectId")))
    );

    private static final String SYSTEM_PROMPT = """
            你是 ScriptForge Agent，一个专精二次元游戏（二游）二创剧本生成的全能自主推理智能体。
            
            你运行在一个完整的 Java + Spring Boot 项目服务器上，可以直接访问项目的数据库、文件系统和 WebSocket 管道。

            ## 核心原则
            1. **先思考，再行动** — 收到请求后，先分析用户意图，制定计划，再逐步执行。你的思考过程可以展示给用户。
            2. **自主决策** — 根据请求复杂度，自行判断需要调用哪些工具、调用顺序、是否需要先搜索信息。
            3. **任务分解** — 复杂请求要拆解成子任务，逐个完成。在思考过程中列出任务清单。
            4. **循序渐进** — 剧本生成有严格的依赖链：先创建项目 → 需求调研 → 信息检索 → 大纲设计 → 剧本生成 → 质量审核。不要跳步。
            5. **信息充分** — 涉及角色、世界观等知识时，优先调用搜索 Agent 或角色检索 Agent 获取信息。
            6. **主动澄清** — 如果用户请求模糊，使用 call_question_agent 提问。

            ## 工作流知识
            你操控的系统有以下生成流水线：
            - 创建项目(create_project) → 需求调研(call_requirement_agent) → 联网搜索(call_search_agent) + 角色检索(call_character_agent) → 大纲设计(call_outline_agent) → 剧本生成(call_script_agent) → 质量审核(call_review_agent)
            - 也可以直接使用 start_workflow 一键启动全自动流水线（推荐用于简单请求）
            - 部分步骤之间可能需要用户确认（系统会自动处理）

            ## 数据读取能力（数据库）
            你可以读取项目中已生成的所有内容，直接从数据库获取：
            - read_requirement — 读取需求分析文档
            - read_outline — 读取大纲方案（含分章结构）
            - read_script — 读取完整剧本正文（所有章节）
            - read_review — 读取质量审核报告（OOC/逻辑/节奏）
            - read_search_results — 读取保存的搜索结果和角色分析
            - list_knowledge — 列出知识库条目
            - list_character_cards — 列出角色卡
            - list_projects — 列出所有已有项目
            - get_project_status — 查询指定项目的详细状态
            当用户询问项目已有内容时，优先调用这些工具读取，而不是重新生成。

            ## 文件操作能力（项目工作空间）
            你可以像本地开发者一样操作项目工作空间中的文件：
            - read_file — 读取工作空间中任意文件的内容
            - write_file — 创建或覆盖文件（写入后前端会自动打开该文件）
            - replace_in_file — 精确替换文件中的指定内容
            - list_files — 列出工作空间目录结构
            - search_files — 在文件中搜索关键词（类似 grep）
            - execute_command — 在项目目录下执行终端命令（如 dir、findstr、type）
            当用户想看文件内容时，直接用 read_file 打开；想搜特定情节时，用 search_files。

            ## 项目管理能力
            - create_project — 创建新的二创项目（需要 title 和 gameName）
            - delete_project — 删除指定项目
            - start_workflow — 一键启动全自动生成流水线
            当用户说"帮我创建一个XX游戏的二创"时，先 create_project，再按需推进。

            ## 回复风格
            - 始终用中文回复
            - 先展示分析思考，再展示行动，最后总结
            - 不要怀疑自己的文件读取能力，你完全可以直接访问项目的所有文件和数据
            - 工具调用结果可以简要概括，不要原文照搬
            - 发生错误时，冷静分析原因并建议替代方案""";

    // ==================== 核心方法 ====================

    /**
     * 处理用户聊天消息（异步，通过 WebSocket 推送各种类型的事件）.
     */
    public void handleChatMessage(Long projectId, Long sessionId, String userMessage) {
        // 同一项目只允许一个对话处理，新消息排队等待
        if (activeProcessing.putIfAbsent(projectId, true) != null) {
            int currentRetry = retryCount.merge(projectId, 1, Integer::sum);
            if (currentRetry > MAX_RETRY) {
                log.warn("Project {}: max retry ({}) exceeded, discarding message", projectId, MAX_RETRY);
                retryCount.remove(projectId);
                return;
            }
            log.warn("Project {} already has an active chat processing — queuing (retry {}/{})", projectId, currentRetry, MAX_RETRY);
            // 等 500ms 后重试（给上一轮完成的机会）
            CompletableFuture.runAsync(() -> {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                handleChatMessage(projectId, sessionId, userMessage);
            });
            return;
        }
        // 清理重试计数
        retryCount.remove(projectId);
        // 将 sessionId 绑定到当前线程
        currentSessionId.set(sessionId);
        CompletableFuture.runAsync(() -> {
            // 在异步线程中重新绑定 sessionId
            currentSessionId.set(sessionId);
            try {
                // 0. 保存用户消息到数据库
                saveChatMessage(projectId, "user", userMessage, "user");

                // 1. 先让 LLM 流式输出思考过程
                sendChatEvent(projectId, "think_start", "");
                streamThinking(projectId, userMessage);

                // 2. ReAct 循环：思考 + 工具调用 + 结果分析
                List<ChatMessage> messages = new ArrayList<>();
                messages.add(ChatMessage.system(SYSTEM_PROMPT));
                messages.add(ChatMessage.user(userMessage));

                for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                    ChatToolResponse resp = deepSeekClient.chatWithTools(messages, TOOLS);

                    // 2a. 先处理 LLM 的文字输出（思考/分析）
                    if (resp.content() != null && !resp.content().isBlank()) {
                        sendChatEvent(projectId, "think_chunk", resp.content());
                    }

                    // 2b. 处理工具调用
                    if (resp.toolCalls() != null && !resp.toolCalls().isEmpty()) {
                        for (ToolCallRequest tc : resp.toolCalls()) {
                            // 告诉前端正在调用哪个工具（结构化 JSON）
                            sendStructuredToolEvent(projectId, "tool_call", tc);
                            String result = executeTool(tc, projectId);
                            sendStructuredToolEvent(projectId, "tool_result", tc, result);

                            messages.add(ChatMessage.assistant(
                                    "已调用 " + tc.name() + "，参数: " + tc.arguments()));
                            messages.add(ChatMessage.user(
                                    "工具 [" + tc.name() + "] 执行结果:\n" + result));
                        }
                        // 继续循环，让 LLM 分析工具结果
                    } else {
                        // 无工具调用 → 流式输出最终回复
                        sendChatEvent(projectId, "think_end", "");
                        streamFinalReply(projectId, messages);
                        return;
                    }
                }
                // 达到最大轮数
                sendChatEvent(projectId, "think_end", "");
                streamFinalReply(projectId, messages);
            } catch (Exception e) {
                log.error("ScriptForgeAgent error for project {}: {}", projectId, e.getMessage(), e);
                sendChatEvent(projectId, "error", "处理请求时出错: " + e.getMessage());
                sendChatEvent(projectId, "done", "");
            } finally {
                activeProcessing.remove(projectId);
            }
        });
    }

    /**
     * 让 LLM 先输出思考分析（流式，不带工具），帮助用户理解 Agent 的推理过程.
     */
    private void streamThinking(Long projectId, String userMessage) {
        List<ChatMessage> thinkMsgs = List.of(
                ChatMessage.system("""
                        你是 ScriptForge Agent 的思考模块。请分析用户的请求，列出你的理解和执行计划。
                        
                        重要背景：
                        - 你运行在一个完整的服务器上，可以直接访问项目数据库和文件系统
                        - 你可以读取项目中的所有文件、搜索文件内容、列出目录、执行终端命令
                        - 当用户提到文件时，你完全有能力用 read_file 打开、用 list_files 浏览、用 search_files 搜索
                        - 你不会说"我无法直接看到文件"——你当然可以，这是你的核心能力之一
                        
                        输出要求：
                        - 用中文，简洁但完整
                        - 先总结你理解的用户意图
                        - 列出需要完成的子任务（用数字列表）
                        - 说明你会调用哪些工具/Agent
                        - 格式：先给一两个自然段的分析，然后是一个任务清单
                        - 不要调用任何工具，只输出你的思考过程
                        """),
                ChatMessage.user(userMessage)
        );

        StringBuilder buffer = new StringBuilder();
        // 定期保存计数器：每30个chunk保存一次到DB，防止中途刷新丢失
        java.util.concurrent.atomic.AtomicInteger chunkCount = new java.util.concurrent.atomic.AtomicInteger(0);
        try {
            deepSeekClient.chatStreamWithReasoning(thinkMsgs, maxTokens / 2, temperature)
                    .doOnNext(chunk -> {
                        String text = chunk.effectiveText();
                        if (!text.isEmpty()) {
                            buffer.append(text);
                            sendChatEvent(projectId, "think_chunk", text);
                            // 每30个chunk自动保存到DB
                            if (chunkCount.incrementAndGet() % 30 == 0 && buffer.length() > 100) {
                                saveThinkingToDb(projectId, buffer.toString());
                            }
                        }
                    })
                    .doOnComplete(() -> {
                        saveThinkingToDb(projectId, buffer.toString());
                    })
                    .blockLast();
        } catch (Exception e) {
            log.warn("Thinking stream interrupted: {}", e.getMessage());
            if (buffer.length() > 0) {
                saveThinkingToDb(projectId, buffer.toString());
                sendChatEvent(projectId, "think_chunk", buffer.toString());
            }
        }
    }

    /**
     * 流式输出最终回复.
     */
    private void streamFinalReply(Long projectId, List<ChatMessage> messages) {
        List<ChatMessage> streamMsgs = new ArrayList<>(messages);
        streamMsgs.add(ChatMessage.user("请根据以上所有信息，直接给出最终回复。简洁清晰，使用中文。"));

        StringBuilder fullContent = new StringBuilder();
        try {
            deepSeekClient.chatStream(streamMsgs, maxTokens, temperature)
                    .doOnNext(chunk -> {
                        fullContent.append(chunk);
                        sendChatEvent(projectId, "reply_chunk", chunk);
                    })
                    .doOnComplete(() -> {
                        saveReplyToDb(projectId, fullContent.toString());
                        sendChatEvent(projectId, "done", "");
                    })
                    .doOnError(err -> {
                        if (fullContent.length() > 0) {
                            sendChatEvent(projectId, "reply_chunk", fullContent.toString());
                        }
                        sendChatEvent(projectId, "error", "流式输出中断: " + err.getMessage());
                        sendChatEvent(projectId, "done", "");
                    })
                    .blockLast();
        } catch (Exception e) {
            log.error("Stream final reply error: {}", e.getMessage(), e);
            if (fullContent.length() > 0) {
                sendChatEvent(projectId, "reply_chunk", fullContent.toString());
            }
            sendChatEvent(projectId, "error", "回复生成失败: " + e.getMessage());
            sendChatEvent(projectId, "done", "");
        }
    }

    // ==================== 工具执行 ====================

    private String executeTool(ToolCallRequest tc, Long contextProjectId) {
        log.info("ScriptForgeAgent executing tool: {} with args: {}", tc.name(), tc.arguments());
        try {
            return switch (tc.name()) {
                // --- 项目管理 ---
                case "create_project" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    String title = args.has("title") ? args.get("title").asText() : "未命名";
                    String game = args.has("gameName") ? args.get("gameName").asText() : "";
                    ProjectDTO p = projectService.createProject(title, game);
                    yield "项目创建成功！ID=" + p.getId() + "，标题=" + p.getTitle()
                            + "，游戏=" + p.getGameName() + "，状态=" + p.getStatus();
                }
                case "list_projects" -> {
                    var projects = projectRepository.findAllByOrderByDisplayOrderAsc();
                    if (projects.isEmpty()) yield "当前没有任何项目。";
                    var sb = new StringBuilder();
                    for (Project p : projects) {
                        sb.append("#").append(p.getDisplayOrder())
                                .append(" ID=").append(p.getId())
                                .append(" ").append(p.getTitle())
                                .append(" (").append(p.getGameName()).append(")")
                                .append(" [").append(p.getStatus()).append("]");
                        if (p.getCurrentStep() != null)
                            sb.append(" 当前步骤=").append(p.getCurrentStep());
                        sb.append("\n");
                    }
                    yield sb.toString();
                }
                case "get_project_status" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    long pid = args.get("projectId").asLong();
                    var opt = projectRepository.findById(pid);
                    if (opt.isEmpty()) yield "未找到 ID=" + pid + " 的项目。";
                    Project p = opt.get();
                    yield "ID=" + p.getId() + " 标题=" + p.getTitle()
                            + " 游戏=" + p.getGameName() + " 状态=" + p.getStatus()
                            + " 当前步骤=" + (p.getCurrentStep() != null ? p.getCurrentStep() : "未开始");
                }
                case "start_workflow" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    long pid = args.get("projectId").asLong();
                    workflowOrchestrator.startWorkflow(pid);
                    yield "项目 " + pid + " 的自动化工作流已启动！系统将依次执行：需求调研→信息检索→大纲设计→剧本生成→质量审核→导出。请在项目页面查看实时进度。";
                }
                case "delete_project" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    long pid = args.get("projectId").asLong();
                    projectService.deleteProject(pid);
                    yield "项目 " + pid + " 已删除。";
                }
                // --- 子 Agent 调用 ---
                case "call_search_agent" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    long pid = args.has("projectId") ? args.get("projectId").asLong() : ensureProjectId(contextProjectId);
                    AgentResult r = searchAgent.execute(pid);
                    yield r.isSuccess() ? summarize(r.getData(), 800) : "搜索失败: " + r.getErrorMessage();
                }
                case "call_character_agent" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    long pid = args.has("projectId") ? args.get("projectId").asLong() : ensureProjectId(contextProjectId);
                    AgentResult r = characterAgent.execute(pid);
                    yield r.isSuccess() ? summarize(r.getData(), 600) : "角色检索失败: " + r.getErrorMessage();
                }
                case "call_requirement_agent" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    long pid = args.has("projectId") ? args.get("projectId").asLong() : ensureProjectId(contextProjectId);
                    String sup = args.has("supplementary") && !args.get("supplementary").isNull()
                            ? args.get("supplementary").asText() : null;
                    AgentResult r = sup != null
                            ? requirementAgent.executeWithSupplementary(pid, sup)
                            : requirementAgent.execute(pid);
                    yield r.isSuccess() ? summarize(r.getData(), 600) : "需求分析失败: " + r.getErrorMessage();
                }
                case "call_outline_agent" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    long pid = args.has("projectId") ? args.get("projectId").asLong() : ensureProjectId(contextProjectId);
                    AgentResult r = outlineAgent.execute(pid);
                    yield r.isSuccess() ? summarize(r.getData(), 800) : "大纲设计失败: " + r.getErrorMessage();
                }
                case "call_script_agent" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    long pid = args.has("projectId") ? args.get("projectId").asLong() : ensureProjectId(contextProjectId);
                    AgentResult r = scriptAgent.execute(pid);
                    yield r.isSuccess() ? summarize(r.getData(), 1000) : "剧本生成失败: " + r.getErrorMessage();
                }
                case "call_review_agent" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    long pid = args.has("projectId") ? args.get("projectId").asLong() : ensureProjectId(contextProjectId);
                    AgentResult r = reviewAgent.execute(pid);
                    yield r.isSuccess() ? summarize(r.getData(), 600) : "质量审核失败: " + r.getErrorMessage();
                }
                case "call_question_agent" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    long pid = args.has("projectId") ? args.get("projectId").asLong() : ensureProjectId(contextProjectId);
                    String q = args.get("question").asText();
                    String answer = questionAgent.ask(pid, q);
                    yield "用户回答: " + (answer != null ? answer : "(等待中...)");
                }
                // --- 文件/数据读取 ---
                case "read_requirement" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    long pid = args.has("projectId") ? args.get("projectId").asLong() : ensureProjectId(contextProjectId);
                    var req = requirementRepository.findByProjectId(pid).orElse(null);
                    if (req == null) yield "该项目还没有需求分析文档，请先运行需求调研。";
                    yield "## 需求分析\n" + req.getSummaryContent() +
                            "\n风格偏好: " + req.getStylePreference() +
                            "\n范围: " + req.getScopeLevel() +
                            "\n目标角色: " + req.getTargetCharacters();
                }
                case "read_outline" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    long pid = args.has("projectId") ? args.get("projectId").asLong() : ensureProjectId(contextProjectId);
                    var outlines = outlineRepository.findByProjectIdOrderByVersionNumberAsc(pid);
                    if (outlines.isEmpty()) yield "该项目还没有大纲方案，请先运行大纲设计。";
                    var sb = new StringBuilder();
                    for (var o : outlines) {
                        sb.append("## 版本 ").append(o.getVersionNumber())
                                .append(o.getSelected() == Boolean.TRUE ? " [已选中]" : "").append("\n");
                        sb.append("标题: ").append(o.getTitle()).append("\n");
                        sb.append("摘要: ").append(o.getSummary()).append("\n");
                        if (o.getCoreConflict() != null)
                            sb.append("核心冲突: ").append(o.getCoreConflict()).append("\n");
                        if (o.getChapters() != null)
                            sb.append("分章: ").append(o.getChapters()).append("\n");
                        sb.append("\n");
                    }
                    yield sb.toString();
                }
                case "read_script" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    long pid = args.has("projectId") ? args.get("projectId").asLong() : ensureProjectId(contextProjectId);
                    var scripts = scriptRepository.findByProjectIdOrderByCreatedAtDesc(pid);
                    if (scripts.isEmpty()) yield "该项目还没有剧本，请先运行剧本生成。";
                    var s = scripts.get(0);
                    var chapters = scriptChapterRepository.findByScriptIdOrderByChapterNumberAsc(s.getId());
                    var sb = new StringBuilder();
                    sb.append("## ").append(s.getTitle())
                            .append(" (").append(chapters.size()).append("章)\n");
                    sb.append("风格: ").append(s.getWritingStyle())
                            .append(" | 状态: ").append(s.getStatus()).append("\n\n");
                    for (var ch : chapters) {
                        sb.append("### 第").append(ch.getChapterNumber()).append("章 ").append(ch.getTitle()).append("\n");
                        sb.append(ch.getRawContent()).append("\n\n---\n\n");
                    }
                    yield summarize(sb.toString(), 8000);
                }
                case "read_review" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    long pid = args.has("projectId") ? args.get("projectId").asLong() : ensureProjectId(contextProjectId);
                    var reviews = reviewReportRepository.findByProjectIdOrderByCreatedAtDesc(pid);
                    if (reviews.isEmpty()) yield "该项目还没有审核报告，请先运行质量审核。";
                    var r = reviews.get(0);
                    yield "## 审核报告\n总分: " + r.getOverallScore() +
                            "\n状态: " + r.getStatus() +
                            "\n\n### OOC 问题\n" + (r.getOocIssues() != null ? r.getOocIssues() : "无") +
                            "\n\n### 逻辑问题\n" + (r.getLogicIssues() != null ? r.getLogicIssues() : "无") +
                            "\n\n### 节奏分析\n" + (r.getPacingAnalysis() != null ? r.getPacingAnalysis() : "无");
                }
                case "read_search_results" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    long pid = args.has("projectId") ? args.get("projectId").asLong() : ensureProjectId(contextProjectId);
                    String search = searchResultStore.getSearchResult(pid);
                    String character = searchResultStore.getCharacterResult(pid);
                    if ((search == null || search.isEmpty()) && (character == null || character.isEmpty()))
                        yield "该项目还没有搜索结果，请先运行搜索 Agent。";
                    var sb = new StringBuilder();
                    if (search != null && !search.isEmpty())
                        sb.append("## 搜索结果\n").append(summarize(search, 2000)).append("\n\n");
                    if (character != null && !character.isEmpty())
                        sb.append("## 角色分析\n").append(summarize(character, 2000));
                    yield sb.toString();
                }
                case "list_knowledge" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    String game = args.get("gameName").asText();
                    var entries = knowledgeEntryRepository.findByGameName(game);
                    if (entries.isEmpty()) yield "游戏 '" + game + "' 的知识库中没有条目。";
                    var sb = new StringBuilder();
                    sb.append("## ").append(game).append(" 知识库 (").append(entries.size()).append("条)\n");
                    for (var e : entries) {
                        sb.append("- [").append(e.getEntryType()).append("] ").append(e.getTitle()).append("\n");
                    }
                    yield sb.toString();
                }
                case "list_character_cards" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    String game = args.get("gameName").asText();
                    var cards = characterCardRepository.findByGameName(game);
                    if (cards.isEmpty()) yield "游戏 '" + game + "' 没有角色卡。";
                    var sb = new StringBuilder();
                    sb.append("## ").append(game).append(" 角色卡 (").append(cards.size()).append("个)\n");
                    for (var c : cards) {
                        sb.append("- ").append(c.getName()).append(" (ID:").append(c.getId()).append(")\n");
                    }
                    yield sb.toString();
                }
                // --- 文件操作 ---
                case "write_file" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    long pid = args.get("projectId").asLong();
                    String filePath = args.get("filePath").asText();
                    String content = args.get("content").asText();
                    Path targetPath = resolveProjectPath(pid, filePath);
                    Files.createDirectories(targetPath.getParent());
                    Files.writeString(targetPath, content);
                    // 通知前端导航到该文件
                    sendNavigateEvent(pid, filePath, targetPath.getFileName().toString());
                    yield "文件已写入: " + filePath + " (" + content.length() + " 字符)";
                }
                case "replace_in_file" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    long pid = args.get("projectId").asLong();
                    String filePath = args.get("filePath").asText();
                    String oldStr = args.get("old_str").asText();
                    String newStr = args.get("new_str").asText();
                    Path targetPath = resolveProjectPath(pid, filePath);
                    if (!Files.exists(targetPath)) {
                        yield "替换失败: 文件不存在 " + filePath;
                    }
                    String fileContent = Files.readString(targetPath);
                    if (!fileContent.contains(oldStr)) {
                        yield "替换失败: 未找到匹配的原文内容 in " + filePath;
                    }
                    String updated = fileContent.replace(oldStr, newStr);
                    Files.writeString(targetPath, updated);
                    // 通知前端导航到该文件
                    sendNavigateEvent(pid, filePath, targetPath.getFileName().toString());
                    yield "文件已更新: " + filePath + " (替换成功)";
                }
                case "read_file" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    long pid = args.get("projectId").asLong();
                    String filePath = args.get("filePath").asText();
                    Path targetPath = resolveProjectPath(pid, filePath);
                    if (!Files.exists(targetPath)) {
                        yield "文件不存在: " + filePath;
                    }
                    String fileContent = Files.readString(targetPath);
                    yield "## " + filePath + "\n```\n" + summarize(fileContent, 4000) + "\n```";
                }
                // --- 终端/搜索工具 ---
                case "execute_command" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    long pid = args.get("projectId").asLong();
                    String command = args.get("command").asText();
                    yield executeShellCommand(pid, command);
                }
                case "search_files" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    long pid = args.get("projectId").asLong();
                    String pattern = args.get("pattern").asText();
                    String fileGlob = args.has("filePattern") ? args.get("filePattern").asText() : "*.*";
                    yield searchFileContents(pid, pattern, fileGlob);
                }
                case "list_files" -> {
                    var args = MAPPER.readTree(tc.arguments());
                    long pid = args.get("projectId").asLong();
                    String dir = args.has("dir") ? args.get("dir").asText() : "";
                    yield listProjectFiles(pid, dir);
                }
                default -> "未知工具: " + tc.name();
            };
        } catch (BusinessException e) {
            return "操作失败: " + e.getMessage();
        } catch (Exception e) {
            log.error("Tool execution failed: {} - {}", tc.name(), e.getMessage(), e);
            return "工具执行异常: " + e.getMessage();
        }
    }

    private long ensureProjectId(Long contextProjectId) {
        if (contextProjectId != null) return contextProjectId;
        // fallback: 找第一个存在的项目
        var all = projectRepository.findAllByOrderByDisplayOrderAsc();
        if (!all.isEmpty()) return all.get(0).getId();
        throw new BusinessException(com.erchuang.scriptforge.infra.ErrorCode.RESOURCE_NOT_FOUND,
                "没有可用的项目，请先创建一个项目");
    }

    /** 解析项目工作空间中的文件路径. */
    private Path resolveProjectPath(Long projectId, String filePath) {
        // 防止路径穿越攻击
        String safe = filePath.replace('\\', '/').replaceAll("\\.\\./", "").replaceAll("^\\.\\.", "");
        return Paths.get(workspaceDir, "project-" + projectId, safe).normalize();
    }

    /** 通知前端导航到指定文件（编辑器打开 + 文件树高亮）. */
    private void sendNavigateEvent(Long projectId, String filePath, String displayName) {
        if (projectId == null) return;
        try {
            var payload = Map.of("type", "navigate", "content",
                    MAPPER.writeValueAsString(Map.of("filePath", filePath, "displayName", displayName)));
            sseEmitterService.sendEvent(projectId, "chat", MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("Failed to send navigate event: {}", e.getMessage());
        }
    }

    /** 在项目工作空间中执行终端命令. */
    private String executeShellCommand(Long projectId, String command) {
        Path workDir = Paths.get(workspaceDir, "project-" + projectId).normalize();
        try {
            if (!Files.exists(workDir)) {
                Files.createDirectories(workDir);
            }
            ProcessBuilder pb;
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", command);
            } else {
                pb = new ProcessBuilder("sh", "-c", command);
            }
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "命令执行超时（30秒）: " + command;
            }
            var output = new StringBuilder();
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < 2000) {
                        output.append(line).append("\n");
                    }
                }
            }
            int exitCode = process.exitValue();
            String result = output.toString().trim();
            if (result.isEmpty()) {
                return "命令执行完毕，无输出（exit code: " + exitCode + "）";
            }
            return "```\n" + result + "\n```\n(exit code: " + exitCode + ")";
        } catch (Exception e) {
            return "命令执行失败: " + e.getMessage();
        }
    }

    /** 搜索文件内容（类似 grep）. */
    private String searchFileContents(Long projectId, String pattern, String fileGlob) {
        Path root = Paths.get(workspaceDir, "project-" + projectId).normalize();
        if (!Files.exists(root)) {
            return "工作空间目录不存在，还没有任何文件。";
        }
        try {
            var sb = new StringBuilder();
            sb.append("搜索 \"").append(pattern).append("\" 结果:\n\n");
            int matchCount = 0;
            var files = Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .collect(Collectors.toList());
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                // 简单 glob 匹配
                if (!simpleGlobMatch(fileName, fileGlob)) continue;
                try {
                    var lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        if (line.toLowerCase().contains(pattern.toLowerCase())) {
                            String relPath = root.relativize(file).toString().replace('\\', '/');
                            sb.append(relPath).append(":").append(i + 1).append(": ")
                                    .append(line.length() > 120 ? line.substring(0, 120) + "..." : line)
                                    .append("\n");
                            matchCount++;
                            if (matchCount >= 30) break;
                        }
                    }
                } catch (Exception ignored) { }
                if (matchCount >= 30) break;
            }
            if (matchCount == 0) {
                return "未找到匹配 \"" + pattern + "\" 的内容";
            }
            return sb.toString();
        } catch (Exception e) {
            return "搜索失败: " + e.getMessage();
        }
    }

    private boolean simpleGlobMatch(String fileName, String pattern) {
        if ("*.*".equals(pattern)) return true;
        String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return fileName.matches(regex);
    }

    /** 列出项目工作空间中的文件和目录. */
    private String listProjectFiles(Long projectId, String subDir) {
        Path root = Paths.get(workspaceDir, "project-" + projectId).normalize();
        if (subDir != null && !subDir.isEmpty()) {
            root = root.resolve(subDir.replace('\\', '/').replaceAll("\\.\\./", "")).normalize();
        }
        if (!Files.exists(root)) {
            return "目录不存在: " + (subDir.isEmpty() ? "(工作空间根目录)" : subDir);
        }
        try {
            var sb = new StringBuilder();
            sb.append("文件列表 ").append(subDir.isEmpty() ? "(根目录)" : subDir).append(":\n\n");
            var children = Files.list(root)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted((a, b) -> {
                        try {
                            boolean aDir = Files.isDirectory(a), bDir = Files.isDirectory(b);
                            if (aDir && !bDir) return -1;
                            if (!aDir && bDir) return 1;
                            return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
                        } catch (Exception e) { return 0; }
                    })
                    .collect(Collectors.toList());
            if (children.isEmpty()) {
                return "目录为空";
            }
            for (Path child : children) {
                try {
                    String name = child.getFileName().toString();
                    if (Files.isDirectory(child)) {
                        sb.append("[DIR]  ").append(name).append("/\n");
                    } else {
                        long size = Files.size(child);
                        sb.append("[FILE] ").append(name).append("  (").append(formatSize(size)).append(")\n");
                    }
                } catch (Exception e) {
                    sb.append("[???] ").append(child.getFileName()).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "列出文件失败: " + e.getMessage();
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** 截断过长文本. */
    private String summarize(String text, int maxLen) {
        if (text == null) return "(无内容)";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...\n(内容已截断，完整内容请在项目页面查看)";
    }

    // ==================== WebSocket 推送 + 持久化 ====================

    private void sendChatEvent(Long projectId, String type, String content) {
        if (projectId == null) return;
        try {
            var payload = Map.of("type", type, "content", content);
            sseEmitterService.sendEvent(projectId, "chat", MAPPER.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize chat event: {}", e.getMessage());
        }
        // 持久化关键事件（排除流式中间块，保存最终结果）
        switch (type) {
            case "tool_call", "tool_result" ->
                    saveChatMessage(projectId, "agent", content, type);
            case "done" ->
                    saveChatMessage(projectId, "agent", content, "done");
            case "error" ->
                    saveChatMessage(projectId, "agent", content, "error");
        }
    }

    /** 发送结构化工具事件（含 toolName / filePath / arguments）. */
    private void sendStructuredToolEvent(Long projectId, String type, ToolCallRequest tc) {
        if (projectId == null) return;
        try {
            var m = new HashMap<String, Object>();
            m.put("toolName", tc.name());
            // 提取 filePath（如果是文件操作工具）
            if ("write_file".equals(tc.name()) || "replace_in_file".equals(tc.name()) || "read_file".equals(tc.name())) {
                try {
                    var argsNode = MAPPER.readTree(tc.arguments());
                    if (argsNode.has("filePath")) {
                        m.put("filePath", argsNode.get("filePath").asText());
                    }
                } catch (Exception ignored) { }
            }
            m.put("arguments", tc.arguments());
            String json = MAPPER.writeValueAsString(m);
            var payload = Map.of("type", type, "content", json);
            sseEmitterService.sendEvent(projectId, "chat", MAPPER.writeValueAsString(payload));
            saveChatMessage(projectId, "agent", json, type);
        } catch (Exception e) {
            log.warn("Failed to serialize tool event: {}", e.getMessage());
            // fallback 到旧格式
            sendChatEvent(projectId, type, tc.name() + "|" + tc.arguments());
        }
    }

    /** 发送结构化工具结果事件（含 toolName / filePath / result）. */
    private void sendStructuredToolEvent(Long projectId, String type, ToolCallRequest tc, String result) {
        if (projectId == null) return;
        try {
            var m = new HashMap<String, Object>();
            m.put("toolName", tc.name());
            if ("write_file".equals(tc.name()) || "replace_in_file".equals(tc.name()) || "read_file".equals(tc.name())) {
                try {
                    var argsNode = MAPPER.readTree(tc.arguments());
                    if (argsNode.has("filePath")) {
                        m.put("filePath", argsNode.get("filePath").asText());
                    }
                } catch (Exception ignored) { }
            }
            m.put("result", result);
            String json = MAPPER.writeValueAsString(m);
            var payload = Map.of("type", type, "content", json);
            sseEmitterService.sendEvent(projectId, "chat", MAPPER.writeValueAsString(payload));
            saveChatMessage(projectId, "agent", json, type);
        } catch (Exception e) {
            log.warn("Failed to serialize tool result event: {}", e.getMessage());
            sendChatEvent(projectId, type, tc.name() + "|" + result);
        }
    }

    /**
     * 保存思考完成后的完整内容（由 streamThinking 完成时调用）.
     */
    private void saveThinkingToDb(Long projectId, String fullThinking) {
        saveChatMessage(projectId, "agent", fullThinking, "thinking");
    }

    /**
     * 保存最终流式回复.
     */
    private void saveReplyToDb(Long projectId, String fullReply) {
        saveChatMessage(projectId, "agent", fullReply, "reply");
    }

    private void saveChatMessage(Long projectId, String role, String content, String subType) {
        if (projectId == null || content == null || content.isBlank()) return;
        try {
            com.erchuang.scriptforge.model.entity.ChatMessage msg =
                    com.erchuang.scriptforge.model.entity.ChatMessage.builder()
                    .projectId(projectId)
                    .role(role)
                    .content(content.length() > 5000 ? content.substring(0, 5000) : content)
                    .subType(subType)
                    .sessionId(currentSessionId.get())
                    .build();
            chatMessageRepository.save(msg);
        } catch (Exception e) {
            log.warn("Failed to save chat message: {}", e.getMessage());
        }
    }
}
