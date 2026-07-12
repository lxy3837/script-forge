package com.erchuang.scriptforge.agent.question;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 提问数据 — 类似 AskUserQuestion 的结构化提问.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuestionData {

    private String questionId;
    private String question;

    /** 可选的预定义选项（可为null，表示自由输入） */
    private List<QuestionOption> options;

    /** 是否允许多选 */
    private boolean multiSelect;

    public QuestionData() {}

    public QuestionData(String questionId, String question) {
        this.questionId = questionId;
        this.question = question;
    }

    public QuestionData(String questionId, String question, List<QuestionOption> options, boolean multiSelect) {
        this.questionId = questionId;
        this.question = question;
        this.options = options;
        this.multiSelect = multiSelect;
    }

    public String getQuestionId() { return questionId; }
    public void setQuestionId(String questionId) { this.questionId = questionId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public List<QuestionOption> getOptions() { return options; }
    public void setOptions(List<QuestionOption> options) { this.options = options; }
    public boolean isMultiSelect() { return multiSelect; }
    public void setMultiSelect(boolean multiSelect) { this.multiSelect = multiSelect; }

    /**
     * 单个选项.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QuestionOption {
        private String label;
        private String description;

        public QuestionOption() {}

        public QuestionOption(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public static QuestionOption of(String label) {
            return new QuestionOption(label, null);
        }
        public static QuestionOption of(String label, String desc) {
            return new QuestionOption(label, desc);
        }
    }
}
