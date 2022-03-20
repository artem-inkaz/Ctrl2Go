package ui.smartpro.ctrl2go

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.postDelayed
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.extension.style.expressions.dsl.generated.interpolate
import com.mapbox.maps.extension.style.image.image
import com.mapbox.maps.extension.style.layers.generated.symbolLayer
import com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.style
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.gestures.OnMoveListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorBearingChangedListener
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.locationcomponent.location
import ui.smartpro.ctrl2go.databinding.ActivityMainBinding
import java.lang.ref.WeakReference

class MainActivity : AppCompatActivity() {

    lateinit var binding: ActivityMainBinding
    private lateinit var locationPermissionHelper: LocationPermissionHelper

    // MAPBOX
    private lateinit var manager: LocationManager
    private val styleUrl = "mapbox://styles/artem-inkaz/cl0y30p9o003h15o83qrby4lh"

    var locationlat: Double? = null
    var locationlong: Double? = null

    var locationAustrlan = -25.0
    var locationAustrlong = 135.0

    private val permReqLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions.entries.all {
                it.value
            }
            if (granted) {
                getMapData()
            } else {
                checkGPSPermission()
            }
        }

    private val onIndicatorBearingChangedListener = OnIndicatorBearingChangedListener {
        binding.mapView.getMapboxMap().setCamera(CameraOptions.Builder().bearing(it).build())
    }

    private val onIndicatorPositionChangedListener = OnIndicatorPositionChangedListener {
        binding.mapView.getMapboxMap().setCamera(CameraOptions.Builder().center(it).build())
        binding.mapView.gestures.focalPoint = binding.mapView.getMapboxMap().pixelForCoordinate(it)
        locationlat = it.coordinates()[0]
        locationlong = it.coordinates()[1]
    }

    private val onMoveListener = object : OnMoveListener {
        override fun onMoveBegin(detector: MoveGestureDetector) {
            onCameraTrackingDismissed()
        }

        override fun onMove(detector: MoveGestureDetector): Boolean {
            return false
        }

        override fun onMoveEnd(detector: MoveGestureDetector) {}
    }

    private fun getMyPosition() {
        binding.btnStartNavigation.setOnClickListener {
            locationPermissionHelper = LocationPermissionHelper(WeakReference(this))
            locationPermissionHelper.checkPermissions {
                onMapReady()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Mapbox.getInstance(this, getString(R.string.mapbox_access_token))
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        manager = this.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        checkGPSPermission()
        getMyPosition()
        if (locationlat != null && locationlong != null)
            binding.mapView.postDelayed(2000) {
                marker()
            }
    }

    private fun getMapData() {
        binding.mapView.getMapboxMap().loadStyleUri(styleUrl)
        binding.mapView.getMapboxMap().setCamera(
            CameraOptions.Builder()
                .center(Point.fromLngLat(locationAustrlong, locationAustrlan))
                .zoom(2.5)
                .build()
        )
    }

    private fun checkGPSPermission() {
        this.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                when {
                    ContextCompat.checkSelfPermission(
                        it,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) ==
                            PackageManager.PERMISSION_GRANTED -> {
                        getMapData()
                    }
                    // Метод для нас, чтобы знали когда необходимы пояснения показывать перед запросом:
                    shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                        alertD(it)
                    }
                    else -> {
                        permReqLauncher.launch(PERMISSIONS)
                    }
                }
            }
        }
    }

    private fun alertD(it: MainActivity) {
        AlertDialog.Builder(it)
            .setTitle("Необходим доступ к GPS")
            .setMessage(
                "Внимание! Для просмотра данных на карте необходимо разрешение на" +
                        "использование Вашего местоположения"
            )
            .setPositiveButton("Предоставить доступ") { _, _ ->
                permReqLauncher.launch(PERMISSIONS)
            }
            .setNegativeButton("Спасибо, не надо") { dialog, _ -> dialog.dismiss() }
            .create()
            .show()
    }

    private fun onMapReady() {
        binding.mapView.getMapboxMap().setCamera(
            CameraOptions.Builder()
                .zoom(18.0)
                .build()
        )
        binding.mapView.getMapboxMap().loadStyleUri(
            Style.MAPBOX_STREETS,
        ) {
            initLocationComponent()
            setupGesturesListener()
        }
        binding.mapView.location.addOnIndicatorPositionChangedListener {
            binding.latitudetv.visibility = View.VISIBLE
            binding.longitudetv.visibility = View.VISIBLE
            binding.latitudetv.text = String.format(
                resources.getString(R.string.current_latitude),
                locationlat.toString()
            )
            binding.longitudetv.text = String.format(
                resources.getString(R.string.current_longitude),
                locationlong.toString()
            )
            if (locationlat != null && locationlong != null) {
                marker()
            }
        }
    }

    private fun marker() {
        binding.mapView.getMapboxMap().also {
            it.setCamera(
                CameraOptions.Builder()
                    .center(Point.fromLngLat(locationlong!!, locationlat!!))
                    .zoom(18.0)
                    .build()
            )
        }.loadStyle(
            styleExtension = style(com.mapbox.maps.Style.MAPBOX_STREETS) {
                +image(RED_ICON_ID) {
                    bitmap(BitmapFactory.decodeResource(resources, R.drawable.red_marker))
                }
                +geoJsonSource(SOURCE_ID) {
                    geometry(Point.fromLngLat(locationlat!!, locationlong!!))
                }
                +symbolLayer(LAYER_ID, SOURCE_ID) {
                    iconImage(RED_ICON_ID)
                    iconAnchor(IconAnchor.BOTTOM)
                }
            }
        )
    }

    private fun setupGesturesListener() {
        binding.mapView.gestures.addOnMoveListener(onMoveListener)
    }

    private fun initLocationComponent() {
        val locationComponentPlugin = binding.mapView.location
        locationComponentPlugin.updateSettings {
            this.enabled = true
            this.locationPuck = LocationPuck2D(
                bearingImage = AppCompatResources.getDrawable(
                    this@MainActivity,
                    R.drawable.mapbox_user_puck_icon,
                ),
                shadowImage = AppCompatResources.getDrawable(
                    this@MainActivity,
                    R.drawable.mapbox_user_icon_shadow,
                ),
                scaleExpression = interpolate {
                    linear()
                    zoom()
                    stop {
                        literal(0.0)
                        literal(0.6)
                    }
                    stop {
                        literal(20.0)
                        literal(1.0)
                    }
                }.toJson()
            )
        }
        locationComponentPlugin.addOnIndicatorPositionChangedListener(
            onIndicatorPositionChangedListener
        )
        locationComponentPlugin.addOnIndicatorBearingChangedListener(
            onIndicatorBearingChangedListener
        )
    }

    private fun onCameraTrackingDismissed() {
        Toast.makeText(this, "onCameraTrackingDismissed", Toast.LENGTH_SHORT).show()
        binding.mapView.location
            .removeOnIndicatorPositionChangedListener(onIndicatorPositionChangedListener)
        binding.mapView.location
            .removeOnIndicatorBearingChangedListener(onIndicatorBearingChangedListener)
        binding.mapView.gestures.removeOnMoveListener(onMoveListener)
    }

    @SuppressWarnings("MissingPermission")
    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
        Log.d("State", "onStart()")
    }

    override fun onStop() {
        super.onStop()
        binding.mapView.onStop()
        Log.d("State", "onStop()")
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.mapView.onDestroy()
        binding.mapView.location
            .removeOnIndicatorBearingChangedListener(onIndicatorBearingChangedListener)
        binding.mapView.location
            .removeOnIndicatorPositionChangedListener(onIndicatorPositionChangedListener)
        binding.mapView.gestures.removeOnMoveListener(onMoveListener)
        Log.d("State", "onDestroy()")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
        Log.d("State", "onLowMemory()")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        locationPermissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    companion object {
        var PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        private const val RED_ICON_ID = "red"
        private const val SOURCE_ID = "source_id"
        private const val LAYER_ID = "layer_id"
    }
}

