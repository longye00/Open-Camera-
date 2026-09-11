/* SPDX-License-Identifier: GPL-3.0-or-later
 * Name-list photography for Open Camera 1.56.2.
 * Source-level patch; see README for build and device-test status.
 */
package net.sourceforge.opencamera;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import org.json.JSONObject;
import org.json.JSONException;
import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import java.util.ArrayList;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import net.sourceforge.opencamera.preview.ApplicationInterface;
import net.sourceforge.opencamera.PhotoNameQueueCore.State;

/** The active transaction is thread-local, so earlier background saves never consume this list. */
public final class PhotoNameQueue {
    private static final String PREFS = "photo_name_queue_v2";
    private static final ThreadLocal<Shot> ACTIVE = new ThreadLocal<>();
    private static volatile boolean busy;
    private static State cachedState;
    private static boolean storageFault;

    private PhotoNameQueue() {}

    public static final class Shot {
        final int index;
        final long captureTime;
        final String filename;
        File file;
        Uri uri;
        Shot(State state, Date time) {
            index = state.selected;
            captureTime = time.getTime();
            filename = state.filename();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Map<String, Integer> readNumbers(String value) {
        try {
            JSONObject json = new JSONObject(value);
            Map<String, Integer> values = new HashMap<>();
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) { String key = keys.next(); values.put(key, json.getInt(key)); }
            return values;
        } catch (JSONException e) { throw new IllegalStateException("编号计数记录损坏，请勿清除数据。", e); }
    }

    private static synchronized State read(Context context) {
        if (cachedState != null) return cachedState;
        SharedPreferences p = prefs(context);
        String text = p.getString("names", "");
        List<String> names = text.isEmpty() ? Collections.<String>emptyList() : PhotoNameQueueCore.parse(text);
        cachedState = new State(names, p.getInt("selected", 0), p.getBoolean("enabled", false),
                p.getBoolean("auto_advance", false), readNumbers(p.getString("numbers", "{}")),
                readNumbers(p.getString("counts", "{}")), p.getInt("pending_number", 0));
        return cachedState;
    }

    /** Commit to disk before publishing a new counter or enabling another shutter press. */
    private static synchronized void write(Context context, State state) throws IOException {
        if (storageFault) throw new IOException("进度存储异常，请退出应用后检查存储空间。");
        boolean ok = prefs(context).edit()
                .putString("names", PhotoNameQueueCore.join(state.names))
                .putInt("selected", state.selected).putBoolean("enabled", state.enabled)
                .putBoolean("auto_advance", state.autoAdvance)
                .putString("numbers", new JSONObject(state.lastNumbers).toString())
                .putString("counts", new JSONObject(state.savedCounts).toString())
                .putInt("pending_number", state.pendingNumber).putInt("schema", 2).commit();
        if (!ok) {
            storageFault = true;
            throw new IOException("无法保存编号进度，已停止拍照。请检查存储空间，不要清除数据。");
        }
        cachedState = state;
    }

    public static boolean enabledForCamera(Context context) {
        if (!prefs(context).getBoolean("enabled", false)) return false;
        if (context instanceof MainActivity) {
            MainActivity a = (MainActivity) context;
            String action = a.getIntent() == null ? null : a.getIntent().getAction();
            if (MediaStore.ACTION_IMAGE_CAPTURE.equals(action)
                    || "android.media.action.IMAGE_CAPTURE_SECURE".equals(action)) return false;
            if (a.getPreview() != null && a.getPreview().isVideo()) return false;
        }
        return true;
    }

    public static boolean hasActiveShot() { return ACTIVE.get() != null; }

    public static synchronized boolean queueReadyForCapture(Context context) {
        if (!enabledForCamera(context)) return true;
        try { return !busy && !storageFault && read(context).ready(); }
        catch (RuntimeException e) { return false; }
    }

    private static String unsupportedMode(MainActivity activity) {
        MyApplicationInterface api = activity.getApplicationInterface();
        if (api.getPhotoMode() != MyApplicationInterface.PhotoMode.Standard)
            return "编号拍摄请使用普通拍照模式；暂不支持 HDR、DRO、全景、包围曝光或连拍。";
        if (api.getRawPref() != ApplicationInterface.RawPref.RAWPREF_JPEG_ONLY)
            return "编号拍摄请关闭 RAW，仅保存 JPG。";
        if (!"1".equals(api.getRepeatPref()))
            return "编号拍摄请关闭系统重复拍摄；可多次单击快门为同一编号拍摄。";
        if (!api.nameQueueUsesJpegFormat())
            return "编号拍摄请将图像格式设为 JPG。";
        if (api.getStorageUtils().isUsingSAF())
            return "编号拍摄仅支持普通保存目录；请关闭“存储访问框架（SAF）”。";
        return null;
    }

