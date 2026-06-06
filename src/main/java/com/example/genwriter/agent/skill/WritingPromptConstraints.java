package com.example.genwriter.agent.skill;

public final class WritingPromptConstraints {

    private WritingPromptConstraints() {
    }

    public static String systemConstraint(WritingGenreProfile profile) {
        if (profile == null || profile.genre() == WritingGenre.UNKNOWN) {
            return "";
        }
        if (profile.isNovel()) {
            return """

                    ## 文体硬约束：小说
                    用户正在请求小说或章节类创作时，最终内容必须写成直接叙事正文（direct narrative prose），
                    以场景推进、人物行动、冲突变化、感官细节和自然对话承载信息。不要把小说写成报告、
                    说明文、设定集、提纲、分析、提案或文章式分节。
                    """;
        }
        return "\n\n## 文体约束\n当前识别文体：" + profile.displayName() + "。请保持该文体的结构、语气和表达习惯。\n";
    }

    public static String outlineConstraint(WritingGenreProfile profile) {
        if (profile == null || !profile.isNovel()) {
            return "";
        }
        return """

                ## 小说章节规划约束
                用户请求小说/章节创作。大纲只作为后续正文的执行蓝图，不要按议论文、报告或说明文标题组织。
                请规划章节标题、场景推进、人物行动、核心冲突、对话节拍、情绪转折和连续性钩子。
                科幻或科技主题的技术细节必须服务于人物选择、冲突后果和剧情推进。
                """;
    }

    public static String draftConstraint(WritingGenreProfile profile) {
        if (profile == null || !profile.isNovel()) {
            return "";
        }
        return """

                ## 小说正文输出约束
                最终回答必须是直接叙事正文（direct narrative prose）。可以包含章节名，但正文必须主要由场景、
                人物行动、具体感官细节和自然对话构成。禁止输出大纲、报告、提案、分析、说明文、设定表、
                条目清单或文章式分节。科幻或科技主题要通过冲突、后果和人物选择呈现技术，不要列成解释清单。
                """;
    }

    public static String polishConstraint(WritingGenreProfile profile) {
        if (profile == null || !profile.isNovel()) {
            return "";
        }
        return """

                ## 小说润色约束
                保持小说叙事文体，只优化语言、节奏、画面感和对话自然度。不要把原文改写成摘要、报告、
                分析、设定说明或文章式分节。
                """;
    }

    public static String reviewConstraint(WritingGenreProfile profile, boolean markdownEnabled) {
        StringBuilder sb = new StringBuilder();
        if (profile != null && profile.isNovel()) {
            sb.append("""

                    ## 小说形态闸门
                    用户请求小说/章节正文。如果待评审内容读起来像大纲、报告、分析、评论、计划、设定集、
                    说明文或文章式分节，而不是叙事小说正文，verdict 必须设为 "REVISE_DRAFT"，并在 feedback
                    中明确要求重写为直接叙事正文。
                    """);
        }
        if (!markdownEnabled) {
            sb.append("""

                    ## 反馈格式约束
                    JSON 结构保持不变，但 feedback 字段中的文字不要使用 Markdown 标记、列表符号或代码块。
                    """);
        }
        return sb.toString();
    }

    public static String outputFormatConstraint(boolean markdownEnabled) {
        if (markdownEnabled) {
            return """

                    ## 输出格式约束
                    允许使用 Markdown 格式。只有在符合目标文体时才使用标题、列表、强调等格式；小说正文不要因为
                    Markdown 可用而变成提纲或报告。
                    """;
        }
        return """

                ## 输出格式硬约束
                只输出纯文本，不使用 Markdown 语法。不要使用 # 标题、-/*/1. 列表、```代码块、**加粗**、
                > 引用或表格。允许自然段、空行和普通中文章节名。
                """;
    }
}
