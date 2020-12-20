package com.example.weatherapp.activities

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.weatherapp.constants.Constants
import com.example.weatherapp.R
import com.example.weatherapp.models.WeatherResponse
import com.example.weatherapp.network.WeatherService
import com.google.android.gms.location.*
import com.google.gson.Gson
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import kotlinx.android.synthetic.main.activity_main.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    // A fused location client variable which is for the user to get the user's current location
    private lateinit var mFusedLocationClient: FusedLocationProviderClient

    private var mProgressDialog: Dialog? = null

    private var mLatitude: Double = 0.0
    private var mLongitude: Double = 0.0

    private lateinit var mSharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize the Fused location variable
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        mSharedPreferences = getSharedPreferences(Constants.PREFERENCE_NAME, Context.MODE_PRIVATE)

        if (!isLocationEnabled()) {
            Toast.makeText(this, "Your location provider is turned OFF. Kindly turn it on", Toast.LENGTH_SHORT).show()
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            startActivity(intent)
        } else {
            Dexter.withContext(this)
                    .withPermissions(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    .withListener(object : MultiplePermissionsListener {
                        override fun onPermissionsChecked(report: MultiplePermissionsReport?) {
                            if (report!!.areAllPermissionsGranted()) {
                               requestLocationData()
                            }

                            if (report!!.isAnyPermissionPermanentlyDenied) {
                                Toast.makeText(this@MainActivity, "You have denied permission. The app will not work without it!", Toast.LENGTH_SHORT).show()
                            }
                        }

                        override fun onPermissionRationaleShouldBeShown(permissions: MutableList<PermissionRequest>?, token: PermissionToken?) {
                            showRationalDialogForPermissions()
                        }
                    }).onSameThread().check()
        }
    }

    //function used to show the alert dialog when the permissions are denied and need to allow it from settings app info.
    private fun showRationalDialogForPermissions() {
        AlertDialog.Builder(this)
                .setMessage("It Looks like you have turned off permissions required for this feature. It can be enabled under Application Settings")
                .setPositiveButton(
                        "GO TO SETTINGS"
                ) { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        val uri = Uri.fromParts("package", packageName, null)
                        intent.data = uri
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        e.printStackTrace()
                    }
                }
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss()
                }.show()
    }

    //A function to request the current location. Using the fused location provider client.
    @SuppressLint("MissingPermission")
    private fun requestLocationData() {

        val mLocationRequest = LocationRequest()
        mLocationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        mLocationRequest.interval = 0
        mLocationRequest.fastestInterval = 0
        mLocationRequest.numUpdates = 1

        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        mFusedLocationClient.requestLocationUpdates(
                mLocationRequest, mLocationCallback,
                Looper.myLooper()
        )
    }

    //A location callback object of fused location provider client where we will get the current location details.
    private val mLocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val mLastLocation: Location = locationResult.lastLocation

            mLatitude = mLastLocation.latitude
            Log.i("Current Latitude", "$mLatitude")

            mLongitude = mLastLocation.longitude
            Log.i("Current Longitude", "$mLongitude")

            getLocationWeatherDetails(mLatitude, mLongitude)
        }
    }

    private fun getLocationWeatherDetails(latitude: Double, longitude: Double) {
        if (Constants.isNetworkAvailable(this)) {
            val retrofit: Retrofit = Retrofit.Builder().baseUrl(Constants.BASE_URL).addConverterFactory(GsonConverterFactory.create()).build()

            val service: WeatherService = retrofit.create(WeatherService::class.java)

            val listCall: Call<WeatherResponse> = service.getWeather(latitude, longitude,
                Constants.METRIC_UNIT,
                Constants.APP_ID
            )

            showProgressDialog()

            listCall.enqueue(object : Callback<WeatherResponse>{
                override fun onResponse(
                    call: Call<WeatherResponse>,
                    response: Response<WeatherResponse>
                ) {
                    if (response.isSuccessful) {
                        hideProgressDialog()

                        val weatherList: WeatherResponse? = response.body()
                        Log.i("Response Result", "$weatherList")

                        // Here we have converted the model class in to Json String to store it in the SharedPreferences.
                        val weatherResponseJsonString = Gson().toJson(weatherList)
                        // Save the converted string to shared preferences
                        val editor = mSharedPreferences.edit()
                        editor.putString(Constants.WEATHER_RESPONSE_DATA, weatherResponseJsonString)
                        editor.apply()

                        setupUI()
                    } else {
                        val rc = response.code()
                        when (rc) {
                            400 -> Log.e("Error 400", "Bad connection")
                            404 -> Log.e("Error 404", "Not Found")
                            else -> Log.e("Error", "Error")
                        }
                    }
                }

                override fun onFailure(call: Call<WeatherResponse>, t: Throwable) {
                    hideProgressDialog()
                    Log.e("Error onFailure", t.message.toString())
                }

            })
        } else {
            Toast.makeText(this@MainActivity, "No Internet connection available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isLocationEnabled(): Boolean {
        //provides access to system location services
        val locationManager: LocationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun showProgressDialog() {
        mProgressDialog = Dialog(this)

        mProgressDialog!!.setContentView(R.layout.dialog_custom_progress)

        mProgressDialog!!.show()
    }

    private fun hideProgressDialog() {
        if (mProgressDialog != null) {
            mProgressDialog!!.dismiss()
        }
    }

    //Function is used to set the result in the UI elements.
    private fun setupUI() {
        // Here we have got the latest stored response from the SharedPreference and converted back to the data model object.
        val weatherResponseJsonString = mSharedPreferences.getString(Constants.WEATHER_RESPONSE_DATA, "")

        if (!weatherResponseJsonString.isNullOrEmpty()) {

            val weatherList = Gson().fromJson(weatherResponseJsonString, WeatherResponse::class.java)

            // For loop to get the required data. And all are populated in the UI.
            for (z in weatherList.weather.indices) {
                Log.i("Weather Name", weatherList.weather[z].main)

                tv_main.text = weatherList.weather[z].main
                tv_main_description.text = weatherList.weather[z].description
                tv_temp.text = weatherList.main.temp.toString() + getUnit(application.resources.configuration.locales.toString())
                tv_humidity.text = weatherList.main.humidity.toString() + "%"
                tv_min.text = weatherList.main.temp_min.toString() + " min"
                tv_max.text = weatherList.main.temp_max.toString() + " max"
                tv_speed.text = weatherList.wind.speed.toString()
                tv_name.text = weatherList.name
                tv_country.text = weatherList.sys.country
                tv_sunrise_time.text = unixTime(weatherList.sys.sunrise)
                tv_sunset_time.text = unixTime(weatherList.sys.sunset)

                // Here we update the main icon
                when (weatherList.weather[z].icon) {
                    "01d" -> iv_main.setImageResource(R.drawable.sunny)
                    "02d" -> iv_main.setImageResource(R.drawable.cloud)
                    "03d" -> iv_main.setImageResource(R.drawable.cloud)
                    "04d" -> iv_main.setImageResource(R.drawable.cloud)
                    "04n" -> iv_main.setImageResource(R.drawable.cloud)
                    "10d" -> iv_main.setImageResource(R.drawable.rain)
                    "11d" -> iv_main.setImageResource(R.drawable.storm)
                    "13d" -> iv_main.setImageResource(R.drawable.snowflake)
                    "01n" -> iv_main.setImageResource(R.drawable.cloud)
                    "02n" -> iv_main.setImageResource(R.drawable.cloud)
                    "03n" -> iv_main.setImageResource(R.drawable.cloud)
                    "10n" -> iv_main.setImageResource(R.drawable.cloud)
                    "11n" -> iv_main.setImageResource(R.drawable.rain)
                    "13n" -> iv_main.setImageResource(R.drawable.snowflake)
                }
            }
        }
    }

    private fun getUnit(value: String): String? {
        Log.i("Temperature Unit", value)
        var value = " °C"
        if ("US" == value || "LR" == value || "MM" == value) {
            value = " °F"
        }
        return value
    }

    //This function is used to get the formatted time based on the Format and the LOCALE we pass to it.
    private fun unixTime(timex: Long): String? {
        val date = Date(timex * 1000L)

        @SuppressLint("SimpleDateFormat")
        val sdf = SimpleDateFormat("HH:mm")

        sdf.timeZone = TimeZone.getDefault()

        return sdf.format(date)
    }

    //Inflate Menu
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.manu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                requestLocationData()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}