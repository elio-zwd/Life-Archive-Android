package com.elio.lifearchive;

import android.inputmethodservice.InputMethodService;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 仅在测试 APK 中：不依赖目标应用运行时，供硬件键盘模拟器验证 IME 避让。 */
public class DesktopTestIme extends InputMethodService {
    @Override public boolean onEvaluateInputViewShown() { return true; }
    @Override public boolean onEvaluateFullscreenMode() { return false; }
    @Override public boolean onShowInputRequested(int flags, boolean configChange) { return true; }
    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        getWindow().getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (int) (220 * getResources().getDisplayMetrics().density));
    }
    @Override public View onCreateInputView() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER);
        body.setMinimumHeight((int) (220 * getResources().getDisplayMetrics().density));
        body.setBackgroundColor(0xffe6ebee);
        TextView title = new TextView(this);
        title.setText("桌面交互测试键盘"); title.setTextSize(18); title.setTextColor(0xff263238);
        body.addView(title);
        Button input = new Button(this); input.setText("输入 a");
        input.setAllCaps(false); input.setContentDescription("test-ime-input");
        input.setOnClickListener(view -> { if (getCurrentInputConnection() != null) getCurrentInputConnection().commitText("a", 1); });
        body.addView(input);
        Button erase = new Button(this); erase.setText("退格");
        erase.setContentDescription("test-ime-erase");
        erase.setOnClickListener(view -> { if (getCurrentInputConnection() != null) getCurrentInputConnection().deleteSurroundingText(1, 0); });
        body.addView(erase);
        return body;
    }
}
