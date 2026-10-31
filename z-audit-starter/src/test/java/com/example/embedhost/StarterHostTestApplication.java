package com.example.embedhost;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 模拟「只放 starter jar 的宿主」（M2 #23）。
 *
 * <p>包树 {@code com.example.embedhost} 与 starter 的装配类
 * （{@code com.zifang.z.audit.starter.config.*}）**毫无关系** ⇒
 * 组件扫描绝无可能扫到装配类，装配只能走 {@code AutoConfiguration.imports}。
 * 用 {@code com.example.*} 是**不给包贴近留后门**。
 *
 * @author zifang
 */
@SpringBootApplication
public class StarterHostTestApplication {
}
