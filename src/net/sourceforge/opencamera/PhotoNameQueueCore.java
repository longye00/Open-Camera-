/* SPDX-License-Identifier: GPL-3.0-or-later
 * Photo-name queue extension, 2026-09-10. Not an official Open Camera release.
 */
package net.sourceforge.opencamera;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Android-independent validation and transaction state. */
public final class PhotoNameQueueCore {
    private PhotoNameQueueCore() {}
    public static final int MAX_NAMES = 5000;
    public static final int MAX_NAME_BYTES = 180;
    public static final int MAX_TEXT_LENGTH = 300000;

    public static List<String> parse(String text) {
        if (text == null) throw new IllegalArgumentException("名称列表不能为空。");
        if (text.length() > MAX_TEXT_LENGTH)
            throw new IllegalArgumentException("名单过长，请分成多个批次。");
        if (text.startsWith("\ufeff")) text = text.substring(1);
        List<String> names = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        String[] lines = text.split("\\r\\n|\\n|\\r", -1);
        for (int i = 0; i < lines.length; i++) {
            String value = lines[i].trim();
            if (value.isEmpty()) continue;
            String where = "第 " + (i + 1) + " 行：";
            // Validate before trimming too: tabs usually mean multiple Excel columns.
            for (int j = 0; j < lines[i].length(); j++) {
                char c = lines[i].charAt(j);
                if (c < 32 || c == 127 || Character.getType(c) == Character.FORMAT)
                    throw new IllegalArgumentException(where + "包含制表符或不可见字符，请只粘贴一列名称。");
            }
            value = Normalizer.normalize(value, Normalizer.Form.NFC);
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".jpeg")) value = value.substring(0, value.length() - 5);
            else if (lower.endsWith(".jpg")) value = value.substring(0, value.length() - 4);
            if (value.isEmpty() || value.equals(".") || value.equals("..")
                    || value.startsWith(".") || value.endsWith(".") || value.endsWith(" "))
                throw new IllegalArgumentException(where + "文件名为空，或以不支持的点/空格开头或结尾。");
            for (char c : value.toCharArray()) {
                if ("/\\:*?\"<>|".indexOf(c) >= 0)
                    throw new IllegalArgumentException(where + "文件名不能含 / \\ : * ? \" < > |。");
            }
            String stem = value.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (stem.matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]"))
                throw new IllegalArgumentException(where + "这是系统保留的文件名。");
            if (value.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES)
                throw new IllegalArgumentException(where + "名称过长，请缩短到 180 个 UTF-8 字节以内。");
            if (!unique.add(value.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException(where + "名称重复：" + value);
            names.add(value);
            if (names.size() > MAX_NAMES)
                throw new IllegalArgumentException("每批最多 " + MAX_NAMES + " 个名称。");
        }
        if (names.isEmpty()) throw new IllegalArgumentException("请至少输入一个照片名称。");
        return Collections.unmodifiableList(names);
    }

    public static String join(List<String> names) {
        StringBuilder out = new StringBuilder();
        for (String name : names) {
            if (out.length() > 0) out.append('\n');
            out.append(name);
        }
        return out.toString();
    }

    /** pending survives process death: never silently guess whether a photo was saved. */
    public static final class State {
        public final List<String> names;
        public final int next;
        public final boolean enabled;
        public final boolean pending;
        public State(List<String> names, int next, boolean enabled, boolean pending) {
            if (names == null || next < 0 || next > names.size()
                    || (pending && next == names.size()))
                throw new IllegalArgumentException("Invalid queue state");
            this.names = Collections.unmodifiableList(new ArrayList<>(names));
            this.next = next;
            this.enabled = enabled;
            this.pending = pending;
        }
        public boolean ready() { return enabled && !pending && next < names.size(); }
        public String filename() {
            if (next >= names.size()) throw new IllegalStateException("名称列表已经拍完。");
            return names.get(next) + ".jpg";
        }
        public State begin() {
            if (!ready()) throw new IllegalStateException(pending
                    ? "上一张照片的保存状态待确认。" : "名单未启用或已经拍完。");
            return new State(names, next, enabled, true);
        }
        public State saved() {
            if (!pending) throw new IllegalStateException("No active photo transaction");
            return new State(names, next + 1, enabled, false);
        }
        public State resolve(boolean wasSaved) {
            if (!pending) throw new IllegalStateException("No pending photo to review");
            return new State(names, next + (wasSaved ? 1 : 0), enabled, false);
        }
        public State enable(boolean value) {
            return new State(names, next, value, pending);
        }
        public State seek(int index) {
            if (pending) throw new IllegalStateException("请先确认上一张照片是否保存成功。");
            return new State(names, index, enabled, false);
        }
        public String status() {
            if (pending) return "待确认：" + filename() + "（进度未推进）";
            if (next == names.size()) return "名单已完成：" + names.size() + " / " + names.size();
            return (enabled ? "下一张：" : "已暂停；下一张：") + filename()
                    + "（" + (next + 1) + " / " + names.size() + "）";
        }
    }
}
