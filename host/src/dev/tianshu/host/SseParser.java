package dev.tianshu.host;

/**
 * 最小 SSE 帧解析器（W3C EventSource 格式，够用即可）。
 *
 * 实测形状：
 *
 *     event: text_delta
 *     data: {"seq":16,"ts":...,"type":"text_delta","data":{"text":"2"},...}
 *     <空行>
 *
 * 三个必须钉住的点：
 *   1. **空行才派发** —— `event:` 与 `data:` 要攒到空行才成帧
 *   2. **`:` 开头是注释** —— 服务端周期性发 `: ping` 当心跳，当成事件解析就会炸
 *   3. **多行 data 用 \n 连接**；没有 `event:` 时事件名默认 `message`
 */
public final class SseParser {

    public interface Handler {
        void onEvent(String event, String data);
    }

    private String event;
    private final StringBuilder data = new StringBuilder();
    private boolean hasData;

    /** 喂一行（不含换行符）。 */
    public void feed(String line, Handler h) {
        if (line == null) return;
        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);

        if (line.isEmpty()) {
            dispatch(h);
            return;
        }
        if (line.charAt(0) == ':') return;                  // 注释行（: ping）

        int c = line.indexOf(':');
        String field;
        String value;
        if (c < 0) {
            field = line;
            value = "";
        } else {
            field = line.substring(0, c);
            value = line.substring(c + 1);
            if (value.startsWith(" ")) value = value.substring(1);   // 规范：冒号后可有一个空格
        }

        if (field.equals("event")) {
            event = value;
        } else if (field.equals("data")) {
            data.append(value).append('\n');
            hasData = true;
        }
        // id / retry 这里用不到
    }

    /** 流结束时把残留帧派出去（有的服务最后一帧不发空行）。 */
    public void flush(Handler h) {
        dispatch(h);
    }

    private void dispatch(Handler h) {
        if (!hasData) {
            event = null;                                   // 只有 event、没有 data —— 丢弃
            return;
        }
        String payload = data.toString();
        if (payload.endsWith("\n")) payload = payload.substring(0, payload.length() - 1);
        String name = event != null ? event : "message";

        event = null;
        data.setLength(0);
        hasData = false;

        h.onEvent(name, payload);
    }
}
