package cn.examassist.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Independent test APK window: only Android APIs, with no target APK runtime dependency. */
public final class ProtectedQuestionActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 160, 32, 32);
        layout.setBackgroundColor(Color.WHITE);
        for (String line : new String[]{"太阳从哪个方向升起？", "A. 东", "B. 西", "C. 南", "D. 北"}) {
            TextView text = new TextView(this);
            text.setText(line);
            text.setTextSize(24f);
            text.setTextColor(Color.BLACK);
            text.setPadding(0, 16, 0, 16);
            layout.addView(text);
        }
        setContentView(layout);
    }
}
