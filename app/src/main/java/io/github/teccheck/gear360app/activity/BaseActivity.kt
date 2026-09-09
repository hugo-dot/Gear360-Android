package io.github.teccheck.gear360app.activity

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.teccheck.gear360app.service.ConnectionState
import io.github.teccheck.gear360app.service.Gear360Service

abstract class BaseActivity : AppCompatActivity() {

    protected var gear360Service: Gear360Service? = null
    private var gearServiceBound = false

    private val gearServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, service: IBinder) {
            gearServiceBound = true
            gear360Service = (service as Gear360Service.LocalBinder).getService()
            gear360Service?.connectionState?.observe(
                this@BaseActivity,
                this@BaseActivity::onConnectionStateChanged
            )
            onGearServiceConnected()
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            gearServiceBound = false
            gear360Service = null
            onGearServiceDisconnected()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    protected open fun onGearServiceConnected() {}
    protected open fun onGearServiceDisconnected() {}
    protected open fun onConnectionStateChanged(state: ConnectionState) {}

    protected fun startGear360Service(): Boolean {
        if (gearServiceBound) return true
        val intent = Intent(this, Gear360Service::class.java)
        return bindService(intent, gearServiceConnection, BIND_AUTO_CREATE).also {
            gearServiceBound = it
        }
    }

    protected fun setupBackButton() {
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home)
            onBackPressedDispatcher.onBackPressed()

        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        if (gearServiceBound) {
            unbindService(gearServiceConnection)
            gearServiceBound = false
            gear360Service = null
        }
        super.onDestroy()
    }
}
