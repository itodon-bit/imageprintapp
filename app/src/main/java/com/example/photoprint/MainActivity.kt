package com.example.photoprint

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.print.PrintHelper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.io.File
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var cropOverlayView: CropOverlayView
    private lateinit var btnTakePhoto: Button
    private lateinit var btnPrint: Button
    private lateinit var btnOcr: Button
    private lateinit var btnPrintText: Button
    private lateinit var tvHint: TextView
    private lateinit var etRecognizedText: EditText

    // 日本語対応のテキスト認識器(端末上でOCRを実行する)
    private val textRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    // 撮影した写真の一時保存先URI/File
    private var photoUri: Uri? = null
    private var photoFile: File? = null

    // 撮影後に読み込んだ元のBitmap(トリミングの元データ)
    private var originalBitmap: Bitmap? = null

    // カメラアプリを起動して撮影結果を受け取るランチャー
    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) {
                onPhotoCaptured()
            } else {
                Toast.makeText(this, "撮影がキャンセルされました", Toast.LENGTH_SHORT).show()
            }
        }

    // カメラ権限のリクエスト
    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchCamera()
            } else {
                Toast.makeText(this, "カメラ権限が必要です", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        cropOverlayView = findViewById(R.id.cropOverlayView)
        btnTakePhoto = findViewById(R.id.btnTakePhoto)
        btnPrint = findViewById(R.id.btnPrint)
        btnOcr = findViewById(R.id.btnOcr)
        btnPrintText = findViewById(R.id.btnPrintText)
        tvHint = findViewById(R.id.tvHint)
        etRecognizedText = findViewById(R.id.etRecognizedText)

        btnTakePhoto.setOnClickListener { checkPermissionAndLaunchCamera() }
        btnPrint.setOnClickListener { printSelectedArea() }
        btnOcr.setOnClickListener { recognizeTextInSelection() }
        btnPrintText.setOnClickListener { printRecognizedText() }
    }

    private fun checkPermissionAndLaunchCamera() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            launchCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        // 撮影データの保存先を用意(FileProvider経由でカメラアプリに渡す)
        val imagesDir = File(getExternalFilesDir(null), "images").apply { mkdirs() }
        val file = File(imagesDir, "captured_${System.currentTimeMillis()}.jpg")
        photoFile = file
        photoUri = FileProvider.getUriForFile(
            this, "${packageName}.fileprovider", file
        )
        photoUri?.let { takePictureLauncher.launch(it) }
    }

    private fun onPhotoCaptured() {
        val file = photoFile ?: return

        // 大きな画像でメモリを圧迫しないよう、必要に応じて縮小して読み込む
        val bitmap = decodeSampledBitmap(file, 3072, 3072)
        originalBitmap = bitmap

        imageView.setImageBitmap(bitmap)
        // ImageViewのレイアウト確定後にimageMatrixが正しく計算されるようpostする
        imageView.post {
            cropOverlayView.reset()
        }
        btnPrint.isEnabled = true
        btnOcr.isEnabled = true
        btnPrintText.isEnabled = false
        etRecognizedText.setText("")
        tvHint.text = "指でドラッグして印刷したい範囲を選択してください"
    }

    /** 画像を必要サイズまで縮小して読み込む(OutOfMemory対策)。EXIFの回転情報も適用する */
    private fun decodeSampledBitmap(file: File, reqWidth: Int, reqHeight: Int): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)

        var inSampleSize = 1
        val (height, width) = options.outHeight to options.outWidth
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        val finalOptions = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, finalOptions)
            ?: throw IllegalStateException("画像の読み込みに失敗しました")

        return rotateBitmapToMatchExif(decoded, file)
    }

    /**
     * カメラアプリはピクセルデータを横向きのまま保存し、正しい向きをEXIFのOrientationタグにだけ
     * 記録することが多い。そのため撮影時に見た向きと表示が一致するよう、ここで実ピクセルを回転させる。
     */
    private fun rotateBitmapToMatchExif(bitmap: Bitmap, file: File): Bitmap {
        val orientation = try {
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val rotationDegrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotationDegrees == 0f) return bitmap

        val matrix = Matrix().apply { postRotate(rotationDegrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** CropOverlayViewの選択範囲(View座標)をBitmap上の座標に変換してトリミングする */
    private fun cropSelectedArea(): Bitmap? {
        val bitmap = originalBitmap ?: return null
        val viewRect = cropOverlayView.getSelectionRect() ?: return bitmap // 未選択なら全体を対象にする

        // ImageViewのimageMatrixは「Bitmap座標 -> View座標」の変換なので、逆行列で戻す
        val matrix = Matrix()
        imageView.imageMatrix.invert(matrix)

        val bitmapRectF = RectF(viewRect)
        matrix.mapRect(bitmapRectF)

        val left = max(0, bitmapRectF.left.toInt())
        val top = max(0, bitmapRectF.top.toInt())
        val right = min(bitmap.width, bitmapRectF.right.toInt())
        val bottom = min(bitmap.height, bitmapRectF.bottom.toInt())

        if (right <= left || bottom <= top) {
            Toast.makeText(this, "選択範囲が不正です", Toast.LENGTH_SHORT).show()
            return null
        }

        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun printSelectedArea() {
        val cropped = cropSelectedArea() ?: return

        // androidx.print の PrintHelper を使うと、システムの印刷ダイアログが開き
        // Wi-Fi/無線接続されたプリンタ(Mopria対応機種やメーカー純正Print Service経由)を選択できる
        val printHelper = PrintHelper(this).apply {
            scaleMode = PrintHelper.SCALE_MODE_FIT
        }

        try {
            printHelper.printBitmap("selected_photo_" + System.currentTimeMillis(), cropped)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "印刷を開始できませんでした。プリンタの印刷サービス(例: Mopria Print Service)が" +
                    "インストール・有効化されているか確認してください。",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * OCRの認識精度を上げるための前処理。
     * 1) 選択範囲が小さい場合は文字の解像度を確保するため拡大する
     * 2) グレースケール化 + コントラスト強調で文字と背景の境界をくっきりさせる
     */
    private fun preprocessForOcr(bitmap: Bitmap): Bitmap {
        val minHeightForOcr = 600
        val scale = if (bitmap.height < minHeightForOcr) {
            minHeightForOcr.toFloat() / bitmap.height
        } else 1f

        val scaled = if (scale > 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        } else bitmap

        val result = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // コントラストを強めつつ、彩度を落としてグレースケールに近づける
        val contrast = 1.5f
        val brightness = -40f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val grayscaleMatrix = ColorMatrix().apply { setSaturation(0f) }
        grayscaleMatrix.postConcat(contrastMatrix)

        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(grayscaleMatrix) }
        canvas.drawBitmap(scaled, 0f, 0f, paint)

        return result
    }

    /** 選択範囲を切り出し、その画像に対してOCR(文字認識)を実行する */
    private fun recognizeTextInSelection() {
        val cropped = cropSelectedArea() ?: return
        val processed = preprocessForOcr(cropped)
        val inputImage = InputImage.fromBitmap(processed, 0)

        btnOcr.isEnabled = false
        tvHint.text = "文字を認識しています…"

        textRecognizer.process(inputImage)
            .addOnSuccessListener { result ->
                btnOcr.isEnabled = true
                val recognized = result.text
                if (recognized.isBlank()) {
                    tvHint.text = "文字を認識できませんでした。範囲を調整して再度お試しください"
                    btnPrintText.isEnabled = false
                } else {
                    etRecognizedText.setText(recognized)
                    btnPrintText.isEnabled = true
                    tvHint.text = "認識結果を確認・修正してから印刷してください"
                }
            }
            .addOnFailureListener { e ->
                btnOcr.isEnabled = true
                tvHint.text = "文字認識に失敗しました"
                Toast.makeText(
                    this,
                    "文字認識でエラーが発生しました: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    /** 認識(または編集)されたテキストを1枚の紙のレイアウトに描画し、印刷する */
    private fun printRecognizedText() {
        val text = etRecognizedText.text?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "印刷するテキストがありません", Toast.LENGTH_SHORT).show()
            return
        }

        val textBitmap = createTextBitmap(text)
        val printHelper = PrintHelper(this).apply {
            scaleMode = PrintHelper.SCALE_MODE_FIT
        }

        try {
            printHelper.printBitmap("recognized_text_" + System.currentTimeMillis(), textBitmap)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "印刷を開始できませんでした。プリンタの印刷サービスが有効になっているか確認してください。",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** テキストをA4相当の白紙レイアウトに描画したBitmapを生成する */
    private fun createTextBitmap(text: String): Bitmap {
        val pageWidth = 1240 // 約A4サイズ相当(150dpi換算)の幅
        val padding = 60

        val textPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 32f
            isAntiAlias = true
        }

        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, pageWidth - padding * 2)
            .setLineSpacing(0f, 1.3f)
            .build()

        val pageHeight = max(layout.height + padding * 2, 800)
        val bitmap = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.translate(padding.toFloat(), padding.toFloat())
        layout.draw(canvas)
        canvas.restore()

        return bitmap
    }
}
