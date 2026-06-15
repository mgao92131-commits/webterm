package com.webterm.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

final class TodoDialogHelper {
    private static final String PREF_NAME = "webterm_todo_prefs";
    private static final String KEY_PREFIX = "todo_text_";

    private TodoDialogHelper() {}

    static String getTodoText(Context context, String sessionId) {
        if (TextUtils.isEmpty(sessionId)) return "";
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_PREFIX + sessionId, "");
    }

    static void saveTodoText(Context context, String sessionId, String text) {
        if (TextUtils.isEmpty(sessionId)) return;
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_PREFIX + sessionId, text).apply();
    }

    static void clearTodo(Context context, String sessionId) {
        if (TextUtils.isEmpty(sessionId)) return;
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(KEY_PREFIX + sessionId).apply();
    }

    static void show(Activity activity, String sessionId) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(UIUtils.dp(activity, 20), UIUtils.dp(activity, 20), UIUtils.dp(activity, 20), UIUtils.dp(activity, 20));

        android.graphics.drawable.GradientDrawable containerBg = new android.graphics.drawable.GradientDrawable();
        containerBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        containerBg.setColor(Color.rgb(30, 30, 36));
        containerBg.setCornerRadius(UIUtils.dp(activity, 12));
        containerBg.setStroke(UIUtils.dp(activity, 1), Color.rgb(55, 65, 81));
        container.setBackground(containerBg);

        // 创建常驻的物理隐藏焦点锚点 (Focus Anchor)，防止重绘失焦时软键盘下落
        final EditText focusAnchor = new EditText(activity);
        focusAnchor.setTag("focus_anchor");
        focusAnchor.setBackground(null);
        focusAnchor.setPadding(0, 0, 0, 0);
        focusAnchor.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        focusAnchor.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        // 防无障碍干扰 TalkBack 朗读
        focusAnchor.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // 防长按拉起系统复制粘贴浮动气泡菜单
        focusAnchor.setCustomSelectionActionModeCallback(new android.view.ActionMode.Callback() {
            @Override public boolean onCreateActionMode(android.view.ActionMode mode, android.view.Menu menu) { return false; }
            @Override public boolean onPrepareActionMode(android.view.ActionMode mode, android.view.Menu menu) { return false; }
            @Override public boolean onActionItemClicked(android.view.ActionMode mode, android.view.MenuItem item) { return false; }
            @Override public void onDestroyActionMode(android.view.ActionMode mode) {}
        });
        // 添加到 container 最上方，长宽设为 1px 隐藏
        container.addView(focusAnchor, 0, new LinearLayout.LayoutParams(1, 1));

        // 顶部 Header: 标题 + 右上角关闭按钮 ✕
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, UIUtils.dp(activity, 12));

        TextView titleView = new TextView(activity);
        titleView.setText("📋 会话任务清单");
        titleView.setTextColor(Color.rgb(243, 244, 246));
        titleView.setTextSize(17);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);

        TextView closeBtn = new TextView(activity);
        closeBtn.setText("✕");
        closeBtn.setTextColor(Color.rgb(156, 163, 175));
        closeBtn.setTextSize(18);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding(UIUtils.dp(activity, 8), UIUtils.dp(activity, 8), UIUtils.dp(activity, 8), UIUtils.dp(activity, 8));

        header.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(closeBtn, new LinearLayout.LayoutParams(-2, -2));
        container.addView(header);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setClipToPadding(false);
        scrollView.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        
        LinearLayout listLayout = new LinearLayout(activity);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(listLayout, new ViewGroup.LayoutParams(-1, -2));
        
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollView.setLayoutParams(scrollLp);
        container.addView(scrollView);

        builder.setView(container);
        final AlertDialog dialog = builder.create();
        dialog.show();
        
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            // 确保窗口处于始终显示输入法模式
            dialog.getWindow().clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | 
                android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            );
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        // 弹窗关闭时，统一将内存中的状态一次性写入 SharedPreferences 磁盘中
        dialog.setOnDismissListener(d -> {
            saveCurrentState(activity, sessionId, listLayout);
        });

        closeBtn.setOnClickListener(v -> dialog.dismiss());

        // 初始化渲染任务
        renderList(activity, sessionId, listLayout, focusAnchor);

        // 自动聚焦第一个输入框并拉起键盘
        focusFirstInput(activity, listLayout);
    }

    private static void renderList(Activity activity, String sessionId, LinearLayout listLayout, EditText focusAnchor) {
        // 在清空所有 View 之前，如果当前弹窗内有聚焦的 EditText，先转移到隐藏的 focusAnchor 挂起
        if (focusAnchor != null && listLayout.findFocus() != null) {
            focusAnchor.requestFocus();
        }

        listLayout.removeAllViews();
        String rawText = getTodoText(activity, sessionId);

        List<TodoItem> uncompleted = new ArrayList<>();
        List<TodoItem> completed = new ArrayList<>();

        String[] lines = rawText.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("[x]") || trimmed.startsWith("[X]")) {
                completed.add(new TodoItem(trimmed.substring(3).trim(), true));
            } else if (trimmed.startsWith("[ ]")) {
                uncompleted.add(new TodoItem(trimmed.substring(3).trim(), false));
            } else {
                uncompleted.add(new TodoItem(trimmed, false));
            }
        }

        // 渲染未完成的任务
        for (TodoItem item : uncompleted) {
            listLayout.addView(createRowView(activity, sessionId, listLayout, focusAnchor, item));
        }

        // 渲染已完成的任务
        for (TodoItem item : completed) {
            listLayout.addView(createRowView(activity, sessionId, listLayout, focusAnchor, item));
        }

        // 渲染底部的 "+" 新增行
        listLayout.addView(createAddRowView(activity, sessionId, listLayout, focusAnchor));
    }

    private static void saveCurrentState(Context context, String sessionId, LinearLayout listLayout) {
        StringBuilder sb = new StringBuilder();
        int count = listLayout.getChildCount();
        for (int i = 0; i < count; i++) {
            View row = listLayout.getChildAt(i);
            if (row.getTag() instanceof TodoItem) {
                TodoItem item = (TodoItem) row.getTag();
                CheckBox cb = row.findViewWithTag("checkbox");
                EditText et = row.findViewWithTag("edittext");
                if (et != null && cb != null) {
                    String text = et.getText().toString().trim();
                    if (!text.isEmpty()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(cb.isChecked() ? "[x] " : "[ ] ").append(text);
                    }
                }
            }
        }
        saveTodoText(context, sessionId, sb.toString());
    }

    private static View createRowView(Activity activity, String sessionId, LinearLayout listLayout, EditText focusAnchor, TodoItem item) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UIUtils.dp(activity, 4), 0, UIUtils.dp(activity, 4));
        row.setTag(item);

        CheckBox checkBox = new CheckBox(activity);
        checkBox.setTag("checkbox");
        checkBox.setChecked(item.completed);
        checkBox.setButtonTintList(ColorStateList.valueOf(Color.rgb(52, 211, 153))); // 极客绿

        EditText editText = new EditText(activity);
        editText.setTag("edittext");
        editText.setText(item.text);
        editText.setBackground(null);
        // 允许自动折行显示（不强行限制单行），使超长任务可以换行展示完整
        editText.setSingleLine(false);
        editText.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        // 关键：拦截多行默认回车打入 \n 的行为，强制在软键盘上显示 IME_ACTION_NEXT（下一步/回车弯箭头）
        editText.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_ENTER_ACTION | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        editText.setTextSize(14);
        editText.setTextColor(item.completed ? Color.rgb(107, 114, 128) : Color.rgb(243, 244, 246));
        if (item.completed) {
            editText.setPaintFlags(editText.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        }

        TextView delBtn = new TextView(activity);
        delBtn.setText("✕");
        delBtn.setTextColor(Color.rgb(156, 163, 175));
        delBtn.setTextSize(14);
        delBtn.setGravity(Gravity.CENTER);
        delBtn.setPadding(UIUtils.dp(activity, 8), UIUtils.dp(activity, 8), UIUtils.dp(activity, 8), UIUtils.dp(activity, 8));
        delBtn.setVisibility(View.INVISIBLE); // 默认隐藏，获得焦点才显示

        // 监听焦点以控制删除按钮显示/隐藏（防抖动占位）
        editText.setOnFocusChangeListener((v, hasFocus) -> {
            delBtn.setVisibility(hasFocus ? View.VISIBLE : View.INVISIBLE);
        });

        // 勾选事件，由于涉及排序沉底，需立刻更新数据状态重绘
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            item.completed = isChecked;
            if (isChecked) {
                editText.setTextColor(Color.rgb(107, 114, 128));
                editText.setPaintFlags(editText.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            } else {
                editText.setTextColor(Color.rgb(243, 244, 246));
                editText.setPaintFlags(editText.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
            }
            saveCurrentState(activity, sessionId, listLayout);
            listLayout.post(() -> {
                renderList(activity, sessionId, listLayout, focusAnchor);
                focusFirstInput(activity, listLayout);
            });
        });

        // 实时修改文本只修改内存，关闭弹窗时统一写磁盘，防止打字磁盘I/O导致卡顿
        editText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                item.text = s.toString();
                // 粘贴多行文本并自动切分（由于结构改变，这里触发一次重组）
                String text = s.toString();
                if (text.contains("\n")) {
                    String[] parts = text.split("\n");
                    if (parts.length > 1) {
                        editText.removeTextChangedListener(this);
                        editText.setText(parts[0].trim());
                        editText.addTextChangedListener(this);
                        item.text = parts[0].trim();

                        int baseIndex = listLayout.indexOfChild(row);
                        for (int i = 1; i < parts.length; i++) {
                            String part = parts[i].trim();
                            if (part.isEmpty()) continue;
                            TodoItem newItem = new TodoItem(part, false);
                            View newRow = createRowView(activity, sessionId, listLayout, focusAnchor, newItem);
                            listLayout.addView(newRow, baseIndex + i);
                        }
                        saveCurrentState(activity, sessionId, listLayout);
                        listLayout.post(() -> renderList(activity, sessionId, listLayout, focusAnchor));
                    }
                }
            }
        });

        // 拦截回车动作，自动在当前行下方添加一个新待办，并顺滑移动焦点到新行
        editText.setOnEditorActionListener((v, actionId, event) -> {
            boolean isEnter = (actionId == EditorInfo.IME_ACTION_DONE || 
                               actionId == EditorInfo.IME_ACTION_NEXT ||
                               actionId == EditorInfo.IME_NULL ||
                               (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER));
            if (isEnter) {
                // 仅在 ACTION_DOWN（或者由 actionId 触发，此时 event 为 null）时执行插入逻辑
                if (event == null || event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    String currentText = editText.getText().toString().trim();
                    // 仅当当前文本框内容非空时，按回车才创建并跳转至下个新行；为空时什么都不做
                    if (!currentText.isEmpty()) {
                        int currentIndex = listLayout.indexOfChild(row);
                        TodoItem newItem = new TodoItem("", false);
                        View newRow = createRowView(activity, sessionId, listLayout, focusAnchor, newItem);
                        listLayout.addView(newRow, currentIndex + 1);
                        
                        EditText newEt = newRow.findViewWithTag("edittext");
                        if (newEt != null) {
                            newEt.requestFocus();
                            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) 
                                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                            if (imm != null) {
                                imm.showSoftInput(newEt, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                            }
                        }
                        saveCurrentState(activity, sessionId, listLayout);
                    }
                }
                return true; // 彻底拦截消费该回车事件的所有 DOWN / UP，阻止收起键盘
            }
            return false;
        });

        // 点击 ✕ 按钮删除行，仅更新 UI 和缓存，并在删除前平滑转移焦点防止收起键盘
        delBtn.setOnClickListener(v -> {
            if (editText.isFocused()) {
                View targetFocusRow = null;
                int currentIndex = listLayout.indexOfChild(row);
                // 优先聚焦到下一行
                if (currentIndex + 1 < listLayout.getChildCount()) {
                    targetFocusRow = listLayout.getChildAt(currentIndex + 1);
                }
                // 其次聚焦到上一行
                if (targetFocusRow == null && currentIndex - 1 >= 0) {
                    targetFocusRow = listLayout.getChildAt(currentIndex - 1);
                }
                
                boolean focusedTransfered = false;
                if (targetFocusRow != null) {
                    EditText targetEt = targetFocusRow.findViewWithTag("edittext");
                    if (targetEt != null) {
                        targetEt.requestFocus();
                        targetEt.setSelection(targetEt.getText().length());
                        focusedTransfered = true;
                    }
                }
                
                // 如果没有临近行可转移（只剩下最后一行要被删），就先转移给 focusAnchor 支撑住键盘
                if (!focusedTransfered && focusAnchor != null) {
                    focusAnchor.requestFocus();
                }
            }
            listLayout.removeView(row);
            saveCurrentState(activity, sessionId, listLayout);
            listLayout.post(() -> renderList(activity, sessionId, listLayout, focusAnchor));
        });

        row.addView(checkBox, new LinearLayout.LayoutParams(UIUtils.dp(activity, 32), UIUtils.dp(activity, 32)));
        row.addView(editText, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(delBtn, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private static View createAddRowView(Activity activity, String sessionId, LinearLayout listLayout, EditText focusAnchor) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UIUtils.dp(activity, 4), 0, UIUtils.dp(activity, 4));

        TextView addIcon = new TextView(activity);
        addIcon.setText("+");
        addIcon.setTextColor(Color.rgb(107, 114, 128));
        addIcon.setTextSize(18);
        addIcon.setGravity(Gravity.CENTER);
        addIcon.setPadding(UIUtils.dp(activity, 6), 0, UIUtils.dp(activity, 6), 0);

        EditText editText = new EditText(activity);
        editText.setTag("edittext");
        editText.setHint("新增任务...");
        editText.setHintTextColor(Color.rgb(107, 114, 128));
        editText.setTextColor(Color.rgb(243, 244, 246));
        editText.setBackground(null);
        // 新建输入框也设置为多行（但通过 Ime 拦截回车动作跳转）
        editText.setSingleLine(false);
        editText.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editText.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_ENTER_ACTION | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        editText.setTextSize(14);

        // 监听回车，将输入变成正式任务插入到它的上方
        editText.setOnEditorActionListener((v, actionId, event) -> {
            boolean isEnter = (actionId == EditorInfo.IME_ACTION_DONE || 
                               actionId == EditorInfo.IME_ACTION_NEXT ||
                               actionId == EditorInfo.IME_NULL ||
                               (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER));
            if (isEnter) {
                if (event == null || event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    String text = editText.getText().toString().trim();
                    if (!text.isEmpty()) {
                        TodoItem newItem = new TodoItem(text, false);
                        int addRowIndex = listLayout.indexOfChild(row);
                        View newRow = createRowView(activity, sessionId, listLayout, focusAnchor, newItem);
                        listLayout.addView(newRow, addRowIndex);
                        
                        editText.setText(""); // 清空
                        saveCurrentState(activity, sessionId, listLayout);
                        
                        // 重新排序沉底，并聚焦到新任务行，使得软键盘不收回
                        listLayout.post(() -> {
                            renderList(activity, sessionId, listLayout, focusAnchor);
                            // 让刚刚生成的这个新任务行获得焦点
                            focusFirstInput(activity, listLayout);
                        });
                    }
                }
                return true; // 彻底消费拦截
            }
            return false;
        });

        row.addView(addIcon, new LinearLayout.LayoutParams(UIUtils.dp(activity, 32), -2));
        row.addView(editText, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private static void focusFirstInput(Activity activity, LinearLayout listLayout) {
        listLayout.postDelayed(() -> {
            int count = listLayout.getChildCount();
            for (int i = 0; i < count; i++) {
                View row = listLayout.getChildAt(i);
                EditText et = row.findViewWithTag("edittext");
                if (et != null && et.getVisibility() == View.VISIBLE && et.isEnabled()) {
                    et.requestFocus();
                    et.setSelection(et.getText().length());
                    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) 
                        activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                    }
                    return;
                }
            }
        }, 150);
    }

    private static class TodoItem {
        String text;
        boolean completed;

        TodoItem(String text, boolean completed) {
            this.text = text;
            this.completed = completed;
        }
    }
}
