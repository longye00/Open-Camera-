/* SPDX-License-Identifier: GPL-3.0-or-later
 * Name-list photography for Open Camera 1.56.2.
 * Source-level patch; see README for build and device-test status.
 */
package net.sourceforge.opencamera;

import android.app.Activity;
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
    private static final String PREFS = "photo_name_queue_v1";
    private static final ThreadLocal<Shot> ACTIVE = new ThreadLocal<>();
    private static boolean busy;
    private static boolean storageFault;

    private PhotoNameQueue() {}

    public static final class Shot {
        final int index;
        final long captureTime;
        final String filename;
        File file;
        Uri uri;
        Shot(State state, Date time) {
            index = state.next;
            captureTime = time.getTime();
            filename = state.filename();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static State read(Context context) {
        SharedPreferences p = prefs(context);
        String text = p.getString("names", "");
        List<String> names = text.isEmpty() ? Collections.<String>emptyList() : PhotoNameQueueCore.parse(text);
        return new State(names, p.getInt("next", 0), p.getBoolean("enabled", false),
                p.getBoolean("pending", false));
    }

    /** Synchronous persistence: do not expose an advanced in-memory index before it is durable. */
    private static void write(Context context, State state) throws IOException {
        if (storageFault) throw new IOException("进度存储异常，请退出应用后检查存储空间。");
        boolean ok = prefs(context).edit()
                .putString("names", PhotoNameQueueCore.join(state.names))
                .putInt("next", state.next)
                .putBoolean("enabled", state.enabled)
                .putBoolean("pending", state.pending)
                .commit();
        if (!ok) {
            storageFault = true;
            throw new IOException("无法保存名单进度；已停止名单拍照，请检查手机存储空间。");
        }
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
            return "名称队列请使用普通拍照模式；暂不支持 HDR、DRO、全景、包围曝光或连拍。";
        if (api.getRawPref() != ApplicationInterface.RawPref.RAWPREF_JPEG_ONLY)
            return "名称队列请关闭 RAW，仅保存 JPG。";
        if (!"1".equals(api.getRepeatPref()))
            return "名称队列请关闭重复拍摄，每次只拍一张。";
        if (!api.nameQueueUsesJpegFormat())
            return "名称队列请将图像格式设为 JPG。";
        if (api.getStorageUtils().isUsingSAF())
            return "此补丁首版仅支持普通保存目录；请关闭“存储访问框架（SAF）”。";
        return null;
    }

    public static synchronized boolean allowShutter(MainActivity activity, boolean specialCapture) {
        if (!enabledForCamera(activity)) return true;
        if (specialCapture) {
            message(activity, "名称队列不支持长按连拍；请单击快门。");
            return false;
        }
        String unsupported = unsupportedMode(activity);
        if (unsupported != null) { message(activity, unsupported); return false; }
        if (busy) { message(activity, "正在保存上一张，请稍候。"); return false; }
        try {
            State state = read(activity);
            if (storageFault || !state.ready()) {
                message(activity, storageFault ? "进度存储异常，已停止拍照。" : state.status() + "；请打开设置中的“照片名称队列”。");
                return false;
            }
            ensureAvailable(activity, state.filename());
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
        ensureAvailable(activity, state.filename());
        Shot shot = new Shot(state, date);
        write(activity, state.begin());
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
            if (state.next != shot.index || !state.pending) throw new IOException("名单进度不匹配，需人工确认。");
            if (!saved) {
                message(activity, "照片未确认保存成功，名称未消耗：" + shot.filename
                        + "。请到“照片名称队列”核对后重试。");
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
            message(activity, "已保存：" + shot.filename + "\n" + next.status());
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

    private static void ensureAvailable(MainActivity activity, String filename) throws IOException {
        StorageUtils storage = activity.getApplicationInterface().getStorageUtils();
        if (storage.isUsingSAF()) throw new IOException("名称队列暂不支持 SAF 保存目录。");
        if (MainActivity.useScopedStorage() && Build.VERSION.SDK_INT >= 29) {
            if (mediaStoreCount(activity, filename) > 0)
                throw new IOException("保存目录已有同名照片：" + filename + "。请更换目录或名单；不会覆盖。");
        } else {
            File folder = storage.getImageFolder();
            if (folder == null) throw new IOException("无法确定照片保存目录。");
            if (new File(folder, filename).exists())
                throw new IOException("保存目录已有同名照片：" + filename + "。请更换目录或名单；不会覆盖。");
        }
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

    public static void onResume(final MainActivity activity) {
        if (enabledForCamera(activity)) {
            try { message(activity, read(activity).status()); }
            catch (RuntimeException e) { message(activity, "名单数据损坏，请检查“照片名称队列”设置。"); }
        }
    }

    public static void addSettings(final PreferenceFragment fragment) {
        if (fragment.getPreferenceScreen().findPreference("photo_name_queue_entry") != null) return;
        final Preference preference = new Preference(fragment.getActivity());
        preference.setKey("photo_name_queue_entry");
        preference.setTitle("照片名称队列");
        preference.setSummary("粘贴 Excel 一列名称；每成功保存一张，使用下一个名称");
        preference.setOrder(-100);
        preference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override public boolean onPreferenceClick(Preference ignored) {
                showManager((MainActivity) fragment.getActivity());
                return true;
            }
        });
        fragment.getPreferenceScreen().addPreference(preference);
    }

    private static synchronized boolean editable(MainActivity a) {
        if (busy || (a.getPreview() != null && a.getPreview().isTakingPhotoOrOnTimer())) {
            message(a, "拍照或保存过程中不能修改名单。"); return false;
        }
        if (storageFault) { message(a, "进度存储异常，请先退出应用并检查存储空间。"); return false; }
        return true;
    }

    private static void showManager(final MainActivity a) {
        if (!editable(a)) return;
        final State state;
        try { state = read(a); }
        catch (RuntimeException e) { message(a, "名单数据异常，停止修改以免丢失原进度。"); return; }
        if (state.pending) { showRecovery(a, state); return; }
        final String[] actions = {"编辑 / 粘贴名称列表", state.enabled ? "暂停名单（保留进度）" : "启用名单（继续进度）",
                "指定下一张序号", "查看当前进度"};
        new AlertDialog.Builder(a).setTitle("照片名称队列")
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) showEditor(a);
                        else if (which == 1) {
                            if (state.names.isEmpty()) { showEditor(a); return; }
                            try {
                                synchronized (PhotoNameQueue.class) {
                                    if (!editable(a)) return;
                                    write(a, read(a).enable(!state.enabled));
                                }
                                message(a, read(a).status());
                            } catch (IOException | RuntimeException e) { message(a, detail(e)); }
                        } else if (which == 2) showSeek(a);
                        else new AlertDialog.Builder(a).setTitle("名单进度")
                                .setMessage(state.status() + "\n已确认保存：" + state.next + " 张\n共：" + state.names.size()
                                        + " 个名称\n重名时停止，不自动覆盖或添加后缀。")
                                .setPositiveButton("知道了", null).show();
                    }
                }).setNegativeButton("关闭", null).show();
    }

    private static void showEditor(final MainActivity a) {
        if (!editable(a)) return;
        State state = read(a);
        if (state.pending) { showRecovery(a, state); return; }
        LinearLayout container = new LinearLayout(a);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * a.getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, pad);
        TextView tip = new TextView(a);
        tip.setText("一行一个名称，不要表头。可从 Excel 复制一列粘贴；自动添加 .jpg。\n名单模式仅用于普通单张 JPG；请关闭 RAW、连拍、重复拍摄和 SAF。\n名单内容不变时保留进度；替换名单会另行确认。\n" + state.status());
        tip.setTextSize(14);
        container.addView(tip);
        final EditText input = new EditText(a);
        input.setTextSize(16);
        input.setMinLines(6);
        input.setMaxLines(10);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(PhotoNameQueueCore.MAX_TEXT_LENGTH)});
        input.setHint("材料A_CK_R1\n材料A_盐处理_R1\n材料B_CK_R1");
        input.setText(PhotoNameQueueCore.join(state.names));
        container.addView(input);
        final AlertDialog dialog = new AlertDialog.Builder(a).setTitle("粘贴照片名称列表")
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
                    new AlertDialog.Builder(a).setTitle("替换名单？")
                            .setMessage("新名单从第 1 项开始。原进度将被替换，但不会删除任何照片。")
                            .setPositiveButton("替换并从第1项开始", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface ignored, int which) {
                                    saveList(a, names, dialog);
                                }
                            }).setNegativeButton("返回编辑", null).show();
                } else saveList(a, names, dialog);
            }
        });
    }

    private static synchronized void saveList(MainActivity a, List<String> names, AlertDialog dialog) {
        if (!editable(a)) return;
        try {
            State old = read(a);
            if (old.pending) { message(a, "请先确认上一张保存状态。"); return; }
            int next = old.names.equals(names) ? old.next : 0;
            write(a, new State(names, next, true, false));
            dialog.dismiss();
            message(a, read(a).status() + "\n返回相机后，单击原来的快门即可。");
        } catch (IOException | RuntimeException e) { message(a, detail(e)); }
    }

    private static void showSeek(final MainActivity a) {
        final State state = read(a);
        if (state.names.isEmpty()) { showEditor(a); return; }
        if (state.pending) { showRecovery(a, state); return; }
        final EditText number = new EditText(a);
        number.setInputType(InputType.TYPE_CLASS_NUMBER);
        number.setText(String.valueOf(Math.min(state.next + 1, state.names.size())));
        final AlertDialog dialog = new AlertDialog.Builder(a).setTitle("指定下一张序号")
                .setMessage("输入 1～" + state.names.size() + "。只改变名单位置，不删除或覆盖已有照片。")
                .setView(number).setPositiveButton("确认", null).setNegativeButton("取消", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                try {
                    int index = Integer.parseInt(number.getText().toString().trim()) - 1;
                    if (index < 0 || index >= state.names.size()) throw new IllegalArgumentException("请输入有效序号。");
                    synchronized (PhotoNameQueue.class) {
                        if (!editable(a)) return;
                        write(a, read(a).seek(index));
                    }
                    dialog.dismiss(); message(a, read(a).status());
                } catch (IOException | RuntimeException e) { number.setError(detail(e)); }
            }
        });
    }

    private static void showRecovery(final MainActivity a, final State state) {
        new AlertDialog.Builder(a).setTitle("请核对上一张照片")
                .setMessage("待确认：" + state.filename()
                        + "\n上次保存失败、被中断，或无法确认最终名称。进度没有自动推进。"
                        + "\n请先检查相册/文件管理器，再选择："
                        + "\n• 已有完整照片：确认已保存并使用下一项。"
                        + "\n• 没有照片：确认未保存并重试。若残留同名空文件，请先手动删除或更换目录。")
                .setPositiveButton("已保存，下一项", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { resolve(a, true); }
                }).setNeutralButton("未保存，重试", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { resolve(a, false); }
                }).setNegativeButton("先去检查", null).show();
    }

    private static synchronized void resolve(MainActivity a, boolean wasSaved) {
        if (!editable(a)) return;
        try { write(a, read(a).resolve(wasSaved)); message(a, read(a).status()); }
        catch (IOException | RuntimeException e) { message(a, detail(e)); }
    }

    private static String detail(Exception exception) {
        String text = exception.getMessage();
        return text == null ? "操作失败，名单进度未推进。" : text;
    }

    public static void reportError(Context context, Exception exception) { message(context, detail(exception)); }

    private static void message(Context context, final String text) {
        final Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() { Toast.makeText(app, text, Toast.LENGTH_LONG).show(); }
        });
    }
}
