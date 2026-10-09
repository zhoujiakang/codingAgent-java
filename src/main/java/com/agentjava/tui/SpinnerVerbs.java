package com.agentjava.tui;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * spinner 动词。
 *
 * 等待模型的时候显示"正在做什么"，每轮换一个词，
 * 比干巴巴一个 "Loading..." 有意思，也让界面显得是活的。
 */
public final class SpinnerVerbs {

    private static final List<String> VERBS = List.of(
            "Imagining", "Befuddled", "Cogitating", "Pondering", "Ruminating",
            "Musing", "Contemplating", "Deliberating", "Scheming", "Brewing",
            "Fermenting", "Concocting"
    );

    /** spinner 动画帧。 */
    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private SpinnerVerbs() {
    }

    /** 为本轮请求随机挑一个动词。 */
    public static String random() {
        return VERBS.get(ThreadLocalRandom.current().nextInt(VERBS.size()));
    }

    /** 按时间推进的动画帧，约每 100ms 走一格。 */
    public static String frame() {
        int index = (int) ((System.currentTimeMillis() / 100) % FRAMES.length);
        return FRAMES[index];
    }
}
