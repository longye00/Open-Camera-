/* SPDX-License-Identifier: GPL-3.0-or-later
 * Photo-name queue extension, 2026-09-10. Not an official Open Camera release.
 */
package net.sourceforge.opencamera;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
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

    public static final int MAX_SERIAL = 999999;

    private static String key(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    public static String numberedFilename(String name, int number) {
        if (number < 1 || number > MAX_SERIAL) throw new IllegalArgumentException("照片序号超出范围。");
        return name + "_" + String.format(Locale.ROOT, "%03d", number) + ".jpg";
    }

    /** Only recognizes this exact identifier's numbered JPGs, not another identifier's files. */
    public static int serialFromFilename(String name, String filename) {
        if (filename == null) return 0;
        String prefix = key(name) + "_";
        String value = key(filename);
        if (!value.startsWith(prefix) || !value.endsWith(".jpg")) return 0;
        String digits = value.substring(prefix.length(), value.length() - 4);
        if (!digits.matches("[0-9]{3,6}")) return 0;
        try { return Integer.parseInt(digits); }
        catch (NumberFormatException ignored) { return 0; }
    }

    /** Immutable per-identifier state. A save never changes the selected identifier by default. */
    public static final class State {
        public final List<String> names;
        public final int selected;
        public final boolean enabled, autoAdvance, pending;
        public final int pendingNumber;
        public final Map<String, Integer> lastNumbers, savedCounts;

        public State(List<String> names, int selected, boolean enabled, boolean autoAdvance,
                     Map<String, Integer> lastNumbers, Map<String, Integer> savedCounts,
                     int pendingNumber) {
            if (names == null || selected < 0 || (!names.isEmpty() && selected >= names.size())
                    || (names.isEmpty() && selected != 0) || pendingNumber < 0 || pendingNumber > MAX_SERIAL
                    || (names.isEmpty() && (enabled || pendingNumber != 0)))
                throw new IllegalArgumentException("Invalid identifier state");
            this.names = Collections.unmodifiableList(new ArrayList<>(names));
            this.selected = selected;
            this.enabled = enabled;
            this.autoAdvance = autoAdvance;
            this.lastNumbers = immutableNumbers(lastNumbers);
            this.savedCounts = immutableNumbers(savedCounts);
            this.pendingNumber = pendingNumber;
            this.pending = pendingNumber > 0;
            if (pending && pendingNumber <= numberFor(currentName()))
                throw new IllegalArgumentException("Pending photo number was already used");
        }

        private static Map<String, Integer> immutableNumbers(Map<String, Integer> source) {
            Map<String, Integer> copy = new HashMap<>();
            if (source != null) for (Map.Entry<String, Integer> entry : source.entrySet()) {
                Integer n = entry.getValue();
                if (entry.getKey() == null || n == null || n < 0 || n > MAX_SERIAL)
                    throw new IllegalArgumentException("Invalid photo counter");
                copy.put(key(entry.getKey()), n);
            }
            return Collections.unmodifiableMap(copy);
        }

        public static State empty() {
            return new State(Collections.<String>emptyList(), 0, false, false, null, null, 0);
        }

        public String currentName() {
            if (names.isEmpty()) throw new IllegalStateException("请先导入拍摄编号。");
            return names.get(selected);
        }
        public int numberFor(String name) {
            Integer number = lastNumbers.get(key(name));
            return number == null ? 0 : number;
        }
        public int countFor(String name) {
            Integer number = savedCounts.get(key(name));
            return number == null ? 0 : number;
        }
        public int nextNumber() {
            int n = numberFor(currentName()) + 1;
            if (n > MAX_SERIAL) throw new IllegalStateException("当前编号的照片序号已用完，请选择其他编号。");
            return n;
        }
        public boolean ready() {
            return enabled && !pending && !names.isEmpty() && numberFor(currentName()) < MAX_SERIAL;
        }
        public String filename() {
            return numberedFilename(currentName(), pending ? pendingNumber : nextNumber());
        }
        private void mutable() {
            if (pending) throw new IllegalStateException("请先确认上一张照片的保存状态。");
        }
        public State begin() { return begin(nextNumber()); }
        public State begin(int number) {
            if (!ready()) throw new IllegalStateException(pending ? "上一张照片的保存状态待确认。" : "编号拍摄未启用或尚未导入名单。");
            if (number < nextNumber() || number > MAX_SERIAL) throw new IllegalArgumentException("照片序号不可回退。");
            return new State(names, selected, enabled, autoAdvance, lastNumbers, savedCounts, number);
        }
        public State saved() { return resolve(true); }
        public State resolve(boolean wasSaved) {
            if (!pending) throw new IllegalStateException("No pending photo transaction");
            Map<String, Integer> numbers = new HashMap<>(lastNumbers);
            Map<String, Integer> counts = new HashMap<>(savedCounts);
            numbers.put(key(currentName()), pendingNumber);
            if (wasSaved) counts.put(key(currentName()), countFor(currentName()) + 1);
            int target = selected;
            if (wasSaved && autoAdvance && selected + 1 < names.size()) target++;
            // An uncertain/failed attempt reserves its suffix but never consumes a success count.
            return new State(names, target, enabled, autoAdvance, numbers, counts, 0);
        }
        public State enable(boolean value) {
            mutable();
            if (value && names.isEmpty()) throw new IllegalStateException("请先导入编号。");
            return new State(names, selected, value, autoAdvance, lastNumbers, savedCounts, 0);
        }
        public State setAutoAdvance(boolean value) {
            mutable();
            return new State(names, selected, enabled, value, lastNumbers, savedCounts, 0);
        }
        public State select(int index) {
            mutable();
            if (index < 0 || index >= names.size()) throw new IllegalArgumentException("请选择有效编号。");
            return new State(names, index, enabled, autoAdvance, lastNumbers, savedCounts, 0);
        }
        public State withNames(List<String> replacement) {
            mutable();
            if (replacement == null || replacement.isEmpty()) throw new IllegalArgumentException("编号名单不能为空。");
            int target = 0;
            if (!names.isEmpty()) for (int i = 0; i < replacement.size(); i++) {
                if (key(replacement.get(i)).equals(key(currentName()))) { target = i; break; }
            }
            // History is retained even for removed identifiers, preventing suffix reuse on re-import.
            return new State(replacement, target, true, autoAdvance, lastNumbers, savedCounts, 0);
        }
        public String status() {
            if (names.isEmpty()) return "尚未导入拍摄编号";
            if (pending) return "待确认：" + filename();
            return (enabled ? "当前编号：" : "已暂停；当前编号：") + currentName()
                    + " · 本版已确认 " + countFor(currentName()) + " 张"
                    + " · " + (autoAdvance ? "自动切换" : "手动切换");
        }
    }
}
