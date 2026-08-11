package com.yukarlo.unlockmymac.ui.pairing

import android.annotation.SuppressLint
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Camera preview that reports the first QR code it reads.
 *
 * [onQrCode] can fire repeatedly while the code stays in frame; the caller is responsible for
 * ignoring codes once it has acted on one.
 */
@Composable
fun QrScannerView(
    onQrCode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnQrCode by rememberUpdatedState(onQrCode)

    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        providerFuture.addListener({
            val cameraProvider = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener
            provider = cameraProvider

            val preview =
                Preview
                    .Builder()
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis =
                ImageAnalysis
                    .Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, qrAnalyzer(scanner) { currentOnQrCode(it) }) }

            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }.onFailure { Log.w(TAG, "Could not bind camera", it) }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
            scanner.close()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

@SuppressLint("UnsafeOptInUsageError") // ImageProxy.image is experimental but stable in practice.
private fun qrAnalyzer(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onResult: (String) -> Unit,
) = ImageAnalysis.Analyzer { imageProxy ->
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return@Analyzer
    }
    val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner
        .process(input)
        .addOnSuccessListener { barcodes ->
            barcodes
                .firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                ?.rawValue
                ?.let(onResult)
        }.addOnCompleteListener { imageProxy.close() }
}

private const val TAG = "QrScannerView"
