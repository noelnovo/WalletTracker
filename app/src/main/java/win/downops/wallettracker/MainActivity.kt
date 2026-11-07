package win.downops.wallettracker

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import com.google.android.material.navigation.NavigationView
import androidx.navigation.NavOptions
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import androidx.drawerlayout.widget.DrawerLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import jakarta.inject.Inject
import win.downops.wallettracker.data.ExpenseRepository
import win.downops.wallettracker.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import win.downops.wallettracker.data.models.AppResult
import win.downops.wallettracker.data.SessionRepository
import win.downops.wallettracker.data.LoginRepository
import win.downops.wallettracker.ui.login.LoginActivity
import win.downops.wallettracker.util.AppResultHandler
import win.downops.wallettracker.util.Logger

@AndroidEntryPoint
class MainActivity  : AppCompatActivity() {
    @Inject
    lateinit var expenseRepo: ExpenseRepository
    @Inject
    lateinit var sessionRepo: SessionRepository
    @Inject
    lateinit var loginRepo: LoginRepository
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        try{
            super.onCreate(savedInstanceState)

            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setSupportActionBar(binding.appBarMain.toolbar)

            val drawerLayout: DrawerLayout = binding.drawerLayout
            val navView: NavigationView = binding.navView
            val navController = findNavController(R.id.nav_host_fragment_content_main)

            appBarConfiguration = AppBarConfiguration(
                setOf(
                    R.id.nav_categories,
                    R.id.nav_importes,
                    R.id.nav_metrics,
                    R.id.nav_settings,
                    R.id.nav_logout
                ), drawerLayout
            )
            setupActionBarWithNavController(navController, appBarConfiguration)
            navView.setupWithNavController(navController)
            navView.setNavigationItemSelectedListener { item ->
                try {
                    if (item.itemId == R.id.nav_categories) {
                        navController.popBackStack(R.id.nav_categories, false)
                    } else if (item.itemId == R.id.nav_logout) {
                        doLogOut()
                    } else {
                        NavigationUI.onNavDestinationSelected(item, navController)
                    }
                } catch (e: Exception) {
                    Logger.log(e)
                }
                binding.drawerLayout.closeDrawers()
                true
            }

            val headerView = navView.getHeaderView(0)
            val lblUsername = headerView.findViewById<android.widget.TextView>(R.id.lblUsername)
            val session = sessionRepo.getFirstSession()
            if (session != null && session.username.isNotEmpty()) {
                lblUsername.text = session.username
            }

        }catch(e: Exception){
            Logger.log(e)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.resetExpenses -> {

                lifecycleScope.launch {
                    resetExpenses()
                }
                return true
            }
            R.id.logOff -> {
                doLogOut()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun resetExpenses() {
        try{

            when (val result = expenseRepo.deleteAll()) {
                is AppResult.Success<*> -> {
                    Toast.makeText(this, "Expenses reset successfully", Toast.LENGTH_SHORT).show()
                }
                is AppResult.Error -> {
                    AppResultHandler.handleError(this, result)
                }
            }
        }catch(e: Exception){
            Logger.log(e)
        }
    }

    fun doLogOut() {
        lifecycleScope.launch {
            try {
                val session = sessionRepo.getFirstSession()
                if (session != null && session.token.isNotEmpty()) {
                    loginRepo.logout(session.token)
                }
                sessionRepo.deleteAll()
                val intent = Intent(this@MainActivity, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                Logger.log(e)
            }
        }
    }
}