    public static synchronized boolean allowShutter(MainActivity activity, boolean specialCapture) {
        if (!enabledForCamera(activity)) return true;
        if (specialCapture) {
            message(activity, "编号拍摄不支持长按连拍；请单击快门。");
            return false;
        }
        String unsupported = unsupportedMode(activity);
        if (unsupported != null) { message(activity, unsupported); return false; }
        if (busy) { message(activity, "正在保存上一张，请稍候。"); return false; }
        try {
            State state = read(activity);
            if (storageFault || !state.ready()) {
                message(activity, storageFault ? "进度存储异常，已停止拍照。" : state.status() + "；请打开设置中的“编号拍摄 / 名称列表”。");
                return false;
            }
            candidateFor(activity, state);
            return true;
        } catch (IOException | RuntimeException e) {
            message(activity, detail(e));
            return false;
        }
    }

    /** Called only for the ordinary single-JPEG callback, immediately before synchronous saving. */
    public static synchronized Shot begin(MainActivity activity, Date date) throws IOException {
        if (busy || storageFault || ACTIVE.get() != null) throw new IOException("上一张还未保存完成。");
        if (date == null) throw new IOException("没有有效的拍摄时间，已停止名单拍照。");
        String unsupported = unsupportedMode(activity);
        if (unsupported != null) throw new IOException(unsupported);
        State state = read(activity);
        if (!state.ready()) throw new IOException(state.status());
        State pending = state.begin(candidateFor(activity, state));
        Shot shot = new Shot(pending, date);
        write(activity, pending);
        ACTIVE.set(shot);
        busy = true;
        return shot;
    }

    /** null means use the original timestamp filename; this method never advances the queue. */
    public static String filename(int type, String suffix, int count, String extension, Date date) {
        Shot shot = ACTIVE.get();
        if (shot == null || type != StorageUtils.MEDIA_TYPE_IMAGE) return null;
        if (date == null || date.getTime() != shot.captureTime)
            throw new IllegalStateException("照片保存事务不匹配，已停止，进度未推进。");
        String ext = extension == null ? "" : extension.replaceFirst("^\\.", "").toLowerCase(Locale.ROOT);
        if (!(ext.equals("jpg") || ext.equals("jpeg")) || (suffix != null && !suffix.isEmpty()))
            throw new IllegalStateException("本次输出不是普通单张 JPG；进度未推进。");
        if (count > 0)
            throw new IllegalStateException("同名文件已存在：" + shot.filename + "；不会覆盖，也不会自动改成其他名称。");
        return shot.filename;
    }

    /** The File-API path must be exclusively reserved, never opened over another existing file. */
    public static File reserveFile(File file, int type) throws IOException {
        Shot shot = ACTIVE.get();
        if (shot != null && type == StorageUtils.MEDIA_TYPE_IMAGE) {
            if (!file.getName().equals(shot.filename)) throw new IOException("输出文件名不匹配。");
            if (!file.createNewFile()) throw new IOException("同名文件已存在：" + shot.filename);
            shot.file = file;
        }
        return file;
    }

    /** MediaStore's actual display name is checked when available. */
    public static void recordSavedUri(Uri uri) {
        Shot shot = ACTIVE.get();
        if (shot != null && uri != null) shot.uri = uri;
    }

