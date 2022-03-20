package ui.smartpro.ctrl2go

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
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
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.common.Cancelable
import com.mapbox.common.TileRegionLoadOptions
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.Image
import com.mapbox.maps.extension.style.expressions.dsl.generated.interpolate
import com.mapbox.maps.extension.style.image.image
import com.mapbox.maps.extension.style.layers.generated.symbolLayer
import com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.getSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.extension.style.style
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.gestures.OnMoveListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorBearingChangedListener
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.search.*
import com.mapbox.search.Country.Companion.RUSSIA
import com.mapbox.search.result.SearchResult
import com.mapbox.search.result.SearchSuggestion
import ui.smartpro.ctrl2go.databinding.ActivityMainBinding
import ui.smartpro.ctrl2go.utils.DataConstants.Companion.searchObject
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
    private lateinit var searchEngine: OfflineSearchEngine
    private lateinit var tilesLoadingTask: Cancelable
    private var categorySearchEngine: CategorySearchEngine? = null
    private var searchRequestTask: SearchRequestTask? = null
    private var markerCoordinates = mutableListOf<Point>()
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

    val categorySearchCallback = object : SearchCallback {
        override fun onError(e: Exception) {
            Log.i("SearchApiExample", "Search error", e)
        }

        override fun onResults(results: List<SearchResult>, responseInfo: ResponseInfo) {
            if (results.isEmpty()) {
                Log.i("SearchApiExample", "No category search results")
            } else {
                Log.i("SearchApiExample", "Category search results: $results")
                searchObject?.results = results
                showMarkers(results.mapNotNull { it.coordinate })
            }
        }
    }

    private val engineReadyCallback = object : OfflineSearchEngine.EngineReadyCallback {
        override fun onEngineReady() {
            Log.i("SearchApiExample", "Engine is ready")
        }

        override fun onError(e: Exception) {
            Log.i("SearchApiExample", "Error during engine initialization", e)
        }
    }

    private val searchCallback = object : SearchSelectionCallback {

        override fun onSuggestions(suggestions: List<SearchSuggestion>, responseInfo: ResponseInfo) {
            if (suggestions.isEmpty()) {
                Log.i("SearchApiExample", "No suggestions found")
            } else {
                Log.i("SearchApiExample", "Search suggestions: $suggestions.\nSelecting first suggestion...")
                searchRequestTask = searchEngine.select(suggestions.first(), this)
            }
        }

        override fun onResult(
            suggestion: SearchSuggestion,
            result: SearchResult,
            responseInfo: ResponseInfo
        ) {
            Log.i("SearchApiExample", "Search result: $result")
        }

        override fun onCategoryResult(
            suggestion: SearchSuggestion,
            results: List<SearchResult>,
            responseInfo: ResponseInfo
        ) {
            Log.i("SearchApiExample", "Category search results: $results")
        }

        override fun onError(e: Exception) {
            Log.i("SearchApiExample", "Search error", e)
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
            categorySearch()
            offLineSearch()
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
//        if (locationlat != null && locationlong != null)
//            binding.mapView.postDelayed(2000) {
//                marker()
////                markers()
//            }
    }

    private fun offLineSearch() {
        searchEngine = MapboxSearchSdk.getOfflineSearchEngine()
        searchEngine.addEngineReadyCallback(engineReadyCallback)

        val tileStore = searchEngine.tileStore

        val dcLocation = locationlat?.let { locationlong?.let { it1 -> Point.fromLngLat(it1, it) } }

        val descriptors = listOf(searchEngine.createTilesetDescriptor())

        val tileRegionLoadOptions = TileRegionLoadOptions.Builder()
            .descriptors(descriptors)
            .geometry(dcLocation)
            .acceptExpired(true)
            .build()

        Log.i("SearchApiExample", "Loading tiles...")

        tilesLoadingTask = tileStore.loadTileRegion(
            "Novosibirsk",
            tileRegionLoadOptions,
            { progress -> Log.i("SearchApiExample", "Loading progress: $progress") },
            { region ->
                if (region.isValue) {
                    Log.i("SearchApiExample", "Tiles successfully loaded")
                    searchRequestTask = searchEngine.search(
                        "Cafe",
                        OfflineSearchOptions(),
                        searchCallback
                    )
                } else {
                    Log.i("SearchApiExample", "Tiles loading error: ${region.error}")
                }
            }
        )
    }

    private fun categorySearch() {
        categorySearchEngine = MapboxSearchSdk.getCategorySearchEngine()
        val options: CategorySearchOptions = CategorySearchOptions.Builder()
            .countries(RUSSIA)
            .limit(20)
            .build()
        searchRequestTask = categorySearchEngine!!.search(
            "bar",
            options,
            categorySearchCallback
        )
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
                .zoom(8.0)
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
                showMarkers(markerCoordinates)
//                binding.mapView.postDelayed(2000) {
//                    marker()
                    if (markerCoordinates.isNotEmpty()) {
                        for (register in markerCoordinates.indices) {
                            markers(register)
//                            marker2(register)
                        }
                    }
//                }
            }
        }
    }

    private fun marker2(register: Int) {
        binding.mapView.getMapboxMap()
//            .also {
//            it.setCamera(
//                CameraOptions.Builder()
//                    .center(Point.fromLngLat(locationlong!!, locationlat!!))
//                    .zoom(18.0)
//                    .build()
//            )
//        }
            .loadStyle(
            styleExtension = style(com.mapbox.maps.Style.MAPBOX_STREETS) {
                +image(RED_ICON_ID) {
                    bitmap(BitmapFactory.decodeResource(resources, R.drawable.marker))
                }
                +geoJsonSource(SOURCE_ID) {
                    geometry(Point.fromLngLat(markerCoordinates[register].longitude(),
                        markerCoordinates[register].latitude()))
                }
                +symbolLayer(LAYER_ID, SOURCE_ID) {
                    iconImage(RED_ICON_ID)
                    iconAnchor(IconAnchor.BOTTOM)
                }
            }
        )
    }

