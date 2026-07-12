package com.erchuang.scriptforge.agent.review;

import com.erchuang.scriptforge.llm.DeepSeekClient;
import com.erchuang.scriptforge.llm.PromptTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 角色OOC检测器——比对剧本中角色行为与官方人设的一致性.
 *
 * @author ScriptForge Team
 */
public class OOCDetector {

    private static final Logger log = LoggerFactory.getLogger(OOCDetector.class);

    private final DeepSeekClient deepSeekClient;
    private final PromptTemplate promptTemplate;

    public OOCDetector(DeepSeekClient deepSeekClient, PromptTemplate promptTemplate) {
        this.deepSeekClient = deepSeekClient;
        this.promptTemplate = promptTemplate;
    }

    /**
     * 检测剧本中的OOC（角色偏离）问题.
     *
     * @param scriptContent  剧本内容
     * @param characterCards 角色人设信息
     * @return OOC检测结果
     */
    public String detect(String scriptContent, String characterCards) {
        try {
            String systemPrompt = promptTemplate.load("review-system");

            String userPrompt = "直接对以下剧本进行OOC（角色偏离）检测：\n\n" +
                    "## 角色人设\n" + characterCards + "\n\n" +
                    "## 剧本内容\n" + scriptContent + "\n\n" +
                    "直接以JSON格式列出所有OOC问题，禁止任何引导语。每个问题包含：角色名称、偏离描述、严重等级、位置定位、修改建议。";

            String result = deepSeekClient.chat(List.of(
                    DeepSeekClient.ChatMessage.system(systemPrompt),
                    DeepSeekClient.ChatMessage.user(userPrompt)
            ));

            return result;
        } catch (Exception e) {
            log.warn("OOC detection failed: {}", e.getMessage());
            return "OOC检测执行异常，请人工审核。";
        }
    }
}