    public static synchronized void finish(MainActivity activity, Shot shot, boolean saved) {
        if (shot == null) return;
        try {
            if (ACTIVE.get() != shot) throw new IOException("保存事务不匹配，需人工确认进度。");
            State state = read(activity);
            if (state.selected != shot.index || !state.pending || !state.filename().equals(shot.filename)) throw new IOException("名单进度不匹配，需人工确认。");
            if (!saved) {
                message(activity, "照片未确认保存成功，已拍计数未增加：" + shot.filename
                        + "。请到“编号拍摄 / 名称列表”核对后重试。");
                return;
            }
            if (shot.file != null && (!shot.file.isFile() || shot.file.length() == 0))
                throw new IOException("照片文件为空或丢失，进度未推进。");
            if (shot.uri != null) {
                String actual = displayName(activity, shot.uri);
                if (actual == null || !shot.filename.equals(actual))
                    throw new IOException("系统保存后的实际名称为“" + actual + "”，与名单不符；请检查相册后确认进度。");
            } else if (MainActivity.useScopedStorage()) {
                // No recorded URI: still require exactly one visible MediaStore row for this name.
                if (mediaStoreCount(activity, shot.filename) != 1)
                    throw new IOException("无法确认照片的最终名称；请检查相册后确认进度。");
            }
            State next = state.saved();
            write(activity, next);
            message(activity, "已保存：" + shot.filename);
        } catch (IOException | RuntimeException e) {
            message(activity, detail(e));
            // Keep the durable pending marker. The user must review rather than guessing.
        } finally {
            ACTIVE.remove();
            busy = false;
        }
    }

    private static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            return cursor != null && cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    private static int candidateFor(MainActivity activity, State state) throws IOException {
        int number = state.nextNumber();
        if (!exists(activity, PhotoNameQueueCore.numberedFilename(state.currentName(), number))) return number;
        number = Math.max(number, highestExistingSerial(activity, state.currentName()) + 1);
        if (number > PhotoNameQueueCore.MAX_SERIAL)
            throw new IOException("当前编号的照片序号已用完，请选择其他编号。");
        if (exists(activity, PhotoNameQueueCore.numberedFilename(state.currentName(), number)))
            throw new IOException("保存目录正在变化，请稍后重试；不会覆盖照片。");
        return number;
    }

    private static boolean exists(MainActivity activity, String filename) throws IOException {
        StorageUtils storage = activity.getApplicationInterface().getStorageUtils();
        if (storage.isUsingSAF()) throw new IOException("编号拍摄暂不支持 SAF 保存目录。");
        if (MainActivity.useScopedStorage() && Build.VERSION.SDK_INT >= 29)
            return mediaStoreCount(activity, filename) > 0;
        File folder = storage.getImageFolder();
        if (folder == null) throw new IOException("无法确定照片保存目录。");
        return new File(folder, filename).exists();
    }

