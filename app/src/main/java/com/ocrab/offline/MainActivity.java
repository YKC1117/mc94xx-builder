package com.ocrab.offline;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends ComponentActivity {

    private static final int STABLE_HITS_REQUIRED = 2;
    private static final int CLEAR_FRAMES_REQUIRED = 2;
    private static final long ANALYZE_THROTTLE_MS = 180L;
    private static final long RESULT_HOLD_MS = 1800L;

    private enum State {
        A,
        WAIT_CLEAR_TO_B1,
        B1,
        WAIT_CLEAR_TO_B2,
        B2,
        DONE,
        WAIT_CLEAR_TO_A
    }

    private PreviewView previewView;
    private TextView stepText;
    private TextView statusText;
    private TextView aText;
    private TextView bText;
    private TextView bTitleText;
    private TextView candidateText;
    private Button resetButton;

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean ocrBusy = new AtomicBoolean(false);

    private TextRecognizer recognizer;
    private ToneGenerator toneGenerator;
    private Vibrator vibrator;

    private State state = State.A;
    private String aValue = "";
    private String b1Value = "";
    private String b2Value = "";
    private String lastCandidate = "";
    private int stableHits = 0;
    private int clearFrames = 0;
    private long lastAnalyzeAt = 0L;

    private final Pattern exactPattern = Pattern.compile("[A-Z0-9]{6}[-._][A-Z0-9]{6}[-._][A-Z0-9]{2}");
    private final Pattern spacedPattern = Pattern.compile("([A-Z0-9]{6})\\s+([A-Z0-9]{6})\\s+([A-Z0-9]{2})");

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    setStatus("需要相機權限", false, true);
                    stepText.setText("請到系統設定允許此 App 使用相機");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        stepText = findViewById(R.id.stepText);
        statusText = findViewById(R.id.statusText);
        aText = findViewById(R.id.aText);
        bText = findViewById(R.id.bText);
        bTitleText = findViewById(R.id.bTitleText);
        candidateText = findViewById(R.id.candidateText);
        resetButton = findViewById(R.id.resetButton);

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 85);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        resetButton.setOnClickListener(v -> resetToA("已清除，請重新對準 A"));
        resetToA("把 A 的料號放進藍框，系統會自動讀取");

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        stepText.setText("正在啟動相機…");
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                bindCamera(provider);
            } catch (Exception e) {
                setStatus("相機啟動失敗", false, true);
                stepText.setText(e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera(ProcessCameraProvider provider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

        provider.unbindAll();
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);

        runOnUiThread(() -> {
            stepText.setText("即時 OCR 已啟動｜資料只在本機記憶體中");
            setStatus("請對準 A", false, false);
        });
    }

    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        long now = System.currentTimeMillis();
        if (now - lastAnalyzeAt < ANALYZE_THROTTLE_MS || !ocrBusy.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }
        lastAnalyzeAt = now;

        if (imageProxy.getImage() == null) {
            ocrBusy.set(false);
            imageProxy.close();
            return;
        }

        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        int uprightWidth = (rotation == 90 || rotation == 270) ? imageProxy.getHeight() : imageProxy.getWidth();
        int uprightHeight = (rotation == 90 || rotation == 270) ? imageProxy.getWidth() : imageProxy.getHeight();

        InputImage input = InputImage.fromMediaImage(imageProxy.getImage(), rotation);
        recognizer.process(input)
                .addOnSuccessListener(text -> handleRecognizedText(text, uprightWidth, uprightHeight))
                .addOnFailureListener(err -> runOnUiThread(() -> stepText.setText("OCR 錯誤：" + err.getClass().getSimpleName())))
                .addOnCompleteListener(task -> {
                    imageProxy.close();
                    ocrBusy.set(false);
                });
    }

    private void handleRecognizedText(Text text, int imageWidth, int imageHeight) {
        String candidate = findCandidateInsideRoi(text, imageWidth, imageHeight);
        runOnUiThread(() -> onCandidate(candidate));
    }

    private String findCandidateInsideRoi(Text text, int imageWidth, int imageHeight) {
        if (imageWidth <= 0 || imageHeight <= 0) return "";

        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                if (box == null) continue;

                float cx = box.exactCenterX() / (float) imageWidth;
                float cy = box.exactCenterY() / (float) imageHeight;

                if (cx < RoiOverlayView.ROI_LEFT || cx > RoiOverlayView.ROI_RIGHT ||
                        cy < RoiOverlayView.ROI_TOP || cy > RoiOverlayView.ROI_BOTTOM) {
                    continue;
                }

                String c = extractPartNumber(line.getText());
                if (!c.isEmpty()) return c;
            }
        }
        return "";
    }

    private String extractPartNumber(String raw) {
        if (raw == null) return "";
        String s = raw.toUpperCase(Locale.ROOT)
                .replace('‐', '-')
                .replace('‑', '-')
                .replace('‒', '-')
                .replace('–', '-')
                .replace('—', '-')
                .replace('―', '-')
                .trim();

        Matcher m = exactPattern.matcher(s.replace(" ", ""));
        if (m.find()) {
            return m.group().replace('.', '-').replace('_', '-');
        }

        Matcher spaced = spacedPattern.matcher(s);
        if (spaced.find()) {
            return spaced.group(1) + "-" + spaced.group(2) + "-" + spaced.group(3);
        }
        return "";
    }

    private void onCandidate(String candidate) {
        candidateText.setText("目前辨識：" + (candidate.isEmpty() ? "—" : candidate));

        if (candidate.isEmpty()) {
            lastCandidate = "";
            stableHits = 0;
            handleClearFrame();
            return;
        }

        if (isWaitingForClear()) {
            clearFrames = 0;
            return;
        }

        if (state == State.DONE) return;

        if (candidate.equals(lastCandidate)) {
            stableHits++;
        } else {
            lastCandidate = candidate;
            stableHits = 1;
        }

        stepText.setText("候選 " + candidate + "｜穩定 " + stableHits + "/" + STABLE_HITS_REQUIRED);
        if (stableHits >= STABLE_HITS_REQUIRED) {
            lastCandidate = "";
            stableHits = 0;
            acceptCandidate(candidate);
        }
    }

    private boolean isWaitingForClear() {
        return state == State.WAIT_CLEAR_TO_B1 ||
                state == State.WAIT_CLEAR_TO_B2 ||
                state == State.WAIT_CLEAR_TO_A;
    }

    private void handleClearFrame() {
        if (!isWaitingForClear()) return;
        clearFrames++;
        if (clearFrames < CLEAR_FRAMES_REQUIRED) return;
        clearFrames = 0;

        if (state == State.WAIT_CLEAR_TO_B1) {
            state = State.B1;
            setStatus("請對準 B", false, false);
            stepText.setText("A 已完成，現在掃 B");
        } else if (state == State.WAIT_CLEAR_TO_B2) {
            state = State.B2;
            setStatus("NG｜請重掃 B", false, true);
            stepText.setText("第一次 B 不一致，請再掃一次 B");
            bTitleText.setText("B（二次核對）");
        } else if (state == State.WAIT_CLEAR_TO_A) {
            resetToA("請掃下一組 A");
        }
    }

    private void acceptCandidate(String value) {
        switch (state) {
            case A:
                aValue = value;
                updateValues();
                feedback(true);
                state = State.WAIT_CLEAR_TO_B1;
                clearFrames = 0;
                setStatus("A 已讀取｜請移開 A", false, false);
                stepText.setText("移開目前標籤後，會自動進入 B");
                break;

            case B1:
                b1Value = value;
                updateValues();
                if (b1Value.equals(aValue)) {
                    feedback(true);
                    finishResult(true, "PASS", "A / B 相同");
                } else {
                    feedback(false);
                    state = State.WAIT_CLEAR_TO_B2;
                    clearFrames = 0;
                    setStatus("NG｜A / B 不一致", false, true);
                    stepText.setText("移開 B 後，系統會要求第二次核對");
                }
                break;

            case B2:
                b2Value = value;
                updateValues();
                if (b2Value.equals(aValue)) {
                    feedback(true);
                    finishResult(true, "PASS｜二次核對", "第二次 B 與 A 相同");
                } else {
                    feedback(false);
                    finishResult(false, "NG｜最終不一致", "第二次 B 仍與 A 不同");
                }
                break;

            default:
                break;
        }
    }

    private void finishResult(boolean pass, String status, String detail) {
        state = State.DONE;
        setStatus(status, pass, !pass);
        stepText.setText(detail + "｜結果將短暫保留");
        mainHandler.postDelayed(() -> {
            if (state == State.DONE) {
                state = State.WAIT_CLEAR_TO_A;
                clearFrames = 0;
                setStatus("請移開目前標籤", false, false);
                stepText.setText("畫面清空後自動進入下一組 A");
            }
        }, RESULT_HOLD_MS);
    }

    private void resetToA(String message) {
        state = State.A;
        aValue = "";
        b1Value = "";
        b2Value = "";
        lastCandidate = "";
        stableHits = 0;
        clearFrames = 0;
        updateValues();
        bTitleText.setText("B");
        candidateText.setText("目前辨識：—");
        setStatus("請對準 A", false, false);
        stepText.setText(message);
    }

    private void updateValues() {
        aText.setText(aValue.isEmpty() ? "—" : aValue);
        String bDisplay = !b2Value.isEmpty() ? b2Value : b1Value;
        bText.setText(bDisplay.isEmpty() ? "—" : bDisplay);
    }

    private void setStatus(String text, boolean pass, boolean ng) {
        statusText.setText(text);
        if (pass) statusText.setTextColor(Color.rgb(134, 239, 172));
        else if (ng) statusText.setTextColor(Color.rgb(252, 165, 165));
        else statusText.setTextColor(Color.rgb(125, 211, 252));
    }

    private void feedback(boolean ok) {
        try {
            if (ok) toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 90);
            else toneGenerator.startTone(ToneGenerator.TONE_SUP_ERROR, 260);
        } catch (Exception ignored) {}

        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = ok ? new long[]{0, 45} : new long[]{0, 120, 70, 120};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
                } else {
                    //noinspection deprecation
                    vibrator.vibrate(pattern, -1);
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        cameraExecutor.shutdownNow();
        if (recognizer != null) recognizer.close();
        if (toneGenerator != null) toneGenerator.release();
    }
}
