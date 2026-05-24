package com.example

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Data representation for installed launcher applications.
 * Holding optimized size [ImageBitmap] keeps memory footprint extremely light on 3GB RAM devices.
 */
data class AppInfo(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: ImageBitmap? = null
)

/**
 * VM to load the system apps asynchronously using Kotlin Coroutines + Flow.
 */
class HomeViewModel : ViewModel() {
    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadApps(packageManager: PackageManager, currentPackageName: String) {
        viewModelScope.launch(Dispatchers.Default) {
            _isLoading.value = true
            try {
                val intent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = packageManager.queryIntentActivities(intent, 0)
                val tempList = mutableListOf<AppInfo>()
                for (info in resolveInfos) {
                    val pkgName = info.activityInfo.packageName
                    // Filter out this launcher app itself
                    if (pkgName == currentPackageName) continue

                    val label = info.loadLabel(packageManager).toString()
                    val actName = info.activityInfo.name
                    
                    // Decode icon off the main thread with restricted maximum size (128x128 max ensures extreme memory savings)
                    val iconBitmap = try {
                        val drawable = info.loadIcon(packageManager)
                        val size = 120
                        val bmp = drawable.toBitmap(size, size, Bitmap.Config.ARGB_8888)
                        bmp.asImageBitmap()
                    } catch (e: Exception) {
                        null
                    }
                    tempList.add(AppInfo(label, pkgName, actName, iconBitmap))
                }
                // Sort launchers alphabetically for consistency
                tempList.sortBy { it.label.lowercase() }
                _apps.value = tempList
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to query app list", e)
            } finally {
                _isLoading.value = false
            }
        }
    }
}

/**
 * Primary activity for the Custom Android Launcher.
 * Set as MAIN and HOME launcher category inside AndroidManifest.xml.
 */
class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Safely start background persistence service right when launching home activity
        val serviceIntent = Intent(this, LauncherService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("HomeActivity", "Failed to boot foreground services", e)
        }

        setContent {
            MyApplicationTheme {
                val viewModel: HomeViewModel = viewModel()
                val context = LocalContext.current
                val packageManager = context.packageManager

                // Query apps off-thread when launching Activity
                LaunchedEffect(Unit) {
                    viewModel.loadApps(packageManager, context.packageName)
                }

                LauncherHomeScreen(
                    viewModel = viewModel,
                    onAppClick = { app ->
                        try {
                            val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
                            if (launchIntent != null) {
                                startActivity(launchIntent)
                            } else {
                                val intent = Intent().apply {
                                    setClassName(app.packageName, app.activityName)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(intent)
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "Cannot open ${app.label}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onSettingsClick = {
                        val settingsIntent = Intent(context, SettingsActivity::class.java)
                        startActivity(settingsIntent)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherHomeScreen(
    viewModel: HomeViewModel,
    onAppClick: (AppInfo) -> Unit,
    onSettingsClick: () -> Unit
) {
    val context = LocalContext.current
    val allApps by viewModel.apps.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    // 1. Separate dock candidates from page apps dynamically
    val dockApps = remember(allApps) {
        val selectedDock = mutableListOf<AppInfo>()
        val categories = listOf("dial", "phone", "messag", "sms", "mms", "browser", "chrome", "camera")
        
        // Match standard utility packages for dock slotting
        val matched = allApps.filter { app ->
            categories.any { cat -> 
                app.packageName.lowercase().contains(cat) || app.label.lowercase().contains(cat) 
            }
        }.take(4)
        
        selectedDock.addAll(matched)
        if (selectedDock.size < 4) {
            val fill = allApps.filter { it !in selectedDock }.take(4 - selectedDock.size)
            selectedDock.addAll(fill)
        }
        selectedDock.take(4)
    }

    val pageApps = remember(allApps, dockApps) {
        allApps.filter { it !in dockApps }
    }

    val rows = 5
    val columns = 4
    val appsPerPage = rows * columns
    val realPageCount = remember(pageApps) {
        if (pageApps.isEmpty()) 1 else (pageApps.size + appsPerPage - 1) / appsPerPage
    }

    // Configure loop-based infinite scroll centered safely in middle section
    val pagerState = rememberPagerState(
        initialPage = if (realPageCount > 1) (1000 * realPageCount) else 0,
        pageCount = { if (realPageCount > 1) 2000 * realPageCount else 1 }
    )

    // Layout uses transparent Box to overlay system wallpaper perfectly
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Proactive very light dark gradient overlay provides excellent legibility for icons across white or dark wallpapers
            .background(Color.Black.copy(alpha = 0.12f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            // Header panel with Launcher Title + Settings Gear trigger
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Foundation",
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.9f),
                            blurRadius = 6f
                        )
                    )
                )
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f))
                        .semantics { testTag = "settings_gear" }
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "System Launcher Settings Icon",
                        tint = Color.White
                    )
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .weight(1.0f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            } else {
                // Paginated Application Grid
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1.0f)
                        .fillMaxWidth()
                ) { fakePage ->
                    val actualPage = if (realPageCount > 0) fakePage % realPageCount else 0
                    val startingIndex = actualPage * appsPerPage
                    
                    AppGridPage(
                        apps = pageApps,
                        startIndex = startingIndex,
                        columns = columns,
                        rows = rows,
                        onAppClick = onAppClick
                    )
                }

                // Page Indicator Dots
                if (realPageCount > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val activePage = pagerState.currentPage % realPageCount
                        for (i in 0 until realPageCount) {
                            val isActive = i == activePage
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .size(if (isActive) 8.dp else 5.dp)
                                    .clip(CircleShape)
                                    .background(if (isActive) Color.White else Color.White.copy(alpha = 0.4f))
                            )
                        }
                    }
                }
            }

            // Bottom Dock
            BottomDock(
                dockApps = dockApps,
                onAppClick = onAppClick
            )
        }
    }
}

@Composable
fun AppGridPage(
    apps: List<AppInfo>,
    startIndex: Int,
    columns: Int,
    rows: Int,
    onAppClick: (AppInfo) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        for (r in 0 until rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (c in 0 until columns) {
                    val index = startIndex + (r * columns) + c
                    if (index < apps.size) {
                        val app = apps[index]
                        AppCell(
                            app = app,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { testTag = "app_item_${app.packageName}" },
                            onClick = { onAppClick(app) }
                        )
                    } else {
                        // Keep grid placeholders symmetrical
                        Box(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun AppCell(
    app: AppInfo,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (app.icon != null) {
            Image(
                bitmap = app.icon,
                contentDescription = "${app.label} app icon",
                modifier = Modifier
                    .size(52.dp)
                    .padding(4.dp)
            )
        } else {
            // High fidelity modern circle fallback with visual initials
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF455A64)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = app.label.take(1).uppercase(),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = app.label,
            color = Color.White,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            style = MaterialTheme.typography.labelMedium.copy(
                shadow = Shadow(
                    color = Color.Black,
                    blurRadius = 5f
                )
            )
        )
    }
}

@Composable
fun BottomDock(
    dockApps: List<AppInfo>,
    onAppClick: (AppInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // High quality glassmorphic style horizontal divider
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.16f))
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0 until 4) {
                if (i < dockApps.size) {
                    val app = dockApps[i]
                    AppCell(
                        app = app,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { testTag = "dock_item_${app.packageName}" },
                        onClick = { onAppClick(app) }
                    )
                } else {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
