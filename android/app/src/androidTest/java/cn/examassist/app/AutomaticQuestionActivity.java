package cn.examassist.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;
import android.widget.*;

/** Independent external-app fixture; its next buttons reject incorrect selections. */
public final class AutomaticQuestionActivity extends Activity {
    private LinearLayout layout;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("labels", false)) labelSingle();
        else if (getIntent().getBooleanExtra("compatibility", false)) customSingle(); else single();
    }
    @Override public void onDestroy() { timerHandler.removeCallbacksAndMessages(null); super.onDestroy(); }
    private void screen(String stem) {
        layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(28, 100, 28, 28);
        setContentView(layout);
        TextView title = new TextView(this);
        title.setText(stem); title.setTextSize(24); layout.addView(title);
    }
    private void single() {
        screen("太阳从哪个方向升起？");
        RadioGroup group = new RadioGroup(this);
        String[] options = {"A. 东", "B. 西", "C. 南", "D. 北"};
        for (int i = 0; i < options.length; i++) {
            RadioButton button = new RadioButton(this);
            button.setId(100 + i); button.setText(options[i]); button.setTextSize(22); group.addView(button);
        }
        layout.addView(group); group.check(101);
        Button next = new Button(this); next.setText("下一题"); layout.addView(next);
        next.setOnClickListener(v -> { if (group.getCheckedRadioButtonId() == 100) multiple(); else screen("错误选择单选题"); });
    }
    private void multiple() {
        screen("多选题\n下列哪些是质数？");
        String[] options = {"A. 2", "B. 4", "C. 3"};
        CheckBox[] choices = new CheckBox[3];
        for (int i = 0; i < options.length; i++) {
            choices[i] = new CheckBox(this); choices[i].setText(options[i]); choices[i].setTextSize(22); layout.addView(choices[i]);
        }
        choices[1].setChecked(true);
        Button next = new Button(this); next.setText("下一题"); layout.addView(next);
        next.setOnClickListener(v -> {
            if (choices[0].isChecked() && !choices[1].isChecked() && choices[2].isChecked()) fill();
            else screen("错误选择多选题");
        });
    }
    private void fill() {
        screen("圆周率约为多少？");
        EditText input = new EditText(this); layout.addView(input);
        Button submit = new Button(this); submit.setText("提交试卷"); layout.addView(submit);
        submit.setOnClickListener(v -> screen("错误提交试卷"));
    }
    private TextView row(String text) {
        TextView row = new TextView(this);
        row.setText(text); row.setTextSize(22); row.setPadding(16, 22, 16, 22);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        layout.addView(row); return row;
    }
    private void customSingle() {
        screen("太阳从哪个方向升起？");
        // No checkable/selected flags: the only feedback is a painted background.
        int[] selected = {-1};
        String[] options = {"A. 东", "B. 西", "C. 南", "D. 北"};
        for (int i = 0; i < options.length; i++) {
            final int choice = i;
            TextView view = row(options[i]);
            view.setOnClickListener(v -> { selected[0] = choice; v.setBackgroundColor(0xffddeeff); });
        }
        LinearLayout next = new LinearLayout(this);
        TextView label = new TextView(this); label.setText("下一题"); label.setTextSize(24); next.addView(label);
        TextView progress = new TextView(this); progress.setText("1/4"); next.addView(progress);
        next.setPadding(20, 25, 20, 25); layout.addView(next);
        next.setOnClickListener(v -> { if (selected[0] == 0) gestureMultiple(); else screen("错误选择自定义单选题"); });
    }
    private void gestureMultiple() {
        screen("多选题\n下列哪些是质数？");
        boolean[] selected = {false, false, false};
        String[] options = {"A. 2", "B. 4", "C. 3"};
        for (int i = 0; i < options.length; i++) {
            final int choice = i;
            TextView view = row(options[i]);
            view.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    selected[choice] = !selected[choice];
                    v.setBackgroundColor(selected[choice] ? 0xffddeeff : 0xffffffff);
                }
                return true;
            });
        }
        TextView next = row("下一题（2/4）");
        next.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                if (selected[0] && !selected[1] && selected[2]) webQuestion();
                else screen("错误选择触摸多选题");
            }
            return true;
        });
    }
    private void webQuestion() {
        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        setContentView(web);
        web.loadDataWithBaseURL(null, "<!doctype html><meta name='viewport' content='width=device-width, initial-scale=1'>"
            + "<style>body{font:22px sans-serif;padding:30px 14px}.opt,#next{padding:20px;margin:8px 0;background:#eee}.on{background:#acd!important}</style>"
            + "<div id='quiz'><p id='timer'>倒计时：09:59</p><p>苹果属于哪类食物？</p>"
            + "<div class='opt' data-choice='A'>A. 水果</div><div class='opt' data-choice='B'>B. 蔬菜</div>"
            + "<div id='next'>下一题（3/4）</div></div>"
            + "<script>let selected='';let time=59;setInterval(()=>{let t=document.getElementById('timer');if(t)t.textContent='倒计时：09:'+String(--time).padStart(2,'0')},1000);"
            + "document.body.addEventListener('click',e=>{let o=e.target.closest('.opt');if(o){selected=o.dataset.choice;document.querySelectorAll('.opt').forEach(x=>x.classList.toggle('on',x===o));}"
            + "if(e.target.id==='next'){document.getElementById('quiz').innerHTML=selected==='A'?'<p>圆周率约为多少？</p><input><button>提交试卷</button>':'<p>错误选择网页题</p>';}});</script>",
            "text/html", "UTF-8", null);
    }
    private TextView letterRow(String label, String content) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, 12, 0, 12);
        layout.addView(row);
        TextView letter = new TextView(this);
        letter.setText(label + "."); letter.setTextSize(22); letter.setPadding(16, 16, 16, 16);
        row.addView(letter);
        // Only the letter accepts touches. The rest of the row is fragmented text;
        // a tap at the full row's centre must not accidentally pass this fixture.
        for (char part : content.toCharArray()) {
            TextView text = new TextView(this);
            text.setText(String.valueOf(part)); text.setTextSize(22); row.addView(text);
        }
        return letter;
    }
    private void labelSingle() {
        screen("太阳升起的方向是哪里？");
        TextView type = new TextView(this);
        type.setText("学习平台单选题1|100"); layout.addView(type, 0);
        int[] selected = {-1};
        String[] options = {"正西", "正东", "正南", "正北"};
        for (int i = 0; i < options.length; i++) {
            final int choice = i;
            TextView letter = letterRow(String.valueOf((char) ('A' + i)), options[i]);
            letter.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    selected[0] = choice; v.setBackgroundColor(0xffddeeff);
                }
                return true;
            });
        }
        row("本题分值：2分");
        row("下一题（2/3）").setOnClickListener(v -> { if (selected[0] == 1) labelMultiple(); else screen("错误选择字母单选题"); });
    }
    private void labelMultiple() {
        // No checkbox role or multi-choice title. The resolved AC answer supplies the labels.
        screen("以下哪些属于水果？");
        boolean[] selected = {false, false, false};
        String[] options = {"苹果", "白菜", "香蕉"};
        for (int i = 0; i < options.length; i++) {
            final int choice = i;
            TextView letter = letterRow(String.valueOf((char) ('A' + i)), options[i]);
            letter.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    selected[choice] = !selected[choice];
                    v.setBackgroundColor(selected[choice] ? 0xffddeeff : 0xffffffff);
                }
                return true;
            });
        }
        row("下一题").setOnClickListener(v -> {
            if (selected[0] && !selected[1] && selected[2]) unknown(); else screen("错误选择字母多选题");
        });
    }
    private void unknown() {
        screen("星河企业大学\n单选题3|100\n这是一道本地题库没有收录的新题？");
        for (String text : new String[] {"A. 甲", "B. 乙", "C. 丙"}) {
            row(text).setOnClickListener(v -> screen("错误点击未知题目"));
        }
        row("下一题").setOnClickListener(v -> fill());
    }
}
