package kz.poolaim;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    static final String GAME = "com.miniclip.eightballpool";
    private static final int REQ_CAPTURE = 7;

    private static final String HELP =
            "КАК ПОЛЬЗОВАТЬСЯ\n\n"
                    + "1. Нажми «Запустить». Android спросит про запись экрана — выбери «Весь экран» и «Начать». "
                    + "Так линейка сама видит стол и шары. Ничего никуда не отправляется, всё считается на телефоне.\n\n"
                    + "2. Всё. Дальше всё автоматически: линейка сама находит стол, размер шаров, биток и все шары, "
                    + "и для каждого шара рисует свою линию:\n"
                    + "• зелёная линия — в какую лузу пойдёт этот шар;\n"
                    + "• белый круг — куда навести прицел игры (кружок прицела в игре совмести с ним);\n"
                    + "• самый простой удар выделен ярко: белая линия от битка, сила и голубой пунктир — "
                    + "куда уедет биток (красный — упадёт в лузу!);\n"
                    + "• красный пунктир вокруг шара — его сейчас не забить.\n\n"
                    + "• белые точки на линиях — отскоки от бортов: линейка ищет удары через 1–2 борта и битком через борт.\n\n"
                    + "ФИЗИКА УДАРА (как в видео): двигай кий в игре — линейка просчитывает удар со всеми шарами: "
                    + "белая линия — путь битка с отскоками, цветные — пути шаров, в которые он попадёт (каждый своим цветом), "
                    + "точки — где шары остановятся, кольцо у лузы — шар упадёт. На разбое видно, куда разлетятся все шары. "
                    + "Сила берётся со шкалы справа (АВТО: 100% на разбое, иначе 70%) — поставь её как в игре.\n\n"
                    + "💡 — включить подсказки «линия к каждому шару» (по умолчанию выключены, экран чистый).\n\n"
                    + "3. СИЛА — шкала справа на панели. АВТО — показывает, с какой силой бить (в % от полной силы в игре). "
                    + "Тап по шкале — ручной режим: веди пальцем, и видно, докатится ли шар.\n\n"
                    + "КНОПКИ ПАНЕЛИ\n"
                    + "✥ — передвинуть панель\n"
                    + "🎯 — найти стол и шары заново (или включить запись экрана)\n"
                    + "⟳ — выделить следующий шар\n"
                    + "В / ● / ◐ — все шары / только сплошные / только полосатые\n"
                    + "Б — удары через борта вкл/выкл (по умолчанию включено)\n"
                    + "⚙ — подстроить стол вручную (только если нашёлся неточно)\n"
                    + "👁 — спрятать всё\n"
                    + "✕ — выключить\n\n"
                    + "Вверху экрана маленькая строка показывает, что делает линейка "
                    + "(«ищу стол», «шаров: 15» и т.п.). Если что-то не так — пришли скриншот.\n\n"
                    + "Используй против бота / офлайн. В онлайне это нечестно по отношению к живым соперникам, "
                    + "а за сторонние программы аккаунт могут заблокировать.";

    private Button bPerm, bStart;
    private TextView status, dmaxLabel;
    private SharedPreferences prefs;
    private boolean launchGameAfter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("aim", MODE_PRIVATE);
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * dp);

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("🎱 Pool Линейка 5.0");
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
        bStart = addButton(ll, "2. Запустить линейку и открыть игру", v -> requestCapture(true));
        addButton(ll, "Выключить линейку", v -> {
            stopService(new Intent(this, OverlayService.class));
            v.postDelayed(this::refresh, 300);
        });

        // Масштаб силы: насколько далеко катится биток при 100% силы (у разных киёв в игре разная сила).
        dmaxLabel = new TextView(this);
        dmaxLabel.setTextSize(15);
        dmaxLabel.setPadding(0, (int) (18 * dp), 0, 0);
        ll.addView(dmaxLabel);
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        float dmax = prefs.getFloat("dmax", 6f);
        sb.setProgress(Math.round((dmax - 2f) * 10f));
        updateDmaxLabel(dmax);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                float v = 2f + progress / 10f;
                updateDmaxLabel(v);
                if (fromUser) prefs.edit().putFloat("dmax", v).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        ll.addView(sb, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView hint = new TextView(this);
        hint.setTextSize(13);
        hint.setText("Если на деле шары катятся дальше, чем рисует линейка, — двигай вправо; если ближе — влево. "
                + "Зависит от силы твоего кия в игре.");
        ll.addView(hint);

        TextView help = new TextView(this);
        help.setTextSize(15);
        help.setLineSpacing(0, 1.2f);
        help.setPadding(0, (int) (16 * dp), 0, 0);
        help.setText(HELP);
        ll.addView(help);

        ScrollView sv = new ScrollView(this);
        sv.addView(ll);
        setContentView(sv);

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra("capture", false)) {
            intent.removeExtra("capture");
            requestCapture(true);
        }
    }

    private void updateDmaxLabel(float v) {
        dmaxLabel.setText(String.format(java.util.Locale.US, "Дальность удара на 100%% силы: %.1f длины стола", v));
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

    private void requestCapture(boolean launchGame) {
        if (!Settings.canDrawOverlays(this)) {
            refresh();
            return;
        }
        launchGameAfter = launchGame;
        try {
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (Exception e) {
            startOverlay(0, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode == RESULT_OK && data != null) {
            startOverlay(resultCode, data);
        } else {
            startOverlay(0, null);
            Toast.makeText(this, "Без записи экрана автоприцел не работает — ручной режим", Toast.LENGTH_LONG).show();
        }
    }

    private void startOverlay(int code, Intent data) {
        Intent svc = new Intent(this, OverlayService.class);
        if (data != null) {
            svc.putExtra("code", code);
            svc.putExtra("data", data);
        }
        startService(svc);
        refresh();
        if (launchGameAfter) {
            launchGameAfter = false;
            // Небольшая пауза, чтобы служба успела стартовать, пока мы на экране.
            getWindow().getDecorView().postDelayed(() -> {
                Intent game = getPackageManager().getLaunchIntentForPackage(GAME);
                if (game != null) {
                    startActivity(game);
                } else {
                    Toast.makeText(this, "Линейка запущена. Открой 8 Ball Pool сам.", Toast.LENGTH_LONG).show();
                }
            }, 800);
        }
    }
}
