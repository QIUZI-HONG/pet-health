package com.pethealth.content.domain;

import com.pethealth.common.error.BusinessException;

/**
 * 三类社区内容：经验卡片 / 提问 / 回答。
 *
 * <p>为什么是一个 enum 而不是三个各自处理：**审核队列与审核动作对三者是同构的**
 * （谁、什么时候、发了什么、现在什么状态），所以它们共用一套状态（{@link ContentStatus}）、
 * 一个队列接口、一对处置动作，只在「落到哪张表」这一步分叉。
 *
 * <p>码值与 contract 的两处对齐：admin.yaml 的 {@code ContentReviewItemView.content_type}
 * 与路径参数 {@code {content_type}}。
 */
public enum ContentType {

    CARD(1, "经验卡片"),
    QUESTION(2, "提问"),
    ANSWER(3, "回答");

    private final int code;
    private final String label;

    ContentType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 解析路径参数里的内容类型；不认识的值按**参数错误**拒绝（40001），不静默当作某一种。 */
    public static ContentType of(int code) {
        for (ContentType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw BusinessException.paramInvalid("内容类型只能是 1 卡片 / 2 提问 / 3 回答");
    }

    public static String name(Integer code) {
        if (code == null) {
            return null;
        }
        for (ContentType type : values()) {
            if (type.code == code) {
                return type.label;
            }
        }
        return null;
    }
}
