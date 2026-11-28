package com.hualala.linyu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * 扫码绑定热水器。
 *
 * 趣智校园热水器上的二维码形如：KLCXKJ-Water,M,AABBCCDDEEFF
 * 解析最后一个字段（冒号去掉后为 12 位十六进制）作为设备 snCode。
 *
 * 识别设备后用 RESULT_OK 返回 [EXTRA_SN_CODE]。取消时不返回设备，识别错误在扫码页提示。
 *
 * ## 配色为什么写死
 *
 * 这个页面**故意不接 App 主题**：遮罩纯黑、控件白色、手电筒亮起暖黄。
 * 它永远压在相机画面上，不参与深浅模式；强调色也不跟自定义背景提取的那个色走——
 * 万一提取出个深色，压在相机上根本看不清。微信/支付宝的扫码页都是这个路子。
 *
 * ## 布局为什么用 Compose
 *
 * 以前是 `FrameLayout` 手搭 View，间距写的是**裸 px**（`topMargin = 24`，3x 屏上才 8dp），
 * 手电筒按钮因此贴着屏幕顶端，看着像钻进状态栏；而且它没跟 MainActivity 一样开
 * edge-to-edge，两套规则各走各的。换成 Compose 后间距一律 dp、
 * 安全区统一交给 statusBarsPadding / navigationBarsPadding。
 */
class QrScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SN_CODE = "sn_code"

        fun parseSnCode(raw: String?): String? = QrDecoder.parseSnCode(raw)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    /**
     * 解码线程。**单线程**：同一时刻只解一帧，
     * 既避免多帧并发抢 CPU/内存，也不会让识别结果乱序。
     *
     * （以前 ML Kit 会在内部用自己的线程池，换 ZXing 后这一步要自己管。）
     */
    private val decoderExecutor = Executors.newSingleThreadExecutor()

    /** 正在解帧。相机以 KEEP_ONLY_LATEST 供帧，忙时直接丢帧即可。 */
    @Volatile private var decoding = false

    /** 已经识别到了，停止处理后续帧（避免重复触发返回） */
    @Volatile private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // edge-to-edge，和 MainActivity 保持一致。间距全部交给 Compose 的
        // statusBarsPadding / navigationBarsPadding
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        // 界面是深色的，状态栏图标要用浅色，否则深色图标压在相机画面上看不见
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContent { ScanScreen() }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        // 退出时关灯
        runCatching { camera?.cameraControl?.enableTorch(false) }
        runCatching { cameraProvider?.unbindAll() }
        runCatching { decoderExecutor.shutdownNow() }
    }

    // ── 识别 ──

    /** 命中：震动一下、返回结果并关掉自己。**只应该生效一次** */
    private fun finishWithSn(sn: String) {
        if (done) return
        done = true
        // 相机→结果之间没有任何过渡，不给点反馈会像"突然跳走"了。
        // 走 View 的 haptic 不用申请 VIBRATE 权限。
        runCatching {
            val fb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            window.decorView.performHapticFeedback(fb)
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SN_CODE, sn))
        finish()
    }

    // ── 相机 ──

    @androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
    private fun startCamera(
        previewView: PreviewView,
        onReady: () -> Unit,
        onError: () -> Unit
    ) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(decoderExecutor) { imageProxy ->
                    if (done || decoding) { imageProxy.close(); return@setAnalyzer }
                    val plane = imageProxy.planes.firstOrNull()
                    if (plane == null) { imageProxy.close(); return@setAnalyzer }
                    decoding = true
                    try {
                        val buffer = plane.buffer
                        val y = ByteArray(buffer.remaining())
                        buffer.get(y)
                        val sn = QrDecoder.decodeCameraFrame(
                            y = y,
                            width = imageProxy.width,
                            height = imageProxy.height,
                            rowStride = plane.rowStride,
                            rotationDegrees = imageProxy.imageInfo.rotationDegrees
                        )
                        if (sn != null) {
                            runOnUiThread { finishWithSn(sn) }
                        }
                    } catch (_: Exception) {
                        // 单帧解码失败不是错误，下一帧继续
                    } finally {
                        decoding = false
                        imageProxy.close()
                    }
                }
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
                onReady()
            } catch (_: Exception) {
                // 以前这里是 `catch (_: Exception) {}` 一吞了事，
                // 失败就是永久黑屏，用户完全不知道发生了什么
                onError()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun hasTorch(): Boolean =
        runCatching { camera?.cameraInfo?.hasFlashUnit() == true }.getOrDefault(false)

    private fun setTorch(on: Boolean) {
        runCatching { camera?.cameraControl?.enableTorch(on) }
    }

    /**
     * 识别相册里的图。
     *
     * 读图 + 解码都是**阻塞 IO/CPU**，必须离开主线程，否则相册里选一张大图会把界面卡住。
     *
     * ⚠️ 换 ZXing 后要**自己处理 EXIF 旋转**：相机拍的图很多是横向存储 + `Orientation=6/8`，
     * 不旋转直接解会一直解不出来（ML Kit 的 `InputImage.fromFilePath` 内部替我们做了这一步）。
     */
    private fun analyzeImage(uri: Uri, onMiss: () -> Unit, onError: (String) -> Unit) {
        scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { loadBitmapForDecode(uri) }
                if (bitmap == null) {
                    onError("图片读取失败，请换张图片")
                    return@launch
                }
                val sn = withContext(Dispatchers.Default) {
                    QrDecoder.decodeBitmap(bitmap)
                }
                if (sn != null) finishWithSn(sn) else onMiss()
            } catch (_: Exception) {
                onError("图片读取失败，请换张图片")
            }
        }
    }

    /**
     * 读相册图：按 EXIF 旋转，并把长边限制在 2048 以内（再大对解码没有帮助，只会更慢）。
     *
     * 用框架自带的 `android.media.ExifInterface`（API 24+），不额外引 androidx 依赖。
     */
    private fun loadBitmapForDecode(uri: Uri): Bitmap? {
        val resolver = contentResolver
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        if (degrees == 0) return decoded
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    // ── Compose 界面 ──

    @Composable
    private fun ScanScreen() {
        val context = LocalContext.current

        // PreviewView 提前建好：让 CameraX 拿到的就是 Compose 正在显示的那个实例，
        // 而不是等 AndroidView 的 factory 回调才创建
        val previewView = remember {
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                // ⚠️ 必须 COMPATIBLE（TextureView）。默认的 PERFORMANCE 用 SurfaceView，
                // 它在自己的窗口层上绘制，Compose 画的半透明遮罩和扫描框**盖不住它**，
                // 结果是遮罩看不见、框被相机画面糊掉。
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        }

        var cameraFailed by remember { mutableStateOf(false) }
        var hasTorch by remember { mutableStateOf(false) }
        var torchOn by remember { mutableStateOf(false) }
        var hint by remember { mutableStateOf<String?>(null) }

        var granted by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            )
        }
        var denied by remember { mutableStateOf(false) }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { ok ->
            granted = ok
            denied = !ok
            if (!ok) hint = "请允许相机权限后扫码"
        }

        // 相册选图：Android 13+ 走系统照片选择器，旧版本自动回落，
        // **不需要任何存储权限**
        val pickImage = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            analyzeImage(
                uri,
                onMiss = { hint = "未识别到设备二维码，请换张图片" },
                onError = { hint = it }
            )
        }

        LaunchedEffect(granted) {
            if (granted) {
                startCamera(
                    previewView,
                    onReady = {
                        cameraFailed = false
                        hasTorch = hasTorch()
                    },
                    onError = { cameraFailed = true }
                )
            }
        }

        // 提示几秒后自己消失
        LaunchedEffect(hint) {
            if (hint != null) {
                delay(2600)
                hint = null
            }
        }

        val cameraLive = granted && !cameraFailed

        BoxWithConstraints(Modifier.fillMaxSize().background(ComposeColor.Black)) {
            if (cameraLive) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                ScanOverlay(
                    frameSize = minOf(maxWidth * 0.72f, 280.dp),
                    onBack = { finish() }
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        hint ?: "将二维码放入框内，自动扫描",
                        color = if (hint == null) ComposeColor.White.copy(alpha = 0.75f)
                        else TORCH_YELLOW,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 相册：从本地图片里找二维码。没有存储权限要求，
                        // 选图走系统选择器
                        CircleAction(R.drawable.ic_photo_library, "从相册选择") {
                            pickImage.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }

                        // 没有闪光灯就整个不显示——比点了弹「设备不支持闪光灯」干净。
                        // 位置用一个等宽占位撑住，按钮不会左右乱跳
                        if (hasTorch) {
                            TorchButton(torchOn) {
                                torchOn = !torchOn
                                setTorch(torchOn)
                            }
                        } else {
                            Spacer(Modifier.size(52.dp))
                        }

                        CircleAction(R.drawable.ic_qr_code_scanner, "取消扫码") { finish() }
                    }
                }
            }

            if (!cameraLive) {
                NoticeCard(
                    title = if (!granted) "需要相机权限" else "相机启动失败",
                    desc = when {
                        !granted && denied ->
                            "请允许相机权限后扫码"
                        !granted -> "相机仅用于扫码，不会上传照片"
                        else -> "相机暂不可用，请稍后重试"
                    },
                    actions = buildList {
                        if (!granted) {
                            add("授予权限" to { permissionLauncher.launch(Manifest.permission.CAMERA) })
                            if (denied) {
                                add("去设置" to {
                                    runCatching {
                                        startActivity(
                                            Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.fromParts("package", packageName, null)
                                            )
                                        )
                                    }
                                })
                            }
                        } else {
                            add("重试" to {
                                cameraFailed = false
                                startCamera(
                                    previewView,
                                    onReady = { hasTorch = hasTorch() },
                                    onError = { cameraFailed = true }
                                )
                            })
                        }
                        add("返回" to { finish() })
                    }
                )
            }
        }
    }

    /** 遮罩 + 四角框 + 上下往复的扫动光带 */
    @Composable
    private fun ScanOverlay(frameSize: Dp, onBack: () -> Unit) {
        val transition = rememberInfiniteTransition(label = "scanline")
        val progress by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scanlineProgress"
        )

        Canvas(Modifier.fillMaxSize()) {
            val side = frameSize.toPx()
            val left = (size.width - side) / 2f
            // 居中偏上：底下要留提示文字和三个按钮的位置
            val top = size.height * 0.5f - side * 0.62f
            val right = left + side
            val bottom = top + side

            // 遮罩：四块矩形拼出来，中间镂空。
            // 不用 BlendMode.Clear 挖洞——那要额外建图层，四块矩形更省事也更稳
            val mask = ComposeColor.Black.copy(alpha = 0.62f)
            drawRect(mask, Offset(0f, 0f), Size(size.width, top))
            drawRect(mask, Offset(0f, bottom), Size(size.width, size.height - bottom))
            drawRect(mask, Offset(0f, top), Size(left, side))
            drawRect(mask, Offset(right, top), Size(size.width - right, side))

            // 四角：每条只画两笔，中间留空，不挡二维码
            val corner = side * 0.16f
            val path = Path().apply {
                moveTo(left, top + corner); lineTo(left, top); lineTo(left + corner, top)
                moveTo(right - corner, top); lineTo(right, top); lineTo(right, top + corner)
                moveTo(right, bottom - corner); lineTo(right, bottom); lineTo(right - corner, bottom)
                moveTo(left + corner, bottom); lineTo(left, bottom); lineTo(left, bottom - corner)
            }
            drawPath(path, ComposeColor.White, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))

            // 扫动光带：上下用渐变淡出，比一条实线自然
            val beamY = top + side * progress
            val half = 7.dp.toPx()
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        ComposeColor.Transparent,
                        TORCH_YELLOW.copy(alpha = 0.85f),
                        ComposeColor.Transparent
                    ),
                    startY = beamY - half,
                    endY = beamY + half
                ),
                topLeft = Offset(left + 4.dp.toPx(), beamY - half),
                size = Size(side - 8.dp.toPx(), half * 2)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = ComposeColor.White)
            }
            Text(
                "扫描二维码", color = ComposeColor.White,
                fontSize = 17.sp, fontWeight = FontWeight.Medium
            )
        }
    }

    /**
     * 手电筒按钮：关 = 半透明白底 + 描边图标；开 = 暖黄填充 + 光晕 + 实心图标。
     *
     * 原来是个 `ImageButton`，开灯前后**图标一模一样**、背景全透明，
     * 点了完全看不出有没有生效。
     */
    @Composable
    private fun TorchButton(on: Boolean, onClick: () -> Unit) {
        // ⚠️ 光晕必须用 `drawBehind` 画，**不能**再加一个 64.dp 的兄弟 Box：
        // 那样按钮的外框会从 52 变成 64，整个底部 Row 的高度跟着变，
        // 表现就是「一开灯，按钮被撑高、文字和按钮整体往上跳一下」（真机反馈过）。
        // `drawBehind` 只在边界外绘制，不参与测量，开灯前后布局完全一致。
        Box(
            modifier = Modifier
                .size(52.dp)
                .drawBehind {
                    if (on) {
                        drawCircle(
                            color = TORCH_YELLOW.copy(alpha = 0.22f),
                            radius = size.minDimension / 2f + 6.dp.toPx()
                        )
                    }
                }
                    .clip(CircleShape)
                    .background(if (on) TORCH_YELLOW else ComposeColor.White.copy(alpha = 0.10f))
                    .border(
                        1.dp,
                        if (on) ComposeColor.Transparent else ComposeColor.White.copy(alpha = 0.28f),
                        CircleShape
                    )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(
                    if (on) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight
                ),
                contentDescription = if (on) "关闭手电筒" else "打开手电筒",
                modifier = Modifier.size(22.dp)
            )
        }
    }

    /** 圆形次级按钮（相册、取消） */
    @Composable
    private fun CircleAction(iconRes: Int, contentDesc: String, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(ComposeColor.White.copy(alpha = 0.10f))
                .border(1.dp, ComposeColor.White.copy(alpha = 0.28f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = contentDesc,
                modifier = Modifier.size(22.dp)
            )
        }
    }

    /** 权限被拒 / 相机起不来时的居中说明 */
    @Composable
    private fun NoticeCard(
        title: String,
        desc: String,
        actions: List<Pair<String, () -> Unit>>
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, color = ComposeColor.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(desc, color = ComposeColor.White.copy(alpha = 0.65f), fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { (label, action) ->
                    TextButton(onClick = action) {
                        Text(label, color = TORCH_YELLOW, fontSize = 14.sp)
                    }
                }
            }
        }
    }

    private val TORCH_YELLOW = ComposeColor(0xFFFFC53D)
}
