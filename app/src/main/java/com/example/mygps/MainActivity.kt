package com.example.mygps

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
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

        // osmdroid requires a User-Agent (app policy) and cache path
        Configuration.getInstance().apply {
            userAgentValue = packageName
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
            setTileSource(TileSourceFactory.MAPNIK)
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
            val running = engine.state.value.isRunning
            if (running) {
                engine.stop()
                binding.btnPlay.setImageResource(R.drawable.ic_play)
                binding.btnPlay.contentDescription = getString(R.string.btn_start_mock)
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
                    if (!engine.state.value.isRunning) ensureLocationPermissionThenStart()
                    true
                }
                R.id.action_stop -> {
                    if (engine.state.value.isRunning) {
                        engine.stop()
                        binding.btnPlay.setImageResource(R.drawable.ic_play)
                        Toast.makeText(this, R.string.mock_stopped, Toast.LENGTH_SHORT).show()
                    }
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
                R.id.nav_go_pro -> Toast.makeText(this, "mygps Pro — coming soon", Toast.LENGTH_SHORT).show()
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
            engine.state.collect { s ->
                if (s.isRunning) {
                    binding.btnPlay.setImageResource(R.drawable.ic_stop)
                    binding.btnPlay.contentDescription = getString(R.string.btn_stop_mock)
                    binding.tvStatus.visibility = View.VISIBLE
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_ok))
                    binding.tvStatus.text = getString(R.string.status_running, s.latitude, s.longitude)
                } else {
                    binding.btnPlay.setImageResource(R.drawable.ic_play)
                    binding.btnPlay.contentDescription = getString(R.string.btn_start_mock)
                    if (s.message != null && s.message != "Mocking stopped") {
                        binding.tvStatus.text = s.message
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_warn))
                        binding.tvStatus.visibility = View.VISIBLE
                    } else {
                        binding.tvStatus.visibility = View.GONE
                    }
                }
                if (s.isRunning) {
                    refreshLatLngOverlay()
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
        if (engine.state.value.isRunning) {
            engine.updateLocation(lat, lng)
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
        val result = engine.start(pt.latitude, pt.longitude)
        when (result) {
            MockLocationEngine.StartResult.OK -> {
                Toast.makeText(this, R.string.mock_started, Toast.LENGTH_SHORT).show()
            }
            MockLocationEngine.StartResult.NO_LOCATION_PERMISSION ->
                showPermissionDeniedHelp()
            MockLocationEngine.StartResult.SECURITY_EXCEPTION,
            MockLocationEngine.StartResult.PROVIDER_ALREADY_PRESENT -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.status_dev_options_required)
                    .setMessage(R.string.status_no_mock_app)
                    .setPositiveButton(R.string.btn_open_settings) { _, _ ->
                        openDeveloperOptions()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            MockLocationEngine.StartResult.UNKNOWN_ERROR -> {
                val msg = engine.state.value.message ?: getString(R.string.mock_failed, "Unknown")
                Snackbar.make(binding.root, getString(R.string.mock_failed, msg), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showPermissionDeniedHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.status_permission_required)
            .setMessage("mygps needs location permission to inject mock GPS coordinates.")
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
            .setMessage("Some apps call Location.isFromMockProvider() or use Play Integrity API to detect mock locations. mygps sets the test provider cleanly, but it cannot bypass server-side checks like Snap, Pokémon GO, or banking apps.")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_help)
            .setMessage("""
                1) Tap Developer Options > Select mock location app > mygps
                2) Long-press the map to drop a pin, or use Search to find a place
                3) Tap the blue Play button (or use the toolbar Play icon) to start mocking
                4) Open any map app and your location will appear at the pinned coordinate

                Tap Stop (square icon) to release the mock and restore real GPS.
            """.trimIndent())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show() }
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
        val isMockApp = isMockLocationApp()
        if (!isMockApp) {
            binding.tvStatus.visibility = View.VISIBLE
            binding.tvStatus.text = getString(R.string.status_no_mock_app)
        }
    }

    /**
     * Modern way: Android 6+ removed `Settings.Secure.ALLOW_MOCK_LOCATION` exposure,
     * so we just guide the user to Developer Options instead.
     */
    private fun isMockLocationApp(): Boolean {
        @Suppress("DEPRECATION")
        val legacy = Settings.Secure.getString(contentResolver, Settings.Secure.ALLOW_MOCK_LOCATION)
        return !legacy.isNullOrEmpty() && legacy == packageName
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