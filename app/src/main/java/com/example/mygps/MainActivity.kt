package com.example.mygps

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mygps.databinding.ActivityMainBinding
import com.example.mygps.databinding.DialogSearchBinding
import com.example.mygps.databinding.ItemSearchResultBinding
import com.example.mygps.databinding.ItemSavedLocationBinding
import com.example.mygps.databinding.SheetSavedLocationsBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/**
 * Single-screen entry point.
 *
 * Layout matches the reference "Fake GPS" app:
 *   Toolbar (hamburger | title | play | stop | filter | more)
 *   + osmdroid MapView filling the body
 *   + big round play button bottom-right
 *   + Lat/Lng overlay bottom-center
 *   + status banner above the overlay
 *   + NavigationView drawer with the same menu items
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val DEFAULT_LAT = 13.7563
        private const val DEFAULT_LNG = 100.5018
        private const val PREF_KEY_LAST_LAT = "last_lat"
        private const val PREF_KEY_LAST_LNG = "last_lng"
        private const val PREF_KEY_LAST_ACC = "last_accuracy"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: MockLocationEngine
    private lateinit var savedLocations: SavedLocations
    private val nominatim = NominatimService()

    private var marker: Marker? = null
    private var searchJob: Job? = null

    // ---- permission launcher ----
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startMockFromUi()
            } else {
                showPermissionDeniedHelp()
            }
        }

    private val drawer: DrawerLayout get() = binding.drawerLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid requires a User-Agent per OSM tile usage policy:
// https://operations.osmfoundation.org/policies/tiles/
// The UA must identify the application AND provide a way to contact the developer.
// Using just the package name ("com.example.mygps") gets blocked with 403.
        Configuration.getInstance().apply {
            userAgentValue = "SP/1.0 (https://github.com/mixvivo18-ai/mygps; contact via repo)"
            load(applicationContext, PreferenceManager.getDefaultSharedPreferences(applicationContext))
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = MockLocationEngine(applicationContext)
        savedLocations = SavedLocations(applicationContext)

        setupToolbar()
        setupMap()
        setupPlayButton()
        setupToolbarActions()
        setupDrawerItems()
        observeEngineState()

        refreshLatLngOverlay()
        updateStatusBanner()
    }

    // ---------------- setup ----------------

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        binding.toolbar.inflateMenu(R.menu.main_menu)
        binding.toolbar.setNavigationOnClickListener { drawer.openDrawer(GravityCompat.START) }
    }

    private fun setupMap() {
        binding.mapView.apply {
            // CartoDB Voyager — entirely separate infrastructure from OSM and
            // Esri. Uses Cloudflare CDN. Free, no API key required, no UA
            // restrictions on tile downloads.
            //
            // Tiles we've tried and got blocked on the user's device:
            //   - openstreetmap.org (main)
            //   - tile.openstreetmap.de (mirror)
            //   - maps.wikimedia.org (Wikimedia CDN)
            //   - server.arcgisonline.com (Esri - tried in v1.0.6)
            //
            // If CartoDB also fails, the next step would be to bundle MBTiles
            // inside the APK so the map works offline by default.
            val cartoTiles = XYTileSource(
                "CartoDB Voyager",
                0, 19, 256, ".png",
                arrayOf("https://basemaps.cartocdn.com/rastertiles/voyager/"),
                "© OpenStreetMap contributors © CARTO"
            )
            setTileSource(cartoTiles)
            setMultiTouchControls(true)
            controller.setZoom(14.0)
            val start = GeoPoint(DEFAULT_LAT, DEFAULT_LNG)
            controller.setCenter(start)
            addOnFirstLayoutListener { _, _, _, _, _ ->
                placeMarkerAt(start, animate = false)
            }
            // Long-press to pick a new location
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean = false
                override fun longPressHelper(p: GeoPoint): Boolean {
                    placeMarkerAt(p, animate = true)
                    maybeUpdateRunningLocation(p.latitude, p.longitude)
                    return true
                }
            }))
        }
    }

    private fun setupPlayButton() {
        binding.btnPlay.setOnClickListener {
            val running = MockLocationService.running.value
            if (running) {
                MockLocationService.stop(applicationContext)
                Toast.makeText(this, R.string.mock_stopped, Toast.LENGTH_SHORT).show()
            } else {
                ensureLocationPermissionThenStart()
            }
        }
    }

    private fun setupToolbarActions() {
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_play -> {
                    if (!MockLocationService.running.value) ensureLocationPermissionThenStart()
                    true
                }
                R.id.action_stop -> {
                    if (MockLocationService.running.value) {
                        MockLocationService.stop(applicationContext)
                        Toast.makeText(this, R.string.mock_stopped, Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                R.id.action_offline -> {
                    downloadOfflineCache()
                    true
                }
                R.id.action_filter -> {
                    showSavedLocationsSheet()
                    true
                }
                R.id.action_more -> {
                    drawer.openDrawer(GravityCompat.START)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupDrawerItems() {
        binding.navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_change_view -> showChangeViewDialog()
                R.id.nav_search -> showSearchDialog()
                R.id.nav_share -> shareCurrentLocation()
                R.id.nav_rate -> openUrl("https://play.google.com/store/apps/details?id=$packageName")
                R.id.nav_go_pro -> Toast.makeText(this, "SP Pro — coming soon", Toast.LENGTH_SHORT).show()
                R.id.nav_privacy -> openUrl("https://example.com/privacy")
                R.id.nav_detect_mock -> showDetectMockHelp()
                R.id.nav_dev_settings -> openDeveloperOptions()
                R.id.nav_help -> showHelp()
            }
            drawer.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun observeEngineState() {
        lifecycleScope.launch {
            MockLocationService.running.collect { running ->
                if (running) {
                    val pt = marker?.position
                    binding.btnPlay.setImageResource(R.drawable.ic_stop)
                    binding.btnPlay.contentDescription = getString(R.string.btn_stop_mock)
                    binding.tvStatus.visibility = View.VISIBLE
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_ok))
                    binding.tvStatus.text = getString(R.string.status_running, pt?.latitude ?: 0.0, pt?.longitude ?: 0.0)
                } else {
                    binding.btnPlay.setImageResource(R.drawable.ic_play)
                    binding.btnPlay.contentDescription = getString(R.string.btn_start_mock)
                    val msg = engine.state.value.message
                    if (msg != null && msg != "Mocking stopped") {
                        binding.tvStatus.text = msg
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_warn))
                        binding.tvStatus.visibility = View.VISIBLE
                    } else {
                        binding.tvStatus.visibility = View.GONE
                    }
                }
                refreshLatLngOverlay()
            }
        }

        // Poll service status periodically to surface any errors
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(3_000)
                val st = MockLocationService.snapshotStatus()
                if (!st.running) {
                    // Show last error if we had one
                    val err = st.lastError
                    if (err != null && binding.btnPlay.contentDescription == getString(R.string.btn_stop_mock)) {
                        binding.tvStatus.text = "Mock error: $err"
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_warn))
                        binding.tvStatus.visibility = View.VISIBLE
                    }
                } else if (!st.providerReady) {
                    binding.tvStatus.text = "Provider not registered — check Developer Options"
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_warn))
                    binding.tvStatus.visibility = View.VISIBLE
                }
            }
        }
    }

    // ---------------- map helpers ----------------

    private fun placeMarkerAt(point: GeoPoint, animate: Boolean) {
        if (marker == null) {
            marker = Marker(binding.mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Mocked location"
            }
            binding.mapView.overlays.add(marker)
        }
        marker?.position = point
        binding.mapView.invalidate()
        if (animate) binding.mapView.controller.animateTo(point)
    }

    private fun refreshLatLngOverlay() {
        val s = engine.state.value
        val lat = if (s.isRunning) s.latitude else (marker?.position?.latitude ?: DEFAULT_LAT)
        val lng = if (s.isRunning) s.longitude else (marker?.position?.longitude ?: DEFAULT_LNG)
        binding.tvLatLng.text = getString(R.string.label_lat_lng, lat, lng)
    }

    private fun maybeUpdateRunningLocation(lat: Double, lng: Double) {
        if (MockLocationService.running.value) {
            // Restart the service with new coordinates
            MockLocationService.start(applicationContext, lat, lng)
        }
        saveLastCoords(lat, lng, MockLocationEngine.DEFAULT_ACCURACY_M)
    }

    private fun saveLastCoords(lat: Double, lng: Double, accuracyM: Float) {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString(PREF_KEY_LAST_LAT, lat.toString())
            .putString(PREF_KEY_LAST_LNG, lng.toString())
            .putString(PREF_KEY_LAST_ACC, accuracyM.toString())
            .apply()
    }

    // ---------------- permission flow ----------------

    private fun ensureLocationPermissionThenStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startMockFromUi()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun startMockFromUi() {
        val pt = marker?.position ?: GeoPoint(DEFAULT_LAT, DEFAULT_LNG)
        // Ask user to disable battery optimization so OEM savers don't kill us
        requestIgnoreBatteryOptimization()
        // Delegate to the foreground service so the mock location keeps running
        // even when the app is backgrounded.
        MockLocationService.start(applicationContext, pt.latitude, pt.longitude)
        Toast.makeText(this, R.string.mock_started, Toast.LENGTH_SHORT).show()
        // Save last coords for next session
        saveLastCoords(pt.latitude, pt.longitude, MockLocationEngine.DEFAULT_ACCURACY_M)
    }

    private fun showPermissionDeniedHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.status_permission_required)
            .setMessage("SP needs location permission to inject mock GPS coordinates.")
            .setPositiveButton(R.string.btn_open_settings) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openDeveloperOptions() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (e: Throwable) {
            Log.w(TAG, "no dev settings", e)
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /**
     * Request the user to disable battery optimization for SP. Without this, OEMs
     * like Xiaomi/Huawei/OPPO will kill the foreground service after a few minutes.
     */
    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm == null) return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return  // already granted

        try {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = android.net.Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (e: Throwable) {
            Log.w(TAG, "could not request battery optimization exemption", e)
            // Fallback to general battery settings
            try {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Throwable) {
                Log.w(TAG, "battery optimization settings not available", e2)
            }
        }
    }

    // ---------------- drawer actions ----------------

    private fun showChangeViewDialog() {
        val items = arrayOf(
            getString(R.string.view_standard),
            getString(R.string.view_satellite),
            getString(R.string.view_hybrid)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_change_view)
            .setItems(items) { _, which ->
                binding.mapView.setTileSource(
                    when (which) {
                        0 -> TileSourceFactory.MAPNIK
                        1 -> TileSourceFactory.MAPNIK
                        else -> TileSourceFactory.MAPNIK
                    }
                )
            }
            .show()
    }

    private fun showSearchDialog() {
        val dialogBinding = DialogSearchBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.menu_search)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        val adapter = SearchResultsAdapter { result ->
            val p = GeoPoint(result.latitude, result.longitude)
            binding.mapView.controller.setZoom(15.0)
            binding.mapView.controller.setCenter(p)
            placeMarkerAt(p, animate = true)
            maybeUpdateRunningLocation(result.latitude, result.longitude)
            dialog.dismiss()
        }
        dialogBinding.rvResults.layoutManager = LinearLayoutManager(this)
        dialogBinding.rvResults.adapter = adapter

        dialogBinding.etSearch.setOnEditorActionListener { v, _, _ ->
            val q = v.text.toString()
            performSearch(q, adapter, dialogBinding)
            true
        }
        dialog.show()
    }

    private fun performSearch(query: String, adapter: SearchResultsAdapter, view: DialogSearchBinding) {
        searchJob?.cancel()
        if (query.isBlank()) return
        view.searchProgress.visibility = View.VISIBLE
        view.rvResults.visibility = View.GONE
        searchJob = lifecycleScope.launch {
            val results = nominatim.searchMulti(query)
            adapter.submit(results)
            view.searchProgress.visibility = View.GONE
            view.rvResults.visibility = if (results.isEmpty()) View.GONE else View.VISIBLE
            if (results.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.search_no_results, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareCurrentLocation() {
        val pt = marker?.position ?: GeoPoint(DEFAULT_LAT, DEFAULT_LNG)
        val text = "https://www.openstreetmap.org/?mlat=${pt.latitude}&mlon=${pt.longitude}#map=15/${pt.latitude}/${pt.longitude}"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.menu_share)))
    }

    private fun showDetectMockHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_detect_mock)
            .setMessage("""
                Many apps detect mock locations. Here's what they check:

                • Location.isFromMockProvider() / Location.isMock() — this is set automatically by Android for any test provider added via addTestProvider. There is no way to bypass this from a non-rooted app.

                • Cross-check with network (WiFi/cell) — if your mock location doesn't match the IP-based geolocation, the app may block.

                • Play Integrity API — server-side check that flags rooted devices and custom ROMs.

                What SP does to look natural:
                • Adds small random jitter (~10m) to coordinates
                • Varies accuracy, bearing, speed, altitude each tick
                • Non-uniform tick intervals (real GPS isn't perfectly regular)

                Bypasses that need root:
                • Magisk + Mock Mock Locations module
                • Xposed MockLocationRemover
                • Custom ROM with location spoofing disabled

                For business-critical apps like banking or payment, even rooted devices get flagged.
            """.trimIndent())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_help)
            .setMessage("""
                1) Tap Developer Options > Select mock location app > SP
                2) Long-press the map to drop a pin, or use Search to find a place
                3) Tap the blue Play button (or use the toolbar Play icon) to start mocking
                4) Open any map app and your location will appear at the pinned coordinate

                The mock keeps running in the background even when you leave the app —
                tap Stop (square icon) to release the mock and restore real GPS.
            """.trimIndent())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show() }
    }

    // ---------------- offline map cache ----------------

    /**
     * Convert a lat/lon to tile X/Y at the given zoom using the slippy map convention.
     */
    private fun lonToTileX(lon: Double, z: Int): Int =
        ((lon + 180.0) / 360.0 * (1 shl z)).toInt()

    private fun latToTileY(lat: Double, z: Int): Int {
        val latRad = Math.toRadians(lat)
        val n = 1 shl z
        return ((1.0 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2.0 * n).toInt()
    }

    /**
     * Pre-download tiles for the currently visible map area so the user can view
     * the map offline. Iterates over a small range of zooms around the current
     * zoom level (e.g. current-1 to current+1).
     */
    private fun downloadOfflineCache() {
        val bb = binding.mapView.boundingBox
        val centerZoom = binding.mapView.zoomLevelDouble.toInt()
        val zoomMin = (centerZoom - 1).coerceAtLeast(8)
        val zoomMax = (centerZoom + 1).coerceAtMost(18)

        val tileProvider = binding.mapView.tileProvider

        val dialog = AlertDialog.Builder(this)
            .setTitle("Save map for offline")
            .setMessage("Downloading tiles zoom $zoomMin..$zoomMax for the current view…\nThis may take a moment.")
            .setCancelable(false)
            .create()
        dialog.show()

        lifecycleScope.launch {
            var count = 0
            for (z in zoomMin..zoomMax) {
                val xMin = lonToTileX(bb.lonWest, z)
                val xMax = lonToTileX(bb.lonEast, z)
                // Y goes north->south in slippy maps
                val yMin = latToTileY(bb.latNorth, z)
                val yMax = latToTileY(bb.latSouth, z)
                for (x in xMin..xMax) {
                    for (y in yMin..yMax) {
                        val tileIndex = MapTileIndex.getTileIndex(z, x, y)
                        runCatching { tileProvider.getMapTile(tileIndex) }
                        count++
                    }
                }
            }
            dialog.dismiss()
            val msg = if (count > 0) "Saved $count tiles for offline use" else "No tiles saved"
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
        }
    }

    // ---------------- saved locations ----------------

    private fun showSavedLocationsSheet() {
        val view = SheetSavedLocationsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view.root)

        // Declare adapter reference up-front so the onDelete closure can capture it
        var adapter: SavedLocationsAdapter? = null
        adapter = SavedLocationsAdapter(
            onLoad = { saved ->
                val p = GeoPoint(saved.latitude, saved.longitude)
                binding.mapView.controller.setZoom(15.0)
                binding.mapView.controller.setCenter(p)
                placeMarkerAt(p, animate = true)
                maybeUpdateRunningLocation(saved.latitude, saved.longitude)
                dialog.dismiss()
            },
            onDelete = { saved ->
                savedLocations.delete(saved.name)
                Toast.makeText(this, getString(R.string.saved_location_deleted, saved.name), Toast.LENGTH_SHORT).show()
                adapter?.let { refreshSavedList(view, it) }
            }
        )
        view.rvSavedLocations.layoutManager = LinearLayoutManager(this)
        view.rvSavedLocations.adapter = adapter
        refreshSavedList(view, adapter)

        view.btnSaveCurrent.setOnClickListener {
            val pt = marker?.position ?: GeoPoint(DEFAULT_LAT, DEFAULT_LNG)
            val name = view.etSavedName.text.toString().trim()
            if (name.isBlank()) {
                view.etSavedName.error = "Required"
                return@setOnClickListener
            }
            val ok = savedLocations.save(name, pt.latitude, pt.longitude)
            if (!ok) {
                Snackbar.make(view.root, "Invalid location", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            view.etSavedName.text?.clear()
            Toast.makeText(this, getString(R.string.saved_location_saved, name), Toast.LENGTH_SHORT).show()
            refreshSavedList(view, adapter)
        }

        dialog.show()
    }

    private fun refreshSavedList(view: SheetSavedLocationsBinding, adapter: SavedLocationsAdapter) {
        val items = savedLocations.all()
        adapter.submit(items)
        view.tvSavedEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateStatusBanner() {
        // Note: we no longer show "Mock location app not set" because the
        // Settings.Secure.ALLOW_MOCK_LOCATION API was removed in API 18+ and
        // always returns empty now, so this check is unreliable on modern Android.
        // Instead, we trust the service state — if the service is running and the
        // provider is registered, the mock is set up correctly.
        // We show a banner hint only on first launch to remind users about
        // Developer Options.
        if (!MockLocationService.running.value) {
            binding.tvStatus.visibility = View.VISIBLE
            binding.tvStatus.text = "Tap Play to start. Make sure SP is selected in Developer Options → Select mock location app."
            binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_warn))
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        updateStatusBanner()
        refreshLatLngOverlay()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroy() {
        engine.dispose()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    // ---- inner adapters ----

    private class SearchResultsAdapter(
        private val onPick: (NominatimService.Result) -> Unit
    ) : RecyclerView.Adapter<SearchResultsAdapter.VH>() {

        private val items = mutableListOf<NominatimService.Result>()

        fun submit(list: List<NominatimService.Result>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val b = ItemSearchResultBinding.inflate(
                android.view.LayoutInflater.from(parent.context), parent, false
            )
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount(): Int = items.size

        inner class VH(private val b: ItemSearchResultBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(r: NominatimService.Result) {
                b.tvResultName.text = r.displayName.split(",").firstOrNull() ?: r.displayName
                b.tvResultDetail.text = "%.5f, %.5f".format(r.latitude, r.longitude)
                b.root.setOnClickListener { onPick(r) }
            }
        }
    }

    private class SavedLocationsAdapter(
        private val onLoad: (SavedLocations.SavedLocation) -> Unit,
        private val onDelete: (SavedLocations.SavedLocation) -> Unit
    ) : RecyclerView.Adapter<SavedLocationsAdapter.VH>() {

        private val items = mutableListOf<SavedLocations.SavedLocation>()

        fun submit(list: List<SavedLocations.SavedLocation>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val b = ItemSavedLocationBinding.inflate(
                android.view.LayoutInflater.from(parent.context), parent, false
            )
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount(): Int = items.size

        inner class VH(private val b: ItemSavedLocationBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(s: SavedLocations.SavedLocation) {
                b.tvSavedName.text = s.name
                b.tvSavedCoords.text = "%.6f, %.6f".format(s.latitude, s.longitude)
                b.btnLoadSaved.setOnClickListener { onLoad(s) }
                b.btnDeleteSaved.setOnClickListener { onDelete(s) }
            }
        }
    }
}