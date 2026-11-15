package app.winnow.gallery

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import app.winnow.gallery.ui.WinnowApp

class MainActivity : ComponentActivity() {
    val vm: WinnowViewModel by viewModels()

    private val wanted: Array<String>
        get() = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun granted(): Boolean = wanted.any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.onAccess(granted()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { WinnowApp(vm) { ask.launch(wanted) } }
    }

    override fun onStart() {
        super.onStart()
        val g = granted()
        if (g != vm.hasAccess) vm.onAccess(g) else if (g) vm.refresh()
    }
}
