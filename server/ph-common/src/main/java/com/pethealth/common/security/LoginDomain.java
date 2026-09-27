package com.pethealth.common.security;

/**
 * 登录域。三个端各有独立登录域（CONTEXT.md「服务者后台与运营后台是两个独立登录域」），
 * Token 里带的就是这个值，校验时与接口路径前缀比对（ADR-0012）。
 */
public enum LoginDomain {

    APP("/api/v1/app/"),
    PROVIDER("/api/v1/provider/"),
    ADMIN("/api/v1/admin/");

    private final String pathPrefix;

    LoginDomain(String pathPrefix) {
        this.pathPrefix = pathPrefix;
    }

    public String pathPrefix() {
        return pathPrefix;
    }

    /** 按请求路径判断它属于哪个登录域；不属于任何端（如 open 回调、actuator）返回 null。 */
    public static LoginDomain ofPath(String path) {
        if (path == null) {
            return null;
        }
        for (LoginDomain domain : values()) {
            if (path.startsWith(domain.pathPrefix)) {
                return domain;
            }
        }
        return null;
    }
}