//    private fun marker() {
//        binding.mapView.getMapboxMap().also {
//            it.setCamera(
//                CameraOptions.Builder()
//                    .center(Point.fromLngLat(locationlong!!, locationlat!!))
//                    .zoom(18.0)
//                    .build()
//            )
//        }.loadStyle(
//            styleExtension = style(com.mapbox.maps.Style.MAPBOX_STREETS) {
//                +image(RED_ICON_ID) {
//                    bitmap(BitmapFactory.decodeResource(resources, R.drawable.red_marker))
//                }
//                +geoJsonSource(SOURCE_ID) {
//                    geometry(Point.fromLngLat(locationlat!!, locationlong!!))
//                }
//                +symbolLayer(LAYER_ID, SOURCE_ID) {
//                    iconImage(RED_ICON_ID)
//                    iconAnchor(IconAnchor.BOTTOM)
//                }
//            }
//        )
//    }

    private fun markers(register: Int) {
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
                    featureCollection(
                        FeatureCollection.fromFeatures(
                            arrayOf(
                                Feature.fromGeometry(
                                Point.fromLngLat(locationlat!!, locationlong!!)),
                            Feature.fromGeometry(
                                    Point.fromLngLat(
                                        markerCoordinates[register].longitude(),
                                        markerCoordinates[register].latitude()
                                    )
                                )
                            )
                        )
                    )
                }
                +symbolLayer(LAYER_ID, SOURCE_ID) {
                    iconImage(RED_ICON_ID)
                    iconAnchor(IconAnchor.BOTTOM)
                }
            }
        )
    }

    private fun showMarkers(coordinates: List<Point>) {
        if (coordinates.isEmpty()) {
            clearMarkers()
            return
        } else if (coordinates.size == 1) {
            showMarker(coordinates.first())
            return
        }

        val cameraOptions = binding.mapView.getMapboxMap()
            .cameraForCoordinates(
            coordinates, markersPaddings, bearing = null, pitch = null
        )

        if (cameraOptions.center == null) {
            clearMarkers()
            return
        }

        showMarkers2(cameraOptions, coordinates)
    }

    private fun showMarker(coordinate: Point) {
        val cameraOptions = CameraOptions.Builder()
            .center(coordinate)
            .zoom(10.0)
            .build()

        showMarkers2(cameraOptions, listOf(coordinate))
    }

    private fun showMarkers2(cameraOptions: CameraOptions, coordinates: List<Point>) {
        markerCoordinates.clear()
        markerCoordinates.addAll(coordinates)
        updateMarkersOnMap()

        binding.mapView.getMapboxMap().setCamera(cameraOptions)
    }

    private fun updateMarkersOnMap() {
        binding.mapView.getMapboxMap().getStyle()?.
             addImage(RED_ICON_ID,BitmapFactory.decodeResource(resources, R.drawable.red_marker))
        binding.mapView.getMapboxMap().getStyle()?.
                getSourceAs<GeoJsonSource>(SOURCE_ID)?.featureCollection(
            FeatureCollection.fromFeatures(
                markerCoordinates.map {
                    Feature.fromGeometry(it)
                }
            )
        )
    }

    private fun clearMarkers() {
        markerCoordinates.clear()
        updateMarkersOnMap()
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
        searchRequestTask!!.cancel()
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

        val markersPaddings: EdgeInsets = dpToPx(100).toDouble()
            .let { mapPadding ->
                EdgeInsets(mapPadding, mapPadding, mapPadding, mapPadding)
            }
        fun dpToPx(dp: Int): Int {
            return (dp * Resources.getSystem().displayMetrics.density).toInt()
        }
    }
}