    private static int highestExistingSerial(MainActivity activity, String name) throws IOException {
        StorageUtils storage = activity.getApplicationInterface().getStorageUtils();
        int highest = 0;
        if (MainActivity.useScopedStorage() && Build.VERSION.SDK_INT >= 29) {
            String relative = storage.getSaveRelativeFolder();
            if (relative == null || relative.isEmpty()) throw new IOException("无法确定照片保存目录。");
            if (!relative.endsWith("/")) relative += "/";
            try (Cursor cursor = activity.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media.DISPLAY_NAME},
                    MediaStore.Images.Media.RELATIVE_PATH + "=?", new String[]{relative}, null)) {
                if (cursor == null) throw new IOException("无法检查照片序号，已停止以免覆盖。");
                while (cursor.moveToNext()) highest = Math.max(highest,
                        PhotoNameQueueCore.serialFromFilename(name, cursor.getString(0)));
            } catch (SecurityException e) { throw new IOException("没有权限检查照片目录。", e); }
        } else {
            File folder = storage.getImageFolder();
            if (folder == null) throw new IOException("无法确定照片目录。");
            String[] files = folder.list();
            if (files == null) throw new IOException("无法读取照片目录。");
            for (String file : files) highest = Math.max(highest, PhotoNameQueueCore.serialFromFilename(name, file));
        }
        return highest;
    }

    private static int mediaStoreCount(MainActivity activity, String filename) throws IOException {
        StorageUtils storage = activity.getApplicationInterface().getStorageUtils();
        String relative = storage.getSaveRelativeFolder();
        if (relative == null || relative.isEmpty()) throw new IOException("无法确定 MediaStore 保存目录。");
        if (!relative.endsWith("/")) relative += "/";
        try (Cursor cursor = activity.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID},
                MediaStore.Images.Media.RELATIVE_PATH + "=? AND lower("
                        + MediaStore.Images.Media.DISPLAY_NAME + ")=?",
                new String[]{relative, filename.toLowerCase(Locale.ROOT)}, null)) {
            if (cursor == null) throw new IOException("系统未返回照片目录，不能安全检查重名。");
            return cursor.getCount();
        } catch (SecurityException e) {
            throw new IOException("没有权限检查照片目录，已停止名单拍照。", e);
        }
    }

    static State snapshot(Context context) { return read(context); }
    static boolean isBusy() { return busy; }
    static boolean isExternalCapture(MainActivity a) {
        String action = a.getIntent() == null ? null : a.getIntent().getAction();
        return MediaStore.ACTION_IMAGE_CAPTURE.equals(action)
                || "android.media.action.IMAGE_CAPTURE_SECURE".equals(action);
    }
    public static void onResume(MainActivity a) { PhotoNameQueueControls.attach(a); }
    public static void onPause() { PhotoNameQueueControls.detach(); }
    static void openSelection(MainActivity a) { showChooser(a); }
    static void openManager(MainActivity a) { showManager(a); }

    static synchronized void stepSelection(MainActivity a, int delta) {
        if (!editable(a)) return;
        try {
            State state = read(a);
            if (state.pending) { showRecovery(a, state); return; }
            if (state.names.isEmpty()) { showEditor(a); return; }
            int index = state.selected + delta;
            if (index < 0 || index >= state.names.size()) return;
            write(a, state.select(index).enable(true));
        } catch (IOException | RuntimeException e) { message(a, detail(e)); }
    }

    public static void addSettings(final PreferenceFragment fragment) {
        if (fragment.getPreferenceScreen().findPreference("photo_name_queue_entry") != null) return;
        final Preference preference = new Preference(fragment.getActivity());
        preference.setKey("photo_name_queue_entry");
        preference.setTitle("编号拍摄 / 名称列表");
        preference.setSummary("一个编号拍多张；可直接选编号，默认手动切换");
        preference.setOrder(-100);
        preference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override public boolean onPreferenceClick(Preference ignored) {
                showManager((MainActivity) fragment.getActivity()); return true;
            }
        });
        fragment.getPreferenceScreen().addPreference(preference);
    }

    private static synchronized boolean editable(MainActivity a) {
        if (busy || (a.getPreview() != null && a.getPreview().isTakingPhotoOrOnTimer())) {
            message(a, "拍照或保存过程中不能切换编号，请稍候。"); return false;
        }
        if (storageFault) { message(a, "进度存储异常，请退出应用并检查存储空间；不要清除数据。"); return false; }
        return true;
    }

    private static void showManager(final MainActivity a) {
        if (!editable(a)) return;
        final State state;
        try { state = read(a); }
        catch (RuntimeException e) { message(a, "编号记录读取失败，请勿清除数据。"); return; }
        if (state.pending) { showRecovery(a, state); return; }
        final String[] actions = {"选择当前拍摄编号", "导入 / 编辑编号名单",
                state.enabled ? "暂停编号拍摄（保留记录）" : "启用编号拍摄",
                "拍完自动切换编号：" + (state.autoAdvance ? "开" : "关（默认）"), "查看编号记录"};
        new AlertDialog.Builder(a).setTitle("编号拍摄")
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) showChooser(a);
                        else if (which == 1) showEditor(a);
                        else if (which == 2 || which == 3) {
                            try {
                                synchronized (PhotoNameQueue.class) {
                                    if (!editable(a)) return;
                                    State current = read(a);
                                    if (current.names.isEmpty()) { showEditor(a); return; }
                                    write(a, which == 2 ? current.enable(!current.enabled)
                                            : current.setAutoAdvance(!current.autoAdvance));
                                }
                                message(a, read(a).status());
                            } catch (IOException | RuntimeException e) { message(a, detail(e)); }
                        } else {
                            new AlertDialog.Builder(a).setTitle("编号拍摄记录")
                                    .setMessage(state.status() + "\n共 " + state.names.size() + " 个编号。"
                                            + "\n同一编号可多次单击快门，文件名为 编号_001.jpg、编号_002.jpg…"
                                            + "\n计数为本版确认保存的记录，不代表相册当前文件数。删除照片不会回退序号。"
                                            + "\n取景界面点中间编号直接选择；两侧箭头手动切换；长按编号打开管理。")
                                    .setPositiveButton("知道了", null).show();
                        }
                    }
                }).setNegativeButton("关闭", null).show();
    }

    private static void showEditor(final MainActivity a) {
        if (!editable(a)) return;
        final State state;
        try { state = read(a); } catch (RuntimeException e) { message(a, detail(e)); return; }
        if (state.pending) { showRecovery(a, state); return; }
        LinearLayout container = new LinearLayout(a);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(a, 16); container.setPadding(pad, pad, pad, pad);
        TextView tip = new TextView(a);
        tip.setText("一行一个编号，不要表头；支持从 Excel 复制一列粘贴。"
                + "\n每个编号可拍多张，自动加 _001、_002…；默认拍完不跳号。"
                + "\n仅支持普通 JPG 单击拍摄，不支持 RAW、HDR、长按连拍、系统重复拍摄和 SAF。");
        tip.setTextSize(14); container.addView(tip);
        final EditText input = new EditText(a);
        input.setTextSize(16); input.setMinLines(3); input.setMaxLines(7);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(PhotoNameQueueCore.MAX_TEXT_LENGTH)});
        input.setHint("材料A_CK_R1\n材料A_盐处理_R1\n材料B_CK_R1");
        input.setText(PhotoNameQueueCore.join(state.names)); container.addView(input);
        final AlertDialog dialog = new AlertDialog.Builder(a).setTitle("导入 / 编辑编号名单")
                .setView(container).setPositiveButton("保存并启用", null)
                .setNegativeButton("取消", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (!editable(a)) return;
                final List<String> names;
                try { names = PhotoNameQueueCore.parse(input.getText().toString()); }
                catch (IllegalArgumentException e) { input.setError(e.getMessage()); return; }
                State current = read(a);
                if (!current.names.isEmpty() && !current.names.equals(names)) {
                    new AlertDialog.Builder(a).setTitle("更新编号名单？")
                            .setMessage("保留同名编号的拍摄计数和已用序号，不删除照片。当前编号仍在新名单中时会继续选中它。")
                            .setPositiveButton("更新名单", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int which) { saveList(a, names, dialog); }
                            }).setNegativeButton("返回编辑", null).show();
                } else saveList(a, names, dialog);
            }
        });
    }

    private static synchronized void saveList(MainActivity a, List<String> names, AlertDialog dialog) {
        if (!editable(a)) return;
        try {
            write(a, read(a).withNames(names));
            dialog.dismiss(); message(a, "名单已保存。返回取景界面，点击编号可直接选择；拍完默认不跳号。");
        } catch (IOException | RuntimeException e) { message(a, detail(e)); }
    }

    private static void showChooser(final MainActivity a) {
        if (!editable(a)) return;
        final State state;
        try { state = read(a); } catch (RuntimeException e) { message(a, detail(e)); return; }
        if (state.pending) { showRecovery(a, state); return; }
        if (state.names.isEmpty()) { showEditor(a); return; }
        final Dialog dialog = new Dialog(a, android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout root = new LinearLayout(a); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(249, 250, 252));
        root.setPadding(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 8));
        TextView heading = new TextView(a); heading.setText("选择拍摄编号");
        heading.setTextSize(22); heading.setTextColor(Color.rgb(30, 35, 42));
        heading.setTypeface(null, Typeface.BOLD); root.addView(heading);
        TextView hint = new TextView(a);
        hint.setText(state.autoAdvance ? "已开启拍完自动切换；可在编号管理中关闭" : "拍完停留当前编号，想换哪个就点哪个");
        hint.setTextSize(14); hint.setTextColor(Color.rgb(94, 102, 113));
        hint.setPadding(0, dp(a, 4), 0, dp(a, 12)); root.addView(hint);
        final EditText search = new EditText(a);
        search.setSingleLine(true); search.setTextSize(16); search.setHint("搜索编号 / 名称");
        search.setTextColor(Color.rgb(30, 35, 42)); search.setHintTextColor(Color.rgb(111, 119, 130));
        search.setPadding(dp(a, 12), 0, dp(a, 12), 0);
        GradientDrawable searchBackground = new GradientDrawable(); searchBackground.setColor(Color.WHITE);
        searchBackground.setCornerRadius(dp(a, 8)); searchBackground.setStroke(dp(a, 1), Color.rgb(217, 222, 228));
        search.setBackground(searchBackground);
        root.addView(search, new LinearLayout.LayoutParams(-1, dp(a, 48)));
        final List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < state.names.size(); i++) indices.add(i);
        final ArrayAdapter<Integer> adapter = new ArrayAdapter<Integer>(a, android.R.layout.simple_list_item_1, indices) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                LinearLayout row;
                if (recycled instanceof LinearLayout) row = (LinearLayout) recycled;
                else {
                    row = new LinearLayout(a); row.setOrientation(LinearLayout.VERTICAL);
                    row.setPadding(dp(a, 12), dp(a, 12), dp(a, 12), dp(a, 12));
                    TextView name = new TextView(a); name.setTextSize(16); name.setTextColor(Color.rgb(30, 35, 42));
                    name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END); row.addView(name);
                    TextView count = new TextView(a); count.setTextSize(14); count.setTextColor(Color.rgb(90, 102, 115));
                    count.setPadding(0, dp(a, 4), 0, 0); row.addView(count);
                }
                int index = getItem(position); String name = state.names.get(index);
                ((TextView) row.getChildAt(0)).setText((index == state.selected ? "✓  " : "") + name);
                ((TextView) row.getChildAt(1)).setText("第 " + (index + 1) + " 项 · 本版已确认 " + state.countFor(name) + " 张");
                row.setBackgroundColor(index == state.selected ? Color.rgb(229, 240, 253) : Color.TRANSPARENT);
                return row;
            }
        };
        final ListView list = new ListView(a); list.setAdapter(adapter);
        list.setDividerHeight(dp(a, 1)); list.setPadding(0, dp(a, 8), 0, 0);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        final TextView empty = new TextView(a); empty.setText("没有匹配的编号");
        empty.setTextSize(16); empty.setTextColor(Color.rgb(90, 102, 115));
        empty.setPadding(dp(a, 12), dp(a, 16), dp(a, 12), dp(a, 16));
        empty.setVisibility(View.GONE); root.addView(empty);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim().toLowerCase(Locale.ROOT);
                indices.clear();
                for (int i = 0; i < state.names.size(); i++)
                    if (state.names.get(i).toLowerCase(Locale.ROOT).contains(query)
                            || Integer.toString(i + 1).equals(query)) indices.add(i);
                adapter.notifyDataSetChanged(); empty.setVisibility(indices.isEmpty() ? View.VISIBLE : View.GONE);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View row, int position, long id) {
                int index = indices.get(position);
                try {
                    synchronized (PhotoNameQueue.class) {
                        if (!editable(a)) return;
                        State current = read(a);
                        if (!current.names.equals(state.names)) throw new IllegalStateException("名单已改变，请重新选择。");
                        write(a, current.select(index).enable(true));
                    }
                    dialog.dismiss();
                } catch (IOException | RuntimeException e) { message(a, detail(e)); }
            }
        });
        LinearLayout footer = new LinearLayout(a);
        Button edit = new Button(dialog.getContext()); edit.setText("编辑名单");
        edit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); showEditor(a); }
        });
        Button close = new Button(dialog.getContext()); close.setText("返回相机");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        footer.addView(edit, new LinearLayout.LayoutParams(0, dp(a, 48), 1));
        footer.addView(close, new LinearLayout.LayoutParams(0, dp(a, 48), 1)); root.addView(footer);
        root.setFocusableInTouchMode(true); root.requestFocus();
        dialog.setContentView(root);
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, -1);
        list.setSelection(state.selected);
    }

    private static void showRecovery(final MainActivity a, State state) {
        if (busy) { message(a, "正在保存，请稍候。"); return; }
        new AlertDialog.Builder(a).setTitle("核对上一张照片")
                .setMessage("待确认：" + state.filename()
                        + "\n保存被中断或无法确认最终结果，未增加已拍数量。"
                        + "\n请先检查实际文件，再选择是否已保存。无论哪种结果，都不会重用本次后缀或删除照片。"
                        + "\n默认仍停留当前编号；只有开启自动切换且确认已保存时才切到下一编号。")
                .setPositiveButton("确认已保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { resolve(a, true); }
                }).setNeutralButton("确认未保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { resolve(a, false); }
                }).setNegativeButton("先去检查", null).show();
    }

    private static synchronized void resolve(MainActivity a, boolean wasSaved) {
        if (!editable(a)) return;
        try { write(a, read(a).resolve(wasSaved)); message(a, read(a).status()); }
        catch (IOException | RuntimeException e) { message(a, detail(e)); }
    }

    private static int dp(Context c, float value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }

    private static String detail(Exception exception) {
        String text = exception.getMessage();
        return text == null ? "操作失败，名单进度未推进。" : text;
    }

    public static void reportError(Context context, Exception exception) { message(context, detail(exception)); }

    private static void message(Context context, final String text) {
        final Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() { Toast.makeText(app, text, text.startsWith("已保存：") ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show(); }
        });
    }
}
