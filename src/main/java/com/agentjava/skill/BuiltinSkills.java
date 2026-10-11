package com.agentjava.skill;

import com.agentjava.skill.SkillCatalog.Skill;

import java.util.List;

/**
 * 内置 Skill 加载器（三级搜索路径里优先级最低的那一级）。
 *
 * <p>当前返回空列表：Skill 一律从用户目录或项目目录加载，不编译进程序。
 *
 * <p>为什么不内置几个 commit / review 之类开箱即用的 Skill：Skill 的核心卖点是
 * 「改一个 Markdown 文件就生效，不需要重新编译」，把它编进 jar 里等于把这个卖点
 * 废掉一半——用户想微调内置 Skill 的措辞，还得回来改代码重新发版。留出这一级
 * 是为了路径语义完整（内置 &lt; 用户 &lt; 项目），以及将来真要内置时不需要改结构。
 */
public final class BuiltinSkills {

    private BuiltinSkills() {
    }

    public static List<Skill> load() {
        return List.of();
    }
}
