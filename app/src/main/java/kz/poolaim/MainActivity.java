package kz.poolaim;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    static final String GAME = "com.miniclip.eightballpool";

    private static final String HELP =
            "КАК ПОЛЬЗОВАТЬСЯ\n\n"
                    + "1. Один раз настрой стол: в игре нажми ⚙ на панели. Перетащи жёлтые углы рамки "
                    + "на края зелёного сукна (там, где начинаются бортики). Белый круг положи на любой шар "
                    + "и жёлтой ручкой справа подгони размер. Нажми ✓.\n\n"
                    + "2. Белое кольцо поставь на биток, жёлтое — на шар, который хочешь забить. "
                    + "Кольца таскаются пальцем, можно хвататься за край кольца, чтобы видеть шар.\n\n"
                    + "3. Зелёная линия — куда покатится шар (в какую лузу). "
                    + "Белый круг — куда должен прийти биток в момент удара. "
                    + "Наведи кий в игре так, чтобы кружок прицела игры совпал с белым кругом, и бей.\n\n"
                    + "4. Голубой пунктир — куда уедет биток после удара (если бить без вращения). "
                    + "Тап по белому кольцу — спрятать/показать его.\n\n"
                    + "5. Число у шара — угол резки: зелёное — легко, жёлтое — средне, красное — сложно. "
                    + "«2/5» — это второй из пяти вариантов.\n\n"
                    + "КНОПКИ ПАНЕЛИ\n"
                    + "✥ — передвинуть панель\n"
                    + "⟳ — другая луза (или тап по жёлтому кольцу)\n"
                    + "Б — добавить удары через борт\n"
                    + "⚙ — настройка стола\n"
                    + "👁 — спрятать всё, чтобы не мешало\n"
                    + "✕ — выключить\n\n"
                    + "Если кольца или линии не ложатся на стол — заново сделай настройку ⚙ в той же "
                    + "ориентации экрана, в которой играешь.\n\n"
                    + "Используй против бота / офлайн. В онлайне это нечестно по отношению к живым соперникам, "
                    + "а за сторонние программы аккаунт могут заблокировать.";

    private Button bPerm, bStart;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * dp);

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("🎱 Pool Линейка");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        ll.addView(title);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, (int) (8 * dp), 0, (int) (8 * dp));
        ll.addView(status);

        bPerm = addButton(ll, "1. Разрешить показ поверх других окон", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            }
        });
        bStart = addButton(ll, "2. Запустить линейку и открыть игру", v -> start());
        addButton(ll, "Выключить линейку", v -> {
            stopService(new Intent(this, OverlayService.class));
            v.postDelayed(this::refresh, 300);
        });

        TextView help = new TextView(this);
        help.setTextSize(15);
        help.setLineSpacing(0, 1.2f);
        help.setPadding(0, (int) (16 * dp), 0, 0);
        help.setText(HELP);
        ll.addView(help);

        ScrollView sv = new ScrollView(this);
        sv.addView(ll);
        setContentView(sv);
    }

    private Button addButton(LinearLayout ll, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        ll.addView(b, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return b;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean ok = Settings.canDrawOverlays(this);
        bPerm.setEnabled(!ok);
        bStart.setEnabled(ok);
        if (!ok) status.setText("Шаг 1: дай разрешение «Поверх других приложений».");
        else if (OverlayService.running) status.setText("✅ Линейка работает. Открой игру.");
        else status.setText("Разрешение есть — жми «Запустить».");
    }

    private void start() {
        if (!Settings.canDrawOverlays(this)) {
            refresh();
            return;
        }
        startService(new Intent(this, OverlayService.class));
        Intent game = getPackageManager().getLaunchIntentForPackage(GAME);
        if (game != null) {
            startActivity(game);
        } else {
            Toast.makeText(this, "Линейка запущена. Открой 8 Ball Pool сам.", Toast.LENGTH_LONG).show();
            refresh();
        }
    }
}
