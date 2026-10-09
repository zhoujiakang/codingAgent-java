package com.agentjava.tui.tea;

/**
 * update() 的返回值：新状态 + 一条副作用指令。
 *
 * 这是 Elm 架构（也是 Bubble Tea）的标准形状——
 * 状态归状态，副作用归副作用，两边不混在一起。
 */
public record UpdateResult<M extends Model>(M model, Command command) {

    public static <M extends Model> UpdateResult<M> of(M model) {
        return new UpdateResult<>(model, Command.none());
    }

    public static <M extends Model> UpdateResult<M> of(M model, Command command) {
        return new UpdateResult<>(model, command == null ? Command.none() : command);
    }
}
